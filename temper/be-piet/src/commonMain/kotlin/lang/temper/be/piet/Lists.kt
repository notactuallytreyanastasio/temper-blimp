package lang.temper.be.piet

import lang.temper.be.piet.PietOp.Add
import lang.temper.be.piet.PietOp.Greater
import lang.temper.be.piet.PietOp.Multiply
import lang.temper.be.piet.PietOp.Not
import lang.temper.be.piet.PietOp.Subtract

/*
 * Lists. List, ListBuilder and Listed share one layout, so code written
 * against Listed works on either:
 *
 *     h(l) = length, h(l+1) = capacity, h(l+2) = address of the elements
 *
 * The elements are a separate block so that a ListBuilder can grow: the
 * heap only grows at its top, so growing means a new, larger block and a
 * copy. The old block is garbage; nothing is ever freed.
 *
 * A routine that calls a function value allocates nothing contiguous
 * across that call, because the function may allocate too. Results go
 * into cells allocated before the first call.
 */

const val LIST_LENGTH = 0
const val LIST_CAPACITY = 1
const val LIST_DATA = 2

private fun FunctionWriter.field(slot: Int, offset: Int) {
    frame.load(slot)
    frame.heapLoad(offset)
}

private fun FunctionWriter.setField(slot: Int, offset: Int, push: () -> Unit) {
    push()
    frame.load(slot)
    frame.heapStore(offset)
}

/** Pushes element [index] of the list in [list]. */
private fun FunctionWriter.element(list: Int, index: () -> Unit) {
    field(list, LIST_DATA)
    index()
    e.op(Add)
    frame.heapLoad()
}

/** Pops a value into element [index] of the list in [list]. */
private fun FunctionWriter.setElement(list: Int, index: () -> Unit) {
    field(list, LIST_DATA)
    index()
    e.op(Add)
    frame.heapStore()
}

private fun FunctionWriter.inc(slot: Int, by: Int = 1) {
    frame.load(slot)
    e.const(by)
    e.op(Add)
    frame.store(slot)
}

/** `for (i = 0; i < length of list; ++i)`. */
private fun FunctionWriter.forEachIndex(name: String, list: Int, i: Int, body: () -> Unit) {
    e.const(0)
    frame.store(i)
    e.whileLoop(label(name).hint, {
        field(list, LIST_LENGTH)
        frame.load(i)
        e.op(Greater)
    }) {
        body()
        inc(i)
    }
}

/** n zero cells, consecutive; pushes the first's address. */
val Runtime.allocZeros: Label get() = routine("zeros", 1) {
    begin(2)
    val n = param(0)
    val start = param(1)
    val i = param(2)
    frame.loadHeapSize()
    frame.store(start)
    e.const(0)
    frame.store(i)
    e.whileLoop(label("zeros").hint, {
        frame.load(n)
        frame.load(i)
        e.op(Greater)
    }) {
        e.const(0)
        frame.append()
        inc(i)
    }
    frame.load(start)
    ret(true)
}

/** A list header over [data] with the given length and capacity. */
private fun FunctionWriter.newHeader(length: () -> Unit, capacity: () -> Unit, data: () -> Unit) {
    length()
    capacity()
    data()
    frame.alloc(3)
}

/** `list[i]`, which bubbles out of range. */
val Runtime.listGet: Label get() = routine("list_get", 2) {
    begin(0)
    val l = param(0)
    val i = param(1)
    // i < 0 || !(length > i)
    e.const(0)
    frame.load(i)
    e.op(Greater)
    field(l, LIST_LENGTH)
    frame.load(i)
    e.op(Greater)
    e.op(Not)
    e.op(Add)
    val ok = label("ok")
    e.op(Not)
    e.branch(ok, failLanding(e.depth - 1))
    e.startBlock(ok)
    element(l) { frame.load(i) }
    ret(true)
    end()
}

