package lang.temper.be.piet

import lang.temper.be.piet.PietOp.Add
import lang.temper.be.piet.PietOp.Divide
import lang.temper.be.piet.PietOp.Duplicate
import lang.temper.be.piet.PietOp.Greater
import lang.temper.be.piet.PietOp.Mod
import lang.temper.be.piet.PietOp.Multiply
import lang.temper.be.piet.PietOp.Not
import lang.temper.be.piet.PietOp.Subtract

/*
 * Int64. npiet's integers are 64 bits wide, but its roll moves a value
 * through a C int, and every stored value is reached by a roll. So an
 * Int64 is a two-cell box `hi, lo`, both 32-bit and lo signed, and every
 * operation unpacks to one native 64-bit value on top of the stack, works
 * there, and packs the result into a new box.
 *
 * The rule that makes that safe: a 64-bit value on the stack is never the
 * element a roll moves. A roll moves one element and shifts the rest, and
 * shifting keeps all 64 bits. So the code below only ever moves small
 * values (addresses, halves) past wide ones, and copies wide ones with dup.
 */

const val TWO_32 = 1L shl 32

/** Pushes Long.MIN_VALUE, which has no positive counterpart to negate. */
fun Emitter.constMin64() {
    const(Long.MAX_VALUE)
    const(-1)
    op(Multiply)
    const(1)
    op(Subtract)
}

/** `[box] -> [v]` */
fun Frame.unpack64() {
    e.op(Duplicate)
    heapLoad(0)
    e.const(TWO_32)
    e.op(Multiply)
    // [box, hi * 2^32] -> raise the box, which is small, over the wide value.
    // Not swap: roll(2, 1) moves the *top* element, and that is the wide
    // one, which the move would cut to 32 bits.
    e.const(2)
    e.const(-1)
    e.op(PietOp.Roll)
    heapLoad(1)
    e.const(TWO_32)
    e.op(Add)
    e.const(TWO_32)
    e.op(Mod)
    e.op(Add)
}

/**
 * `[v] -> [box]`, with hi the true top half (floor(v / 2^32)) and lo the
 * bottom half's bit pattern as a signed Int, so that the halves are what
 * two's complement says they are and bitwise operations can work on them
 * one at a time. hi comes first: deriving it from a signed lo is off by
 * one whenever lo is negative, which turned -1 into 4294967295.
 */
fun Frame.pack64() {
    // q = v / 2^32 truncates; floor needs one less when v % 2^32 < 0.
    e.op(Duplicate)
    e.op(Duplicate)
    e.const(TWO_32)
    e.op(Mod)
    e.const(0)
    e.swap()
    e.op(Greater)
    // [v, v, c] -> move c (small) under the second v: [v, c, v]
    e.const(2)
    e.const(1)
    e.op(PietOp.Roll)
    e.const(TWO_32)
    e.op(Divide)
    // [v, c, q] -> q fits in 32 bits, so it may move: [v, q, c]
    e.const(2)
    e.const(1)
    e.op(PietOp.Roll)
    e.op(Subtract)
    // [v, hi] -> [hi, v, hi], moving only hi
    e.op(Duplicate)
    e.const(3)
    e.const(1)
    e.op(PietOp.Roll)
    e.const(TWO_32)
    e.op(Multiply)
    e.op(Subtract)
    // [hi, lo] with lo in [0, 2^32): make it a signed Int before it is stored.
    e.op(Duplicate)
    e.const(Int.MAX_VALUE.toLong())
    e.op(Greater)
    e.const(TWO_32)
    e.op(Multiply)
    e.op(Subtract)
    alloc(2)
}

/**
 * `[a, b] -> [va, vb]`, or `[vb, va]` if [reversed], from two boxes. Only
 * the boxes ever move.
 */
fun Frame.unpackPair64(reversed: Boolean) {
    if (!reversed) e.swap()
    unpack64()
    // [other box, v1] -> raise the box from under v1 (it moves, v1 shifts).
    e.const(2)
    e.const(-1)
    e.op(PietOp.Roll)
    unpack64()
}

