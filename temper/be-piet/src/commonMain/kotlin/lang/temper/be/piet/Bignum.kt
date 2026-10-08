package lang.temper.be.piet

import lang.temper.be.piet.PietOp.Add
import lang.temper.be.piet.PietOp.Divide
import lang.temper.be.piet.PietOp.Greater
import lang.temper.be.piet.PietOp.Mod
import lang.temper.be.piet.PietOp.Multiply
import lang.temper.be.piet.PietOp.Not
import lang.temper.be.piet.PietOp.Subtract

/*
 * Unsigned big integers, for printing and parsing Float64s exactly. A
 * bignum has the list layout, its elements limbs in base 2^30, least
 * significant first, with no high zero limbs (zero is no limbs at all).
 * A limb times a factor below 2^31, plus a carry, fits in 64 bits on top
 * of the stack, and a sum of two limbs and a carry fits in 32.
 */

const val LIMB_BITS = 30
const val LIMB = 1L shl LIMB_BITS

private fun FunctionWriter.limb(n: Locals.V, i: Locals.V) {
    n.get()
    frame.heapLoad(LIST_DATA)
    i.get()
    e.op(Add)
    frame.heapLoad()
}

private fun FunctionWriter.setLimb(n: Locals.V, i: Locals.V, push: () -> Unit) {
    push()
    n.get()
    frame.heapLoad(LIST_DATA)
    i.get()
    e.op(Add)
    frame.heapStore()
}

private fun FunctionWriter.size(n: Locals.V) {
    n.get()
    frame.heapLoad(LIST_LENGTH)
}

/**
 * Drops high zero limbs. Reads a limb only once it knows there is one: a
 * bignum with no limbs may have no element block at all, and reading
 * element 0 of that would read through address -1.
 */
private fun FunctionWriter.trim(n: Locals.V, i: Locals.V) {
    e.loop(label("trim").hint, {
        size(n)
        e.const(0)
        e.op(Greater)
    }) { exit ->
        i.set {
            size(n)
            e.const(1)
            e.op(Subtract)
        }
        val zero = label("zero")
        limb(n, i)
        e.op(Not)
        e.branch(zero, exit)
        e.startBlock(zero)
        i.get()
        n.get()
        frame.heapStore(LIST_LENGTH)
    }
}

private fun FunctionWriter.inc(v: Locals.V) = v.set {
    v.get()
    e.const(1)
    e.op(Add)
}

/** A new bignum holding zero. */
fun Emitter.newBignum(frame: Frame) {
    const(0)
    const(0)
    const(PietTranslator.NULL)
    frame.alloc(3)
}

/** `n *= k`, k from 1 to 2^31 - 1. */
val Runtime.bnMulSmall: Label get() {
    val add = listBuilderAdd
    return routine("bn_mul_small", 2) {
        val L = Locals(this)
        val n = L.V(param(0))
        val k = L.V(param(1))
        val i = L.v()
        val carry = L.wide()
        L.begin()
        carry.set { e.const(0) }
        e.whileLoop(label("limbs").hint, {
            size(n)
            i.get()
            e.op(Greater)
        }) {
            // t = limb * k + carry; limb = t % B; carry = t / B
            carry.set {
                limb(n, i)
                k.get()
                e.op(Multiply)
                carry.get()
                e.op(Add)
            }
            setLimb(n, i) {
                carry.get()
                e.const(LIMB)
                e.op(Mod)
            }
            carry.set {
                carry.get()
                e.const(LIMB)
                e.op(Divide)
            }
            inc(i)
        }
        e.whileLoop(label("carry").hint, {
            carry.get()
            e.op(Not)
            e.op(Not)
        }) {
            call(frame, add, 2, false) {
                n.get()
                carry.get()
                e.const(LIMB)
                e.op(Mod)
            }
            carry.set {
                carry.get()
                e.const(LIMB)
                e.op(Divide)
            }
        }
        ret(false)
    }
}

/** `n += k`, k below 2^30. */
val Runtime.bnAddSmall: Label get() {
    val add = listBuilderAdd
    return routine("bn_add_small", 2) {
        val L = Locals(this)
        val n = L.V(param(0))
        val carry = L.V(param(1))
        val i = L.v()
        L.begin()
        e.whileLoop(label("limbs").hint, {
            size(n)
            i.get()
            e.op(Greater)
            carry.get()
            e.op(Not)
            e.op(Not)
            e.op(Multiply)
        }) {
            carry.set {
                limb(n, i)
                carry.get()
                e.op(Add)
            }
            setLimb(n, i) {
                carry.get()
                e.const(LIMB)
                e.op(Mod)
            }
            carry.set {
                carry.get()
                e.const(LIMB)
                e.op(Divide)
            }
            inc(i)
        }
        e.ifThen(label("grow").hint, { carry.get() }) {
            call(frame, add, 2, false) {
                n.get()
                carry.get()
            }
        }
        ret(false)
    }
}

