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
