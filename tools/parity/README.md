# Parity harness — Vikey engine vs `TelexProcessor`

Answers one question with numbers instead of opinions: **if mini_qwerty_kb swapped its
Telex engine for Vikey's `Engine/`, what would actually change?**

It compiles Vikey's C++ engine natively (it has no Android dependencies), drives it the
way an IME does — one key press at a time, applying each `(deleteCount, text)` edit to a
simulated text field — and diffs the result against `TelexProcessor.resolve()`.

```
./setup.sh          # clone Vikey, build harness + Kotlin dumper
./gen_corpus.py     # write corpus_hand.txt (curated) + corpus_matrix.txt (~28k syllables)
./compare.py corpus_hand.txt
```

`compare.py` prints a class summary, a markdown table of disagreements, and writes the
full result to `diff.csv`. The classes compare Vikey against `TelexProcessor` **live**
mode (no dictionary), so `mini(commit)` differences are dictionary-only.

## Files

| file | job |
|---|---|
| `harness.cpp` | drives `VikeyDriver::press()` from stdin, prints the field after a trailing space |
| `Dump.kt` | prints `raw \t live \t commit` from `TelexProcessor` |
| `gen_corpus.py` | curated cases + a systematic onset × nucleus × coda × tone matrix |
| `compare.py` | runs both, classifies and tabulates the differences |
| `.vikey/` | Vikey checkout (gitignored) |

## Flags that matter

Vikey's Android build hard-codes several engine settings that `TelexProcessor` has no
equivalent for, so a fair comparison has to state them:

| flag | why |
|---|---|
| `--modern=0` | keeps `hòa/khỏe/thúy`; Vikey's default (`1`) gives `hoà/thuý` |
| `--teencode=0` | Vikey's default is `1` (`z` = `d`, `gòy`…), with no Android UI to turn it off |
| `--autoswitch=1` | the English/Vietnamese auto-switch; `0` shows what the pure Telex engine does |
| `--jni-keys` | emulate the current `vikey_jni.cpp` `keyFor()`: `[`/`]` unmapped, other characters reset the word |

Example comparisons:

```
./compare.py corpus_hand.txt --modern=0 --teencode=0 --autoswitch=1
./compare.py corpus_hand.txt --modern=0 --teencode=0 --jni-keys
./compare.py corpus_matrix.txt --out diff_matrix.csv
```

## Baseline result (2026-11, Vikey @ clone date)

Curated corpus, `--modern=0 --teencode=0 --autoswitch=1`: **112/118 engine match**
(104/118 including mini's dictionary at commit). Systematic matrix: **20 357/28 566
match (71 %)**; the differences are dominated by Vikey *rejecting* impossible
tone + coda combinations that `TelexProcessor` happily accents (`acf` → Vikey keeps
`acf`, mini shows `àc`), plus `qu`-digraph and `uow` placement where Vikey is closer to
standard orthography (`quoác` vs `quóac`, `thuở` vs `thướ`).

## Caveats

- The harness drives the engine directly; it does **not** exercise the `InputConnection`
  layer (composing text vs commit-only edits), which is where the real porting risk is.
- `VikeyDriver` keeps engine state in file-scope globals: one driver per process. The
  harness creates exactly one, like the IME does.
- Vikey is GPLv3. These scripts only read its source; embedding `Engine/` in the app is
  the licensing decision, not a tooling one.
