package lang.temper.be.blimp

import lang.temper.ast.boundaryDescent
import lang.temper.be.tmpl.TmpL
import lang.temper.log.Position
import lang.temper.name.OutName
import lang.temper.name.ResolvedName
import lang.temper.type.Abstractness
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

/** The handler a `spawn` is followed by, standing in for Temper's constructor. */
private const val CONSTRUCTOR_MESSAGE = "__new"

/** How a lowered loop body reports which way it left. */
private const val LOOP_BREAK = "break"
private const val LOOP_CONTINUE = "continue"
private const val LOOP_FALL = "fall"

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
     * The actor whose handler is being translated, if any.
     *
     * Blimp's `state` is in lexical scope inside a handler, and assigning to a
     * field name there creates a local that shadows it. So a field write is a
     * plain assignment and one `become` at the end of the handler publishes
     * every field that changed. That ordering is load-bearing: a handler's
     * state bindings are frozen at entry, so emitting a `become` per write
     * would compute each from the entry value and the last one would win.
     */
    private var actorScope: ActorScope? = null

    private class ActorScope(val fields: Set<ResolvedName>) {
        /** Fields written in this handler, in first-write order. */
        val mutated = linkedSetOf<ResolvedName>()
    }

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
            is TmpL.TypeDeclaration -> processTypeDeclaration(topLevel)
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

            is TmpL.IfStatement -> translateIfStatement(statement, out)

            is TmpL.SetProperty -> translateSetProperty(statement, out)

            // `break L` leaves the labelled block, which is a tail call to the
            // continuation holding whatever followed it.
            is TmpL.BreakStatement -> {
                val label = statement.label?.id?.let { nameOf(it) }
                val registered = label?.let { labelContinuations[it] }
                val loop = loops.lastOrNull()
                when {
                    registered != null && registered.second != loops.size ->
                        TODO("break across a loop boundary: $statement")
                    registered?.first != null -> out.add(registered.first!!.callFrom(statement.pos))
                    registered != null -> out.add(Blimp.ExprStatement(statement.pos, Blimp.NilLit(statement.pos)))
                    // An unlabelled break leaves the innermost lowered loop.
                    label == null && loop != null -> out.add(loop.signal(statement.pos, LOOP_BREAK))
                    label != null -> TODO("break to a label that is not an enclosing block: $statement")
                    else -> out.add(Blimp.ExprStatement(statement.pos, Blimp.NilLit(statement.pos)))
                }
            }

            is TmpL.ContinueStatement -> {
                val loop = loops.lastOrNull()
                when {
                    statement.label != null -> TODO("labelled continue: $statement")
                    loop != null -> out.add(loop.signal(statement.pos, LOOP_CONTINUE))
                    else -> TODO("continue outside a loop: $statement")
                }
            }

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
        is TmpL.This -> Blimp.Id(expression.pos, OutName("self", null))
        is TmpL.GetProperty -> translateGetProperty(expression)
        // Temper has already checked the type, and a Blimp actor dispatches on
        // whatever it actually is, so a cast carries no runtime meaning.
        is TmpL.CastExpression -> translateExpression(expression.expr)
        is TmpL.UncheckedNotNullExpression -> translateExpression(expression.expression)
        else -> TODO("expression: $expression")
    }

    private fun translateCallExpression(call: TmpL.CallExpression): Blimp.Expr =
        when (val fn = call.fn) {
            // Support code such as console.log becomes Blimp syntax right here.
            is TmpL.InlineSupportCodeWrapper -> {
                val supportCode = fn.supportCode as BlimpInlineSupportCode
                preludeHelpers.addAll(supportCode.preludeHelpers)
                when (
                    val tree = supportCode.callFactory(call.pos, call.parameters.map { translateActual(it) })
                ) {
                    // `bubble` is a statement in Blimp, so in expression
                    // position it is hoisted ahead and the expression it
                    // replaces evaluates to nil -- which is unreachable,
                    // because the bubble has already left.
                    is Blimp.Statement -> {
                        hoisted.add(tree)
                        Blimp.NilLit(call.pos)
                    }

                    // A hole's comment runs to end of line, so it gets a line
                    // of its own rather than sitting inside a larger form.
                    is Blimp.Hole -> {
                        val holeId = Blimp.Id(call.pos, names.gensym("hole"))
                        hoisted.add(Blimp.Assign(call.pos, target = holeId.deepCopy(), value = tree))
                        holeId
                    }

                    else -> tree as Blimp.Expr
                }
            }

            // `obj <- :method(args)`. The receiver is on the callable, not in
            // parameters, which is exactly the shape a send wants.
            // A static method has no receiver actor, so it is a plain call to
            // the flat name the declaration emitted.
            is TmpL.MethodReference if fn.subject is TmpL.TypeSubject -> Blimp.Call(
                call.pos,
                callee = Blimp.Id(
                    call.pos,
                    OutName(staticName(typeSubjectName(fn.subject as TmpL.TypeSubject), fn.methodName), null),
                ),
                args = call.parameters.map { translateActual(it) },
            )

            is TmpL.MethodReference -> Blimp.Send(
                call.pos,
                target = translateSubject(fn.subject),
                message = Blimp.MessageCall(
                    call.pos,
                    name = Blimp.Atom(call.pos, fn.methodName.dotNameText),
                    args = call.parameters.map { translateActual(it) },
                ),
            )

            // `new C(a, b)` becomes a spawn plus an `:new` send, hoisted ahead
            // of the expression that wanted the object.
            is TmpL.ConstructorReference -> {
                val pos = call.pos
                val target = Blimp.Id(pos, names.gensym("new"))
                hoisted.add(
                    Blimp.Assign(
                        pos,
                        target = target.deepCopy(),
                        value = Blimp.Spawn(pos, name = typeNameOf(fn.typeName), inits = listOf()),
                    ),
                )
                hoisted.add(
                    Blimp.ExprStatement(
                        pos,
                        Blimp.Send(
                            pos,
                            target = target.deepCopy(),
                            message = Blimp.MessageCall(
                                pos,
                                name = Blimp.Atom(pos, CONSTRUCTOR_MESSAGE),
                                args = call.parameters.map { translateActual(it) },
                            ),
                        ),
                    ),
                )
                target
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
    private fun translateFunction(
        decl: TmpL.FunctionDeclarationOrMethod,
        nameOverride: String? = null,
    ): Blimp.DefDecl {
        val params = decl.parameters.parameters.map { formal ->
            Blimp.Param(formal.pos, id = idOf(formal.name), type = anyType(formal.pos))
        }
        val scope = mutableSetOf<ResolvedName>()
        decl.parameters.parameters.forEach { formal -> nameOf(formal.name)?.let(scope::add) }
        scopes.addLast(scope)
        val body = try {
            translateBlock(decl.body)
        } finally {
            scopes.removeLast()
        }
        return Blimp.DefDecl(
            decl.pos,
            id = when (nameOverride) {
                null -> idOf(decl.name)
                else -> Blimp.Id(decl.pos, OutName(nameOverride, null))
            },
            params = params,
            returnType = anyType(decl.pos),
            body = body,
        )
    }

    /**
     * Translates a statement list, splitting at the first early `return`.
     *
     * Blimp has no `return`: a block's value is its last statement. A trailing
     * return is therefore free, but an early one needs the statements after it
     * lifted into a continuation `def` that the non-returning paths tail-call.
     * Nothing is duplicated, because only the fall-through calls it.
     *
     *     if (c) { return a }
     *     return b
     *
     * becomes
     *
     *     case c do
     *       true -> a
     *       _ -> b
     *     end
     */
    private fun translateBody(statements: List<TmpL.Statement>, out: MutableList<Blimp.Statement>): Boolean {
        val splitIndex = statements.indexOfFirst { statement ->
            !statement.isExit() && statement.containsExit()
        }
        if (splitIndex < 0) {
            statements.forEach { translateStatementInto(it, out) }
            return statements.lastOrNull()?.isExit() == true
        }
        statements.subList(0, splitIndex).forEach { translateStatementInto(it, out) }
        val split = statements[splitIndex]
        val tail = statements.subList(splitIndex + 1, statements.size)
        val continuation = when {
            tail.isEmpty() -> null
            else -> makeContinuation(tail, split.pos)
        }
        return when (split) {
            is TmpL.IfStatement -> {
                translateReturningIf(split, continuation, out)
                // Both arms end in an exit or in the continuation call.
                true
            }
            is TmpL.LabeledStatement -> translateLabeledBlock(split, continuation, out)
            else -> TODO("early exit inside: $split")
        }
    }

    /** Appends the loop's fall-through signal unless [terminated] already covers every path. */
    private fun endLoopPath(terminated: Boolean, out: MutableList<Blimp.Statement>, pos: Position) {
        val loop = loops.lastOrNull()
        if (!terminated && loop != null) out.add(loop.signal(pos, LOOP_FALL))
    }

    /**
     * A labelled block, which is how the frontend expresses an early `return`.
     *
     *     fn__4: { if (c) { return__0 = "yes"; break fn__4 } ... }
     *     return return__0
     *
     * `break fn__4` means "skip the rest of this block", which is exactly a
     * call to the continuation holding the statements after the block.
     */
    private fun translateLabeledBlock(
        labeled: TmpL.LabeledStatement,
        continuation: Continuation?,
        out: MutableList<Blimp.Statement>,
    ): Boolean {
        val label = nameOf(labeled.label.id)
        val previous = label?.let { labelContinuations.put(it, continuation to loops.size) }
        try {
            return when (val inner = labeled.statement) {
                is TmpL.BlockStatement -> translateBody(inner.statements, out)
                else -> translateBody(listOf(inner), out)
            }
        } finally {
            if (label != null) {
                when (previous) {
                    null -> labelContinuations.remove(label)
                    else -> labelContinuations[label] = previous
                }
            }
        }
    }

    private fun elemOf(pos: Position, list: Blimp.Id, index: Int): Blimp.Expr = Blimp.Call(
        pos,
        callee = Blimp.Id(pos, OutName("elem", null)),
        args = listOf(list.deepCopy(), Blimp.NumberLit(pos, index)),
    )

    private fun TmpL.Statement.containsReturn(): Boolean {
        var found = false
        boundaryDescent { node ->
            if (node is TmpL.ReturnStatement) found = true
            !found
        }
        return found
    }

    /** Statements that end a path: the continuation picks up from here. */
    private fun TmpL.Statement.isExit(): Boolean =
        this is TmpL.ReturnStatement || this is TmpL.BreakStatement || this is TmpL.ContinueStatement

    /**
     * Whether this statement can leave the enclosing block.
     *
     * A loop captures its own unlabelled `break` and `continue`, so the walk
     * treats those as handled once it is inside one. A *labelled* break still
     * escapes: that is how the frontend spells "continue the outer loop".
     */
    private fun TmpL.Statement.containsExit(): Boolean = findExit(insideLoop = this is TmpL.WhileStatement)

    private fun TmpL.Tree.findExit(insideLoop: Boolean): Boolean {
        when (this) {
            is TmpL.ReturnStatement -> return true
            is TmpL.BreakStatement -> if (!insideLoop || label != null) return true
            is TmpL.ContinueStatement -> if (!insideLoop) return true
            else -> {}
        }
        val nested = insideLoop || this is TmpL.WhileStatement
        for (index in 0 until childCount) {
            if (childOrNull(index)?.findExit(nested) == true) return true
        }
        return false
    }

    /**
     * Continuations for labelled blocks, so a `break L` knows where to go,
     * paired with how many loops were open when the label was registered.
     * A jump that crosses a loop boundary cannot just call the continuation --
     * the loop it leaves still has to report that it finished.
     */
    private val labelContinuations = mutableMapOf<ResolvedName, Pair<Continuation?, Int>>()

    /** Enclosing lowered loops, innermost last, so `break` and `continue` know their target. */
    private val loops = ArrayDeque<LoopSignals>()

    /**
     * How a loop body reports which way it left.
     *
     * A Blimp case arm does not reliably export its assignments, so the body
     * cannot just fall out with the loop variables updated. Instead every path
     * ends in `[tag, carried...]` and the loop decodes it.
     */
    private class LoopSignals(val carried: List<Blimp.Id>) {
        fun signal(pos: Position, tag: String): Blimp.Statement = Blimp.ExprStatement(
            pos,
            Blimp.ListLit(pos, items = listOf(Blimp.Atom(pos, tag)) + carried.map { it.deepCopy() }),
        )
    }

    private class Continuation(val id: Blimp.Id, val params: List<Blimp.Id>) {
        fun callFrom(pos: Position): Blimp.Statement = Blimp.ExprStatement(
            pos,
            Blimp.Call(pos, callee = id.deepCopy(), args = params.map { it.deepCopy() }),
        )
    }

    /** Lifts [tail] into a top-level `def` taking the enclosing locals it uses. */
    private fun makeContinuation(tail: List<TmpL.Statement>, pos: Position): Continuation {
        val enclosing = scopes.flatten().toSet()
        val mentioned = mutableSetOf<ResolvedName>()
        tail.forEach { statement ->
            statement.boundaryDescent { node ->
                when (node) {
                    is TmpL.Reference -> nameOf(node.id)?.let(mentioned::add)
                    is TmpL.Assignment -> nameOf(node.left)?.let(mentioned::add)
                    else -> {}
                }
                true
            }
        }
        val params = mentioned
            .filter { it in enclosing }
            .sortedBy { names.outName(it).outputNameText }
            .map { Blimp.Id(pos, names.outName(it)) }
        val id = Blimp.Id(pos, names.gensym("cont"))
        val body = mutableListOf<Blimp.Statement>()
        endLoopPath(translateBody(tail, body), body, pos)
        declarations.add(
            Blimp.DefDecl(
                pos,
                id = id.deepCopy(),
                params = params.map { Blimp.Param(pos, id = it.deepCopy(), type = anyType(pos)) },
                returnType = anyType(pos),
                body = Blimp.Block(pos, statements = body),
            ),
        )
        return Continuation(id, params)
    }

    /**
     * An `if` where at least one branch returns.
     *
     * The case expression's value is the function's value, so a branch that
     * returns supplies its value directly and a branch that falls through ends
     * in the continuation call.
     */
    private fun translateReturningIf(
        statement: TmpL.IfStatement,
        continuation: Continuation?,
        out: MutableList<Blimp.Statement>,
    ) {
        val pos = statement.pos
        val test = translateExpressionHoisting(statement.test, out)
        val consequent = translateBranch(statement.consequent, continuation, pos)
        val alternate = when (val alternate = statement.alternate) {
            null -> mutableListOf<Blimp.Statement>().also { branch ->
                when {
                    continuation != null -> branch.add(continuation.callFrom(pos))
                    loops.isNotEmpty() -> branch.add(loops.last().signal(pos, LOOP_FALL))
                    else -> branch.add(Blimp.ExprStatement(pos, Blimp.NilLit(pos)))
                }
            }
            else -> translateBranch(alternate, continuation, pos)
        }
        out.add(
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
                            body = Blimp.Block(pos, statements = consequent),
                        ),
                        Blimp.CaseArm(
                            pos,
                            pattern = Blimp.Wildcard(pos),
                            guard = null,
                            body = Blimp.Block(pos, statements = alternate),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun translateBranch(
        branch: TmpL.Statement,
        continuation: Continuation?,
        pos: Position,
    ): MutableList<Blimp.Statement> {
        val statements = when (branch) {
            is TmpL.BlockStatement -> branch.statements
            else -> listOf(branch)
        }
        val out = mutableListOf<Blimp.Statement>()
        when {
            // Ends in a return or a break: that path supplies the arm's value.
            statements.lastOrNull()?.isExit() == true -> translateBody(statements, out)
            // Exits somewhere other than the end; threading the continuation
            // into a nested split is not built yet.
            statements.any { it.containsExit() } -> TODO("conditional early exit: $branch")
            else -> {
                statements.forEach { translateStatementInto(it, out) }
                when {
                    continuation != null -> out.add(continuation.callFrom(pos))
                    loops.isNotEmpty() -> out.add(loops.last().signal(pos, LOOP_FALL))
                    else -> out.add(Blimp.ExprStatement(pos, Blimp.NilLit(pos)))
                }
            }
        }
        return out
    }

    private fun translateBlock(block: TmpL.BlockStatement?): Blimp.Block {
        val statements = mutableListOf<Blimp.Statement>()
        block?.statements?.let { translateBody(it, statements) }
        return Blimp.Block(block?.pos ?: module.pos, statements = statements)
    }

    // ── Classes as actors ────────────────────────────────────────────────

    /**
     * A Temper class becomes a Blimp actor.
     *
     * Fields become `state`, methods become `on :name(...)` handlers, and
     * construction becomes `spawn` plus an `:__new` send. Virtual dispatch
     * needs no vtable: a send through a base-typed reference already
     * dispatches on the receiving actor's own handlers, so a subclass is just
     * another actor with its own handlers.
     */
    private fun processTypeDeclaration(decl: TmpL.TypeDeclaration) {
        if (decl.kind == TmpL.TypeDeclarationKind.Interface) {
            // Blimp has no interface concept and does not need one: dispatch
            // is a send, so an implementation only has to carry the handlers.
            // A default method body comes along through `decl.inherited` on the
            // concrete class.
            return
        }
        if (decl.kind != TmpL.TypeDeclarationKind.Class) {
            TODO("${decl.kind} declaration: ${decl.name}")
        }
        val typePrefix = names.outName(nameOf(decl.name)!!).outputNameText
        val pos = decl.pos
        val properties = decl.members
            .filterIsInstance<TmpL.InstanceProperty>()
            .filter { it.memberShape.abstractness == Abstractness.Concrete }
        val fieldNames = properties.mapNotNull { nameOf(it.name) }.toSet()

        // TmpL carries no field initializer -- every one lives in the
        // constructor as a property write -- so the declared default is nil and
        // the constructor does the real work.
        val states = properties.map { property ->
            Blimp.StateDecl(
                property.pos,
                id = idOf(property.name),
                type = anyType(property.pos),
                init = Blimp.NilLit(property.pos),
            )
        }

        val handlers = mutableListOf<Blimp.Handler>()
        for (member in decl.members) {
            when (member) {
                is TmpL.InstanceProperty -> {}
                is TmpL.Constructor ->
                    handlers.add(translateHandler(member, fieldNames, Blimp.Atom(member.pos, CONSTRUCTOR_MESSAGE)))
                is TmpL.NormalMethod ->
                    handlers.add(translateHandler(member, fieldNames, Blimp.Atom(member.pos, messageAtom(member))))
                // A static has no instance, so it cannot be a handler. It
                // becomes a flat top-level name instead.
                is TmpL.StaticMethod -> declarations.add(
                    translateFunction(member, nameOverride = staticName(typePrefix, member.name)),
                )
                is TmpL.StaticProperty -> mainStatements.add(
                    Blimp.Assign(
                        member.pos,
                        target = Blimp.Id(member.pos, OutName(staticName(typePrefix, member.name), null)),
                        value = translateExpressionHoisting(member.expression, mainStatements),
                    ),
                )
                is TmpL.Getter ->
                    handlers.add(
                        translateHandler(member, fieldNames, Blimp.Atom(member.pos, member.dotName.dotNameText)),
                    )
                is TmpL.Setter ->
                    handlers.add(
                        translateHandler(
                            member,
                            fieldNames,
                            Blimp.Atom(member.pos, "set_${member.dotName.dotNameText}"),
                        ),
                    )
                else -> TODO("class member: $member")
            }
        }
        declarations.add(
            Blimp.ActorDecl(
                pos,
                name = Blimp.Name(pos, listOf(idOf(decl.name))),
                states = states,
                handlers = handlers,
            ),
        )
    }

    private fun messageAtom(method: TmpL.NormalMethod): String =
        method.dotName?.dotNameText ?: names.outName(nameOf(method.name)!!).outputNameText

    /**
     * A method becomes a handler.
     *
     * `this` is not a parameter: inside a handler the state is already in
     * lexical scope, and `self` names the actor.
     */
    private fun translateHandler(
        method: TmpL.FunctionDeclarationOrMethod,
        fieldNames: Set<ResolvedName>,
        message: Blimp.Atom,
    ): Blimp.Handler {
        val pos = method.pos
        // The receiver is a formal in TmpL, but a handler has no receiver
        // parameter: `self` names the actor and its state is already in scope.
        val thisName = method.parameters.thisName?.let { nameOf(it) }
        val formals = method.parameters.parameters.filter { formal -> nameOf(formal.name) != thisName }
        val params = formals.map { formal ->
            Blimp.Param(formal.pos, id = idOf(formal.name), type = anyType(formal.pos))
        }
        val scope = mutableSetOf<ResolvedName>()
        formals.forEach { formal -> nameOf(formal.name)?.let(scope::add) }
        val previousActor = actorScope
        actorScope = ActorScope(fieldNames)
        scopes.addLast(scope)
        val statements = mutableListOf<Blimp.Statement>()
        val mutated = try {
            method.body?.statements?.let { translateBody(it, statements) }
            actorScope!!.mutated.toList()
        } finally {
            scopes.removeLast()
            actorScope = previousActor
        }

        // The handler's value becomes its reply, and `become` has to publish
        // the field shadows before that reply reads them.
        val replyValue = when (val last = statements.lastOrNull()) {
            is Blimp.ExprStatement -> {
                statements.removeAt(statements.lastIndex)
                last.expr
            }
            else -> Blimp.NilLit(pos)
        }
        if (mutated.isNotEmpty()) {
            statements.add(
                Blimp.Become(
                    pos,
                    fields = mutated.map { field ->
                        val id = Blimp.Id(pos, names.outName(field))
                        Blimp.BecomeField(pos, id = id, value = id.deepCopy())
                    },
                ),
            )
        }
        statements.add(Blimp.Reply(pos, replyValue))
        return Blimp.Handler(
            pos,
            message = message,
            params = params,
            guard = null,
            bubbles = null,
            body = Blimp.Block(pos, statements = statements),
        )
    }

    private fun translateGetProperty(expression: TmpL.GetProperty): Blimp.Expr {
        val pos = expression.pos
        val propertyName = propertyAtom(expression.property)
        return when (val subject = expression.subject) {
            // A field of the actor running this handler is in lexical scope.
            is TmpL.This -> Blimp.Id(pos, OutName(propertyName, null))
            is TmpL.TypeSubject ->
                Blimp.Id(pos, OutName(staticName(typeSubjectName(subject), propertyName), null))
            is TmpL.Expression -> Blimp.Send(
                pos,
                target = translateExpression(subject),
                message = Blimp.Atom(pos, propertyName),
            )
        }
    }

    private fun translateSetProperty(statement: TmpL.SetProperty, out: MutableList<Blimp.Statement>) {
        val pos = statement.pos
        val value = translateExpressionHoisting(statement.right, out)
        val propertyName = propertyAtom(statement.left.property)
        when (val subject = statement.left.subject) {
            is TmpL.This -> {
                // Assigning the field name creates a local that shadows the
                // state; one `become` at the end of the handler publishes it.
                internalNameOf(statement.left.property)?.let { actorScope?.mutated?.add(it) }
                out.add(Blimp.Assign(pos, target = Blimp.Id(pos, OutName(propertyName, null)), value = value))
            }
            is TmpL.Expression -> out.add(
                Blimp.ExprStatement(
                    pos,
                    Blimp.Send(
                        pos,
                        target = translateExpression(subject),
                        message = Blimp.MessageCall(
                            pos,
                            name = Blimp.Atom(pos, "set_$propertyName"),
                            args = listOf(value),
                        ),
                    ),
                ),
            )
            else -> TODO("property subject: $subject")
        }
    }

    private fun propertyAtom(property: TmpL.PropertyId): String = when (property) {
        is TmpL.InternalPropertyId -> nameOf(property.name)?.let { names.outName(it).outputNameText } ?: "$property"
        is TmpL.ExternalPropertyId -> property.name.dotNameText
    }

    private fun internalNameOf(property: TmpL.PropertyId): ResolvedName? = when (property) {
        is TmpL.InternalPropertyId -> nameOf(property.name)
        else -> null
    }

    private fun translateSubject(subject: TmpL.Subject): Blimp.Expr = when (subject) {
        is TmpL.Expression -> translateExpression(subject)
        else -> TODO("call subject: $subject")
    }

    /** Statics are flattened to `Type__member`, since Blimp has one namespace. */
    private fun staticName(typePrefix: String, member: String): String = "${typePrefix}__$member"

    private fun staticName(typePrefix: String, member: TmpL.Id): String =
        staticName(typePrefix, nameOf(member)?.let { names.outName(it).outputNameText } ?: "$member")

    private fun staticName(typePrefix: String, member: TmpL.DotName): String =
        staticName(typePrefix, member.dotNameText)

    private fun typeSubjectName(subject: TmpL.TypeSubject): String = when (subject) {
        is TmpL.TypeName -> subject.toString()
        else -> TODO("type subject: $subject")
    }

    private fun typeNameOf(typeName: TmpL.TypeName): Blimp.Name =
        Blimp.Name(typeName.pos, listOf(Blimp.Id(typeName.pos, OutName(typeName.toString(), null))))

    // ── Branching ────────────────────────────────────────────────────────

    /**
     * Blimp has no `if`, and a two-armed `case` is not a drop-in replacement.
     *
     * Checked against the interpreter: an assignment inside a `case` arm does
     * not reliably survive the arm. `case c do true -> x = 1 ... end` leaves
     * `x` untouched afterwards. So a branch that assigns anything has to hand
     * its values out as the `case` expression's value and let the call site
     * put them back, the same shape the loop lowering uses.
     *
     *     branch = case c do
     *       true ->
     *         x = 1
     *         [x]
     *       _ ->
     *         x = 2
     *         [x]
     *     end
     *     x = elem(branch, 0)
     */
    private fun translateIfStatement(statement: TmpL.IfStatement, out: MutableList<Blimp.Statement>) {
        val pos = statement.pos
        val test = translateExpressionHoisting(statement.test, out)
        val consequent = mutableListOf<Blimp.Statement>()
        translateStatementInto(statement.consequent, consequent)
        val alternate = mutableListOf<Blimp.Statement>()
        statement.alternate?.let { translateStatementInto(it, alternate) }

        val assigned = assignedEnclosingNames(statement)
        val ids = assigned.map { Blimp.Id(pos, names.outName(it)) }
        if (ids.isEmpty()) {
            if (consequent.isEmpty()) consequent.add(Blimp.ExprStatement(pos, Blimp.NilLit(pos)))
            if (alternate.isEmpty()) alternate.add(Blimp.ExprStatement(pos, Blimp.NilLit(pos)))
        } else {
            consequent.add(Blimp.ExprStatement(pos, Blimp.ListLit(pos, items = ids.map { it.deepCopy() })))
            alternate.add(Blimp.ExprStatement(pos, Blimp.ListLit(pos, items = ids.map { it.deepCopy() })))
        }

        val caseExpr = Blimp.CaseExpr(
            pos,
            subject = test,
            arms = listOf(
                Blimp.CaseArm(
                    pos,
                    pattern = Blimp.BoolLit(pos, true),
                    guard = null,
                    body = Blimp.Block(pos, statements = consequent),
                ),
                Blimp.CaseArm(
                    pos,
                    pattern = Blimp.Wildcard(pos),
                    guard = null,
                    body = Blimp.Block(pos, statements = alternate),
                ),
            ),
        )
        if (ids.isEmpty()) {
            out.add(Blimp.ExprStatement(pos, caseExpr))
            return
        }
        val resultId = Blimp.Id(pos, names.gensym("branch"))
        out.add(Blimp.Assign(pos, target = resultId.deepCopy(), value = caseExpr))
        ids.forEachIndexed { index, id ->
            out.add(
                Blimp.Assign(
                    pos,
                    target = id.deepCopy(),
                    value = elemOf(pos, resultId, index),
                ),
            )
        }
    }

    /** Enclosing-scope names either branch assigns to, sorted for stable output. */
    private fun assignedEnclosingNames(statement: TmpL.Statement): List<ResolvedName> {
        val enclosing = scopes.flatten().toSet()
        val assigned = mutableSetOf<ResolvedName>()
        statement.boundaryDescent { node ->
            if (node is TmpL.Assignment) nameOf(node.left)?.let(assigned::add)
            true
        }
        return assigned.filter { it in enclosing }.sortedBy { names.outName(it).outputNameText }
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
     * `break` returns the carried values immediately and `continue` is just
     * the recursive call, so neither needs a signal of its own. What does need
     * one is getting the values back out of a branch at all. A `return` out of
     * a loop still needs continuation splitting threaded through here.
     */
    private fun translateWhileStatement(loop: TmpL.WhileStatement, out: MutableList<Blimp.Statement>) {
        if (loop.body.containsReturn()) {
            TODO("return out of a loop: $loop")
        }
        val pos = loop.pos
        val carried = loopCarriedNames(loop)
        val loopFn = Blimp.Id(pos, names.gensym("loop"))
        val carriedIds = carried.map { Blimp.Id(pos, names.outName(it)) }

        // Every path out of the body ends in `[tag, carried...]`. A case arm
        // does not reliably export its assignments, so the loop variables have
        // to be handed out explicitly rather than left to fall through.
        val signals = LoopSignals(carriedIds)
        loops.addLast(signals)
        val inner = mutableListOf<Blimp.Statement>()
        try {
            val terminated = when (val body = loop.body) {
                is TmpL.BlockStatement -> translateBody(body.statements, inner)
                else -> translateBody(listOf(body), inner)
            }
            endLoopPath(terminated, inner, pos)
        } finally {
            loops.removeLast()
        }

        val signalId = Blimp.Id(pos, names.gensym("signal"))
        val bodyStatements = mutableListOf<Blimp.Statement>()
        // A one-armed `case true` turns the body back into an expression so
        // the signal can be bound to a name.
        bodyStatements.add(
            Blimp.Assign(
                pos,
                target = signalId.deepCopy(),
                value = Blimp.CaseExpr(
                    pos,
                    subject = Blimp.BoolLit(pos, true),
                    arms = listOf(
                        Blimp.CaseArm(
                            pos,
                            pattern = Blimp.BoolLit(pos, true),
                            guard = null,
                            body = Blimp.Block(pos, statements = inner),
                        ),
                    ),
                ),
            ),
        )
        carriedIds.forEachIndexed { index, id ->
            bodyStatements.add(Blimp.Assign(pos, target = id.deepCopy(), value = elemOf(pos, signalId, index + 1)))
        }
        bodyStatements.add(
            Blimp.ExprStatement(
                pos,
                Blimp.CaseExpr(
                    pos,
                    subject = elemOf(pos, signalId, 0),
                    arms = listOf(
                        Blimp.CaseArm(
                            pos,
                            pattern = Blimp.Atom(pos, LOOP_BREAK),
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
                        Blimp.CaseArm(
                            pos,
                            pattern = Blimp.Wildcard(pos),
                            guard = null,
                            body = Blimp.Block(
                                pos,
                                statements = listOf(
                                    Blimp.ExprStatement(
                                        pos,
                                        Blimp.Call(
                                            pos,
                                            callee = loopFn.deepCopy(),
                                            args = carriedIds.map { it.deepCopy() },
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val test = translateExpressionHoisting(loop.test, mutableListOf())
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
