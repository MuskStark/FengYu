#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/OfficialPlugins/target/packages"
npm --prefix "$ROOT/plugin-sdk/typescript" run build
node --test "$ROOT/OfficialPlugins/packages/excel/ui/wizard-state.test.mjs"
rm -rf "$OUT"
mkdir -p "$OUT/markdown/backend" "$OUT/excel/backend"
cp -R "$ROOT/OfficialPlugins/packages/markdown/." "$OUT/markdown/"
cp -R "$ROOT/OfficialPlugins/packages/excel/." "$OUT/excel/"
cp "$ROOT/plugin-sdk/typescript/dist/index.js" "$OUT/markdown/ui/sdk.js"
cp "$ROOT/plugin-sdk/typescript/dist/index.js" "$OUT/excel/ui/sdk.js"
cp "$ROOT/OfficialPlugins/plugin-markdown/target/markdown-worker.jar" "$OUT/markdown/backend/worker.jar"
cp "$ROOT/OfficialPlugins/plugin-excel/target/excel-worker.jar" "$OUT/excel/backend/worker.jar"
(cd "$OUT/markdown" && zip -qr ../fan.summer.markdown-4.0.0.fyp .)
(cd "$OUT/excel" && zip -qr ../fan.summer.excel-4.0.0.fyp .)
echo "Packages written to $OUT"
