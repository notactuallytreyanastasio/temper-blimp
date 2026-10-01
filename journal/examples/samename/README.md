# samename

Two libraries that each declare a class `Foo`. `user` reads a property of,
and calls a method on, the `Foo` that `base` makes. be-elixir used to find a
class by its short name alone, so `user`'s own `Foo` answered for base's.
Before entry 30 that was an `UndefinedFunctionError`; entry 30 turned it into
a "broken code" panic; entry 32 fixed the lookup.

    cd journal/examples/samename
    temper run -b elixir --library user -w .

js, py and elixir all print:

    theirs.x=42 doubled=84
    mine.y=1
