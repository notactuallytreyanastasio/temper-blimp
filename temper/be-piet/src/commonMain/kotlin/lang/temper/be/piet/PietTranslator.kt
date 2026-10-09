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
import lang.temper.type.TypeFormal
import lang.temper.type.WellKnownTypes
import lang.temper.type.isVoidLike
import lang.temper.type2.Nullity
import lang.temper.type2.Signature2
import lang.temper.type2.Type2
import lang.temper.value.TBoolean
import lang.temper.value.TInt
import lang.temper.value.TInt64
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
/** Set to print each program's blocks before they are laid out. A debugging aid only. */
var debugDump = false

class PietTranslator(private val modules: List<TmpL.Module>, private val roots: Set<TmpL.Module> = modules.toSet()) {
    private val program = AsmProgram()
    private val rt = Runtime(program)

    private val globals = mutableMapOf<ResolvedName, Int>()
    private val functions = mutableMapOf<ResolvedName, FnInfo>()
    private val builtins = mutableMapOf<ResolvedName, PietBuiltin>()

    /** The global holding each string literal. */
    private val literalGlobals = mutableMapOf<String, Int>()

    /**
     * Import aliases to the names they import. An imported name's
     * ResolvedName is the exporting module's own, so after this one lookup
     * it is found like any other module-level name.
     */
    private val aliases = mutableMapOf<ResolvedName, ResolvedName>()

    private fun canon(name: ResolvedName): ResolvedName = aliases[name] ?: name

