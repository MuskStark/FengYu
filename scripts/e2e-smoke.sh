#!/usr/bin/env bash
# Phase 1 walking-skeleton end-to-end smoke test.
# Boots the headless backend jar, probes every Phase 1 endpoint, asserts the Markdown
# plugin renders through the invoke path, then kills the backend.
#
# Usage: scripts/e2e-smoke.sh [port] [token]
set -euo pipefail

PORT="${1:-8899}"
TOKEN="${2:-e2e-smoke-token}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/FengYu/target/FengYu-4.0.0-alpha.1.jar"

if [ ! -f "$JAR" ]; then
  echo "FAIL: jar not found at $JAR — build it first (mvn -f FengYu/pom.xml package -DskipTests)"
  exit 1
fi

# Build the official plugins through the CLI into each plugin's dist-package/,
# then stage their .fyp outputs into a single official-packages directory the
# host is pointed at. This replaces the old per-plugin shell packager.
OFFICIAL_DIR="$(mktemp -d)"
# WORK is assigned below; pre-declare so the EXIT trap can reference it safely even if the
# script is interrupted between setting the trap and assigning WORK.
WORK=""
SRV=""
for plugin in markdown excel email offlinepython; do
  if ! node "$ROOT/plugin-cli/bin/fengyu.mjs" plugin build "$ROOT/OfficialPlugins/plugin-$plugin" >/dev/null; then
    echo "FAIL: fengyu plugin build OfficialPlugins/plugin-$plugin failed"
    rm -rf "$OFFICIAL_DIR"
    exit 1
  fi
