package lang.temper.be.blimp

import lang.temper.be.tmpl.InlineSupportCode
import lang.temper.be.tmpl.NamedSupportCode
import lang.temper.be.tmpl.TypedArg
import lang.temper.format.TokenSink
import lang.temper.log.Position
import lang.temper.name.OutName
import lang.temper.name.ParsedName
import lang.temper.name.name
import lang.temper.type2.Type2
import lang.temper.value.BuiltinOperatorId
import lang.temper.value.pureVirtualBuiltinName

/**
 * Support code that becomes Blimp syntax at the call site.
 *
 * Modelled on be-lua's `InlineLua`: the [callFactory] split is what the
 * translator calls when it meets a `TmpL.InlineSupportCodeWrapper`, which keeps
 * [inlineToTree]'s unused translator parameter out of the way.
 *
 * TODO The translator type parameter is [Any] until BlimpTranslator exists.
 *  Nothing here reads it.
 */
internal abstract class BlimpInlineSupportCode(
    /** The `@connected` key, e.g. `core.type Console.log()`. Doubles as the name hint. */
    val connectedKey: String,
    override val builtinOperatorId: BuiltinOperatorId? = null,
) : InlineSupportCode<Blimp.Tree, Any>, NamedSupportCode {

    override val baseName: ParsedName get() = ParsedName(connectedKey)

    /** temper-core helpers this support code's expansion calls, if any. */
    open val preludeHelpers: Set<String> get() = setOf()

    override val needsThisEquivalent: Boolean get() = false

    override fun renderTo(tokenSink: TokenSink) = tokenSink.name(baseName, inOperatorPosition = false)

    /**
     * Builds the Blimp expression for one call.
     *
     * For a `@connected` *method* `args[0]` is the receiver and the declared
     * parameters follow, because TranslateDotHelper merges `this` into the
     * argument list before it reaches here.
     */
    abstract fun callFactory(pos: Position, args: List<Blimp.Expr>): Blimp.Tree

    final override fun inlineToTree(
        pos: Position,
        arguments: List<TypedArg<Blimp.Tree>>,
        returnType: Type2,
        translator: Any,
    ): Blimp.Tree = callFactory(pos, arguments.map { it.expr as Blimp.Expr })

    final override fun equals(other: Any?): Boolean =
        this === other || (other is BlimpInlineSupportCode && connectedKey == other.connectedKey)

    final override fun hashCode(): Int = connectedKey.hashCode()

    final override fun toString(): String = "BlimpSupportCode($connectedKey)"
}

/**
 * `core.getConsole()`.
 *
 * Blimp has no console object, and the only thing Temper does with the result
 * is call `log` on it, which [ConsoleLog] inlines while dropping the receiver.
 * So this is a placeholder, as it is in be-lua (a dummy `0.0`) and be-rust (a
 * dummy string literal).
 */
internal object GetConsole : BlimpInlineSupportCode("core.getConsole()") {
    override fun callFactory(pos: Position, args: List<Blimp.Expr>): Blimp.Tree = Blimp.NilLit(pos)
}

/**
 * `core.type Console.log()` becomes `puts(message)`.
 *
 * `puts` writes the string raw plus a newline. `print` would quote it and
 * truncate at 4096 bytes, which is not what `console.log` means.
 *
 * `args` is `[receiver, message]`, and the receiver is [GetConsole]'s `nil`.
 */
internal object ConsoleLog : BlimpInlineSupportCode("core.type Console.log()") {
    override fun callFactory(pos: Position, args: List<Blimp.Expr>): Blimp.Tree =
        Blimp.Call(pos, callee = Blimp.Id(pos, OutName(PUTS, null)), args = listOf(args.last()))
}

/** The Blimp builtin that writes a string to stdout unquoted and untruncated. */
internal const val PUTS = "puts"

/**
 * A `@connected` member that becomes a call to a Blimp builtin or a
 * temper-core helper, with the receiver passed as the first argument.
 */
internal class BlimpConnectedCall(
    connectedKey: String,
    private val fn: String,
    override val preludeHelpers: Set<String> = setOf(),
) : BlimpInlineSupportCode(connectedKey) {
    override fun callFactory(pos: Position, args: List<Blimp.Expr>): Blimp.Tree =
        Blimp.Call(pos, callee = Blimp.Id(pos, OutName(fn, null)), args = args)
}

/** The temper-core helper that restores a whole float's decimal point. */
internal const val TEMPER_FLOAT_TO_STRING = "temper_float_to_string"

