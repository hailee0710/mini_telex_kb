#!/usr/bin/env python3
"""Generates the parity corpus.

  corpus_hand.txt   curated cases, one Telex sequence per line (readable diffs)
  corpus_matrix.txt systematic onset x nucleus x coda x tone combinations (coverage)
"""
import itertools
import pathlib

HAND = """\
# ── documented Telex digraphs / triples ──────────────────────────────
aw
aa
ee
oo
ow
uw
uow
uiw
dd
# ── documented undo (triple letter) forms ────────────────────────────
dooor
goood
uww
oww
uoww
forr
buss
thiss
# ── mid-word tone keys ───────────────────────────────────────────────
masy
mast
mays
# ── tone toggle / spill ──────────────────────────────────────────────
char
charr
charz
# ── English words that must survive ──────────────────────────────────
fix
pool
cluster
good
zoo
this
door
bus
office
message
carry
terrible
password
TestFlight
hello
world
keyboard
android
github
opencode
email
server
delete
return
shift
# ── Vietnamese words (tone-placement rules 1-7) ──────────────────────
tieengs
uoongs
tuooir
truowngf
dduowngf
nguowif
duowcj
giowf
gias
giuwax
quar
quas
quees
quyeens
hoaf
hoas
hoangf
khoer
thuys
suyst
toans
khoets
nguyeenx
chuyeenj
ddaay
ddang
tooi
toois
toif
toir
toix
tooij
bao
baos
cao
caos
mua
muas
cuar
khuya
khuyar
nghieeng
nghieengs
hieeur
hieeus
yeeru
yeer
# ── late / odd w placement ───────────────────────────────────────────
truotw
truotwj
trw
nhw
hew
new
view
guiwr
guwir
quowf
uowm
uow
# ── dictionary-miss cases (CLAUDE.md limitation) ─────────────────────
gieengx
nghieengx
# ── case + mixed ─────────────────────────────────────────────────────
Viet
VIET
Tieengs
Hoaf
123
abc123
a
b
xyz
"""

ONSETS = ["", "b", "c", "ch", "d", "dd", "g", "h", "k", "kh", "l", "m", "n", "ng", "nh",
          "ph", "qu", "s", "t", "th", "tr", "v", "x"]
NUCLEI = ["a", "aw", "aa", "e", "ee", "i", "o", "oo", "ow", "u", "uw", "uow", "iee", "uoo",
          "yee", "uaa", "uyee", "uiw", "oa", "oe", "uy", "ia", "ua"]
CODAS = ["", "c", "ch", "m", "n", "ng", "nh", "p", "t"]
TONES = ["", "s", "f", "r", "x", "j"]

out = pathlib.Path(__file__).parent
(out / "corpus_hand.txt").write_text(HAND, encoding="utf-8")

rows = [f"{o}{n}{c}{t}" for o, n, c, t in itertools.product(ONSETS, NUCLEI, CODAS, TONES)]
(out / "corpus_matrix.txt").write_text("\n".join(rows) + "\n", encoding="utf-8")
print(f"hand={sum(1 for l in HAND.splitlines() if l and not l.startswith('#'))} matrix={len(rows)}")
