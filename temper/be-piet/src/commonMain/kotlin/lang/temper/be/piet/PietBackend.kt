package lang.temper.be.piet

import lang.temper.be.Backend
import lang.temper.be.BackendSetup
import lang.temper.be.tmpl.TmpL
import lang.temper.be.tmpl.TmpLTranslator
import lang.temper.common.MimeType
import lang.temper.frontend.Module
import lang.temper.fs.ResourceDescriptor
import lang.temper.log.FilePath
import lang.temper.name.BackendId
import lang.temper.name.BackendMeta
import lang.temper.name.FileType
import lang.temper.name.LanguageLabel

/**
 * <!-- snippet: backend/piet -->
 * # Piet Backend
 *
 * ⎀ backend/piet/id
 *
 * Translates Temper to [Piet], whose programs are images. The output is one
 * `main.ppm` per library, with a codel size of one pixel.
 *
 * ## Pre-requisites
 *
 * `npiet` on the path.
 *
 * [Piet]: https://www.dangermouse.net/esoteric/piet.html
 */
class PietBackend(setup: BackendSetup<PietBackend>) : Backend<PietBackend>(Factory.backendId, setup) {
    override fun tentativeTmpL(): TmpL.ModuleSet =
        TmpLTranslator.translateModules(
            logSink,
            readyModules,
            PietSupportNetwork,
            libraryConfigurations,
            dependencyResolver,
            ::tentativeOutputPathFor,
        )

    override fun translate(finished: TmpL.ModuleSet): List<OutputFileSpecification> {
        TODO("$finished")
    }

    override val supportNetwork = PietSupportNetwork

    private fun tentativeOutputPathFor(module: Module): FilePath =
        allocateTextFile(module, FILE_EXTENSION, defaultName = "module")

    companion object {
        const val FILE_EXTENSION = ".ppm"

        /** A library is one image: Piet has nothing to link with. */
        const val MAIN_FILE = "main.ppm"

        val mimeType = MimeType("image", "x-portable-pixmap")

        /**
         * <!-- snippet: backend/piet/id -->
         * BackendID: `piet`
         */
        internal const val BACKEND_ID = "piet"
    }

    @PluginBackendId(BACKEND_ID)
    @BackendSupportLevel(isSupported = true, isDefaultSupported = false, isTested = false)
    object Factory : Backend.Factory<PietBackend> {
        override val backendId = BackendId(BACKEND_ID)

        override val specifics = PietSpecifics

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

        override val coreLibraryResources: List<ResourceDescriptor> = listOf()

        override fun make(setup: BackendSetup<PietBackend>) = PietBackend(setup)
    }
}
