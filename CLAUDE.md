# Project guidance

This file provides guidance to AI coding agents when working with code in this repository. It is read by Claude Code as `CLAUDE.md` and by other agents as `AGENTS.md` (a symlink to this file) — keep this as the single source of truth.

## Project

Android custom keyboard (IME) with a compact 3-row QWERTY layout and a built-in Vietnamese Telex engine — Vikey's engine (itself derived from OpenKey), vendored under `app/src/main/cpp/` and compiled into `libvikey.so`. Beyond the keyboard itself there is one settings screen (theme mode, haptics, Smart Telex, double-tap window), which is also the app's launcher entry point. minSdk 21, compileSdk/targetSdk 34, JDK 17.

Toolchain pins: AGP 8.2.2, Kotlin 1.9.22, Gradle 8.5, NDK 27.2.12479018, CMake 3.22.1. Only external dependency is `androidx.core:core-ktx`.

The engine is **GPLv3**, so the combined work is too — see `THIRD-PARTY-NOTICES.md`. The root `LICENSE` still says MIT and must change before any release.

## Build

The `gradlew` script is **not committed** — only `gradle/wrapper/gradle-wrapper.properties` is. CI generates the wrapper (`gradle wrapper --gradle-version 8.5`) before building; do the same locally if `./gradlew` is missing.

The APK carries native code, so the build needs NDK 27.2.12479018 and CMake 3.22.1 (`sdkmanager --install "ndk;27.2.12479018" "cmake;3.22.1"`; AGP can fetch them itself once the SDK licences are accepted). ABIs are `arm64-v8a` and `x86_64` only — the engine typedefs `Uint64` as `unsigned long`, which is 32-bit on `armeabi-v7a`.

```bash
gradle wrapper --gradle-version 8.5   # one-time, if ./gradlew missing
./gradlew assembleDebug               # build debug APK
./gradlew assembleRelease             # R8 build; keeps VikeyDriver (JNI symbol names)
./gradlew lint                        # Android lint
```

Output APK: `app/build/outputs/apk/debug/`.

The only automated tests are the engine's, which build `libvikey.so` for the host from the vendored sources and drive `VikeyDriver.kt` on the JVM:

```bash
JAVA_HOME=/path/to/jdk tools/engine-test/jni-smoke.sh
```

It needs a JDK with `include/jni.h` (a JRE is not enough) plus `g++`, and takes the Kotlin jars from `~/.gradle/caches` — run `./gradlew assembleDebug` once first. `tools/engine-test/README.md` lists what it covers.

CI (`.github/workflows/build.yml`) installs the NDK and CMake, builds `assembleDebug`, runs the engine tests and uploads the APK artifact.

## Architecture

Source files, each with one clear job:

- `app/src/main/java/com/miniqwerty/kb/MiniKeyboardIME.kt` — `InputMethodService` subclass. Owns the engine session and all interaction with the target app via `InputConnection`.
- `app/src/main/java/com/miniqwerty/kb/MiniKeyboardView.kt` — custom `View` that draws the keyboard on a `Canvas` and handles touch. Emits events through the `OnKeyActionListener` interface (defined in this file). No Android XML layouts — everything is drawn.
- `app/src/main/java/com/miniqwerty/kb/VikeyDriver.kt` — `Driver`/`Edit` wrapper over the JNI face of the engine. Exactly one `Driver` may exist per process; the IME creates it lazily and never closes it.
- `app/src/main/cpp/` — the vendored engine (`engine/`, GPLv3), `vikey_jni.cpp` (the JNI surface and the character → key map) and `CMakeLists.txt`. Read `app/src/main/cpp/README.md` before touching any of it — it records the deliberate deviations from upstream.
- `app/src/main/java/com/miniqwerty/kb/MainActivity.kt` — settings screen (plain `Activity`, no AppCompat): theme mode radio group, switches for haptics / Smart Telex / auto-capitalize, double-tap window slider, "enable keyboard" shortcut to `Settings.ACTION_INPUT_METHOD_SETTINGS`. Also the launcher entry point.
- `app/src/main/java/com/miniqwerty/kb/Prefs.kt` — shared preference keys, defaults, and constants shared by the IME and the settings screen (`miniqwerty_kb_prefs`).

