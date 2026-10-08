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

    override val tools: List<ToolSpecifics> = listOf(NpietCommand)
}

object NpietCommand : ToolSpecifics {
    override val cliNames = listOf("npiet")
}

internal fun runPiet(cliEnv: CliEnv, request: ToolchainRequest): List<ToolchainResult> =
    when (request) {
        is RunLibraryRequest -> listOf(cliEnv.runMain(request.libraryName))
        is RunTestsRequest -> unavailable(cliEnv, "Piet backend does not run tests yet")
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

private fun CliEnv.runMain(libraryName: DashedIdentifier): ToolchainResult {
    val runDir = relativeOutputDirectoryForLibrary(PietBackend.Factory.backendId, libraryName)
    val npiet = this[NpietCommand]
    val command = Command(
        args = listOf("-q", "-cs", "1", PietBackend.MAIN_FILE),
        aux = mapOf(Aux.Stderr to runDir.resolveFile("stderr.txt")),
        cwd = runDir,
    )
    command.maybeLogBeforeRunning(npiet, shellPreferences)
    return ToolchainResult(libraryName = libraryName, result = npiet.run(command))
}
