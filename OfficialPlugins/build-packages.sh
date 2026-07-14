#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/OfficialPlugins/target/packages"
EXCEL_WORKER="$ROOT/OfficialPlugins/plugin-excel/target/excel-worker.jar"
EMAIL_WORKER="$ROOT/OfficialPlugins/plugin-email/target/email-worker.jar"
mvn -f "$ROOT/pom.xml" -pl OfficialPlugins/plugin-markdown,OfficialPlugins/plugin-excel,OfficialPlugins/plugin-email \
  -am package -DskipTests
npm --prefix "$ROOT/plugin-sdk/typescript" run build
npm --prefix "$ROOT/OfficialPlugins/plugin-email/ui-src" ci --silent
npm --prefix "$ROOT/OfficialPlugins/plugin-email/ui-src" run build
node --test "$ROOT/OfficialPlugins/packages/excel/ui/wizard-state.test.mjs"
unzip -p "$EXCEL_WORKER" META-INF/services/org.apache.poi.ss.usermodel.WorkbookProvider \
  | grep -qxF 'org.apache.poi.xssf.usermodel.XSSFWorkbookFactory'
unzip -p "$EMAIL_WORKER" META-INF/MANIFEST.MF | tr -d '\r' \
  | grep -qxF 'Main-Class: fan.summer.fengyu.plugin.email.EmailWorkerMain'
rm -rf "$OUT"
mkdir -p "$OUT/markdown/backend" "$OUT/excel/backend" "$OUT/email/backend" "$OUT/email/ui"
cp -R "$ROOT/OfficialPlugins/packages/markdown/." "$OUT/markdown/"
cp -R "$ROOT/OfficialPlugins/packages/excel/." "$OUT/excel/"
cp "$ROOT/OfficialPlugins/packages/email/manifest.json" "$OUT/email/manifest.json"
cp -R "$ROOT/OfficialPlugins/plugin-email/ui-src/dist/." "$OUT/email/ui/"
cp "$ROOT/plugin-sdk/typescript/dist/index.js" "$OUT/markdown/ui/sdk.js"
cp "$ROOT/plugin-sdk/typescript/dist/index.js" "$OUT/excel/ui/sdk.js"
cp "$ROOT/OfficialPlugins/plugin-markdown/target/markdown-worker.jar" "$OUT/markdown/backend/worker.jar"
cp "$EXCEL_WORKER" "$OUT/excel/backend/worker.jar"
cp "$EMAIL_WORKER" "$OUT/email/backend/worker.jar"
(cd "$OUT/markdown" && zip -qr ../fan.summer.markdown-4.0.0.fyp .)
(cd "$OUT/excel" && zip -qr ../fan.summer.excel-4.0.0.fyp .)
(cd "$OUT/email" && zip -qr ../fan.summer.email-4.0.0.fyp .)
echo "Packages written to $OUT"
