package lang.temper.be.piet

import lang.temper.be.piet.PietOp.Add
import lang.temper.be.piet.PietOp.Divide
import lang.temper.be.piet.PietOp.Duplicate
import lang.temper.be.piet.PietOp.Greater
import lang.temper.be.piet.PietOp.Mod
import lang.temper.be.piet.PietOp.Multiply
import lang.temper.be.piet.PietOp.Not
import lang.temper.be.piet.PietOp.Pop
import lang.temper.be.piet.PietOp.Subtract
import lang.temper.be.tmpl.TmpL
import lang.temper.be.tmpl.TmpLOperator
import lang.temper.name.ResolvedName
import lang.temper.type.TypeDefinition
import lang.temper.type.WellKnownTypes
import lang.temper.type.isVoidLike
import lang.temper.type2.Signature2
import lang.temper.value.TBoolean
import lang.temper.value.TInt
import lang.temper.value.TNull
import lang.temper.value.TString
import lang.temper.value.TVoid

/**
 * Translates TmpL to an [AsmProgram].
 *
 * Every value is one stack cell: an Int is itself, a Boolean is 0 or 1, and a
 * String is a heap address (see [Runtime]). Locals live in fixed frame slots,
 * allocated for the whole function on entry, so at statement boundaries the
 * frame's depth is always the same and jumps between statements never need to
 * adjust the stack.
 */
class PietTranslator(private val modules: List<TmpL.Module>) {
    private val program = AsmProgram()
    private val rt = Runtime(program)

    private val globals = mutableMapOf<ResolvedName, Int>()
    private val functions = mutableMapOf<ResolvedName, FnInfo>()
    private val builtins = mutableMapOf<ResolvedName, PietBuiltin>()

    private class FnInfo(
        val name: String,
        val parameters: List<TmpL.Formal>,
        val body: TmpL.BlockStatement?,
        val returnsValue: Boolean,
        /** For a local function: what it captures, in environment order, and its own name. */
        val captured: List<ResolvedName>? = null,
        val selfName: ResolvedName? = null,
    ) {
        val label = Label(name)
        val isClosure get() = captured != null
    }

    /** Local functions met while translating, translated after everything else. */
    private val pendingClosures = ArrayDeque<FnInfo>()

    /** Adapters that let a module function be called as a function value. */
    private val adapters = mutableMapOf<ResolvedName, Label>()

    /**
     * Locals that a local function captures and that something assigns.
     * Each lives in a one-cell heap box so the closure and its maker share it;
     * the slot holds the box's address.
     */
    private val boxed = mutableSetOf<ResolvedName>()

    /**
     * Local functions some closure captures. Their boxes exist from function
     * entry, because a closure can capture a sibling declared after it:
     *
     *     let walker(i: Int): Void { ... putter(i) ... }
     *     let putter(i: Int): Void { ... walker(i + 1) ... }
     *
     * walker's block is built before putter's exists. Copying putter's slot
     * would copy 0, and calling it would jump to whatever row h(0) holds.
     */
    private val boxedFromEntry = mutableSetOf<ResolvedName>()

    /** What each local function captures. */
    private val captures = mutableMapOf<ResolvedName, List<ResolvedName>>()

    /**
     * Works out [captures] and [boxed]. A local function captures every name
     * it mentions that some other function declares as a local. Globals and
     * module functions are reachable without capture.
     */
    private fun analyzeClosures(topLevels: List<TmpL.TopLevel>) {
        val localDecls = mutableMapOf<ResolvedName, Boolean>() // name -> mutable
        val localFunctions = mutableListOf<TmpL.LocalFunctionDeclaration>()
        val mentionsSelf = mutableMapOf<ResolvedName, Boolean>()
        fun walk(node: TmpL.Tree) {
            when (node) {
                is TmpL.LocalDeclaration -> localDecls[node.name.name] = !node.assignOnce
                is TmpL.Formal -> localDecls[node.name.name] = !node.assignOnce
                is TmpL.LocalFunctionDeclaration -> {
                    localDecls[node.name.name] = false
                    localFunctions.add(node)
                }
                else -> {}
            }
            for (i in 0 until node.childCount) node.childOrNull(i)?.let { walk(it) }
        }
        topLevels.forEach { walk(it) }
        for (fn in localFunctions) {
            val inside = mutableSetOf<ResolvedName>()
            val mentioned = linkedSetOf<ResolvedName>()
            fun collect(node: TmpL.Tree) {
                when (node) {
                    is TmpL.LocalDeclaration -> inside.add(node.name.name)
                    is TmpL.Formal -> inside.add(node.name.name)
                    is TmpL.LocalFunctionDeclaration -> inside.add(node.name.name)
                    is TmpL.Id -> (node.nameContent as? lang.temper.common.Either.Left)?.let {
                        mentioned.add(it.item)
                    }
                    else -> {}
                }
                for (i in 0 until node.childCount) node.childOrNull(i)?.let { collect(it) }
            }
            fn.parameters.parameters.forEach { collect(it) }
            collect(fn.body)
            val captured = mentioned.filter { it in localDecls && it !in inside && it != fn.name.name }
            captures[fn.name.name] = captured
            mentionsSelf[fn.name.name] = fn.name.name in mentioned
            boxed.addAll(captured.filter { localDecls[it] == true })
        }
        val functionNames = localFunctions.map { it.name.name }.toSet()
        for (captured in captures.values) {
            for (name in captured) {
                if (name in functionNames) {
                    boxed.add(name)
                    boxedFromEntry.add(name)
                }
            }
        }
        // A boxed function that names itself reaches itself through its box
        // too, so that its own closures, which capture the box, agree with it.
        for ((name, captured) in captures) {
            if (name in boxedFromEntry && mentionsSelf[name] == true) captures[name] = captured + name
        }
    }