/** Every `@connected` key be-blimp understands, keyed by the key string. */
internal val blimpConnectedReferences: Map<String, BlimpInlineSupportCode> =
    listOf(
        ConsoleLog,
        GetConsole,
        // Blimp's to_string covers Int, Int64, Boolean and String directly.
        BlimpConnectedCall("core.type Int32.toString()", "to_string"),
        BlimpConnectedCall("core.type Int64.toString()", "to_string"),
        BlimpConnectedCall("core.type Boolean.toString()", "to_string"),
        BlimpConnectedCall("core.type String.toString()", "to_string"),
        // Float needs the helper: Blimp prints a whole float as "1", Temper as "1.0".
        BlimpConnectedCall(
            "core.type Float64.toString()",
            TEMPER_FLOAT_TO_STRING,
            preludeHelpers = setOf(TEMPER_FLOAT_TO_STRING),
        ),
    ).associateBy { it.connectedKey }

/**
 * A builtin operator that becomes a Blimp expression.
 *
 * [connectedKey] is only a name hint here; these arrive through
 * `getSupportCode(NamedBuiltinFun)` keyed on [builtinOperatorId], not through
 * a `@connected` key.
 */
internal class BlimpOperatorSupportCode(
    name: String,
    operatorId: BuiltinOperatorId,
    override val preludeHelpers: Set<String> = setOf(),
    private val build: (Position, List<Blimp.Expr>) -> Blimp.Tree,
) : BlimpInlineSupportCode(name, operatorId) {
    override fun callFactory(pos: Position, args: List<Blimp.Expr>): Blimp.Tree = build(pos, args)
}

/** `a <op> b`. */
private fun infix(id: BuiltinOperatorId, op: BlimpOperator) = BlimpOperatorSupportCode(op.name, id) { pos, args ->
    Blimp.Operation(pos, left = args[0], operator = Blimp.Operator(pos, op), right = args[1])
}

/** `<op> a`. */
private fun prefix(id: BuiltinOperatorId, op: BlimpOperator) = BlimpOperatorSupportCode(op.name, id) { pos, args ->
    Blimp.Operation(pos, left = null, operator = Blimp.Operator(pos, op), right = args[0])
}

/** `f(a, b, ...)`, a call to a Blimp builtin or a temper-core helper. */
private fun call(id: BuiltinOperatorId, fn: String, helpers: Set<String> = setOf()) =
    BlimpOperatorSupportCode(fn, id, helpers) { pos, args ->
        Blimp.Call(pos, callee = Blimp.Id(pos, OutName(fn, null)), args = args)
    }

/**
 * `temper_int32(a <op> b)`.
 *
 * Temper's Int is 32-bit and wraps; Blimp's is 64-bit and panics the process
 * on i64 overflow. Both operands are already in range, so a product stays well
 * under 2^62 and wrapping after each operation never trips that panic. This is
 * the same shape as be-rust's `wrapping_add` / `wrapping_mul`.
 */
private fun wrapping(id: BuiltinOperatorId, op: BlimpOperator) =
    BlimpOperatorSupportCode("int32_${op.name}", id, setOf(TEMPER_INT32)) { pos, args ->
        Blimp.Call(
            pos,
            callee = Blimp.Id(pos, OutName(TEMPER_INT32, null)),
            args = listOf(
                when (args.size) {
                    1 -> Blimp.Operation(pos, left = null, operator = Blimp.Operator(pos, op), right = args[0])
                    else -> Blimp.Operation(pos, left = args[0], operator = Blimp.Operator(pos, op), right = args[1])
                },
            ),
        )
    }

/** The temper-core helper that wraps an Int to 32 bits. */
internal const val TEMPER_INT32 = "temper_int32"

/** temper-core bit operations, done arithmetically since Blimp has no bitwise operators. */
internal const val TEMPER_BIT_AND = "temper_bit_and"
internal const val TEMPER_BIT_OR = "temper_bit_or"
internal const val TEMPER_BIT_XOR = "temper_bit_xor"
internal const val TEMPER_BIT_NOT = "temper_bit_not"
internal const val TEMPER_SHL32 = "temper_shl32"
internal const val TEMPER_SHR32 = "temper_shr32"
internal const val TEMPER_USHR32 = "temper_ushr32"

/** Everything a bit operation leans on: the loop, the unsigned view and the wrap. */
private val bitHelpers = setOf(
    "temper_bitop", "temper_bitop_loop", "temper_u32", TEMPER_INT32, "temper_pow2",
)

/** temper-core helpers that raise from anywhere, including a plain `def` body. */
internal const val TEMPER_BUBBLE = "temper_bubble"
internal const val TEMPER_PANIC = "temper_panic"

