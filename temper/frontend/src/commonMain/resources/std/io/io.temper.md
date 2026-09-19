# Console I/O

A program that only computes needs neither of these. A program that has to
keep time with something outside itself -- a user, a frame rate, another
process -- needs both, and before this the standard library's whole I/O
surface was `console.log`.

Both return a promise, so `await` is where the waiting shows up in the source
rather than the call looking like it returns instantly.

*Sleep* pauses for at least `ms` milliseconds and answers how many it was
asked for. A backend is free to take longer; none may return early.

The answer is an `Int` rather than `Void` because `await` binds its result to
a type formal bounded by `AnyValue`, and `Void` does not fit it -- the
compiler says `Type formal <awaitR extends AnyValue> cannot bind to Void`.
Nothing in this file may be indented four spaces except the Temper itself: a
four-space indent *is* the code block in this format, so that message has to
stay inline. So a promise of nothing cannot be awaited, and a sleep that cannot be awaited
is not a sleep. Callers are expected to ignore the number.

    @connected
    export let sleep(ms: Int): Promise<Int> {
      panic()
    }

*ReadLine* reads one line from standard input, without its line terminator,
and answers null at end of input.

Null and the empty string are different answers: a user who presses Enter on
an empty line typed something, and a caller that cannot tell the two apart
spins forever once stdin closes.

    @connected
    export let readLine(): Promise<String?> {
      panic()
    }
