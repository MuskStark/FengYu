#!/usr/bin/env bash
# Assemble a portable, deterministic Web distribution archive from shared build inputs.
#
# Usage: scripts/package-web-release.sh VERSION JAR OUTPUT_DIR
#   VERSION     — full version string, e.g. 4.0.0-alpha.1
#   JAR         — path to the shaded FengYu jar (renamed to Infinia.jar in the archive)
#   OUTPUT_DIR  — where the Infinia-<version>-web.{zip,tar.gz} archives are written
#
# Produces:
#   <OUTPUT_DIR>/Infinia-<VERSION>-web.zip
#   <OUTPUT_DIR>/Infinia-<VERSION>-web.tar.gz
# Both contain: Infinia.jar, run.sh (executable), run.bat, README.md.
# No official plugins are bundled — they are distributed through the Infinia store
# (www.infinia.fyi). Keep in sync with scripts/test-web-release.sh (which asserts
# the same layout on the unpacked archive).
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 VERSION JAR OUTPUT_DIR" >&2
  exit 2
fi

VERSION="$1"
JAR="$2"
OUTPUT_DIR="$3"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMPLATE="$ROOT/distribution/web"

# --- validate inputs ---
[ -f "$JAR" ]        || { echo "FAIL: jar not found: $JAR" >&2; exit 1; }
[ -d "$TEMPLATE" ]   || { echo "FAIL: web template missing: $TEMPLATE" >&2; exit 1; }

STAGE="$(mktemp -d)"
PKG="Infinia-$VERSION-web"
DEST="$STAGE/$PKG"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$DEST"

# Backend jar (uniform name), launchers, docs.
cp "$JAR" "$DEST/Infinia.jar"
cp "$TEMPLATE/run.sh"   "$DEST/run.sh"
cp "$TEMPLATE/run.bat"  "$DEST/run.bat"
cp "$TEMPLATE/README.md" "$DEST/README.md"
chmod +x "$DEST/run.sh"

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
( cd "$STAGE" && zip -qr "$OUTPUT_DIR/$PKG.zip" "$PKG" )
( cd "$STAGE" && tar -czf "$OUTPUT_DIR/$PKG.tar.gz" "$PKG" )

echo "Packaged:"
echo "  $OUTPUT_DIR/$PKG.zip"
echo "  $OUTPUT_DIR/$PKG.tar.gz"