/** `a += b` */
val Runtime.bnAdd: Label get() {
    val add = listBuilderAdd
    return routine("bn_add", 2) {
        val L = Locals(this)
        val a = L.V(param(0))
        val b = L.V(param(1))
        val i = L.v()
        val carry = L.v()
        val t = L.v()
        L.begin()
        e.whileLoop(label("limbs").hint, {
            // i < len b || carry
            size(b)
            i.get()
            e.op(Greater)
            carry.get()
            e.op(Add)
        }) {
            e.ifThen(label("grow").hint, {
                size(a)
                i.get()
                e.op(Greater)
                e.op(Not)
            }) {
                call(frame, add, 2, false) {
                    a.get()
                    e.const(0)
                }
            }
            t.set {
                limb(a, i)
                carry.get()
                e.op(Add)
            }
            e.ifThen(label("bhas").hint, {
                size(b)
                i.get()
                e.op(Greater)
            }) {
                t.set {
                    t.get()
                    limb(b, i)
                    e.op(Add)
                }
            }
            setLimb(a, i) {
                t.get()
                e.const(LIMB)
                e.op(Mod)
            }
            carry.set {
                t.get()
                e.const(LIMB)
                e.op(Divide)
            }
            inc(i)
        }
        ret(false)
    }
}

/** `a -= b`, a >= b, dropping high zero limbs after. */
val Runtime.bnSub: Label get() = routine("bn_sub", 2) {
    val L = Locals(this)
    val a = L.V(param(0))
    val b = L.V(param(1))
    val i = L.v()
    val borrow = L.v()
    val t = L.v()
    L.begin()
    e.whileLoop(label("limbs").hint, {
        size(b)
        i.get()
        e.op(Greater)
        borrow.get()
        e.op(Add)
    }) {
        t.set {
            limb(a, i)
            borrow.get()
            e.op(Subtract)
        }
        e.ifThen(label("bhas").hint, {
            size(b)
            i.get()
            e.op(Greater)
        }) {
            t.set {
                t.get()
                limb(b, i)
                e.op(Subtract)
            }
        }
        borrow.set {
            e.const(0)
            t.get()
            e.op(Greater)
        }
        setLimb(a, i) {
            t.get()
            borrow.get()
            e.const(LIMB)
            e.op(Multiply)
            e.op(Add)
        }
        inc(i)
    }
    trim(a, i)
    ret(false)
}

/** -1, 0 or 1 comparing a and b. */
val Runtime.bnCmp: Label get() = routine("bn_cmp", 2) {
    val L = Locals(this)
    val a = L.V(param(0))
    val b = L.V(param(1))
    val i = L.v()
    val d = L.v()
    L.begin()
    // Longer is larger (no high zero limbs).
    d.set {
        size(a)
        size(b)
        e.op(Greater)
        size(b)
        size(a)
        e.op(Greater)
        e.op(Subtract)
    }
    e.ifThen(label("same").hint, {
        d.get()
        e.op(Not)
    }) {
        i.set {
            size(a)
            e.const(1)
            e.op(Subtract)
        }
        e.loop(label("limbs").hint, {
            i.get()
            e.const(-1)
            e.op(Greater)
            d.get()
            e.op(Not)
            e.op(Multiply)
        }) {
            d.set {
                limb(a, i)
                limb(b, i)
                e.op(Greater)
                limb(b, i)
                limb(a, i)
                e.op(Greater)
                e.op(Subtract)
            }
            i.set {
                i.get()
                e.const(1)
                e.op(Subtract)
            }
        }
    }
    d.get()
    ret(true)
}

val Runtime.bnCopy: Label get() {
    val add = listBuilderAdd
    return routine("bn_copy", 1) {
        val L = Locals(this)
        val a = L.V(param(0))
        val c = L.v()
        val i = L.v()
        L.begin()
        c.set { e.newBignum(frame) }
        e.whileLoop(label("limbs").hint, {
            size(a)
            i.get()
            e.op(Greater)
        }) {
            call(frame, add, 2, false) {
                c.get()
                limb(a, i)
            }
            inc(i)
        }
        c.get()
        ret(true)
    }
}

/** `n *= 2^k` for any k >= 0. */
val Runtime.bnShiftLeft: Label get() {
    val mul = bnMulSmall
    return routine("bn_shl", 2) {
        val L = Locals(this)
        val n = L.V(param(0))
        val k = L.V(param(1))
        L.begin()
        e.whileLoop(label("big").hint, {
            k.get()
            e.const(LIMB_BITS - 1L)
            e.op(Greater)
        }) {
            call(frame, mul, 2, false) {
                n.get()
                e.const(LIMB)
            }
            k.set {
                k.get()
                e.const(LIMB_BITS)
                e.op(Subtract)
            }
        }
        call(frame, mul, 2, false) {
            n.get()
            k.get()
            e.pow2()
        }
        ret(false)
    }
}

