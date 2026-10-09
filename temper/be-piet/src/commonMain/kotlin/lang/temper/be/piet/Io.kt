package lang.temper.be.piet

import lang.temper.be.piet.PietOp.Add
import lang.temper.be.piet.PietOp.Divide
import lang.temper.be.piet.PietOp.Duplicate
import lang.temper.be.piet.PietOp.Greater
import lang.temper.be.piet.PietOp.InNumber
import lang.temper.be.piet.PietOp.Mod
import lang.temper.be.piet.PietOp.Multiply
import lang.temper.be.piet.PietOp.Not
import lang.temper.be.piet.PietOp.OutChar
import lang.temper.be.piet.PietOp.Subtract

/*
 * A Piet program's only way out is stdout and its only way in is stdin, so
 * anything else it needs, today's date or an HTTP response, it asks for.
 * A request is a line that starts with [REQUEST_MARKER]; [IO_SCRIPT] runs
 * npiet with its stdin on a FIFO, passes every other line through as
 * output, and answers each request on the FIFO as whitespace-separated
 * numbers, which the program reads with in(number). npiet flushes its
 * output before it blocks on input, so a request is always seen before
 * its answer is awaited.
 *
 *     \u0001DATE                      ->  2026 10 08
 *     \u0001HTTP M U B T              ->  status, then two strings
 *
 * M, U, B and T are the method, URL, body and content type, each written
 * as the octal escapes of its UTF-8 bytes (`\150\164...`) so that it is one
 * word whatever it holds, which the script's printf turns back into bytes;
 * a null body or type is `-` and a present one has a leading `=`, so that
 * "" is still a word. A string comes back as its UTF-8 byte count, or -1
 * for null, and then the bytes. Status 0 means the request failed.
 */
const val REQUEST_MARKER = "\u0001"

/** The file [IO_SCRIPT] is written to, beside the images. */
const val IO_FILE = "io.sh"

val IO_SCRIPT = """
    |#!/bin/sh
    |# Runs a be-piet image, answering its requests: usage `sh io.sh <npiet args>`.
    |# A request is a line starting with \001, answered on npiet's stdin; see
    |# Io.kt in be-piet for the protocol. Every other line is output.
    |set -u
    |dir=${'$'}(mktemp -d) || exit 1
    |trap 'rm -rf "${'$'}dir"' EXIT
    |mkfifo "${'$'}dir/in" || exit 1
    |SOH=${'$'}(printf '\001')
    |NL='
    |'
    |
    |# A string as its byte count and bytes; a missing file is null.
    |bytes() {
    |  if [ -f "${'$'}1" ]; then
    |    wc -c < "${'$'}1"
    |    od -An -v -tu1 "${'$'}1"
    |  else
    |    echo -1
    |  fi
    |}
    |
    |http() {
    |  rm -f "${'$'}dir/body" "${'$'}dir/req" "${'$'}dir/type"
    |  method=${'$'}(printf "${'$'}1")
    |  url=${'$'}(printf "${'$'}2")
    |  set -- "${'$'}3" "${'$'}4" -s -o "${'$'}dir/body" -w '%{http_code}\n%{content_type}' -X "${'$'}method"
    |  if [ "${'$'}1" != - ]; then
    |    printf "${'$'}{1#=}" > "${'$'}dir/req"
    |    set -- "${'$'}@" --data-binary "@${'$'}dir/req"
    |  fi
    |  if [ "${'$'}2" != - ]; then
    |    set -- "${'$'}@" -H "Content-Type: ${'$'}(printf "${'$'}{2#=}")"
    |  fi
    |  shift 2
    |  if meta=${'$'}(curl "${'$'}@" "${'$'}url") && [ "${'$'}{meta%%"${'$'}NL"*}" != 000 ]; then
    |    type=${'$'}{meta#*"${'$'}NL"}
    |    [ -z "${'$'}type" ] || printf '%s' "${'$'}type" > "${'$'}dir/type"
    |    echo "${'$'}{meta%%"${'$'}NL"*}"
    |    bytes "${'$'}dir/type"
    |    [ -f "${'$'}dir/body" ] || : > "${'$'}dir/body"
    |    bytes "${'$'}dir/body"
    |  else
    |    echo 0
    |  fi
    |}
    |
    |npiet "${'$'}@" < "${'$'}dir/in" | {
    |  exec 4> "${'$'}dir/in"
    |  while :; do
    |    if IFS= read -r line; then end="${'$'}NL"; else [ -n "${'$'}line" ] || break; end=; fi
    |    # Output before a request on the same line is still output.
    |    out=${'$'}{line%%"${'$'}SOH"*}
    |    request=${'$'}{line#"${'$'}out"}
    |    case "${'$'}request" in
    |      "${'$'}SOH"DATE)
    |        printf '%s' "${'$'}out"
    |        date -u '+%Y %m %d' >&4 ;;
    |      "${'$'}SOH"HTTP\ *)
    |        printf '%s' "${'$'}out"
    |        # shellcheck disable=SC2086 # the words are escapes, never globs
    |        http ${'$'}{request#"${'$'}SOH"HTTP } >&4 ;;
    |      *)
    |        printf '%s%s' "${'$'}line" "${'$'}end" ;;
    |    esac
    |    [ -n "${'$'}end" ] || break
    |  done
    |}
    |""".trimMargin()

