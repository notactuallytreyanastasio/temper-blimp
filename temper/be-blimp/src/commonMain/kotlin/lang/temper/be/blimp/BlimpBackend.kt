package lang.temper.be.blimp

import lang.temper.be.Backend
import lang.temper.be.SiblingData
import lang.temper.be.BackendSetup
import lang.temper.be.storeDescriptorsForDeclarations
import lang.temper.be.tmpl.TmpL
import lang.temper.be.tmpl.TmpLTranslator
import lang.temper.common.MimeType
import lang.temper.frontend.Module
import lang.temper.fs.ResourceDescriptor
import lang.temper.fs.declareResources
import lang.temper.log.FilePath
import lang.temper.log.resolveFile
import lang.temper.log.dirPath
import lang.temper.log.filePath
import lang.temper.name.BackendId
import lang.temper.name.BackendMeta
import lang.temper.name.DashedIdentifier
import lang.temper.name.FileType
import lang.temper.name.LanguageLabel

/**
 * <!-- snippet: backend/blimp -->
 * # Blimp Backend
 *
 * ⎀ backend/blimp/id
 *
 * Translates Temper to [Blimp], an actor-oriented language whose only
 * abstraction is the actor.
 *
 * A Temper class becomes a Blimp `actor`: its fields become `state`, its
 * methods become `on :name(...)` handlers, and a method call becomes a
 * synchronous `obj <- :name(args)` send. Temper's module paths become dotted
 * actor names such as `Shop.Checkout`, which in Blimp are simultaneously the
 * namespace and the supervision edge.
 *
 * ## Pre-requisites
 *
 * A `blimp` interpreter on the path.
 *
 * [Blimp]: https://blimp.bobbby.online/
 */
class BlimpBackend(setup: BackendSetup<BlimpBackend>) : Backend<BlimpBackend>(Factory.backendId, setup) {
    override fun tentativeTmpL(): TmpL.ModuleSet =
        TmpLTranslator.translateModules(
            logSink,
            readyModules,
            BlimpSupportNetwork,
            libraryConfigurations,
            dependencyResolver,
            ::tentativeOutputPathFor,
        ).also {
            storeDescriptorsForDeclarations(it, Factory)
        }

    /**
     * Folds every module into one `main.blimp`.
     *
     * Blimp has no module system, and the order within the file matters: a
     * `def` body resolves its calls when it runs, so two `def`s may refer to
     * each other in any order, but a top-level *statement* resolves as it is
     * reached. `y = z` above `def z` is an undefined variable. Declarations
     * therefore come before any code that runs them, which is also why a
     * pasted-in library has to come before the library that uses it.
     */
    override fun translate(finished: TmpL.ModuleSet): List<OutputFileSpecification> {
        // Everything that ends up in the file, dependencies first: a library's
        // top-level statements run where they stand, and
        // `int32JsonAdapter__11 = int32JsonAdapter` is one of those.
        val moduleSets = dependencyLibrariesInOrder().mapNotNull { siblingModuleSets[it] } + finished
        val declarations = mutableListOf<Blimp.Item>()
        val mainStatements = mutableListOf<Blimp.Statement>()
        val preludeHelpers = mutableSetOf<String>()
        // A Blimp actor stands alone -- there is no super to call -- so a
        // subclass is flattened, which means every class has to be reachable
        // before any of them is translated. A subclass in one library of a
        // class in another is why this spans every module set, not just this
        // library's.
        val types = buildMap {
            for (moduleSet in moduleSets) {
                for (module in moduleSet.modules) {
                    for (topLevel in module.topLevels) {
                        if (topLevel is TmpL.TypeDeclaration) {
                            typeKeyOf(topLevel)?.let { put(it, topLevel) }
                        }
                    }
                }
            }
        }
        // A library can hand the backend its own Blimp, the way it hands
        // be-lua a `_connected.lua`: a `_connected.blimp` beside the module
        // source is spliced in and its `connected_<name>` functions are what a
        // @connected declaration calls.
        val connectedSources = mutableListOf<String>()
        val libraryName = libraryConfigurations.currentLibraryConfiguration.libraryName
        for (moduleSet in moduleSets) {
            val own = moduleSet === finished
            for (module in moduleSet.modules) {
                val connectedPath = module.codeLocation.codeLocation.sourceFile.resolveFile(CONNECTED_FILE)
                rawBackendFiles[connectedPath]?.let(connectedSources::add)
                val translated = BlimpTranslator(module, types, emitTests = own).translateModule()
                declarations.addAll(translated.declarations)
                mainStatements.addAll(translated.mainStatements)
                preludeHelpers.addAll(translated.preludeHelpers)
                // Only this library's, and under the Blimp name: the harness
                // matches what it finds in the XML against what is registered
                // here, and the XML carries `aTestCase__53`, not the sentence
                // the test was declared with. A dependency's tests belong to
                // the dependency, which registers them itself.
                if (own) {
                    for ((test, blimpName) in translated.tests) {
                        dependenciesBuilder.addTest(libraryName, test, blimpName)
                    }
                }
            }
        }
        val connected = connectedSources.map { Blimp.Prelude(finished.pos, it) }
        // The prelude is spliced in rather than imported, because Blimp has no
        // module system, and only when something actually called into it.
        val prelude = when {
            preludeHelpers.isEmpty() -> listOf()
            else -> listOf(Blimp.Prelude(finished.pos, preludeResource.load()))
        }
        return listOf(
            TranslatedFileSpecification(
                path = filePath(MAIN_FILE),
                content = Blimp.SourceFile(
                    finished.pos,
                    items = prelude + connected + declarations + mainStatements,
                ),
                mimeType = mimeType,
            ),
        )
    }