    private fun adapterFor(name: ResolvedName): Label = adapters.getOrPut(name) {
        val fn = functions.getValue(name)
        val n = fn.parameters.size
        // [ret, fail, D, closure, args...] -> drop the closure and go.
        val writer = FunctionWriter(program, "${fn.name}\$value", n + 1)
        writer.begin(0)
        writer.e.const(n + 1)
        writer.e.const(-1)
        writer.e.op(PietOp.Roll)
        writer.e.op(Pop)
        writer.e.jumpAway(fn.label)
        writer.entry
    }

    /**
     * A class or interface. An object is a heap block `classId, fields...`;
     * [own] holds the methods, getters and setters declared here, keyed by
     * [key].
     */
    private class TypeInfo(val decl: TmpL.TypeDeclaration, val classId: Int?) {
        val fieldCount get() = decl.members.count { it is TmpL.InstanceProperty }
        val own = mutableMapOf<String, FnInfo>()
        var constructor: FnInfo? = null
    }

    private val types = mutableMapOf<TypeDefinition, TypeInfo>()

    /** Heap offset of each backed property within its object. */
    private val fieldOffsets = mutableMapOf<ResolvedName, Int>()

    /** Method keys called through an interface, which need a dispatcher. */
    private val dispatchers = mutableMapOf<String, Label>()

    private fun fnInfo(name: ResolvedName, parameters: TmpL.Parameters, body: TmpL.BlockStatement?, sig: Signature2) =
        FnInfo(name.toString(), parameters.parameters, body, !sig.returnType2.isVoidLike)

    private fun registerType(decl: TmpL.TypeDeclaration) {
        val classId = if (decl.kind ==
            TmpL.TypeDeclarationKind.Class
        ) {
            types.values.count { it.classId != null } + 1
        } else {
            null
        }
        val info = TypeInfo(decl, classId)
        types[decl.typeShape] = info
        var offset = 1
        for (member in decl.members) {
            when (member) {
                is TmpL.InstanceProperty -> fieldOffsets[member.name.name] = offset++
                is TmpL.Constructor ->
                    info.constructor = fnInfo(member.name.name, member.parameters, member.body, member.sig)
                is TmpL.NormalMethod -> info.own["m:${member.dotName.dotNameText}"] =
                    fnInfo(member.name.name, member.parameters, member.body, member.sig)
                is TmpL.Getter -> info.own["g:${member.dotName.dotNameText}"] =
                    fnInfo(member.name.name, member.parameters, member.body, member.sig)
                is TmpL.Setter -> info.own["s:${member.dotName.dotNameText}"] =
                    fnInfo(member.name.name, member.parameters, member.body, member.sig)
                else -> TODO("${member::class.simpleName}: $member")
            }
        }
    }

    /**
     * The implementation of [key] a value of [info]'s type runs: its own, or
     * the first found searching super types breadth first, in the order they
     * are declared. Depth first is wrong:
     *
     *     interface A { a(): String { "A wins!" } }
     *     interface B extends A {}
     *     interface C extends A { a(): String { "C wins!" } }
     *     class D extends B & C {}
     *
     * D's a() is C's; depth first finds A's through B.
     */
    private fun resolve(info: TypeInfo, key: String): FnInfo? {
        var level = listOf(info)
        val seen = mutableSetOf<TypeInfo>()
        while (level.isNotEmpty()) {
            level.firstNotNullOfOrNull { it.own[key] }?.let { return it }
            seen.addAll(level)
            level = level.flatMap { t ->
                t.decl.superTypes.mapNotNull { types[it.typeName.sourceDefinition] }
            }.distinct().filter { it !in seen }
        }
        return null
    }

    private fun allFunctions(): List<FnInfo> =
        functions.values.toList() + types.values.flatMap { listOfNotNull(it.constructor) + it.own.values }

    fun translate(): DispatchLayout {
        val topLevels = modules.flatMap { it.topLevels }
        for (topLevel in topLevels) {
            when (topLevel) {
                is TmpL.SupportCodeDeclaration ->
                    builtins[topLevel.name.name] = topLevel.init.supportCode as? PietBuiltin ?: TODO("$topLevel")
                is TmpL.ModuleFunctionDeclaration -> functions[topLevel.name.name] =
                    fnInfo(topLevel.name.name, topLevel.parameters, topLevel.body, topLevel.sig)
                is TmpL.TypeDeclaration -> registerType(topLevel)
                is TmpL.ModuleLevelDeclaration -> globals[topLevel.name.name] = globals.size
                is TmpL.ModuleInitBlock -> {}
                else -> TODO("$topLevel")
            }
        }

        analyzeClosures(topLevels)
        val main = MainWriter(program, globals.size)
        val mainLocals = topLevels.filterIsInstance<TmpL.ModuleInitBlock>().flatMap { localsOf(it.body) }
        Scope(main.e, main.frame, writer = null, slotsFrom(mainLocals, 0)).apply {
            repeat(mainLocals.size) { e.const(0) }
            allocateEntryBoxes(mainLocals)
            for (topLevel in topLevels) {
                when (topLevel) {
                    is TmpL.ModuleLevelDeclaration -> topLevel.init?.let { init ->
                        if (value(init)) frame.storeGlobal(globals.getValue(topLevel.name.name))
                    }
                    is TmpL.ModuleInitBlock -> statement(topLevel.body)
                    else -> {}
                }
            }
            main.halt()
            finish()
        }

        for (fn in allFunctions()) translateFunction(fn)
        while (pendingClosures.isNotEmpty()) translateFunction(pendingClosures.removeFirst())
        // Translating can ask for more dispatchers only from function
        // bodies, which are all done by now.
        for ((key, label) in dispatchers) writeDispatcher(key, label)
        return program.link(main.entry)
    }