private fun Emitter.outText(text: String) {
    for (c in text) {
        const(c.code)
        op(OutChar)
    }
}

/** Asks for the date: three numbers, year, month and day, follow on stdin. */
fun Emitter.requestDate() = outText("${REQUEST_MARKER}DATE\n")

/** `[b] -> []`, writing byte b as an octal escape. */
private fun Emitter.outOctal() {
    outText("\\")
    op(Duplicate)
    const(64)
    op(Divide)
    const('0'.code)
    op(Add)
    op(OutChar)
    op(Duplicate)
    const(8)
    op(Divide)
    const(8)
    op(Mod)
    const('0'.code)
    op(Add)
    op(OutChar)
    const(8)
    op(Mod)
    const('0'.code)
    op(Add)
    op(OutChar)
}

/** `[s] -> []`, writing a string as the octal escapes of its UTF-8 bytes. */
val Runtime.writeEscaped: Label get() = routine("write_escaped", 1) {
    begin(2)
    val s = param(0)
    val i = param(1)
    val cp = param(2)
    // [lead + cp / 64^k % 64, applied to the continuation bytes]
    fun byte(base: Int, shift: Int, mask: Boolean) {
        frame.load(cp)
        if (shift > 0) {
            e.const(1 shl shift)
            e.op(Divide)
        }
        if (mask) {
            e.const(64)
            e.op(Mod)
        }
        if (base != 0) {
            e.const(base)
            e.op(Add)
        }
        e.outOctal()
    }
    fun below(limit: Int) = {
        e.const(limit)
        frame.load(cp)
        e.op(Greater)
    }
    e.const(0)
    frame.store(i)
    e.whileLoop(label("chars").hint, {
        field(s, 0)
        frame.load(i)
        e.op(Greater)
    }) {
        frame.load(s)
        frame.load(i)
        e.op(Add)
        frame.heapLoad(1)
        frame.store(cp)
        e.ifElse(label("one").hint, below(0x80), { byte(0, 0, false) }) {
            e.ifElse(label("two").hint, below(0x800), {
                byte(0xC0, 6, false)
                byte(0x80, 0, true)
            }) {
                e.ifElse(label("three").hint, below(0x10000), {
                    byte(0xE0, 12, false)
                    byte(0x80, 6, true)
                    byte(0x80, 0, true)
                }) {
                    byte(0xF0, 18, false)
                    byte(0x80, 12, true)
                    byte(0x80, 6, true)
                    byte(0x80, 0, true)
                }
            }
        }
        frame.load(i)
        e.const(1)
        e.op(Add)
        frame.store(i)
    }
    ret(false)
}

/**
 * `[] -> [string or null]`, reading a byte count and that many UTF-8 bytes
 * from stdin. Bytes that are not UTF-8 fail the call.
 */
