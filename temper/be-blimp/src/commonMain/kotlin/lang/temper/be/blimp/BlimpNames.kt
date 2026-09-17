package lang.temper.be.blimp

import lang.temper.name.ExportedName
import lang.temper.name.OutName
import lang.temper.name.ResolvedName
import lang.temper.name.Temporary

/**
 * Blimp's reserved words, read off `Token.keyword` in the interpreter's lexer.
 *
 * Note what is absent: `not`, `if`, `while`, `return` and `import` are not
 * keywords, because Blimp has no such constructs.
 */
private val blimpKeywords = setOf(
    "actor", "and", "become", "bubble", "bubbles", "case", "catch", "def", "do", "end", "false", "fn",
    "for", "given", "in", "nil", "on", "or", "orelse", "property", "reply", "self", "situation",
    "spawn", "state", "test", "true", "try", "when",
)

/** Blimp identifiers are ASCII letters, digits and underscore, not starting with a digit. */
private val notIdentifierChar = Regex("[^A-Za-z0-9_]")

/**
 * Makes Blimp identifiers, keeping them stable and collision-free.
 *
 * Blimp has no module system, so every name in a translated program shares one
 * flat namespace. TmpL names already carry a disambiguating suffix (`a__4`),
 * which is what keeps that workable.
 */
internal class BlimpNames {
    private var gensymCount = 0

    fun outName(name: ResolvedName): OutName = OutName(identText(name), name)

    /** A fresh name that cannot collide with a translated one, for hoisted temporaries. */
    fun gensym(hint: String): OutName = OutName("blimp_${sanitize(hint)}_${gensymCount++}", null)

    private fun identText(name: ResolvedName): String = sanitize(
        when (name) {
            is ExportedName -> name.displayName
            // Three underscores, as be-rust does, so a temporary cannot collide
            // with a user name that happens to end in digits.
            is Temporary -> "${name.nameHint}___${name.uid}"
            else -> "$name"
        },
    )

    private fun sanitize(text: String): String {
        val cleaned = notIdentifierChar.replace(text, "_")
        val legal = when {
            cleaned.isEmpty() -> "_"
            cleaned.first().isDigit() -> "_$cleaned"
            else -> cleaned
        }
        return when (legal) {
            in blimpKeywords -> "${legal}_"
            else -> legal
        }
    }
}