/** Digits of an Int64 in a radix, as a new string. */
val Runtime.int64ToString: Label get() {
    val add = listBuilderAdd
    return routine("i64_to_string", 2) {
        begin(4)
        val box = param(0)
        val radix = param(1)
        val neg = param(2)
        val v = param(3)
        val digits = param(4)
        val header = param(5)
        // v = -|x|, boxed; neg remembers the sign.
        frame.load(box)
        frame.unpack64()
        e.op(Duplicate)
        e.const(0)
        e.swap()
        e.op(Greater)
        frame.store(neg)
        // [x] -> x > 0 ? -x : x
        e.op(Duplicate)
        e.const(0)
        e.op(Greater)
        e.const(-2)
        e.op(Multiply)
        e.const(1)
        e.op(Add)
        e.op(Multiply)
        frame.pack64()
        frame.store(v)
        frame.load(digits).also { }
        e.op(PietOp.Pop)
        e.const(0)
        e.const(0)
        e.const(PietTranslator.NULL)
        frame.alloc(3)
        frame.store(digits)
        e.loop(label("digits").hint, { e.const(1) }) { exit ->
            // digit = -(v % radix); v = v / radix
            call(frame, add, 2, false) {
                frame.load(digits)
                frame.load(v)
                frame.unpack64()
                frame.load(radix)
                e.op(Mod)
                e.const(-1)
                e.op(Multiply)
            }
            frame.load(v)
            frame.unpack64()
            frame.load(radix)
            e.op(Divide)
            e.op(Duplicate)
            e.op(Not)
            val more = label("more")
            val done = label("done")
            e.branch(done, more)
            e.startBlock(done)
            e.op(PietOp.Pop)
            e.jump(exit)
            e.startBlock(more)
            frame.pack64()
            frame.store(v)
        }
        e.const(0)
        frame.alloc(1)
        frame.store(header)
        e.ifThen(label("sign").hint, { frame.load(neg) }) {
            e.const('-'.code)
            frame.append()
        }
        // Digits came least significant first.
        frame.load(digits)
        frame.heapLoad(LIST_LENGTH)
        frame.store(v)
        e.whileLoop(label("emit").hint, {
            frame.load(v)
            e.const(0)
            e.op(Greater)
        }) {
            frame.load(v)
            e.const(1)
            e.op(Subtract)
            frame.store(v)
            frame.load(digits)
            frame.heapLoad(LIST_DATA)
            frame.load(v)
            e.op(Add)
            frame.heapLoad()
            e.op(Duplicate)
            e.const(9)
            e.op(Greater)
            e.const('a'.code - '0'.code - 10)
            e.op(Multiply)
            e.op(Add)
            e.const('0'.code)
            e.op(Add)
            frame.append()
        }
        frame.fillLength { frame.load(header) }
        frame.load(header)
        this@int64ToString.internTop(frame)
        ret(true)
    }
}

/**
 * `s.toInt64(radix)`, as stringToInt but accumulating in a box, and checking
 * for overflow before multiplying: acc * radix - d >= MIN exactly when acc
 * >= (MIN + d) / radix, the division truncating toward zero.
 */
