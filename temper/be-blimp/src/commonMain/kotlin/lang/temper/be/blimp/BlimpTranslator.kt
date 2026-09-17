package lang.temper.be.blimp

import lang.temper.be.tmpl.TmpL
import lang.temper.value.TBoolean
import lang.temper.value.TClass
import lang.temper.value.TClosureRecord
import lang.temper.value.TFloat64
import lang.temper.value.TFunction
import lang.temper.value.TInt
import lang.temper.value.TInt64
import lang.temper.value.TList
import lang.temper.value.TListBuilder
import lang.temper.value.TMap
import lang.temper.value.TMapBuilder
import lang.temper.value.TNull
import lang.temper.value.TProblem
import lang.temper.value.TStageRange
import lang.temper.value.TString
import lang.temper.value.TSymbol
import lang.temper.value.TType
import lang.temper.value.TVoid

/**
 * Turns one [TmpL.Module] into Blimp items.
 *
 * Layout is dictated by two Blimp facts, both checked against the interpreter:
 *
 * - Blimp has no module system, so every module's items land in one file and
 *   Temper's module path becomes the dotted prefix of an actor name.
 * - A top-level forward reference to a `def` is an error, but a `def` called
 *   from inside an actor handler resolves at send time. So declarations are
 *   emitted before the statements that run them, which is why
 *   [translateModule] returns [declarations] and [mainStatements] separately
 *   rather than one interleaved list.
 *
 * Unhandled nodes are `TODO()` on purpose: the backend guide's advice is to
 * pick a functional test, see what breaks, and fill in the path it needs. A
 * loud failure with the node in the message is the point.
 */
internal class BlimpTranslator(private val module: TmpL.Module) {

    /** `actor` and `def` items, which must precede any code that runs them. */
    private val declarations = mutableListOf<Blimp.Item>()

    /** Module-level statements, which run in order after every declaration. */
    private val mainStatements = mutableListOf<Blimp.Statement>()

    /**
     * temper-core helpers this module called, e.g. `temper_int32`.
     *
     * Blimp has no module system, so the backend splices the prelude source
     * into the one output file, and only when something actually needs it.
     */
    private val preludeHelpers = mutableSetOf<String>()

    /**
     * Statements hoisted out of the expression currently being translated.
     *
     * Blimp accepts `x = case ... end` but rejects `f(case ... end)`, so an
     * expression that has no Blimp expression form is emitted here as an
     * assignment to a temporary and replaced by a reference to it.
     */
    private var hoisted = mutableListOf<Blimp.Statement>()

    fun translateModule(): Translated {
        for (topLevel in module.topLevels) {
            processTopLevel(topLevel)
        }
        return Translated(
            declarations = declarations.toList(),
            mainStatements = mainStatements.toList(),
            preludeHelpers = preludeHelpers.toSet(),
        )
    }

    data class Translated(
        val declarations: List<Blimp.Item>,
        val mainStatements: List<Blimp.Statement>,
        val preludeHelpers: Set<String>,
    )

    /** Records that [helper] from temper-core is needed, and returns a call to it. */
    @Suppress("unused") // Used as the expression translator grows past literals.
    private fun preludeCall(pos: lang.temper.log.Position, helper: String, args: List<Blimp.Expr>): Blimp.Expr {
        preludeHelpers.add(helper)
        return Blimp.Call(pos, callee = Blimp.Id(pos, lang.temper.name.OutName(helper, null)), args = args)
    }

    // ── Top levels ───────────────────────────────────────────────────────

    private fun processTopLevel(topLevel: TmpL.TopLevel) {
        when (topLevel) {
            is TmpL.ModuleInitBlock -> processModuleInitBlock(topLevel)
            is TmpL.ModuleLevelDeclaration -> processModuleLevelDeclaration(topLevel)
            is TmpL.ModuleFunctionDeclaration -> TODO("module function: $topLevel")
            is TmpL.TypeDeclaration -> TODO("type declaration: $topLevel")
            is TmpL.Test -> TODO("test: $topLevel")
            // TypeConnection, PooledValueDeclaration, SupportCodeDeclaration,
            // comments and garbage carry no Blimp output, as in be-rust.
            else -> {}
        }
    }

