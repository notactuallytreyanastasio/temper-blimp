package lang.temper.be.blimp

import lang.temper.be.Backend
import lang.temper.be.assertGeneratedCode
import lang.temper.log.FilePath
import lang.temper.log.filePath
import kotlin.test.Test

/**
 * Hoisted names are unique across the whole output file, not per module.
 *
 * Blimp has no module system, so every module of every library in the
 * dependency graph is spliced into one `main.blimp`. A gensym counter that
 * restarted per module handed `blimp_loop_3` to two of them, and the second
 * `def` silently won -- a wrong answer with nothing to read as a symptom.
 *
 * The two modules here are the same function under two names, so before the
 * counter was shared their hoisted loops were `blimp_loop_0` twice, and
 * `count` and `tally` both ran `tally`'s.
 *
 * `while (f) { f = false }` rather than a counter because an `i + 1` calls
 * `temper_int32`, and anything that needs temper-core brings three thousand
 * lines of prelude into the expectation.
 */
class BlimpGensymTest {
    @Test
    fun twoModulesDoNotShareHoistedNames() {
        val loop = """
            |export let count(flag: Boolean): Boolean {
            |  var f = flag;
            |  while (f) { f = false; }
            |  f
            |}
        """.trimMargin()
        val blimp = """
            |def blimp_loop_0(f__0: Any) -> Any do
            |  case f__0 do
            |    true -> blimp_signal_1 = case true do
            |      true -> f__0 = false
            |      [:fall, f__0]
            |    end
            |    f__0 = elem(blimp_signal_1, 1)
            |    case elem(blimp_signal_1, 0) do
            |      :escape -> blimp_signal_1
            |      :break ->[:done, f__0]
            |      _ -> blimp_loop_0(f__0)
            |    end
            |    _ ->[:done, f__0]
            |  end
            |end
            |def count(flag__0: Any) -> Any do
            |  f__0 = flag__0
            |  blimp_carried_2 = blimp_loop_0(f__0)
            |  f__0 = elem(blimp_carried_2, 1)
            |  f__0
            |end
            |def blimp_loop_3(f__0: Any) -> Any do
            |  case f__0 do
            |    true -> blimp_signal_4 = case true do
            |      true -> f__0 = false
            |      [:fall, f__0]
            |    end
            |    f__0 = elem(blimp_signal_4, 1)
            |    case elem(blimp_signal_4, 0) do
            |      :escape -> blimp_signal_4
            |      :break ->[:done, f__0]
            |      _ -> blimp_loop_3(f__0)
            |    end
            |    _ ->[:done, f__0]
            |  end
            |end
            |def tally(flag__0: Any) -> Any do
            |  f__0 = flag__0
            |  blimp_carried_5 = blimp_loop_3(f__0)
            |  f__0 = elem(blimp_carried_5, 1)
            |  f__0
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
            inputs = listOf<Pair<FilePath, String>>(
                filePath("one", "one.temper") to loop,
                filePath("two", "two.temper") to loop.replace("count", "tally"),
            ),
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