val Runtime.readString: Label get() = routine("read_string", 0) {
    begin(4)
    val left = param(0)
    val header = param(1)
    val cp = param(2)
    val more = param(3)
    e.op(InNumber)
    frame.store(left)
    e.ifElseValue(label("null").hint, {
        e.const(0)
        frame.load(left)
        e.op(Greater)
    }, { e.const(PietTranslator.NULL) }) {
        e.const(0)
        frame.alloc(1)
        frame.store(header)
        // [] -> [b], counting it off.
        fun read() {
            e.op(InNumber)
            frame.load(left)
            e.const(1)
            e.op(Subtract)
            frame.store(left)
        }
        // [ok] -> [], failing the call unless ok.
        fun require() {
            val ok = label("ok")
            e.branch(ok, failLanding(e.depth - 1))
            e.startBlock(ok)
        }
        // cp holds the lead byte; keep what it contributes and how many follow.
        fun lead(base: Int, count: Int) {
            frame.load(cp)
            e.const(base)
            e.op(Subtract)
            frame.store(cp)
            e.const(count)
            frame.store(more)
        }
        fun cpBelow(limit: Int) = {
            e.const(limit)
            frame.load(cp)
            e.op(Greater)
        }
        e.whileLoop(label("bytes").hint, {
            frame.load(left)
            e.const(0)
            e.op(Greater)
        }) {
            read()
            frame.store(cp)
            cpBelow(0xF8)()
            require()
            e.ifElse(label("ascii").hint, cpBelow(0x80), { lead(0, 0) }) {
                // A lead byte is at least 0xC0.
                frame.load(cp)
                e.const(0xBF)
                e.op(Greater)
                require()
                e.ifElse(label("two").hint, cpBelow(0xE0), { lead(0xC0, 1) }) {
                    e.ifElse(label("three").hint, cpBelow(0xF0), { lead(0xE0, 2) }) { lead(0xF0, 3) }
                }
            }
            e.whileLoop(label("continuation").hint, {
                frame.load(more)
                e.const(0)
                e.op(Greater)
            }) {
                read()
                // 0x80 <= c < 0xC0
                e.op(Duplicate)
                e.const(0x7F)
                e.op(Greater)
                require()
                e.op(Duplicate)
                e.const(0xC0)
                e.swap()
                e.op(Greater)
                require()
                e.const(0x80)
                e.op(Subtract)
                frame.load(cp)
                e.const(64)
                e.op(Multiply)
                e.op(Add)
                frame.store(cp)
                frame.load(more)
                e.const(1)
                e.op(Subtract)
                frame.store(more)
            }
            frame.load(cp)
            frame.append()
        }
        frame.fillLength { frame.load(header) }
        frame.load(header)
        this@readString.internTop(frame)
    }
    ret(true)
    end()
}

/** A NetResponse is a block `status, contentType, bodyContent`, the last a kept promise. */
const val NET_STATUS = 0
const val NET_CONTENT_TYPE = 1
const val NET_BODY = 2

/**
 * `[url, method, body, type] -> [promise of a response]`: std/net's
 * sendRequest. The request is answered before this returns, so the
 * promise is already settled, kept, or broken if the request failed.
 */
val Runtime.netSend: Label get() {
    val write = writeEscaped
    val read = readString
    val settle = promiseSettle
    return routine("net_send", 4) {
        begin(4)
        val (url, method, body, type) = (0..3).map { param(it) }
        val promise = param(4)
        val contentType = param(5)
        val bodyPromise = param(6)
        val status = param(7)
        e.outText("${REQUEST_MARKER}HTTP ")
        call(frame, write, 1, false) { frame.load(method) }
        e.outText(" ")
        call(frame, write, 1, false) { frame.load(url) }
        for (optional in listOf(body, type)) {
            e.outText(" ")
            e.ifElse(label("absent").hint, {
                frame.load(optional)
                e.const(PietTranslator.NULL)
                e.op(Subtract)
                e.op(Not)
            }, { e.outText("-") }) {
                e.outText("=")
                call(frame, write, 1, false) { frame.load(optional) }
            }
        }
        e.outText("\n")
        frame.newPromise()
        frame.store(promise)
        e.op(InNumber)
        frame.store(status)
        e.ifElse(label("failed").hint, {
            frame.load(status)
            e.op(Not)
        }, {
            call(frame, settle, 3, false) {
                frame.load(promise)
                e.const(PROMISE_BROKEN)
                e.const(0)
            }
        }) {
            call(frame, read, 0, true) {}
            frame.store(contentType)
            frame.newPromise()
            frame.store(bodyPromise)
            call(frame, settle, 3, false) {
                frame.load(bodyPromise)
                e.const(PROMISE_KEPT)
                call(frame, read, 0, true) {}
            }
            call(frame, settle, 3, false) {
                frame.load(promise)
                e.const(PROMISE_KEPT)
                frame.load(status)
                frame.load(contentType)
                frame.load(bodyPromise)
                frame.alloc(3)
            }
        }
        frame.load(promise)
        ret(true)
    }
}
