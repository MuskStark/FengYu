#!/usr/bin/env bash
# Generate a jlink-minimized JRE for the with-JRE build variant.
# Run on each platform runner with JDK 21 LTS on PATH.
#
# Usage:
#   build-jre.sh <path/to/FengYu.jar> [output-dir]
#
# Outputs a self-contained JRE (with bin/java) at <output-dir> (default resources/jre).
# electron-builder then bundles it under <resources>/jre/ via the CI --config override,
# and runtime-layout.ts resolves <resources>/jre/bin/java as the backend launcher.
set -euo pipefail

JAR="${1:?usage: build-jre.sh <path/to/FengYu.jar>}"
OUT="${2:-resources/jre}"

# Explicit module baseline. jdeps may miss reflectively/SPI-loaded modules, so we
# always union its output with this conservative list rather than trusting it alone.
EXPLICIT="java.base,java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.scripting,java.sql,java.sql.rowset,java.transaction.xa,java.xml,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.unsupported,jdk.zipfs,jdk.management"

# Ask jdeps what the jar actually needs (best-effort; tolerate missing deps).
JLINK_MODS=$(jdeps --multi-release 21 --ignore-missing-deps --print-module-deps -cp "$JAR" "$JAR" || echo "")

if [ -z "$JLINK_MODS" ]; then
  MODS="$EXPLICIT"
else
  MODS="$JLINK_MODS,$EXPLICIT"
fi

echo "[build-jre] modules: $MODS"
rm -rf "$OUT"
jlink --no-header-files --no-man-pages --strip-debug \
  --add-modules "$MODS" \
  --output "$OUT"
echo "[build-jre] JRE written to $OUT"
