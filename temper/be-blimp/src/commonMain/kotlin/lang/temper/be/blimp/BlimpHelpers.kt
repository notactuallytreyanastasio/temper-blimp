package lang.temper.be.blimp

import lang.temper.format.TokenSink

/**
 * Quotes a string as a Blimp string literal.
 *
 * Blimp decodes `\n`, `\t`, `\r`, `\e`, `\"` and `\\`. It does not decode `\0`,
 * `\uXXXX` or anything else: an unrecognised escape keeps its backslash, so
 * `\q` is two characters and `\u000d` is six. Blimp strings are byte arrays
 * over UTF-8 source, so everything else is emitted raw.
 *
 * Decoded by the evaluator, not the lexer. `lexString` only counts a
 * backslash as "the next byte cannot close this string"; the switch that
 * turns `\r` into a carriage return is in eval.zig's `.string_lit` branch.
 */
internal fun stringTokenText(value: String): String = buildString {
    append('"')
    for (char in value) {
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            // Blimp has this escape -- `char_code("\r", 0)` answers 13.
            // Writing `\n` here instead, on the theory that it did not, is
            // what made a JSON encoder turn U+000D into U+000A.
            //
            // A raw CR would in fact survive too: `lexString` does not stop at
            // a line break, and all 33 raw control bytes round-trip through a
            // literal unchanged, which is how every other one in this switch's
            // `else` branch gets through. The escape is for the reader, and
            // for any tool that sees the generated file as lines.
            '\r' -> append("\\r")
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
        // A negative zero is `== 0.0`, so the whole-number branch below would
        // print it as "0.0" and lose the sign Temper compares on. Blimp has no
        // literal for it either; multiplying is how you get one.
        value == 0.0 && 1.0 / value < 0.0 -> "(0.0 * (0.0 - 1.0))"
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
