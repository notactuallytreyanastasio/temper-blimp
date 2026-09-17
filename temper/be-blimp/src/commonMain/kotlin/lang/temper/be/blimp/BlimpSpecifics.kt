package lang.temper.be.blimp

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
 * How to run translated Blimp.
 *
 * Blimp has no build step and no package manager: `blimp foo.blimp` evaluates
 * the file, so running a translated library is a single command.
 */
object BlimpSpecifics : RunnerSpecifics {
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
    ): List<ToolchainResult> = runBlimp(cliEnv, request)

    override val backendId get() = BlimpBackend.Factory.backendId

    override val tools: List<ToolSpecifics> = listOf(BlimpCommand)
}

object BlimpCommand : ToolSpecifics {
    override val cliNames = listOf("blimp")
}

internal fun runBlimp(cliEnv: CliEnv, request: ToolchainRequest): List<ToolchainResult> {
    return when (request) {
        is RunLibraryRequest -> listOf(cliEnv.runMain(request.libraryName))
        // Blimp's own `blimp test <dir>` runner is not wired up here yet; the
        // translated program runs its asserts inline.
        is RunTestsRequest -> when (val libraryName = request.libraries?.firstOrNull()) {
            null -> unavailable(cliEnv, "Blimp backend needs an explicit library to test")
            else -> listOf(cliEnv.runMain(libraryName))
        }
        is RunBackendSpecificCompilationStepRequest -> error(request)
        is ExecInteractiveRepl -> unavailable(cliEnv, "Blimp backend does not yet drive `blimp --repl`")
    }
}

private fun unavailable(cliEnv: CliEnv, message: String = "Blimp backend cannot serve this request") =
    listOf(
        ToolchainResult(
            result = RFailure(
                CliFailure(message = message, effort = Effort(exitCode = EXIT_UNAVAILABLE, cliEnv = cliEnv)),
            ),
        ),
    )

private fun CliEnv.runMain(libraryName: DashedIdentifier): ToolchainResult {
    val runDir = relativeOutputDirectoryForLibrary(BlimpBackend.Factory.backendId, libraryName)
    val blimp = this[BlimpCommand]
    val command = Command(
        args = listOf(BlimpBackend.MAIN_FILE),
        aux = mapOf(Aux.Stderr to runDir.resolveFile("stderr.txt")),
        cwd = runDir,
    )
    command.maybeLogBeforeRunning(blimp, shellPreferences)
    return ToolchainResult(libraryName = libraryName, result = blimp.run(command))
}
