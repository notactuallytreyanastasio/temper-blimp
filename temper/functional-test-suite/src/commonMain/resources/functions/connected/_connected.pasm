# Piet assembly for the @connected declarations in connected.temper.md.
#
# be-piet assembles each routine as the body of the declaration with the
# same name. An optional Int parameter is a nullable Int, which be-piet
# represents as a box (null is -1), so `bonus heap0` reads the value in it.

# i + j + bonus, an omitted bonus being 0
fn sum(i, j, bonus):
    i j add
    bonus isnull if 0 else bonus heap0 end
    add ret

# Hidden's one field, i, is the cell after its class id.
fn prod(hidden, j):
    hidden heap1 j mul ret

# -1 for a null string, which is what the other backends' versions answer.
# A string keeps its length in its first cell.
fn length(s):
    s isnull if -1 else s heap0 end
    ret
