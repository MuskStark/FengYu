#!/usr/bin/env bash
# Assemble a portable, deterministic Web distribution archive from shared build inputs.
#
# Usage: scripts/package-web-release.sh VERSION JAR PLUGIN_DIR OUTPUT_DIR
#   VERSION     — full version string, e.g. 4.0.0-alpha.1
#   JAR         — path to the shaded FengYu jar (renamed to Infinia.jar in the archive)
#   PLUGIN_DIR  — directory containing the official .fyp plugin packages
#   OUTPUT_DIR  — where the Infinia-<version>-web.{zip,tar.gz} archives are written
#
# Produces:
#   <OUTPUT_DIR>/Infinia-<VERSION>-web.zip
#   <OUTPUT_DIR>/Infinia-<VERSION>-web.tar.gz
# Both contain: Infinia.jar, run.sh (executable), run.bat, README.md, plugins/*.fyp
# Fails unless all four official plugin IDs (markdown, excel, email, offlinepython) are present.
set -euo pipefail

# Single source of truth for which official plugins ship in the portable Web bundle. This set
# matches OfficialPlugins/pom.xml's module list and scripts/e2e-smoke.sh's build loop. Keep in
# sync with scripts/test-web-release.sh (which asserts the same set on the unpacked archive)
# and .github/workflows/fengyu-release.yml's `Build official plugins` step.
OFFICIAL_PLUGINS=(markdown excel email offlinepython)

if [ "$#" -ne 4 ]; then
  echo "Usage: $0 VERSION JAR PLUGIN_DIR OUTPUT_DIR" >&2
  exit 2
fi

VERSION="$1"
JAR="$2"
PLUGIN_DIR="$3"
OUTPUT_DIR="$4"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMPLATE="$ROOT/distribution/web"

# --- validate inputs ---
[ -f "$JAR" ]        || { echo "FAIL: jar not found: $JAR" >&2; exit 1; }
[ -d "$PLUGIN_DIR" ] || { echo "FAIL: plugin dir not found: $PLUGIN_DIR" >&2; exit 1; }
[ -d "$TEMPLATE" ]   || { echo "FAIL: web template missing: $TEMPLATE" >&2; exit 1; }

# Every official plugin must be staged — and we copy ONLY these by explicit glob, so a
# stray/old .fyp in PLUGIN_DIR can never sneak into the release archive.
for id in "${OFFICIAL_PLUGINS[@]}"; do
  ls "$PLUGIN_DIR"/fan.summer."$id"-*.fyp >/dev/null 2>&1 \
    || { echo "FAIL: missing official plugin fan.summer.$id in $PLUGIN_DIR" >&2; exit 1; }
done

STAGE="$(mktemp -d)"
PKG="Infinia-$VERSION-web"
DEST="$STAGE/$PKG"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$DEST/plugins"

# Backend jar (uniform name), launchers, docs.
cp "$JAR" "$DEST/Infinia.jar"
cp "$TEMPLATE/run.sh"   "$DEST/run.sh"
cp "$TEMPLATE/run.bat"  "$DEST/run.bat"
cp "$TEMPLATE/README.md" "$DEST/README.md"
chmod +x "$DEST/run.sh"

# Official plugins — explicit list, never a bare `fan.summer.*.fyp` glob.
for id in "${OFFICIAL_PLUGINS[@]}"; do
  cp "$PLUGIN_DIR"/fan.summer."$id"-*.fyp "$DEST/plugins/"
done

mkdir -p "$OUTPUT_DIR"
( cd "$STAGE" && zip -qr "$ROOT/$OUTPUT_DIR/$PKG.zip" "$PKG" )
( cd "$STAGE" && tar -czf "$ROOT/$OUTPUT_DIR/$PKG.tar.gz" "$PKG" )

echo "Packaged:"
echo "  $OUTPUT_DIR/$PKG.zip"
echo "  $OUTPUT_DIR/$PKG.tar.gz"
