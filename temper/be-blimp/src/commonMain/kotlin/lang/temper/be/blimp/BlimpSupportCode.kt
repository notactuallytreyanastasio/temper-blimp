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

/** temper-core shims over Blimp builtins whose shape differs from Temper's. */
internal const val TEMPER_SLICE = "temper_slice"
internal const val TEMPER_STRING_END = "temper_string_end"
internal const val TEMPER_HAS_INDEX = "temper_string_has_index"
internal const val TEMPER_IDENTITY = "temper_identity"

/**
 * Marks that the translated module needs temper-core spliced in.
 *
 * The backend emits the whole prelude when any helper is used, so the exact
 * names matter only as a flag.
 */
internal val needsCore = setOf("temper_core")

/** Comparison helpers, since Blimp's < and > are a type error on strings. */
internal const val TEMPER_CMP = "temper_cmp"
internal val strCmpHelpers = setOf(
    "temper_str_cmp", "temper_str_cmp_loop",
    "temper_str_lt", "temper_str_le", "temper_str_gt", "temper_str_ge",
)
internal val cmpHelpers = strCmpHelpers + TEMPER_CMP

/** List operations Blimp lacks, plus the loops they run on. */
internal val listHelpers = setOf(
    "temper_filter", "temper_filter_loop", "temper_join", "temper_join_loop", "temper_for_each",
)

/** The UTF-8 layer, which the code-point string operations all lean on. */
internal val utf8Helpers = setOf(
    "temper_string_code_point_at", "temper_string_next", "u8_encode", "u8_decode_at", "u8_seq_len",
)

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
 * A `@connected` member that becomes a message send to the receiver.
 *
 * Temper's mutable ListBuilder has no counterpart in Blimp, where lists are
 * immutable, so temper-core makes it an actor -- which is what the language is
 * for -- and its methods are ordinary sends.
 */
internal class BlimpConnectedSend(
    connectedKey: String,
    private val message: String,
    /**
     * Messages to use at particular argument counts, excluding the receiver.
     *
     * Blimp dispatches a handler on its name alone, not its arity, so a Temper
     * method with optional parameters arrives at several arities and needs a
     * name for each. `ListBuilder.splice` takes three optional parameters and
     * so turns up with one, two or three.
     */
    private val byArity: Map<Int, String> = mapOf(),
) : BlimpInlineSupportCode(connectedKey) {
    override val preludeHelpers: Set<String> get() = needsCore

    override fun callFactory(pos: Position, args: List<Blimp.Expr>): Blimp.Tree {
        val actuals = args.drop(1)
        return Blimp.Send(
            pos,
            target = args.first(),
            message = Blimp.MessageCall(
                pos,
                name = Blimp.Atom(pos, byArity[actuals.size] ?: message),
                args = actuals,
            ),
        )
    }
}

/**
 * A `@connected` member that becomes a call to a Blimp builtin or a
 * temper-core helper, with the receiver passed as the first argument.
 */
internal class BlimpConnectedCall(
    connectedKey: String,
    private val fn: String,
    override val preludeHelpers: Set<String> = setOf(),
    /**
     * Helpers to use at particular argument counts.
     *
     * A Blimp `def` has a fixed arity and no defaults, so a Temper member that
     * arrives at more than one arity needs a helper per shape. `Map`'s
     * constructor turns up bare and with a list of `Pair`s.
     */
    private val byArity: Map<Int, String> = mapOf(),
    /**
     * Arity to fill out with `nil`, for a member with optional parameters.
     *
     * Temper drops the optionals the caller left out, so `near` arrives with
     * two arguments or four, while the helper is one `def` of fixed arity that
     * tests each optional for nil. This is [BlimpTranslator.padOptional] for
     * the connected calls, which do not go through it.
     */
    private val padTo: Int = 0,
) : BlimpInlineSupportCode(connectedKey) {
    override fun callFactory(pos: Position, args: List<Blimp.Expr>): Blimp.Tree {
        val padded = when {
            args.size >= padTo -> args
            else -> args + List(padTo - args.size) { Blimp.NilLit(pos) }
        }
        return Blimp.Call(pos, callee = Blimp.Id(pos, OutName(byArity[args.size] ?: fn, null)), args = padded)
    }
}

/** temper-core's checked narrowing from Int64 to Int32: bubbles rather than wrapping. */
internal const val TEMPER_FIT_INT32 = "temper_fit_int32"