    /**
     * Jumps to the implementation of [key] for the class of `this`, with the
     * caller's frame left exactly as it was: the implementation takes the
     * same frame. The class id is h(this), and the row is a sum of
     * `(id == k) * (row_k - halt)` over every class that has [key], plus
     * halt, so an unknown class halts rather than running row 0.
     */
    private fun writeDispatcher(key: String, label: Label) {
        val impls = types.values.filter { it.classId != null }.mapNotNull { c ->
            resolve(c, key)?.let {
                c.classId!! to
                    it
            }
        }
        val paramCount = impls.map { it.second.parameters.size }.distinct().singleOrNull()
            ?: TODO("dispatch $key over ${impls.map { it.second.name }}")
        val writer = FunctionWriter(program, "dispatch_$key", paramCount, label)
        val e = writer.e
        writer.begin(0)
        writer.frame.load(writer.param(0))
        writer.frame.heapLoad()
        e.pushLabel(program.halt)
        for ((classId, impl) in impls) {
            e.pick(2)
            e.const(classId)
            e.op(Subtract)
            e.op(Not)
            e.pushLabel(impl.label)
            e.pushLabel(program.halt)
            e.op(Subtract)
            e.op(Multiply)
            e.op(Add)
        }
        e.dropUnder(1)
        e.jumpToTop()
    }

    private fun translateFunction(fn: FnInfo) {
        val params = fn.parameters
        // A closure's first parameter is itself: the block holding its row and environment.
        val envSlots = if (fn.isClosure) 1 else 0
        val writer = FunctionWriter(program, fn.label.hint, envSlots + params.size, fn.label)
        val body = fn.body ?: TODO("${fn.name} has no body")
        val locals = localsOf(body)
        writer.begin(locals.size)
        val slots = slotsFrom(params.map { it.name.name }, writer.param(envSlots)) +
            slotsFrom(locals, writer.param(envSlots + params.size))
        val env = if (fn.isClosure) writer.param(0) else null
        Scope(writer.e, writer.frame, writer, slots, fn.returnsValue, env, fn.captured.orEmpty(), fn.selfName).apply {
            allocateEntryBoxes(locals)
            // A boxed parameter moves into its box on entry.
            for (p in params) {
                val slot = slots.getValue(p.name.name)
                if (p.name.name in boxed) {
                    frame.load(slot)
                    frame.alloc(1)
                    frame.store(slot)
                }
            }
            statement(body)
            if (fn.returnsValue) {
                // Falling off the end of a function that returns a value does
                // not happen in TmpL; if it does, say so here.
                if (e.reachable) TODO("${fn.name} falls off its end")
            } else {
                writer.ret(false)
            }
            finish()
        }
    }

    private fun slotsFrom(names: List<ResolvedName>, first: Int) =
        names.withIndex().associate { (i, name) -> name to first + i }

    private fun localsOf(stmt: TmpL.Statement): List<ResolvedName> = buildList {
        fun walk(s: TmpL.Statement) {
            when (s) {
                is TmpL.LocalDeclaration -> add(s.name.name)
                is TmpL.LocalFunctionDeclaration -> add(s.name.name)
                is TmpL.NestingStatement -> s.nestedStatements.forEach { walk(it) }
                else -> {}
            }
        }
        walk(stmt)
    }

    /** Where `break` and `continue` go from inside one loop or labeled block. */
    private class JumpScope(val name: ResolvedName?, val breakTo: Label, val continueTo: Label?)

    /** Where a bubble goes: a catch at statement depth, or out of the function. */
    private class Handler(val catchTo: Label, val depth: Int)

