# Third-party notices

## Vikey / OpenKey engine — GPLv3

`app/src/main/cpp/engine/` is the processing core of
[Vikey](https://github.com/tonyminibot/Vikey), itself derived from
[OpenKey](https://github.com/tuyenvm/OpenKey). Copied from upstream commit
`783bf954b318b27a625e5809ef06c091a35282e7` (2026-11-03) and built into `libvikey.so`.

The engine is licensed under the **GNU General Public License v3**; the full text is at
`app/src/main/cpp/engine/LICENSE`.

### What this means for the app

Because the app links GPLv3 code, the **combined work must be distributed under GPLv3**,
with complete corresponding source available. The repository's root `LICENSE` still says
MIT — that is a mismatch that must be resolved before any release:

1. Replace the root `LICENSE` with GPLv3 (or add it alongside and state in the README that
   the app as a whole is GPLv3).
2. Make the source of the shipped build available (this repository is enough, provided the
   shipped tag/commit is public).
3. Keep this notice and `app/src/main/cpp/engine/LICENSE` intact.

The `tools/engine-test/` scripts read the engine's source and drive it for testing; they
do not change the licensing analysis above.

## Compiled-in data

`AutoSwitchData.h`, `BigramData.h`, `TeencodeData.h` and `KeepWords.inc` are word-frequency
data shipped with Vikey under the same GPLv3 licence.
