package lang.temper.be.blimp

import lang.temper.be.cli.CliEnv
import lang.temper.be.cli.ShellPreferences
import lang.temper.common.RFailure
import lang.temper.common.RSuccess
import lang.temper.common.console
import lang.temper.common.currents.makeCancelGroupForTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `BlimpSpecifics.runSingleSource` runs a string of Blimp.
 *
 * It is the one method of [lang.temper.be.cli.RunnerSpecifics] the backend did
 * not implement, and nothing in this tree called it, so nothing noticed. A
 * stub that reads `TODO("Not yet implemented")` is the shape this project
 * treats as a bug: it aborts with no location and no name for what is missing.
 */
class BlimpRunnerTest {
    private fun runIt(code: String) = CliEnv.using(
        BlimpSpecifics,
        ShellPreferences.functionalTests(console),
        makeCancelGroupForTest(),
    ) {
        BlimpSpecifics.runSingleSource(cliEnv = this, code = code)
    }

    @Test
    fun runsAString() {
        val result = runIt("puts(concat(\"two plus two is \", to_string(2 + 2)))\n")
        assertTrue(result is RSuccess, "expected success, got $result")
    }

    /**
     * Why a Temper field that a method writes cannot live in actor state.
     *
     * Blimp binds state at handler entry and `become` publishes to the *next*
     * message, so `:go` below sees 0 after calling a handler that set it to 1.
     * Temper fields are ordinary mutable fields, so the translator puts a
     * field at risk of this in a cell instead, and a cell is another actor:
     * `:set` is answered before the sender continues.
     *
     * Both halves are asserted. If Blimp ever made `become` visible to the
     * rest of the handler, the first half would fail and the cells would
     * become an unnecessary message per access.
     */
    @Test
    fun becomeIsVisibleOnlyToTheNextMessage() {
        val stale = runIt(
            """
            |actor A do
            |  state n: Int :: 0
            |  on :bump do
            |    n = n + 1
            |    become n: n
            |    reply n
            |  end
            |  on :go do
            |    r = self <- :bump()
            |    reply [r, n]
            |  end
            |end
            |a = spawn A
            |seen = a <- :go()
            |case seen == [1, 0] do
            |  true -> nil
            |  _ -> raise :become_became_visible_early
            |end
            |
            """.trimMargin(),
        )
        assertTrue(stale is RSuccess, "a state field did not read stale after a self-send: $stale")

        val cell = runIt(
            """
            |actor Cell do
            |  state value: Any :: nil
            |  on :get do reply value end
            |  on :set(v: Any) do
            |    become value: v
            |    reply nil
            |  end
            |end
            |actor B do
            |  state n: Any :: nil
            |  on :__new do
            |    n = spawn Cell
            |    n <- :set(0)
            |    become n: n
            |    reply nil
            |  end
            |  on :bump do
            |    n <- :set((n <- :get) + 1)
            |    reply n <- :get
            |  end
            |  on :go do
            |    r = self <- :bump()
            |    reply [r, n <- :get]
            |  end
            |end
            |b = spawn B
            |b <- :__new()
            |seen = b <- :go()
            |case seen == [1, 1] do
            |  true -> nil
            |  _ -> raise :cell_read_was_stale
            |end
            |
            """.trimMargin(),
        )
        assertTrue(cell is RSuccess, "a cell field read stale after a self-send: $cell")
    }

    /**
     * A program that dies has to come back as a failure. `blimp` exits
     * non-zero for an uncaught bubble, and if that were not plumbed through,
     * a caller would read a crash as a clean run.
     */
    @Test
    fun aProgramThatDiesIsAFailure() {
        val result = runIt("raise :boom\n")
        assertTrue(result is RFailure, "expected failure, got $result")
    }
}
