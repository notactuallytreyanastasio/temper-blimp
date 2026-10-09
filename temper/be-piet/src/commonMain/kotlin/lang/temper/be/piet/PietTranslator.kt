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
    ) {
        val label = Label(name)
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

    /** The implementation of [key] a value of [info]'s type runs: its own, or a super type's default. */
    private fun resolve(info: TypeInfo, key: String): FnInfo? =
        info.own[key] ?: info.decl.superTypes.firstNotNullOfOrNull { superType ->
            types[superType.typeName.sourceDefinition]?.let { resolve(it, key) }
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

        val main = MainWriter(program, globals.size)
        val mainLocals = topLevels.filterIsInstance<TmpL.ModuleInitBlock>().flatMap { localsOf(it.body) }
        Scope(main.e, main.frame, writer = null, slotsFrom(mainLocals, 0)).apply {
            repeat(mainLocals.size) { e.const(0) }
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
        val writer = FunctionWriter(program, fn.label.hint, params.size, fn.label)
        val body = fn.body ?: TODO("${fn.name} has no body")
        val locals = localsOf(body)
        writer.begin(locals.size)
        val slots = slotsFrom(params.map { it.name.name }, writer.param(0)) +
            slotsFrom(locals, writer.param(params.size))
        Scope(writer.e, writer.frame, writer, slots, fn.returnsValue).apply {
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
    ) {
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
                is TmpL.LocalDeclaration -> stmt.init?.let { init ->
                    if (value(init)) frame.store(slots.getValue(stmt.name.name))
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
            when (val slot = slots[name]) {
                null -> frame.storeGlobal(globals[name] ?: TODO("store to $name"))
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
                        else -> TODO("value $v")
                    }
                }
                is TmpL.Reference -> {
                    val name = expr.id.name
                    when (val slot = slots[name]) {
                        null -> frame.loadGlobal(globals[name] ?: TODO("reference to $name"))
                        else -> frame.load(slot)
                    }
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
                    val info = functions[name] ?: TODO("call to $name")
                    val landing = bubbleLanding()
                    call(frame, info.label, expr.parameters.size, info.returnsValue, landing) {
                        args(expr.parameters)
                    }
                    return info.returnsValue
                }
                is TmpL.MethodReference -> {
                    val subject = fn.subject as? TmpL.Expression ?: TODO("static method $expr")
                    return methodCall(subject, "m:${fn.methodName.dotNameText}", expr.parameters)
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
                PietBuiltin.PureVirtual -> {
                    // An abstract method's body. Dispatch never reaches one.
                    bubble()
                    return false
                }
            }
            return true
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