/** temper-core helpers that bubble on a zero divisor, as Temper's Int does. */
internal const val TEMPER_INT_DIV = "temper_int_div"
internal const val TEMPER_INT_REM = "temper_int_rem"

/**
 * Builtin operators this backend knows, keyed by [BuiltinOperatorId].
 *
 * Anything absent makes the frontend report "Cannot translate builtin X not
 * supported by Blimp Backend", which is the intended way to find the next gap.
 */
internal val blimpOperators: Map<BuiltinOperatorId, BlimpOperatorSupportCode> = listOf(
    // Int arithmetic wraps to 32 bits.
    wrapping(BuiltinOperatorId.PlusIntInt, BlimpOperator.Addition),
    wrapping(BuiltinOperatorId.MinusIntInt, BlimpOperator.Subtraction),
    wrapping(BuiltinOperatorId.TimesIntInt, BlimpOperator.Multiplication),
    wrapping(BuiltinOperatorId.MinusInt, BlimpOperator.Negate),
    // Int64 is Blimp's native width, so no wrapping. It does inherit Blimp's
    // panic on i64 overflow where Temper would wrap.
    infix(BuiltinOperatorId.PlusIntInt64, BlimpOperator.Addition),
    infix(BuiltinOperatorId.MinusIntInt64, BlimpOperator.Subtraction),
    infix(BuiltinOperatorId.TimesIntInt64, BlimpOperator.Multiplication),
    prefix(BuiltinOperatorId.MinusInt64, BlimpOperator.Negate),
    // Blimp's `/` truncates toward zero on Int and `rem` matches its sign,
    // which is what Temper wants. There is no `%` operator.
    infix(BuiltinOperatorId.DivIntIntSafe, BlimpOperator.Division),
    infix(BuiltinOperatorId.DivIntInt64Safe, BlimpOperator.Division),
    call(BuiltinOperatorId.ModIntIntSafe, "rem"),
    call(BuiltinOperatorId.ModIntInt64Safe, "rem"),
    // Float64 maps straight across.
    infix(BuiltinOperatorId.PlusFltFlt, BlimpOperator.Addition),
    infix(BuiltinOperatorId.MinusFltFlt, BlimpOperator.Subtraction),
    infix(BuiltinOperatorId.TimesFltFlt, BlimpOperator.Multiplication),
    infix(BuiltinOperatorId.DivFltFlt, BlimpOperator.Division),
    prefix(BuiltinOperatorId.MinusFlt, BlimpOperator.Negate),
    // Comparisons. Blimp compares Int, Float and String with the same operators.
    infix(BuiltinOperatorId.LtIntInt, BlimpOperator.LessThan),
    infix(BuiltinOperatorId.LeIntInt, BlimpOperator.LessEquals),
    infix(BuiltinOperatorId.GtIntInt, BlimpOperator.GreaterThan),
    infix(BuiltinOperatorId.GeIntInt, BlimpOperator.GreaterEquals),
    infix(BuiltinOperatorId.EqIntInt, BlimpOperator.Equals),
    infix(BuiltinOperatorId.NeIntInt, BlimpOperator.NotEquals),
    infix(BuiltinOperatorId.LtFltFlt, BlimpOperator.LessThan),
    infix(BuiltinOperatorId.LeFltFlt, BlimpOperator.LessEquals),
    infix(BuiltinOperatorId.GtFltFlt, BlimpOperator.GreaterThan),
    infix(BuiltinOperatorId.GeFltFlt, BlimpOperator.GreaterEquals),
    infix(BuiltinOperatorId.EqFltFlt, BlimpOperator.Equals),
    infix(BuiltinOperatorId.NeFltFlt, BlimpOperator.NotEquals),
    infix(BuiltinOperatorId.LtStrStr, BlimpOperator.LessThan),
    infix(BuiltinOperatorId.LeStrStr, BlimpOperator.LessEquals),
    infix(BuiltinOperatorId.GtStrStr, BlimpOperator.GreaterThan),
    infix(BuiltinOperatorId.GeStrStr, BlimpOperator.GreaterEquals),
    infix(BuiltinOperatorId.EqStrStr, BlimpOperator.Equals),
    infix(BuiltinOperatorId.NeStrStr, BlimpOperator.NotEquals),
    infix(BuiltinOperatorId.EqGeneric, BlimpOperator.Equals),
    infix(BuiltinOperatorId.NeGeneric, BlimpOperator.NotEquals),
    // Boolean negation is `!`; `not` is a builtin function, not an operator.
    prefix(BuiltinOperatorId.BooleanNegation, BlimpOperator.Not),
    // `++` concatenates strings.
    infix(BuiltinOperatorId.StrCat, BlimpOperator.Concat),
    // Blimp has no bitwise operators; temper-core does these arithmetically.
    call(BuiltinOperatorId.BitwiseAnd32, TEMPER_BIT_AND, bitHelpers + TEMPER_BIT_AND),
    call(BuiltinOperatorId.BitwiseOr32, TEMPER_BIT_OR, bitHelpers + TEMPER_BIT_OR),
    call(BuiltinOperatorId.BitwiseXor32, TEMPER_BIT_XOR, bitHelpers + TEMPER_BIT_XOR),
    call(BuiltinOperatorId.BitwiseNegation32, TEMPER_BIT_NOT, setOf(TEMPER_BIT_NOT, TEMPER_INT32)),
    call(BuiltinOperatorId.BitwiseShl32, TEMPER_SHL32, bitHelpers + TEMPER_SHL32),
    call(BuiltinOperatorId.BitwiseShr32, TEMPER_SHR32, bitHelpers + TEMPER_SHR32),
    call(BuiltinOperatorId.BitwiseShrUnsigned32, TEMPER_USHR32, bitHelpers + TEMPER_USHR32),
    // Generic comparisons fall back to Blimp's polymorphic operators.
    infix(BuiltinOperatorId.LtGeneric, BlimpOperator.LessThan),
    infix(BuiltinOperatorId.LeGeneric, BlimpOperator.LessEquals),
    infix(BuiltinOperatorId.GtGeneric, BlimpOperator.GreaterThan),
    infix(BuiltinOperatorId.GeGeneric, BlimpOperator.GreaterEquals),
    // The unchecked variants bubble on a zero divisor, as Temper's Int does.
    call(BuiltinOperatorId.DivIntInt, TEMPER_INT_DIV, setOf(TEMPER_INT_DIV, TEMPER_INT32)),
    call(BuiltinOperatorId.ModIntInt, TEMPER_INT_REM, setOf(TEMPER_INT_REM)),
    call(BuiltinOperatorId.DivIntInt64, TEMPER_INT_DIV, setOf(TEMPER_INT_DIV, TEMPER_INT32)),
    call(BuiltinOperatorId.ModIntInt64, TEMPER_INT_REM, setOf(TEMPER_INT_REM)),
    // `nil?` is Blimp's null test; a non-null assertion is the value itself,
    // because Temper has already proved it.
    call(BuiltinOperatorId.IsNull, "nil?"),
    BlimpOperatorSupportCode("not_null", BuiltinOperatorId.NotNull) { _, args -> args[0] },
    // Temper's list constructor is variadic; Blimp has a list literal.
    BlimpOperatorSupportCode("list", BuiltinOperatorId.Listify) { pos, args -> Blimp.ListLit(pos, items = args) },
    // `bubble` only parses inside a handler or a case arm, so both go through
    // temper-core helpers that are callable from anywhere and catchable by
    // try/catch, which is what BubbleBranchStrategy.Exceptions expects.
    call(BuiltinOperatorId.Bubble, TEMPER_BUBBLE, setOf(TEMPER_BUBBLE)),
    call(BuiltinOperatorId.Panic, TEMPER_PANIC, setOf(TEMPER_PANIC)),
).associateBy { it.builtinOperatorId!! }

