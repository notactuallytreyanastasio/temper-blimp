# 2026-10-01: programs the suite never saw

65 of 65 says the backend handles the 65 programs it was built against. The
Blimp backend reached the same number, and the first program written outside
its suite, a 130-line calculator, failed five of its six tests. So this
session ran be-elixir over every Temper program on hand that was not written
for it, and compared each with `-b js`. It ran on Elixir 1.18.4 and OTP 28; the
entries before it used 1.19.5.

| Program | `-b js` | `-b elixir`, before this entry |
|---------|---------|-------------|
| blimp-highlight, a Blimp syntax highlighter written in Temper | 11 of 11 | 11 of 11 |
| temper_snake, the game library | 31 of 31 | 31 of 31 |
| alloy | 225 of 225 | 225 of 225 |
| prismora | 12 of 12 | 12 of 12 |
| templight | 140 of 140 | 140 of 140 |
| ormery | 0 of 30 (30 not run) | translation crashed |
| an older ORM | 0 of 127 (127 not run) | translation crashed |

The highlighter carries its own oracle: a token dumper built from Blimp's
real `lexer.zig`. Its `dump` under Elixir matched the oracle on all 96
`.blimp` files tracked in this repository, plus the 11,000-line generated
snake server, 62,362 tokens, in one `mix run`: 1.9 s, 289 MB peak. Under
`-b blimp` the same library runs out of a 2 GB heap on the largest of them.

The two ORMs do not run anywhere: each has a class that declares its
constructor inputs and an explicit constructor, which the frontend rejects.
`-b js` builds them anyway and stops at run time with the frontend's
message. be-elixir stopped the build. That difference turned into three
fixes.

## A property of a value whose type did not compile

```
kotlin.NotImplementedError: An operation is not implemented:
  getter Invalid.fieldType has no Elixir support code: fieldInfo__396.fieldType
```

ormery's `Query` is the rejected class, so its field `schema` has the type
*Invalid*, and so does `schema.getField(...)`. `builtinGet` read `Invalid` as
a builtin type it had no support code for, which is a `TODO`. It is the case
the build was told to go on through, so it now gets what every other garbage
node gets, a raise where it stands:

```elixir
raise(TemperCore.Panic, "broken code: read of .fieldType on a value of a type that did not compile")
```

`semantics/broken` covers garbage nodes, but none typed this way, which is
why 65 of 65 never reached it. Fifteen lines reproduce it, and they are the
first test in a new `ElixirBackendTest`.

## A raise ends its block

With the crash gone, ormery's Elixir did not compile:

```
error: undefined variable "fieldType"
 442 │                 fieldType == "Int" ->
```

Entry 23's tidy pass turns `fieldType = raise(...)` into `raise(...)`,
because Elixir's type checker warns that the binding can never match. But
Elixir checks every variable a function reads, reachable or not, so the
later read of `fieldType` is a compile error. `probes/10_unbound_after_raise.exs`
shows all three shapes:

```
error: undefined variable "x"
binding dropped, read kept: {:compile_error, ...}
warning: the following pattern will never match:
binding kept          : :compiles
block ends at raise   : :compiles
```

Keeping the binding brings back the warning entry 23 removed. Nothing after
a raise in the same block can run, so the pass now ends the block there.
The second test in `ElixirBackendTest` covers it. Reverting only the tidy
change makes that test fail, which is how the test was checked. The first
attempt at it failed for another reason: the generated output includes the
source map, whose `sourcesContent` is the Temper source, so a search for a
Temper name always matched. The helper leaves `.map` files out.

## Tests the CLI never heard about

ormery then compiled and failed at run time, as on JS, but reported
differently:

```
-b js       Tests passed: 0 of 30 (30 not run)
-b elixir   Tests passed: 0 of 0
```

The CLI counts tests from the ones each backend registers through
`dependenciesBuilder.addTest`. be-js, be-py and be-lua register theirs;
be-elixir did not, so a run that died before writing `test-results.xml`
looked like an empty suite. The registered name has to be the name the
JUnit report carries, so it is the test's function name. That also gives
the CLI the sentence to print for a failure:

```
before: Test failed (elixir): thisOneIsWrongOnPurpose__6 - expected 3
after:  Test failed (elixir): this one is wrong on purpose - expected 3
```

The test helpers do not hand back a backend's dependencies, so this one is
checked through the CLI: ormery now reports `0 of 30 (30 not run)`, alloy
still `225 of 225`, and the line above was checked both ways by reverting
the change.

## The constructor of a rejected class

The class ormery's frontend rejected still has a constructor, and it still
says `this.schema = schema` about a property the class no longer has. That
compiled to `Temper.Ormery.Query.set_schema(this, schema)`, a function the
class's module never defines: a compiler warning, and an
`UndefinedFunctionError` when Elixir code constructs the class directly.

A class module defines `set_x` exactly when the class's flattened members
include a setter for `x` with a body, so the translator can ask the same
question before calling one. When the answer is no, for a class of this
library, the write is broken code like the rest of the class:

```
** (TemperCore.Panic) broken code: write of .schema on a class that does not declare it
```

The same class's reads had the same problem (`get_schema/1`), and get the
same answer. A class from another library cannot be inspected from here,
so a call into one stands.

The second ORM showed a third shape. Its `new Query(tableName, [], [], [],
null, null)` failed to type-check, and a call the frontend could not check
carries `invalidSig`: no fixed parameters and a rest parameter typed
*Invalid*. Packing arguments by that signature put all six into one list,
a call of `Query.new/1` against a `new/6`. With the real arity unknown, the
arguments now go as written, as they do in js.

Three more `ElixirBackendTest` cases, each red first. After them neither
ORM has an undefined function in its generated Elixir. What warnings
remain are Elixir's type checker reasoning about values whose types did not
compile: 36 and 10 "incompatible types given to", and 35 "comparison
between distinct types".

## What is left

ormery has one warning of another kind, and it is not about broken code:

```
warning: clauses with the same name and arity ... "def main/0" was previously defined
```

ormery exports a function called `main`, and be-elixir names its own entry
point `main/0`. The user's comes first, so the entry point never runs. A
four-line library shows what that costs: running it prints "user main ran"
though nothing called `main`, and the entry point's `TemperCore.Async.drain()`
is skipped. Fixing it means renaming one of the two, which changes how a
translated program is run, so it is left for its own entry.

65 of 65 functional tests pass.
