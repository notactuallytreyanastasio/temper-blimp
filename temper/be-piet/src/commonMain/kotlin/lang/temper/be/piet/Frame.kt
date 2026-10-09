package lang.temper.be.piet

import lang.temper.be.piet.PietOp.Add
import lang.temper.be.piet.PietOp.Duplicate
import lang.temper.be.piet.PietOp.Multiply
import lang.temper.be.piet.PietOp.Pop
import lang.temper.be.piet.PietOp.Roll
import lang.temper.be.piet.PietOp.Subtract

/**
 * The one stack, from the bottom:
 *
 * ```
 * h_0 .. h_{N-1}   the heap; an address is an index from the bottom
 * N                how many heap cells there are
 * g_0 .. g_{G-1}   module-level variables
 * frames           the oldest caller's first
 * ```
 *
 * A function's frame is `ret, fail, D, params..., locals..., temporaries...`.
 * ret is the row to return to and fail the row to return to instead when the
 * call bubbles. D counts the values strictly between N and the frame:
 * the globals and every caller's frame. Nothing in Piet reports the stack's
 * height, so D is how anything below the frame is found: N is `depth + D + 1`
 * from the top, where depth is the frame's own height, which the [Emitter]
 * always knows.
 *
 * The heap grows by inserting cells just below N, which moves nothing that
 * is addressed: heap addresses count from the bottom, and D and every depth
 * count from the top.
 *
 * The module's top-level code runs in a frame with no ret or D slot; its D is
 * the constant G.
 */
class Frame(val e: Emitter, private val constantD: Int?) {
    val program: AsmProgram get() = e.program

    /** Pushes D + [offset]. */
    fun pushD(offset: Int) {
        if (constantD != null) {
            e.const(constantD + offset)
        } else {
            e.pick(e.depth - D_SLOT)
            if (offset != 0) {
                e.const(offset)
                e.op(Add)
            }
        }
    }

    fun load(slot: Int) = e.pick(e.depth - slot)

    /** Pops the top into [slot]. */
    fun store(slot: Int) = e.poke(e.depth - 1 - slot)

    fun loadGlobal(i: Int) {
        if (constantD != null) {
            e.pick(e.depth + constantD - i)
        } else {
            pushD(e.depth - i)
            e.pickDynamic()
        }
    }

    /** Pops the top into global [i]. */
    fun storeGlobal(i: Int) {
        if (constantD != null) {
            e.poke(e.depth - 1 + constantD - i)
        } else {
            pushD(e.depth - 1 - i)
            e.pokeDynamic()
        }
    }

    /** Pushes N, the heap's size, which is also the address the next cell gets. */
    fun loadHeapSize() {
        if (constantD != null) {
            e.pick(e.depth + constantD + 1)
        } else {
            pushD(e.depth + 1)
            e.pickDynamic()
        }
    }

    /** `[a] -> [h(a + offset)]` */
    fun heapLoad(offset: Int = 0) {
        val d0 = e.depth
        loadHeapSize()
        e.op(Subtract)
        e.const(-1)
        e.op(Multiply)
        pushD(d0 - offset)
        e.op(Add)
        e.pickDynamic()
    }

    /** `[v, a] -> []`, storing v at h(a + offset). */
    fun heapStore(offset: Int = 0) {
        val d0 = e.depth
        loadHeapSize()
        e.op(Subtract)
        e.const(-1)
        e.op(Multiply)
        pushD(d0 - 1 - offset)
        e.op(Add)
        e.pokeDynamic()
    }

    /**
     * Moves the [count] values on top into the heap, as consecutive cells in
     * the order they were pushed, and pushes the address of the first.
     */
    fun alloc(count: Int) {
        require(count >= 1)
        val below = e.depth - count
        // Roll the cells under everything down to and including N.
        pushD(e.depth + 1)
        e.const(count)
        e.op(Roll)
        e.adjust(-count)
        check(e.depth == below)
        // N grows by count; the old N is the address.
        loadHeapSize()
        e.op(Duplicate)
        e.const(count)
        e.op(Add)
        pushD(below + 2)
        e.pokeDynamic()
    }

    /** Like [alloc] of one cell, dropping the address. */
    fun append() {
        alloc(1)
        e.op(Pop)
    }

    companion object {
        const val RET_SLOT = 0
        const val FAIL_SLOT = 1
        const val D_SLOT = 2
        const val FIRST_PARAM_SLOT = 3
    }
}

/**
 * A function in the calling convention [Frame] describes. The caller pushes
 * the return row, the failure row, the callee's D and the arguments, and
 * jumps to [entry].
 */
