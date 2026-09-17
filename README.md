# A Temper backend for Blimp

[Temper][] is a language for writing libraries once and compiling them to many
other languages. [Blimp][] is an actor-oriented language in which actors are
the only abstraction.

This repository holds both, plus a new Temper backend that targets Blimp, as a
series of pull requests meant to be read in order.

[Temper]: https://temperlang.dev/
[Blimp]: https://blimp.bobbby.online/

## What is here

    temper/   Temper, plus the new be-blimp backend
    blimp/    Blimp, plus the one language change the backend needed

## The interesting part

The two languages disagree about nearly everything. Temper has classes,
interfaces, `while`, `break`, early `return` and a module system. Blimp has
none of those. It has actors, `case`, `for..in`, tail recursion, and one flat
namespace.

So the backend is mostly a set of lowerings, and each pull request is one or
two of them:

- a Temper class becomes an actor, its fields `state`, its methods `on`
  handlers, and a method call a synchronous send
- a `while` becomes a top-level tail-recursive `def` carrying the loop
  variables, which costs nothing because Blimp optimises tail calls
- an early `return` becomes a continuation `def` that the non-returning paths
  tail-call
- `instanceof` becomes a question you ask the actor

Virtual dispatch needed no code at all: a send through a base-typed reference
already dispatches on the receiving actor's own handlers.

Several lowerings have the shape they do because the obvious version is
silently wrong. Blimp's scoping is asymmetric in four places that would each
have miscompiled quietly, and the pull requests say which and why.

## State of it

18 of Temper's 65 shared functional tests pass, up from none. Anything the
translator cannot yet handle is a `TODO()` carrying the offending node, so it
fails loudly at build time rather than emitting wrong code.

## Provenance and licensing

`temper/` is a copy of [temperlang/temper][upstream] with the `be-blimp`
module added and small changes to `fundamentals`, `builtin`, `interp`,
`be-rust` and `functional-test-suite`. It keeps its own licences, which travel
with it: see `temper/LICENSE-APACHE`, `temper/LICENSE-MIT`,
`temper/LICENSE-CC-BY-SA-4.0` and `temper/COPYRIGHT`.

`blimp/` is a copy of Blimp with one builtin added, under `blimp/LICENSE`.

This is neither an official fork of Temper nor endorsed by or affiliated with
the Temper project. It exists to show the backend work as a readable series of
changes.

[upstream]: https://github.com/temperlang/temper