done
mkdir -p "$OFFICIAL_DIR"
for fyp in "$ROOT"/OfficialPlugins/plugin-*/dist-package/*.fyp; do
  cp "$fyp" "$OFFICIAL_DIR/"
done
# Defensive `${VAR:-}` so a trap firing before WORK/SRV are set never expands to rm -rf ""
# or kill "" (the latter would be a no-op, but under set -u an unset var is fatal).
trap 'kill ${SRV:-} 2>/dev/null || true; rm -rf "${WORK:-}" "$OFFICIAL_DIR"' EXIT

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || echo "")}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

WORK="$(mktemp -d)"
cd "$WORK"

# Pre-seed an embedded H2 datasource config so HeadlessLauncher's startup probe (added by the
# multi-datasource setup wizard) picks APP mode instead of SETUP mode. Without this, a fresh
# working dir has no ~/.fengyu/config/datasource.properties and the backend boots into the
# minimal setup-wizard context, which excludes PluginController/PluginFileController entirely.
DB_FILE="$WORK/.fengyu/database/fengyu"
mkdir -p "$WORK/.fengyu/config" "$(dirname "$DB_FILE")"
cat > "$WORK/.fengyu/config/datasource.properties" <<EOF
db.type=h2
db.url=jdbc:h2:file:${DB_FILE};AUTO_SERVER=TRUE
db.driver=org.h2.Driver
db.dialect=org.hibernate.dialect.H2Dialect
db.file.path=${DB_FILE}
EOF

"$JAVA" -Dfengyu.plugins.official-directory="$OFFICIAL_DIR" \
  -Dfengyu.plugins.directory="$WORK/.fengyu/plugins" \
  -Dfengyu.plugins.data-directory="$WORK/.fengyu/plugin-data" \
  -cp "$JAR" fan.summer.fengyu.HeadlessLauncher --port="$PORT" --token="$TOKEN" > server.log 2>&1 &
SRV=$!

H="http://127.0.0.1:$PORT"
AUTH=(-H "X-FengYu-Token: $TOKEN")

# Wait for health.
ready=0
for _ in $(seq 1 40); do
  if curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$H/api/health" 2>/dev/null | grep -q 200; then
    ready=1; break
  fi
  sleep 1
done
[ "$ready" = 1 ] || { echo "FAIL: backend never became healthy"; tail -20 server.log; exit 1; }

fail() { echo "FAIL: $1"; tail -100 server.log; exit 1; }

# Installed package discovery lists all official plugins.
RUNTIME="$(curl -s "${AUTH[@]}" "$H/api/plugin-runtime")"
echo "$RUNTIME" | grep -q 'fan.summer.markdown' || fail "Markdown plugin not listed: $RUNTIME"
echo "$RUNTIME" | grep -q 'fan.summer.offlinepython' || fail "Offline Python plugin not listed: $RUNTIME"

# invoke render returns correct HTML.
RENDER="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.markdown/invoke" \
  -d '{"method":"render","params":{"markdown":"# Hello\n\n**bold**"}}')"
echo "$RENDER" | grep -q '<h1>Hello</h1>' || fail "render missing <h1>: $RENDER"
echo "$RENDER" | grep -q '<strong>bold</strong>' || fail "render missing <strong>: $RENDER"

# token enforcement: no token → 401.
CODE="$(curl -s -o /dev/null -w '%{http_code}' "$H/api/plugin-runtime")"
[ "$CODE" = 401 ] || fail "expected 401 without token, got $CODE"

echo "PASS: health + plugins + Markdown render + token auth all OK (port=$PORT)"

# Offline Python receives a writable project workspace and the host resolves the complete
# FileRef object before dispatching to the out-of-process worker.
printf 'numpy==1.26.4\n' > "$WORK/requirements.txt"
OPB_PROJECT="$(curl -s "${AUTH[@]}" -F "files=@$WORK/requirements.txt" -F 'paths=requirements.txt' \
  "$H/api/plugin-runtime/fan.summer.offlinepython/files/upload-directory?access=read-write")"
echo "$OPB_PROJECT" | grep -q '"access":"read-write"' || fail "offlinepython workspace grant: $OPB_PROJECT"
OPB_GET_BODY="$(python3 -c 'import json,sys; print(json.dumps({"method":"requirements.get","params":{"projectDir":json.loads(sys.argv[1])}}))' "$OPB_PROJECT")"
OPB_GET="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.offlinepython/invoke" -d "$OPB_GET_BODY")"
echo "$OPB_GET" | grep -q 'numpy==1.26.4' || fail "offlinepython FileRef resolution: $OPB_GET"
OPB_SAVE_BODY="$(python3 -c 'import json,sys; print(json.dumps({"method":"requirements.save","params":{"projectDir":json.loads(sys.argv[1]),"text":"requests==2.32.4\\n"}}))' "$OPB_PROJECT")"
curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.offlinepython/invoke" -d "$OPB_SAVE_BODY" | grep -q '"success":true' \
  && echo "PASS: offlinepython writable workspace + FileRef bridge" || fail "offlinepython requirements save"

# --- Excel plugin (web upload -> analyze -> split -> archive) ---

# /api/plugins lists Excel — closes Task 11's deferred registration check.
echo "$RUNTIME" | grep -q 'fan.summer.excel' || fail "Excel plugin not listed"
echo "PASS: excel plugin registered"

# Email Center is seeded as an isolated .fyp and its Worker answers through the official SDK protocol.
echo "$RUNTIME" | grep -q 'fan.summer.email' || fail "Email Center plugin not listed"
EMAIL_ACCOUNTS="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.email/invoke" -d '{"method":"email_accounts_list","params":{}}')"
echo "$EMAIL_ACCOUNTS" | grep -q '"success":true' \
  && echo "PASS: email worker discovered" || fail "email account RPC: $EMAIL_ACCOUNTS"

# Email batch preview parses the final filename tag and resolves the attachment/group intersection.
EAST_TAG="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.email/invoke" \
  -d '{"method":"email_tag_save","params":{"name":"East"}}')"
GROUP_TAG="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.email/invoke" \
  -d '{"method":"email_tag_save","params":{"name":"Smoke recipients"}}')"
CONTACT="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.email/invoke" \
  -d '{"method":"email_contact_save","params":{"email":"smoke@example.com","nickname":"Smoke"}}')"
EAST_ID="$(printf '%s' "$EAST_TAG" | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag"]["id"])')"
GROUP_ID="$(printf '%s' "$GROUP_TAG" | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag"]["id"])')"
CONTACT_ID="$(printf '%s' "$CONTACT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["contact"]["id"])')"
ASSIGN_BODY="$(python3 -c 'import json,sys; print(json.dumps({"method":"email_tags_assign","params":{"contactIds":[int(sys.argv[1])],"tagIds":[int(sys.argv[2]),int(sys.argv[3])]}}))' "$CONTACT_ID" "$EAST_ID" "$GROUP_ID")"
curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.email/invoke" -d "$ASSIGN_BODY" | grep -q '"success":true' \
  || fail "email tag assignment"
printf 'report' > "$WORK/report_East.pdf"
EMAIL_DIR="$(curl -s "${AUTH[@]}" -F "files=@$WORK/report_East.pdf" -F 'paths=report_East.pdf' \
  "$H/api/plugin-runtime/fan.summer.email/files/upload-directory")"
PREVIEW_BODY="$(python3 -c 'import json,sys; print(json.dumps({"method":"email_batch_preview","params":{"accountId":1,"recipientGroupTagIds":[int(sys.argv[1])],"ccGroupTagIds":[],"inputDirectory":json.loads(sys.argv[2]),"commonAttachments":[],"subject":"Smoke","plainText":"Preview"}}))' "$GROUP_ID" "$EMAIL_DIR")"
EMAIL_PREVIEW="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.email/invoke" -d "$PREVIEW_BODY")"
echo "$EMAIL_PREVIEW" | grep -q '"attachmentTag":"East"' \
  && echo "PASS: email filename-tag preview" || fail "email batch preview: $EMAIL_PREVIEW"

XLSX="$WORK/sample.xlsx"
EXCEL_WORKER="$ROOT/OfficialPlugins/plugin-excel/target/excel-worker.jar"
# Pre-compile the fixture with javac against the shaded worker jar (POI is on its classpath),
# then run the compiled class. This replaces the previous single-file source-mode invocation
# (`java Fixture.java`), which silently depended on javac's implicit source-file behavior and
# would have broken opaquely if the worker's shade config ever relocated POI classes. A failed
# compile now surfaces immediately instead of at runtime.
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
command -v "$JAVAC" >/dev/null 2>&1 || JAVAC="$(command -v javac)"
[ -n "$JAVAC" ] || fail "javac not found on PATH (needed to compile ExcelSmokeFixture)"
"$JAVAC" -cp "$EXCEL_WORKER" -d "$WORK" "$ROOT/scripts/fixtures/ExcelSmokeFixture.java" \
  || fail "compile ExcelSmokeFixture"
"$JAVA" -cp "$WORK:$EXCEL_WORKER" ExcelSmokeFixture "$XLSX" \
  || fail "generate Excel fixture"

UP="$(curl -s "${AUTH[@]}" -F "file=@$XLSX" "$H/api/plugin-runtime/fan.summer.excel/files/upload")"
OUT="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.excel/files/output" -d '{}')"
ANALYZE_BODY="$(python3 -c 'import json,sys; print(json.dumps({"method":"analyze","params":{"session":"e2e-smoke","sourceFile":json.loads(sys.argv[1])}}))' "$UP")"
SPLIT_BODY="$(python3 -c 'import json,sys; print(json.dumps({"method":"split","params":{"session":"e2e-smoke","sourceFile":json.loads(sys.argv[1]),"outputDir":json.loads(sys.argv[2])}}))' "$UP" "$OUT")"
OUT_REF="$(printf '%s' "$OUT" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')"

curl -s "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "$ANALYZE_BODY" "$H/api/plugin-runtime/fan.summer.excel/invoke" | grep -q '"success":true' \
  && echo "PASS: excel analyze" || fail "excel analyze"

curl -s "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "$SPLIT_BODY" "$H/api/plugin-runtime/fan.summer.excel/invoke" | grep -q '"success":true' \
  && echo "PASS: excel split" || fail "excel split"

curl -s "${AUTH[@]}" "$H/api/plugin-runtime/fan.summer.excel/files/export/$OUT_REF" -o "$WORK/r.zip"
unzip -l "$WORK/r.zip" | grep -q '\.xlsx' && echo "PASS: excel archive" || fail "excel archive"
