package lang.temper.be.blimp

import lang.temper.common.LeftOrRight
import lang.temper.common.LeftOrRight.Left
import lang.temper.format.OperatorDefinition

/**
 * Blimp's precedence ladder, lowest binding first.
 *
 * Read off `Parser.parseExpression` in `chunks/lang/src/parser.zig`, which is a
 * precedence-climbing chain: `orelse` -> `|>` -> `<-` -> `or` -> `and` ->
 * comparison -> `+ - ++` -> `* /` -> unary -> postfix.
 *
 * Note that Blimp has no `%` operator; remainder is the `rem` builtin.
 */
enum class BlimpOperatorDefinition(
    private val associativity: LeftOrRight = Left,
    /** Blimp parses these with a single `if`, not a loop, so `a == b == c` is a syntax error. */
    private val nonAssociative: Boolean = false,
) : OperatorDefinition {
    OrElse(nonAssociative = true),
    Pipe,
    Send(nonAssociative = true),
    LogicalOr,
    LogicalAnd,
    Relational(nonAssociative = true),
    Additive,
    Multiplicative,
    Prefix(LeftOrRight.Right),
    Postfix,
    ;

    override fun canNest(inner: OperatorDefinition, childIndex: Int) = when {
        inner !is BlimpOperatorDefinition -> false
        ordinal < inner.ordinal -> true
        ordinal > inner.ordinal -> false
        nonAssociative -> false
        else -> (childIndex == 0) == (associativity != LeftOrRight.Right)
    }
}
