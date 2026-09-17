@file:lang.temper.common.Generated("OutputGrammarCodeGenerator")
@file:Suppress("ktlint", "unused", "CascadeIf", "MagicNumber", "MemberNameEqualsClassName", "MemberVisibilityCanBePrivate")

package lang.temper.be.blimp
import lang.temper.ast.ChildMemberRelationships
import lang.temper.ast.OutData
import lang.temper.ast.OutTree
import lang.temper.ast.deepCopy
import lang.temper.be.BaseOutData
import lang.temper.be.BaseOutTree
import lang.temper.format.CodeFormattingTemplate
import lang.temper.format.FormattableTreeGroup
import lang.temper.format.FormattingHints
import lang.temper.format.IndexableFormattableTreeElement
import lang.temper.format.OutputToken
import lang.temper.format.OutputTokenType
import lang.temper.format.TokenAssociation
import lang.temper.format.TokenSink
import lang.temper.log.Position
import lang.temper.name.OutName
import lang.temper.name.name

object Blimp {
    sealed interface Tree : OutTree<Tree> {
        override fun formattingHints(): FormattingHints = BlimpFormattingHints.getInstance()
        override val operatorDefinition: BlimpOperatorDefinition?
        override fun deepCopy(): Tree
    }
    sealed class BaseTree(
        pos: Position,
    ) : BaseOutTree<Tree>(pos), Tree
    sealed interface Data : OutData<Data> {
        override fun formattingHints(): FormattingHints = BlimpFormattingHints.getInstance()
        override val operatorDefinition: BlimpOperatorDefinition?
    }
    sealed class BaseData : BaseOutData<Data>(), Data

    sealed interface Program : Tree {
        override fun deepCopy(): Program
    }

