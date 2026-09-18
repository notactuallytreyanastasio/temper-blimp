# Greeting

The library `temper test -b blimp` was checked with. Run it from the directory
above this one and it answers `Tests passed: 2 of 2`.

Nothing in this file may be indented four spaces except the Temper itself: a
four-space indent *is* the code block in this format, and a fenced block with
no language tag is one too. The first version of this file put a shell
transcript in each, and the library stopped compiling — which is a poor
advertisement for a file whose whole job is to be compiled.

    export let greet(who: String): String { "hello ${who}" }

    test("greets") {
      assert(greet("world") == "hello world");
    }

    test("greets again") {
      assert(greet("blimp") == "hello blimp");
    }