### Composing flow

The engine never composes. `Driver.typeCharacter()` / `backspace()` return an `Edit` (delete N characters before the caret, then insert text) computed from the engine's C++ word state, and the IME applies it at once with `deleteSurroundingText` + `commitText`, inside one `beginBatchEdit`/`endBatchEdit`. Text is therefore committed as it is typed — no `setComposingText`, no underline, and nothing is left pending at space, enter or focus loss.

The engine counts its deletions against the text it produced, so the IME mirrors the text before the caret in `lastContext` and keeps it in sync from its own edits (`afterEdit`) instead of reading the field back on every keystroke; a new field, a paste and a caret move re-read it. `applyEdit` also trims the common prefix of the old tail and the new text, so `tooi` + `s` deletes only `oi` and inserts `ói`.

`onUpdateSelection` treats a caret move the IME did not cause as the end of the word: `recentPositions` holds the positions our own edits produced (apps report them back, often several callbacks later) and anything else resets the engine, re-reads `lastContext` and drops the sentence state.

Backspace is the engine's: it removes the last *displayed* letter, not the last key, so `tiếng` + ⌫ gives `tiến`. An active selection is deleted whole first (`commitText("")`), and an empty field falls through to `deleteSurroundingText(1, 0)`.

Double-tap replays the word. `backspace` + retype would delete a letter the first tap only toned (`ba` + `s` → `bá`, so a second tap on that key must give `baz`, not `bz`), so `wordKeys` records the raw keys of the current word and `onReplaceCharacter` re-runs the engine over them with the secondary swapped in, then swaps the displayed word for the result. `wordKeys` is cleared when a non-letter ends the word, and on backspace — there the engine's state no longer matches the keystrokes, so a double-tap falls back to backspace + type.

The numeric and rare-symbol layers (`onDirectCharacter`) end the word and insert the character without the engine: a digit glued to a word is part of it to the engine, which then restores the whole thing raw (`tooi5` → `tooi5`). Their double-tap (`onReplaceDirectCharacter`) deletes the committed character and inserts the symbol.

Password fields bypass the engine entirely — no Telex, no auto switch; the keystroke is committed exactly as pressed.

Space-bar long-press is a cursor mode: horizontal drag moves the caret via `onCursorMove`, which resets the engine (the word ends where the caret was).

Shift is latched (one character), not held: `MiniKeyboardView` uppercases the character before calling `onCharacter`, then auto-releases. Double-tapping Shift toggles sticky caps lock (`capsLockActive`, drawn as ⇪).

Quick double-tap on the same key replaces the character the first tap inserted with the key's secondary character. The window is user-adjustable in settings (`Prefs.KEY_DOUBLE_TAP_MS`, 100–500 ms, default 200). The second tap must be clean (no drift beyond the touch slop) to count as a double-tap — a moved finger is a new gesture. Backspace repeats while held: initial delay 400 ms, then every 60 ms.

Hard keys also flow through the engine: `onKeyDown` intercepts DEL/ENTER/SPACE and routes printable characters into `onCharacter`.

### Telex rules (in the engine)

The engine is Vikey's, so the Telex rules live in `app/src/main/cpp/engine/` — standard Telex: `aw→ă aa→â ee→ê oo→ô ow→ơ uw→ư uow→ươ uiw→ưi dd→đ`, tone keys `s f r x j`, tone placement by Vietnamese orthography (the `ươ` cluster, `gi`/`qu` digraphs, and the oa/oe/uy coda rule), triple-letter undo (`dooor` → `door`), and the doubled-tone-key toggle (`charr` → `char`). `vUseModernOrthography` is off, so placement is the older style: `hòa`, `khỏe`, `thúy` (not `hoà`, `thoại`-style `thuý`).

