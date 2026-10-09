package lang.temper.be.piet

import lang.temper.be.Dependencies
import lang.temper.be.cli.Aux
import lang.temper.be.cli.CliEnv
import lang.temper.be.cli.CliFailure
import lang.temper.be.cli.Command
import lang.temper.be.cli.EXIT_UNAVAILABLE
import lang.temper.be.cli.Effort
import lang.temper.be.cli.EffortSuccess
import lang.temper.be.cli.ExecInteractiveRepl
import lang.temper.be.cli.RunBackendSpecificCompilationStepRequest
import lang.temper.be.cli.RunLibraryRequest
import lang.temper.be.cli.RunTestsRequest
import lang.temper.be.cli.RunnerSpecifics
import lang.temper.be.cli.ToolSpecifics
import lang.temper.be.cli.ToolchainRequest
import lang.temper.be.cli.ToolchainResult
import lang.temper.be.cli.maybeLogBeforeRunning
import lang.temper.common.RFailure
import lang.temper.common.RResult
import lang.temper.common.RSuccess
import lang.temper.fs.OutDir
import lang.temper.library.relativeOutputDirectoryForLibrary
import lang.temper.log.FilePath
import lang.temper.log.resolveFile
import lang.temper.name.DashedIdentifier

/**
 * How to run translated Piet: `npiet -q -cs 1 main.ppm`.
 *
 * The codel size is given rather than guessed. npiet guesses it from the
 * narrowest run of one colour, and a program whose every block is two codels
 * wide would be read at half size.
 */
object PietSpecifics : RunnerSpecifics {
    override fun runSingleSource(
        cliEnv: CliEnv,
        code: String,
        env: Map<String, String>,
        aux: Map<Aux, FilePath>,
    ): RResult<EffortSuccess, CliFailure> {
        TODO("Not yet implemented")
    }

    override fun runBestEffort(
        cliEnv: CliEnv,
        request: ToolchainRequest,
        code: OutDir,
        dependencies: Dependencies<*>,
    ): List<ToolchainResult> = runPiet(cliEnv, request)

    override val backendId get() = PietBackend.Factory.backendId

    override val tools: List<ToolSpecifics> = listOf(NpietCommand, ShellCommand)
}

object NpietCommand : ToolSpecifics {
    override val cliNames = listOf("npiet")
}

/**
 * npiet runs under a shell so that today's date can arrive on its stdin: a
 * Piet program has no clock, and Date.today() reads year, month and day with
 * three in(number) commands. Temper has no input of its own, so nothing
 * else reads stdin.
 */
object ShellCommand : ToolSpecifics {
    override val cliNames = listOf("sh")
}

/**
 * [maxSteps] bounds how many colour blocks npiet may visit. A Piet program
 * that loops forever is indistinguishable from a slow one, so tests pass a
 * budget and a program that exhausts it fails on its output.
 */
internal fun runPiet(cliEnv: CliEnv, request: ToolchainRequest, maxSteps: Long? = null): List<ToolchainResult> =
    when (request) {
        is RunLibraryRequest -> listOf(cliEnv.runMain(request.libraryName, maxSteps))
        is RunTestsRequest -> request.libraries.orEmpty().map { cliEnv.runTests(it, maxSteps) }
        is RunBackendSpecificCompilationStepRequest -> error(request)
        is ExecInteractiveRepl -> unavailable(cliEnv, "Piet has no REPL")
    }

private fun unavailable(cliEnv: CliEnv, message: String) =
    listOf(
        ToolchainResult(
            result = RFailure(
                CliFailure(message = message, effort = Effort(exitCode = EXIT_UNAVAILABLE, cliEnv = cliEnv)),
            ),
        ),
    )

/** A run whose output carries [PANIC_MARKER] failed, whatever npiet's exit status says. */
private fun panicked(result: RResult<EffortSuccess, CliFailure>, cliEnv: CliEnv): RResult<EffortSuccess, CliFailure> {
    val stdout = (result as? RSuccess)?.result?.stdout ?: return result
    val at = stdout.indexOf(PANIC_MARKER)
    if (at < 0) return result
    return RFailure(
        CliFailure("panic", effort = Effort(stdout = stdout.substring(0, at), exitCode = 1, cliEnv = cliEnv)),
    )
}

private fun CliEnv.runMain(libraryName: DashedIdentifier, maxSteps: Long?): ToolchainResult =
    ToolchainResult(libraryName = libraryName, result = runImage(libraryName, PietBackend.MAIN_FILE, maxSteps))

/**
 * The test image prints std/testing's JUnit XML after whatever the
 * library's own module code logs; the XML goes where test reporting looks.
 */
private fun CliEnv.runTests(libraryName: DashedIdentifier, maxSteps: Long?): ToolchainResult {
    val result = runImage(libraryName, PietBackend.TEST_FILE, maxSteps)
    val effort = (result as? RSuccess)?.result as? Effort
        ?: return ToolchainResult(libraryName = libraryName, result = result)
    val start = effort.stdout.indexOf(JUNIT_START)
    val end = effort.stdout.lastIndexOf(JUNIT_END)
    if (start < 0 || end < start) {
        return ToolchainResult(
            libraryName = libraryName,
            result = RFailure(CliFailure("no test report in the output", effort = effort.copy(exitCode = 1))),
        )
    }
    val junit = effort.stdout.substring(start, end + JUNIT_END.length)
    return ToolchainResult(
        libraryName = libraryName,
        result = RSuccess(effort.copy(stdout = effort.stdout.substring(0, start)).withAux(Aux.JunitXml, junit)),
    )
}

private const val JUNIT_START = "<testsuites>"
private const val JUNIT_END = "</testsuites>"

private fun CliEnv.runImage(libraryName: DashedIdentifier, file: String, maxSteps: Long?): RResult<EffortSuccess, CliFailure> {
    val runDir = relativeOutputDirectoryForLibrary(PietBackend.Factory.backendId, libraryName)
    val shell = this[ShellCommand]
    val npietArgs = buildList {
        add("-q")
        add("-cs")
        add("1")
        if (maxSteps != null) {
            add("-e")
            add("$maxSteps")
        }
        add(file)
    }
    val command = Command(
        args = listOf("-c", "date -u '+%Y %m %d' | exec npiet ${npietArgs.joinToString(" ")}"),
        aux = mapOf(Aux.Stderr to runDir.resolveFile("stderr.txt")),
        cwd = runDir,
    )
    command.maybeLogBeforeRunning(shell, shellPreferences)
    return panicked(shell.run(command), this)
}
