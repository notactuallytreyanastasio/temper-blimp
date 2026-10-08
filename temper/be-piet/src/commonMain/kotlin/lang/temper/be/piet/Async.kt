package lang.temper.be.piet

/*
 * Async without threads. Temper turns each async block into a generator
 * whose every await is a yield, so a scheduler only has to decide which
 * generator to step next. Its run queue is a Deque of generators in a
 * global that the translator reserves; main drains it after module code,
 * stepping each generator once per time it was queued.
 *
 * A promise, and the builder for it, are one block: `state, value,
 * waiters`, where the state is pending, kept or broken, and the waiters
 * are a ListBuilder of generators to queue when it settles. Settling
 * again does nothing, so the first completion wins.
 */
const val PROMISE_STATE = 0
const val PROMISE_VALUE = 1
const val PROMISE_WAITERS = 2

const val PROMISE_PENDING = 0
const val PROMISE_KEPT = 1
const val PROMISE_BROKEN = 2

/** `[] -> [promise]`, pending with no waiters. */
fun Frame.newPromise() {
    e.const(PROMISE_PENDING)
    e.const(0)
    // An empty ListBuilder.
    e.const(0)
    e.const(0)
    e.const(PietTranslator.NULL)
    alloc(3)
    alloc(3)
}

/** `[] -> [queue]`, an empty Deque. */
fun Frame.newRunQueue() {
    e.const(0)
    e.const(0)
    e.const(PietTranslator.NULL)
    e.const(0)
    alloc(4)
}

/** `[generator] -> []`: queue it to be stepped. */
val Runtime.enqueue: Label get() {
    val add = listBuilderAdd
    return routine("enqueue", 1) {
        begin(0)
        call(frame, add, 2, false) {
            frame.loadGlobal(runQueueGlobal)
            frame.load(param(0))
        }
        ret(false)
    }
}

/** `[promise, state, value] -> []`: keeps or breaks a pending promise and queues its waiters. */
val Runtime.promiseSettle: Label get() {
    val enqueue = enqueue
    return routine("promise_settle", 3) {
        begin(2)
        val p = param(0)
        val waiters = param(3)
        val i = param(4)
        e.ifThen(label("pending").hint, {
            field(p, PROMISE_STATE)
            e.op(PietOp.Not)
        }) {
            setField(p, PROMISE_STATE) { frame.load(param(1)) }
            setField(p, PROMISE_VALUE) { frame.load(param(2)) }
            field(p, PROMISE_WAITERS)
            frame.store(waiters)
            forEachIndex("wake", waiters, i) {
                call(frame, enqueue, 1, false) { element(waiters) { frame.load(i) } }
            }
        }
        ret(false)
    }
}

/** `[promise, generator] -> []`: step the generator once the promise settles, or soon if it has. */
val Runtime.awakeUpon: Label get() {
    val enqueue = enqueue
    val add = listBuilderAdd
    return routine("awake_upon", 2) {
        begin(0)
        val p = param(0)
        e.ifElse(label("pending").hint, {
            field(p, PROMISE_STATE)
            e.op(PietOp.Not)
        }, {
            call(frame, add, 2, false) {
                field(p, PROMISE_WAITERS)
                frame.load(param(1))
            }
        }) {
            call(frame, enqueue, 1, false) { frame.load(param(1)) }
        }
        ret(false)
    }
}

/** `[promise] -> [value]`, bubbling if it was broken. Only asked of a settled promise. */
val Runtime.promiseResult: Label get() = routine("promise_result", 1) {
    begin(0)
    val p = param(0)
    val kept = label("kept")
    field(p, PROMISE_STATE)
    e.const(PROMISE_KEPT)
    e.op(PietOp.Subtract)
    e.op(PietOp.Not)
    e.branch(kept, failLanding(e.depth - 1))
    e.startBlock(kept)
    field(p, PROMISE_VALUE)
    ret(true)
    end()
}
