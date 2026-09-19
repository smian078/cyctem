#!/usr/bin/env sh
set -eu
GRADLE_VERSION="8.11.1"
DIST_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/cystem-gradle/$GRADLE_VERSION"
GRADLE_BIN="$DIST_DIR/gradle-$GRADLE_VERSION/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$DIST_DIR"
  TMP="$DIST_DIR/gradle.zip"
  URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$URL" -o "$TMP"; elif command -v wget >/dev/null 2>&1; then wget -q "$URL" -O "$TMP"; else echo "curl or wget is required to bootstrap Gradle" >&2; exit 1; fi
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required to bootstrap Gradle" >&2; exit 1; }
  unzip -q -o "$TMP" -d "$DIST_DIR"
  rm -f "$TMP"
fi
exec "$GRADLE_BIN" "$@"
