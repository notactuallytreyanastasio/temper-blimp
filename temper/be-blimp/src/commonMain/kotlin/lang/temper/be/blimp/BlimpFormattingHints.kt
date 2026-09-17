package lang.temper.be.blimp

import lang.temper.common.TriState
import lang.temper.format.FormattingHints
import lang.temper.format.OutputToken
import lang.temper.format.OutputTokenType

/**
 * Blimp is newline-significant and `do` / `end` delimited, so this is shaped
 * much more like [lang.temper.be.lua.LuaFormattingHints] than like the Rust
 * backend's hints: `do` opens a block and indents, `end` closes one and
 * dedents, and statements are separated by line breaks rather than semicolons.
 */
object BlimpFormattingHints : FormattingHints {
    fun getInstance() = BlimpFormattingHints

    private val closers = setOf(")", "]", "}", ",")
    private val openers = setOf("(", "[", "%{")
    private val breakBefore = setOf("actor", "catch", "def", "end", "on", "state")

    /** Tokens after which a `(` opens an argument list rather than a grouping. */
    private val callableTypes = setOf<OutputTokenType>(OutputTokenType.Name, OutputTokenType.OtherValue)

    override fun spaceBetween(preceding: OutputToken, following: OutputToken): Boolean = when {
        // `Shop.Checkout`, `item.price`
        preceding.text == "." || following.text == "." -> false
        // `print(x)` and `on :add(item)`, but not `x = (a + b) * c`, where the
        // parenthesis is a grouping the formatter inserted after an operator.
        following.text == "(" &&
            (preceding.type in callableTypes || preceding.text in setOf(")", "]", "bubbles", "fn")) -> false
        following.text == "," -> false
        preceding.text in openers -> false
        following.text in setOf(")", "]", "}") -> false
        // `%{name: value}` and `state x: Int` hug the colon on the left.
        following.text == ":" -> false
        else -> super.spaceBetween(preceding, following)
    }

    override fun shouldBreakAfter(token: OutputToken): Boolean = token.text == "do"

    override fun shouldBreakBefore(token: OutputToken): Boolean = token.text in breakBefore

    override fun shouldBreakBetween(preceding: OutputToken, following: OutputToken): TriState = when {
        // An `end` closing an inline `fn(x) do ... end` inside a call argument
        // must not force a line break before the `)` or `,` that follows it.
        preceding.text == "end" && following.text in closers -> TriState.FALSE
        preceding.text == "end" -> TriState.TRUE
        else -> super.shouldBreakBetween(preceding, following)
    }

    override fun indents(token: OutputToken): Boolean = token.text == "do"

    /** `catch` closes the tried block before its own `do` reopens one. */
    override fun dedents(token: OutputToken): Boolean = token.text == "end" || token.text == "catch"

    /**
     * Blimp blocks are delimited by `do` and `end`, never by a single
     * continuation line, so the formatter's transient one-line indents would
     * only drift the block indentation. Lua's backend disables them too.
     */
    override val localLevelIndents: Boolean get() = false

    override val standardIndent get() = "  "
}
