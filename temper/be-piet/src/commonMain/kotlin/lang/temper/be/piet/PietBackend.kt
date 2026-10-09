package lang.temper.be.piet

import lang.temper.be.Backend
import lang.temper.be.BackendSetup
import lang.temper.be.SiblingData
import lang.temper.be.tmpl.TmpL
import lang.temper.be.tmpl.TmpLTranslator
import lang.temper.common.MimeType
import lang.temper.frontend.Module
import lang.temper.fs.ResourceDescriptor
import lang.temper.log.FilePath
import lang.temper.log.filePath
import lang.temper.log.resolveFile
import lang.temper.name.BackendId
import lang.temper.name.BackendMeta
import lang.temper.name.DashedIdentifier
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

    /**
     * One image holds the library and everything it depends on, dependencies
     * first so that their module-level code runs first. Piet has nothing to
     * link with, so a dependency is translated again into each dependent.
     */
    override fun translate(finished: TmpL.ModuleSet): List<OutputFileSpecification> {
        val moduleSets = dependencyLibrariesInOrder().mapNotNull { siblingModuleSets[it] } + finished
        // Each module's `_connected.pasm`, if it has one.
        val connected = moduleSets.flatMap { it.modules }.mapNotNull { module ->
            val path = module.codeLocation.codeLocation.sourceFile.resolveFile(Pasm.CONNECTED_FILE)
            rawBackendFiles[path]?.let { Pasm(it) }
        }
        fun image(tests: Boolean) = PietTranslator(
            moduleSets.flatMap { it.modules },
            finished.modules.toSet(),
            connected.flatMap { it.routines.values }.associateBy { it.name },
            tests,
        ).translate().render().toPpm()
        // Tests get an image of their own, which runs them instead.
        val hasTests = finished.modules.any { m -> m.topLevels.any { it is TmpL.Test } }
        return listOfNotNull(
            ByteArrayFileSpecification(filePath(MAIN_FILE), image(false)),
            // What runs an image, answering its requests for the date and for HTTP.
            ByteArrayFileSpecification(filePath(IO_FILE), IO_SCRIPT.encodeToByteArray()),
            if (hasTests) ByteArrayFileSpecification(filePath(TEST_FILE), image(true)) else null,
        )
    }

    /**
     * Every sibling library's TmpL, by library root. A library is translated
     * before the libraries it depends on, so their own backends have produced
     * nothing yet; [finishTmpL] is the one place every sibling's TmpL is
     * available. (The Blimp backend meets the same problem the same way.)
     */
    private var siblingModuleSets: Map<FilePath, TmpL.ModuleSet> = mapOf()

    override fun finishTmpL(tentative: TmpL.ModuleSet, siblings: SiblingData<TmpL.ModuleSet>): TmpL.ModuleSet {
        siblingModuleSets = siblings.dataByLibraryRoot.filterKeys {
            it != libraryConfigurations.currentLibraryConfiguration.libraryRoot
        }
        return super.finishTmpL(tentative, siblings)
    }

    /** Library roots this one depends on, transitively, each after its own dependencies. */
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

    override val supportNetwork = PietSupportNetwork

    private fun tentativeOutputPathFor(module: Module): FilePath =
        allocateTextFile(module, FILE_EXTENSION, defaultName = "module")

    companion object {
        const val FILE_EXTENSION = ".ppm"

        /** A library is one image: Piet has nothing to link with. */
        const val MAIN_FILE = "main.ppm"
        const val TEST_FILE = "test.ppm"

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
                // The build reads every source file with one of these
                // extensions as text and hands it to the backend, so this
                // lists what be-piet reads, not what it writes: a library's
                // own Piet assembly. Listing ".ppm" here made a Piet image
                // kept beside a library's source fail the build with
                // MalformedInputException.
                fileExtensionMap = mapOf(
                    FileType.Header to ".pasm",
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