Behaviour that differs from the engine this app used to ship, all accepted:

- no composing text or underline — every keystroke is committed immediately (above);
- backspace removes the last displayed letter (`tiếng` → `tiến`) rather than the last key;
- `z` removes the tone (`charz` → `cha`) instead of spilling a literal;
- the word list and the English auto switch are the engine's own compiled-in data, not `assets/vi_words.txt`;
- `[` and `]` are plain symbols — upstream maps them to ơ/ư, but this layout types those with `ow`/`uw` and needs both brackets (`vikey_jni.cpp` leaves them unmapped).

### Smart Telex

The settings toggle maps to three engine flags (`MiniKeyboardIME.applyEngineSettings`): on → `vCheckSpelling`, `vRestoreIfWrongSpelling` and auto switch on; off → all three off, i.e. plain Telex (every transform applies, no shape check, no auto switch, so `fix` → `fĩ`). `vTeencode` stays off (upstream hard-codes it on with no UI) and so does `vUseModernOrthography`.

With Smart Telex on, a shape that is not a Vietnamese syllable is restored to the keys typed at the word break, and the engine's auto switch moves between English and Vietnamese as words are typed: `cluster`, `fix`, `for`, `server`, `door`, `bus`, `this`, `carry`, `office`, `password` all stay literal, while `tieengs` → `tiếng` and a Vietnamese word typed right after an English one converts back. A resolved word the engine's word data does not know is also restored (`aw`, `dd`, `uoow` stay as typed).

The keyboard has no VI/EN key, so the language is set to Vietnamese at every field start (`onStartInputView`) and otherwise left to the engine's auto switch; there is no on-screen language indicator.

### Keyboard layout

Defined as `List<List<KeyDef>>` literals in `MiniKeyboardView` — `letterKeys` and `numericKeys`, switched by `currentLayer`. Each key has a `primary` and optional `secondary` character: tap commits primary, quick double-tap replaces it with the secondary letter, a downward flick that clearly leaves the key or a long-press (350 ms) commits the secondary. Character keys without a secondary (vowels) have no secondary action.

Letters layer, 3 rows. Row 1 is 10 columns — `X(Q) W E R T H(Y) U I(P) O ?(!)` — question mark with exclamation below it at the right end. Row 2 is `A S(Z) D F(C) G(V) N(B) J(K) M(L)` at 1 unit each, with ⌫ (1.25 units) pulled out to the far right. The letters pin to row 1's 10-unit grid with a quarter-unit stagger off the left edge (so they keep row 1's exact width and get a natural staggered typing feel); the backspace flushes against row 1's right edge, leaving a half-unit gap after M so a thumb aiming at M can't drift onto it. The layout is QWERTY-familiar by design: `tools/layout_analyzer.py` optimizes effort + λ·displacement from each letter's QWERTY home (λ=0.5), so every key sits at or next to its QWERTY position and the 9 rarest letters (Q Y P Z C V B K L) become double-tap secondaries on the key nearest their QWERTY home. Tone keys X, S, F, R, J sit where Vietnamese Telex typists expect them. A, E, O, D sit on keys without secondaries so the Telex same-key digraphs `aa ee oo dd` stay typeable via quick double-press. Row 3 uses fractional `widthUnits` spans (shift 1.5, `123` 1, space 5, `,(.)` 1, return 1.5) — 10 total units so the row aligns to row 1's column grid, and it is 75% of the standard row height (`effectiveRows` = row count − 1 + 0.75, so both 3-row layers measure 2.75 row-units; the clipboard layer uses the letters-layer count to keep the window height stable). The `,(.)` key (comma, period by double-tap/long-press/flick) sits immediately right of the space bar. `123` switches to the numeric layer; `ABC` switches back.

