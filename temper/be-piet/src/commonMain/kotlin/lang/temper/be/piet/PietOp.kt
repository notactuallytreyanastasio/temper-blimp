package lang.temper.be.piet

/**
 * A Piet command, named by the colour change that performs it: [hueSteps]
 * around red, yellow, green, cyan, blue, magenta and [darkSteps] around
 * light, normal, dark.
 */
enum class PietOp(val hueSteps: Int, val darkSteps: Int) {
    Push(0, 1),
    Pop(0, 2),
    Add(1, 0),
    Subtract(1, 1),
    Multiply(1, 2),
    Divide(2, 0),
    Mod(2, 1),
    Not(2, 2),
    Greater(3, 0),
    Pointer(3, 1),
    Switch(3, 2),
    Duplicate(4, 0),
    Roll(4, 1),
    InNumber(4, 2),
    InChar(5, 0),
    OutNumber(5, 1),
    OutChar(5, 2),
}

/**
 * What a row of a program is written in. [Const] pushes any integer: Piet's
 * push can only push the size of the block it leaves, so only positive values
 * are pushed directly and the rest are built with arithmetic.
 */
sealed interface PietInstr {
    data class Op(val op: PietOp) : PietInstr {
        override fun toString() = op.name
    }

    data class Const(val value: Long) : PietInstr {
        override fun toString() = "$value"
    }
}

/** A colour block that performs [op] on entry and is [width] codels wide. */
internal data class Step(val op: PietOp, val width: Int = 1)

/**
 * Lowers [PietInstr]s to [Step]s. `push n` is a step whose *preceding* block
 * is n codels wide, which [toStrip] arranges; here it is a [Step] carrying n.
 */
internal fun lower(instrs: List<PietInstr>): List<Step> = buildList {
    for (instr in instrs) {
        when (instr) {
            is PietInstr.Op -> add(Step(instr.op))
            is PietInstr.Const -> pushConst(instr.value)
        }
    }
}

/** Widest block a constant is pushed from directly; larger ones are computed. */
internal const val MAX_DIRECT_PUSH = 12

private fun MutableList<Step>.pushConst(n: Long) {
    when {
        n == 0L -> {
            add(Step(PietOp.Push, 1))
            add(Step(PietOp.Not))
        }
        n == Long.MIN_VALUE -> {
            // No positive counterpart to negate: -MAX - 1.
            pushConst(-Long.MAX_VALUE)
            add(Step(PietOp.Push, 1))
            add(Step(PietOp.Subtract))
        }
        n < 0L -> {
            // 0 - |n|
            pushConst(0L)
            pushConst(-n)
            add(Step(PietOp.Subtract))
        }
        n <= MAX_DIRECT_PUSH -> add(Step(PietOp.Push, n.toInt()))
        else -> {
            // n = a * a + r with r <= 2a, so a row stays roughly logarithmic in n.
            val a = isqrt(n)
            pushConst(a)
            add(Step(PietOp.Duplicate))
            add(Step(PietOp.Multiply))
            val r = n - a * a
            if (r != 0L) {
                pushConst(r)
                add(Step(PietOp.Add))
            }
        }
    }
}

/**
 * floor(sqrt(n)). Compares by division, not by squaring: for n near
 * Long.MAX_VALUE, `(a + 1) * (a + 1)` overflows, goes negative, and a loop
 * written that way never ends.
 */
private fun isqrt(n: Long): Long {
    var a = kotlin.math.sqrt(n.toDouble()).toLong()
    while (a > 0 && a > n / a) a -= 1
    while ((a + 1) <= n / (a + 1)) a += 1
    return a
}
