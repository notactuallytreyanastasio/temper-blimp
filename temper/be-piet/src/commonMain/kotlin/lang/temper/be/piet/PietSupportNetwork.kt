package lang.temper.be.piet

import lang.temper.be.TargetLanguageTypeName
import lang.temper.be.tmpl.BubbleBranchStrategy
import lang.temper.be.tmpl.ComputedJumpStrategy
import lang.temper.be.tmpl.CoroutineStrategy
import lang.temper.be.tmpl.FunctionSupportCode
import lang.temper.be.tmpl.FunctionTypeStrategy
import lang.temper.be.tmpl.NamedSupportCode
import lang.temper.be.tmpl.OptionalSupportCodeKind
import lang.temper.be.tmpl.RepresentationOfVoid
import lang.temper.be.tmpl.SupportCode
import lang.temper.be.tmpl.SupportNetwork
import lang.temper.format.TokenSink
import lang.temper.lexer.Genre
import lang.temper.log.Position
import lang.temper.name.ParsedName
import lang.temper.name.name
import lang.temper.type2.Signature2
import lang.temper.type2.Type2
import lang.temper.value.BuiltinOperatorId
import lang.temper.value.NamedBuiltinFun

object PietSupportNetwork : SupportNetwork {
    override val backendDescription = "Piet Backend"

    // TODO Choose once calls exist. Results keeps every call an ordinary
    //  return, which suits a stack machine better than unwinding frames.
    override val bubbleStrategy = BubbleBranchStrategy.Results
    override val coroutineStrategy = CoroutineStrategy.TranslateToRegularFunction
    override val functionTypeStrategy = FunctionTypeStrategy.ToFunctionType

    // A computed jump is what every jump in the output already is.
    override val computedJumpStrategy = ComputedJumpStrategy.Use

    override fun representationOfVoid(genre: Genre) = RepresentationOfVoid.DoNotReifyVoid

    override fun getSupportCode(pos: Position, builtin: NamedBuiltinFun, genre: Genre): SupportCode? =
        builtin.builtinOperatorId?.let { PietBuiltin.byOperator[it] }

    override fun optionalSupportCode(
        optionalSupportCodeKind: OptionalSupportCodeKind,
    ): Pair<SupportCode, Signature2>? = null

    override fun translateConnectedReference(pos: Position, connectedKey: String, genre: Genre): SupportCode? =
        PietBuiltin.byConnectedKey[connectedKey]

    override fun translatedConnectedType(
        pos: Position,
        connectedKey: String,
        genre: Genre,
        temperType: Type2,
    ): Pair<TargetLanguageTypeName, List<Type2>>? = null
}

/**
 * Operations the translator implements inline. The support network only
 * names them; [PietTranslator] decides what each one becomes.
 */
enum class PietBuiltin(
    override val builtinOperatorId: BuiltinOperatorId? = null,
    val connectedKey: String? = null,
) : NamedSupportCode, FunctionSupportCode {
    GetConsole(connectedKey = "core.getConsole()"),
    ConsoleLog(connectedKey = "core.type Console.log()"),
    ;

    override val baseName = ParsedName(name)
    override fun renderTo(tokenSink: TokenSink) = tokenSink.name(baseName, inOperatorPosition = false)

    companion object {
        val byOperator = entries.mapNotNull { b -> b.builtinOperatorId?.let { it to b } }.toMap()
        val byConnectedKey = entries.mapNotNull { b -> b.connectedKey?.let { it to b } }.toMap()
    }
}
