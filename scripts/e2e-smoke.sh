#!/usr/bin/env bash
# Phase 1 walking-skeleton end-to-end smoke test.
# Boots the headless backend jar, probes every Phase 1 endpoint, asserts the Markdown
# plugin renders through the invoke path, then kills the backend.
#
# Usage: scripts/e2e-smoke.sh [port] [token]
set -uo pipefail

PORT="${1:-8899}"
TOKEN="${2:-e2e-smoke-token}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/FengYu/target/FengYu-4.0.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
  echo "FAIL: jar not found at $JAR — build it first (mvn -f FengYu/pom.xml package -DskipTests)"
  exit 1
fi

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

"$JAVA" -Dfengyu.plugins.official-directory="$ROOT/OfficialPlugins/target/packages" \
  -Dfengyu.plugins.directory="$WORK/.fengyu/plugins" \
  -Dfengyu.plugins.data-directory="$WORK/.fengyu/plugin-data" \
  -cp "$JAR" fan.summer.fengyu.HeadlessLauncher --port="$PORT" --token="$TOKEN" > server.log 2>&1 &
SRV=$!
trap 'kill $SRV 2>/dev/null; rm -rf "$WORK"' EXIT

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

XLSX="$WORK/sample.xlsx"
python3 - "$XLSX" <<'PY'
import sys
try:
    from openpyxl import Workbook
    wb = Workbook(); ws = wb.active; ws.title = "Alpha"
    ws.append(["region"]); ws.append(["east"]); ws.append(["west"])
    wb.save(sys.argv[1])
except Exception as e:
    print("SKIP-EXCEL:", e)
PY

if [ -f "$XLSX" ]; then
  UP="$(curl -s "${AUTH[@]}" -F "file=@$XLSX" "$H/api/plugin-runtime/fan.summer.excel/files/upload")"
  OUT="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
    "$H/api/plugin-runtime/fan.summer.excel/files/output" -d '{}')"
  SESS="e2e-smoke"
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
else
  echo "SKIP: openpyxl unavailable, skipping Excel file-flow (upload/analyze/split/archive)"
fi
