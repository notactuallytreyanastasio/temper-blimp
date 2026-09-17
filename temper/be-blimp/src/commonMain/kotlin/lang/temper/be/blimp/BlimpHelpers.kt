package lang.temper.be.blimp

import lang.temper.format.TokenSink

/**
 * Quotes a string as a Blimp string literal.
 *
 * Blimp's lexer only understands `\n`, `\t`, `\"` and `\\`; a `\uXXXX` escape
 * passes through verbatim as those six characters. Blimp strings are byte
 * arrays over UTF-8 source, so everything else is emitted raw.
 */
internal fun stringTokenText(value: String): String = buildString {
    append('"')
    for (char in value) {
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            // No escape exists for these, and raw control characters would
            // break the literal, so drop back to the closest printable thing.
            '\r' -> append("\\n")
            else -> append(char)
        }
    }
    append('"')
}

/**
 * Blimp distinguishes Int and Float literals by the presence of a decimal
 * point, and `1.0` prints as `1`, so a whole Double still needs its `.0` in
 * source to stay a Float.
 */
internal fun blimpNumberText(value: Number): String = when (value) {
    is Double -> when {
        value.isNaN() -> "(0.0 / 0.0)"
        value == Double.POSITIVE_INFINITY -> "(1.0 / 0.0)"
        value == Double.NEGATIVE_INFINITY -> "(0.0 - 1.0 / 0.0)"
        value == value.toLong().toDouble() -> "${value.toLong()}.0"
        else -> value.toString()
    }
    is Float -> blimpNumberText(value.toDouble())
    // Blimp's lexer reads the minus as the prefix operator and the digits on
    // their own, so the most negative Int64 has no literal form: the digits
    // alone do not fit. Checked against the interpreter.
    Long.MIN_VALUE -> "(0 - ${Long.MAX_VALUE} - 1)"
    else -> value.toString()
}

/** Blimp comments run from `#` to end of line, so every line gets its own marker. */
internal fun blimpCommentText(text: String): String =
    text.trimEnd().lineSequence().joinToString("\n") { line ->
        when {
            line.isEmpty() -> "#"
            else -> "# $line"
        }
    }

/**
 * Emits `_ # Hole: directive`.
 *
 * The directive is flattened to one line because a Blimp comment runs to end
 * of line and would otherwise swallow the rest of the statement.
 */
internal fun emitBlimpHole(tokenSink: TokenSink, directive: String) {
    tokenSink.value("_")
    tokenSink.comment("# Hole: ${directive.replace(Regex("\\s+"), " ").trim()}")
}
