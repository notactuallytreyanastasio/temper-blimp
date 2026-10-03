package lang.temper.be.blimp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [blimpBuiltins] against the interpreter this repository carries in blimp/.
 *
 * A name Blimp defines and this list lacks is one a Temper export can take,
 * and a definition beats a builtin for the whole program. The list was read
 * off builtins.zig by hand, and by 2026-10 it was 63 names behind: the
 * crypto, json, utf8, http and WebSocket builtins, `sleep_ms` and
 * `read_line`, which temper-core itself calls, and the view's `el`, `fetch`,
 * `show`, `stored`, `store` and `socket`. This test reads the names out of
 * the source instead, so refreshing blimp/ is enough to find the next one.
 */
class BlimpNamesTest {
    private fun blimpSrc(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val src = File(dir, "blimp/chunks/lang/src")
            if (File(src, "builtins.zig").isFile) return src
            dir = dir.parentFile
        }
        fail("no blimp/chunks/lang/src/builtins.zig above ${System.getProperty("user.dir")}")
    }

    @Test
    fun everyNameBlimpDefinesIsOneATemperNameCannotTake() {
        val src = blimpSrc()
        // reg.register("name", ...) in builtins.zig, and the names eval.zig
        // dispatches itself before any builtin (map, filter, show, ...)
        val registered = Regex("""reg\.register\("([^"]+)"""")
            .findAll(File(src, "builtins.zig").readText()).map { it.groupValues[1] }
        val dispatched = Regex("""std\.mem\.eql\(u8, call\.name, "([^"]+)"\)""")
            .findAll(File(src, "eval.zig").readText()).map { it.groupValues[1] }
        // `nil?` and `empty?` cannot be spelled by a sanitized name; see BlimpNames
        val names = (registered + dispatched).filter { Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it) }.toSortedSet()
        assertTrue(names.size > 100, "read only ${names.size} names out of $src")
        val missing = names - blimpBuiltins
        if (missing.isNotEmpty()) {
            fail("Blimp defines ${missing.size} names BlimpNames does not keep Temper names off: $missing")
        }
    }

    @Test
    fun everyKeywordTheLexerHasIsOneATemperNameCannotTake() {
        val token = File(blimpSrc(), "token.zig").readText()
        val keywords = Regex("""\.\{ "([a-z_]+)", \.kw_""").findAll(token).map { it.groupValues[1] }.toSortedSet()
        assertTrue(keywords.size > 20, "read only ${keywords.size} keywords out of token.zig")
        val missing = keywords - blimpKeywords
        if (missing.isNotEmpty()) fail("Blimp keywords BlimpNames does not avoid: $missing")
    }
}
