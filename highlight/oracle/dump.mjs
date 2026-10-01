// The JS translation of the highlighter, run over one file: prints what
// `dump` returns, in the same format as dump.zig.
import { readFileSync } from "node:fs";
import { dump } from "../temper.out/js/blimp-highlight/index.js";
process.stdout.write(dump(readFileSync(process.argv[2], "utf8")));
