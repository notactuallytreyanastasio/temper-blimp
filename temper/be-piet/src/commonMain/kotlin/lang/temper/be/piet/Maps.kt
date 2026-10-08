package lang.temper.be.piet

import lang.temper.be.piet.PietOp.Add
import lang.temper.be.piet.PietOp.Greater
import lang.temper.be.piet.PietOp.Not
import lang.temper.be.piet.PietOp.Subtract

/*
 * Maps. Map and MapBuilder are lists of Pair objects in insertion order,
 * looked up by linear search. A Pair is `0, key, value`: the 0 keeps its
 * key and value at the same offsets as fields of a one-field-past-id
 * object, and no class has id 0.
 *
 * Keys compare by value: Ints and Booleans as themselves, Strings by
 * content. Which one is decided at each call site from the key's static
 * type and passed as [stringKeys].
 */

const val PAIR_KEY = 1
const val PAIR_VALUE = 2

private fun FunctionWriter.ld(slot: Int) = frame.load(slot)

private fun FunctionWriter.st(slot: Int, push: () -> Unit) {
    push()
    frame.store(slot)
}

private fun FunctionWriter.inc(slot: Int) = st(slot) {
    ld(slot)
    e.const(1)
    e.op(Add)
}

private fun FunctionWriter.entry(map: Int, index: () -> Unit) {
    ld(map)
    frame.heapLoad(LIST_DATA)
    index()
    e.op(Add)
    frame.heapLoad()
}

private fun FunctionWriter.size(map: Int) {
    ld(map)
    frame.heapLoad(LIST_LENGTH)
}

/** The index of [key] in the map, or -1. */
fun Runtime.mapFind(stringKeys: Boolean): Label {
    val compare = stringCompare
    return routine("map_find_${if (stringKeys) "s" else "i"}", 2) {
        begin(2)
        val m = param(0)
        val k = param(1)
        val i = param(2)
        val found = param(3)
        st(found) { e.const(-1) }
        e.loop(label("scan").hint, {
            size(m)
            ld(i)
            e.op(Greater)
        }) { exit ->
            val hit = label("hit")
            val miss = label("miss")
            if (stringKeys) {
                call(frame, compare, 2, true) {
                    entry(m) { ld(i) }
                    frame.heapLoad(PAIR_KEY)
                    ld(k)
                }
            } else {
                entry(m) { ld(i) }
                frame.heapLoad(PAIR_KEY)
                ld(k)
                e.op(Subtract)
            }
            e.op(Not)
            e.branch(hit, miss)
            e.startBlock(hit)
            st(found) { ld(i) }
            e.jump(exit)
            e.startBlock(miss)
            inc(i)
        }
        ld(found)
        ret(true)
    }
}

/** `map[key]`, bubbling when absent, or getOr's fallback when [orElse]. */
fun Runtime.mapGet(stringKeys: Boolean, orElse: Boolean): Label {
    val find = mapFind(stringKeys)
    return routine("map_get_${if (stringKeys) "s" else "i"}${if (orElse) "_or" else ""}", if (orElse) 3 else 2) {
        begin(1)
        val m = param(0)
        val k = param(1)
        val at = param(if (orElse) 3 else 2)
        st(at) {
            call(frame, find, 2, true) {
                ld(m)
                ld(k)
            }
        }
        if (orElse) {
            e.ifElseValue(label("found").hint, {
                ld(at)
                e.const(-1)
                e.op(Greater)
            }, {
                entry(m) { ld(at) }
                frame.heapLoad(PAIR_VALUE)
            }) { ld(param(2)) }
        } else {
            val ok = label("found")
            ld(at)
            e.const(-1)
            e.op(Greater)
            e.branch(ok, failLanding(e.depth - 1))
            e.startBlock(ok)
            entry(m) { ld(at) }
            frame.heapLoad(PAIR_VALUE)
        }
        ret(true)
        end()
    }
}

fun Runtime.mapHas(stringKeys: Boolean): Label {
    val find = mapFind(stringKeys)
    return routine("map_has_${if (stringKeys) "s" else "i"}", 2) {
        begin(0)
        call(frame, find, 2, true) {
            ld(param(0))
            ld(param(1))
        }
        e.const(-1)
        e.op(Greater)
        ret(true)
    }
}

/**
 * `builder[key] = value`: a new Pair, in the old one's place or appended.
 * Not the old Pair's value cell overwritten: toMap and toMapBuilder copy
 * the list of entries, not the Pairs, so a copy would see the change.
 */
