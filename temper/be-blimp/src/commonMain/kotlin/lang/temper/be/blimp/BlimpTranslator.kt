package lang.temper.be.blimp

import lang.temper.ast.boundaryDescent
import lang.temper.be.tmpl.TmpL
import lang.temper.be.tmpl.TmpLOperator
import lang.temper.log.Position
import lang.temper.name.OutName
import lang.temper.name.ResolvedName
import lang.temper.name.ResolvedParsedName
import lang.temper.type.Abstractness
import lang.temper.type2.Signature2
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

/** Temper's primitives, keyed to the tags Blimp's `type_of` returns. */
private val primitiveTypeTags = mapOf(
    "String" to "string",
    "Int" to "integer",
    "Int32" to "integer",
    "Int64" to "integer",
    "Float64" to "float",
    "Boolean" to "boolean",
    "Listed" to "list",
    "List" to "list",
    "ListBuilder" to "list",
    "Mapped" to "map",
    "Map" to "map",
    "MapBuilder" to "map",
    "Null" to "nil",
    "Void" to "nil",
)

/** How a lowered loop body reports which way it left. */
private const val LOOP_BREAK = "break"
private const val LOOP_CONTINUE = "continue"
private const val LOOP_FALL = "fall"

/** A lowered loop finished on its own terms: the test failed, or a `break`. */
private const val LOOP_DONE = "done"

/**
 * Something inside a lowered loop wants out past it: a `return`, or a `break`
 * to a label registered outside the loop. The signal carries a destination id
 * and a payload so the call site can decide whether it can honour it.
 */