/** A copy whose elements are its own: toList, toListBuilder, sorted. */
val Runtime.listCopy: Label get() {
    val zeros = allocZeros
    return routine("list_copy", 1) {
        begin(2)
        val l = param(0)
        val data = param(1)
        val i = param(2)
        call(frame, zeros, 1, true) { field(l, LIST_LENGTH) }
        frame.store(data)
        forEachIndex("copy", l, i) {
            element(l) { frame.load(i) }
            frame.load(data)
            frame.load(i)
            e.op(Add)
            frame.heapStore()
        }
        newHeader({ field(l, LIST_LENGTH) }, { field(l, LIST_LENGTH) }, { frame.load(data) })
        ret(true)
        end()
    }
}

val Runtime.listBuilderAdd: Label get() {
    val zeros = allocZeros
    return routine("lb_add", 2) {
        begin(3)
        val lb = param(0)
        val x = param(1)
        val newCap = param(2)
        val newData = param(3)
        val i = param(4)
        e.ifThen(label("full").hint, {
            field(lb, LIST_LENGTH)
            field(lb, LIST_CAPACITY)
            e.op(Subtract)
            e.op(Not)
        }) {
            field(lb, LIST_CAPACITY)
            e.const(2)
            e.op(Multiply)
            e.const(4)
            e.op(Add)
            frame.store(newCap)
            call(frame, zeros, 1, true) { frame.load(newCap) }
            frame.store(newData)
            forEachIndex("grow", lb, i) {
                element(lb) { frame.load(i) }
                frame.load(newData)
                frame.load(i)
                e.op(Add)
                frame.heapStore()
            }
            setField(lb, LIST_DATA) { frame.load(newData) }
            setField(lb, LIST_CAPACITY) { frame.load(newCap) }
        }
        frame.load(x)
        setElement(lb) { field(lb, LIST_LENGTH) }
        setField(lb, LIST_LENGTH) {
            field(lb, LIST_LENGTH)
            e.const(1)
            e.op(Add)
        }
        ret(false)
        end()
    }
}

val Runtime.listMap: Label get() {
    val zeros = allocZeros
    return routine("list_map", 2) {
        begin(2)
        val l = param(0)
        val f = param(1)
        val data = param(2)
        val i = param(3)
        call(frame, zeros, 1, true) { field(l, LIST_LENGTH) }
        frame.store(data)
        forEachIndex("map", l, i) {
            callClosure(frame, 1, true, failLanding(), { frame.load(f) }) { element(l) { frame.load(i) } }
            frame.load(data)
            frame.load(i)
            e.op(Add)
            frame.heapStore()
        }
        newHeader({ field(l, LIST_LENGTH) }, { field(l, LIST_LENGTH) }, { frame.load(data) })
        ret(true)
        end()
    }
}

val Runtime.listFilter: Label get() {
    val zeros = allocZeros
    return routine("list_filter", 2) {
        begin(3)
        val l = param(0)
        val f = param(1)
        val data = param(2)
        val i = param(3)
        val kept = param(4)
        call(frame, zeros, 1, true) { field(l, LIST_LENGTH) }
        frame.store(data)
        forEachIndex("filter", l, i) {
            e.ifThen(label("keep").hint, {
                callClosure(frame, 1, true, failLanding(), { frame.load(f) }) { element(l) { frame.load(i) } }
            }) {
                element(l) { frame.load(i) }
                frame.load(data)
                frame.load(kept)
                e.op(Add)
                frame.heapStore()
                inc(kept)
            }
        }
        newHeader({ frame.load(kept) }, { field(l, LIST_LENGTH) }, { frame.load(data) })
        ret(true)
        end()
    }
}

val Runtime.listForEach: Label get() = routine("list_foreach", 2) {
    begin(1)
    val l = param(0)
    val f = param(1)
    val i = param(2)
    forEachIndex("each", l, i) {
        callClosure(frame, 1, false, failLanding(), { frame.load(f) }) { element(l) { frame.load(i) } }
    }
    ret(false)
    end()
}

