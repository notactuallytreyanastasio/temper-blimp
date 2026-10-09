package lang.temper.be.piet

import java.io.File
import java.util.concurrent.TimeUnit

/** Runs [image] under npiet, which must be on PATH, and returns its stdout. */
fun runNpiet(image: PietImage, stdin: String = ""): String {
    val file = File.createTempFile("piet", ".ppm")
    try {
        file.writeBytes(image.toPpm())
        val process = ProcessBuilder("npiet", "-q", "-cs", "1", file.path)
            .redirectErrorStream(true)
            .start()
        process.outputStream.use { it.write(stdin.toByteArray()) }
        val out = process.inputStream.readBytes().decodeToString()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "npiet did not halt" }
        return out
    } finally {
        file.delete()
    }
}