private const val LOOP_ESCAPE = "escape"

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
internal class BlimpTranslator(
    private val module: TmpL.Module,
    /** Every class in the module set, so a subclass can be flattened. */
    private val types: Map<String, TmpL.TypeDeclaration> = mapOf(),
) {

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
     * Locals that a nested function assigns to, and so live in a cell.
     *
     * A Blimp closure captures the values of names already bound when it is
     * made, and an assignment inside it is local to it. Temper closures
     * capture by reference and may assign, so those locals are held in a
     * `TemperCell` actor that the closure captures instead: reads become
     * `x <- :get`, writes `x <- :set(v)`.
     */
    private val boxed = mutableSetOf<ResolvedName>()

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
        processImports()
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

    /**
     * Binds each imported name to the exporting module's name.
     *
     * Blimp has one flat namespace and every module lands in the same file, so
     * an import is an alias rather than a lookup. The exporting module is
     * translated first, so its value is already bound by the time this runs.
     */
    private fun processImports() {
        for (import in module.imports) {
            val local = import.localName ?: continue
            val localName = nameOf(local) ?: continue
            val externalName = nameOf(import.externalName) ?: continue
            if (localName == externalName) continue
            moduleScope.add(localName)
            mainStatements.add(
                Blimp.Assign(
                    import.pos,
                    target = Blimp.Id(import.pos, names.outName(localName)),
                    value = Blimp.Id(import.pos, names.outName(externalName)),
                ),
            )
        }
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
            // Through `translateBody`, not statement by statement: module code
            // gets labelled blocks too, and a `break` out of one needs the
            // continuation split that only this path performs.
            translateBody(block.body.statements, mainStatements)
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
                val boxedHere = nameOf(statement.name) in boxed
                val stored = when {
                    boxedHere -> {
                        preludeHelpers.addAll(needsCore)
                        Blimp.Call(
                            statement.pos,
                            callee = Blimp.Id(statement.pos, OutName(TEMPER_NEW_CELL, null)),
                            args = listOf(value),
                        )
                    }
                    else -> value
                }
                out.add(Blimp.Assign(statement.pos, target = idOf(statement.name), value = stored))
            }

            is TmpL.Assignment -> {
                val value = translateExpressionHoisting(statement.right, out)
                when (nameOf(statement.left)) {
                    in boxed -> out.add(
                        Blimp.ExprStatement(
                            statement.pos,
                            Blimp.Send(
                                statement.pos,
                                target = idOf(statement.left),
                                message = Blimp.MessageCall(
                                    statement.pos,
                                    name = Blimp.Atom(statement.pos, CELL_SET),
                                    args = listOf(value),
                                ),
                            ),
                        ),
                    )
                    else -> out.add(Blimp.Assign(statement.pos, target = idOf(statement.left), value = value))
                }
            }

            is TmpL.WhileStatement -> translateWhileStatement(statement, out)

            is TmpL.LocalFunctionDeclaration -> {
                nameOf(statement.name)?.let { scopes.lastOrNull()?.add(it) }
                out.add(
                    Blimp.Assign(
                        statement.pos,
                        target = idOf(statement.name),
                        value = translateLambda(statement),
                    ),
                )
            }

            is TmpL.IfStatement -> translateIfStatement(statement, out)

            is TmpL.SetProperty -> translateSetProperty(statement, out)

            is TmpL.TryStatement -> translateTryStatement(statement, out)

            // With BubbleBranchStrategy.Exceptions a throw carries no operand.
            is TmpL.ThrowStatement -> {
                preludeHelpers.add(TEMPER_BUBBLE)
                out.add(
                    Blimp.ExprStatement(
                        statement.pos,
                        Blimp.Call(
                            statement.pos,
                            callee = Blimp.Id(statement.pos, OutName(TEMPER_BUBBLE, null)),
                            args = listOf(),
                        ),
                    ),
                )
            }

            // `break L` leaves the labelled block, which is a tail call to the
            // continuation holding whatever followed it.
            is TmpL.BreakStatement -> {
                val label = statement.label?.id?.let { nameOf(it) }
                val registered = label?.let { labelContinuations[it] }
                val loop = loops.lastOrNull()
                when {
                    // The loop this break leaves still has to report that it
                    // finished, so the jump cannot just call the continuation.
                    registered != null && registered.second != loops.size ->
                        out.add(
                            loops.last().escape(
                                statement.pos,
                                escapeIdFor(label!!),
                                Blimp.NilLit(statement.pos),
                            ),
                        )
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
            // is just that expression. Inside a lowered loop it cannot be:
            // the body's last expression is the loop's signal, so a return has
            // to travel out as one and be re-raised at the call site.
            is TmpL.ReturnStatement -> {
                val returned = when (val expression = statement.expression) {
                    null -> Blimp.NilLit(statement.pos)
                    else -> translateExpressionHoisting(expression, out)
                }
                when (val loop = loops.lastOrNull()) {
                    null -> out.add(Blimp.ExprStatement(statement.pos, returned))
                    else -> out.add(loop.escape(statement.pos, escapeIdFor(FunctionReturn), returned))
                }
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
        is TmpL.Reference -> when (nameOf(expression.id)) {
            in boxed -> cellRead(expression.pos, idOf(expression.id))
            else -> idOf(expression.id)
        }
        is TmpL.This -> Blimp.Id(expression.pos, OutName("self", null))
        is TmpL.GetProperty -> translateGetProperty(expression)
        // A callable used as a value. Blimp closures are values already, so
        // the wrapper carries nothing at run time.
        is TmpL.FunInterfaceExpression -> when (val callable = expression.callable) {
            is TmpL.FnReference -> idOf(callable.id)
            else -> TODO("function value: $callable")
        }
        is TmpL.InstanceOfExpression -> {
            preludeHelpers.addAll(isATypeHelpers)
            Blimp.Call(
                expression.pos,
                callee = Blimp.Id(expression.pos, OutName(TEMPER_IS_A, null)),
                args = listOf(
                    translateExpression(expression.expr),
                    Blimp.Atom(expression.pos, typeTagOf(expression.checkedType)),
                ),
            )
        }
        is TmpL.CastExpression -> translateCastExpression(expression)
        is TmpL.InfixOperation -> translateInfixOperation(expression)
        is TmpL.PrefixOperation -> Blimp.Operation(
            expression.pos,
            left = null,
            operator = Blimp.Operator(
                expression.op.pos,
                when (expression.op.tmpLOperator) {
                    // The only prefix operator TmpL has.
                    TmpLOperator.Bang -> BlimpOperator.Not
                },
            ),
            right = translateExpression(expression.operand),
        )
        is TmpL.UncheckedNotNullExpression -> translateExpression(expression.expression)
        else -> TODO("expression: $expression")
    }

    /**
     * A cast that can fail has to check, because nothing else will.
     *
     * Blimp is untyped: an actor answers whatever message it is sent and a
     * mismatch surfaces, if at all, as a later `NOT AN ACTOR` or a wrong
     * answer. Temper's `x as Apple` bubbles when `x` is not an Apple, and the
     * bubble is the observable behaviour -- `casts/as-expr` prints "Cast
     * failed!" from the `orelse` arm. Passing the value straight through, as
     * this did until now, silently produced the success path for a cast that
     * should have failed.
     *
     * `canFail` is false for an upcast, where the frontend has already proved
     * the value's type, so those still cost nothing. A tag of `nil` means the
     * target is not a nominal type `temper_is_a` can answer for, and checking
     * it would reject every value.
     */
    private fun translateCastExpression(cast: TmpL.CastExpression): Blimp.Expr {
        val tag = typeTagOf(cast.checkedType)
        val expr = translateExpression(cast.expr)
        if (!cast.canFail || tag == "nil") return expr
        preludeHelpers.addAll(castTypeHelpers)
        return Blimp.Call(
            cast.pos,
            callee = Blimp.Id(cast.pos, OutName(TEMPER_CAST, null)),
            args = listOf(expr, Blimp.Atom(cast.pos, tag)),
        )
    }

    /**
     * The handful of operators the frontend leaves as operators.
     *
     * Everything arithmetic arrives as an inlined support-code call instead,
     * which is why this went missing until a `>= 0` in the middle of
     * `types/string/indices` had nowhere to go.
     *
     * `&&` and `||` are the interesting ones. Blimp has `and` and `or`, and
     * they are the wrong translation: they are eager, so both sides run
     * whatever the left one says. Temper's short-circuit, and callers depend
     * on it -- `i >= 0 && charAt(s, i) == c` reads out of range when `i` is
     * negative. They lower to a `case` instead, which is the only construct
     * in Blimp that will not evaluate a branch it did not take.
     *
     * Both `when`s below are exhaustive, so TmpL growing an operator is a
     * compile error here rather than a `TODO()` someone finds at build time.
     */
    private fun translateInfixOperation(expression: TmpL.InfixOperation): Blimp.Expr {
        val pos = expression.pos
        val left = translateExpression(expression.left)
        val operator = when (expression.op.tmpLOperator) {
            TmpLOperator.AmpAmp ->
                return shortCircuit(pos, left, Blimp.BoolLit(pos, false), rightFirst = true, expression = expression)
            TmpLOperator.BarBar ->
                return shortCircuit(pos, left, Blimp.BoolLit(pos, true), rightFirst = false, expression = expression)
            TmpLOperator.EqEqInt -> BlimpOperator.Equals
            TmpLOperator.GeInt -> BlimpOperator.GreaterEquals
            TmpLOperator.GtInt -> BlimpOperator.GreaterThan
            TmpLOperator.LeInt -> BlimpOperator.LessEquals
            TmpLOperator.LtInt -> BlimpOperator.LessThan
            // Blimp's Int is 64-bit and does not wrap. This operator is the
            // frontend's own index arithmetic, which cannot overflow; Temper's
            // wrapping Int32 addition arrives as a support-code call that goes
            // through `temper_int32`.
            TmpLOperator.PlusInt -> BlimpOperator.Addition
        }
        return Blimp.Operation(
            pos,
            left = left,
            operator = Blimp.Operator(expression.op.pos, operator),
            right = translateExpression(expression.right),
        )
    }

    /**
     * `case left do true -> a _ -> b end`, where one of the arms is the right
     * operand and the other is the constant the operator settles on.
     */
    private fun shortCircuit(
        pos: Position,
        left: Blimp.Expr,
        settled: Blimp.BoolLit,
        /** True for `&&`, where the right operand is the `true` arm. */
        rightFirst: Boolean,
        expression: TmpL.InfixOperation,
    ): Blimp.Expr {
        val right = translateExpression(expression.right)
        val whenTrue = if (rightFirst) right else settled
        val whenFalse = if (rightFirst) settled else right
        val cased = Blimp.CaseExpr(
            pos,
            subject = left,
            arms = listOf(
                Blimp.CaseArm(
                    pos,
                    pattern = Blimp.BoolLit(pos, true),
                    guard = null,
                    body = Blimp.Block(pos, statements = listOf(Blimp.ExprStatement(pos, whenTrue))),
                ),
                Blimp.CaseArm(
                    pos,
                    pattern = Blimp.Wildcard(pos),
                    guard = null,
                    body = Blimp.Block(pos, statements = listOf(Blimp.ExprStatement(pos, whenFalse))),
                ),
            ),
        )
        return bindIfCase(pos, cased, hoisted)
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
                    args = padOptional(
                        call.pos,
                        call.parameters.map { translateActual(it) },
                        declaredArity(fn.type),
                    ),
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
                                args = padOptional(
                                    pos,
                                    call.parameters.map { translateActual(it) },
                                    constructorArity(fn.typeName),
                                ),
                            ),
                        ),
                    ),
                )
                target
            }

            // A function value being called: in Blimp a closure is called like
            // anything else, so the expression becomes the callee.
            is TmpL.FunInterfaceCallable -> Blimp.Call(
                call.pos,
                callee = translateExpression(fn.expr),
                args = call.parameters.map { translateActual(it) },
            )

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
        collectBoxedCaptures(decl.body)
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
    private fun translateBody(
        statements: List<TmpL.Statement>,
        out: MutableList<Blimp.Statement>,
        /** Where a path that runs off the end of [statements] should go, if anywhere. */
        outer: Continuation? = null,
    ): Boolean {
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
            // Nothing follows, so a path that falls through carries on to
            // whatever the enclosing block was going to do.
            tail.isEmpty() -> outer
            else -> makeContinuation(tail, split.pos, outer)
        }
        return when (split) {
            is TmpL.IfStatement -> {
                translateReturningIf(split, continuation, out)
                // Both arms end in an exit or in the continuation call.
                true
            }
            is TmpL.LabeledStatement -> translateLabeledBlock(split, continuation, out)
            is TmpL.WhileStatement -> {
                translateWhileStatement(split, out, continuation, escaping = true)
                true
            }
            is TmpL.TryStatement -> {
                translateReturningTry(split, continuation, out)
                // Both arms end in an exit or in the continuation call.
                true
            }
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

    /**
     * Binds [expr] to a name when it is a `case`, because Blimp only accepts
     * one on the right-hand side of an assignment.
     *
     * Verified against the interpreter: `reply case c do ... end`,
     * `f(case ...)`, `[case ...]`, `become s: case ...` and `"a" ++ case ...`
     * are all parse errors, while `x = case ... end` is fine. So a `case` that
     * is going to be used as a value has to be given a name first.
     */
    private fun bindIfCase(pos: Position, expr: Blimp.Expr, out: MutableList<Blimp.Statement>): Blimp.Expr {
        if (expr !is Blimp.CaseExpr) return expr
        val id = Blimp.Id(pos, names.gensym("cased"))
        out.add(Blimp.Assign(pos, target = id.deepCopy(), value = expr))
        return id
    }

    private fun elemOf(pos: Position, list: Blimp.Id, index: Int): Blimp.Expr = Blimp.Call(
        pos,
        callee = Blimp.Id(pos, OutName("elem", null)),
        args = listOf(list.deepCopy(), Blimp.NumberLit(pos, index)),
    )

    /**
     * Whether a `return` inside this statement belongs to the enclosing
     * function. A nested function's returns are its own, so the walk stops
     * there, the same way [findExit] does.
     */
    private fun TmpL.Statement.containsReturn(): Boolean = findReturn()

    private fun TmpL.Tree.findReturn(): Boolean {
        when (this) {
            is TmpL.LocalFunctionDeclaration -> return false
            is TmpL.ReturnStatement -> return true
            else -> {}
        }
        for (index in 0 until childCount) {
            if (childOrNull(index)?.findReturn() == true) return true
        }
        return false
    }

    /**
     * Fills in omitted optional arguments with `nil`.
     *
     * A Blimp handler takes a fixed number of arguments, but Temper drops
     * trailing optional ones at the call site -- the same constructor turns up
     * as `__new(1)` and `__new(2, 3)`. The declared body already tests
     * `isNull` for each default, so `nil` is exactly what it expects.
     */
    private fun padOptional(pos: Position, args: List<Blimp.Expr>, arity: Int): List<Blimp.Expr> = when {
        arity <= args.size -> args
        else -> args + List(arity - args.size) { Blimp.NilLit(pos) }
    }

    /**
     * How many parameters a signature declares, excluding `this`.
     *
     * Optional ones count: the handler takes them all, and the call site is
     * where the omitted ones get filled in.
     */
    private fun declaredArity(sig: Signature2): Int {
        val required = sig.requiredInputTypes.size - if (sig.hasThisFormal) 1 else 0
        return required + sig.optionalInputTypes.size
    }

    /** How many parameters the class's constructor declares, excluding `this`. */
    private fun constructorArity(typeName: TmpL.TypeName): Int {
        val key = (typeName.sourceDefinition?.name as? ResolvedParsedName)?.baseName?.nameText
        val decl = key?.let { types[it] } ?: return 0
        val constructor = flattenMembers(decl).filterIsInstance<TmpL.Constructor>().firstOrNull() ?: return 0
        val thisName = constructor.parameters.thisName?.let { nameOf(it) }
        return constructor.parameters.parameters.count { nameOf(it.name) != thisName }
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
            // A nested function's returns and jumps are its own.
            is TmpL.LocalFunctionDeclaration -> return false
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

        /**
         * `[:escape, carried..., dest, payload]`.
         *
         * The destination and payload sit after the carried values so that
         * `elem(r, i + 1)` reads a carried value whichever kind of signal
         * came back, and only the tag says which one it is.
         */
        fun escapeOf(pos: Position, dest: Blimp.Expr, payload: Blimp.Expr): Blimp.Statement =
            Blimp.ExprStatement(
                pos,
                Blimp.ListLit(
                    pos,
                    items = listOf(Blimp.Atom(pos, LOOP_ESCAPE)) +
                        carried.map { it.deepCopy() } + listOf(dest, payload),
                ),
            )

        fun escape(pos: Position, dest: Int, payload: Blimp.Expr): Blimp.Statement =
            escapeOf(pos, Blimp.NumberLit(pos, dest), payload)
    }

    /** The destination of an escape that leaves the enclosing function entirely. */
    private object FunctionReturn

    /**
     * Small integer ids for escape destinations, so a signal can name one.
     *
     * A destination is either a labelled block (keyed by its [ResolvedName])
     * or [FunctionReturn]. Ids are global to the module; a call site only
     * builds arms for the destinations it can actually honour.
     */
    private val escapeIds = mutableMapOf<Any, Int>()

    private fun escapeIdFor(dest: Any): Int = escapeIds.getOrPut(dest) { escapeIds.size }

    private class Continuation(val id: Blimp.Id, val params: List<Blimp.Id>) {
        fun callFrom(pos: Position): Blimp.Statement = Blimp.ExprStatement(
            pos,
            Blimp.Call(pos, callee = id.deepCopy(), args = params.map { it.deepCopy() }),
        )
    }

    /** Lifts [tail] into a top-level `def` taking the enclosing locals it uses. */
    private fun makeContinuation(tail: List<TmpL.Statement>, pos: Position, outer: Continuation? = null): Continuation {
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
        val terminated = translateBody(tail, body, outer)
        when {
            terminated -> {}
            // A continuation that runs off its end hands control on.
            outer != null -> body.add(outer.callFrom(pos))
            else -> endLoopPath(false, body, pos)
        }
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

    /**
     * A `try` where at least one arm leaves the enclosing block.
     *
     * The try expression's value is the block's value, so an arm that returns
     * supplies its value directly and one that falls through ends in the
     * continuation call -- the same shape [translateReturningIf] uses.
     */
    private fun translateReturningTry(
        statement: TmpL.TryStatement,
        continuation: Continuation?,
        out: MutableList<Blimp.Statement>,
    ) {
        val pos = statement.pos
        if (statement.recover is TmpL.ThrowStatement) {
            translateBody(listOf(statement.tried), out)
            return
        }
        out.add(
            Blimp.ExprStatement(
                pos,
                Blimp.TryCatch(
                    pos,
                    body = Blimp.Block(pos, statements = translateBranch(statement.tried, continuation, pos)),
                    id = null,
                    handler = Blimp.Block(pos, statements = translateBranch(statement.recover, continuation, pos)),
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
            // Exits on some paths but not all: split inside the branch, with
            // this branch's continuation as where the surviving paths go.
            statements.any { it.containsExit() } -> {
                val terminated = translateBody(statements, out, continuation)
                if (!terminated) {
                    when {
                        continuation != null -> out.add(continuation.callFrom(pos))
                        loops.isNotEmpty() -> out.add(loops.last().signal(pos, LOOP_FALL))
                        else -> {}
                    }
                }
            }
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

    /**
     * A nested function becomes a `fn(...) do ... end` bound to its name.
     *
     * A Blimp closure captures the values of names already bound when it is
     * made; a name not yet bound resolves when the closure runs, and an
     * assignment inside it is local to it. So a local the closure assigns to
     * has to live in a cell -- see [collectBoxedCaptures].
     *
     * The enclosing loop and label context is set aside: a `return` inside the
     * lambda is the lambda's own, not a jump out of the function around it.
     */
    private fun translateLambda(decl: TmpL.FunctionDeclarationOrMethod): Blimp.Lambda {
        val pos = decl.pos
        val thisName = decl.parameters.thisName?.let { nameOf(it) }
        val formals = decl.parameters.parameters.filter { formal -> nameOf(formal.name) != thisName }
        val scope = mutableSetOf<ResolvedName>()
        formals.forEach { formal -> nameOf(formal.name)?.let(scope::add) }

        val outerLoops = loops.toList()
        val outerLabels = labelContinuations.toMap()
        loops.clear()
        labelContinuations.clear()
        scopes.addLast(scope)
        val body = try {
            translateBlock(decl.body)
        } finally {
            scopes.removeLast()
            labelContinuations.clear()
            labelContinuations.putAll(outerLabels)
            loops.clear()
            outerLoops.forEach { loops.addLast(it) }
        }
        return Blimp.Lambda(
            pos,
            params = formals.map { formal ->
                Blimp.Param(formal.pos, id = idOf(formal.name), type = anyType(formal.pos))
            },
            body = body,
        )
    }

    /**
     * Records every enclosing local that a nested function assigns to, so its
     * declaration, reads and writes can all go through a cell.
     */
    private fun collectBoxedCaptures(block: TmpL.BlockStatement?) {
        val declared = mutableSetOf<ResolvedName>()
        block?.boundaryDescent { node ->
            if (node is TmpL.LocalDeclaration) nameOf(node.name)?.let(declared::add)
            true
        }
        block?.boundaryDescent { node ->
            if (node is TmpL.LocalFunctionDeclaration) {
                val inner = mutableSetOf<ResolvedName>()
                node.boundaryDescent { child ->
                    when (child) {
                        is TmpL.LocalDeclaration -> nameOf(child.name)?.let(inner::add)
                        is TmpL.Formal -> nameOf(child.name)?.let(inner::add)
                        else -> {}
                    }
                    true
                }
                node.boundaryDescent { child ->
                    if (child is TmpL.Assignment) {
                        val target = nameOf(child.left)
                        if (target != null && target !in inner) boxed.add(target)
                    }
                    true
                }
            }
            true
        }
    }

    private fun cellRead(pos: Position, id: Blimp.Id): Blimp.Expr =
        Blimp.Send(pos, target = id, message = Blimp.Atom(pos, CELL_GET))

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
            // is a send, so an implementation only carries the handlers, which
            // it gets by being flattened. But an interface's statics have no
            // instance either way, so they are emitted like any other.
            emitStatics(decl)
            return
        }
        if (decl.kind != TmpL.TypeDeclarationKind.Class) {
            TODO("${decl.kind} declaration: ${decl.name}")
        }
        val typePrefix = names.outName(nameOf(decl.name)!!).outputNameText
        val pos = decl.pos
        // A subclass carries its parents' members, because a Blimp actor has
        // no super to defer to. Own members win over inherited ones.
        val flattened = flattenMembers(decl)
        val properties = flattened
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

        // Blimp has no type tags of its own, so each actor carries the list a
        // runtime type test consults: itself, then its supertypes.
        // The tag is the base name, not `typePrefix`. The actor is called
        // `HiGreeter__1` because TmpL disambiguates declarations, but every
        // reader of the tag -- `typeTagOf`, for both `instanceof` and a cast --
        // resolves a type to its suffix-free name, so tagging the actor with
        // the suffixed one made `x as HiGreeter` bubble against `:HiGreeter__1`.
        val typeNames = listOf(baseNameText(decl.name)) + ancestorTagsOf(decl)
        val handlers = mutableListOf(
            Blimp.Handler(
                pos,
                message = Blimp.Atom(pos, TYPES_MESSAGE),
                params = listOf(),
                guard = null,
                bubbles = null,
                body = Blimp.Block(
                    pos,
                    statements = listOf(
                        Blimp.Reply(pos, Blimp.ListLit(pos, items = typeNames.map { Blimp.Atom(pos, it) })),
                    ),
                ),
            ),
        )
        for (member in flattened) {
            when (member) {
                is TmpL.InstanceProperty -> {}
                is TmpL.Constructor ->
                    handlers.add(translateHandler(member, fieldNames, Blimp.Atom(member.pos, CONSTRUCTOR_MESSAGE)))
                is TmpL.NormalMethod ->
                    handlers.add(translateHandler(member, fieldNames, Blimp.Atom(member.pos, messageAtom(member))))
                // Statics have no instance, so they are emitted separately.
                is TmpL.StaticMethod, is TmpL.StaticProperty -> {}
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
        emitStatics(decl)
    }

    /**
     * A type's statics, which have no instance and so cannot be handlers.
     *
     * They flatten to top-level names, which works because Blimp has one
     * namespace. Interfaces get this too even though they emit no actor.
     */
    private fun emitStatics(decl: TmpL.TypeDeclaration) {
        val typePrefix = names.outName(nameOf(decl.name)!!).outputNameText
        for (member in decl.members) {
            when (member) {
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
                else -> {}
            }
        }
    }

    /**
     * This class's members plus every inherited one it does not redefine.
     *
     * Blimp has no inheritance and needs none: an actor with the handlers is
     * the thing. Dispatch is a send, so a flattened subclass answers for
     * itself without any vtable.
     */
    private fun flattenMembers(decl: TmpL.TypeDeclaration): List<TmpL.Member> {
        val byName = linkedMapOf<String, TmpL.Member>()
        // Breadth first, so a nearer supertype wins over a farther one. With
        // `class D extends B & C` where both extend A and only C overrides,
        // C is at depth 1 and A at depth 2, so C wins -- which is what every
        // other backend does.
        var level = listOf(decl)
        val seen = mutableSetOf<String>()
        while (level.isNotEmpty()) {
            for (type in level) {
                for (member in type.members.filterIsInstance<TmpL.Member>()) {
                    memberKey(member)?.let { byName.putIfAbsent(it, member) }
                }
            }
            level = level.flatMap { type ->
                type.superTypes.mapNotNull { superType ->
                    val key = (superType.typeName.sourceDefinition?.name as? ResolvedParsedName)
                        ?.baseName?.nameText
                    when {
                        key == null || !seen.add(key) -> null
                        else -> types[key]
                    }
                }
            }
        }
        return byName.values.toList()
    }

    /**
     * Every supertype name above this one, nearest first.
     *
     * Direct supertypes are not enough: `class C extends B`, `class B extends
     * A` makes `c as A` a legitimate cast, and the tag list is the only thing
     * a Blimp actor can be asked about itself.
     */
    private fun ancestorTagsOf(decl: TmpL.TypeDeclaration): List<String> {
        val tags = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var level = listOf(decl)
        while (level.isNotEmpty()) {
            level = level.flatMap { type ->
                type.superTypes.mapNotNull { superType ->
                    val key = (superType.typeName.sourceDefinition?.name as? ResolvedParsedName)
                        ?.baseName?.nameText
                    when {
                        key == null || !seen.add(key) -> null
                        else -> {
                            tags.add(key)
                            types[key]
                        }
                    }
                }
            }
        }
        return tags
    }

    /** What makes two members the same member for override purposes. */
    private fun memberKey(member: TmpL.Member): String? = when (member) {
        is TmpL.InstanceProperty -> "prop:" + (member.dotName?.dotNameText ?: nameText(member.name))
        is TmpL.StaticProperty -> "static-prop:" + nameText(member.name)
        is TmpL.Getter -> "get:" + member.dotName.dotNameText
        is TmpL.Setter -> "set:" + member.dotName.dotNameText
        is TmpL.NormalMethod -> "fn:" + messageAtom(member)
        is TmpL.StaticMethod -> "static-fn:" + nameText(member.name)
        is TmpL.Constructor -> "ctor"
    }

    private fun nameText(id: TmpL.Id): String =
        nameOf(id)?.let { names.outName(it).outputNameText } ?: "$id"

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
        collectBoxedCaptures(method.body)
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
                // A `case` is a legal statement but not a legal reply value,
                // and lowering an `if` that returns produces exactly that.
                bindIfCase(pos, last.expr, statements)
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
            // A backed property is a real field, and an actor's state is in
            // lexical scope inside its handlers. An abstract one is a getter,
            // so it has to be invoked even on `this`.
            is TmpL.This -> when (expression) {
                is TmpL.GetBackedProperty -> Blimp.Id(pos, OutName(propertyName, null))
                else -> Blimp.Send(
                    pos,
                    target = Blimp.Id(pos, OutName("self", null)),
                    message = Blimp.Atom(pos, propertyName),
                )
            }
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
            // A backed property is a real field: assigning the field name
            // creates a local that shadows the state, and one `become` at the
            // end of the handler publishes it. An abstract one is a setter and
            // has to be invoked, even on `this`.
            is TmpL.This -> when (statement) {
                is TmpL.SetBackedProperty -> {
                    internalNameOf(statement.left.property)?.let { actorScope?.mutated?.add(it) }
                    out.add(Blimp.Assign(pos, target = Blimp.Id(pos, OutName(propertyName, null)), value = value))
                }
                else -> out.add(
                    Blimp.ExprStatement(
                        pos,
                        Blimp.Send(
                            pos,
                            target = Blimp.Id(pos, OutName("self", null)),
                            message = Blimp.MessageCall(
                                pos,
                                name = Blimp.Atom(pos, "set_$propertyName"),
                                args = listOf(value),
                            ),
                        ),
                    ),
                )
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

    /**
     * Statics are flattened to `static_Type__member`, since Blimp has one
     * namespace.
     *
     * The `static_` is not decoration: Blimp's `def` takes a lower-case
     * identifier, and an upper-case one is a parse error, so `Thing__go` will
     * not do. Prefixing also keeps a static clear of any user name.
     */
    private fun staticName(typePrefix: String, member: String): String = "static_${typePrefix}__$member"

    /**
     * A static is named by its source name, not its TmpL one.
     *
     * A read arrives as a dot name with no disambiguating suffix, so the
     * declaration has to drop the suffix too or the two never meet.
     */
    private fun staticName(typePrefix: String, member: TmpL.Id): String =
        staticName(typePrefix, baseNameText(member))

    private fun baseNameText(id: TmpL.Id): String {
        val name = nameOf(id)
        return (name as? ResolvedParsedName)?.baseName?.nameText
            ?: name?.let { names.outName(it).outputNameText }
            ?: "$id"
    }

    private fun staticName(typePrefix: String, member: TmpL.DotName): String =
        staticName(typePrefix, member.dotNameText)

    private fun typeSubjectName(subject: TmpL.TypeSubject): String = when (subject) {
        is TmpL.TypeName -> subject.toString()
        else -> TODO("type subject: $subject")
    }

    /**
     * The atom a runtime type test compares against.
     *
     * Temper's primitives map onto Blimp's own `type_of` tags; anything else
     * is a translated class, which answers for its own name.
     */
    private fun typeTagOf(type: TmpL.AType): String {
        val definition = (type.ot as? TmpL.NominalType)?.typeName?.sourceDefinition
        val text = (definition?.name as? ResolvedParsedName)?.baseName?.nameText ?: return "nil"
        return primitiveTypeTags[text] ?: text
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

    /**
     * `try do ... catch do ... end`.
     *
     * Checked against the interpreter: an assignment in the *try* body escapes
     * it, but one in the *catch* body does not. Rather than depend on that
     * asymmetry, both arms hand their values out as the expression's value and
     * the call site puts them back, the same shape `if` and loops use.
     *
     * A recover clause that is a bare rethrow is elided, as be-rust does.
     */
    private fun translateTryStatement(statement: TmpL.TryStatement, out: MutableList<Blimp.Statement>) {
        val pos = statement.pos
        if (statement.recover is TmpL.ThrowStatement) {
            translateStatementInto(statement.tried, out)
            return
        }
        val tried = mutableListOf<Blimp.Statement>()
        translateStatementInto(statement.tried, tried)
        val recover = mutableListOf<Blimp.Statement>()
        translateStatementInto(statement.recover, recover)

        val assigned = assignedEnclosingNames(statement)
        val ids = assigned.map { Blimp.Id(pos, names.outName(it)) }
        if (ids.isEmpty()) {
            if (tried.isEmpty()) tried.add(Blimp.ExprStatement(pos, Blimp.NilLit(pos)))
            if (recover.isEmpty()) recover.add(Blimp.ExprStatement(pos, Blimp.NilLit(pos)))
        } else {
            tried.add(Blimp.ExprStatement(pos, Blimp.ListLit(pos, items = ids.map { it.deepCopy() })))
            recover.add(Blimp.ExprStatement(pos, Blimp.ListLit(pos, items = ids.map { it.deepCopy() })))
        }
        val tryCatch = Blimp.TryCatch(
            pos,
            body = Blimp.Block(pos, statements = tried),
            id = null,
            handler = Blimp.Block(pos, statements = recover),
        )
        if (ids.isEmpty()) {
            out.add(Blimp.ExprStatement(pos, tryCatch))
            return
        }
        val resultId = Blimp.Id(pos, names.gensym("recovered"))
        out.add(Blimp.Assign(pos, target = resultId.deepCopy(), value = tryCatch))
        ids.forEachIndexed { index, id ->
            out.add(Blimp.Assign(pos, target = id.deepCopy(), value = elemOf(pos, resultId, index)))
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
    private fun translateWhileStatement(
        loop: TmpL.WhileStatement,
        out: MutableList<Blimp.Statement>,
        /** Where a path that leaves the loop normally should go, if anywhere. */
        continuation: Continuation? = null,
        /**
         * Whether anything inside can leave the loop entirely.
         *
         * Set by [translateBody] when the loop is the statement it split on,
         * which is exactly when the body holds a `return` or a `break` to a
         * label outside. The call site then has to decode an escape; without
         * one the loop only ever reports `:done`.
         */
        escaping: Boolean = false,
    ) {
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
                            pattern = Blimp.Atom(pos, LOOP_ESCAPE),
                            guard = null,
                            body = Blimp.Block(
                                pos,
                                statements = listOf(Blimp.ExprStatement(pos, signalId.deepCopy())),
                            ),
                        ),
                        Blimp.CaseArm(
                            pos,
                            pattern = Blimp.Atom(pos, LOOP_BREAK),
                            guard = null,
                            body = Blimp.Block(
                                pos,
                                statements = listOf(
                                    Blimp.ExprStatement(
                                        pos,
                                        Blimp.ListLit(
                                            pos,
                                            items =
                                            listOf(Blimp.Atom(pos, LOOP_DONE)) + carriedIds.map { it.deepCopy() },
                                        ),
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
                                                    Blimp.ListLit(
                                                        pos,
                                                        items =
                                                        listOf(Blimp.Atom(pos, LOOP_DONE)) +
                                                            carriedIds.map { it.deepCopy() },
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
            out.add(Blimp.Assign(pos, target = id.deepCopy(), value = elemOf(pos, resultId, index + 1)))
        }
        if (escaping) {
            out.add(decodeEscape(pos, resultId, carriedIds.size, continuation))
        }
    }

    /**
     * The statement that reads an `:escape` back out of a finished loop.
     *
     *     case elem(r, 0) do
     *       :escape -> case elem(r, n + 1) do
     *           0 -> cont_3(x)          # a labelled block that ends here
     *           1 -> elem(r, n + 2)     # the function's return value
     *           _ -> [:escape, ...]     # not ours: hand it to the loop outside
     *         end
     *       _ -> cont_3(x)
     *     end
     *
     * A destination belongs to this call site when the number of loops open
     * where it was registered matches the number open here. Anything else is
     * re-raised, so an escape crossing several loops is decoded once per loop
     * until it reaches the one that can honour it.
     */
    private fun decodeEscape(
        pos: Position,
        resultId: Blimp.Id,
        carriedCount: Int,
        continuation: Continuation?,
    ): Blimp.Statement {
        val dest = elemOf(pos, resultId, carriedCount + 1)
        val payload = elemOf(pos, resultId, carriedCount + 2)
        val enclosing = loops.lastOrNull()
        // Falling off the end of a loop body is itself a signal, so a loop
        // nested in another cannot just evaluate to nil here.
        // Built fresh each time: the same statement is needed in two arms, and
        // a Blimp tree node can only have one parent.
        val fallThrough = {
            when {
                continuation != null -> continuation.callFrom(pos)
                enclosing != null -> enclosing.signal(pos, LOOP_FALL)
                else -> Blimp.ExprStatement(pos, Blimp.NilLit(pos))
            }
        }
        val arms = mutableListOf<Blimp.CaseArm>()
        for ((destination, id) in escapeIds) {
            val action = when {
                destination === FunctionReturn && loops.isEmpty() ->
                    Blimp.ExprStatement(pos, payload.deepCopy())
                destination is ResolvedName ->
                    labelContinuations[destination]
                        ?.takeIf { it.second == loops.size }
                        // A labelled block with nothing after it means "skip
                        // the rest of this loop body", which is a fall, not nil.
                        ?.let { it.first?.callFrom(pos) ?: fallThrough() }
                else -> null
            } ?: continue
            arms.add(
                Blimp.CaseArm(
                    pos,
                    pattern = Blimp.NumberLit(pos, id),
                    guard = null,
                    body = Blimp.Block(pos, statements = listOf(action)),
                ),
            )
        }
        arms.add(
            Blimp.CaseArm(
                pos,
                guard = null,
                pattern = Blimp.Wildcard(pos),
                body = Blimp.Block(
                    pos,
                    statements = listOf(
                        when (enclosing) {
                            null -> Blimp.ExprStatement(pos, Blimp.NilLit(pos))
                            else -> enclosing.escapeOf(pos, dest.deepCopy(), payload.deepCopy())
                        },
                    ),
                ),
            ),
        )
        return Blimp.ExprStatement(
            pos,
            Blimp.CaseExpr(
                pos,
                subject = elemOf(pos, resultId, 0),
                arms = listOf(
                    Blimp.CaseArm(
                        pos,
                        pattern = Blimp.Atom(pos, LOOP_ESCAPE),
                        guard = null,
                        body = Blimp.Block(
                            pos,
                            statements = listOf(
                                Blimp.ExprStatement(pos, Blimp.CaseExpr(pos, subject = dest, arms = arms)),
                            ),
                        ),
                    ),
                    Blimp.CaseArm(
                        pos,
                        pattern = Blimp.Wildcard(pos),
                        guard = null,
                        body = Blimp.Block(pos, statements = listOf(fallThrough())),
                    ),
                ),
            ),
        )
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
