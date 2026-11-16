# Engine tests

`jni-smoke.sh` is the regression suite for the Telex engine. It builds `libvikey.so` for
the host from the **vendored** sources (`app/src/main/cpp/`) and runs `VikeyDriver.kt`
against it on the JVM, driving it exactly the way `MiniKeyboardIME` does.

```bash
JAVA_HOME=/path/to/jdk ./jni-smoke.sh     # needs include/jni.h and g++
```

The Kotlin jar comes from `~/.gradle/caches`, so run `./gradlew assembleDebug` once
first. Exit status is non-zero when a case fails.

## What it covers

`jni-smoke.kt` reimplements the IME's side of the contract — `applyEdit` with the
common-prefix trim, the backspace, the numeric-layer reset and the double-tap replay — over
a `StringBuilder` standing in for the editor. That way a broken bridge, a wrong JNI
signature or a change in the engine's `Edit` packing fails here rather than on a phone.

| group | cases |
|---|---|
| Telex core | `tieengs` → tiếng, `nguowif` → người, `hoaf` → hòa, `quar` → quả, `masy` → máy |
| English fallback | `cluster`, `for`, `door` (triple-letter undo) stay literal |
| Auto-capitalize | first letter of a sentence, `aa` → â |
| Backspace | mid-word, through a tone key, down to empty, at an empty field |
| Double-tap | punctuation (`x,` → `x.`), a tone key, a mid-word vowel, word start |
| Layers | a digit after a word (`tôi5`), a replaced digit, punctuation as a word break |
| Auto switch | VI → EN → VI across a sentence; a Telex word whose keys are also a rare English entry (`huw`, `tris`) still converts after an English word |

Known engine behaviour pinned by the suite (not bugs to "fix" in the test):

- backspace drops the last **displayed letter**, not the last key: `tiếng` + ⌫ → `tiến`;
- with Smart Telex off the engine accents everything (`fix` → `fĩ`), which is what the
  toggle means here.

## Notes

- One `Driver` per process: the engine keeps its word state in C++ globals, so the test
  creates one and calls `resetContext()` between cases, as the IME does.
- The test does not exercise the `InputConnection` itself — only the edits the IME would
  apply to it. Device-only behaviour (caret callbacks, app-specific editors) still needs
  a phone.
