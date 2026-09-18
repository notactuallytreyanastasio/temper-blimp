package lang.temper.be.blimp

import lang.temper.be.Backend
import lang.temper.be.assertGeneratedCode
import lang.temper.log.FilePath
import lang.temper.log.filePath
import kotlin.test.Test

/**
 * What one construct translates to, checked against the text rather than by
 * running a program.
 *
 * The functional suite runs whole programs, and a program that happens not to
 * call `length` passes whether or not the backend would have shadowed it. That
 * is how the bug in chapter 57 survived: every test was green while a library
 * exporting `length` compiled to a function that called itself.
 *
 * Every program here is chosen to need nothing from temper-core, because the
 * prelude is spliced in verbatim when anything does and there is no reading
 * three thousand lines of it in an assertion.
 */
class BlimpBackendTest {
    @Test
    fun classBecomesActor() {
        assertGeneratedBlimp(
            temper = """
                |export class Counter(public n: Int) {
                |  public get(): Int { n }
                |}
            """.trimMargin(),
            blimp = """
                |actor Counter do
                |  state n__0: Any :: nil
                |  on :__temper_types do
                |    reply[:Counter]
                |  end
                |  on :get do
                |    reply n__0
                |  end
                |  on :__new(n__1: Any) do
                |    n__0 = n__1
                |    become n__0: n__0
                |    reply nil
                |  end
                |  on :n do
                |    reply n__0
                |  end
                |end
                |
            """.trimMargin(),
        )
    }

    /**
     * A top-level name is the one that can collide.
     *
     * A field or a local carries a `__N` suffix that disambiguates it, so
     * `length` as a property is `length__0` and could never have shadowed
     * anything. An exported function keeps the name it was written with.
     */
    @Test
    fun nameThatShadowsABuiltin() {
        assertGeneratedBlimp(
            temper = """
                |export let length(h: Holder): Holder { h }
                |export class Holder() {}
            """.trimMargin(),
            blimp = """
                |actor Holder do
                |  on :__temper_types do
                |    reply[:Holder]
                |  end
                |  on :__new do
                |    reply nil
                |  end
                |end
                |def length_(h__0: Any) -> Any do
                |  h__0
                |end
                |
            """.trimMargin(),
        )
    }

    @Test
    fun nameThatIsAKeyword() {
        assertGeneratedBlimp(
            temper = """
                |export let end(s: Span): Span { s }
                |export class Span() {}
            """.trimMargin(),
            blimp = """
                |actor Span do
                |  on :__temper_types do
                |    reply[:Span]
                |  end
                |  on :__new do
                |    reply nil
                |  end
                |end
                |def end_(s__0: Any) -> Any do
                |  s__0
                |end
                |
            """.trimMargin(),
        )
    }

    /**
     * A Blimp actor stands alone: there is no super to call, so a subclass
     * carries its parent's handlers rather than inheriting them. `Animal`
     * emits nothing of its own -- an interface is not an actor -- and its
     * `speak` arrives at the bottom of `Dog`, after `Dog`'s own.
     *
     * `__temper_types` is how a downcast is answered without a class
     * hierarchy to ask: the list is the chain, most derived first.
     */
    @Test
    fun subclassIsFlattened() {
        assertGeneratedBlimp(
            temper = """
                |export interface Animal {
                |  public speak(): Animal { this }
                |}
                |export class Dog() extends Animal {
                |  public fetch(): Dog { this }
                |}
            """.trimMargin(),
            blimp = """
                |actor Dog do
                |  on :__temper_types do
                |    reply[:Dog, :Animal]
                |  end
                |  on :fetch do
                |    reply self
                |  end
                |  on :__new do
                |    reply nil
                |  end
                |  on :speak do
                |    reply self
                |  end
                |end
                |
            """.trimMargin(),
        )
    }
}

private fun assertGeneratedBlimp(
    temper: String,
    blimp: String,
) {
    val escaped = """
        |               "content":
        |```
        |$blimp
        |```
    """.trimMargin()
    assertGeneratedCode(
        backendConfig = Backend.Config.production,
        factory = BlimpBackend.Factory,
        inputs = listOf<Pair<FilePath, String>>(filePath("something", "something.temper") to temper),
        want = """
            |{
            |    "blimp": {
            |        "my-test-library": {
            |            "main.blimp": {
            |${escaped}
            |            },
            |            "main.blimp.map": "__DO_NOT_CARE__"
            |        }
            |    }
            |}
        """.trimMargin(),
    )
}
