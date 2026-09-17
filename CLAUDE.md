# temper-blimp

Temper and Blimp side by side, plus a Temper backend that targets Blimp, told
as a stack of pull requests.

## Layout

    temper/   Temper (upstream temperlang/temper) + the be-blimp backend
    blimp/    Blimp + the one builtin the backend needed
    .prs/     PR bodies, gitignored, passed to `gh pr create --body-file`

Neither subtree is a submodule. Both were extracted with `git archive` from
their own repos at a base commit, so they contain tracked source only — no
build output. Upstream changes come in the same way, not by pulling.

## The pull request stack

Every PR is based on the one before it, so each diff is exactly one chapter:

    main <- 01-out-grammar <- 02-backend-scaffold <- ... <- 09-the-core-library

Adding a chapter means a new `NN-slug` branch off the current tip, a body in
`.prs/NN-slug.md`, and a PR based on the previous branch. Never squash the
stack into one PR; the order is the point.

## Working on the backend

Everything lives in `temper/be-blimp`. It needs JDK 21 and a `blimp` on PATH.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
cd temper
./gradlew :be-blimp:jvmTest :be-blimp:ktlintCheck   # the gate
./gradlew :be-blimp:ktlintFormat                    # fixes formatting for you
./gradlew kcodegen:updateGeneratedCode              # after editing blimp.out-grammar
./gradlew :cli:installDist                          # rebuild the temper CLI
```

`Blimp.kt` is generated from `blimp.out-grammar`. Never hand-edit it.

To see real output end to end, build a small library and run what comes out:

```bash
temper build -b blimp -w /path/to/lib
blimp /path/to/lib/temper.out/blimp/<lib>/main.blimp
```

`functional-test-matrix.md` regenerates when the functional suite runs and is
already stale upstream in the `cpp` column. Leave it alone: `git checkout
functional-test-matrix.md` after test runs.

## Rules that are load-bearing here

**Verify against the interpreter, not the source.** Every claim about Blimp in
this repo was checked by running `blimp`. Four of its scoping behaviours are
asymmetric in ways that silently miscompile, and reading the Zig would not have
caught any of them:

- `become` does not compose; two in a handler each compute from the entry value
- a `case` arm's assignment escapes from the wildcard arm but not the `true` arm
- a `try` body's assignment escapes, a `catch` body's does not
- a handler's `state` bindings are frozen at entry

When you find another, write the probe, keep it, and put the finding in the
commit message.

**Fail loudly.** Anything the translator cannot handle is `TODO()` carrying the
node. Never emit a plausible fallback — a crash is a work item, wrong output is
a bug that hides. This is why the functional suite is honest: `onlyPasses(blimp(),
...)` in `temper/functional-test-suite/.../FunctionalTestStatus.kt` lists what
is expected to work, and widening that list is how progress is recorded.

**temper-core is written in Blimp**, at
`temper/be-blimp/src/commonMain/resources/lang/temper/be/blimp/temper-core/`.
It is spliced into output that needs it, because Blimp cannot import a second
file. Test it by concatenating it with its test file and running `--test`:

```bash
cat core.blimp core_test.blimp > /tmp/all.blimp && blimp /tmp/all.blimp --test
```

## Commit and PR style

Lead with the surprising fact. Show generated code rather than describing it.
Say what does not work, by name. Explain why the obvious version is wrong —
that is usually the sentence worth having. No marketing language, no emoji.
