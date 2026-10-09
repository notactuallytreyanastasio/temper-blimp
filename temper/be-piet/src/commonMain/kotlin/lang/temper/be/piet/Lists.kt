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
