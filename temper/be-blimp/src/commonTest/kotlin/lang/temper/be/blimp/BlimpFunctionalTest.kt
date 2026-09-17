package lang.temper.be.blimp

import lang.temper.be.FunctionalTestRunner
import lang.temper.be.assertRunOutput
import lang.temper.be.assertTestingTest
import lang.temper.be.cli.CliEnv
import lang.temper.be.cli.ShellPreferences
import lang.temper.be.cli.ToolchainRequest
import lang.temper.be.cli.print
import lang.temper.common.console
import lang.temper.frontend.Module
import lang.temper.fs.OutDir
import lang.temper.fs.OutputRoot
import lang.temper.log.FilePath
import lang.temper.name.ModuleName
import lang.temper.tests.FunctionalTestBase
import kotlin.test.Test

/**
 * Runs the shared functional test suite against the Blimp backend.
 *
 * Blimp has no build step and no package manager, so the run step is a single
 * `blimp main.blimp` in the library's output directory. There is also nothing
 * to copy alongside it: Blimp cannot import a second file, so temper-core is
 * spliced into the output as a `Blimp.Prelude` at translation time rather than
 * laid down as a sibling.
 *
 * Which tests actually run is decided by the `onlyPasses(blimp(), ...)` entry
 * in `FunctionalTestStatus.kt`. Everything outside that list is skipped, so
 * widening the list is how this backend records progress.
 */
class BlimpFunctionalTest : FunctionalTestRunner<BlimpBackend>(BlimpBackend.Factory) {
    @Test
    override fun algosHelloWorld() {
        super.algosHelloWorld()
    }

    override fun runGeneratedCode(
        backend: BlimpBackend,
        modules: List<Module>,
        outputRoot: OutputRoot,
        outputDir: OutDir,
        outputPaths: Map<ModuleName, FilePath>,
        test: FunctionalTestBase,
        request: ToolchainRequest,
    ) {
        CliEnv.using(factory.specifics, ShellPreferences.functionalTests(console), cancelGroup) {
            // The build wrote blimp/<library>/main.blimp into the output root
            // and runBlimp runs with blimp/<library> as its cwd, so laying the
            // tree down at the environment root is all the copying needed.
            copyOutputDir(outputRoot, FilePath.emptyPath)
            val result = runBlimp(cliEnv = this, request = request).first().result
            var pass = false
            try {
                when {
                    test.runAsTest -> assertTestingTest(test, result)
                    else -> test.assertRunOutput(result)
                }
                pass = true
            } finally {
                if (!pass) {
                    // While the backend is young the TmpL is usually the thing
                    // worth looking at when output does not match.
                    dumpModuleBodies(modules)
                    result.print(console)
                }
            }
        }
    }
}
