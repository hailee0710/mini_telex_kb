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
the keyboard yet. `tools/parity/harness.cpp` builds the same sources on the host, which
is how the engine's behaviour is diffed against the old `TelexProcessor`.

## Changes from upstream

Only `vikey_jni.cpp` (in the parent directory) differs:

- `keyFor()` maps printable ASCII instead of a dozen punctuation keys, so pressing a
  symbol key no longer resets the word being composed.
- `[` and `]` stay unmapped — upstream maps them to ơ/ư, but this keyboard has no such
  key and uses both brackets as symbols.
- `configure()` also sets `vTeencode`; upstream hard-codes `vTeencode = 1` with no UI.

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

GPLv3 (see `engine/LICENSE`). Combined with this app it makes the whole work GPLv3 —
the repository's `LICENSE` still says MIT and must change before distribution.