    private class FnInfo(
        val name: String,
        val parameters: List<TmpL.Formal>,
        val body: TmpL.BlockStatement?,
        val returnsValue: Boolean,
        /** For a local function: what it captures, in environment order, and its own name. */
        val captured: List<ResolvedName>? = null,
        val selfName: ResolvedName? = null,
        /** A rest parameter, which arrives as one List after the others. */
        val rest: ResolvedName? = null,
        val returnType: Type2? = null,
    ) {
        val label = Label(name)
        val isClosure get() = captured != null

        /** Arguments a caller pushes, not counting a closure's own block. */
        val arity get() = parameters.size + if (rest != null) 1 else 0
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

    /** Static properties, which are globals, and static methods, by type and dot name. */
    private val staticProperties = mutableMapOf<Pair<TypeDefinition, String>, ResolvedName>()
    private val staticMethods = mutableMapOf<Pair<TypeDefinition, String>, FnInfo>()

    private val fieldTypes = mutableMapOf<ResolvedName, Type2>()

    /** Heap offset of each backed property within its object. */
    private val fieldOffsets = mutableMapOf<ResolvedName, Int>()

    /** Method keys called through an interface, which need a dispatcher. */
    private val dispatchers = mutableMapOf<String, Label>()

    private fun fnInfo(name: ResolvedName, parameters: TmpL.Parameters, body: TmpL.BlockStatement?, sig: Signature2) =
        FnInfo(
            name.toString(),
            parameters.parameters,
            body,
            !sig.returnType2.isVoidLike,
            rest = parameters.restParameter?.name?.name,
            returnType = sig.returnType2,
        )

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
                is TmpL.InstanceProperty -> {
                    fieldOffsets[member.name.name] = offset++
                    member.descriptor?.let { fieldTypes[member.name.name] = it }
                }
                is TmpL.Constructor ->
                    info.constructor =
                        FnInfo(member.name.name.toString(), member.parameters.parameters, member.body, false)
                is TmpL.NormalMethod -> info.own["m:${member.dotName.dotNameText}"] =
                    fnInfo(member.name.name, member.parameters, member.body, member.sig)
                is TmpL.Getter -> info.own["g:${member.dotName.dotNameText}"] =
                    fnInfo(member.name.name, member.parameters, member.body, member.sig)
                is TmpL.Setter -> info.own["s:${member.dotName.dotNameText}"] =
                    fnInfo(member.name.name, member.parameters, member.body, member.sig)
                is TmpL.StaticProperty -> {
                    globals[member.name.name] = globals.size
                    staticProperties[decl.typeShape to member.dotName.dotNameText] = member.name.name
                }
                is TmpL.StaticMethod -> staticMethods[decl.typeShape to member.dotName.dotNameText] =
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
    private fun resolve(info: TypeInfo, key: String, concrete: Boolean = true): FnInfo? {
        var level = listOf(info)
        val seen = mutableSetOf<TypeInfo>()
        while (level.isNotEmpty()) {
            level.firstNotNullOfOrNull { t -> t.own[key]?.takeIf { !concrete || it.body != null } }?.let { return it }
            seen.addAll(level)
            level = level.flatMap { t ->
                t.decl.superTypes.mapNotNull { types[it.typeName.sourceDefinition] }
            }.distinct().filter { it !in seen }
        }
        return null
    }

    /** Whether every [from] is a [to], from declared super types alone. */
    private fun staticSubtype(from: TypeDefinition, to: TypeDefinition): Boolean =
        from == to || from.superTypes.any { staticSubtype(it.definition, to) }

    /** Whether [c] is [target] or has it among its super types. */
    private fun isSubtype(c: TypeInfo, target: TypeDefinition): Boolean {
        if (c.decl.typeShape == target) return true
        return c.decl.superTypes.any { st ->
            types[st.typeName.sourceDefinition]?.let { isSubtype(it, target) } == true
        }
    }

    private fun allFunctions(): List<FnInfo> =
        functions.values.toList() + staticMethods.values +
            types.values.flatMap { listOfNotNull(it.constructor) + it.own.values }

    fun translate(): DispatchLayout {
        for (module in modules) {
            for (import in module.imports) {
                val local = import.localName?.name ?: continue
                aliases[local] = import.externalName.name
            }
        }
        val topLevels = reachableTopLevels(modules, roots)
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
        // String literals are globals, made and interned once before anything runs.
        val literals = linkedSetOf<String>()
        fun findLiterals(node: TmpL.Tree) {
            if (node is TmpL.ValueReference && node.value.typeTag == TString) literals.add(TString.unpack(node.value))
            for (i in 0 until node.childCount) node.childOrNull(i)?.let { findLiterals(it) }
        }
        topLevels.forEach { findLiterals(it) }
        for (literal in literals) literalGlobals[literal] = globals.size + literalGlobals.size
        val main = MainWriter(program, globals.size + literalGlobals.size)
        val mainLocals = topLevels.filterIsInstance<TmpL.ModuleInitBlock>().flatMap { localsOf(it.body) }
        Scope(main.e, main.frame, writer = null, slotsFrom(mainLocals, 0)).apply {
            // The intern table must be the first allocation: it lives at address 0.
            call(frame, rt.allocZeros, 1, true) { e.const(INTERN_BUCKETS) }
            e.op(Pop)
            for ((literal, global) in literalGlobals) {
                frame.stringLiteral(literal)
                rt.internTop(frame)
                frame.storeGlobal(global)
            }
            repeat(mainLocals.size) { e.const(0) }
            allocateEntryBoxes(mainLocals)
            for (topLevel in topLevels) {
                when (topLevel) {
                    is TmpL.ModuleLevelDeclaration -> topLevel.init?.let { init ->
                        if (valueAs(init, topLevel.descriptor)) frame.storeGlobal(globals.getValue(topLevel.name.name))
                    }
                    is TmpL.ModuleInitBlock -> statement(topLevel.body)
                    // Static properties initialize where their type is declared.
                    is TmpL.TypeDeclaration -> for (member in topLevel.members) {
                        if (member is TmpL.StaticProperty && value(member.expression)) {
                            frame.storeGlobal(globals.getValue(member.name.name))
                        }
                    }
                    else -> {}
                }
            }
            main.halt()
            finish()
        }

        // An abstract member has no body and is never jumped to.
        for (fn in allFunctions()) if (fn.body != null) translateFunction(fn)
        while (pendingClosures.isNotEmpty()) translateFunction(pendingClosures.removeFirst())
        // Translating can ask for more dispatchers only from function
        // bodies, which are all done by now.
        for ((key, label) in dispatchers) writeDispatcher(key, label)
        if (debugDump) println("ASM>>>\n$program")
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
        val restSlots = if (fn.rest != null) 1 else 0
        val writer = FunctionWriter(program, fn.label.hint, envSlots + params.size + restSlots, fn.label)
        val body = fn.body ?: TODO("${fn.name} has no body")
        val locals = localsOf(body)
        writer.begin(locals.size)
        val slots = slotsFrom(params.map { it.name.name } + listOfNotNull(fn.rest), writer.param(envSlots)) +
            slotsFrom(locals, writer.param(envSlots + params.size + restSlots))
        val env = if (fn.isClosure) writer.param(0) else null
        Scope(writer.e, writer.frame, writer, slots, fn.returnsValue, env, fn.captured.orEmpty(), fn.selfName).apply {
            returnType = fn.returnType
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
                // TmpL can fall off the end of a function the frontend has
                // already flagged as missing a return.
                if (e.reachable) e.jumpAway(rt.panic)
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
        var returnType: Type2? = null

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
                else -> frame.loadGlobal(globals[canon(name)] ?: TODO("reference to $name"))
            }
        }

        fun load(local: ResolvedName) {
            val name = canon(local)
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

        /**
         * Code the frontend could not make sense of, and has already said so,
         * panics when reached. Garbage that names a builtin this backend does
         * not support is this backend's gap, not the program's, and stops the
         * build instead.
         */
        fun garbage(text: String) {
            if ("not supported by Piet Backend" in text) TODO(text)
            e.jumpAway(rt.panic)
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
                    else -> e.jumpAway(rt.panic)
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
                        if (init == null || !valueAs(init, stmt.descriptor)) e.const(0)
                        frame.alloc(1)
                        frame.store(slot)
                    } else {
                        stmt.init?.let { init -> if (valueAs(init, stmt.descriptor)) frame.store(slot) }
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
                        returnType = stmt.sig.returnType2,
                    )
                    pendingClosures.addLast(fn)
                    e.pushLabel(fn.label)
                    for (c in captured) loadRaw(c)
                    frame.alloc(1 + captured.size)
                    if (name in boxedFromEntry) store(name) else frame.store(slots.getValue(name))
                }
                is TmpL.Assignment -> {
                    check(valueAs(stmt.right, stmt.type)) { "assigning nothing: $stmt" }
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
                    val pushed = stmt.expression?.let { valueAs(it, returnType) } ?: false
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
                    val fieldType = (stmt.left.property as? TmpL.InternalPropertyId)?.let { fieldTypes[it.name.name] }
                    check(valueAs(stmt.right, fieldType))
                    check(value(stmt.left.subject as TmpL.Expression))
                    frame.heapStore(fieldOffset(stmt.left.property))
                }
                is TmpL.SetAbstractProperty -> {
                    val subject = stmt.left.subject as? TmpL.Expression ?: TODO("static set $stmt")
                    methodCall(subject, "s:${dotName(stmt.left.property)}", listOf(stmt.right))
                }
                is TmpL.ComputedJumpStatement -> {
                    // A chain of tests. Each case pops the value first.
                    check(value(stmt.caseExpr))
                    val end = label("endwhen")
                    val d0 = e.depth
                    for (case in stmt.cases) {
                        val body = label("case")
                        val next = label("nextcase")
                        e.const(0)
                        for (v in case.values) {
                            e.pick(2)
                            e.const(v.index)
                            e.op(Subtract)
                            e.op(Not)
                            e.op(Add)
                        }
                        e.branch(body, next)
                        e.startBlock(body, d0)
                        e.op(Pop)
                        statement(case.body)
                        e.jump(end)
                        e.startBlock(next, d0)
                    }
                    e.op(Pop)
                    statement(stmt.elseCase.body)
                    e.jump(end)
                    if (e.knows(end)) e.startBlock(end)
                }
                is TmpL.ThrowStatement -> bubble()
                is TmpL.ModuleInitFailed -> e.jumpAway(rt.panic)
                is TmpL.GarbageStatement -> garbage("$stmt")
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
                    frame.storeGlobal(globals[canon(name)] ?: TODO("store to $name"))
                }
                else -> frame.store(slot)
            }
        }

        private fun condition(expr: TmpL.Expression) {
            check(value(expr)) { "condition with no value: $expr" }
        }

        // Expressions. Each pushes one value, or none if it is Void, and says which.

        /**
         * Pushes [expr]'s value and says whether there is one. Code after a
         * panic or bubble is unreachable, and its value is never used, so
         * there it counts as pushed.
         */
        fun value(expr: TmpL.Expression): Boolean = valueOrNot(expr) || !e.reachable

        private fun valueOrNot(expr: TmpL.Expression): Boolean {
            when (expr) {
                is TmpL.ValueReference -> {
                    val v = expr.value
                    when (v.typeTag) {
                        TInt -> e.const(TInt.unpack(v))
                        TBoolean -> e.const(if (TBoolean.unpack(v)) 1 else 0)
                        TString -> frame.loadGlobal(literalGlobals.getValue(TString.unpack(v)))
                        TVoid -> return false
                        TNull -> e.const(NULL)
                        TInt64 -> {
                            val n = TInt64.unpack(v)
                            if (n == Long.MIN_VALUE) e.constMin64() else e.const(n)
                            frame.pack64()
                        }
                        else -> TODO("value $v")
                    }
                }
                is TmpL.Reference -> load(expr.id.name)
                is TmpL.FunInterfaceExpression -> when (val callable = expr.callable) {
                    is TmpL.FnReference -> load(callable.id.name)
                    else -> TODO("fun interface from $callable")
                }
                is TmpL.CallExpression -> return callExpression(expr)
                is TmpL.GarbageExpression -> {
                    garbage("${expr.diagnostic?.text}")
                    // Unreachable, but the expression has to leave a value.
                    e.const(0)
                }
                // A lambda in a method captures `this` like any other name.
                is TmpL.This -> load(expr.id.name)
                is TmpL.UncheckedNotNullExpression -> return valueAs(expr.expression, expr.passType)
                is TmpL.InstanceOfExpression -> {
                    check(value(expr.expr))
                    typeTest(expr.expr, expr.checkedFrontendType)
                }
                is TmpL.CastExpression -> {
                    check(value(expr.expr))
                    e.op(PietOp.Duplicate)
                    typeTest(expr.expr, expr.checkedFrontendType)
                    val ok = label("cast")
                    if (expr.canFail) {
                        val landing = bubbleLanding(e.depth - 1)
                        e.branch(ok, landing)
                        e.startBlock(ok)
                    } else {
                        // A safe cast is one the frontend proved; check it anyway.
                        e.branch(ok, bubbleLanding(e.depth - 1))
                        e.startBlock(ok)
                    }
                }
                is TmpL.GetBackedProperty -> when (val subject = expr.subject) {
                    is TmpL.TypeName if subject.sourceDefinition !in types -> {
                        val name = (expr.property as? TmpL.ExternalPropertyId)?.name?.dotNameText
                        when (subject.sourceDefinition to name) {
                            WellKnownTypes.stringTypeDefinition to "begin" -> e.const(0)
                            WellKnownTypes.stringIndexTypeDefinition to "none" -> e.const(NULL)
                            else -> TODO("static $expr")
                        }
                    }
                    is TmpL.TypeName -> {
                        val name = when (val p = expr.property) {
                            is TmpL.InternalPropertyId -> p.name.name
                            is TmpL.ExternalPropertyId ->
                                staticProperties[subject.sourceDefinition to p.name.dotNameText]
                                    ?: TODO("static $expr")
                        }
                        frame.loadGlobal(globals[name] ?: TODO("static $expr"))
                    }
                    is TmpL.Expression -> {
                        check(value(subject))
                        frame.heapLoad(fieldOffset(expr.property))
                    }
                    else -> TODO("${subject::class.simpleName}: $expr")
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

        /**
         * Pushes [expr] as a value of type [to]. A nullable Int is a box:
         * no Int is free to mean null, so `Int?` is -1 or the address of a
         * one-cell box. Every place the static type changes between Int and
         * Int? boxes or unboxes here.
         */
        fun valueAs(expr: TmpL.Expression, to: Type2?): Boolean {
            if (!value(expr)) return false
            if (to != null) coerce(expr.type, to)
            return true
        }

        fun coerce(from: Type2, to: Type2) {
            val fromBoxed = isNullableInt(from)
            val toBoxed = isNullableInt(to)
            when {
                toBoxed && !fromBoxed && from.definition == WellKnownTypes.intTypeDefinition -> frame.alloc(1)
                fromBoxed && !toBoxed && to.definition == WellKnownTypes.intTypeDefinition -> frame.heapLoad()
            }
        }

        /** Pushes [actuals] for [fn], gathering any past its parameters into its rest List. */
        private fun argsFor(fn: FnInfo, actuals: List<TmpL.Actual>, skip: Int = 0) {
            val formals = fn.parameters.drop(skip)
            for ((i, formal) in formals.withIndex()) {
                val actual = actuals.getOrNull(i)
                if (actual == null) {
                    // An omitted optional argument; the callee defaults a null.
                    e.const(NULL)
                } else {
                    check(valueAs(actual as? TmpL.Expression ?: TODO("actual $actual"), formal.descriptor)) {
                        "void argument $actual"
                    }
                }
            }
            if (fn.rest != null) listOf(actuals.drop(formals.size))
        }

        /** Pushes a new List of [actuals]. */
        private fun listOf(actuals: List<TmpL.Actual>) {
            val n = args(actuals)
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

        private fun args(actuals: List<TmpL.Actual>): Int {
            for (actual in actuals) {
                check(value(actual as? TmpL.Expression ?: TODO("actual $actual"))) { "void argument $actual" }
            }
            return actuals.size
        }

        private fun callExpression(expr: TmpL.CallExpression): Boolean {
            when (val fn = expr.fn) {
                is TmpL.FnReference -> {
                    val name = canon(fn.id.name)
                    builtins[name]?.let { return builtin(it, expr) }
                    val info = functions[name]
                    if (info == null || name in slots || name in envOffsets || name == selfName) {
                        return callValue(expr) { load(name) }
                    }
                    val landing = bubbleLanding()
                    call(frame, info.label, info.arity, info.returnsValue, landing) {
                        argsFor(info, expr.parameters)
                    }
                    return info.returnsValue
                }
                is TmpL.FunInterfaceCallable -> return callValue(expr) { check(value(fn.expr)) }
                is TmpL.GarbageCallable -> {
                    garbage("${fn.diagnostic?.text}")
                    return !expr.passType.isVoidLike
                }
                is TmpL.MethodReference if fn.subject is TmpL.TypeName -> {
                    val type = (fn.subject as TmpL.TypeName).sourceDefinition
                    val info = staticMethods[type to fn.methodName.dotNameText]
                        ?: return staticBuiltin(type, fn.methodName.dotNameText, expr)
                    call(frame, info.label, info.arity, info.returnsValue, bubbleLanding()) {
                        argsFor(info, expr.parameters)
                    }
                    return info.returnsValue
                }
                is TmpL.MethodReference -> {
                    val subject = fn.subject as? TmpL.Expression ?: TODO("static method $expr")
                    return methodCall(subject, "m:${fn.methodName.dotNameText}", expr.parameters)
                }
                is TmpL.ConstructorReference if fn.typeShape == WellKnownTypes.dequeTypeDefinition -> {
                    e.const(0)
                    e.const(0)
                    e.const(NULL)
                    e.const(0)
                    frame.alloc(4)
                    return true
                }
                is TmpL.ConstructorReference if fn.typeShape == WellKnownTypes.denseBitVectorTypeDefinition -> {
                    // The capacity is a hint; the vector grows as it is set.
                    repeat(args(expr.parameters)) { e.op(Pop) }
                    e.const(0)
                    e.const(0)
                    e.const(NULL)
                    frame.alloc(3)
                    return true
                }
                is TmpL.ConstructorReference if fn.typeShape == WellKnownTypes.pairTypeDefinition -> {
                    e.const(0)
                    args(expr.parameters)
                    frame.alloc(3)
                    return true
                }
                is TmpL.ConstructorReference if fn.typeShape == WellKnownTypes.mapTypeDefinition -> {
                    call(frame, rt.mapFromPairs(stringKeys(expr.type)), 1, true) { args(expr.parameters) }
                    return true
                }
                is TmpL.ConstructorReference if fn.typeShape == WellKnownTypes.mapBuilderTypeDefinition -> {
                    e.const(0)
                    e.const(0)
                    e.const(NULL)
                    frame.alloc(3)
                    return true
                }
                is TmpL.ConstructorReference if fn.typeShape == WellKnownTypes.listBuilderTypeDefinition ||
                    fn.typeShape == WellKnownTypes.stringBuilderTypeDefinition -> {
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
                    call(frame, ctor.label, ctor.arity, false, bubbleLanding()) {
                        // ret, fail and D are above the new object.
                        e.pick(4)
                        argsFor(ctor, expr.parameters, skip = 1)
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
            val info = types[type]
                ?: (type as? TypeFormal)?.upperBounds?.firstNotNullOfOrNull { types[it.definition] }
                ?: return builtinMethod(subject, key, actuals)
            // An interface's member may be abstract; its signature is what matters here.
            val impl = resolve(info, key, concrete = info.classId != null) ?: TODO("$key on ${info.decl.name}")
            val target = when (info.classId) {
                null -> dispatchers.getOrPut(key) { Label("dispatch_$key") }
                else -> impl.label
            }
            call(frame, target, impl.arity, impl.returnsValue, bubbleLanding()) {
                check(value(subject))
                argsFor(impl, actuals, skip = 1)
            }
            return impl.returnsValue
        }

        /** Static methods of types the runtime provides. */
        private fun staticBuiltin(type: TypeDefinition, name: String, expr: TmpL.CallExpression): Boolean {
            when {
                type == WellKnownTypes.listTypeDefinition && name == "of" -> listOf(expr.parameters)
                type == WellKnownTypes.stringTypeDefinition && name == "fromCodePoint" ->
                    call(frame, rt.stringFromCodePoint, 1, true, bubbleLanding()) { args(expr.parameters) }
                type == WellKnownTypes.stringTypeDefinition && name == "fromCodePoints" ->
                    call(frame, rt.stringFromCodePoints, 1, true, bubbleLanding()) { args(expr.parameters) }
                else -> TODO("static $name on $type: $expr")
            }
            return true
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
                type == WellKnownTypes.stringTypeDefinition -> return stringMethod(subject, key, actuals)
                type == WellKnownTypes.stringBuilderTypeDefinition -> return stringBuilderMethod(subject, key, actuals)
                type in INDEX_TYPES && key == "m:compareTo" -> {
                    check(value(subject))
                    args(actuals)
                    compareInts()
                }
                type == WellKnownTypes.intTypeDefinition && key == "m:toInt64" -> {
                    check(value(subject))
                    frame.pack64()
                }
                type == WellKnownTypes.int64TypeDefinition -> return int64Method(subject, key, actuals)
                type == WellKnownTypes.intTypeDefinition && key == "m:toString" ->
                    call(frame, rt.intToStringRadix, 2, true) {
                        check(value(subject))
                        args(actuals)
                    }
                type in LIST_TYPES -> return listMethod(subject, key, actuals)
                type in MAP_TYPES -> return mapMethod(subject, key, actuals)
                type == WellKnownTypes.dequeTypeDefinition -> when (key) {
                    "m:add" -> return rtCall(rt.listBuilderAdd, subject, actuals, false)
                    "m:removeFirst" -> return rtCall(rt.dequeRemoveFirst, subject, actuals, true)
                    "g:isEmpty" -> {
                        check(value(subject))
                        e.op(PietOp.Duplicate)
                        frame.heapLoad(LIST_LENGTH)
                        e.swap()
                        frame.heapLoad(DEQUE_HEAD)
                        e.op(Subtract)
                        e.op(Not)
                    }
                    else -> TODO("$key on Deque")
                }
                type == WellKnownTypes.denseBitVectorTypeDefinition -> when (key) {
                    "m:get" -> return rtCall(rt.bitVectorGet, subject, actuals, true)
                    "m:set" -> return rtCall(rt.bitVectorSet, subject, actuals, false)
                    else -> TODO("$key on DenseBitVector")
                }
                type == WellKnownTypes.pairTypeDefinition && key == "g:key" -> {
                    check(value(subject))
                    frame.heapLoad(PAIR_KEY)
                }
                type == WellKnownTypes.pairTypeDefinition && key == "g:value" -> {
                    check(value(subject))
                    frame.heapLoad(PAIR_VALUE)
                }
                else -> TODO("$key on ${subject.type}")
            }
            return true
        }

        /** Calls [target] with the subject and actuals, padding absent optionals with [defaults]. */
        private fun rtCall(
            target: Label,
            subject: TmpL.Expression,
            actuals: List<TmpL.Actual>,
            returnsValue: Boolean,
            vararg defaults: () -> Unit,
        ): Boolean {
            call(frame, target, 1 + actuals.size + defaults.size, returnsValue, bubbleLanding()) {
                check(value(subject))
                args(actuals)
                for (d in defaults) d()
            }
            return returnsValue
        }

        private fun stringMethod(subject: TmpL.Expression, key: String, actuals: List<TmpL.Actual>): Boolean {
            fun len() {
                check(value(subject))
                frame.heapLoad()
            }
            fun arg(i: Int) = check(value(actuals[i] as TmpL.Expression))
            when (key) {
                "g:isEmpty" -> {
                    len()
                    e.op(Not)
                }
                "g:end" -> len()
                "m:toString" -> check(value(subject))
                "m:countBetween" -> {
                    arg(1)
                    arg(0)
                    e.op(Subtract)
                    e.const(0)
                    e.maxTop()
                }
                "m:hasAtLeast" -> {
                    // !(n > b - a)
                    arg(2)
                    arg(1)
                    arg(0)
                    e.op(Subtract)
                    e.op(Greater)
                    e.op(Not)
                }
                "m:hasIndex" -> {
                    // !(0 > i) * (len > i)
                    e.const(0)
                    arg(0)
                    e.op(Greater)
                    e.op(Not)
                    len()
                    arg(0)
                    e.op(Greater)
                    e.op(Multiply)
                }
                "m:next" -> {
                    arg(0)
                    e.const(1)
                    e.op(Add)
                    len()
                    e.minTop()
                }
                "m:prev" -> {
                    arg(0)
                    e.const(1)
                    e.op(Subtract)
                    e.const(0)
                    e.maxTop()
                }
                "m:step" -> {
                    arg(0)
                    arg(1)
                    e.op(Add)
                    e.clampTop({ e.const(0) }, { len() })
                }
                "m:get" -> return rtCall(rt.stringGet, subject, actuals, true)
                "m:slice" -> return rtCall(rt.stringSlice, subject, actuals, true)
                "m:indexOf" -> return when (actuals.size) {
                    1 -> rtCall(rt.stringIndexOf, subject, actuals, true, { e.const(0) })
                    else -> rtCall(rt.stringIndexOf, subject, actuals, true)
                }
                "m:split" -> return rtCall(rt.stringSplit, subject, actuals, true)
                "m:forEach" -> return rtCall(rt.stringForEach, subject, actuals, false)
                "m:toInt32" -> return when (actuals.size) {
                    0 -> rtCall(rt.stringToInt, subject, actuals, true, { e.const(10) })
                    else -> rtCall(rt.stringToInt, subject, actuals, true)
                }
                "m:toInt64" -> return when (actuals.size) {
                    0 -> rtCall(rt.stringToInt64, subject, actuals, true, { e.const(10) })
                    else -> rtCall(rt.stringToInt64, subject, actuals, true)
                }
                else -> TODO("$key on String")
            }
            return true
        }

        private fun int64Method(subject: TmpL.Expression, key: String, actuals: List<TmpL.Actual>): Boolean {
            when (key) {
                "m:toString" -> return when (actuals.size) {
                    0 -> rtCall(rt.int64ToString, subject, actuals, true, { e.const(10) })
                    else -> rtCall(rt.int64ToString, subject, actuals, true)
                }
                "m:toInt32Unsafe" -> {
                    check(value(subject))
                    frame.unpack64()
                    wrapInt32()
                }
                "m:toInt32" -> {
                    // Bubbles outside Int32: v - (v wrapped) is 0 exactly when it fits.
                    check(value(subject))
                    frame.unpack64()
                    e.op(Duplicate)
                    e.op(Duplicate)
                    wrapInt32()
                    e.op(Subtract)
                    e.op(Not)
                    val ok = label("fits")
                    e.branch(ok, bubbleLanding(e.depth - 1))
                    e.startBlock(ok)
                }
                "m:min", "m:max" -> {
                    // Choose between the boxes themselves, which are small.
                    check(value(subject))
                    args(actuals)
                    e.pick(2)
                    e.pick(2)
                    frame.unpackPair64(reversed = false)
                    e.op(Greater)
                    if (key == "m:max") e.op(Not)
                    // [a, b, c] -> a + c * (b - a)
                    e.pick(2)
                    e.pick(4)
                    e.op(Subtract)
                    e.op(Multiply)
                    e.swap()
                    e.op(Pop)
                    e.op(Add)
                }
                else -> TODO("$key on Int64")
            }
            return true
        }

        private fun stringBuilderMethod(subject: TmpL.Expression, key: String, actuals: List<TmpL.Actual>): Boolean {
            when (key) {
                "m:append" -> return rtCall(
                    rt.stringBuilderAppendBetween,
                    subject,
                    actuals,
                    false,
                    { e.const(0) },
                    { e.const(1 shl 30) },
                )
                "m:appendBetween" -> return rtCall(rt.stringBuilderAppendBetween, subject, actuals, false)
                "m:appendCodePoint" -> return rtCall(rt.stringBuilderAppendCodePoint, subject, actuals, false)
                "m:toString" -> return rtCall(rt.stringBuilderToString, subject, actuals, true)
                "g:end" -> {
                    check(value(subject))
                    frame.heapLoad(LIST_LENGTH)
                }
                "m:clear" -> {
                    e.const(0)
                    check(value(subject))
                    frame.heapStore(LIST_LENGTH)
                    return false
                }
                else -> TODO("$key on StringBuilder")
            }
            return true
        }

        /**
         * Calls a list routine whose leading optional arguments are Ints:
         * an absent one, or a literal null, is pushed as [ABSENT]. [arity]
         * counts the arguments after the subject; the first [intArgs] of
         * them are the optional Ints, any others follow them.
         */
        private fun optionalIntCall(
            target: Label,
            subject: TmpL.Expression,
            actuals: List<TmpL.Actual>,
            arity: Int,
            returnsValue: Boolean,
            intArgs: Int = -1,
        ) {
            // add(x, at) and addAll(list, at) put the optional last; splice puts it first.
            call(frame, target, 1 + arity, returnsValue, bubbleLanding()) {
                check(value(subject))
                for (i in 0 until arity) {
                    val actual = actuals.getOrNull(i) as TmpL.Expression?
                    val isOptionalInt = if (intArgs < 0) i == arity - 1 else i < intArgs
                    when {
                        actual == null && isOptionalInt -> e.const(ABSENT)
                        actual == null -> e.const(NULL)
                        isOptionalInt && (
                            actual.type.definition == WellKnownTypes.nullTypeDefinition ||
                                (actual as? TmpL.ValueReference)?.value?.typeTag == TNull
                            ) -> e.const(ABSENT)
                        isOptionalInt && isNullableInt(actual.type) -> TODO("Int? argument $actual")
                        else -> check(value(actual))
                    }
                }
            }
        }

        /** Interned strings compare by address like everything else, so no key needs content comparison. */
        @Suppress("UnusedParameter")
        private fun stringKeys(mapType: Type2): Boolean = false

        private fun mapMethod(subject: TmpL.Expression, key: String, actuals: List<TmpL.Actual>): Boolean {
            val strings = { stringKeys(subject.type) }
            when (key) {
                "g:length" -> {
                    check(value(subject))
                    frame.heapLoad(LIST_LENGTH)
                }
                "m:get" -> return rtCall(rt.mapGet(strings(), false), subject, actuals, true)
                "m:getOr" -> return rtCall(rt.mapGet(strings(), true), subject, actuals, true)
                "m:has" -> return rtCall(rt.mapHas(strings()), subject, actuals, true)
                "m:set" -> return rtCall(rt.mapSet(strings()), subject, actuals, false)
                "m:remove" -> return rtCall(rt.mapRemove(strings()), subject, actuals, true)
                "m:clear" -> {
                    e.const(0)
                    check(value(subject))
                    frame.heapStore(LIST_LENGTH)
                    return false
                }
                "m:keys" -> return rtCall(rt.mapProject("keys"), subject, actuals, true)
                "m:values" -> return rtCall(rt.mapProject("values"), subject, actuals, true)
                "m:toList", "m:toListBuilder" -> return rtCall(rt.mapProject("pairs"), subject, actuals, true)
                "m:toListWith", "m:toListBuilderWith" -> return rtCall(rt.mapProject("with"), subject, actuals, true)
                "m:toMap", "m:toMapBuilder" -> return rtCall(rt.listCopy, subject, actuals, true)
                "m:forEach" -> return rtCall(rt.mapForEach, subject, actuals, false)
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
                    if (actuals.size == 1) {
                        rtCall(rt.listBuilderAdd, false)
                    } else {
                        optionalIntCall(rt.listBuilderInsert, subject, actuals, 2, false)
                    }
                    return false
                }
                "m:set" -> {
                    rtCall(rt.listBuilderSet, false)
                    return false
                }
                "m:addAll" -> {
                    optionalIntCall(rt.listBuilderAddAll, subject, actuals, 2, false)
                    return false
                }
                "m:removeLast" -> rtCall(rt.listBuilderRemoveLast, true)
                "m:reverse" -> {
                    rtCall(rt.listBuilderReverse, false)
                    return false
                }
                "m:clear" -> {
                    e.const(0)
                    check(value(subject))
                    frame.heapStore(LIST_LENGTH)
                    return false
                }
                "m:splice" -> optionalIntCall(rt.listBuilderSplice, subject, actuals, 3, true, intArgs = 2)
                "m:slice" -> rtCall(rt.listSlice, true)
                "m:getOr" -> rtCall(rt.listGetOr, true)
                "m:reduceFrom" -> call(frame, rt.listReduceFrom, 4, true, bubbleLanding()) {
                    check(value(subject))
                    args(actuals)
                    e.const(0)
                }
                "m:reduce" -> {
                    // The first element is the start; empty bubbles, as get(0) does.
                    call(frame, rt.listReduceFrom, 4, true, bubbleLanding()) {
                        check(value(subject))
                        call(frame, rt.listGet, 2, true, bubbleLanding()) {
                            // The list, under this call's ret, fail and D.
                            e.pick(4)
                            e.const(0)
                        }
                        args(actuals)
                        e.const(1)
                    }
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
                PietBuiltin.Ignore -> {
                    repeat(args(ps)) { e.op(Pop) }
                    return false
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
                    rt.internTop(frame)
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
                PietBuiltin.Listify -> listOf(ps)
                PietBuiltin.IsNull, PietBuiltin.NotNull -> {
                    val x = ps.single() as TmpL.Expression
                    requireNullable(x)
                    check(value(x))
                    e.const(NULL)
                    e.op(Subtract)
                    if (b == PietBuiltin.IsNull) e.op(Not)
                }
                PietBuiltin.EqGeneric, PietBuiltin.NeGeneric -> {
                    // Strings are interned, so equality on every type except Int64,
                    // whose boxes differ for equal values, is equality of cells.
                    val (l, r) = ps.map { it as TmpL.Expression }
                    if (l.type.definition == WellKnownTypes.int64TypeDefinition) {
                        args(ps)
                        frame.unpackPair64(reversed = false)
                        e.op(Subtract)
                        e.op(Not)
                        if (b == PietBuiltin.NeGeneric) e.op(Not)
                        return true
                    }
                    for (x in listOf(l, r)) identityComparable(x)
                    args(ps)
                    e.op(Subtract)
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
                        WellKnownTypes.int64TypeDefinition -> {
                            args(ps)
                            compareBoxes64()
                        }
                        WellKnownTypes.stringTypeDefinition -> call(frame, rt.stringCompare, 2, true) { args(ps) }
                        WellKnownTypes.intTypeDefinition, in INDEX_TYPES -> {
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
                PietBuiltin.PlusIntInt64, PietBuiltin.MinusIntInt64, PietBuiltin.TimesIntInt64 -> {
                    args(ps)
                    frame.unpackPair64(reversed = false)
                    e.op(
                        when (b) {
                            PietBuiltin.PlusIntInt64 -> Add
                            PietBuiltin.MinusIntInt64 -> Subtract
                            else -> Multiply
                        },
                    )
                    frame.pack64()
                }
                PietBuiltin.MinusInt64 -> {
                    args(ps)
                    frame.unpack64()
                    e.const(-1)
                    e.op(Multiply)
                    frame.pack64()
                }
                PietBuiltin.DivIntInt64, PietBuiltin.ModIntInt64,
                PietBuiltin.DivIntInt64Safe, PietBuiltin.ModIntInt64Safe,
                -> {
                    args(ps)
                    frame.unpackPair64(reversed = false)
                    if (b == PietBuiltin.DivIntInt64 || b == PietBuiltin.ModIntInt64) {
                        val ok = label("nonzero")
                        e.op(Duplicate)
                        e.branch(ok, bubbleLanding(e.depth - 1))
                        e.startBlock(ok)
                    }
                    e.op(if (b == PietBuiltin.DivIntInt64 || b == PietBuiltin.DivIntInt64Safe) Divide else Mod)
                    frame.pack64()
                }
                PietBuiltin.BitwiseNegation32 -> {
                    // ~x = -x - 1, which cannot overflow on top of the stack.
                    args(ps)
                    e.const(-1)
                    e.op(Multiply)
                    e.const(1)
                    e.op(Subtract)
                }
                PietBuiltin.BitwiseNegation64 -> {
                    args(ps)
                    frame.unpack64()
                    e.const(-1)
                    e.op(Multiply)
                    e.const(1)
                    e.op(Subtract)
                    frame.pack64()
                }
                PietBuiltin.BitwiseAnd64, PietBuiltin.BitwiseOr64, PietBuiltin.BitwiseXor64 -> {
                    // Two's complement halves: each 32-bit half combines on its own.
                    val op = when (b) {
                        PietBuiltin.BitwiseAnd64 -> rt.bitwise("and", 0, 1)
                        PietBuiltin.BitwiseOr64 -> rt.bitwise("or", 1, -1)
                        else -> rt.bitwise("xor", 1, -2)
                    }
                    args(ps)
                    for (half in 0..1) {
                        call(frame, op, 2, true) {
                            e.pick(5 + half)
                            frame.heapLoad(half)
                            e.pick(5 + half)
                            frame.heapLoad(half)
                        }
                    }
                    frame.alloc(2)
                    e.dropUnder(2)
                }
                PietBuiltin.BitwiseShl32, PietBuiltin.BitwiseShl64, PietBuiltin.BitwiseShr32,
                PietBuiltin.BitwiseShr64, PietBuiltin.BitwiseShrUnsigned32, PietBuiltin.BitwiseShrUnsigned64,
                -> {
                    val wide = b.name.endsWith("64")
                    val left = b.name.startsWith("BitwiseShl")
                    val unsigned = "Unsigned" in b.name
                    call(frame, rt.shift(b.name, wide, left, unsigned), 2, true) { args(ps) }
                }
                PietBuiltin.PureVirtual, PietBuiltin.Panic -> {
                    repeat(args(ps)) { e.op(Pop) }
                    e.jumpAway(rt.panic)
                    return false
                }
            }
            return true
        }

        /**
         * `[v] -> [ok]`: whether v, whose static type is [source]'s, is a
         * [target]. Only values known to be objects or null can be tested:
         * an object's first cell is its class id, but an Int in an AnyValue
         * has no first cell, and reading one would read whatever is there.
         */
        private fun typeTest(source: TmpL.Expression, target: Type2) {
            val from = source.type
            val to = target.definition
            val targetInfo = types[to]
            val fromIsObject = types[from.definition] != null
            when {
                // An upcast: true unless null.
                staticSubtype(from.definition, to) -> {
                    e.const(NULL)
                    e.op(Subtract)
                    if (from.nullity != Nullity.OrNull) {
                        e.op(Pop)
                        e.const(1)
                    } else {
                        e.op(Not)
                        e.op(Not)
                    }
                }
                targetInfo != null && fromIsObject -> {
                    val classes = types.values.filter { it.classId != null && isSubtype(it, to) }.map { it.classId!! }
                    nullGuarded(from) {
                        frame.heapLoad()
                        // Class ids are distinct, so the sum is 0 or 1.
                        e.const(0)
                        for (id in classes) {
                            e.pick(2)
                            e.const(id)
                            e.op(Subtract)
                            e.op(Not)
                            e.op(Add)
                        }
                        e.dropUnder(1)
                    }
                }
                from.definition in INDEX_TYPES && to == WellKnownTypes.stringIndexTypeDefinition -> {
                    // An index is never negative; none is -1.
                    e.const(-1)
                    e.op(Greater)
                }
                from.definition in INDEX_TYPES && to == WellKnownTypes.noStringIndexTypeDefinition -> {
                    e.const(0)
                    e.swap()
                    e.op(Greater)
                }
                to == from.definition && from.nullity == Nullity.OrNull -> {
                    requireNullable(source)
                    e.const(NULL)
                    e.op(Subtract)
                    e.op(Not)
                    e.op(Not)
                }
                else -> TODO("type test from $from to $target: $source")
            }
        }

        /** `[v] -> [0]` if v is null, else [test] of v. */
        private fun nullGuarded(type: Type2, test: () -> Unit) {
            if (type.nullity != Nullity.OrNull) {
                test()
                return
            }
            val isNull = label("isnull")
            val notNull = label("notnull")
            val join = label("endnull")
            e.op(PietOp.Duplicate)
            e.const(NULL)
            e.op(Subtract)
            e.op(Not)
            val d0 = e.depth - 1
            e.branch(isNull, notNull)
            e.startBlock(isNull, d0)
            e.op(Pop)
            e.const(0)
            e.jump(join)
            e.startBlock(notNull, d0)
            test()
            e.jump(join)
            e.startBlock(join)
        }

        /** `[a, b] -> [(a > b) - (b > a)]` for two Int64 boxes. */
        private fun compareBoxes64() {
            e.pick(2)
            e.pick(2)
            frame.unpackPair64(reversed = false)
            e.op(Greater)
            // [a, b, a > b] -> [a > b, a, b]
            e.const(3)
            e.const(1)
            e.op(PietOp.Roll)
            frame.unpackPair64(reversed = true)
            e.op(Greater)
            e.op(Subtract)
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

        @Suppress("UnusedParameter") // A nullable Int is a box, so -1 still means null.
        private fun requireNullable(x: TmpL.Expression) {}

        /**
         * Types whose == is equality of cells: everything, now that strings
         * are interned, except a boxed nullable Int, where two boxes of the
         * same Int are different addresses.
         */
        private fun identityComparable(x: TmpL.Expression) {
            if (isNullableInt(x.type)) TODO("== on Int?: $x")
            if (x.type.definition == WellKnownTypes.int64TypeDefinition) TODO("identity == on Int64: $x")
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
        fun isNullableInt(t: Type2) = t.nullity == Nullity.OrNull && t.definition == WellKnownTypes.intTypeDefinition

        private val INDEX_TYPES = setOf(
            WellKnownTypes.stringIndexTypeDefinition,
            WellKnownTypes.stringIndexOptionTypeDefinition,
            WellKnownTypes.noStringIndexTypeDefinition,
        )

        private val MAP_TYPES = setOf(
            WellKnownTypes.mapTypeDefinition,
            WellKnownTypes.mapBuilderTypeDefinition,
            WellKnownTypes.mappedTypeDefinition,
        )

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
fun Emitter.ifThenElseJump(ifNonZero: Label, ifZero: Label) = branch(ifNonZero, ifZero)