/** temper-core's radix integer parsers, which Blimp's `to_int` cannot stand in for. */
internal const val TEMPER_PARSE_INT32 = "temper_parse_int32"
internal const val TEMPER_PARSE_INT64 = "temper_parse_int64"

/** The temper-core helper that builds a map from a list of `Pair`s. */
internal const val TEMPER_NEW_MAP_FROM = "temper_new_map_from"

/** The temper-core helper that restores a whole float's decimal point. */
internal const val TEMPER_FLOAT_TO_STRING = "temper_float_to_string"

/** What an infinity, a NaN and a signed zero need, none of which Blimp writes. */
internal val floatEdgeHelpers = setOf(
    "temper_float_div",
    "temper_float_inf",
    "temper_float_nan",
    "temper_float_is_negative",
)

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
        // Float maths. The interpreter grew these as builtins; before that a
        // `.sqrt()` went out as a message send and a Float is not an actor:
        //
        //     normSquared__2(x__6, y__7) <- :sqrt()
        //     -- NOT AN ACTOR ──────────────────────────────────
        BlimpConnectedCall("core.type Float64.sqrt()", "sqrt"),
        BlimpConnectedCall("core.type Float64.exp()", "exp"),
        BlimpConnectedCall("core.type Float64.expm1()", "expm1"),
        BlimpConnectedCall("core.type Float64.log()", "log"),
        BlimpConnectedCall("core.type Float64.log1p()", "log1p"),
        BlimpConnectedCall("core.type Float64.log2()", "log2"),
        BlimpConnectedCall("core.type Float64.log10()", "log10"),
        BlimpConnectedCall("core.type Float64.sin()", "sin"),
        BlimpConnectedCall("core.type Float64.cos()", "cos"),
        BlimpConnectedCall("core.type Float64.tan()", "tan"),
        BlimpConnectedCall("core.type Float64.asin()", "asin"),
        BlimpConnectedCall("core.type Float64.acos()", "acos"),
        BlimpConnectedCall("core.type Float64.atan()", "atan"),
        BlimpConnectedCall("core.type Float64.sinh()", "sinh"),
        BlimpConnectedCall("core.type Float64.cosh()", "cosh"),
        BlimpConnectedCall("core.type Float64.tanh()", "tanh"),
        BlimpConnectedCall("core.type Float64.abs()", "abs"),
        BlimpConnectedCall("core.type Float64.atan2()", "atan2"),
        // These four answer an Int in Blimp and a Float in Temper, and Blimp's
        // min and max take Ints only -- the checker rejects a Float before the
        // program runs -- so they go through temper-core.
        BlimpConnectedCall("core.type Float64.ceil()", "temper_float_ceil", setOf("temper_float_ceil")),
        BlimpConnectedCall("core.type Float64.floor()", "temper_float_floor", setOf("temper_float_floor")),
        BlimpConnectedCall("core.type Float64.round()", "temper_float_round", setOf("temper_float_round")),
        BlimpConnectedCall("core.type Float64.sign()", "temper_float_sign", setOf("temper_float_sign")),
        BlimpConnectedCall("core.type Float64.min()", "temper_float_min", setOf("temper_float_min", "temper_float_nan", "temper_float_inf")),
        BlimpConnectedCall("core.type Float64.max()", "temper_float_max", setOf("temper_float_max", "temper_float_nan", "temper_float_inf")),
        BlimpConnectedCall(
            "core.type Float64.near()",
            "temper_float_near",
            setOf("temper_float_near", "temper_float_max", "temper_float_nan", "temper_float_inf"),
            padTo = 4,
        ),
        // Int
        // `ignore(x)` evaluates x for effect and discards it. Blimp has no such
        // builtin, but an identity call is the same thing here.
        BlimpConnectedCall("core.ignore()", "temper_identity", setOf(TEMPER_IDENTITY)),
        BlimpConnectedCall("core.type Int32.min()", "min"),
        BlimpConnectedCall("core.type Int32.max()", "max"),
        // Blimp has one integer type, so Int64 shares Int32's builtins.
        BlimpConnectedCall("core.type Int64.min()", "min"),
        BlimpConnectedCall("core.type Int64.max()", "max"),
        BlimpConnectedCall("core.type Int32.toInt64()", "to_int"),
        // Blimp has one integer type, so narrowing is arithmetic, not a cast.
        // The two spellings differ in what they do when it does not fit:
        // `toInt32` bubbles, `toInt32Unsafe` wraps.
        BlimpConnectedCall("core.type Int64.toInt32()", TEMPER_FIT_INT32, needsCore),
        BlimpConnectedCall("core.type Int64.toInt32Unsafe()", TEMPER_INT32, setOf(TEMPER_INT32)),
        // Blimp's `to_int` reads decimal and takes no radix, so `"7FFFFFFF"
        // .toInt32(16)` needs a real parser. It also answers 0 for text that is
        // not a number at all, where Temper bubbles, so even the no-radix form
        // goes through temper-core.
        BlimpConnectedCall(
            "core.type String.toInt32()",
            TEMPER_PARSE_INT32,
            needsCore,
            byArity = mapOf(2 to TEMPER_PARSE_INT32),
        ),
        BlimpConnectedCall(
            "core.type String.toInt64()",
            TEMPER_PARSE_INT64,
            needsCore,
            byArity = mapOf(2 to TEMPER_PARSE_INT64),
        ),
        // Sequences. `empty?`, `length` and `elem` are Blimp builtins and work
        // on both strings and lists.
        BlimpConnectedCall("core.type Listed.get isEmpty()", "temper_is_empty", needsCore),
        BlimpConnectedCall("core.type Listed.get length()", "temper_len", needsCore),
        BlimpConnectedCall("core.type Listed.get()", "temper_get", needsCore),
        BlimpConnectedCall("core.type Listed.getOr()", "temper_get_or", needsCore),
        BlimpConnectedCall("core.type List.get length()", "temper_len", needsCore),
        BlimpConnectedCall("core.type List.get()", "temper_get", needsCore),
        BlimpConnectedCall("core.type ListBuilder.get length()", "length"),
        BlimpConnectedCall("core.type Listed.toList()", "temper_to_list", needsCore),
        BlimpConnectedCall("core.type List.toList()", "temper_to_list", needsCore),
        // String. Its indices are byte offsets, which is what Blimp uses too.
        BlimpConnectedCall("core.type String.get isEmpty()", "empty?"),
        BlimpConnectedCall("core.type String.toString()", "temper_identity", setOf(TEMPER_IDENTITY)),
        BlimpConnectedCall("core.type String.split()", "temper_string_split", utf8Helpers + needsCore),
        BlimpConnectedCall("core.type String.get end()", TEMPER_STRING_END, setOf(TEMPER_STRING_END)),
        BlimpConnectedCall("core.type String.hasIndex()", TEMPER_HAS_INDEX, setOf(TEMPER_HAS_INDEX)),
        BlimpConnectedCall("core.type String.get()", "temper_string_code_point_at", utf8Helpers),
        BlimpConnectedCall("core.type String.next()", "temper_string_next", utf8Helpers),
        BlimpConnectedCall("core.type String.prev()", "temper_string_prev", needsCore),
        BlimpConnectedCall("core.type String.step()", "temper_string_step", needsCore),
        BlimpConnectedCall("core.type String.countBetween()", "temper_string_count_between", needsCore),
        BlimpConnectedCall("core.type String.hasAtLeast()", "temper_string_has_at_least", needsCore),
        BlimpConnectedCall("core.type String.fromCodePoint()", "u8_encode", utf8Helpers),
        // Blimp's slice takes a length; Temper's takes an exclusive end.
        BlimpConnectedCall("core.type String.slice()", TEMPER_SLICE, setOf(TEMPER_SLICE)),
        BlimpConnectedCall("core.type Listed.slice()", "temper_list_slice", needsCore),
        // map, reduce and sort are Blimp builtins with the same argument order;
        // filter, join and forEach are not, so temper-core supplies them.
        BlimpConnectedCall("core.type Listed.map()", "temper_map", needsCore),
        BlimpConnectedCall("core.type Listed.sorted()", "temper_sort", needsCore),
        BlimpConnectedCall("core.type Listed.reduceFrom()", "temper_reduce", needsCore),
        BlimpConnectedCall("core.type Listed.reduce()", "temper_reduce1", needsCore),
        BlimpConnectedCall("core.type Listed.reduceFromIndex()", "temper_reduce_from_index", needsCore),
        BlimpConnectedCall("core.type Listed.filter()", "temper_filter", listHelpers),
        BlimpConnectedCall("core.type Listed.join()", "temper_join", listHelpers),
        BlimpConnectedCall("core.type Listed.forEach()", "temper_for_each", listHelpers),
        BlimpConnectedCall("core.type List.forEach()", "temper_for_each", listHelpers),
        // Blimp's maps take string keys only, so a Temper map is an actor
        // holding entries. Pair is connected too, so it needs one as well.
        // A StringIndex is an Int here, so `compareTo` cannot be a send. It is
        // already mapped as `CmpGeneric`, but a direct call on the value
        // arrives by connected key and would otherwise become `x <- :compareTo`.
        BlimpConnectedCall("core.type StringIndexOption.compareTo()", TEMPER_CMP, cmpHelpers),
        BlimpConnectedCall("core.type Pair.constructor()", "temper_new_pair", needsCore),
        BlimpConnectedCall(
            "core.type Map.constructor()",
            "temper_new_map",
            needsCore,
            byArity = mapOf(1 to TEMPER_NEW_MAP_FROM),
        ),
        BlimpConnectedCall(
            "core.type MapBuilder.constructor()",
            "temper_new_map",
            needsCore,
            byArity = mapOf(1 to TEMPER_NEW_MAP_FROM),
        ),
        BlimpConnectedCall("core.type Mapped.toMap()", "temper_copy_map", needsCore),
        BlimpConnectedCall("core.type Mapped.toMapBuilder()", "temper_copy_map", needsCore),
        BlimpConnectedCall("core.type Mapped.toListWith()", "temper_map_to_list_with", needsCore),
        BlimpConnectedSend("core.type Mapped.get length()", "length"),
        BlimpConnectedSend("core.type Mapped.get()", "get"),
        BlimpConnectedSend("core.type Mapped.getOr()", "getOr"),
        BlimpConnectedSend("core.type Mapped.has()", "has"),
        BlimpConnectedSend("core.type Mapped.keys()", "keys"),
        BlimpConnectedSend("core.type Mapped.values()", "values"),
        BlimpConnectedSend("core.type Mapped.toList()", "toList"),
        BlimpConnectedSend("core.type Mapped.forEach()", "forEach"),
        BlimpConnectedSend("core.type MapBuilder.set()", "set"),
        BlimpConnectedSend("core.type MapBuilder.remove()", "remove"),
        BlimpConnectedSend("core.type MapBuilder.clear()", "clear"),
        // A StringBuilder is mutable, so it is an actor too.
        BlimpConnectedCall("core.type StringBuilder.constructor()", "temper_new_string_builder", needsCore),
        BlimpConnectedSend("core.type StringBuilder.append()", "append"),
        BlimpConnectedSend("core.type StringBuilder.appendCodePoint()", "appendCodePoint"),
        BlimpConnectedSend("core.type StringBuilder.appendBetween()", "appendBetween"),
        BlimpConnectedSend("core.type StringBuilder.clear()", "clear"),
        BlimpConnectedSend("core.type StringBuilder.toString()", "toString"),
        BlimpConnectedSend("core.type StringBuilder.get end()", "end"),
        // A ListBuilder is an actor, so its methods are sends.
        BlimpConnectedCall("core.type ListBuilder.constructor()", "temper_new_list_builder", needsCore),
        BlimpConnectedSend("core.type ListBuilder.add()", "add", mapOf(2 to "add_at")),
        BlimpConnectedSend("core.type ListBuilder.splice()", "splice", mapOf(1 to "splice_from", 2 to "splice_range")),
        BlimpConnectedSend("core.type ListBuilder.addAll()", "addAll", mapOf(2 to "addAll_at")),
        BlimpConnectedSend("core.type ListBuilder.set()", "set"),
        BlimpConnectedSend("core.type ListBuilder.reverse()", "reverse"),
        BlimpConnectedSend("core.type ListBuilder.clear()", "clear"),
        BlimpConnectedSend("core.type ListBuilder.removeLast()", "removeLast"),
        BlimpConnectedSend("core.type ListBuilder.sort()", "sort"),
        BlimpConnectedSend("core.type ListBuilder.get length()", "length"),
        BlimpConnectedSend("core.type ListBuilder.get()", "get"),
        // Reading a builder back out, and sorting with a comparator, which
        // Blimp's own sort does not take.
        BlimpConnectedCall("core.type ListBuilder.toList()", "temper_to_list", needsCore),
        // A copy, not the same builder: the test that caught this modifies the
        // original after taking one and expects the copy not to follow.
        BlimpConnectedCall("core.type ListBuilder.toListBuilder()", "temper_new_list_builder_from", needsCore),
        BlimpConnectedCall("core.type Listed.toListBuilder()", "temper_new_list_builder_from", needsCore),
        BlimpConnectedCall("core.type List.toListBuilder()", "temper_new_list_builder_from", needsCore),
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

