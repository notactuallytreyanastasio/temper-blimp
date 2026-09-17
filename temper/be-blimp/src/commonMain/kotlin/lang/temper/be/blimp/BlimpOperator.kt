package lang.temper.be.blimp

import lang.temper.format.TokenSink

/**
 * The operators this backend emits.
 *
 * Three of Blimp's choices differ from what a C-family backend would assume,
 * and all three were checked against the interpreter:
 *
 * - Boolean negation is `!`. There is no `not` operator; `not` is a builtin
 *   function, so `not false` is an undefined-variable error while `!false` and
 *   `not(false)` both work.
 * - [LogicalAnd] and [LogicalOr] are EAGER. `false and side(true)` still runs
 *   `side`. They are safe only when the right operand has no effects; Temper's
 *   short-circuiting `&&` and `||` must lower to a hoisted `case` instead.
 * - There is no remainder operator. `rem(a, b)` is a builtin call, so it is
 *   absent here on purpose.
 */
enum class BlimpOperator(
    private val operatorName: String,
    val operatorDefinition: BlimpOperatorDefinition,
) {
    OrElse("orelse", BlimpOperatorDefinition.OrElse),
    Pipe("|>", BlimpOperatorDefinition.Pipe),

    /** Eager, not short-circuiting. See the class comment. */
    LogicalOr("or", BlimpOperatorDefinition.LogicalOr),

    /** Eager, not short-circuiting. See the class comment. */
    LogicalAnd("and", BlimpOperatorDefinition.LogicalAnd),
    Equals("==", BlimpOperatorDefinition.Relational),
    NotEquals("!=", BlimpOperatorDefinition.Relational),
    GreaterEquals(">=", BlimpOperatorDefinition.Relational),
    GreaterThan(">", BlimpOperatorDefinition.Relational),
    LessEquals("<=", BlimpOperatorDefinition.Relational),
    LessThan("<", BlimpOperatorDefinition.Relational),
    Addition("+", BlimpOperatorDefinition.Additive),
    Subtraction("-", BlimpOperatorDefinition.Additive),

    /** `++` concatenates strings and lists. */
    Concat("++", BlimpOperatorDefinition.Additive),
    Multiplication("*", BlimpOperatorDefinition.Multiplicative),

    /** Truncating on Int, like Temper's `Int` division. */
    Division("/", BlimpOperatorDefinition.Multiplicative),
    Negate("-", BlimpOperatorDefinition.Prefix),

    /** `!`, not `not`. `not` is a builtin function, not an operator. */
    Not("!", BlimpOperatorDefinition.Prefix),
    ;

    fun emit(sink: TokenSink) = when (operatorDefinition) {
        BlimpOperatorDefinition.Prefix -> sink.prefixOp(operatorName)
        else -> sink.infixOp(operatorName)
    }
}