    /**
     * Every sibling library's TmpL, by library root.
     *
     * Every other backend hands the dependency problem to the target language:
     * be-lua writes `require`, be-py writes `import`. Blimp has neither and no
     * way to build one -- `blimp_eval(read_file(...))` runs the text in a scope
     * of its own, so a `def` inside it is gone by the time the caller looks:
     *
     * ```
     * $ blimp inc_main.blimp
     * -- UNKNOWN FUNCTION ──────────────────────────────
     *   I don't know a function called `lib_add`.
     * ```
     *
     * So a dependency is translated again, into the dependent's own file. Not
     * taken from the dependency's own [BlimpBackend], which would be the
     * obvious way: a library is translated *before* the libraries it depends
     * on, so by the time `std` has produced anything, `work` is already
     * written. [finishTmpL] is early enough, and hands every sibling's TmpL
     * over at once.
     */
    private var siblingModuleSets: Map<FilePath, TmpL.ModuleSet> = mapOf()

    override fun finishTmpL(tentative: TmpL.ModuleSet, siblings: SiblingData<TmpL.ModuleSet>): TmpL.ModuleSet {
        siblingModuleSets = siblings.dataByLibraryRoot.filterKeys {
            it != libraryConfigurations.currentLibraryConfiguration.libraryRoot
        }
        return super.finishTmpL(tentative, siblings)
    }

    /**
     * The roots of the libraries this one depends on, transitively, in an order
     * where a library follows everything it depends on.
     *
     * A cycle would have no such order. The frontend rejects those before a
     * backend sees them, and if one ever arrives the visited set turns it into
     * a missing declaration rather than a hang.
     */
    private fun dependencyLibrariesInOrder(): List<FilePath> {
        val current = libraryConfigurations.currentLibraryConfiguration.libraryName
        val shallow = dependenciesBuilder.build().shallowDependencies
        val order = mutableListOf<FilePath>()
        val visited = mutableSetOf(current)
        fun visit(name: DashedIdentifier) {
            if (!visited.add(name)) return
            shallow[name].orEmpty().sorted().forEach(::visit)
            libraryConfigurations.byLibraryName[name]?.libraryRoot?.let(order::add)
        }
        shallow[current].orEmpty().sorted().forEach(::visit)
        return order
    }

    override val supportNetwork = BlimpSupportNetwork

    private fun tentativeOutputPathFor(module: Module): FilePath =
        allocateTextFile(module, FILE_EXTENSION, defaultName = "module")

    companion object {
        const val FILE_EXTENSION = ".blimp"

        /** Blimp has no module system, so a library is entered through one file. */
        const val MAIN_FILE = "main.blimp"

        /** A library's own Blimp, spliced in beside the prelude. */
        const val CONNECTED_FILE = "_connected.blimp"

        /** Where a translated test module writes its JUnit XML. */
        const val TEST_RESULTS_FILE = "test-results.xml"

        val mimeType = MimeType("text", "blimp")

        /** temper-core, written in Blimp, spliced into output that needs it. */
        internal val preludeResource: ResourceDescriptor =
            declareResources(
                base = dirPath("lang", "temper", "be", "blimp", "temper-core"),
                filePath("core.blimp"),
            ).single()

        /**
         * <!-- snippet: backend/blimp/id -->
         * BackendID: `blimp`
         */
        internal const val BACKEND_ID = "blimp"
    }

    @PluginBackendId(BACKEND_ID)
    @BackendSupportLevel(isSupported = true, isDefaultSupported = false, isTested = false)
    object Factory : Backend.Factory<BlimpBackend> {
        override val backendId = BackendId(BACKEND_ID)

        override val specifics = BlimpSpecifics

        override val backendMeta: BackendMeta
            get() = BackendMeta(
                backendId = backendId,
                languageLabel = LanguageLabel(backendId.uniqueId),
                fileExtensionMap = mapOf(
                    FileType.Module to FILE_EXTENSION,
                    FileType.Script to FILE_EXTENSION,
                ),
                mimeTypeMap = mapOf(
                    FileType.Module to mimeType,
                    FileType.Script to mimeType,
                ),
            )

        /**
         * Empty on purpose.
         *
         * This mechanism copies a runtime library beside the output, which
         * only helps a target language that can import it. Blimp cannot, so
         * temper-core is spliced into the emitted file as a [Blimp.Prelude]
         * instead. See [preludeResource].
         */
        override val coreLibraryResources: List<ResourceDescriptor> = listOf()

        override fun make(setup: BackendSetup<BlimpBackend>) = BlimpBackend(setup)
    }
}