/**
 * Temper's `hole(directive)` becomes Blimp's own hole operator.
 *
 * This is the one place where Blimp can do more with a Temper construct than
 * the other backends. A hole carries no BuiltinOperatorId, so a backend that
 * has not been taught about one says so at build time; Blimp has a real typed
 * gap that carries the directive to whoever fills it.
 */
internal object Hole : BlimpInlineSupportCode("hole") {
    override fun callFactory(pos: Position, args: List<Blimp.Expr>): Blimp.Tree =
        Blimp.Hole(pos, directive = (args.firstOrNull() as? Blimp.StringLit)?.value ?: "fill this in")
}

/**
 * The body of an abstract method that a concrete class must override.
 *
 * Reaching one means dispatch found no implementation, which in Blimp means
 * the actor simply has no such handler -- but the marker still reaches the
 * translator for the abstract declaration itself, so it raises.
 */
internal object PureVirtual : BlimpInlineSupportCode(pureVirtualBuiltinName.builtinKey) {
    override val preludeHelpers: Set<String> get() = setOf(TEMPER_PANIC)

    override fun callFactory(pos: Position, args: List<Blimp.Expr>): Blimp.Tree =
        Blimp.Call(pos, callee = Blimp.Id(pos, OutName(TEMPER_PANIC, null)), args = listOf())
}
