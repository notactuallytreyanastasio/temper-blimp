"""Draws the small Piet program that piet-in-piet runs, as inner.ppm, and
prints it as the letters piet-in-piet.temper.md embeds.

The program is one row, y = 1, read left to right, between two black rows.
It starts at (0, 0), which belongs to its first block, and ends in a block
three codels tall: entered from the left at its middle, every way out of it
is black or the edge, so the interpreter halts there.

Letters: a-r are the 18 colours, hue-major (red, yellow, green, cyan, blue,
magenta) and lightness-minor (light, normal, dark); W is white, K black.
"""
import sys

RGB = [
    (0xFF, 0xC0, 0xC0), (0xFF, 0x00, 0x00), (0xC0, 0x00, 0x00),
    (0xFF, 0xFF, 0xC0), (0xFF, 0xFF, 0x00), (0xC0, 0xC0, 0x00),
    (0xC0, 0xFF, 0xC0), (0x00, 0xFF, 0x00), (0x00, 0xC0, 0x00),
    (0xC0, 0xFF, 0xFF), (0x00, 0xFF, 0xFF), (0x00, 0xC0, 0xC0),
    (0xC0, 0xC0, 0xFF), (0x00, 0x00, 0xFF), (0x00, 0x00, 0xC0),
    (0xFF, 0xC0, 0xFF), (0xFF, 0x00, 0xFF), (0xC0, 0x00, 0xC0),
]
WHITE, BLACK = 18, 19
# (hue steps, lightness steps) for each command
OPS = {
    "push": (0, 1), "pop": (0, 2), "add": (1, 0), "sub": (1, 1), "mul": (1, 2),
    "div": (2, 0), "mod": (2, 1), "not": (2, 2), "gt": (3, 0), "ptr": (3, 1),
    "sw": (3, 2), "dup": (4, 0), "roll": (4, 1), "inn": (4, 2), "inc": (5, 0),
    "outn": (5, 1), "outc": (5, 2),
}


def step(colour, op):
    dh, dl = OPS[op]
    return ((colour // 3 + dh) % 6) * 3 + (colour % 3 + dl) % 3


def program(text):
    """Pushes and arithmetic for each character: a * b + r, all small."""
    ops = []
    for ch in text:
        n = ord(ch)
        a = max(2, int(n ** 0.5))
        b, r = divmod(n, a)
        ops += [("push", a), ("push", b), ("mul", 1)]
        if r:
            ops += [("push", r), ("add", 1)]
        ops.append(("outc", 1))
    return ops


def draw(text):
    ops = program(text)
    row = []  # colours along y = 1
    colour = 0
    for i, (op, width) in enumerate(ops):
        # A push pushes the size of the block it leaves. The first block
        # also holds the start codel above its first codel, so it is one
        # codel shorter along the row.
        row += [colour] * (width - 1 if i == 0 else width)
        colour = step(colour, op)
    row.append(colour)  # the block the last command enters: the trap
    w = len(row) + 1
    grid = [[BLACK] * w for _ in range(3)]
    grid[1][: len(row)] = row
    grid[0][0] = row[0]  # the start codel, (0, 0), joins the first block
    t = len(row) - 1
    grid[0][t] = grid[2][t] = row[t]  # the trap, three tall
    return grid


if __name__ == "__main__":
    grid = draw(sys.argv[1] if len(sys.argv) > 1 else "Piet!\n")
    with open("inner.ppm", "wb") as f:
        f.write(b"P6\n%d %d\n255\n" % (len(grid[0]), len(grid)))
        for line in grid:
            for c in line:
                f.write(bytes((0, 0, 0) if c == BLACK else (255, 255, 255) if c == WHITE else RGB[c]))
    for line in grid:
        print("".join("K" if c == BLACK else "W" if c == WHITE else chr(ord("a") + c) for c in line))