val Runtime.stringToInt64: Label get() = routine("str_to_i64", 2) {
    begin(6)
    val s = param(0)
    val radix = param(1)
    val i = param(2)
    val end = param(3)
    val neg = param(4)
    val acc = param(5)
    val c = param(6)
    val d = param(7)
    fun cp(at: () -> Unit) {
        frame.load(s)
        at()
        e.op(Add)
        frame.heapLoad(1)
    }
    fun isSpace(at: () -> Unit) {
        cp(at)
        e.op(Duplicate)
        e.const(32)
        e.op(Subtract)
        e.op(Not)
        e.swap()
        e.op(Duplicate)
        e.const(8)
        e.op(Greater)
        e.swap()
        e.const(14)
        e.swap()
        e.op(Greater)
        e.op(Multiply)
        e.op(Add)
    }
    fun failUnless(name: String) {
        val ok = label(name)
        e.branch(ok, failLanding(e.depth - 1))
        e.startBlock(ok)
    }
    frame.load(radix)
    e.const(1)
    e.op(Greater)
    e.const(37)
    frame.load(radix)
    e.op(Greater)
    e.op(Multiply)
    failUnless("radix")
    frame.load(s)
    frame.heapLoad()
    frame.store(end)
    e.loop(label("lead").hint, {
        frame.load(end)
        frame.load(i)
        e.op(Greater)
    }) { exit ->
        val more = label("more")
        isSpace { frame.load(i) }
        e.branch(more, exit)
        e.startBlock(more)
        frame.load(i)
        e.const(1)
        e.op(Add)
        frame.store(i)
    }
    e.loop(label("trail").hint, {
        frame.load(end)
        frame.load(i)
        e.op(Greater)
    }) { exit ->
        val more = label("more")
        isSpace {
            frame.load(end)
            e.const(1)
            e.op(Subtract)
        }
        e.branch(more, exit)
        e.startBlock(more)
        frame.load(end)
        e.const(1)
        e.op(Subtract)
        frame.store(end)
    }
    frame.load(end)
    frame.load(i)
    e.op(Greater)
    failUnless("nonempty")
    e.ifThen(label("sign").hint, {
        cp { frame.load(i) }
        e.const('-'.code)
        e.op(Subtract)
        e.op(Not)
    }) {
        e.const(1)
        frame.store(neg)
        frame.load(i)
        e.const(1)
        e.op(Add)
        frame.store(i)
    }
    frame.load(end)
    frame.load(i)
    e.op(Greater)
    failUnless("digit")
    e.const(0)
    frame.pack64()
    frame.store(acc)
    e.whileLoop(label("digits").hint, {
        frame.load(end)
        frame.load(i)
        e.op(Greater)
    }) {
        cp { frame.load(i) }
        frame.store(c)
        e.const(99)
        fun range(lo: Char, hi: Char, offset: Int) {
            frame.load(c)
            e.const(lo.code - 1)
            e.op(Greater)
            e.const(hi.code + 1)
            frame.load(c)
            e.op(Greater)
            e.op(Multiply)
            frame.load(c)
            e.const(lo.code - offset + 99)
            e.op(Subtract)
            e.op(Multiply)
            e.op(Add)
        }
        range('0', '9', 0)
        range('a', 'z', 10)
        range('A', 'Z', 10)
        frame.store(d)
        frame.load(radix)
        frame.load(d)
        e.op(Greater)
        failUnless("valid")
        // acc >= (MIN + d) / radix, else overflow. Both are wide, so they
        // cannot be swapped on the stack: push limit first, then acc, and
        // ask !(limit > acc).
        e.constMin64()
        frame.load(d)
        e.op(Add)
        frame.load(radix)
        e.op(Divide)
        frame.load(acc)
        frame.unpack64()
        e.op(Greater)
        e.op(Not)
        failUnless("fits")
        frame.load(acc)
        frame.unpack64()
        frame.load(radix)
        e.op(Multiply)
        frame.load(d)
        e.op(Subtract)
        frame.pack64()
        frame.store(acc)
        frame.load(i)
        e.const(1)
        e.op(Add)
        frame.store(i)
    }
    e.ifElseValue(label("negate").hint, { frame.load(neg) }, { frame.load(acc) }) {
        // -MIN does not fit.
        frame.load(acc)
        frame.unpack64()
        e.constMin64()
        e.op(Subtract)
        failUnless("positive")
        frame.load(acc)
        frame.unpack64()
        e.const(-1)
        e.op(Multiply)
        frame.pack64()
    }
    ret(true)
    end()
}

/** `[n] -> [2^n]` for 0 <= n <= 63, multiplying in 2^(2^b) for each set bit b of n. No branch. */
fun Emitter.pow2() {
    const(1)
    for (b in 0 until 6) {
        // [n, p] -> p * (1 + bit_b(n) * (2^(2^b) - 1))
        pick(2)
        const(1L shl b)
        op(Divide)
        const(2)
        op(Mod)
        const((1L shl (1 shl b)) - 1)
        op(Multiply)
        const(1)
        op(Add)
        op(Multiply)
    }
    // [n, p] -> [p]. Not dropUnder: that rolls the top down, and the top is
    // p, which may be wider than 32 bits. Raise n over it and pop n instead.
    const(2)
    const(-1)
    op(PietOp.Roll)
    op(PietOp.Pop)
}

