# Build image for the app: JDK 17 and Gradle 8.5 — the pins from CLAUDE.md —
# and nothing else. The Android SDK (platform, build-tools, NDK, CMake) stays on
# the host and is bind-mounted by tools/build-docker.sh, so the image is small
# and keeps using the SDK licences you already accepted.
FROM eclipse-temurin:17-jdk-jammy

ARG GRADLE_VERSION=8.5

# git: the Gradle wrapper checks the VCS for its distribution cache.
# unzip: Gradle unpacks SDK archives when AGP fetches a component.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git unzip \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o /tmp/gradle.zip \
        "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && unzip -q /tmp/gradle.zip -d /opt \
    && rm /tmp/gradle.zip \
    && ln -s "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle

# Overridden by the bind mount in build-docker.sh; set here so a manual
# `docker run … gradle …` also reuses the cache.
ENV GRADLE_USER_HOME=/gradle-cache
WORKDIR /work