class FunctionWriter(
    program: AsmProgram,
    val name: String,
    val paramCount: Int,
    val entry: Label = Label(name),
) {
    val e = Emitter(program)
    val frame = Frame(e, null)

    fun begin(localCount: Int) {
        e.startBlock(entry, Frame.FIRST_PARAM_SLOT + paramCount)
        repeat(localCount) { e.const(0) }
    }

    fun param(i: Int) = Frame.FIRST_PARAM_SLOT + i

    /** Returns, with the result on top if [withValue]. */
    fun ret(withValue: Boolean) {
        if (!e.reachable) return
        val frameSize = e.depth - (if (withValue) 1 else 0)
        if (withValue) {
            // Bury the result under the frame, then drop all of it but ret.
            e.const(frameSize + 1)
            e.const(1)
            e.op(Roll)
        }
        repeat(frameSize - 1) { e.op(Pop) }
        e.jumpToTop()
    }

    /** Bubbles: drops the whole frame and goes to the failure row. */
    fun fail() {
        if (!e.reachable) return
        frame.load(Frame.FAIL_SLOT)
        e.dropUnder(e.depth - 1)
        e.jumpToTop()
    }

    fun label(hint: String) = Label("$name.$hint${labelCount++}")

    private var labelCount = 0
    private val failLandings = mutableListOf<Pair<Label, Int>>()

    /** A row that fails this function from the current depth. Written by [end]. */
    fun failLanding(depth: Int = e.depth): Label {
        val landing = label("fail")
        failLandings.add(landing to depth)
        return landing
    }

    /** Writes the failure landings asked for. */
    fun end() {
        check(!e.reachable) { "$name falls off its end" }
        for ((landing, depth) in failLandings) {
            e.startBlock(landing, depth)
            fail()
        }
        failLandings.clear()
    }
}

/**
 * Calls the function value [loadFn] pushes, with [argCount] arguments from
 * [args]: the value goes in as the first argument, and its first cell is the
 * row to go to.
 */
fun callClosure(
    frame: Frame,
    argCount: Int,
    returnsValue: Boolean,
    onFail: Label?,
    loadFn: () -> Unit,
    args: () -> Unit,
) {
    val e = frame.e
    if (!e.reachable) return
    val d0 = e.depth
    val back = Label("closure.ret")
    e.pushLabel(back)
    if (onFail != null) {
        e.expect(onFail, d0)
        e.pushLabel(onFail)
    } else {
        e.pushLabel(frame.program.halt)
    }
    frame.pushD(d0)
    loadFn()
    args()
    check(e.depth == d0 + 4 + argCount)
    e.pick(argCount + 1)
    frame.heapLoad()
    e.jumpToTop()
    e.startBlock(back, d0 + if (returnsValue) 1 else 0)
}

/**
 * Calls [target] from the frame [frame] belongs to. [args] pushes exactly
 * [argCount] values; afterwards the result, if [returnsValue], is on top.
 *
 * If the callee bubbles, control goes to [onFail] instead, with the frame as
 * it was before the call. A call that cannot bubble passes the halt row.
 */
fun call(
    frame: Frame,
    target: Label,
    argCount: Int,
    returnsValue: Boolean,
    onFail: Label? = null,
    args: () -> Unit,
) {
    val e = frame.e
    if (!e.reachable) return
    val d0 = e.depth
    val back = Label("$target.ret")
    e.pushLabel(back)
    if (onFail != null) {
        e.expect(onFail, d0)
        e.pushLabel(onFail)
    } else {
        e.pushLabel(frame.program.halt)
    }
    frame.pushD(d0)
    args()
    check(e.depth == d0 + 3 + argCount) { "args to $target pushed ${e.depth - d0 - 3}, not $argCount" }
    e.jumpAway(target)
    e.startBlock(back, d0 + if (returnsValue) 1 else 0)
}

/** Structured control flow for hand-written blocks. */
fun Emitter.ifThen(name: String, cond: () -> Unit, then: () -> Unit) = ifElse(name, cond, then) {}

fun Emitter.ifElse(name: String, cond: () -> Unit, then: () -> Unit, otherwise: () -> Unit) {
    cond()
    val yes = Label("$name.then")
    val no = Label("$name.else")
    val join = Label("$name.join")
    val d0 = depth - 1
    branch(yes, no)
    startBlock(yes, d0)
    then()
    jump(join)
    startBlock(no, d0)
    otherwise()
    jump(join)
    startBlock(join, d0)
}

fun Emitter.whileLoop(name: String, cond: () -> Unit, body: () -> Unit) {
    val head = Label("$name.head")
    val bodyLabel = Label("$name.body")
    val exit = Label("$name.exit")
    val d0 = depth
    startBlock(head, d0)
    cond()
    branch(bodyLabel, exit)
    startBlock(bodyLabel, d0)
    body()
    jump(head)
    startBlock(exit, d0)
}

/** `[a, b] -> [b, a]` */
fun Emitter.swap() {
    const(2)
    const(1)
    op(Roll)
}

/** A loop whose [body] may jump to the exit label it is given. */
fun Emitter.loop(name: String, cond: () -> Unit, body: (exit: Label) -> Unit) {
    val head = Label("$name.head")
    val bodyLabel = Label("$name.body")
    val exit = Label("$name.exit")
    val d0 = depth
    startBlock(head, d0)
    cond()
    branch(bodyLabel, exit)
    startBlock(bodyLabel, d0)
    body(exit)
    jump(head)
    startBlock(exit, d0)
}