    private fun processModuleInitBlock(block: TmpL.ModuleInitBlock) {
        for (statement in block.body.statements) {
            translateStatementInto(statement, mainStatements)
        }
    }

    private fun processModuleLevelDeclaration(decl: TmpL.ModuleLevelDeclaration) {
        // The frontend injects a module-level `console` temporary for every
        // reference to the global console. Console.log is inlined at its call
        // site, so the temporary would only be a stray binding.
        if (decl.isConsole()) return
        TODO("module level declaration: $decl")
    }

    // ── Statements ───────────────────────────────────────────────────────

    /**
     * Appends the Blimp form of [statement] to [out], along with anything its
     * expressions had to hoist ahead of it.
     */
    private fun translateStatementInto(statement: TmpL.Statement, out: MutableList<Blimp.Statement>) {
        when (statement) {
            is TmpL.ExpressionStatement -> {
                val expr = translateExpressionHoisting(statement.expression, out)
                out.add(Blimp.ExprStatement(statement.pos, expr))
            }

            is TmpL.BlockStatement -> for (inner in statement.statements) translateStatementInto(inner, out)

            else -> TODO("statement: $statement")
        }
    }

    // ── Expressions ──────────────────────────────────────────────────────

    /** Translates [expression], draining any hoisted statements into [out] first. */
    private fun translateExpressionHoisting(
        expression: TmpL.Expression,
        out: MutableList<Blimp.Statement>,
    ): Blimp.Expr {
        val outerHoisted = hoisted
        hoisted = mutableListOf()
        val translated = try {
            translateExpression(expression)
        } finally {
            out.addAll(hoisted)
            hoisted = outerHoisted
        }
        return translated
    }

    private fun translateExpression(expression: TmpL.Expression): Blimp.Expr = when (expression) {
        is TmpL.ValueReference -> translateValueReference(expression)
        is TmpL.CallExpression -> translateCallExpression(expression)
        else -> TODO("expression: $expression")
    }

    private fun translateCallExpression(call: TmpL.CallExpression): Blimp.Expr =
        when (val fn = call.fn) {
            // Support code such as console.log becomes Blimp syntax right here.
            is TmpL.InlineSupportCodeWrapper ->
                (fn.supportCode as BlimpInlineSupportCode)
                    .callFactory(call.pos, call.parameters.map { translateActual(it) }) as Blimp.Expr

            else -> TODO("callable: $fn")
        }

    private fun translateActual(actual: TmpL.Actual): Blimp.Expr = when (actual) {
        is TmpL.Expression -> translateExpression(actual)
        else -> TODO("actual: $actual")
    }

    private fun translateValueReference(expression: TmpL.ValueReference): Blimp.Expr {
        val pos = expression.pos
        return when (val tag = expression.value.typeTag) {
            TBoolean -> Blimp.BoolLit(pos, TBoolean.unpack(expression.value))
            TFloat64 -> Blimp.NumberLit(pos, TFloat64.unpack(expression.value))
            TInt -> Blimp.NumberLit(pos, TInt.unpack(expression.value))
            TInt64 -> Blimp.NumberLit(pos, TInt64.unpack(expression.value))
            is TString -> Blimp.StringLit(pos, TString.unpack(expression.value))
            // Blimp's nil covers both, and RepresentationOfVoid.ReifyVoid means
            // a void value really does flow around.
            TNull, TVoid -> Blimp.NilLit(pos)
            is TClass, TClosureRecord, TFunction, TList, TListBuilder, TMap, TMapBuilder,
            TProblem, TStageRange, TSymbol, TType,
            -> TODO("value of type $tag: $expression")
        }
    }
}
