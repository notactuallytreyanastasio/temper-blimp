package lang.temper.be.blimp

import lang.temper.be.TargetLanguageTypeName
import lang.temper.be.tmpl.BubbleBranchStrategy
import lang.temper.be.tmpl.ComputedJumpStrategy
import lang.temper.be.tmpl.CoroutineStrategy
import lang.temper.be.tmpl.FunctionTypeStrategy
import lang.temper.be.tmpl.OptionalSupportCodeKind
import lang.temper.be.tmpl.RepresentationOfVoid
import lang.temper.be.tmpl.SupportCode
import lang.temper.be.tmpl.SupportNetwork
import lang.temper.lexer.Genre
import lang.temper.log.Position
import lang.temper.type2.Signature2
import lang.temper.type2.Type2
import lang.temper.value.NamedBuiltinFun

/**
 * Wires Temper builtins to Blimp support code.
 *
 * The strategies below are chosen for what Blimp actually has rather than for
 * what is convenient:
 *
 * - Blimp has first-class `bubble` / `try` / `catch`, so bubbles translate to
 *   exceptions rather than to checked return values.
 * - Blimp has `fn(...) do ... end` closures, so function types stay functions.
 * - Blimp has no computed goto or jump table, only `case`, so computed jumps
 *   are never used.
 * - Blimp's `nil` gives void a value to be.
 *
 * Coroutines are the one uncomfortable fit: Blimp has no generators, so the
 * eventual answer is likely to be an actor per coroutine. Until the translator
 * grows that, the generator strategy at least keeps the TmpL shape workable.
 */
object BlimpSupportNetwork : SupportNetwork {
    override val backendDescription: String
        get() = "Blimp Backend"

    override val bubbleStrategy: BubbleBranchStrategy = BubbleBranchStrategy.Exceptions

    override val coroutineStrategy: CoroutineStrategy = CoroutineStrategy.TranslateToGenerator

    override val functionTypeStrategy: FunctionTypeStrategy = FunctionTypeStrategy.ToFunctionType

    override val computedJumpStrategy = ComputedJumpStrategy.NeverUse

    override fun representationOfVoid(genre: Genre): RepresentationOfVoid = RepresentationOfVoid.ReifyVoid

    // TODO Map builtin operators onto Blimp's operators and builtins. Until
    //  then the translator falls back to the Temper implementations, which is
    //  enough to get the first functional tests through the pipeline.
    override fun getSupportCode(pos: Position, builtin: NamedBuiltinFun, genre: Genre): SupportCode? = null

    override fun optionalSupportCode(
        optionalSupportCodeKind: OptionalSupportCodeKind,
    ): Pair<SupportCode, Signature2>? = null

    override fun translateConnectedReference(pos: Position, connectedKey: String, genre: Genre): SupportCode? = null

    override fun translatedConnectedType(
        pos: Position,
        connectedKey: String,
        genre: Genre,
        temperType: Type2,
    ): Pair<TargetLanguageTypeName, List<Type2>>? = null
}
