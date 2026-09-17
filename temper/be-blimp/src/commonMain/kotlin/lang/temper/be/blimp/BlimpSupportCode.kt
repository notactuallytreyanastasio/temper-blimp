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

/** Every `@connected` key be-blimp understands, keyed by the key string. */
internal val blimpConnectedReferences: Map<String, BlimpInlineSupportCode> =
    listOf(
        ConsoleLog,
        GetConsole,
    ).associateBy { it.connectedKey }
