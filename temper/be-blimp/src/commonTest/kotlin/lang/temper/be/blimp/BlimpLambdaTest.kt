package lang.temper.be.blimp

import lang.temper.be.Backend
import lang.temper.be.assertGeneratedCode
import lang.temper.log.FilePath
import lang.temper.log.filePath
import kotlin.test.Test

/**
 * A nested function that captures nothing becomes a top-level `def`.
 *
 * The interpreter copies the whole environment into a closure when it makes
 * one, and a nested function is made every time the function around it
 * runs. In a program the size of a web site that is about 140KB a closure:
 * a helper passing `{ (s) => s }` to `join` made one page take 470MB where
 * the same helper in Blimp took 36MB. A def is made once.
 *
 * `negate`'s lambda uses only its own parameter, so it is lifted and the
 * local is bound to the def's name. `keep`'s reads `c`, a parameter of the
 * function around it, so it stays a `fn`.
 */
class BlimpLambdaTest {
    @Test
    fun aLambdaThatCapturesNothingIsADef() {
        val source = """
            |export let apply(b: Boolean, f: fn (Boolean): Boolean): Boolean { f(b) }
            |export let negate(b: Boolean): Boolean { apply(b) { (x: Boolean): Boolean => !x } }
            |export let keep(b: Boolean, c: Boolean): Boolean { apply(b) { (x: Boolean): Boolean => c } }
        """.trimMargin()
        val blimp = """
            |def apply(b__0: Any, f__0: Any) -> Any do
            |  f__0(b__0)
            |end
            |def blimp_fn__0(x__0: Any) -> Any do
            |  !x__0
            |end
            |def negate(b__1: Any) -> Any do
            |  fn__0 = blimp_fn__0
            |  apply(b__1, fn__0)
            |end
            |def keep(b__2: Any, c__0: Any) -> Any do
            |  fn__1 = fn(x__1: Any) do
            |    c__0
            |  end
            |  apply(b__2, fn__1)
            |end
            |
        """.trimMargin()
        val escaped = """
            |               "content":
            |```
            |$blimp
            |```
        """.trimMargin()
        assertGeneratedCode(
            backendConfig = Backend.Config.production,
            factory = BlimpBackend.Factory,
            inputs = listOf<Pair<FilePath, String>>(filePath("one", "one.temper") to source),
            want = """
                |{
                |    "blimp": {
                |        "my-test-library": {
                |            "main.blimp": {
                |${escaped}
                |            },
                |            "main.blimp.map": "__DO_NOT_CARE__"
                |        }
                |    }
                |}
            """.trimMargin(),
        )
    }
}
