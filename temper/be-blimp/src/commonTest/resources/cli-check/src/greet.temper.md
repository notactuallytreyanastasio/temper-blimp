# Greeting

The library `temper test -b blimp` was checked with. Run it from this
directory:

    $ temper test -b blimp
    Tests passed: 2 of 2

    export let greet(who: String): String { "hello ${who}" }

    test("greets") {
      assert(greet("world") == "hello world");
    }

    test("greets again") {
      assert(greet("blimp") == "hello blimp");
    }
