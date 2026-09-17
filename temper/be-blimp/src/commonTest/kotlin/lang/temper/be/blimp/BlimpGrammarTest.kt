package lang.temper.be.blimp

import lang.temper.format.CodeFormatter
import lang.temper.format.toStringViaTokenSink
import lang.temper.name.OutName
import kotlin.test.Test
import kotlin.test.assertEquals
import lang.temper.log.unknownPos as p0

/**
 * Checks that the out-grammar renders syntax Blimp's parser actually accepts.
 *
 * Every expectation here was first run through `zig-out/bin/blimp` by hand, so
 * these are not guesses about the target language.
 */
class BlimpGrammarTest {
    private fun assertCode(expected: String, ast: Blimp.Tree) {
        val actual = toStringViaTokenSink(formattingHints = BlimpFormattingHints, singleLine = false) {
            CodeFormatter(it).format(ast)
        }
        assertEquals(expected.trimEnd(), actual.trimEnd())
    }

    private fun id(text: String) = Blimp.Id(p0, OutName(text, null))

    private fun name(vararg segments: String) = Blimp.Name(p0, segments.map { id(it) })

    @Test
    fun hello() {
        assertCode(
            """print("Hello, World!")""",
            Blimp.ExprStatement(
                p0,
                expr = Blimp.Call(
                    p0,
                    callee = id("print"),
                    args = listOf(Blimp.StringLit(p0, "Hello, World!")),
                ),
            ),
        )
    }

    @Test
    fun actorWithStateAndHandler() {
        assertCode(
            """
                |actor Counter do
                |  state n: Int :: 0
                |  on :bump(k: Int) do
                |    become n: n + k
                |    reply n + k
                |  end
                |end
            """.trimMargin(),
            Blimp.ActorDecl(
                p0,
                name = name("Counter"),
                states = listOf(
                    Blimp.StateDecl(p0, id = id("n"), type = id("Int"), init = Blimp.NumberLit(p0, 0)),
                ),
                handlers = listOf(
                    Blimp.Handler(
                        p0,
                        message = Blimp.Atom(p0, "bump"),
                        params = listOf(Blimp.Param(p0, id = id("k"), type = id("Int"))),
                        body = Blimp.Block(
                            p0,
                            statements = listOf(
                                Blimp.Become(
                                    p0,
                                    fields = listOf(Blimp.BecomeField(p0, id = id("n"), value = addNAndK())),
                                ),
                                Blimp.Reply(p0, value = addNAndK()),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun addNAndK() = Blimp.Operation(
        p0,
        left = id("n"),
        operator = Blimp.Operator(p0, BlimpOperator.Addition),
        right = id("k"),
    )

    @Test
    fun dottedActorNameAndSpawn() {
        assertCode(
            """c = spawn Shop.Checkout, region: :us""",
            Blimp.Assign(
                p0,
                target = id("c"),
                value = Blimp.Spawn(
                    p0,
                    name = name("Shop", "Checkout"),
                    inits = listOf(Blimp.SpawnInit(p0, id = id("region"), value = Blimp.Atom(p0, "us"))),
                ),
            ),
        )
    }

    @Test
    fun sendWithArguments() {
        assertCode(
            """total = cart <- :add(item, 2)""",
            Blimp.Assign(
                p0,
                target = id("total"),
                value = Blimp.Send(
                    p0,
                    target = id("cart"),
                    message = Blimp.MessageCall(
                        p0,
                        name = Blimp.Atom(p0, "add"),
                        args = listOf(id("item"), Blimp.NumberLit(p0, 2)),
                    ),
                ),
            ),
        )
    }

    @Test
    fun caseExpressionWithGuard() {
        assertCode(
            """
                |r = case n do
                |  0 -> :zero
                |  m when m > 0 -> :positive
                |  _ -> :negative
                |end
            """.trimMargin(),
            Blimp.Assign(
                p0,
                target = id("r"),
                value = Blimp.CaseExpr(
                    p0,
                    subject = id("n"),
                    arms = listOf(
                        arm(Blimp.NumberLit(p0, 0), null, Blimp.Atom(p0, "zero")),
                        arm(
                            id("m"),
                            Blimp.Operation(
                                p0,
                                left = id("m"),
                                operator = Blimp.Operator(p0, BlimpOperator.GreaterThan),
                                right = Blimp.NumberLit(p0, 0),
                            ),
                            Blimp.Atom(p0, "positive"),
                        ),
                        arm(Blimp.Wildcard(p0), null, Blimp.Atom(p0, "negative")),
                    ),
                ),
            ),
        )
    }

    private fun arm(pattern: Blimp.Pattern, guard: Blimp.Expr?, result: Blimp.Expr) = Blimp.CaseArm(
        p0,
        pattern = pattern,
        guard = guard,
        body = Blimp.Block(p0, statements = listOf(Blimp.ExprStatement(p0, result))),
    )

    /** Blimp's `*` binds tighter than `+`, so only the sum needs parentheses. */
    @Test
    fun parenthesizesByPrecedence() {
        assertCode(
            """x = (a + b) * c""",
            Blimp.Assign(
                p0,
                target = id("x"),
                value = Blimp.Operation(
                    p0,
                    left = Blimp.Operation(
                        p0,
                        left = id("a"),
                        operator = Blimp.Operator(p0, BlimpOperator.Addition),
                        right = id("b"),
                    ),
                    operator = Blimp.Operator(p0, BlimpOperator.Multiplication),
                    right = id("c"),
                ),
            ),
        )
    }

    @Test
    fun defWithReturnType() {
        assertCode(
            """
                |def double(n: Int) -> Int do
                |  n * 2
                |end
            """.trimMargin(),
            Blimp.DefDecl(
                p0,
                id = id("double"),
                params = listOf(Blimp.Param(p0, id = id("n"), type = id("Int"))),
                returnType = id("Int"),
                body = Blimp.Block(
                    p0,
                    statements = listOf(
                        Blimp.ExprStatement(
                            p0,
                            Blimp.Operation(
                                p0,
                                left = id("n"),
                                operator = Blimp.Operator(p0, BlimpOperator.Multiplication),
                                right = Blimp.NumberLit(p0, 2),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun floatKeepsItsDecimalPoint() {
        assertCode("""x = 1.0""", Blimp.Assign(p0, target = id("x"), value = Blimp.NumberLit(p0, 1.0)))
    }

    @Test
    fun stringEscapes() {
        assertCode(
            """x = "a\nb\"c\\d"""",
            Blimp.Assign(p0, target = id("x"), value = Blimp.StringLit(p0, "a\nb\"c\\d")),
        )
    }
}
