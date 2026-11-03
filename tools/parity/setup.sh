#!/usr/bin/env bash
# Builds the parity harness: Vikey's C++ engine (compiled natively, no NDK needed) plus a
# Kotlin dumper for mini_qwerty_kb's TelexProcessor. Run once, then use ./compare.py.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
VIKEY="${VIKEY_DIR:-$HERE/.vikey}"

if [ ! -d "$VIKEY/Engine" ]; then
    echo "→ cloning Vikey into $VIKEY"
    git clone --depth 1 https://github.com/tonyminibot/Vikey "$VIKEY"
fi

echo "→ building harness (g++)"
g++ -std=c++17 -O2 -w -I"$VIKEY/Engine" "$HERE/harness.cpp" \
    "$VIKEY/Engine/Engine.cpp" "$VIKEY/Engine/Vietnamese.cpp" "$VIKEY/Engine/SmartSwitchKey.cpp" \
    "$VIKEY/Engine/Driver.cpp" "$VIKEY/Engine/TouchModel.cpp" -o "$HERE/harness"

echo "→ locating the Kotlin compiler in the Gradle cache"
find_jar() { find "$HOME/.gradle/caches" -name "$1" 2>/dev/null | sort -V | tail -1; }
KC="$(find_jar 'kotlin-compiler-embeddable-*.jar')"
STD="$(find_jar 'kotlin-stdlib-2*.jar')"
COR="$(find_jar 'kotlinx-coroutines-core-jvm-*.jar')"
ANN="$(find_jar 'annotations-2*.jar')"
for jar in "$KC" "$STD" "$COR" "$ANN"; do
    [ -n "$jar" ] || { echo "missing Kotlin jar — run ./gradlew assembleDebug once to populate ~/.gradle/caches" >&2; exit 1; }
done

echo "→ compiling Dump.kt + TelexProcessor.kt"
rm -rf "$HERE/out"
java -cp "$KC:$STD:$COR:$ANN" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -cp "$STD:$ANN" -nowarn -d "$HERE/out" \
    "$REPO/app/src/main/java/com/miniqwerty/kb/TelexProcessor.kt" "$HERE/Dump.kt"

printf 'STD_JAR=%s\n' "$STD" > "$HERE/build.env"
echo "→ done. Next: ./gen_corpus.py && ./compare.py corpus_hand.txt"
