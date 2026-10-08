package lang.temper.be.piet

import lang.temper.be.piet.PietOp.Add
import lang.temper.be.piet.PietOp.Duplicate
import lang.temper.be.piet.PietOp.Multiply
import lang.temper.be.piet.PietOp.Not
import lang.temper.be.piet.PietOp.Pop
import lang.temper.be.piet.PietOp.Roll
import lang.temper.be.piet.PietOp.Subtract

/** A jump target. Linking turns each into the index of its row. */
class Label(val hint: String) {
    override fun toString() = hint
}

sealed interface AsmInstr {
    data class I(val instr: PietInstr) : AsmInstr {
        override fun toString() = "$instr"
    }

    /** Pushes the row index of [label], known only once every block is placed. */
    data class PushLabel(val label: Label) : AsmInstr {
        override fun toString() = "&$label"
    }
}

/**
 * A straight run of instructions that ends with the next row's index on top.
 * One block is one row of the [DispatchLayout].
 */
class AsmBlock(val label: Label, val code: List<AsmInstr>) {
    override fun toString() = "$label: ${code.joinToString(" ")}"
}

class AsmProgram {
    val blocks = mutableListOf<AsmBlock>()

    /** A label whose row index is past every row, so jumping to it halts. */
    val halt = Label("halt")

    fun link(entry: Label): DispatchLayout {
        val index = mutableMapOf<Label, Int>()
        blocks.forEachIndexed { i, b -> check(index.put(b.label, i) == null) { "duplicate ${b.label}" } }
        index[halt] = blocks.size
        fun indexOf(label: Label) = index[label] ?: error("unplaced label $label")
        val rows = blocks.map { block ->
            block.code.map { instr ->
                when (instr) {
                    is AsmInstr.I -> instr.instr
                    is AsmInstr.PushLabel -> PietInstr.Const(indexOf(instr.label).toLong())
                }
            }
        }
        return DispatchLayout(rows, indexOf(entry))
    }

    override fun toString() = blocks.joinToString("\n")
}

/**
 * Writes blocks while keeping count of [depth]: how many values the current
 * frame has on the stack. Every access to a local is a roll whose depth is
 * computed from this count, so the count has to be exact, and a jump records
 * the depth its target must start with so that every way in agrees.
 *
 * After a jump nothing is reachable until the next [startBlock]; whatever is
 * emitted in between is dropped.
 */
class Emitter(val program: AsmProgram) {
    private var label: Label? = null
    private var code = mutableListOf<AsmInstr>()
    private val entryDepths = mutableMapOf<Label, Int>()

    var depth: Int = 0

    val reachable: Boolean get() = label != null

    /** Whether anything jumps to [target] yet. */
    fun knows(target: Label) = target in entryDepths

    fun startBlock(next: Label, depth: Int? = null) {
        if (reachable) jump(next)
        val known = entryDepths[next]
        this.depth = when {
            depth == null -> known ?: error("no depth known for $next")
            known != null -> {
                check(known == depth) { "$next entered at depth $known and $depth" }
                depth
            }
            else -> depth
        }
        entryDepths[next] = this.depth
        label = next
        code = mutableListOf()
    }

    private fun endBlock() {
        program.blocks.add(AsmBlock(label!!, code))
        label = null
    }

    fun op(op: PietOp) {
        require(op != PietOp.Pointer && op != PietOp.Switch) { "$op would steer off the row" }
        if (!reachable) return
        code.add(AsmInstr.I(PietInstr.Op(op)))
        depth += STACK_EFFECT.getValue(op)
    }

    fun ops(vararg ops: PietOp) = ops.forEach { op(it) }

    fun const(n: Long) {
        if (!reachable) return
        code.add(AsmInstr.I(PietInstr.Const(n)))
        depth += 1
    }

    fun const(n: Int) = const(n.toLong())

    fun pushLabel(target: Label) {
        if (!reachable) return
        code.add(AsmInstr.PushLabel(target))
        depth += 1
    }

