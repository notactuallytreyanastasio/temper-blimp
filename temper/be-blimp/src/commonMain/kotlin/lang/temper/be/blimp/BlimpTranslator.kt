package lang.temper.be.blimp

import lang.temper.ast.boundaryDescent
import lang.temper.be.tmpl.TmpL
import lang.temper.log.Position
import lang.temper.name.OutName
import lang.temper.name.ResolvedName
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

    private val names = BlimpNames()

    /**
     * Names bound in enclosing scopes, innermost last.
     *
     * Used to decide which locals a lowered loop has to carry, since a Blimp
     * top-level `def` closes over nothing.
     */
    private val scopes = ArrayDeque<MutableSet<ResolvedName>>()

    /** Module-level names, in scope for every init block in the module. */
    private val moduleScope = mutableSetOf<ResolvedName>()

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
            is TmpL.ModuleFunctionDeclaration -> declarations.add(translateFunction(topLevel))
            is TmpL.TypeDeclaration -> TODO("type declaration: $topLevel")
            is TmpL.Test -> TODO("test: $topLevel")
            // TypeConnection, PooledValueDeclaration, SupportCodeDeclaration,
            // comments and garbage carry no Blimp output, as in be-rust.
            else -> {}
        }
    }

    private fun processModuleInitBlock(block: TmpL.ModuleInitBlock) {
        scopes.addLast(moduleScope)
        try {
            for (statement in block.body.statements) {
                translateStatementInto(statement, mainStatements)
            }
        } finally {
            scopes.removeLast()
        }
    }

    private fun processModuleLevelDeclaration(decl: TmpL.ModuleLevelDeclaration) {
        // The frontend injects a module-level `console` temporary for every
        // reference to the global console. Console.log is inlined at its call
        // site, so the temporary would only be a stray binding.
        if (decl.isConsole()) return
        // Blimp has no module scope distinct from the file's top level, so a
        // module var is just an assignment that runs before the init blocks.
        nameOf(decl.name)?.let { moduleScope.add(it) }
        val value = when (val init = decl.init) {
            null -> Blimp.NilLit(decl.pos)
            else -> translateExpressionHoisting(init, mainStatements)
        }
        mainStatements.add(Blimp.Assign(decl.pos, target = idOf(decl.name), value = value))
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

            is TmpL.LocalDeclaration -> {
                nameOf(statement.name)?.let { scopes.lastOrNull()?.add(it) }
                // Blimp's assignment is also its declaration, and it has no
                // uninitialised form, so an init-less local starts as nil. That
                // binding matters: it is what makes a following `case` able to
                // assign to the name.
                val value = when (val init = statement.init) {
                    null -> Blimp.NilLit(statement.pos)
                    else -> translateExpressionHoisting(init, out)
                }
                out.add(Blimp.Assign(statement.pos, target = idOf(statement.name), value = value))
            }

            is TmpL.Assignment -> {
                val value = translateExpressionHoisting(statement.right, out)
                out.add(Blimp.Assign(statement.pos, target = idOf(statement.left), value = value))
            }

            is TmpL.WhileStatement -> translateWhileStatement(statement, out)

            // A Blimp block's value is its last statement, so a trailing return
            // is just that expression. Anything else needs continuation
            // splitting, which is not built yet.
            is TmpL.ReturnStatement -> when (val returned = statement.expression) {
                null -> out.add(Blimp.ExprStatement(statement.pos, Blimp.NilLit(statement.pos)))
                else -> out.add(Blimp.ExprStatement(statement.pos, translateExpressionHoisting(returned, out)))
            }

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
        is TmpL.Reference -> idOf(expression.id)
        else -> TODO("expression: $expression")
    }

    private fun translateCallExpression(call: TmpL.CallExpression): Blimp.Expr =
        when (val fn = call.fn) {
            // Support code such as console.log becomes Blimp syntax right here.
            is TmpL.InlineSupportCodeWrapper -> {
                val supportCode = fn.supportCode as BlimpInlineSupportCode
                preludeHelpers.addAll(supportCode.preludeHelpers)
                supportCode.callFactory(call.pos, call.parameters.map { translateActual(it) }) as Blimp.Expr
            }

            is TmpL.FnReference -> Blimp.Call(
                call.pos,
                callee = idOf(fn.id),
                args = call.parameters.map { translateActual(it) },
            )

            else -> TODO("callable: $fn")
        }

    private fun translateActual(actual: TmpL.Actual): Blimp.Expr = when (actual) {
        is TmpL.Expression -> translateExpression(actual)
        else -> TODO("actual: $actual")
    }

    // ── Declarations ─────────────────────────────────────────────────────

    /** A Temper function becomes a top-level `def`. */
    private fun translateFunction(decl: TmpL.FunctionDeclarationOrMethod): Blimp.DefDecl {
        val params = decl.parameters.parameters.map { formal ->
            Blimp.Param(formal.pos, id = idOf(formal.name), type = anyType(formal.pos))
        }
        val scope = mutableSetOf<ResolvedName>()
        decl.parameters.parameters.forEach { formal -> nameOf(formal.name)?.let(scope::add) }
        scopes.addLast(scope)
        checkOnlyTrailingReturn(decl.body)
        val body = try {
            translateBlock(decl.body)
        } finally {
            scopes.removeLast()
        }
        return Blimp.DefDecl(
            decl.pos,
            id = idOf(decl.name),
            params = params,
            returnType = anyType(decl.pos),
            body = body,
        )
    }

    /**
     * Rejects a `return` that is not the function body's last statement.
     *
     * Blimp has no `return`: a body's value is its final statement. A trailing
     * return is therefore free, but an early one needs the remaining
     * statements hoisted into a continuation `def`. Until that exists this
     * fails loudly rather than dropping the control flow.
     */
    private fun checkOnlyTrailingReturn(block: TmpL.BlockStatement?) {
        val statements = block?.statements ?: return
        statements.forEachIndexed { index, statement ->
            val isLast = index == statements.lastIndex
            val hasReturn = when (statement) {
                is TmpL.ReturnStatement -> !isLast
                else -> statement.anyChildRecursiveReturn()
            }
            if (hasReturn) TODO("early return: $statement")
        }
    }

    private fun TmpL.Statement.anyChildRecursiveReturn(): Boolean {
        var found = false
        boundaryDescent { node ->
            if (node is TmpL.ReturnStatement) found = true
            !found
        }
        return found
    }

    private fun translateBlock(block: TmpL.BlockStatement?): Blimp.Block {
        val statements = mutableListOf<Blimp.Statement>()
        block?.statements?.forEach { translateStatementInto(it, statements) }
        return Blimp.Block(block?.pos ?: module.pos, statements = statements)
    }

    // ── Loop lowering ────────────────────────────────────────────────────

    /**
     * A Temper `while` becomes a top-level tail-recursive `def`.
     *
     * Blimp has no `while`, but it does optimise tail calls, so a loop of a
     * million iterations runs flat. The lowered `def` takes the loop-carried
     * locals, returns them as a list when the test fails, and the call site
     * unpacks them back into the same names.
     *
     *     def loop_0(i, total) do
     *       case i < 10 do
     *         true ->
     *           total = total + i
     *           i = i + 1
     *           loop_0(i, total)
     *         _ -> [i, total]
     *       end
     *     end
     *
     * `break`, `continue` and a `return` out of a loop body need a tagged
     * signal the call site decodes; that is not built yet and shows up as the
     * TODO below rather than as wrong output.
     */
    private fun translateWhileStatement(loop: TmpL.WhileStatement, out: MutableList<Blimp.Statement>) {
        if (loop.body.anyChildRecursiveJump()) {
            TODO("loop with break, continue or return: $loop")
        }
        val pos = loop.pos
        val carried = loopCarriedNames(loop)
        val loopFn = Blimp.Id(pos, names.gensym("loop"))
        val carriedIds = carried.map { Blimp.Id(pos, names.outName(it)) }

        val bodyStatements = mutableListOf<Blimp.Statement>()
        translateStatementInto(loop.body, bodyStatements)
        bodyStatements.add(
            Blimp.ExprStatement(
                pos,
                Blimp.Call(pos, callee = loopFn.deepCopy(), args = carriedIds.map { it.deepCopy() }),
            ),
        )

        val test = translateExpressionHoisting(loop.test, bodyStatements.let { mutableListOf() })
        declarations.add(
            Blimp.DefDecl(
                pos,
                id = loopFn.deepCopy(),
                params = carriedIds.map { Blimp.Param(pos, id = it.deepCopy(), type = anyType(pos)) },
                returnType = anyType(pos),
                body = Blimp.Block(
                    pos,
                    statements = listOf(
                        Blimp.ExprStatement(
                            pos,
                            Blimp.CaseExpr(
                                pos,
                                subject = test,
                                arms = listOf(
                                    Blimp.CaseArm(
                                        pos,
                                        pattern = Blimp.BoolLit(pos, true),
                                        guard = null,
                                        body = Blimp.Block(pos, statements = bodyStatements),
                                    ),
                                    Blimp.CaseArm(
                                        pos,
                                        pattern = Blimp.Wildcard(pos),
                                        guard = null,
                                        body = Blimp.Block(
                                            pos,
                                            statements = listOf(
                                                Blimp.ExprStatement(
                                                    pos,
                                                    Blimp.ListLit(pos, items = carriedIds.map { it.deepCopy() }),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // Call it, then unpack the carried values back into their own names.
        val resultId = Blimp.Id(pos, names.gensym("carried"))
        out.add(
            Blimp.Assign(
                pos,
                target = resultId.deepCopy(),
                value = Blimp.Call(pos, callee = loopFn.deepCopy(), args = carriedIds.map { it.deepCopy() }),
            ),
        )
        carriedIds.forEachIndexed { index, id ->
            out.add(
                Blimp.Assign(
                    pos,
                    target = id.deepCopy(),
                    value = Blimp.Call(
                        pos,
                        callee = Blimp.Id(pos, OutName("elem", null)),
                        args = listOf(resultId.deepCopy(), Blimp.NumberLit(pos, index)),
                    ),
                ),
            )
        }
    }

    /**
     * The locals a lowered loop must take as parameters: every enclosing name
     * the loop reads or writes.
     *
     * Sorted for stable output. Names bound inside the loop are excluded
     * because they are recreated each iteration; the induction variable of a
     * desugared `for` is hoisted outside the loop by the frontend, so it lands
     * here automatically.
     */
    private fun loopCarriedNames(loop: TmpL.WhileStatement): List<ResolvedName> {
        val enclosing = scopes.flatten().toSet()
        val mentioned = mutableSetOf<ResolvedName>()
        val boundInside = mutableSetOf<ResolvedName>()
        for (root in listOf<TmpL.Tree>(loop.test, loop.body)) {
            root.boundaryDescent { node ->
                when (node) {
                    is TmpL.Reference -> nameOf(node.id)?.let(mentioned::add)
                    is TmpL.Assignment -> nameOf(node.left)?.let(mentioned::add)
                    is TmpL.LocalDeclaration -> nameOf(node.name)?.let(boundInside::add)
                    else -> {}
                }
                true
            }
        }
        return (mentioned - boundInside).filter { it in enclosing }.sortedBy { names.outName(it).outputNameText }
    }

    /** Whether the subtree jumps in a way the plain loop lowering cannot express. */
    private fun TmpL.Statement.anyChildRecursiveJump(): Boolean {
        var found = false
        boundaryDescent { node ->
            when (node) {
                is TmpL.BreakStatement, is TmpL.ContinueStatement, is TmpL.ReturnStatement -> found = true
                else -> {}
            }
            !found
        }
        return found
    }

    // ── Names ────────────────────────────────────────────────────────────

    /**
     * Blimp requires a type annotation on every `def` parameter and result.
     *
     * Its annotations are shallow and unparameterized, and Temper has already
     * done the type checking, so `Any` carries all the information that
     * survives the trip.
     */
    private fun anyType(pos: Position): Blimp.Id = Blimp.Id(pos, OutName("Any", null))

    private fun idOf(id: TmpL.Id): Blimp.Id =
        Blimp.Id(id.pos, nameOf(id)?.let { names.outName(it) } ?: OutName("$id", null))

    private fun nameOf(id: TmpL.Id): ResolvedName? = runCatching { id.name }.getOrNull()

    private fun freshLocal(pos: Position, hint: String): Blimp.Id = Blimp.Id(pos, names.gensym(hint))

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
