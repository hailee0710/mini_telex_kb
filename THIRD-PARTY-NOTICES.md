# Licence and third-party notices

Mini Qwerty Keyboard — Copyright (C) 2026 Hai Lee — is free software: you can
redistribute it and/or modify it under the terms of the **GNU General Public License v3**
(see `LICENSE`), with no warranty.

## Vikey / OpenKey engine — GPLv3

`app/src/main/cpp/engine/` is the processing core of
[Vikey](https://github.com/tonyminibot/Vikey), itself derived from
[OpenKey](https://github.com/tuyenvm/OpenKey). Copied from upstream commit
`783bf954b318b27a625e5809ef06c091a35282e7` (2026-11-03) and built into `libvikey.so`.

The engine is licensed under the **GNU General Public License v3**; its copy of the licence
is at `app/src/main/cpp/engine/LICENSE`.

### What this means for the app

The app links GPLv3 code, so the **combined work is GPLv3**. Distributing it (a Play Store
release, an APK on GitHub, a build handed to someone) requires:

1. shipping it under GPLv3 — done, the root `LICENSE` is the GPLv3 text;
2. making the **complete corresponding source** of the shipped build available — a public
   tag or commit of this repository is enough;
3. keeping this notice, `app/src/main/cpp/engine/LICENSE` and the copyright notices intact.

Anyone who receives the app may use, study, modify and redistribute it (including selling
it) under the same terms, and must be able to get the source. The engine's code cannot be
taken into a closed-source product.

`vikey_jni.cpp` (the JNI bridge) and the rest of this repository's own code are under the
same GPLv3 — there is no separate licence for them, which keeps the whole work coherent.

## Compiled-in data

`AutoSwitchData.h`, `BigramData.h`, `TeencodeData.h` and `KeepWords.inc` are word-frequency
data shipped with Vikey under the same GPLv3 licence.

`tools/data/` holds corpora used only by development scripts (`tools/layout_analyzer.py`);
it is not compiled into the app.

## Development scripts

`tools/engine-test/` builds the engine for the host and drives it for testing; it does not
change the licensing analysis above.
