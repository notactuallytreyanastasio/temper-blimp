package lang.temper.be.piet

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

/** About a minute of npiet on a laptop. */
private const val MAX_STEPS = 1_000_000_000L

class PietFunctionalTest : FunctionalTestRunner<PietBackend>(PietBackend.Factory) {
    override fun runGeneratedCode(
        backend: PietBackend,
        modules: List<Module>,
        outputRoot: OutputRoot,
        outputDir: OutDir,
        outputPaths: Map<ModuleName, FilePath>,
        test: FunctionalTestBase,
        request: ToolchainRequest,
    ) {
        CliEnv.using(factory.specifics, ShellPreferences.functionalTests(console), cancelGroup) {
            copyOutputDir(outputRoot, FilePath.emptyPath)
            val result = runPiet(this, request, maxSteps = MAX_STEPS).first().result
            var pass = false
            try {
                if (test.runAsTest) assertTestingTest(test, result) else test.assertRunOutput(result)
                pass = true
            } finally {
                if (!pass) {
                    result.print(console)
                }
            }
        }
    }
}
