package lang.temper.be.blimp

import lang.temper.format.TokenSink

/**
 * The operators this backend emits.
 *
 * Blimp spells logical and/or as words, and has no remainder operator -- `rem`
 * is a builtin call, so it is absent here on purpose.
 */
enum class BlimpOperator(
    private val operatorName: String,
    val operatorDefinition: BlimpOperatorDefinition,
) {
    OrElse("orelse", BlimpOperatorDefinition.OrElse),
    Pipe("|>", BlimpOperatorDefinition.Pipe),
    LogicalOr("or", BlimpOperatorDefinition.LogicalOr),
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
    Not("not", BlimpOperatorDefinition.Prefix),
    ;

    fun emit(sink: TokenSink) = when (operatorDefinition) {
        BlimpOperatorDefinition.Prefix -> sink.prefixOp(operatorName)
        else -> sink.infixOp(operatorName)
    }
}