    private inner class Scope(
        val e: Emitter,
        val frame: Frame,
        val writer: FunctionWriter?,
        val slots: Map<ResolvedName, Int>,
        val returnsValue: Boolean = false,
        /** The slot holding this closure's own block, if this is a closure. */
        val envSlot: Int? = null,
        captured: List<ResolvedName> = listOf(),
        val selfName: ResolvedName? = null,
    ) {
        /** Heap offset of each captured name within the closure's block. */
        private val envOffsets = captured.withIndex().associate { (i, name) -> name to i + 1 }

        fun allocateEntryBoxes(locals: List<ResolvedName>) {
            for (name in locals) {
                if (name in boxedFromEntry) {
                    e.const(0)
                    frame.alloc(1)
                    frame.store(slots.getValue(name))
                }
            }
        }

        /** Pushes what the variable's storage holds: a box's address if it is boxed. */
        fun loadRaw(name: ResolvedName) {
            val slot = slots[name]
            val offset = envOffsets[name]
            when {
                slot != null -> frame.load(slot)
                offset != null -> {
                    frame.load(envSlot!!)
                    frame.heapLoad(offset)
                }
                name == selfName -> frame.load(envSlot!!)
                else -> frame.loadGlobal(globals[name] ?: TODO("reference to $name"))
            }
        }

        fun load(name: ResolvedName) {
            val fn = functions[name]
            if (fn != null && name !in slots && name !in globals) {
                // A module function as a value: a block holding its adapter's row.
                e.pushLabel(adapterFor(name))
                frame.alloc(1)
                return
            }
            if (name == selfName && name !in slots && name !in envOffsets) {
                // A closure naming itself is its own block, never boxed.
                frame.load(envSlot!!)
                return
            }
            loadRaw(name)
            if (name in boxed) frame.heapLoad()
        }

        private val jumps = ArrayDeque<JumpScope>()
        private val handlers = ArrayDeque<Handler>()

        /** Failure landings, written once the body is done: label, depth, handler. */
        private val landings = mutableListOf<Triple<Label, Int, Handler?>>()

        private var labelCount = 0
        private fun label(hint: String) = Label("${writer?.name ?: "main"}.$hint${labelCount++}")

        /**
         * A row that something at the current [depth] can bubble to. It drops
         * whatever is above the handler's depth and goes there.
         */
        fun bubbleLanding(depth: Int = e.depth): Label {
            val landing = label("bubble")
            landings.add(Triple(landing, depth, handlers.lastOrNull()))
            return landing
        }

        /** Bubbles from here. */
        fun bubble() {
            if (!e.reachable) return
            e.jump(bubbleLanding())
        }

        fun finish() {
            check(!e.reachable)
            for ((landing, depth, handler) in landings) {
                e.startBlock(landing, depth)
                when {
                    handler != null -> {
                        repeat(depth - handler.depth) { e.op(Pop) }
                        e.jump(handler.catchTo)
                    }
                    writer != null -> writer.fail()
                    // Uncaught at the top level: the module failed to load.
                    else -> e.jumpAway(program.halt)
                }
            }
        }

        // Statements

        fun statement(stmt: TmpL.Statement) {
            when (stmt) {
                is TmpL.BlockStatement -> stmt.statements.forEach { statement(it) }
                is TmpL.ExpressionStatement -> if (value(stmt.expression)) e.op(Pop)
                is TmpL.LocalDeclaration -> {
                    val slot = slots.getValue(stmt.name.name)
                    if (stmt.name.name in boxed) {
                        val init = stmt.init
                        if (init == null || !value(init)) e.const(0)
                        frame.alloc(1)
                        frame.store(slot)
                    } else {
                        stmt.init?.let { init -> if (value(init)) frame.store(slot) }
                    }
                }
                is TmpL.LocalFunctionDeclaration -> {
                    val name = stmt.name.name
                    val captured = captures.getValue(name)
                    val fn = FnInfo(
                        name.toString(),
                        stmt.parameters.parameters,
                        stmt.body,
                        !stmt.sig.returnType2.isVoidLike,
                        captured,
                        name,
                    )
                    pendingClosures.addLast(fn)
                    e.pushLabel(fn.label)
                    for (c in captured) loadRaw(c)
                    frame.alloc(1 + captured.size)
                    if (name in boxedFromEntry) store(name) else frame.store(slots.getValue(name))
                }
                is TmpL.Assignment -> {
                    check(value(stmt.right)) { "assigning nothing: $stmt" }
                    store(stmt.left.name)
                }
                is TmpL.IfStatement -> {
                    val then = label("then")
                    val otherwise = label("else")
                    val join = label("endif")
                    condition(stmt.test)
                    val d0 = e.depth - 1
                    e.branch(then, otherwise)
                    e.startBlock(then, d0)
                    statement(stmt.consequent)
                    e.jump(join)
                    e.startBlock(otherwise, d0)
                    stmt.alternate?.let { statement(it) }
                    e.jump(join)
                    if (e.knows(join)) e.startBlock(join)
                }
                is TmpL.WhileStatement -> loop(null, stmt)
                is TmpL.LabeledStatement -> {
                    val name = stmt.label.id.name
                    when (val inner = stmt.statement) {
                        is TmpL.WhileStatement -> loop(name, inner)
                        else -> {
                            val after = label("after")
                            jumps.addLast(JumpScope(name, after, null))
                            statement(inner)
                            jumps.removeLast()
                            e.jump(after)
                            if (e.knows(after)) e.startBlock(after)
                        }
                    }
                }
                is TmpL.BreakStatement -> e.jump(jumpScope(stmt.label).breakTo)
                is TmpL.ContinueStatement ->
                    e.jump(jumpScope(stmt.label).continueTo ?: TODO("continue to a non-loop: $stmt"))
                is TmpL.ReturnStatement -> {
                    val w = writer ?: TODO("return at top level: $stmt")
                    val pushed = stmt.expression?.let { value(it) } ?: false
                    if (pushed && !returnsValue) e.op(Pop)
                    check(pushed || !returnsValue) { "return without a value: $stmt" }
                    w.ret(returnsValue)
                }
                is TmpL.TryStatement -> {
                    val catchTo = label("catch")
                    val after = label("endtry")
                    handlers.addLast(Handler(catchTo, e.depth))
                    e.expect(catchTo, e.depth)
                    statement(stmt.tried)
                    handlers.removeLast()
                    e.jump(after)
                    e.startBlock(catchTo)
                    statement(stmt.recover)
                    e.jump(after)
                    if (e.knows(after)) e.startBlock(after)
                }
                is TmpL.SetBackedProperty -> {
                    check(value(stmt.right))
                    check(value(stmt.left.subject as TmpL.Expression))
                    frame.heapStore(fieldOffset(stmt.left.property))
                }
                is TmpL.SetAbstractProperty -> {
                    val subject = stmt.left.subject as? TmpL.Expression ?: TODO("static set $stmt")
                    methodCall(subject, "s:${dotName(stmt.left.property)}", listOf(stmt.right))
                }
                is TmpL.ThrowStatement -> bubble()
                is TmpL.ModuleInitFailed -> e.jumpAway(program.halt)
                is TmpL.EmbeddedComment, is TmpL.BoilerplateCodeFoldBoundary -> {}
                else -> TODO("${stmt::class.simpleName}: $stmt")
            }
        }

        private fun loop(name: ResolvedName?, stmt: TmpL.WhileStatement) {
            val head = label("while")
            val body = label("do")
            val exit = label("endwhile")
            e.startBlock(head, e.depth)
            condition(stmt.test)
            e.branch(body, exit)
            e.startBlock(body)
            jumps.addLast(JumpScope(name, exit, head))
            statement(stmt.body)
            jumps.removeLast()
            e.jump(head)
            e.startBlock(exit)
        }

        private fun jumpScope(label: TmpL.JumpLabel?): JumpScope = when (label) {
            null -> jumps.lastOrNull { it.continueTo != null }
            else -> jumps.lastOrNull { it.name == label.id.name }
        } ?: TODO("no jump target for $label")

        private fun store(name: ResolvedName) {
            if (name in boxed) {
                loadRaw(name)
                frame.heapStore()
                return
            }
            when (val slot = slots[name]) {
                null -> {
                    check(name !in envOffsets) { "assigning captured $name, which is not boxed" }
                    frame.storeGlobal(globals[name] ?: TODO("store to $name"))
                }
                else -> frame.store(slot)
            }
        }

        private fun condition(expr: TmpL.Expression) {
            check(value(expr)) { "condition with no value: $expr" }
        }

        // Expressions. Each pushes one value, or none if it is Void, and says which.

        fun value(expr: TmpL.Expression): Boolean {
            when (expr) {
                is TmpL.ValueReference -> {
                    val v = expr.value
                    when (v.typeTag) {
                        TInt -> e.const(TInt.unpack(v))
                        TBoolean -> e.const(if (TBoolean.unpack(v)) 1 else 0)
                        TString -> frame.stringLiteral(TString.unpack(v))
                        TVoid -> return false
                        TNull -> e.const(NULL)
                        else -> TODO("value $v")
                    }
                }
                is TmpL.Reference -> load(expr.id.name)
                is TmpL.FunInterfaceExpression -> when (val callable = expr.callable) {
                    is TmpL.FnReference -> load(callable.id.name)
                    else -> TODO("fun interface from $callable")
                }
                is TmpL.CallExpression -> return callExpression(expr)
                is TmpL.This -> frame.load(slots[expr.id.name] ?: TODO("this outside a method: $expr"))
                is TmpL.GetBackedProperty -> {
                    check(value(expr.subject as? TmpL.Expression ?: TODO("static get $expr")))
                    frame.heapLoad(fieldOffset(expr.property))
                }
                is TmpL.GetAbstractProperty -> return methodCall(expr.subject, "g:${dotName(expr.property)}", listOf())
                is TmpL.InfixOperation -> infix(expr)
                is TmpL.PrefixOperation -> {
                    // Bang is the only prefix operator TmpL has.
                    condition(expr.operand)
                    e.op(Not)
                }
                else -> TODO("${expr::class.simpleName}: $expr")
            }
            return true
        }

        private fun args(actuals: List<TmpL.Actual>): Int {
            for (actual in actuals) {
                check(value(actual as? TmpL.Expression ?: TODO("actual $actual"))) { "void argument $actual" }
            }
            return actuals.size
        }

        private fun callExpression(expr: TmpL.CallExpression): Boolean {
            when (val fn = expr.fn) {
                is TmpL.FnReference -> {
                    val name = fn.id.name
                    builtins[name]?.let { return builtin(it, expr) }
                    val info = functions[name]
                    if (info == null || name in slots || name in envOffsets || name == selfName) {
                        return callValue(expr) { load(name) }
                    }
                    val landing = bubbleLanding()
                    call(frame, info.label, expr.parameters.size, info.returnsValue, landing) {
                        args(expr.parameters)
                    }
                    return info.returnsValue
                }
                is TmpL.FunInterfaceCallable -> return callValue(expr) { check(value(fn.expr)) }
                is TmpL.MethodReference -> {
                    val subject = fn.subject as? TmpL.Expression ?: TODO("static method $expr")
                    return methodCall(subject, "m:${fn.methodName.dotNameText}", expr.parameters)
                }
                is TmpL.ConstructorReference if fn.typeShape == WellKnownTypes.listBuilderTypeDefinition -> {
                    if (expr.parameters.isNotEmpty()) TODO("$expr")
                    e.const(0)
                    e.const(0)
                    e.const(NULL)
                    frame.alloc(3)
                    return true
                }
                is TmpL.ConstructorReference -> {
                    val info = types[fn.typeShape] ?: TODO("new ${fn.typeShape}")
                    val ctor = info.constructor ?: TODO("no constructor: $expr")
                    e.const(info.classId ?: TODO("new interface $expr"))
                    repeat(info.fieldCount) { e.const(0) }
                    frame.alloc(1 + info.fieldCount)
                    call(frame, ctor.label, 1 + expr.parameters.size, false, bubbleLanding()) {
                        // ret, fail and D are above the new object.
                        e.pick(4)
                        args(expr.parameters)
                    }
                    return true
                }
                else -> TODO("${fn::class.simpleName}: $expr")
            }
        }

        /**
         * Calls a function value: the block [loadFn] pushes goes in as the
         * callee's first argument, and its first cell is the row to go to.
         */
        private fun callValue(expr: TmpL.CallExpression, loadFn: () -> Unit): Boolean {
            val returnsValue = !expr.passType.isVoidLike
            if (!e.reachable) return returnsValue
            val n = expr.parameters.size
            val d0 = e.depth
            val back = label("back")
            val landing = bubbleLanding()
            e.pushLabel(back)
            e.pushLabel(landing)
            frame.pushD(d0)
            loadFn()
            args(expr.parameters)
            e.pick(n + 1)
            frame.heapLoad()
            e.jumpToTop()
            e.startBlock(back, d0 + if (returnsValue) 1 else 0)
            return returnsValue
        }

        private fun dotName(property: TmpL.PropertyId) =
            (property as? TmpL.ExternalPropertyId)?.name?.dotNameText ?: TODO("internal property $property")

        private fun fieldOffset(property: TmpL.PropertyId) =
            fieldOffsets[(property as? TmpL.InternalPropertyId)?.name?.name ?: TODO("external $property")]
                ?: TODO("unknown field $property")

        /**
         * Calls the method, getter or setter [key] on [subject]: directly when
         * its static type is a class, which Temper does not let anything
         * extend, and through a dispatcher when it is an interface.
         */
        fun methodCall(subject: TmpL.Expression, key: String, actuals: List<TmpL.Actual>): Boolean {
            val type = subject.type.definition
            val info = types[type] ?: return builtinMethod(subject, key, actuals)
            val impl = resolve(info, key) ?: TODO("$key on ${info.decl.name}")
            val target = when (info.classId) {
                null -> dispatchers.getOrPut(key) { Label("dispatch_$key") }
                else -> impl.label
            }
            call(frame, target, 1 + actuals.size, impl.returnsValue, bubbleLanding()) {
                check(value(subject))
                args(actuals)
            }
            return impl.returnsValue
        }

        /** Methods of types the runtime provides. */
        private fun builtinMethod(subject: TmpL.Expression, key: String, actuals: List<TmpL.Actual>): Boolean {
            val type = subject.type.definition
            when {
                type == WellKnownTypes.intTypeDefinition && key == "m:toString" && actuals.isEmpty() ->
                    call(frame, rt.intToString, 1, true) { check(value(subject)) }
                type == WellKnownTypes.intTypeDefinition && (key == "m:min" || key == "m:max") -> {
                    // [a, b] -> a > b chooses: min is b when a > b.
                    check(value(subject))
                    args(actuals)
                    e.pick(2)
                    e.pick(2)
                    e.op(Greater)
                    if (key == "m:max") e.op(Not)
                    // [a, b, c]: keep b if c else a, as a + c * (b - a)
                    e.pick(3)
                    e.pick(3)
                    e.swap()
                    e.op(Subtract)
                    e.op(Multiply)
                    e.dropUnder(1)
                    e.op(Add)
                }
                type == WellKnownTypes.booleanTypeDefinition && key == "m:toString" -> {
                    val yes = label("true")
                    val no = label("false")
                    val join = label("endbool")
                    check(value(subject))
                    val d0 = e.depth - 1
                    e.branch(yes, no)
                    e.startBlock(yes, d0)
                    frame.stringLiteral("true")
                    e.jump(join)
                    e.startBlock(no, d0)
                    frame.stringLiteral("false")
                    e.jump(join)
                    e.startBlock(join)
                }
                type == WellKnownTypes.stringTypeDefinition && key == "g:isEmpty" -> {
                    check(value(subject))
                    frame.heapLoad()
                    e.op(Not)
                }
                type in LIST_TYPES -> return listMethod(subject, key, actuals)
                else -> TODO("$key on ${subject.type}")
            }
            return true
        }

        private fun listMethod(subject: TmpL.Expression, key: String, actuals: List<TmpL.Actual>): Boolean {
            fun rtCall(target: Label, returnsValue: Boolean) {
                call(frame, target, 1 + actuals.size, returnsValue, bubbleLanding()) {
                    check(value(subject))
                    args(actuals)
                }
            }
            when (key) {
                "g:length" -> {
                    check(value(subject))
                    frame.heapLoad(LIST_LENGTH)
                }
                "g:isEmpty" -> {
                    check(value(subject))
                    frame.heapLoad(LIST_LENGTH)
                    e.op(Not)
                }
                "m:get" -> rtCall(rt.listGet, true)
                "m:add" -> {
                    if (actuals.size != 1) TODO("$key with ${actuals.size} arguments")
                    rtCall(rt.listBuilderAdd, false)
                    return false
                }
                "m:toList", "m:toListBuilder" -> rtCall(rt.listCopy, true)
                "m:map" -> rtCall(rt.listMap, true)
                "m:filter" -> rtCall(rt.listFilter, true)
                "m:forEach" -> {
                    rtCall(rt.listForEach, false)
                    return false
                }
                "m:join" -> rtCall(rt.listJoin, true)
                "m:sort" -> {
                    rtCall(rt.listSort, false)
                    return false
                }
                "m:sorted" -> {
                    call(frame, rt.listCopy, 1, true) { check(value(subject)) }
                    e.op(PietOp.Duplicate)
                    // [copy, copy] -> sort the second copy in place
                    call(frame, rt.listSort, 2, false, bubbleLanding()) {
                        e.pick(4)
                        args(actuals)
                    }
                    e.op(Pop)
                }
                else -> TODO("$key on ${subject.type}")
            }
            return true
        }

        private fun builtin(b: PietBuiltin, expr: TmpL.CallExpression): Boolean {
            val ps = expr.parameters
            when (b) {
                PietBuiltin.GetConsole -> {
                    // There is one console; it is not a value anyone inspects.
                    e.const(0)
                }
                PietBuiltin.ConsoleLog -> {
                    check(ps.size == 2)
                    val message = ps[1] as TmpL.Expression
                    call(frame, rt.log, 1, false) { check(value(message)) }
                    return false
                }
                PietBuiltin.StrCat -> {
                    // Evaluate every part first: they may allocate, and the
                    // result's cells have to be contiguous.
                    val parts = args(ps)
                    e.const(0)
                    frame.alloc(1)
                    for (i in 0 until parts) {
                        call(frame, rt.appendChars, 1, false) { e.pick(parts + 4 - i) }
                    }
                    val header = e.depth
                    frame.fillLength { frame.load(header - 1) }
                    e.dropUnder(parts)
                }
                PietBuiltin.PlusIntInt -> int2(ps, Add)
                PietBuiltin.MinusIntInt -> int2(ps, Subtract)
                PietBuiltin.TimesIntInt -> int2(ps, Multiply)
                PietBuiltin.MinusInt -> {
                    args(ps)
                    e.const(-1)
                    e.op(Multiply)
                    wrapInt32()
                }
                PietBuiltin.DivIntInt, PietBuiltin.ModIntInt -> {
                    args(ps)
                    // Piet leaves x / 0 to the interpreter; npiet pushes 99999999.
                    val ok = label("nonzero")
                    e.op(Duplicate)
                    e.ifThenElseJump(ok, bubbleLanding(e.depth - 1))
                    e.startBlock(ok)
                    divOrMod(b == PietBuiltin.DivIntInt)
                }
                PietBuiltin.DivIntIntSafe, PietBuiltin.ModIntIntSafe -> {
                    args(ps)
                    divOrMod(b == PietBuiltin.DivIntIntSafe)
                }
                PietBuiltin.LtIntInt -> {
                    args(ps)
                    e.swap()
                    e.op(Greater)
                }
                PietBuiltin.GtIntInt -> {
                    args(ps)
                    e.op(Greater)
                }
                PietBuiltin.LeIntInt -> {
                    args(ps)
                    e.op(Greater)
                    e.op(Not)
                }
                PietBuiltin.GeIntInt -> {
                    args(ps)
                    e.swap()
                    e.op(Greater)
                    e.op(Not)
                }
                PietBuiltin.EqIntInt -> {
                    args(ps)
                    e.op(Subtract)
                    e.op(Not)
                }
                PietBuiltin.NeIntInt -> {
                    args(ps)
                    e.op(Subtract)
                    e.op(Not)
                    e.op(Not)
                }
                PietBuiltin.BooleanNegation -> {
                    args(ps)
                    e.op(Not)
                }
                PietBuiltin.Listify -> {
                    val n = args(ps)
                    if (n == 0) {
                        e.const(0)
                        e.const(0)
                        e.const(NULL)
                    } else {
                        frame.alloc(n)
                        e.const(n)
                        e.const(n)
                        e.pick(3)
                    }
                    frame.alloc(3)
                    if (n != 0) e.dropUnder(1)
                }
                PietBuiltin.IsNull, PietBuiltin.NotNull -> {
                    val x = ps.single() as TmpL.Expression
                    requireNullable(x)
                    check(value(x))
                    e.const(NULL)
                    e.op(Subtract)
                    if (b == PietBuiltin.IsNull) e.op(Not)
                }
                PietBuiltin.EqGeneric, PietBuiltin.NeGeneric -> {
                    val (l, r) = ps.map { it as TmpL.Expression }
                    val strings = listOf(l, r).all { it.type.definition == WellKnownTypes.stringTypeDefinition }
                    if (strings) {
                        call(frame, rt.stringCompare, 2, true) { args(ps) }
                    } else {
                        for (x in listOf(l, r)) identityComparable(x)
                        args(ps)
                        e.op(Subtract)
                    }
                    e.op(Not)
                    if (b == PietBuiltin.NeGeneric) e.op(Not)
                }
                PietBuiltin.EqStrStr, PietBuiltin.NeStrStr, PietBuiltin.LtStrStr, PietBuiltin.LeStrStr,
                PietBuiltin.GtStrStr, PietBuiltin.GeStrStr, PietBuiltin.CmpStrStr,
                -> {
                    call(frame, rt.stringCompare, 2, true) { args(ps) }
                    compareResult(b)
                }
                PietBuiltin.CmpIntInt -> {
                    args(ps)
                    compareInts()
                }
                PietBuiltin.LtGeneric, PietBuiltin.LeGeneric, PietBuiltin.GtGeneric, PietBuiltin.GeGeneric,
                PietBuiltin.CmpGeneric,
                -> {
                    val d = (ps[0] as TmpL.Expression).type.definition
                    when (d) {
                        WellKnownTypes.stringTypeDefinition -> call(frame, rt.stringCompare, 2, true) { args(ps) }
                        WellKnownTypes.intTypeDefinition -> {
                            args(ps)
                            compareInts()
                        }
                        else -> TODO("$b on $d")
                    }
                    compareResult(
                        when (b) {
                            PietBuiltin.LtGeneric -> PietBuiltin.LtStrStr
                            PietBuiltin.LeGeneric -> PietBuiltin.LeStrStr
                            PietBuiltin.GtGeneric -> PietBuiltin.GtStrStr
                            PietBuiltin.GeGeneric -> PietBuiltin.GeStrStr
                            else -> PietBuiltin.CmpStrStr
                        },
                    )
                }
                PietBuiltin.BitwiseAnd32 -> call(frame, rt.bitwise("and", 0, 1), 2, true) { args(ps) }
                PietBuiltin.BitwiseOr32 -> call(frame, rt.bitwise("or", 1, -1), 2, true) { args(ps) }
                PietBuiltin.BitwiseXor32 -> call(frame, rt.bitwise("xor", 1, -2), 2, true) { args(ps) }
                PietBuiltin.PureVirtual -> {
                    // An abstract method's body. Dispatch never reaches one.
                    bubble()
                    return false
                }
            }
            return true
        }

        /** `[a, b] -> (a > b) - (b > a)` */
        private fun compareInts() {
            e.pick(2)
            e.pick(2)
            e.op(Greater)
            e.pick(2)
            e.pick(4)
            e.op(Greater)
            e.op(Subtract)
            e.dropUnder(2)
        }

        /** [c], a -1/0/1 comparison, becomes what [b] asks of it. */
        private fun compareResult(b: PietBuiltin) {
            when (b) {
                PietBuiltin.EqStrStr -> e.op(Not)
                PietBuiltin.NeStrStr -> {
                    e.op(Not)
                    e.op(Not)
                }
                PietBuiltin.LtStrStr -> {
                    e.const(0)
                    e.swap()
                    e.op(Greater)
                }
                PietBuiltin.LeStrStr -> {
                    e.const(0)
                    e.op(Greater)
                    e.op(Not)
                }
                PietBuiltin.GtStrStr -> {
                    e.const(0)
                    e.op(Greater)
                }
                PietBuiltin.GeStrStr -> {
                    e.const(0)
                    e.swap()
                    e.op(Greater)
                    e.op(Not)
                }
                PietBuiltin.CmpStrStr -> {}
                else -> error(b)
            }
        }

        private fun requireNullable(x: TmpL.Expression) {
            val t = x.type
            if (t.definition == WellKnownTypes.intTypeDefinition) TODO("nullable Int: $x")
        }

        /** Types whose == is ==, by value or by address. */
        private fun identityComparable(x: TmpL.Expression) {
            val d = x.type.definition
            val ok = d == WellKnownTypes.intTypeDefinition || d == WellKnownTypes.booleanTypeDefinition ||
                d == WellKnownTypes.nullTypeDefinition || d in types
            if (!ok) TODO("== on ${x.type}: $x")
        }

        private fun int2(ps: List<TmpL.Actual>, op: PietOp) {
            args(ps)
            e.op(op)
            wrapInt32()
        }

        /**
         * Truncating division or remainder, as Temper's Int has: npiet's are
         * C's, which truncate. Only -2^31 / -1 overflows.
         */
        private fun divOrMod(divide: Boolean) {
            e.op(if (divide) Divide else Mod)
            if (divide) wrapInt32()
        }

        private fun infix(expr: TmpL.InfixOperation) {
            when (expr.op.tmpLOperator as TmpLOperator.Infix) {
                TmpLOperator.AmpAmp, TmpLOperator.BarBar -> {
                    val and = expr.op.tmpLOperator == TmpLOperator.AmpAmp
                    val right = label(if (and) "and" else "or")
                    val short = label("short")
                    val join = label("endbool")
                    condition(expr.left)
                    val d0 = e.depth - 1
                    if (and) e.branch(right, short) else e.branch(short, right)
                    e.startBlock(right, d0)
                    condition(expr.right)
                    e.jump(join)
                    e.startBlock(short, d0)
                    e.const(if (and) 0 else 1)
                    e.jump(join)
                    e.startBlock(join)
                }
                TmpLOperator.EqEqInt -> binary(expr) { e.op(Subtract); e.op(Not) }
                TmpLOperator.LtInt -> binary(expr) { e.swap(); e.op(Greater) }
                TmpLOperator.GtInt -> binary(expr) { e.op(Greater) }
                TmpLOperator.LeInt -> binary(expr) { e.op(Greater); e.op(Not) }
                TmpLOperator.GeInt -> binary(expr) { e.swap(); e.op(Greater); e.op(Not) }
                TmpLOperator.PlusInt -> binary(expr) { e.op(Add); wrapInt32() }
            }
        }

        private fun binary(expr: TmpL.InfixOperation, op: () -> Unit) {
            check(value(expr.left) && value(expr.right))
            op()
        }

        /**
         * Brings the top into Int32 range the way two's complement wraps. The
         * value is at most 2^62 in size, and stays on top of the stack the
         * whole time: npiet's roll would cut it to 32 bits anyway, but
         * silently, and only once stored.
         *
         * Two mods because npiet's mod truncates: after the first the value
         * is within (-2^32, 2^32), and adding 2^32 + 2^31 makes it positive.
         */
        private fun wrapInt32() {
            e.const(TWO_32)
            e.op(Mod)
            e.const(TWO_32 + TWO_31)
            e.op(Add)
            e.const(TWO_32)
            e.op(Mod)
            e.const(TWO_31)
            e.op(Subtract)
        }
    }

    companion object {
        private val LIST_TYPES = setOf(
            WellKnownTypes.listTypeDefinition,
            WellKnownTypes.listBuilderTypeDefinition,
            WellKnownTypes.listedTypeDefinition,
        )

        /**
         * null, for every type whose values are never negative: heap
         * addresses (strings, objects, lists, functions) and Booleans. A
         * nullable Int has no value free to mean null and is a TODO.
         */
        const val NULL = -1L
        private const val TWO_31 = 1L shl 31
        private const val TWO_32 = 1L shl 32
    }
}

/** Pops a value and goes to [ifNonZero] if it is not 0, else to [ifZero]. */
fun Emitter.ifThenElseJump(ifNonZero: Label, ifZero: Label) {
    op(Not)
    op(Not)
    branch(ifNonZero, ifZero)
}