    /** Ends the block, going to [target] with the stack as it is. */
    fun jump(target: Label) {
        if (!reachable) return
        expect(target, depth)
        pushLabel(target)
        endBlock()
    }

    /** Ends the block, going to [target], which belongs to another frame. */
    fun jumpAway(target: Label) {
        if (!reachable) return
        pushLabel(target)
        endBlock()
    }

    /** Ends the block, going to the row whose index is already on top. */
    fun jumpToTop() {
        if (!reachable) return
        endBlock()
    }

    /**
     * Pops a value and ends the block, going to [ifTrue] if it is not 0.
     *
     * The row is `ifFalse + c * (ifTrue - ifFalse)`, which is right only for
     * c of exactly 0 or 1; any other c is a jump to some unrelated row, and
     * nothing would say so. So c is first made 0 or 1 with `not not`, which
     * costs two codels and means no caller has to get this right.
     */
    fun branch(ifTrue: Label, ifFalse: Label) {
        if (!reachable) return
        depth -= 1
        expect(ifTrue, depth)
        expect(ifFalse, depth)
        depth += 1
        op(Not)
        op(Not)
        // ifFalse + c * (ifTrue - ifFalse)
        pushLabel(ifTrue)
        pushLabel(ifFalse)
        op(Subtract)
        op(Multiply)
        pushLabel(ifFalse)
        op(Add)
        endBlock()
    }

    fun expect(target: Label, depth: Int) {
        val known = entryDepths.getOrPut(target) { depth }
        check(known == depth) { "$target entered at depth $known and $depth" }
    }

    /** Tells the count about values that left or joined the frame some other way. */
    fun adjust(delta: Int) {
        if (reachable) depth += delta
    }

    /** Copies the value [k] from the top (1 = top) to the top. */
    fun pick(k: Int) {
        require(k >= 1) { "pick($k)" }
        if (k == 1) {
            op(Duplicate)
            return
        }
        const(k)
        const(-1)
        op(Roll)
        op(Duplicate)
        const(k + 1)
        const(1)
        op(Roll)
    }

    /** Pops the top and stores it over the value [k] below it (1 = just below). */
    fun poke(k: Int) {
        require(k >= 1) { "poke($k)" }
        const(k + 1)
        const(-1)
        op(Roll)
        op(Pop)
        if (k > 1) {
            const(k)
            const(1)
            op(Roll)
        }
    }

    /**
     * Like [pick] with k popped from the top: `[R, k] -> [R, x]` where x is k
     * from the top of R. The count sees the net effect only.
     */
    fun pickDynamic() {
        // [R, k] -> [R, k, k+1] -> raise x over k: [R\x, k, x]
        ops(Duplicate)
        const(1)
        ops(Add)
        const(-1)
        ops(Roll, Duplicate)
        // [R\x, k, x, x] -> raise k: [R\x, x, x, k] -> bury one x under k-1 of R
        const(3)
        const(-1)
        ops(Roll)
        const(1)
        ops(Add)
        const(1)
        ops(Roll)
    }

    /** Like [poke] with k popped from the top: `[R, v, k] -> [R']` with v stored k from the top of R. */
    fun pokeDynamic() {
        // [R, v, k] -> raise x from k+2: [R\x, v, k, x] -> drop it
        ops(Duplicate)
        const(2)
        ops(Add)
        const(-1)
        ops(Roll, Pop)
        // [R\x, v, k] -> bury v under k-1
        const(1)
        ops(Roll)
    }

    companion object {
        val STACK_EFFECT = mapOf(
            PietOp.Push to 1, PietOp.Pop to -1,
            PietOp.Add to -1, PietOp.Subtract to -1, PietOp.Multiply to -1,
            PietOp.Divide to -1, PietOp.Mod to -1, PietOp.Not to 0, PietOp.Greater to -1,
            PietOp.Pointer to -1, PietOp.Switch to -1,
            PietOp.Duplicate to 1, PietOp.Roll to -2,
            PietOp.InNumber to 1, PietOp.InChar to 1,
            PietOp.OutNumber to -1, PietOp.OutChar to -1,
        )
    }
}