/** `list.join(sep) { x => string }`: map to strings first, then concatenate. */
val Runtime.listJoin: Label get() {
    val map = listMap
    val append = appendChars
    return routine("list_join", 3) {
        begin(3)
        val l = param(0)
        val sep = param(1)
        val f = param(2)
        val strings = param(3)
        val header = param(4)
        val i = param(5)
        call(frame, map, 2, true, failLanding()) {
            frame.load(l)
            frame.load(f)
        }
        frame.store(strings)
        e.const(0)
        frame.alloc(1)
        frame.store(header)
        forEachIndex("join", strings, i) {
            e.ifThen(label("sep").hint, {
                frame.load(i)
                e.const(0)
                e.op(Greater)
            }) {
                call(frame, append, 1, false) { frame.load(sep) }
            }
            call(frame, append, 1, false) { element(strings) { frame.load(i) } }
        }
        frame.fillLength { frame.load(header) }
        frame.load(header)
        this@listJoin.internTop(frame)
        ret(true)
        end()
    }
}

/** Insertion sort in place, so equal elements keep their order. */
val Runtime.listSort: Label get() = routine("list_sort", 2) {
    begin(3)
    val l = param(0)
    val cmp = param(1)
    val i = param(2)
    val j = param(3)
    val x = param(4)
    e.const(1)
    frame.store(i)
    e.whileLoop(label("outer").hint, {
        field(l, LIST_LENGTH)
        frame.load(i)
        e.op(Greater)
    }) {
        element(l) { frame.load(i) }
        frame.store(x)
        frame.load(i)
        e.const(1)
        e.op(Subtract)
        frame.store(j)
        e.loop(label("inner").hint, {
            // j >= 0
            frame.load(j)
            e.const(-1)
            e.op(Greater)
        }) { exit ->
            callClosure(frame, 2, true, failLanding(), { frame.load(cmp) }) {
                element(l) { frame.load(j) }
                frame.load(x)
            }
            e.const(0)
            e.op(Greater)
            val shift = label("shift")
            e.branch(shift, exit)
            e.startBlock(shift)
            element(l) { frame.load(j) }
            setElement(l) {
                frame.load(j)
                e.const(1)
                e.op(Add)
            }
            inc(j, -1)
        }
        frame.load(x)
        setElement(l) {
            frame.load(j)
            e.const(1)
            e.op(Add)
        }
        inc(i)
    }
    ret(false)
    end()
}

/** An absent optional Int argument to a list routine; no valid index or count is this negative. */
const val ABSENT = -(1L shl 31)

private fun FunctionWriter.ld(slot: Int) = frame.load(slot)

private fun FunctionWriter.st(slot: Int, push: () -> Unit) {
    push()
    frame.store(slot)
}

private fun FunctionWriter.length(l: Int) = field(l, LIST_LENGTH)

/** Fails unless `lo <= index < length + extra`. */
private fun FunctionWriter.boundsCheck(list: Int, index: Int, extra: Int = 0) {
    val ok = label("inbounds")
    e.const(0)
    ld(index)
    e.op(Greater)
    length(list)
    e.const(extra)
    e.op(Add)
    ld(index)
    e.op(Greater)
    e.op(Not)
    e.op(Add)
    e.op(Not)
    e.branch(ok, failLanding(e.depth - 1))
    e.startBlock(ok)
}

/** If [slot] holds [ABSENT], replaces it with what [default] pushes. */
private fun FunctionWriter.defaultIfAbsent(slot: Int, default: () -> Unit) {
    e.ifThen(label("absent").hint, {
        ld(slot)
        e.const(ABSENT)
        e.op(Subtract)
        e.op(Not)
    }) {
        st(slot, default)
    }
}

val Runtime.listBuilderSet: Label get() = routine("lb_set", 3) {
    begin(0)
    val lb = param(0)
    val i = param(1)
    val v = param(2)
    boundsCheck(lb, i)
    ld(v)
    setElement(lb) { ld(i) }
    ret(false)
    end()
}

/** `lb.add(x, at)`: append, then shift everything from [at] up by one. */
val Runtime.listBuilderInsert: Label get() {
    val add = listBuilderAdd
    return routine("lb_insert", 3) {
        begin(1)
        val lb = param(0)
        val x = param(1)
        val at = param(2)
        val j = param(3)
        defaultIfAbsent(at) { length(lb) }
        boundsCheck(lb, at, extra = 1)
        call(frame, add, 2, false) {
            ld(lb)
            ld(x)
        }
        st(j) {
            length(lb)
            e.const(1)
            e.op(Subtract)
        }
        e.whileLoop(label("shift").hint, {
            ld(j)
            ld(at)
            e.op(Greater)
        }) {
            element(lb) {
                ld(j)
                e.const(1)
                e.op(Subtract)
            }
            setElement(lb) { ld(j) }
            inc(j, -1)
        }
        ld(x)
        setElement(lb) { ld(at) }
        ret(false)
        end()
    }
}

