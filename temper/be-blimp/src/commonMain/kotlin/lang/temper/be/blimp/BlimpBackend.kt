package lang.temper.be.blimp

import lang.temper.be.Backend
import lang.temper.be.BackendSetup
import lang.temper.be.storeDescriptorsForDeclarations
import lang.temper.be.tmpl.TmpL
import lang.temper.be.tmpl.TmpLTranslator
import lang.temper.common.MimeType
import lang.temper.frontend.Module
import lang.temper.fs.ResourceDescriptor
import lang.temper.log.FilePath
import lang.temper.log.filePath
import lang.temper.name.BackendId
import lang.temper.name.BackendMeta
import lang.temper.name.FileType
import lang.temper.name.LanguageLabel
import lang.temper.name.OutName

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
     * Placeholder translation.
     *
     * Per the backend guide, the first step is a bogus translator that emits a
     * working program in the target language whatever the input, which proves
     * out the file plumbing and the run path before any real tree walking.
     * [BlimpTranslator] replaces this.
     */
    override fun translate(finished: TmpL.ModuleSet): List<OutputFileSpecification> = listOf(
        TranslatedFileSpecification(
            path = filePath(MAIN_FILE),
            content = Blimp.SourceFile(
                finished.pos,
                items = listOf(
                    Blimp.ExprStatement(
                        finished.pos,
                        expr = Blimp.Call(
                            finished.pos,
                            callee = Blimp.Id(finished.pos, OutName("print", null)),
                            args = listOf(Blimp.StringLit(finished.pos, "Hello, World!")),
                        ),
                    ),
                ),
            ),
            mimeType = mimeType,
        ),
    )

    override val supportNetwork = BlimpSupportNetwork

    private fun tentativeOutputPathFor(module: Module): FilePath =
        allocateTextFile(module, FILE_EXTENSION, defaultName = "module")

    companion object {
        const val FILE_EXTENSION = ".blimp"

        /** Blimp has no module system, so a library is entered through one file. */
        const val MAIN_FILE = "main.blimp"

        val mimeType = MimeType("text", "blimp")

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

        // TODO A temper-core written in Blimp: UTF-8 aware strings, maps with
        //  non-string keys, list helpers, and a raw `puts`.
        override val coreLibraryResources: List<ResourceDescriptor> = listOf()

        override fun make(setup: BackendSetup<BlimpBackend>) = BlimpBackend(setup)
    }
}
