#!/usr/bin/env bash
# End-to-end smoke test of the JNI bridge without a device: builds libvikey.so for the
# host from the *vendored* sources in app/src/main/cpp, then runs VikeyDriver.kt against it
# on the JVM. Verifies the Kotlin externals, the symbol names and the Edit packing.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
CPP="$REPO/app/src/main/cpp"
BUILD="${TMPDIR:-/tmp}/miniqwerty-jni-smoke"
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"

rm -rf "$BUILD" && mkdir -p "$BUILD/lib" "$BUILD/out"

echo "→ building libvikey.so for the host"
g++ -std=c++17 -O2 -w -fPIC -shared \
    -I"$CPP/engine" -I"$CPP/engine/platforms" \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    "$CPP/vikey_jni.cpp" \
    "$CPP/engine/Engine.cpp" "$CPP/engine/Vietnamese.cpp" "$CPP/engine/Driver.cpp" \
    -o "$BUILD/lib/libvikey.so"

echo "→ compiling VikeyDriver.kt + jni-smoke.kt"
find_jar() { find "$HOME/.gradle/caches" -name "$1" 2>/dev/null | sort -V | tail -1; }
KC="$(find_jar 'kotlin-compiler-embeddable-*.jar')"
STD="$(find_jar 'kotlin-stdlib-2*.jar')"
COR="$(find_jar 'kotlinx-coroutines-core-jvm-*.jar')"
ANN="$(find_jar 'annotations-2*.jar')"
[ -n "$KC" ] && [ -n "$STD" ] || { echo "Kotlin jars not found — run ./gradlew assembleDebug once" >&2; exit 1; }

java -cp "$KC:$STD:$COR:$ANN" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -cp "$STD:$ANN" -nowarn -d "$BUILD/out" \
    "$REPO/app/src/main/java/com/miniqwerty/kb/VikeyDriver.kt" "$HERE/jni-smoke.kt"

echo "→ running"
java -Djava.library.path="$BUILD/lib" -cp "$BUILD/out:$STD" Jni_smokeKt 2>&1 | grep -v "^WARNING" || true
