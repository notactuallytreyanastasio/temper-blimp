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

    // A failure cannot be a value: no Int is free to mean it, and npiet keeps
    // only 32 bits of anything it rolls. So a bubble is control flow, and
    // every frame carries a row to return to on failure.
    override val bubbleStrategy = BubbleBranchStrategy.Exceptions
    override val coroutineStrategy = CoroutineStrategy.TranslateToRegularFunction
    override val functionTypeStrategy = FunctionTypeStrategy.ToFunctionType

    // A computed jump is what every jump in the output already is.
    override val computedJumpStrategy = ComputedJumpStrategy.Use

    override fun representationOfVoid(genre: Genre) = RepresentationOfVoid.DoNotReifyVoid

    override fun getSupportCode(pos: Position, builtin: NamedBuiltinFun, genre: Genre): SupportCode? =
        builtin.builtinOperatorId?.let { PietBuiltin.byOperator[it] } ?: PietBuiltin.byName[builtin.name]

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
    val builtinName: String? = null,
) : NamedSupportCode, FunctionSupportCode {
    GetConsole(connectedKey = "core.getConsole()"),
    ConsoleLog(connectedKey = "core.type Console.log()"),
    Ignore(connectedKey = "core.ignore()"),
    StrCat(BuiltinOperatorId.StrCat),
    PlusIntInt(BuiltinOperatorId.PlusIntInt),
    MinusIntInt(BuiltinOperatorId.MinusIntInt),
    MinusInt(BuiltinOperatorId.MinusInt),
    TimesIntInt(BuiltinOperatorId.TimesIntInt),
    DivIntInt(BuiltinOperatorId.DivIntInt),
    DivIntIntSafe(BuiltinOperatorId.DivIntIntSafe),
    ModIntInt(BuiltinOperatorId.ModIntInt),
    ModIntIntSafe(BuiltinOperatorId.ModIntIntSafe),
    LtIntInt(BuiltinOperatorId.LtIntInt),
    GtIntInt(BuiltinOperatorId.GtIntInt),
    LeIntInt(BuiltinOperatorId.LeIntInt),
    GeIntInt(BuiltinOperatorId.GeIntInt),
    EqIntInt(BuiltinOperatorId.EqIntInt),
    NeIntInt(BuiltinOperatorId.NeIntInt),
    BooleanNegation(BuiltinOperatorId.BooleanNegation),
    PureVirtual(builtinName = "pureVirtual"),
    Panic(BuiltinOperatorId.Panic),
    Listify(BuiltinOperatorId.Listify),
    IsNull(BuiltinOperatorId.IsNull),
    NotNull(BuiltinOperatorId.NotNull),
    EqGeneric(BuiltinOperatorId.EqGeneric),
    NeGeneric(BuiltinOperatorId.NeGeneric),
    EqStrStr(BuiltinOperatorId.EqStrStr),
    NeStrStr(BuiltinOperatorId.NeStrStr),
    LtStrStr(BuiltinOperatorId.LtStrStr),
    LeStrStr(BuiltinOperatorId.LeStrStr),
    GtStrStr(BuiltinOperatorId.GtStrStr),
    GeStrStr(BuiltinOperatorId.GeStrStr),
    CmpStrStr(BuiltinOperatorId.CmpStrStr),
    CmpIntInt(BuiltinOperatorId.CmpIntInt),
    LtGeneric(BuiltinOperatorId.LtGeneric),
    LeGeneric(BuiltinOperatorId.LeGeneric),
    GtGeneric(BuiltinOperatorId.GtGeneric),
    GeGeneric(BuiltinOperatorId.GeGeneric),
    CmpGeneric(BuiltinOperatorId.CmpGeneric),
    BitwiseAnd32(BuiltinOperatorId.BitwiseAnd32),
    BitwiseOr32(BuiltinOperatorId.BitwiseOr32),
    BitwiseXor32(BuiltinOperatorId.BitwiseXor32),
    BitwiseAnd64(BuiltinOperatorId.BitwiseAnd64),
    BitwiseOr64(BuiltinOperatorId.BitwiseOr64),
    BitwiseXor64(BuiltinOperatorId.BitwiseXor64),
    BitwiseNegation32(BuiltinOperatorId.BitwiseNegation32),
    BitwiseNegation64(BuiltinOperatorId.BitwiseNegation64),
    BitwiseShl32(BuiltinOperatorId.BitwiseShl32),
    BitwiseShl64(BuiltinOperatorId.BitwiseShl64),
    BitwiseShr32(BuiltinOperatorId.BitwiseShr32),
    BitwiseShr64(BuiltinOperatorId.BitwiseShr64),
    BitwiseShrUnsigned32(BuiltinOperatorId.BitwiseShrUnsigned32),
    BitwiseShrUnsigned64(BuiltinOperatorId.BitwiseShrUnsigned64),
    PlusIntInt64(BuiltinOperatorId.PlusIntInt64),
    MinusIntInt64(BuiltinOperatorId.MinusIntInt64),
    TimesIntInt64(BuiltinOperatorId.TimesIntInt64),
    MinusInt64(BuiltinOperatorId.MinusInt64),
    DivIntInt64(BuiltinOperatorId.DivIntInt64),
    DivIntInt64Safe(BuiltinOperatorId.DivIntInt64Safe),
    ModIntInt64(BuiltinOperatorId.ModIntInt64),
    ModIntInt64Safe(BuiltinOperatorId.ModIntInt64Safe),
    ;

    override val baseName = ParsedName(name)
    override fun renderTo(tokenSink: TokenSink) = tokenSink.name(baseName, inOperatorPosition = false)

    companion object {
        val byOperator = entries.mapNotNull { b -> b.builtinOperatorId?.let { it to b } }.toMap()
        val byConnectedKey = entries.mapNotNull { b -> b.connectedKey?.let { it to b } }.toMap()
        val byName = entries.mapNotNull { b -> b.builtinName?.let { it to b } }.toMap()
    }
}
