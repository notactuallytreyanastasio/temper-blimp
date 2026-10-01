package lang.temper.be.elixir

import lang.temper.be.Backend
import lang.temper.be.assertGeneratedStructure
import lang.temper.common.structure.FormattingStructureSink
import lang.temper.lexer.Genre
import lang.temper.log.filePath
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ElixirBackendTest {
    /**
     * A class the frontend rejects leaves its values typed *Invalid*. Reading a
     * property of one must become broken code that raises where it is reached,
     * as every other garbage node does, and not a TODO that takes the whole
     * translation down. `semantics/broken` never reaches this path: it came from
     * a library written outside the suite (ormery), whose `Query` class declares
     * its inputs twice.
     */
    @Test
    fun aGetterOnAnInvalidValueIsBrokenCodeNotACompilerCrash() {
        val out = generatedText(
            """
            |class Field(public fieldType: String) {}
            |
            |class Schema() {
            |  public getField(): Field { new Field("Int") }
            |}
            |
            |class Query(public schema: Schema) {
            |  public constructor(schema: Schema) { this.schema = schema; }
            |  public kind(): String { let f = schema.getField(); f.fieldType }
            |}
            """.trimMargin(),
        )
        assertContains(out, "broken code")
    }

    /**
     * A local whose value raises is never bound, and Elixir refuses to compile
     * a later read of it even though the read can never run ("undefined
     * variable"). So nothing after a raise in the same block may be emitted.
     * ormery's generated Elixir failed `mix compile` five times this way once
     * its broken reads stopped crashing the translator.
     */
    @Test
    fun nothingAfterARaiseReadsTheNameItWouldHaveBound() {
        val out = generatedText(
            """
            |class Field(public fieldType: String) {}
            |
            |class Schema() {
            |  public getField(): Field { new Field("Int") }
            |}
            |
            |class Query(public schema: Schema) {
            |  public constructor(schema: Schema) { this.schema = schema; }
            |  public kind(): String {
            |    let f = schema.getField();
            |    let fieldKind = f.fieldType;
            |    if (fieldKind == "Int") { "int" } else { "other" }
            |  }
            |}
            """.trimMargin(),
        )
        assertContains(out, "broken code")
        assertFalse("fieldKind ==" in out, "a read of fieldKind survives its raise:\n$out")
    }

    /**
     * A class that declares its inputs twice is rejected, and its constructor's
     * `this.schema = schema` is left writing a property the class no longer
     * has. Calling `Query.set_schema/2` there names a function that is never
     * defined: a compiler warning, and UndefinedFunctionError when Elixir code
     * constructs the class. It is broken code like the rest of the class.
     */
    @Test
    fun aWriteToAPropertyTheClassDoesNotHaveIsBrokenCode() {
        val out = generatedText(
            """
            |class Schema() {}
            |
            |class Query(public schema: Schema) {
            |  public constructor(schema: Schema) { this.schema = schema; }
            |}
            """.trimMargin(),
        )
        assertFalse("set_schema(" in out, "a call of a setter Query never defines:\n$out")
        assertContains(out, "broken code: write of .schema")
    }

    /** The same class, read: `get_schema/1` is never defined either. */
    @Test
    fun aReadOfAPropertyTheClassDoesNotHaveIsBrokenCode() {
        val out = generatedText(
            """
            |class Schema() {}
            |
            |class Query(public schema: Schema) {
            |  public constructor(schema: Schema) { }
            |  public peek(): Schema { this.schema }
            |}
            """.trimMargin(),
        )
        assertFalse("get_schema(" in out, "a call of a getter Query never defines:\n$out")
        assertContains(out, "broken code: read of .schema")
    }

    /**
     * A call the frontend could not type-check carries `invalidSig`: no fixed
     * parameters and a rest parameter of type *Invalid*. Packing by that
     * signature put every argument into one list, a call of `Query.new/1`
     * against a `new/2`. With the real arity unknown, the arguments go as
     * written, as they do in js.
     */
    @Test
    fun aCallWithNoSignatureKeepsItsArguments() {
        val out = generatedText(
            """
            |export class Query(public name: String, public conds: List<Nope>) {}
            |export let from(n: String): Query { new Query(n, []) }
            """.trimMargin(),
        )
        assertFalse("Query.new(%TemperCore.Vec" in out, "the arguments were packed into one list:\n$out")
        assertContains(out, "Query.new(n, ")
    }
}

/**
 * Every file the backend writes for one Temper source, as one JSON string.
 * Source maps are left out: they embed the Temper source, which would match
 * any search for a Temper name.
 */
private fun generatedText(temper: String): String {
    var text = ""
    assertGeneratedStructure(
        inputs = listOf(filePath("something", "something.temper") to temper),
        factory = ElixirBackend.Factory,
        backendConfig = Backend.Config.production,
        genre = Genre.Library,
        moduleResultNeeded = false,
    ) { text = FormattingStructureSink.toJsonString(it, filterKeys = { key -> !key.endsWith(".map") }) }
    return text
}
