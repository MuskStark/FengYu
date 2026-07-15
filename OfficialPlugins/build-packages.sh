#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/OfficialPlugins/target/packages"
MARKDOWN_WORKER="$ROOT/OfficialPlugins/plugin-markdown/target/markdown-worker.jar"
EXCEL_WORKER="$ROOT/OfficialPlugins/plugin-excel/target/excel-worker.jar"
EMAIL_WORKER="$ROOT/OfficialPlugins/plugin-email/target/email-worker.jar"

# Build the three worker JARs.
mvn -f "$ROOT/pom.xml" -pl OfficialPlugins/plugin-markdown,OfficialPlugins/plugin-excel,OfficialPlugins/plugin-email \
  -am package -DskipTests

# Build the shared TypeScript SDK + UI kit the plugin front-ends depend on.
npm --prefix "$ROOT/plugin-sdk/typescript" run build
npm --prefix "$ROOT/plugin-ui/vue" run build

# Build each plugin's standalone Vue SPA into ui-src/dist.
for plugin in markdown excel email; do
  npm --prefix "$ROOT/OfficialPlugins/plugin-$plugin/ui-src" ci --silent
  npm --prefix "$ROOT/OfficialPlugins/plugin-$plugin/ui-src" run build
done

# Assemble each package dir: manifest + built UI (dist → ui/) + worker JAR (→ backend/).
rm -rf "$OUT"
mkdir -p "$OUT/markdown/backend" "$OUT/excel/backend" "$OUT/email/backend" \
         "$OUT/markdown/ui" "$OUT/excel/ui" "$OUT/email/ui"

cp "$ROOT/OfficialPlugins/packages/markdown/manifest.json" "$OUT/markdown/manifest.json"
cp "$ROOT/OfficialPlugins/packages/excel/manifest.json"   "$OUT/excel/manifest.json"
cp "$ROOT/OfficialPlugins/packages/email/manifest.json"   "$OUT/email/manifest.json"

cp -R "$ROOT/OfficialPlugins/plugin-markdown/ui-src/dist/." "$OUT/markdown/ui/"
cp -R "$ROOT/OfficialPlugins/plugin-excel/ui-src/dist/."   "$OUT/excel/ui/"
cp -R "$ROOT/OfficialPlugins/plugin-email/ui-src/dist/."   "$OUT/email/ui/"

cp "$MARKDOWN_WORKER" "$OUT/markdown/backend/worker.jar"
cp "$EXCEL_WORKER"    "$OUT/excel/backend/worker.jar"
cp "$EMAIL_WORKER"    "$OUT/email/backend/worker.jar"

# Zip each into a .fyp archive.
(cd "$OUT/markdown" && zip -qr ../fan.summer.markdown-4.0.0.fyp .)
(cd "$OUT/excel"    && zip -qr ../fan.summer.excel-4.0.0.fyp .)
(cd "$OUT/email"    && zip -qr ../fan.summer.email-4.0.0.fyp .)

# Sanity: every package's UI entry exists and carries a charset meta.
for id in markdown excel email; do
  unzip -p "$OUT/fan.summer.$id-4.0.0.fyp" ui/index.html | grep -q '<meta charset="UTF-8"'
done

# Excel worker still ships the POI XSSF service registration it relies on.
unzip -p "$EXCEL_WORKER" META-INF/services/org.apache.poi.ss.usermodel.WorkbookProvider \
  | grep -qxF 'org.apache.poi.xssf.usermodel.XSSFWorkbookFactory'
# Email worker's shade manifest points at its main class.
unzip -p "$EMAIL_WORKER" META-INF/MANIFEST.MF | tr -d '\r' \
  | grep -qxF 'Main-Class: fan.summer.fengyu.plugin.email.EmailWorkerMain'

echo "Packages written to $OUT"
