// Prints Blimp's own token stream for a file: one "kind<TAB>lexeme" per line,
// newlines skipped (the highlighter keeps them as trivia). \n and \t inside a
// lexeme are escaped so a line is always one token. The lexer is a copy of
// blimp/chunks/lang/src/lexer.zig and token.zig, unmodified.
const std = @import("std");
const Lexer = @import("lexer.zig").Lexer;

pub fn main(init: std.process.Init.Minimal) !void {
    var arena_state = std.heap.ArenaAllocator.init(std.heap.page_allocator);
    const a = arena_state.allocator();
    var threaded: std.Io.Threaded = .init(a, .{ .argv0 = .init(init.args), .environ = init.environ });
    const io = threaded.io();
    const args = try init.args.toSlice(a);
    const src = try std.Io.Dir.cwd().readFileAlloc(io, args[1], a, .limited(64 << 20));
    var buf: [1 << 16]u8 = undefined;
    var w = std.Io.File.stdout().writer(io, &buf);
    const out = &w.interface;
    var lx = Lexer.init(src);
    while (true) {
        const t = lx.next();
        if (t.kind == .eof) break;
        if (t.kind == .newline) continue;
        try out.writeAll(@tagName(t.kind));
        try out.writeAll("\t");
        for (t.lexeme) |ch| switch (ch) {
            '\n' => try out.writeAll("\\n"),
            '\t' => try out.writeAll("\\t"),
            else => try out.writeByte(ch),
        };
        try out.writeAll("\n");
    }
    try out.flush();
}