Numeric layer: row 1 is 10 digits with no secondaries — `1 2 3 4 5 6 7 8 9 0`. Row 2 is 11 units of frequent symbols plus ⌫: `@ ! % : ) - ? = / ]` with double-tap secondaries `~ # $ & ( _ + ; ' [` (`(` under `)`, `[` under `]`, `&` under `:`). Row 3 spans `ABC` (1.5), `=\<` (1), space (6), `.` (1, `,` double-tap), `⏎` (1.5) — 11 total units so the dot matches the row-2 key width. The `.` key sits immediately right of the space bar (mirroring the letters layer's `,(.)`); `=\<` flips to the rare-symbol page. Numeric-layer characters commit directly via `onDirectCharacter` (bypass the engine). Rare symbols (`` ` \ | " < > { } ^ * ``) open on the `=\<` page.

### Touch handling

`MiniKeyboardView` tracks one active pointer (`activePointerId`) and reads every event through that pointer's coordinates. Multi-touch is handled deliberately: fast typing overlaps taps, so when a second finger lands (`ACTION_POINTER_DOWN`) the in-flight gesture is finalized immediately (its tap commits) and tracking rebinds to the new pointer — a key is never lost to an unhandled `ACTION_POINTER_UP`. `beginGesture`/`commitGesture` factor the down/up logic; an unrelated finger lifting is a no-op.

Hit testing is nearest-center with a circular sweet spot per key: radius = half the key's bounding-box diagonal × `HIT_RADIUS_FACTOR` (1.08), which overlaps the gaps between keys so no tap lands dead. Sweet-spot centers are offset from the visual center toward where thumbs actually land — up to `SWEET_SPOT_HEIGHT_FRACTION` (38% of key height; the contact pad sits below the aim) and row-edge keys pulled inward (`SWEET_SPOT_EDGE_PULL`). Visual centers are unchanged.

On `ACTION_MOVE` beyond the touch slop, the held key re-targets to the key under the finger (`findKeyAt`), so the highlight and the released commit follow the finger; drift also cancels the long-press and swaps backspace hold-repeat accordingly. A downward flick commits the secondary only when it clearly leaves the original key — travel ≥ `swipeSlop` (2× touch slop), vertical-dominant, past the key's bottom — so a thumb roll on a normal tap cannot misfire. The swipe stays bound to `originalDownKey` (the key pressed at `ACTION_DOWN`).

Haptics fire on `ACTION_DOWN` for immediate press feedback; the key still commits on release.

### Theme and sizing

The view draws an opaque background (no transparent IME window) in a light, dark, or OLED-black palette. Theme mode is user-selectable via `Prefs.KEY_THEME_MODE` (system / light / dark / black, default system; black is a true-black background for OLED screens — `Prefs.THEME_BLACK` maps to the `PALETTE_OLED` palette whose pixels stay off). `MiniKeyboardView.resolvePaletteFor()` resolves the pref and `refreshTheme()` reapplies colors. The IME calls `refreshTheme()` on configuration change and every `onStartInputView`, so settings changes apply to the next keyboard window; the palette also tints the navigation bar via `MiniKeyboardView.backgroundColor()`. A drag handle strip (14 dp) at the top resizes the keyboard: dragging changes `rowHeightDp` (clamped 30–75 dp, default 46), persisted in `Prefs.KEY_ROW_HEIGHT_DP`, applied on the next `onMeasure`.

Key presses vibrate via `performHapticFeedback` on `ACTION_DOWN` (`KEYBOARD_TAP`; `LONG_PRESS` fires separately on long-press) when `Prefs.KEY_HAPTIC_ENABLED` is on (default). The toggle is re-read in `refreshTheme()` so settings changes apply live.

### IME wiring

`app/src/main/AndroidManifest.xml` declares the service with `BIND_INPUT_METHOD` permission and `MainActivity` as the MAIN/LAUNCHER entry point; the application carries a launcher icon (`mipmap/ic_launcher`, adaptive + legacy densities) and `@string/app_name`. `app/src/main/res/xml/method.xml` defines two subtypes: `en_US` and `vi_VN` (Telex) — the IME itself starts every field in Vietnamese, the subtype is only what the system lists. To test on a device: install, then enable the keyboard in Settings → System → Languages & input (the settings screen has a shortcut button).
