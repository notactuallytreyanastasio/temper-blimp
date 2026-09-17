package lang.temper.be.blimp

import lang.temper.be.Backend
import lang.temper.be.BackendSetup
import lang.temper.be.storeDescriptorsForDeclarations
import lang.temper.be.tmpl.TmpL
import lang.temper.be.tmpl.TmpLTranslator
import lang.temper.common.MimeType
import lang.temper.frontend.Module
import lang.temper.fs.ResourceDescriptor
import lang.temper.fs.declareResources
import lang.temper.log.FilePath
import lang.temper.log.dirPath
import lang.temper.log.filePath
import lang.temper.name.BackendId
import lang.temper.name.BackendMeta
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
     * Blimp has no module system and a top-level forward reference to a `def`
     * is an error, so all declarations are emitted before any code that runs
     * them.
     */
    override fun translate(finished: TmpL.ModuleSet): List<OutputFileSpecification> {
        val declarations = mutableListOf<Blimp.Item>()
        val mainStatements = mutableListOf<Blimp.Statement>()
        val preludeHelpers = mutableSetOf<String>()
        // A Blimp actor stands alone -- there is no super to call -- so a
        // subclass is flattened, which means every class has to be reachable
        // before any of them is translated.
        val types = buildMap {
            for (module in finished.modules) {
                for (topLevel in module.topLevels) {
                    if (topLevel is TmpL.TypeDeclaration) {
                        typeKeyOf(topLevel)?.let { put(it, topLevel) }
                    }
                }
            }
        }
        for (module in finished.modules) {
            val translated = BlimpTranslator(module, types).translateModule()
            declarations.addAll(translated.declarations)
            mainStatements.addAll(translated.mainStatements)
            preludeHelpers.addAll(translated.preludeHelpers)
        }
        // The prelude is spliced in rather than imported, because Blimp has no
        // module system, and only when something actually called into it.
        val prelude = when {
            preludeHelpers.isEmpty() -> listOf()
            else -> listOf(Blimp.Prelude(finished.pos, preludeResource.load()))
        }
        return listOf(
            TranslatedFileSpecification(
                path = filePath(MAIN_FILE),
                content = Blimp.SourceFile(finished.pos, items = prelude + declarations + mainStatements),
                mimeType = mimeType,
            ),
        )
    }

    override val supportNetwork = BlimpSupportNetwork

    private fun tentativeOutputPathFor(module: Module): FilePath =
        allocateTextFile(module, FILE_EXTENSION, defaultName = "module")

    companion object {
        const val FILE_EXTENSION = ".blimp"

        /** Blimp has no module system, so a library is entered through one file. */
        const val MAIN_FILE = "main.blimp"

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
