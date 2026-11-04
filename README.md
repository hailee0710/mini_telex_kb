# Mini Qwerty Keyboard

A compact 3-row QWERTY Android keyboard (IME) with a built-in Vietnamese **Telex** engine and an English-aware **Smart Telex** mode. The engine is [Vikey](https://github.com/tonyminibot/Vikey)'s (based on OpenKey), compiled from source into the app.

**Zero permissions. Zero network.** The keyboard reads only what you type and sends it nowhere.

- [Tiếng Việt](README.vi.md)

## Features

- **Smart Telex** — type Vietnamese with Telex, English words type literally. A syllable-shape check plus the engine's word data and auto switch keep English looking English; Vietnamese typed after it converts back.
- **Full Telex support** — all 5 tone keys, every vowel digraph, correct orthographic tone placement, mid-word tone keys, triple-letter undo.
- **Auto-capitalize** — the first letter of a sentence is uppercased, and Shift lights up to show it.
- **Compact 3-row QWERTY** — the 9 rarest letters are quick double-tap secondaries on their QWERTY home keys; tone keys `X S F R J` sit where Telex typists expect them.
- **Numeric/symbol layer** with symbol double-tap secondaries.
- **Light/dark/system theme**, haptics toggle, adjustable keyboard height (drag handle), adjustable double-tap window.
- **Tap accuracy** — nearest-key hit targets with thumb-offset correction, press feedback on key-down, and multi-touch handling so fast typing never drops a key.
- **No special permissions** — nothing to grant, nothing to leak.

## Install

Download the latest `app-release.apk` from the [Releases](https://github.com/hailee0710/mini_qwerty_kb/releases) page and open it on your device.

Then enable the keyboard: **Settings → System → Languages & input → On-screen keyboard → Mini Qwerty** (the app's settings screen has a one-tap shortcut).

## Telex input

Tone keys:

| Key | Tone | Example |
|-----|------|---------|
| `s` | sắc | `masy` → `máy` |
| `f` | huyền | `maf` → `mà` |
| `r` | hỏi | `mar` → `mả` |
| `x` | ngã | `max` → `mã` |
| `j` | nặng | `maj` → `mạ` |

Vowel digraphs (same key twice, or the digraph sequence):

```
aa → â    ee → ê    oo → ô    aw → ă
ow → ơ    uw → ư    dd → đ    uow → ươ
```

Special nuclei: `iê` = `iee`, `uô` = `uoo`, `yê` = `yee`, `uâ` = `uaa`, `uyê` = `uyee`, `ươ` = `uow`.

Type the digraph's last letter a third time to undo it — the word becomes literal:
`dooor` → `door`. English words need no undo: with Smart Telex on they are restored to the
keys typed when the word ends (`good`, `for`, `bus` stay as typed).

Same tone key twice toggles the tone off; a different tone key replaces the previous one.

## Smart Telex

With Smart Telex on, an output that is not a valid Vietnamese syllable is restored to the keys
typed as soon as the word ends, so `cluster`, `good`, `pool`, `fix`, `door`, `bus`, `this`,
`carry`, `office` and `password` all type literally. The engine's auto switch also moves
between Vietnamese and English by itself — an English word typed after Vietnamese stays
English, and the next Vietnamese word converts back.

With Smart Telex off the keyboard is plain Telex: every transform applies and there is no
shape check or auto switch, so `fix` becomes `fĩ`.

## Keyboard layout

- **Row 1:** `X(Q) W(?) E R T H(Y) U I(P) O ,(.)` — comma top, dot below.
- **Row 2:** `A S(Z) D F(C) G(V) N(B) J(K) M(L)` + backspace.
- **Row 3:** shift, `123`, space, return.
- **Numeric layer:** digits, `@ ! % : ) - ? = / ]` with double-tap secondaries `~ # $ & ( _ + ; ' [`; plus the `.` key (`,` on double-tap).
- **Clipboard:** the strip above row 1 opens with a clipboard button at its left end — tap it to open or close the clipboard layer. The strip shows whenever the clipboard history has an item.

Quick double-tap on a key replaces the last character with the key's secondary. Long-press (350 ms) or a deliberate swipe-down commits the secondary. Double-tap Shift for caps lock (⇪). Backspace repeats while held. Fast typing is safe: overlapping taps (the next finger landing before the previous lifts) still register both keys, and a thumb roll on a tap won't fire a swipe or a double-tap.

## Settings

- Theme: system / light / dark / black (OLED)
- Haptics on/off
- Smart Telex on/off
- Auto-capitalize on/off
- Clipboard suggestion strip on/off
- Double-tap window (100–500 ms)

## Build

Prerequisites: JDK 17, Android SDK, Gradle 8.5, and — because the engine is native code — NDK 27.2.12479018 with CMake 3.22.1 (`sdkmanager --install "ndk;27.2.12479018" "cmake;3.22.1"`). ABIs are `arm64-v8a` and `x86_64`.

```bash
gradle wrapper --gradle-version 8.5   # if ./gradlew missing (not committed)
./gradlew assembleDebug               # debug APK
./gradlew bundleRelease               # release AAB for publishing

# engine tests: builds libvikey.so for the host and drives the Kotlin bridge on the JVM
JAVA_HOME=/path/to/jdk tools/engine-test/jni-smoke.sh
```

No JDK 17 installed? `tools/build-docker.sh [gradle task…]` runs the same build in a
container — JDK 17 and Gradle 8.5 live in `tools/docker/Dockerfile`, while your repo, Gradle
cache and Android SDK are bind-mounted, so nothing else has to change.

The engine test needs a JDK with `include/jni.h` (not just a JRE) and `g++`; run
`./gradlew assembleDebug` once first so its Kotlin jars are in the Gradle cache.

Release signing reads `miniqwerty.*` properties from `~/.gradle/gradle.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without them the release build is unsigned, so plain clones still build.

Output APKs: `app/build/outputs/apk/`, AAB: `app/build/outputs/bundle/release/`.

## Privacy

No `uses-permission` entries in the manifest. No internet permission, no telemetry, no analytics. Keystrokes never leave the device.

## Engine and license

The Vietnamese engine in `app/src/main/cpp/` is [Vikey](https://github.com/tonyminibot/Vikey)'s,
itself derived from [OpenKey](https://github.com/tuyenvm/OpenKey), vendored verbatim at
upstream commit `783bf95`.

This app as a whole is **GPLv3** — see [LICENSE](LICENSE) and
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). You may use, study, change and
redistribute it under the same licence, and any distributed build must come with its
complete corresponding source.