/** `lb.addAll(list, at)`, reading list's length first in case it is lb. */
val Runtime.listBuilderAddAll: Label get() {
    val insert = listBuilderInsert
    return routine("lb_add_all", 3) {
        begin(2)
        val lb = param(0)
        val list = param(1)
        val at = param(2)
        val i = param(3)
        val n = param(4)
        defaultIfAbsent(at) { length(lb) }
        st(n) { length(list) }
        e.whileLoop(label("each").hint, {
            ld(n)
            ld(i)
            e.op(Greater)
        }) {
            call(frame, insert, 3, false, failLanding()) {
                ld(lb)
                element(list) { ld(i) }
                ld(at)
                ld(i)
                e.op(Add)
            }
            inc(i)
        }
        ret(false)
        end()
    }
}

val Runtime.listBuilderRemoveLast: Label get() = routine("lb_remove_last", 1) {
    begin(0)
    val lb = param(0)
    val ok = label("nonempty")
    length(lb)
    e.branch(ok, failLanding(e.depth - 1))
    e.startBlock(ok)
    setField(lb, LIST_LENGTH) {
        length(lb)
        e.const(1)
        e.op(Subtract)
    }
    element(lb) { length(lb) }
    ret(true)
    end()
}

val Runtime.listBuilderReverse: Label get() = routine("lb_reverse", 1) {
    begin(3)
    val lb = param(0)
    val i = param(1)
    val j = param(2)
    val t = param(3)
    st(j) {
        length(lb)
        e.const(1)
        e.op(Subtract)
    }
    e.whileLoop(label("swap").hint, {
        ld(j)
        ld(i)
        e.op(Greater)
    }) {
        st(t) { element(lb) { ld(i) } }
        element(lb) { ld(j) }
        setElement(lb) { ld(i) }
        ld(t)
        setElement(lb) { ld(j) }
        inc(i)
        inc(j, -1)
    }
    ret(false)
}

/** A new list of elements [a, b), both clamped. */
val Runtime.listSlice: Label get() {
    val zeros = allocZeros
    return routine("list_slice", 3) {
        begin(2)
        val l = param(0)
        val a = param(1)
        val b = param(2)
        val data = param(3)
        val i = param(4)
        st(a) {
            ld(a)
            e.clampTop({ e.const(0) }, { length(l) })
        }
        st(b) {
            ld(b)
            e.clampTop({ ld(a) }, { length(l) })
        }
        st(data) {
            call(frame, zeros, 1, true) {
                ld(b)
                ld(a)
                e.op(Subtract)
            }
        }
        e.whileLoop(label("copy").hint, {
            ld(b)
            ld(a)
            ld(i)
            e.op(Add)
            e.op(Greater)
        }) {
            element(l) {
                ld(a)
                ld(i)
                e.op(Add)
            }
            ld(data)
            ld(i)
            e.op(Add)
            frame.heapStore()
            inc(i)
        }
        newHeader({
            ld(b)
            ld(a)
            e.op(Subtract)
        }, {
            ld(b)
            ld(a)
            e.op(Subtract)
        }, { ld(data) })
        ret(true)
    }
}

/**
 * `lb.splice(index, removeCount, newValues)`: removes and returns
 * removeCount elements from index, putting newValues there. index
 * defaults to 0 and removeCount to everything after index.
 */
