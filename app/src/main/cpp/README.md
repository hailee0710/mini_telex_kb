# Vendored Vikey engine

`engine/` is the processing core of [Vikey](https://github.com/tonyminibot/Vikey) (itself
derived from OpenKey), copied verbatim from upstream commit
`783bf954b318b27a625e5809ef06c091a35282e7` (2026-11-03) and compiled into `libvikey.so`.

| file | role |
|---|---|
| `Engine.cpp`, `Engine.h` | key state machine, tone placement, spelling, auto switch |
| `Vietnamese.cpp/.h` | vowel / consonant / unicode tables |
| `Driver.cpp/.h` | the platform-facing `press()` API and the English-mode buffer |
| `AutoSwitchData.h`, `BigramData.h`, `TeencodeData.h`, `KeepWords.inc` | compiled-in word frequency data |
| `SmartSwitchKey.h` | header only — `SmartSwitchKey.cpp` is not built (upstream's per-app switching is not wired) |
| `platforms/mac.h` | key codes; upstream shares this table with the macOS build |

Not built: `TouchModel.*` (fat-finger autocorrect and suggestions) — it is not wired to
the keyboard yet. `tools/engine-test/jni-smoke.sh` builds these same sources on the host
and runs `VikeyDriver.kt` against them on the JVM.

## Changes from upstream

`vikey_jni.cpp` is the JNI surface, not upstream code; it differs on purpose:

- `keyFor()` maps printable ASCII instead of a dozen punctuation keys, so pressing a
  symbol key no longer resets the word being composed.
- `[` and `]` stay unmapped — upstream maps them to ơ/ư, but this keyboard has no such
  key and uses both brackets as symbols.
- `configure()` also sets `vTeencode`; upstream hard-codes `vTeencode = 1` with no UI.

`Engine.cpp` carries one small deviation, marked `AS_D22_MIN_EN`:

- The auto switch's D22 rule ("a tie right after a clearly English word stays English")
  used to fire for any word the English word data knew, including barely-attested name
  entries. With the keyboard sitting in English mode after an English word, a Telex word
  whose keys happen to be such an entry stayed literal — `server is huw` stayed `huw`
  instead of `hư` (Huw is a Welsh name) and `… tris` stayed `tris` instead of `trí`.
  D22 now needs the current word to score `>= 0` (`AS_D22_MIN_EN`), so real English words
  at a tie still win ("the theme", "of rust") while a name-like entry does not.

## Engine behaviour worth knowing

- **One instance per process.** Word state, previous word and auto-switch undo data live
  in file-scope globals; two `Driver`s in one process corrupt each other. Measured: after
  nine `Driver`s were created and closed, `dooor` resolved to `dỏo` instead of `door` in
  the tenth; one long-lived `Driver` with `resetContext()` between words always matched.
  An IME service recreated in the same process must reuse or reset the single instance.
- **No composing text.** `press()` returns `(deleteCount, text)`; the host applies it with
  `deleteSurroundingText` + `commitText`. The engine never sees the field contents, only a
  cached copy the host keeps in sync.
- `vCheckSpelling` is re-run at `insertKey`, `backspace`, word break and auto switch — not
  after every key. Per key the engine runs `checkGrammar` only.
- `MAX_BUFF` is 32 keys per word.

## Licence

GPLv3 (see `engine/LICENSE`), which makes the whole app GPLv3 — the repository's root
`LICENSE` carries the same licence.