    class SourceFile(
        pos: Position,
        items: Iterable<Item> = listOf(),
    ) : BaseTree(pos), Program {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate0
        override val formatElementCount
            get() = 1
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> FormattableTreeGroup(this.items)
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private val _items: MutableList<Item> = mutableListOf()
        var items: List<Item>
            get() = _items
            set(newValue) { updateTreeConnections(_items, newValue) }
        override fun deepCopy(): SourceFile {
            return SourceFile(pos, items = this.items.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is SourceFile && this.items == other.items
        }
        override fun hashCode(): Int {
            return items.hashCode()
        }
        init {
            updateTreeConnections(this._items, items)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as SourceFile).items },
            )
        }
    }

    sealed interface Item : Tree {
        override fun deepCopy(): Item
    }

    /**
     * `actor Shop.Checkout do ... end`
     *
     * Blimp has no module system; a dotted actor name is both the namespace and
     * the supervision edge, so Temper's module paths land in [name].
     */
    class ActorDecl(
        pos: Position,
        doc: Comment? = null,
        name: Name,
        states: Iterable<StateDecl> = listOf(),
        handlers: Iterable<Handler> = listOf(),
    ) : BaseTree(pos), Item {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate1
        override val formatElementCount
            get() = 4
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.doc ?: FormattableTreeGroup.empty
                1 -> this.name
                2 -> FormattableTreeGroup(this.states)
                3 -> FormattableTreeGroup(this.handlers)
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _doc: Comment?
        var doc: Comment?
            get() = _doc
            set(newValue) { _doc = updateTreeConnection(_doc, newValue) }
        private var _name: Name
        var name: Name
            get() = _name
            set(newValue) { _name = updateTreeConnection(_name, newValue) }
        private val _states: MutableList<StateDecl> = mutableListOf()
        var states: List<StateDecl>
            get() = _states
            set(newValue) { updateTreeConnections(_states, newValue) }
        private val _handlers: MutableList<Handler> = mutableListOf()
        var handlers: List<Handler>
            get() = _handlers
            set(newValue) { updateTreeConnections(_handlers, newValue) }
        override fun deepCopy(): ActorDecl {
            return ActorDecl(pos, doc = this.doc?.deepCopy(), name = this.name.deepCopy(), states = this.states.deepCopy(), handlers = this.handlers.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is ActorDecl && this.doc == other.doc && this.name == other.name && this.states == other.states && this.handlers == other.handlers
        }
        override fun hashCode(): Int {
            var hc = doc.hashCode()
            hc = 31 * hc + name.hashCode()
            hc = 31 * hc + states.hashCode()
            hc = 31 * hc + handlers.hashCode()
            return hc
        }
        init {
            this._doc = updateTreeConnection(null, doc)
            this._name = updateTreeConnection(null, name)
            updateTreeConnections(this._states, states)
            updateTreeConnections(this._handlers, handlers)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as ActorDecl).doc },
                { n -> (n as ActorDecl).name },
                { n -> (n as ActorDecl).states },
                { n -> (n as ActorDecl).handlers },
            )
        }
    }

    sealed interface Statement : Tree, Item {
        override fun deepCopy(): Statement
    }

    class Comment(
        pos: Position,
        var text: String,
    ) : BaseTree(pos), Item, Statement {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override fun renderTo(
            tokenSink: TokenSink,
        ) {
            tokenSink.comment(blimpCommentText(text))
        }
        override val codeFormattingTemplate: CodeFormattingTemplate?
            get() = null
        override fun deepCopy(): Comment {
            return Comment(pos, text = this.text)
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Comment && this.text == other.text
        }
        override fun hashCode(): Int {
            return text.hashCode()
        }
        companion object {
            private val cmr = ChildMemberRelationships()
        }
    }

    /**
     * `def name(a: Int) -> Int do ... end`
     *
     * Reserved for the support layer and for lowered loops; user-facing Temper
     * declarations become actors.
     */
    class DefDecl(
        pos: Position,
        doc: Comment? = null,
        id: Id,
        params: Iterable<Param> = listOf(),
        returnType: TypeRef? = null,
        body: Block,
    ) : BaseTree(pos), Item {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() =
                if (returnType != null) {
                    sharedCodeFormattingTemplate2
                } else {
                    sharedCodeFormattingTemplate3
                }
        override val formatElementCount
            get() = 5
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.doc ?: FormattableTreeGroup.empty
                1 -> this.id
                2 -> FormattableTreeGroup(this.params)
                3 -> this.returnType ?: FormattableTreeGroup.empty
                4 -> this.body
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _doc: Comment?
        var doc: Comment?
            get() = _doc
            set(newValue) { _doc = updateTreeConnection(_doc, newValue) }
        private var _id: Id
        var id: Id
            get() = _id
            set(newValue) { _id = updateTreeConnection(_id, newValue) }
        private val _params: MutableList<Param> = mutableListOf()
        var params: List<Param>
            get() = _params
            set(newValue) { updateTreeConnections(_params, newValue) }
        private var _returnType: TypeRef?
        var returnType: TypeRef?
            get() = _returnType
            set(newValue) { _returnType = updateTreeConnection(_returnType, newValue) }
        private var _body: Block
        var body: Block
            get() = _body
            set(newValue) { _body = updateTreeConnection(_body, newValue) }
        override fun deepCopy(): DefDecl {
            return DefDecl(pos, doc = this.doc?.deepCopy(), id = this.id.deepCopy(), params = this.params.deepCopy(), returnType = this.returnType?.deepCopy(), body = this.body.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is DefDecl && this.doc == other.doc && this.id == other.id && this.params == other.params && this.returnType == other.returnType && this.body == other.body
        }
        override fun hashCode(): Int {
            var hc = doc.hashCode()
            hc = 31 * hc + id.hashCode()
            hc = 31 * hc + params.hashCode()
            hc = 31 * hc + returnType.hashCode()
            hc = 31 * hc + body.hashCode()
            return hc
        }
        init {
            this._doc = updateTreeConnection(null, doc)
            this._id = updateTreeConnection(null, id)
            updateTreeConnections(this._params, params)
            this._returnType = updateTreeConnection(null, returnType)
            this._body = updateTreeConnection(null, body)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as DefDecl).doc },
                { n -> (n as DefDecl).id },
                { n -> (n as DefDecl).params },
                { n -> (n as DefDecl).returnType },
                { n -> (n as DefDecl).body },
            )
        }
    }

    /**
     * Raw Blimp source spliced in verbatim, used for the temper-core prelude.
     *
     * Blimp has no module system, so support code cannot be a separate file that
     * the output imports -- it has to be part of the one emitted file. be-lua's
     * `Connected` node does the same for raw Lua.
     */
    class Prelude(
        pos: Position,
        var source: String,
    ) : BaseTree(pos), Item {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override fun renderTo(
            tokenSink: TokenSink,
        ) {
            tokenSink.value(source)
        }
        override val codeFormattingTemplate: CodeFormattingTemplate?
            get() = null
        override fun deepCopy(): Prelude {
            return Prelude(pos, source = this.source)
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Prelude && this.source == other.source
        }
        override fun hashCode(): Int {
            return source.hashCode()
        }
        companion object {
            private val cmr = ChildMemberRelationships()
        }
    }

    sealed interface Expr : Tree {
        override fun deepCopy(): Expr
    }

    /** A dotted actor path: `Shop.Checkout.TaxCalculator`. */
    class Name(
        pos: Position,
        segments: Iterable<Id>,
    ) : BaseTree(pos), Expr {
        override val operatorDefinition
            get() = BlimpOperatorDefinition.Postfix
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate4
        override val formatElementCount
            get() = 1
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> FormattableTreeGroup(this.segments)
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private val _segments: MutableList<Id> = mutableListOf()
        var segments: List<Id>
            get() = _segments
            set(newValue) { updateTreeConnections(_segments, newValue) }
        override fun deepCopy(): Name {
            return Name(pos, segments = this.segments.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Name && this.segments == other.segments
        }
        override fun hashCode(): Int {
            return segments.hashCode()
        }
        init {
            updateTreeConnections(this._segments, segments)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as Name).segments },
            )
        }
    }

    /** `state total: Float :: 0.0` */
    class StateDecl(
        pos: Position,
        id: Id,
        type: TypeRef,
        init: Expr,
    ) : BaseTree(pos) {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate5
        override val formatElementCount
            get() = 3
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.id
                1 -> this.type
                2 -> this.init
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _id: Id
        var id: Id
            get() = _id
            set(newValue) { _id = updateTreeConnection(_id, newValue) }
        private var _type: TypeRef
        var type: TypeRef
            get() = _type
            set(newValue) { _type = updateTreeConnection(_type, newValue) }
        private var _init: Expr
        var init: Expr
            get() = _init
            set(newValue) { _init = updateTreeConnection(_init, newValue) }
        override fun deepCopy(): StateDecl {
            return StateDecl(pos, id = this.id.deepCopy(), type = this.type.deepCopy(), init = this.init.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is StateDecl && this.id == other.id && this.type == other.type && this.init == other.init
        }
        override fun hashCode(): Int {
            var hc = id.hashCode()
            hc = 31 * hc + type.hashCode()
            hc = 31 * hc + init.hashCode()
            return hc
        }
        init {
            this._id = updateTreeConnection(null, id)
            this._type = updateTreeConnection(null, type)
            this._init = updateTreeConnection(null, init)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as StateDecl).id },
                { n -> (n as StateDecl).type },
                { n -> (n as StateDecl).init },
            )
        }
    }

    /** `on :charge(payment: Payment) when ready bubbles(CascadeBubble) do ... end` */
    class Handler(
        pos: Position,
        message: Atom,
        params: Iterable<Param> = listOf(),
        guard: Expr? = null,
        bubbles: Id? = null,
        body: Block,
    ) : BaseTree(pos) {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() =
                if (params.isNotEmpty() && guard != null && bubbles != null) {
                    sharedCodeFormattingTemplate6
                } else if (params.isNotEmpty() && guard != null) {
                    sharedCodeFormattingTemplate7
                } else if (params.isNotEmpty() && bubbles != null) {
                    sharedCodeFormattingTemplate8
                } else if (params.isNotEmpty()) {
                    sharedCodeFormattingTemplate9
                } else if (guard != null && bubbles != null) {
                    sharedCodeFormattingTemplate10
                } else if (guard != null) {
                    sharedCodeFormattingTemplate11
                } else if (bubbles != null) {
                    sharedCodeFormattingTemplate12
                } else {
                    sharedCodeFormattingTemplate13
                }
        override val formatElementCount
            get() = 5
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.message
                1 -> FormattableTreeGroup(this.params)
                2 -> this.guard ?: FormattableTreeGroup.empty
                3 -> this.bubbles ?: FormattableTreeGroup.empty
                4 -> this.body
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _message: Atom
        var message: Atom
            get() = _message
            set(newValue) { _message = updateTreeConnection(_message, newValue) }
        private val _params: MutableList<Param> = mutableListOf()
        var params: List<Param>
            get() = _params
            set(newValue) { updateTreeConnections(_params, newValue) }
        private var _guard: Expr?
        var guard: Expr?
            get() = _guard
            set(newValue) { _guard = updateTreeConnection(_guard, newValue) }
        private var _bubbles: Id?
        var bubbles: Id?
            get() = _bubbles
            set(newValue) { _bubbles = updateTreeConnection(_bubbles, newValue) }
        private var _body: Block
        var body: Block
            get() = _body
            set(newValue) { _body = updateTreeConnection(_body, newValue) }
        override fun deepCopy(): Handler {
            return Handler(pos, message = this.message.deepCopy(), params = this.params.deepCopy(), guard = this.guard?.deepCopy(), bubbles = this.bubbles?.deepCopy(), body = this.body.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Handler && this.message == other.message && this.params == other.params && this.guard == other.guard && this.bubbles == other.bubbles && this.body == other.body
        }
        override fun hashCode(): Int {
            var hc = message.hashCode()
            hc = 31 * hc + params.hashCode()
            hc = 31 * hc + guard.hashCode()
            hc = 31 * hc + bubbles.hashCode()
            hc = 31 * hc + body.hashCode()
            return hc
        }
        init {
            this._message = updateTreeConnection(null, message)
            updateTreeConnections(this._params, params)
            this._guard = updateTreeConnection(null, guard)
            this._bubbles = updateTreeConnection(null, bubbles)
            this._body = updateTreeConnection(null, body)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as Handler).message },
                { n -> (n as Handler).params },
                { n -> (n as Handler).guard },
                { n -> (n as Handler).bubbles },
                { n -> (n as Handler).body },
            )
        }
    }

    /** Blimp's type annotations are shallow: Int, Float, String, Bool, Atom, List, Map, Any. */
    sealed interface TypeRef : Tree {
        override fun deepCopy(): TypeRef
    }

    sealed interface Pattern : Tree {
        override fun deepCopy(): Pattern
    }

    class Id(
        pos: Position,
        var outName: OutName,
    ) : BaseTree(pos), TypeRef, Expr, Pattern {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override fun renderTo(
            tokenSink: TokenSink,
        ) {
            tokenSink.name(outName, inOperatorPosition = false)
        }
        override val codeFormattingTemplate: CodeFormattingTemplate?
            get() = null
        override fun deepCopy(): Id {
            return Id(pos, outName = this.outName)
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Id && this.outName == other.outName
        }
        override fun hashCode(): Int {
            return outName.hashCode()
        }
        companion object {
            private val cmr = ChildMemberRelationships()
        }
    }

    sealed interface Message : Tree {
        override fun deepCopy(): Message
    }

    /** `:ok`, `:add` -- emitted as a single token so no space creeps after the colon. */
    class Atom(
        pos: Position,
        var text: String,
    ) : BaseTree(pos), Expr, Message, Pattern {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override fun renderTo(
            tokenSink: TokenSink,
        ) {
            tokenSink.emit(OutputToken(":$text", OutputTokenType.OtherValue))
        }
        override val codeFormattingTemplate: CodeFormattingTemplate?
            get() = null
        override fun deepCopy(): Atom {
            return Atom(pos, text = this.text)
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Atom && this.text == other.text
        }
        override fun hashCode(): Int {
            return text.hashCode()
        }
        companion object {
            private val cmr = ChildMemberRelationships()
        }
    }

    class Param(
        pos: Position,
        id: Id,
        type: TypeRef? = null,
    ) : BaseTree(pos) {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() =
                if (type != null) {
                    sharedCodeFormattingTemplate14
                } else {
                    sharedCodeFormattingTemplate15
                }
        override val formatElementCount
            get() = 2
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.id
                1 -> this.type ?: FormattableTreeGroup.empty
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _id: Id
        var id: Id
            get() = _id
            set(newValue) { _id = updateTreeConnection(_id, newValue) }
        private var _type: TypeRef?
        var type: TypeRef?
            get() = _type
            set(newValue) { _type = updateTreeConnection(_type, newValue) }
        override fun deepCopy(): Param {
            return Param(pos, id = this.id.deepCopy(), type = this.type?.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Param && this.id == other.id && this.type == other.type
        }
        override fun hashCode(): Int {
            var hc = id.hashCode()
            hc = 31 * hc + type.hashCode()
            return hc
        }
        init {
            this._id = updateTreeConnection(null, id)
            this._type = updateTreeConnection(null, type)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as Param).id },
                { n -> (n as Param).type },
            )
        }
    }

    class Block(
        pos: Position,
        statements: Iterable<Statement> = listOf(),
    ) : BaseTree(pos) {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate0
        override val formatElementCount
            get() = 1
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> FormattableTreeGroup(this.statements)
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private val _statements: MutableList<Statement> = mutableListOf()
        var statements: List<Statement>
            get() = _statements
            set(newValue) { updateTreeConnections(_statements, newValue) }
        override fun deepCopy(): Block {
            return Block(pos, statements = this.statements.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Block && this.statements == other.statements
        }
        override fun hashCode(): Int {
            return statements.hashCode()
        }
        init {
            updateTreeConnections(this._statements, statements)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as Block).statements },
            )
        }
    }

    /** Blimp rebinds within a block, so this covers both declaration and update. */
    class Assign(
        pos: Position,
        target: Id,
        value: Expr,
    ) : BaseTree(pos), Statement {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate16
        override val formatElementCount
            get() = 2
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.target
                1 -> this.value
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _target: Id
        var target: Id
            get() = _target
            set(newValue) { _target = updateTreeConnection(_target, newValue) }
        private var _value: Expr
        var value: Expr
            get() = _value
            set(newValue) { _value = updateTreeConnection(_value, newValue) }
        override fun deepCopy(): Assign {
            return Assign(pos, target = this.target.deepCopy(), value = this.value.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Assign && this.target == other.target && this.value == other.value
        }
        override fun hashCode(): Int {
            var hc = target.hashCode()
            hc = 31 * hc + value.hashCode()
            return hc
        }
        init {
            this._target = updateTreeConnection(null, target)
            this._value = updateTreeConnection(null, value)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as Assign).target },
                { n -> (n as Assign).value },
            )
        }
    }

    /** `become items: [], total: 0.0` */
    class Become(
        pos: Position,
        fields: Iterable<BecomeField>,
    ) : BaseTree(pos), Statement {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate17
        override val formatElementCount
            get() = 1
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> FormattableTreeGroup(this.fields)
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private val _fields: MutableList<BecomeField> = mutableListOf()
        var fields: List<BecomeField>
            get() = _fields
            set(newValue) { updateTreeConnections(_fields, newValue) }
        override fun deepCopy(): Become {
            return Become(pos, fields = this.fields.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Become && this.fields == other.fields
        }
        override fun hashCode(): Int {
            return fields.hashCode()
        }
        init {
            updateTreeConnections(this._fields, fields)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as Become).fields },
            )
        }
    }

    class BubbleStmt(
        pos: Position,
        value: Expr,
    ) : BaseTree(pos), Statement {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate18
        override val formatElementCount
            get() = 1
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.value
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _value: Expr
        var value: Expr
            get() = _value
            set(newValue) { _value = updateTreeConnection(_value, newValue) }
        override fun deepCopy(): BubbleStmt {
            return BubbleStmt(pos, value = this.value.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is BubbleStmt && this.value == other.value
        }
        override fun hashCode(): Int {
            return value.hashCode()
        }
        init {
            this._value = updateTreeConnection(null, value)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as BubbleStmt).value },
            )
        }
    }

    class ExprStatement(
        pos: Position,
        expr: Expr,
    ) : BaseTree(pos), Statement {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate15
        override val formatElementCount
            get() = 1
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.expr
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _expr: Expr
        var expr: Expr
            get() = _expr
            set(newValue) { _expr = updateTreeConnection(_expr, newValue) }
        override fun deepCopy(): ExprStatement {
            return ExprStatement(pos, expr = this.expr.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is ExprStatement && this.expr == other.expr
        }
        override fun hashCode(): Int {
            return expr.hashCode()
        }
        init {
            this._expr = updateTreeConnection(null, expr)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as ExprStatement).expr },
            )
        }
    }

    class ForLoop(
        pos: Position,
        id: Id,
        iterable: Expr,
        body: Block,
    ) : BaseTree(pos), Statement {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate19
        override val formatElementCount
            get() = 3
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.id
                1 -> this.iterable
                2 -> this.body
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _id: Id
        var id: Id
            get() = _id
            set(newValue) { _id = updateTreeConnection(_id, newValue) }
        private var _iterable: Expr
        var iterable: Expr
            get() = _iterable
            set(newValue) { _iterable = updateTreeConnection(_iterable, newValue) }
        private var _body: Block
        var body: Block
            get() = _body
            set(newValue) { _body = updateTreeConnection(_body, newValue) }
        override fun deepCopy(): ForLoop {
            return ForLoop(pos, id = this.id.deepCopy(), iterable = this.iterable.deepCopy(), body = this.body.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is ForLoop && this.id == other.id && this.iterable == other.iterable && this.body == other.body
        }
        override fun hashCode(): Int {
            var hc = id.hashCode()
            hc = 31 * hc + iterable.hashCode()
            hc = 31 * hc + body.hashCode()
            return hc
        }
        init {
            this._id = updateTreeConnection(null, id)
            this._iterable = updateTreeConnection(null, iterable)
            this._body = updateTreeConnection(null, body)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as ForLoop).id },
                { n -> (n as ForLoop).iterable },
                { n -> (n as ForLoop).body },
            )
        }
    }

    class Reply(
        pos: Position,
        value: Expr,
    ) : BaseTree(pos), Statement {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate20
        override val formatElementCount
            get() = 1
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.value
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _value: Expr
        var value: Expr
            get() = _value
            set(newValue) { _value = updateTreeConnection(_value, newValue) }
        override fun deepCopy(): Reply {
            return Reply(pos, value = this.value.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Reply && this.value == other.value
        }
        override fun hashCode(): Int {
            return value.hashCode()
        }
        init {
            this._value = updateTreeConnection(null, value)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as Reply).value },
            )
        }
    }

    class BecomeField(
        pos: Position,
        id: Id,
        value: Expr,
    ) : BaseTree(pos) {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate14
        override val formatElementCount
            get() = 2
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.id
                1 -> this.value
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _id: Id
        var id: Id
            get() = _id
            set(newValue) { _id = updateTreeConnection(_id, newValue) }
        private var _value: Expr
        var value: Expr
            get() = _value
            set(newValue) { _value = updateTreeConnection(_value, newValue) }
        override fun deepCopy(): BecomeField {
            return BecomeField(pos, id = this.id.deepCopy(), value = this.value.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is BecomeField && this.id == other.id && this.value == other.value
        }
        override fun hashCode(): Int {
            var hc = id.hashCode()
            hc = 31 * hc + value.hashCode()
            return hc
        }
        init {
            this._id = updateTreeConnection(null, id)
            this._value = updateTreeConnection(null, value)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as BecomeField).id },
                { n -> (n as BecomeField).value },
            )
        }
    }

    class BoolLit(
        pos: Position,
        var value: Boolean,
    ) : BaseTree(pos), Expr, Pattern {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() =
                if (value) {
                    sharedCodeFormattingTemplate21
                } else {
                    sharedCodeFormattingTemplate22
                }
        override val formatElementCount
            get() = 0
        override fun deepCopy(): BoolLit {
            return BoolLit(pos, value = this.value)
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is BoolLit && this.value == other.value
        }
        override fun hashCode(): Int {
            return value.hashCode()
        }
        companion object {
            private val cmr = ChildMemberRelationships()
        }
    }

    /**
     * `case subject do pat -> body end`
     *
     * Blimp's parser accepts `case` on the right of an assignment but not as a
     * call argument, so the translator hoists it into a temporary.
     */
    class CaseExpr(
        pos: Position,
        subject: Expr,
        arms: Iterable<CaseArm>,
    ) : BaseTree(pos), Expr {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate23
        override val formatElementCount
            get() = 2
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.subject
                1 -> FormattableTreeGroup(this.arms)
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _subject: Expr
        var subject: Expr
            get() = _subject
            set(newValue) { _subject = updateTreeConnection(_subject, newValue) }
        private val _arms: MutableList<CaseArm> = mutableListOf()
        var arms: List<CaseArm>
            get() = _arms
            set(newValue) { updateTreeConnections(_arms, newValue) }
        override fun deepCopy(): CaseExpr {
            return CaseExpr(pos, subject = this.subject.deepCopy(), arms = this.arms.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is CaseExpr && this.subject == other.subject && this.arms == other.arms
        }
        override fun hashCode(): Int {
            var hc = subject.hashCode()
            hc = 31 * hc + arms.hashCode()
            return hc
        }
        init {
            this._subject = updateTreeConnection(null, subject)
            updateTreeConnections(this._arms, arms)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as CaseExpr).subject },
                { n -> (n as CaseExpr).arms },
            )
        }
    }

    class Call(
        pos: Position,
        callee: Expr,
        args: Iterable<Expr> = listOf(),
    ) : BaseTree(pos), Expr {
        override val operatorDefinition
            get() = BlimpOperatorDefinition.Postfix
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate24
        override val formatElementCount
            get() = 2
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.callee
                1 -> FormattableTreeGroup(this.args)
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _callee: Expr
        var callee: Expr
            get() = _callee
            set(newValue) { _callee = updateTreeConnection(_callee, newValue) }
        private val _args: MutableList<Expr> = mutableListOf()
        var args: List<Expr>
            get() = _args
            set(newValue) { updateTreeConnections(_args, newValue) }
        override fun deepCopy(): Call {
            return Call(pos, callee = this.callee.deepCopy(), args = this.args.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Call && this.callee == other.callee && this.args == other.args
        }
        override fun hashCode(): Int {
            var hc = callee.hashCode()
            hc = 31 * hc + args.hashCode()
            return hc
        }
        init {
            this._callee = updateTreeConnection(null, callee)
            updateTreeConnections(this._args, args)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as Call).callee },
                { n -> (n as Call).args },
            )
        }
    }

    class Lambda(
        pos: Position,
        params: Iterable<Param> = listOf(),
        body: Block,
    ) : BaseTree(pos), Expr {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate25
        override val formatElementCount
            get() = 2
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> FormattableTreeGroup(this.params)
                1 -> this.body
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private val _params: MutableList<Param> = mutableListOf()
        var params: List<Param>
            get() = _params
            set(newValue) { updateTreeConnections(_params, newValue) }
        private var _body: Block
        var body: Block
            get() = _body
            set(newValue) { _body = updateTreeConnection(_body, newValue) }
        override fun deepCopy(): Lambda {
            return Lambda(pos, params = this.params.deepCopy(), body = this.body.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Lambda && this.params == other.params && this.body == other.body
        }
        override fun hashCode(): Int {
            var hc = params.hashCode()
            hc = 31 * hc + body.hashCode()
            return hc
        }
        init {
            updateTreeConnections(this._params, params)
            this._body = updateTreeConnection(null, body)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as Lambda).params },
                { n -> (n as Lambda).body },
            )
        }
    }

    class ListLit(
        pos: Position,
        items: Iterable<Expr> = listOf(),
    ) : BaseTree(pos), Expr {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate26
        override val formatElementCount
            get() = 1
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> FormattableTreeGroup(this.items)
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private val _items: MutableList<Expr> = mutableListOf()
        var items: List<Expr>
            get() = _items
            set(newValue) { updateTreeConnections(_items, newValue) }
        override fun deepCopy(): ListLit {
            return ListLit(pos, items = this.items.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is ListLit && this.items == other.items
        }
        override fun hashCode(): Int {
            return items.hashCode()
        }
        init {
            updateTreeConnections(this._items, items)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as ListLit).items },
            )
        }
    }

    /** Blimp map literals take static identifier keys only; dynamic keys go through `put`. */
    class MapLit(
        pos: Position,
        entries: Iterable<MapEntry> = listOf(),
    ) : BaseTree(pos), Expr {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate27
        override val formatElementCount
            get() = 1
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> FormattableTreeGroup(this.entries)
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private val _entries: MutableList<MapEntry> = mutableListOf()
        var entries: List<MapEntry>
            get() = _entries
            set(newValue) { updateTreeConnections(_entries, newValue) }
        override fun deepCopy(): MapLit {
            return MapLit(pos, entries = this.entries.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is MapLit && this.entries == other.entries
        }
        override fun hashCode(): Int {
            return entries.hashCode()
        }
        init {
            updateTreeConnections(this._entries, entries)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as MapLit).entries },
            )
        }
    }

    class Member(
        pos: Position,
        obj: Expr,
        id: Id,
    ) : BaseTree(pos), Expr {
        override val operatorDefinition
            get() = BlimpOperatorDefinition.Postfix
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate28
        override val formatElementCount
            get() = 2
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.obj
                1 -> this.id
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _obj: Expr
        var obj: Expr
            get() = _obj
            set(newValue) { _obj = updateTreeConnection(_obj, newValue) }
        private var _id: Id
        var id: Id
            get() = _id
            set(newValue) { _id = updateTreeConnection(_id, newValue) }
        override fun deepCopy(): Member {
            return Member(pos, obj = this.obj.deepCopy(), id = this.id.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Member && this.obj == other.obj && this.id == other.id
        }
        override fun hashCode(): Int {
            var hc = obj.hashCode()
            hc = 31 * hc + id.hashCode()
            return hc
        }
        init {
            this._obj = updateTreeConnection(null, obj)
            this._id = updateTreeConnection(null, id)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as Member).obj },
                { n -> (n as Member).id },
            )
        }
    }

    class NilLit(
        pos: Position,
    ) : BaseTree(pos), Expr, Pattern {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate29
        override val formatElementCount
            get() = 0
        override fun deepCopy(): NilLit {
            return NilLit(pos)
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is NilLit
        }
        override fun hashCode(): Int {
            return 0
        }
        companion object {
            private val cmr = ChildMemberRelationships()
        }
    }

    class NumberLit(
        pos: Position,
        var value: Number,
    ) : BaseTree(pos), Expr, Pattern {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override fun renderTo(
            tokenSink: TokenSink,
        ) {
            tokenSink.number(blimpNumberText(value))
        }
        override val codeFormattingTemplate: CodeFormattingTemplate?
            get() = null
        override fun deepCopy(): NumberLit {
            return NumberLit(pos, value = this.value)
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is NumberLit && this.value == other.value
        }
        override fun hashCode(): Int {
            return value.hashCode()
        }
        companion object {
            private val cmr = ChildMemberRelationships()
        }
    }

    class Operation(
        pos: Position,
        left: Expr? = null,
        operator: Operator,
        right: Expr? = null,
    ) : BaseTree(pos), Expr {
        override val operatorDefinition
            get() = operator.operator.operatorDefinition
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() =
                if (left != null && right != null) {
                    sharedCodeFormattingTemplate30
                } else if (left != null) {
                    sharedCodeFormattingTemplate31
                } else if (right != null) {
                    sharedCodeFormattingTemplate32
                } else {
                    sharedCodeFormattingTemplate33
                }
        override val formatElementCount
            get() = 3
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.left ?: FormattableTreeGroup.empty
                1 -> this.operator
                2 -> this.right ?: FormattableTreeGroup.empty
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _left: Expr?
        var left: Expr?
            get() = _left
            set(newValue) { _left = updateTreeConnection(_left, newValue) }
        private var _operator: Operator
        var operator: Operator
            get() = _operator
            set(newValue) { _operator = updateTreeConnection(_operator, newValue) }
        private var _right: Expr?
        var right: Expr?
            get() = _right
            set(newValue) { _right = updateTreeConnection(_right, newValue) }
        override fun deepCopy(): Operation {
            return Operation(pos, left = this.left?.deepCopy(), operator = this.operator.deepCopy(), right = this.right?.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Operation && this.left == other.left && this.operator == other.operator && this.right == other.right
        }
        override fun hashCode(): Int {
            var hc = left.hashCode()
            hc = 31 * hc + operator.hashCode()
            hc = 31 * hc + right.hashCode()
            return hc
        }
        init {
            this._left = updateTreeConnection(null, left)
            this._operator = updateTreeConnection(null, operator)
            this._right = updateTreeConnection(null, right)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as Operation).left },
                { n -> (n as Operation).operator },
                { n -> (n as Operation).right },
            )
        }
    }

    /** `checkout <- :add(item)` -- synchronous, evaluates to the handler's reply. */
    class Send(
        pos: Position,
        target: Expr,
        message: Message,
    ) : BaseTree(pos), Expr {
        override val operatorDefinition
            get() = BlimpOperatorDefinition.Send
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate34
        override val formatElementCount
            get() = 2
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.target
                1 -> this.message
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _target: Expr
        var target: Expr
            get() = _target
            set(newValue) { _target = updateTreeConnection(_target, newValue) }
        private var _message: Message
        var message: Message
            get() = _message
            set(newValue) { _message = updateTreeConnection(_message, newValue) }
        override fun deepCopy(): Send {
            return Send(pos, target = this.target.deepCopy(), message = this.message.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Send && this.target == other.target && this.message == other.message
        }
        override fun hashCode(): Int {
            var hc = target.hashCode()
            hc = 31 * hc + message.hashCode()
            return hc
        }
        init {
            this._target = updateTreeConnection(null, target)
            this._message = updateTreeConnection(null, message)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as Send).target },
                { n -> (n as Send).message },
            )
        }
    }

    /** `spawn Shop.Checkout, region: :us` */
    class Spawn(
        pos: Position,
        name: Name,
        inits: Iterable<SpawnInit> = listOf(),
    ) : BaseTree(pos), Expr {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() =
                if (inits.isNotEmpty()) {
                    sharedCodeFormattingTemplate35
                } else {
                    sharedCodeFormattingTemplate36
                }
        override val formatElementCount
            get() = 2
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.name
                1 -> FormattableTreeGroup(this.inits)
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _name: Name
        var name: Name
            get() = _name
            set(newValue) { _name = updateTreeConnection(_name, newValue) }
        private val _inits: MutableList<SpawnInit> = mutableListOf()
        var inits: List<SpawnInit>
            get() = _inits
            set(newValue) { updateTreeConnections(_inits, newValue) }
        override fun deepCopy(): Spawn {
            return Spawn(pos, name = this.name.deepCopy(), inits = this.inits.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Spawn && this.name == other.name && this.inits == other.inits
        }
        override fun hashCode(): Int {
            var hc = name.hashCode()
            hc = 31 * hc + inits.hashCode()
            return hc
        }
        init {
            this._name = updateTreeConnection(null, name)
            updateTreeConnections(this._inits, inits)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as Spawn).name },
                { n -> (n as Spawn).inits },
            )
        }
    }

    class StringLit(
        pos: Position,
        var value: String,
    ) : BaseTree(pos), Expr, Pattern {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override fun renderTo(
            tokenSink: TokenSink,
        ) {
            tokenSink.quoted(stringTokenText(value))
        }
        override val codeFormattingTemplate: CodeFormattingTemplate?
            get() = null
        override fun deepCopy(): StringLit {
            return StringLit(pos, value = this.value)
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is StringLit && this.value == other.value
        }
        override fun hashCode(): Int {
            return value.hashCode()
        }
        companion object {
            private val cmr = ChildMemberRelationships()
        }
    }

    /** `try do ... catch e do ... end` -- Temper's bubbles land here. */
    class TryCatch(
        pos: Position,
        body: Block,
        id: Id? = null,
        handler: Block,
    ) : BaseTree(pos), Expr {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() =
                if (id != null) {
                    sharedCodeFormattingTemplate37
                } else {
                    sharedCodeFormattingTemplate38
                }
        override val formatElementCount
            get() = 3
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.body
                1 -> this.id ?: FormattableTreeGroup.empty
                2 -> this.handler
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _body: Block
        var body: Block
            get() = _body
            set(newValue) { _body = updateTreeConnection(_body, newValue) }
        private var _id: Id?
        var id: Id?
            get() = _id
            set(newValue) { _id = updateTreeConnection(_id, newValue) }
        private var _handler: Block
        var handler: Block
            get() = _handler
            set(newValue) { _handler = updateTreeConnection(_handler, newValue) }
        override fun deepCopy(): TryCatch {
            return TryCatch(pos, body = this.body.deepCopy(), id = this.id?.deepCopy(), handler = this.handler.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is TryCatch && this.body == other.body && this.id == other.id && this.handler == other.handler
        }
        override fun hashCode(): Int {
            var hc = body.hashCode()
            hc = 31 * hc + id.hashCode()
            hc = 31 * hc + handler.hashCode()
            return hc
        }
        init {
            this._body = updateTreeConnection(null, body)
            this._id = updateTreeConnection(null, id)
            this._handler = updateTreeConnection(null, handler)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as TryCatch).body },
                { n -> (n as TryCatch).id },
                { n -> (n as TryCatch).handler },
            )
        }
    }

    class TupleLit(
        pos: Position,
        items: Iterable<Expr> = listOf(),
    ) : BaseTree(pos), Expr {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate39
        override val formatElementCount
            get() = 1
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> FormattableTreeGroup(this.items)
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private val _items: MutableList<Expr> = mutableListOf()
        var items: List<Expr>
            get() = _items
            set(newValue) { updateTreeConnections(_items, newValue) }
        override fun deepCopy(): TupleLit {
            return TupleLit(pos, items = this.items.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is TupleLit && this.items == other.items
        }
        override fun hashCode(): Int {
            return items.hashCode()
        }
        init {
            updateTreeConnections(this._items, items)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as TupleLit).items },
            )
        }
    }

    class CaseArm(
        pos: Position,
        pattern: Pattern,
        guard: Expr? = null,
        body: Block,
    ) : BaseTree(pos) {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() =
                if (guard != null) {
                    sharedCodeFormattingTemplate40
                } else {
                    sharedCodeFormattingTemplate41
                }
        override val formatElementCount
            get() = 3
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.pattern
                1 -> this.guard ?: FormattableTreeGroup.empty
                2 -> this.body
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _pattern: Pattern
        var pattern: Pattern
            get() = _pattern
            set(newValue) { _pattern = updateTreeConnection(_pattern, newValue) }
        private var _guard: Expr?
        var guard: Expr?
            get() = _guard
            set(newValue) { _guard = updateTreeConnection(_guard, newValue) }
        private var _body: Block
        var body: Block
            get() = _body
            set(newValue) { _body = updateTreeConnection(_body, newValue) }
        override fun deepCopy(): CaseArm {
            return CaseArm(pos, pattern = this.pattern.deepCopy(), guard = this.guard?.deepCopy(), body = this.body.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is CaseArm && this.pattern == other.pattern && this.guard == other.guard && this.body == other.body
        }
        override fun hashCode(): Int {
            var hc = pattern.hashCode()
            hc = 31 * hc + guard.hashCode()
            hc = 31 * hc + body.hashCode()
            return hc
        }
        init {
            this._pattern = updateTreeConnection(null, pattern)
            this._guard = updateTreeConnection(null, guard)
            this._body = updateTreeConnection(null, body)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as CaseArm).pattern },
                { n -> (n as CaseArm).guard },
                { n -> (n as CaseArm).body },
            )
        }
    }

    class MapEntry(
        pos: Position,
        key: Id,
        value: Expr,
    ) : BaseTree(pos) {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate14
        override val formatElementCount
            get() = 2
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.key
                1 -> this.value
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _key: Id
        var key: Id
            get() = _key
            set(newValue) { _key = updateTreeConnection(_key, newValue) }
        private var _value: Expr
        var value: Expr
            get() = _value
            set(newValue) { _value = updateTreeConnection(_value, newValue) }
        override fun deepCopy(): MapEntry {
            return MapEntry(pos, key = this.key.deepCopy(), value = this.value.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is MapEntry && this.key == other.key && this.value == other.value
        }
        override fun hashCode(): Int {
            var hc = key.hashCode()
            hc = 31 * hc + value.hashCode()
            return hc
        }
        init {
            this._key = updateTreeConnection(null, key)
            this._value = updateTreeConnection(null, value)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as MapEntry).key },
                { n -> (n as MapEntry).value },
            )
        }
    }

    class MessageCall(
        pos: Position,
        name: Atom,
        args: Iterable<Expr> = listOf(),
    ) : BaseTree(pos), Message {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate24
        override val formatElementCount
            get() = 2
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.name
                1 -> FormattableTreeGroup(this.args)
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _name: Atom
        var name: Atom
            get() = _name
            set(newValue) { _name = updateTreeConnection(_name, newValue) }
        private val _args: MutableList<Expr> = mutableListOf()
        var args: List<Expr>
            get() = _args
            set(newValue) { updateTreeConnections(_args, newValue) }
        override fun deepCopy(): MessageCall {
            return MessageCall(pos, name = this.name.deepCopy(), args = this.args.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is MessageCall && this.name == other.name && this.args == other.args
        }
        override fun hashCode(): Int {
            var hc = name.hashCode()
            hc = 31 * hc + args.hashCode()
            return hc
        }
        init {
            this._name = updateTreeConnection(null, name)
            updateTreeConnections(this._args, args)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as MessageCall).name },
                { n -> (n as MessageCall).args },
            )
        }
    }

    class SpawnInit(
        pos: Position,
        id: Id,
        value: Expr,
    ) : BaseTree(pos) {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate14
        override val formatElementCount
            get() = 2
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.id
                1 -> this.value
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _id: Id
        var id: Id
            get() = _id
            set(newValue) { _id = updateTreeConnection(_id, newValue) }
        private var _value: Expr
        var value: Expr
            get() = _value
            set(newValue) { _value = updateTreeConnection(_value, newValue) }
        override fun deepCopy(): SpawnInit {
            return SpawnInit(pos, id = this.id.deepCopy(), value = this.value.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is SpawnInit && this.id == other.id && this.value == other.value
        }
        override fun hashCode(): Int {
            var hc = id.hashCode()
            hc = 31 * hc + value.hashCode()
            return hc
        }
        init {
            this._id = updateTreeConnection(null, id)
            this._value = updateTreeConnection(null, value)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as SpawnInit).id },
                { n -> (n as SpawnInit).value },
            )
        }
    }

    class Operator(
        pos: Position,
        var operator: BlimpOperator,
    ) : BaseTree(pos) {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override fun renderTo(
            tokenSink: TokenSink,
        ) {
            operator.emit(tokenSink)
        }
        override val codeFormattingTemplate: CodeFormattingTemplate?
            get() = null
        override fun deepCopy(): Operator {
            return Operator(pos, operator = this.operator)
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Operator && this.operator == other.operator
        }
        override fun hashCode(): Int {
            return operator.hashCode()
        }
        companion object {
            private val cmr = ChildMemberRelationships()
        }
    }

    /** `[head | tail]` */
    class ConsPattern(
        pos: Position,
        head: Pattern,
        tail: Pattern,
    ) : BaseTree(pos), Pattern {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate42
        override val formatElementCount
            get() = 2
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> this.head
                1 -> this.tail
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private var _head: Pattern
        var head: Pattern
            get() = _head
            set(newValue) { _head = updateTreeConnection(_head, newValue) }
        private var _tail: Pattern
        var tail: Pattern
            get() = _tail
            set(newValue) { _tail = updateTreeConnection(_tail, newValue) }
        override fun deepCopy(): ConsPattern {
            return ConsPattern(pos, head = this.head.deepCopy(), tail = this.tail.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is ConsPattern && this.head == other.head && this.tail == other.tail
        }
        override fun hashCode(): Int {
            var hc = head.hashCode()
            hc = 31 * hc + tail.hashCode()
            return hc
        }
        init {
            this._head = updateTreeConnection(null, head)
            this._tail = updateTreeConnection(null, tail)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as ConsPattern).head },
                { n -> (n as ConsPattern).tail },
            )
        }
    }

    class ListPattern(
        pos: Position,
        items: Iterable<Pattern> = listOf(),
    ) : BaseTree(pos), Pattern {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate26
        override val formatElementCount
            get() = 1
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> FormattableTreeGroup(this.items)
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private val _items: MutableList<Pattern> = mutableListOf()
        var items: List<Pattern>
            get() = _items
            set(newValue) { updateTreeConnections(_items, newValue) }
        override fun deepCopy(): ListPattern {
            return ListPattern(pos, items = this.items.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is ListPattern && this.items == other.items
        }
        override fun hashCode(): Int {
            return items.hashCode()
        }
        init {
            updateTreeConnections(this._items, items)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as ListPattern).items },
            )
        }
    }

    class TuplePattern(
        pos: Position,
        items: Iterable<Pattern> = listOf(),
    ) : BaseTree(pos), Pattern {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate39
        override val formatElementCount
            get() = 1
        override fun formatElement(
            index: Int,
        ): IndexableFormattableTreeElement {
            return when (index) {
                0 -> FormattableTreeGroup(this.items)
                else -> throw IndexOutOfBoundsException("$index")
            }
        }
        private val _items: MutableList<Pattern> = mutableListOf()
        var items: List<Pattern>
            get() = _items
            set(newValue) { updateTreeConnections(_items, newValue) }
        override fun deepCopy(): TuplePattern {
            return TuplePattern(pos, items = this.items.deepCopy())
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is TuplePattern && this.items == other.items
        }
        override fun hashCode(): Int {
            return items.hashCode()
        }
        init {
            updateTreeConnections(this._items, items)
        }
        companion object {
            private val cmr = ChildMemberRelationships(
                { n -> (n as TuplePattern).items },
            )
        }
    }

    class Wildcard(
        pos: Position,
    ) : BaseTree(pos), Pattern {
        override val operatorDefinition: BlimpOperatorDefinition?
            get() = null
        override val codeFormattingTemplate: CodeFormattingTemplate
            get() = sharedCodeFormattingTemplate43
        override val formatElementCount
            get() = 0
        override fun deepCopy(): Wildcard {
            return Wildcard(pos)
        }
        override val childMemberRelationships
            get() = cmr
        override fun equals(
            other: Any?,
        ): Boolean {
            return other is Wildcard
        }
        override fun hashCode(): Int {
            return 0
        }
        companion object {
            private val cmr = ChildMemberRelationships()
        }
    }

    /** `{{0*\n}}` */
    private val sharedCodeFormattingTemplate0 =
        CodeFormattingTemplate.GroupSubstitution(
            0,
            CodeFormattingTemplate.NewLine,
        )

    /** `{{0}} actor {{1}} do {{2*\n}} {{3*\n}} end` */
    private val sharedCodeFormattingTemplate1 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("actor", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(1),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.GroupSubstitution(
                    2,
                    CodeFormattingTemplate.NewLine,
                ),
                CodeFormattingTemplate.GroupSubstitution(
                    3,
                    CodeFormattingTemplate.NewLine,
                ),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `{{0}} def {{1}} ( {{2*,}} ) -> {{3}} do {{4}} end` */
    private val sharedCodeFormattingTemplate2 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("def", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(1),
                CodeFormattingTemplate.LiteralToken("(", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.GroupSubstitution(
                    2,
                    CodeFormattingTemplate.LiteralToken(",", OutputTokenType.Punctuation),
                ),
                CodeFormattingTemplate.LiteralToken(")", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.LiteralToken("-\u003e", OutputTokenType.Punctuation),
                CodeFormattingTemplate.OneSubstitution(3),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(4),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `{{0}} def {{1}} ( {{2*,}} ) do {{4}} end` */
    private val sharedCodeFormattingTemplate3 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("def", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(1),
                CodeFormattingTemplate.LiteralToken("(", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.GroupSubstitution(
                    2,
                    CodeFormattingTemplate.LiteralToken(",", OutputTokenType.Punctuation),
                ),
                CodeFormattingTemplate.LiteralToken(")", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(4),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `{{0*.}}` */
    private val sharedCodeFormattingTemplate4 =
        CodeFormattingTemplate.GroupSubstitution(
            0,
            CodeFormattingTemplate.LiteralToken(".", OutputTokenType.Punctuation),
        )

    /** `state {{0}} : {{1}} :: {{2}}` */
    private val sharedCodeFormattingTemplate5 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("state", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken(":", OutputTokenType.Punctuation),
                CodeFormattingTemplate.OneSubstitution(1),
                CodeFormattingTemplate.LiteralToken("::", OutputTokenType.Punctuation),
                CodeFormattingTemplate.OneSubstitution(2),
            ),
        )

    /** `on {{0}} ( {{1*,}} ) when {{2}} bubbles ( {{3}} ) do {{4}} end` */
    private val sharedCodeFormattingTemplate6 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("on", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("(", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.GroupSubstitution(
                    1,
                    CodeFormattingTemplate.LiteralToken(",", OutputTokenType.Punctuation),
                ),
                CodeFormattingTemplate.LiteralToken(")", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.LiteralToken("when", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(2),
                CodeFormattingTemplate.LiteralToken("bubbles", OutputTokenType.Word),
                CodeFormattingTemplate.LiteralToken("(", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.OneSubstitution(3),
                CodeFormattingTemplate.LiteralToken(")", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(4),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `on {{0}} ( {{1*,}} ) when {{2}} do {{4}} end` */
    private val sharedCodeFormattingTemplate7 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("on", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("(", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.GroupSubstitution(
                    1,
                    CodeFormattingTemplate.LiteralToken(",", OutputTokenType.Punctuation),
                ),
                CodeFormattingTemplate.LiteralToken(")", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.LiteralToken("when", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(2),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(4),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `on {{0}} ( {{1*,}} ) bubbles ( {{3}} ) do {{4}} end` */
    private val sharedCodeFormattingTemplate8 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("on", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("(", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.GroupSubstitution(
                    1,
                    CodeFormattingTemplate.LiteralToken(",", OutputTokenType.Punctuation),
                ),
                CodeFormattingTemplate.LiteralToken(")", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.LiteralToken("bubbles", OutputTokenType.Word),
                CodeFormattingTemplate.LiteralToken("(", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.OneSubstitution(3),
                CodeFormattingTemplate.LiteralToken(")", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(4),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `on {{0}} ( {{1*,}} ) do {{4}} end` */
    private val sharedCodeFormattingTemplate9 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("on", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("(", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.GroupSubstitution(
                    1,
                    CodeFormattingTemplate.LiteralToken(",", OutputTokenType.Punctuation),
                ),
                CodeFormattingTemplate.LiteralToken(")", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(4),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `on {{0}} when {{2}} bubbles ( {{3}} ) do {{4}} end` */
    private val sharedCodeFormattingTemplate10 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("on", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("when", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(2),
                CodeFormattingTemplate.LiteralToken("bubbles", OutputTokenType.Word),
                CodeFormattingTemplate.LiteralToken("(", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.OneSubstitution(3),
                CodeFormattingTemplate.LiteralToken(")", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(4),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `on {{0}} when {{2}} do {{4}} end` */
    private val sharedCodeFormattingTemplate11 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("on", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("when", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(2),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(4),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `on {{0}} bubbles ( {{3}} ) do {{4}} end` */
    private val sharedCodeFormattingTemplate12 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("on", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("bubbles", OutputTokenType.Word),
                CodeFormattingTemplate.LiteralToken("(", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.OneSubstitution(3),
                CodeFormattingTemplate.LiteralToken(")", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(4),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `on {{0}} do {{4}} end` */
    private val sharedCodeFormattingTemplate13 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("on", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(4),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `{{0}} : {{1}}` */
    private val sharedCodeFormattingTemplate14 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken(":", OutputTokenType.Punctuation),
                CodeFormattingTemplate.OneSubstitution(1),
            ),
        )

    /** `{{0}}` */
    private val sharedCodeFormattingTemplate15 =
        CodeFormattingTemplate.OneSubstitution(0)

    /** `{{0}} = {{1}}` */
    private val sharedCodeFormattingTemplate16 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("=", OutputTokenType.Punctuation),
                CodeFormattingTemplate.OneSubstitution(1),
            ),
        )

    /** `become {{0*,}}` */
    private val sharedCodeFormattingTemplate17 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("become", OutputTokenType.Word),
                CodeFormattingTemplate.GroupSubstitution(
                    0,
                    CodeFormattingTemplate.LiteralToken(",", OutputTokenType.Punctuation),
                ),
            ),
        )

    /** `bubble {{0}}` */
    private val sharedCodeFormattingTemplate18 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("bubble", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
            ),
        )

    /** `for {{0}} in {{1}} do {{2}} end` */
    private val sharedCodeFormattingTemplate19 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("for", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("in", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(1),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(2),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `reply {{0}}` */
    private val sharedCodeFormattingTemplate20 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("reply", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
            ),
        )

    /** `true` */
    private val sharedCodeFormattingTemplate21 =
        CodeFormattingTemplate.LiteralToken("true", OutputTokenType.Word)

    /** `false` */
    private val sharedCodeFormattingTemplate22 =
        CodeFormattingTemplate.LiteralToken("false", OutputTokenType.Word)

    /** `case {{0}} do {{1*\n}} end` */
    private val sharedCodeFormattingTemplate23 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("case", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.GroupSubstitution(
                    1,
                    CodeFormattingTemplate.NewLine,
                ),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `{{0}} ( {{1*,}} )` */
    private val sharedCodeFormattingTemplate24 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("(", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.GroupSubstitution(
                    1,
                    CodeFormattingTemplate.LiteralToken(",", OutputTokenType.Punctuation),
                ),
                CodeFormattingTemplate.LiteralToken(")", OutputTokenType.Punctuation, TokenAssociation.Bracket),
            ),
        )

    /** `fn ( {{0*,}} ) do {{1}} end` */
    private val sharedCodeFormattingTemplate25 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("fn", OutputTokenType.Word),
                CodeFormattingTemplate.LiteralToken("(", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.GroupSubstitution(
                    0,
                    CodeFormattingTemplate.LiteralToken(",", OutputTokenType.Punctuation),
                ),
                CodeFormattingTemplate.LiteralToken(")", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(1),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `[ {{0*,}} ]` */
    private val sharedCodeFormattingTemplate26 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("[", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.GroupSubstitution(
                    0,
                    CodeFormattingTemplate.LiteralToken(",", OutputTokenType.Punctuation),
                ),
                CodeFormattingTemplate.LiteralToken("]", OutputTokenType.Punctuation, TokenAssociation.Bracket),
            ),
        )

    /** `%\{ {{0*,}} \}` */
    private val sharedCodeFormattingTemplate27 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("%{", OutputTokenType.Punctuation),
                CodeFormattingTemplate.GroupSubstitution(
                    0,
                    CodeFormattingTemplate.LiteralToken(",", OutputTokenType.Punctuation),
                ),
                CodeFormattingTemplate.LiteralToken("}", OutputTokenType.Punctuation),
            ),
        )

    /** `{{0}} . {{1}}` */
    private val sharedCodeFormattingTemplate28 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken(".", OutputTokenType.Punctuation),
                CodeFormattingTemplate.OneSubstitution(1),
            ),
        )

    /** `nil` */
    private val sharedCodeFormattingTemplate29 =
        CodeFormattingTemplate.LiteralToken("nil", OutputTokenType.Word)

    /** `{{0}} {{1}} {{2}}` */
    private val sharedCodeFormattingTemplate30 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.OneSubstitution(1),
                CodeFormattingTemplate.OneSubstitution(2),
            ),
        )

    /** `{{0}} {{1}}` */
    private val sharedCodeFormattingTemplate31 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.OneSubstitution(1),
            ),
        )

    /** `{{1}} {{2}}` */
    private val sharedCodeFormattingTemplate32 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.OneSubstitution(1),
                CodeFormattingTemplate.OneSubstitution(2),
            ),
        )

    /** `{{1}}` */
    private val sharedCodeFormattingTemplate33 =
        CodeFormattingTemplate.OneSubstitution(1)

    /** `{{0}} <- {{1}}` */
    private val sharedCodeFormattingTemplate34 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("\u003c-", OutputTokenType.Punctuation),
                CodeFormattingTemplate.OneSubstitution(1),
            ),
        )

    /** `spawn {{0}} , {{1*,}}` */
    private val sharedCodeFormattingTemplate35 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("spawn", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken(",", OutputTokenType.Punctuation),
                CodeFormattingTemplate.GroupSubstitution(
                    1,
                    CodeFormattingTemplate.LiteralToken(",", OutputTokenType.Punctuation),
                ),
            ),
        )

    /** `spawn {{0}}` */
    private val sharedCodeFormattingTemplate36 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("spawn", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
            ),
        )

    /** `try do {{0}} catch {{1}} do {{2}} end` */
    private val sharedCodeFormattingTemplate37 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("try", OutputTokenType.Word),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("catch", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(1),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(2),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `try do {{0}} catch do {{2}} end` */
    private val sharedCodeFormattingTemplate38 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("try", OutputTokenType.Word),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("catch", OutputTokenType.Word),
                CodeFormattingTemplate.LiteralToken("do", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(2),
                CodeFormattingTemplate.LiteralToken("end", OutputTokenType.Word),
            ),
        )

    /** `\{ {{0*,}} \}` */
    private val sharedCodeFormattingTemplate39 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("{", OutputTokenType.Punctuation),
                CodeFormattingTemplate.GroupSubstitution(
                    0,
                    CodeFormattingTemplate.LiteralToken(",", OutputTokenType.Punctuation),
                ),
                CodeFormattingTemplate.LiteralToken("}", OutputTokenType.Punctuation),
            ),
        )

    /** `{{0}} when {{1}} -> {{2}}` */
    private val sharedCodeFormattingTemplate40 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("when", OutputTokenType.Word),
                CodeFormattingTemplate.OneSubstitution(1),
                CodeFormattingTemplate.LiteralToken("-\u003e", OutputTokenType.Punctuation),
                CodeFormattingTemplate.OneSubstitution(2),
            ),
        )

    /** `{{0}} -> {{2}}` */
    private val sharedCodeFormattingTemplate41 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("-\u003e", OutputTokenType.Punctuation),
                CodeFormattingTemplate.OneSubstitution(2),
            ),
        )

    /** `[ {{0}} | {{1}} ]` */
    private val sharedCodeFormattingTemplate42 =
        CodeFormattingTemplate.Concatenation(
            listOf(
                CodeFormattingTemplate.LiteralToken("[", OutputTokenType.Punctuation, TokenAssociation.Bracket),
                CodeFormattingTemplate.OneSubstitution(0),
                CodeFormattingTemplate.LiteralToken("|", OutputTokenType.Punctuation),
                CodeFormattingTemplate.OneSubstitution(1),
                CodeFormattingTemplate.LiteralToken("]", OutputTokenType.Punctuation, TokenAssociation.Bracket),
            ),
        )

    /** `_` */
    private val sharedCodeFormattingTemplate43 =
        CodeFormattingTemplate.LiteralToken("_", OutputTokenType.Word)
}