fun Runtime.mapSet(stringKeys: Boolean): Label {
    val find = mapFind(stringKeys)
    val add = listBuilderAdd
    return routine("map_set_${if (stringKeys) "s" else "i"}", 3) {
        begin(1)
        val m = param(0)
        val k = param(1)
        val v = param(2)
        val at = param(3)
        st(at) {
            call(frame, find, 2, true) {
                ld(m)
                ld(k)
            }
        }
        e.ifElse(label("found").hint, {
            ld(at)
            e.const(-1)
            e.op(Greater)
        }, {
            e.const(0)
            ld(k)
            ld(v)
            frame.alloc(3)
            ld(m)
            frame.heapLoad(LIST_DATA)
            ld(at)
            e.op(Add)
            frame.heapStore()
        }) {
            call(frame, add, 2, false) {
                ld(m)
                e.const(0)
                ld(k)
                ld(v)
                frame.alloc(3)
            }
        }
        ret(false)
    }
}

/** A map built from a list of pairs; a later pair for the same key wins. */
fun Runtime.mapFromPairs(stringKeys: Boolean): Label {
    val set = mapSet(stringKeys)
    return routine("map_new_${if (stringKeys) "s" else "i"}", 1) {
        begin(2)
        val pairs = param(0)
        val m = param(1)
        val i = param(2)
        st(m) {
            e.const(0)
            e.const(0)
            e.const(PietTranslator.NULL)
            frame.alloc(3)
        }
        e.whileLoop(label("each").hint, {
            size(pairs)
            ld(i)
            e.op(Greater)
        }) {
            call(frame, set, 3, false) {
                ld(m)
                entry(pairs) { ld(i) }
                frame.heapLoad(PAIR_KEY)
                entry(pairs) { ld(i) }
                frame.heapLoad(PAIR_VALUE)
            }
            inc(i)
        }
        ld(m)
        ret(true)
    }
}

/** `builder.remove(key)`: the removed value, bubbling when absent. */
fun Runtime.mapRemove(stringKeys: Boolean): Label {
    val find = mapFind(stringKeys)
    val splice = listBuilderSplice
    return routine("map_remove_${if (stringKeys) "s" else "i"}", 2) {
        begin(2)
        val m = param(0)
        val k = param(1)
        val at = param(2)
        val v = param(3)
        st(at) {
            call(frame, find, 2, true) {
                ld(m)
                ld(k)
            }
        }
        val ok = label("found")
        ld(at)
        e.const(-1)
        e.op(Greater)
        e.branch(ok, failLanding(e.depth - 1))
        e.startBlock(ok)
        st(v) {
            entry(m) { ld(at) }
            frame.heapLoad(PAIR_VALUE)
        }
        call(frame, splice, 4, true) {
            ld(m)
            ld(at)
            e.const(1)
            e.const(PietTranslator.NULL)
        }
        e.op(PietOp.Pop)
        ld(v)
        ret(true)
        end()
    }
}

/**
 * A new list made from each entry: its key, its value, the Pair itself, or
 * what a function of (key, value) returns.
 */
fun Runtime.mapProject(what: String): Label {
    val zeros = allocZeros
    return routine("map_$what", if (what == "with") 2 else 1) {
        begin(2)
        val m = param(0)
        val data = param(if (what == "with") 2 else 1)
        val i = param(if (what == "with") 3 else 2)
        st(data) { call(frame, zeros, 1, true) { size(m) } }
        e.whileLoop(label("each").hint, {
            size(m)
            ld(i)
            e.op(Greater)
        }) {
            when (what) {
                "keys" -> {
                    entry(m) { ld(i) }
                    frame.heapLoad(PAIR_KEY)
                }
                "values" -> {
                    entry(m) { ld(i) }
                    frame.heapLoad(PAIR_VALUE)
                }
                "pairs" -> entry(m) { ld(i) }
                else -> callClosure(frame, 2, true, failLanding(), { ld(param(1)) }) {
                    entry(m) { ld(i) }
                    frame.heapLoad(PAIR_KEY)
                    entry(m) { ld(i) }
                    frame.heapLoad(PAIR_VALUE)
                }
            }
            ld(data)
            ld(i)
            e.op(Add)
            frame.heapStore()
            inc(i)
        }
        size(m)
        size(m)
        ld(data)
        frame.alloc(3)
        ret(true)
        end()
    }
}

val Runtime.mapForEach: Label get() = routine("map_foreach", 2) {
    begin(1)
    val m = param(0)
    val f = param(1)
    val i = param(2)
    e.whileLoop(label("each").hint, {
        size(m)
        ld(i)
        e.op(Greater)
    }) {
        callClosure(frame, 2, false, failLanding(), { ld(f) }) {
            entry(m) { ld(i) }
            frame.heapLoad(PAIR_KEY)
            entry(m) { ld(i) }
            frame.heapLoad(PAIR_VALUE)
        }
        inc(i)
    }
    ret(false)
    end()
}