/** `n *= 10^k` for any k >= 0. */
val Runtime.bnMulPow10: Label get() {
    val mul = bnMulSmall
    return routine("bn_mul_pow10", 2) {
        val L = Locals(this)
        val n = L.V(param(0))
        val k = L.V(param(1))
        L.begin()
        e.whileLoop(label("big").hint, {
            k.get()
            e.const(8)
            e.op(Greater)
        }) {
            call(frame, mul, 2, false) {
                n.get()
                e.const(1_000_000_000)
            }
            k.set {
                k.get()
                e.const(9)
                e.op(Subtract)
            }
        }
        e.whileLoop(label("small").hint, {
            k.get()
            e.const(0)
            e.op(Greater)
        }) {
            call(frame, mul, 2, false) {
                n.get()
                e.const(10)
            }
            k.set {
                k.get()
                e.const(1)
                e.op(Subtract)
            }
        }
        ret(false)
    }
}

/** A new bignum from an Int64 box holding a non-negative value. */
val Runtime.bnFromInt64: Label get() {
    val add = listBuilderAdd
    return routine("bn_from_i64", 1) {
        val L = Locals(this)
        val box = L.V(param(0))
        val n = L.v()
        val v = L.wide()
        L.begin()
        v.set {
            box.get()
            frame.unpack64()
        }
        n.set { e.newBignum(frame) }
        e.whileLoop(label("limbs").hint, {
            v.get()
            e.op(Not)
            e.op(Not)
        }) {
            call(frame, add, 2, false) {
                n.get()
                v.get()
                e.const(LIMB)
                e.op(Mod)
            }
            v.set {
                v.get()
                e.const(LIMB)
                e.op(Divide)
            }
        }
        n.get()
        ret(true)
    }
}

/** The number of bits in n: 0 for zero. */
val Runtime.bnBitLength: Label get() = routine("bn_bitlen", 1) {
    val L = Locals(this)
    val n = L.V(param(0))
    val top = L.v()
    val bits = L.v()
    val i = L.v()
    L.begin()
    returnIfZeroLength(n)
    i.set {
        size(n)
        e.const(1)
        e.op(Subtract)
    }
    top.set { limb(n, i) }
    bits.set {
        i.get()
        e.const(LIMB_BITS)
        e.op(Multiply)
    }
    e.whileLoop(label("top").hint, {
        top.get()
        e.const(0)
        e.op(Greater)
    }) {
        top.set {
            top.get()
            e.const(2)
            e.op(Divide)
        }
        inc(bits)
    }
    bits.get()
    ret(true)
}

private fun FunctionWriter.returnIfZeroLength(n: Locals.V) {
    val zero = label("empty")
    val more = label("nonempty")
    size(n)
    e.op(Not)
    val d0 = e.depth - 1
    e.branch(zero, more)
    e.startBlock(zero, d0)
    e.const(0)
    ret(true)
    e.startBlock(more, d0)
}

/** `n /= d` for d from 1 to 2^30, returning whether anything was lost. */
val Runtime.bnDivSmallSticky: Label get() = routine("bn_div_small", 2) {
    val L = Locals(this)
    val n = L.V(param(0))
    val d = L.V(param(1))
    val i = L.v()
    val rem = L.wide()
    L.begin()
    rem.set { e.const(0) }
    i.set {
        size(n)
        e.const(1)
        e.op(Subtract)
    }
    e.whileLoop(label("limbs").hint, {
        i.get()
        e.const(-1)
        e.op(Greater)
    }) {
        rem.set {
            rem.get()
            e.const(LIMB)
            e.op(Multiply)
            limb(n, i)
            e.op(Add)
        }
        setLimb(n, i) {
            rem.get()
            d.get()
            e.op(Divide)
        }
        rem.set {
            rem.get()
            d.get()
            e.op(Mod)
        }
        i.set {
            i.get()
            e.const(1)
            e.op(Subtract)
        }
    }
    trim(n, i)
    rem.get()
    e.op(Not)
    e.op(Not)
    ret(true)
}

/** The value of n, which must be below 2^60, as a wide value pushed in an Int64 box. */
val Runtime.bnToInt64: Label get() = routine("bn_to_i64", 1) {
    val L = Locals(this)
    val n = L.V(param(0))
    val i = L.v()
    val v = L.wide()
    L.begin()
    v.set { e.const(0) }
    i.set {
        size(n)
        e.const(1)
        e.op(Subtract)
    }
    e.whileLoop(label("limbs").hint, {
        i.get()
        e.const(-1)
        e.op(Greater)
    }) {
        v.set {
            v.get()
            e.const(LIMB)
            e.op(Multiply)
            limb(n, i)
            e.op(Add)
        }
        i.set {
            i.get()
            e.const(1)
            e.op(Subtract)
        }
    }
    v.get()
    frame.pack64()
    ret(true)
}

/** `dst = src`, reusing dst's limbs: no allocation once dst is big enough. */
val Runtime.bnAssign: Label get() {
    val add = listBuilderAdd
    return routine("bn_assign", 2) {
        val L = Locals(this)
        val dst = L.V(param(0))
        val src = L.V(param(1))
        val i = L.v()
        L.begin()
        e.const(0)
        dst.get()
        frame.heapStore(LIST_LENGTH)
        e.whileLoop(label("limbs").hint, {
            size(src)
            i.get()
            e.op(Greater)
        }) {
            call(frame, add, 2, false) {
                dst.get()
                limb(src, i)
            }
            inc(i)
        }
        ret(false)
    }
}