val Runtime.listBuilderSplice: Label get() {
    val slice = listSlice
    val addAll = listBuilderAddAll
    return routine("lb_splice", 4) {
        begin(2)
        val lb = param(0)
        val index = param(1)
        val count = param(2)
        val values = param(3)
        val removed = param(4)
        val rest = param(5)
        defaultIfAbsent(index) { e.const(0) }
        st(index) {
            ld(index)
            e.clampTop({ e.const(0) }, { length(lb) })
        }
        defaultIfAbsent(count) {
            length(lb)
            ld(index)
            e.op(Subtract)
        }
        st(count) {
            ld(count)
            e.clampTop({ e.const(0) }, {
                length(lb)
                ld(index)
                e.op(Subtract)
            })
        }
        st(removed) {
            call(frame, slice, 3, true) {
                ld(lb)
                ld(index)
                ld(index)
                ld(count)
                e.op(Add)
            }
        }
        st(rest) {
            call(frame, slice, 3, true) {
                ld(lb)
                ld(index)
                ld(count)
                e.op(Add)
                length(lb)
            }
        }
        setField(lb, LIST_LENGTH) { ld(index) }
        e.ifThen(label("values").hint, {
            ld(values)
            e.const(PietTranslator.NULL)
            e.op(Subtract)
        }) {
            call(frame, addAll, 3, false, failLanding()) {
                ld(lb)
                ld(values)
                e.const(ABSENT)
            }
        }
        call(frame, addAll, 3, false, failLanding()) {
            ld(lb)
            ld(rest)
            e.const(ABSENT)
        }
        ld(removed)
        ret(true)
        end()
    }
}

val Runtime.listGetOr: Label get() = routine("list_get_or", 3) {
    begin(0)
    val l = param(0)
    val i = param(1)
    val fallback = param(2)
    e.ifElseValue(label("in").hint, {
        // !(0 > i) * (length > i)
        e.const(0)
        ld(i)
        e.op(Greater)
        e.op(Not)
        length(l)
        ld(i)
        e.op(Greater)
        e.op(Multiply)
    }, { element(l) { ld(i) } }) { ld(fallback) }
    ret(true)
}

/** reduceFrom(init, f); reduce is reduceFrom of the first element over the rest, and bubbles if empty. */
val Runtime.listReduceFrom: Label get() = routine("list_reduce_from", 4) {
    begin(0)
    val l = param(0)
    val acc = param(1)
    val f = param(2)
    val i = param(3)
    e.whileLoop(label("each").hint, {
        length(l)
        ld(i)
        e.op(Greater)
    }) {
        st(acc) {
            callClosure(frame, 2, true, failLanding(), { ld(f) }) {
                ld(acc)
                element(l) { ld(i) }
            }
        }
        inc(i)
    }
    ld(acc)
    ret(true)
    end()
}

/*
 * A Deque is a list with a fourth cell, the index of its first element:
 * add appends as a ListBuilder does, removeFirst advances the head.
 */
const val DEQUE_HEAD = 3

val Runtime.dequeRemoveFirst: Label get() = routine("deque_remove_first", 1) {
    begin(0)
    val d = param(0)
    val ok = label("nonempty")
    length(d)
    field(d, DEQUE_HEAD)
    e.op(Greater)
    e.branch(ok, failLanding(e.depth - 1))
    e.startBlock(ok)
    element(d) { field(d, DEQUE_HEAD) }
    setField(d, DEQUE_HEAD) {
        field(d, DEQUE_HEAD)
        e.const(1)
        e.op(Add)
    }
    ret(true)
    end()
}

/*
 * A DenseBitVector is a list of 0s and 1s that grows when set past its end;
 * reading past the end is false.
 */
val Runtime.bitVectorGet: Label get() = routine("bits_get", 2) {
    begin(0)
    val v = param(0)
    val i = param(1)
    e.ifElseValue(label("in").hint, {
        e.const(0)
        ld(i)
        e.op(Greater)
        e.op(Not)
        length(v)
        ld(i)
        e.op(Greater)
        e.op(PietOp.Multiply)
    }, { element(v) { ld(i) } }) { e.const(0) }
    ret(true)
}

val Runtime.bitVectorSet: Label get() {
    val add = listBuilderAdd
    return routine("bits_set", 3) {
        begin(0)
        val v = param(0)
        val i = param(1)
        val b = param(2)
        e.whileLoop(label("grow").hint, {
            length(v)
            ld(i)
            e.op(Greater)
            e.op(Not)
        }) {
            call(frame, add, 2, false) {
                ld(v)
                e.const(0)
            }
        }
        ld(b)
        setElement(v) { ld(i) }
        ret(false)
    }
}
