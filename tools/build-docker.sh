#!/usr/bin/env bash
# Build the app inside Docker — no JDK on the host, and the Android SDK/NDK you
# already have are reused as-is.
#
#   tools/build-docker.sh                  # assembleDebug
#   tools/build-docker.sh assembleRelease
#   tools/build-docker.sh lint
#   tools/build-docker.sh assembleDebug --stacktrace
#
# The repo is bind-mounted writable at its own path, ~/.gradle is mounted as the
# Gradle cache (wrapper distribution + dependencies, so rebuilds are fast), and
# the SDK is mounted at the exact path local.properties uses. Mounting the repo
# at the same absolute path matters: the native build (app/.cxx) caches absolute
# paths, so host and container builds can share the same working tree. The
# container runs as your UID/GID, so the APK and the caches are not left owned
# by root.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
IMAGE="miniqwerty-kb-build:latest"

# SDK: environment first, then local.properties (the same path the build reads).
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ] && [ -f "$REPO/local.properties" ]; then
    SDK="$(sed -n 's/^sdk\.dir=//p' "$REPO/local.properties")"
fi
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
    echo "Android SDK not found — set ANDROID_HOME or sdk.dir in local.properties." >&2
    exit 1
fi
SDK="$(cd "$SDK" && pwd)"

GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
mkdir -p "$GRADLE_HOME"

# ~/.gradle/gradle.properties may pin org.gradle.java.home to a JDK that exists
# on the host only — the container has its own JDK, so that path is overridden
# below. If a release keystore is configured, mount it where the build expects
# it so assembleRelease works too.
EXTRA_MOUNTS=()
STORE_FILE="$(sed -n 's/^miniqwerty\.storeFile=//p' "$GRADLE_HOME/gradle.properties" 2>/dev/null || true)"
if [ -n "$STORE_FILE" ] && [ -f "$STORE_FILE" ]; then
    EXTRA_MOUNTS+=(-v "$STORE_FILE":"$STORE_FILE":ro)
fi

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "→ building the $IMAGE image (JDK 17 + Gradle 8.5, one-time)"
    docker build -t "$IMAGE" "$HERE/docker"
fi

[ "$#" -gt 0 ] || set -- assembleDebug

echo "→ gradle $* (SDK: $SDK)"
docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp/container-home \
    -e GRADLE_USER_HOME=/gradle-cache \
    -v "$REPO":"$REPO" \
    -v "$GRADLE_HOME":/gradle-cache \
    -v "$SDK":"$SDK":ro \
    "${EXTRA_MOUNTS[@]}" \
    -w "$REPO" \
    "$IMAGE" bash -c '
        set -euo pipefail
        mkdir -p "$HOME"
        # gradlew is generated, not committed (see CLAUDE.md) — create it the
        # same way CI does when it is missing.
        if [ ! -x ./gradlew ]; then
            gradle wrapper --gradle-version 8.5 --console=plain
        fi
        # Use the image JDK, not the host path pinned in ~/.gradle/gradle.properties.
        exec ./gradlew "$@" --console=plain "-Dorg.gradle.java.home=$JAVA_HOME"
    ' -- "$@"