/**
 * Shifts, masked first as Java does (`5 << 32 == 5`), and done with one
 * multiplication or floor division by a power of two computed by [pow2].
 * A loop of doublings was correct, and types/int/shifty took 235 seconds.
 */
fun Runtime.shift(name: String, wide: Boolean, left: Boolean, unsigned: Boolean): Label =
    routine("shift_$name", 2) {
        begin(0)
        val x = param(0)
        val n = param(1)
        val bits = if (wide) 64 else 32
        // n = n mod bits, non-negative
        frame.load(n)
        e.const(bits)
        e.op(Mod)
        e.const(bits)
        e.op(Add)
        e.const(bits)
        e.op(Mod)
        frame.store(n)
        fun floorDivBy(pushDivisor: () -> Unit) {
            // [v] -> floor(v / d) = v / d - (v % d < 0)
            e.op(PietOp.Duplicate)
            pushDivisor()
            e.op(Mod)
            e.const(0)
            e.swap()
            e.op(Greater)
            // [v, c] -> move c under v
            e.swap()
            pushDivisor()
            e.op(Divide)
            // [c, q]: q may be wide; move c over it, not q.
            e.const(2)
            e.const(-1)
            e.op(PietOp.Roll)
            e.op(Subtract)
        }
        when {
            !wide && left -> {
                frame.load(x)
                frame.load(n)
                e.pow2()
                e.op(Multiply)
                wrap32()
            }
            !wide && !unsigned -> {
                frame.load(x)
                floorDivBy {
                    frame.load(n)
                    e.pow2()
                }
            }
            !wide -> {
                // The 32-bit pattern as an unsigned value, halved n times, back to Int32.
                frame.load(x)
                e.const(TWO_32)
                e.op(Add)
                e.const(TWO_32)
                e.op(Mod)
                frame.load(n)
                e.pow2()
                e.op(Divide)
                wrap32()
            }
            left -> {
                frame.load(x)
                frame.unpack64()
                frame.load(n)
                e.pow2()
                e.op(Multiply)
                frame.pack64()
            }
            else -> {
                // Split the count so that no divisor needs 2^63: n = n1 + n2, each <= 32.
                frame.load(x)
                frame.unpack64()
                if (unsigned) {
                    // Shift once unsigned when n > 0: floor(v / 2) + (v < 0) * 2^63,
                    // the 2^63 added as MAX + 1 to stay in range; then n - 1 more.
                    e.ifElse(label("any").hint, { frame.load(n) }, {
                        // [v] -> half
                        floorDivBy { e.const(2) }
                        e.op(PietOp.Duplicate)
                        e.const(0)
                        e.swap()
                        e.op(Greater)
                        e.op(PietOp.Duplicate)
                        e.const(Long.MAX_VALUE)
                        e.op(Multiply)
                        e.op(Add)
                        e.op(Add)
                        // now n - 1 further, as a floor division of a non-negative value
                        floorDivBy {
                            frame.load(n)
                            e.const(1)
                            e.op(Subtract)
                            e.const(32)
                            e.minTop()
                            e.pow2()
                        }
                        floorDivBy {
                            frame.load(n)
                            e.const(1)
                            e.op(Subtract)
                            e.const(32)
                            e.minTop()
                            frame.load(n)
                            e.const(1)
                            e.op(Subtract)
                            e.swap()
                            e.op(Subtract)
                            e.pow2()
                        }
                    }) { }
                } else {
                    floorDivBy {
                        frame.load(n)
                        e.const(32)
                        e.minTop()
                        e.pow2()
                    }
                    floorDivBy {
                        frame.load(n)
                        e.const(32)
                        e.minTop()
                        frame.load(n)
                        e.swap()
                        e.op(Subtract)
                        e.pow2()
                    }
                }
                frame.pack64()
            }
        }
        ret(true)
    }

/** `[v] -> [v wrapped to Int32]`, all on top of the stack. */
fun FunctionWriter.wrap32() {
    e.const(TWO_32)
    e.op(Mod)
    e.const(TWO_32 + (1L shl 31))
    e.op(Add)
    e.const(TWO_32)
    e.op(Mod)
    e.const(1L shl 31)
    e.op(Subtract)
}