/** The cell a local lives in when a nested function assigns to it. */
internal const val TEMPER_NEW_CELL = "temper_new_cell"
internal const val CELL_GET = "get"
internal const val CELL_SET = "set"

/** A cast that can fail: checks the tag and bubbles if it does not match. */
internal const val TEMPER_CAST = "temper_cast"

/** temper-core's runtime type test, and the handler every translated actor carries. */
internal const val TEMPER_IS_A = "temper_is_a"
internal const val TYPES_MESSAGE = "__temper_types"
internal val isATypeHelpers = setOf(TEMPER_IS_A, "temper_list_has")

/** What `temper_cast` needs: the type test, plus the bubble it raises on a miss. */
internal val castTypeHelpers = isATypeHelpers + setOf(TEMPER_CAST, "temper_bubble")

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
    // `-2147483648 / -1` is the one division whose result leaves Int32 range,
    // so this wraps like every other Int32 operation. Int64 division below
    // does not: there is no wider type to come back from.
    wrapping(BuiltinOperatorId.DivIntIntSafe, BlimpOperator.Division),
    infix(BuiltinOperatorId.DivIntInt64Safe, BlimpOperator.Division),
    call(BuiltinOperatorId.ModIntIntSafe, "rem"),
    call(BuiltinOperatorId.ModIntInt64Safe, "rem"),
    // Float64 maps straight across.
    infix(BuiltinOperatorId.PlusFltFlt, BlimpOperator.Addition),
    infix(BuiltinOperatorId.MinusFltFlt, BlimpOperator.Subtraction),
    infix(BuiltinOperatorId.TimesFltFlt, BlimpOperator.Multiplication),
    // Not the operator: Blimp raises on a zero divisor and IEEE does not.
    call(BuiltinOperatorId.DivFltFlt, "temper_float_div", floatEdgeHelpers),
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
    // Blimp's < and > are a type error on strings, so these go through
    // temper-core, which compares byte by byte.
    call(BuiltinOperatorId.LtStrStr, "temper_str_lt", strCmpHelpers),
    call(BuiltinOperatorId.LeStrStr, "temper_str_le", strCmpHelpers),
    call(BuiltinOperatorId.GtStrStr, "temper_str_gt", strCmpHelpers),
    call(BuiltinOperatorId.GeStrStr, "temper_str_ge", strCmpHelpers),
    infix(BuiltinOperatorId.EqStrStr, BlimpOperator.Equals),
    infix(BuiltinOperatorId.NeStrStr, BlimpOperator.NotEquals),
    infix(BuiltinOperatorId.EqGeneric, BlimpOperator.Equals),
    call(BuiltinOperatorId.CmpIntInt, TEMPER_CMP, cmpHelpers),
    call(BuiltinOperatorId.CmpFltFlt, TEMPER_CMP, cmpHelpers),
    call(BuiltinOperatorId.CmpStrStr, TEMPER_CMP, cmpHelpers),
    call(BuiltinOperatorId.CmpGeneric, TEMPER_CMP, cmpHelpers),
    call(BuiltinOperatorId.ModFltFlt, "temper_fmod", setOf("temper_fmod")),
    call(BuiltinOperatorId.PowFltFlt, "temper_pow", setOf("temper_pow")),
    infix(BuiltinOperatorId.NeGeneric, BlimpOperator.NotEquals),
    // Boolean negation is `!`; `not` is a builtin function, not an operator.
    prefix(BuiltinOperatorId.BooleanNegation, BlimpOperator.Not),
    // StrCat is variadic -- `"a" ++ b ++ "c"` arrives as one call with three
    // arguments -- and Blimp's `concat` builtin is too, so the operator form
    // would silently drop everything past the second.
    //
    // One argument is not a concatenation, and `concat(x)` is a TypeError in
    // Blimp, so a one-piece interpolation -- `"${zero}"`, which the frontend
    // has already turned into a String -- would stop the program.
    BlimpOperatorSupportCode("concat", BuiltinOperatorId.StrCat, setOf()) { pos, args ->
        when (args.size) {
            0 -> Blimp.StringLit(pos, "")
            1 -> args[0]
            else -> Blimp.Call(pos, callee = Blimp.Id(pos, OutName("concat", null)), args = args)
        }
    },
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
