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

fail() { echo "FAIL: $1"; tail -20 server.log; exit 1; }

# /api/plugins lists Markdown.
curl -s "${AUTH[@]}" "$H/api/plugins" | grep -q 'fan.summer.markdown' || fail "Markdown plugin not listed"

# invoke render returns correct HTML.
RENDER="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugins/fan.summer.markdown/invoke" \
  -d '{"action":"render","args":{"markdown":"# Hello\n\n**bold**"}}')"
echo "$RENDER" | grep -q '<h1>Hello</h1>' || fail "render missing <h1>: $RENDER"
echo "$RENDER" | grep -q '<strong>bold</strong>' || fail "render missing <strong>: $RENDER"

# token enforcement: no token → 401.
CODE="$(curl -s -o /dev/null -w '%{http_code}' "$H/api/plugins")"
[ "$CODE" = 401 ] || fail "expected 401 without token, got $CODE"

echo "PASS: health + plugins + Markdown render + token auth all OK (port=$PORT)"

# --- Excel plugin (web upload -> analyze -> split -> archive) ---

# /api/plugins lists Excel — closes Task 11's deferred registration check.
curl -s "${AUTH[@]}" "$H/api/plugins" | grep -q 'fan.summer.excel' || fail "Excel plugin not listed"
echo "PASS: excel plugin registered"

# Email Center is seeded as an isolated .fyp and its Worker answers through the official SDK protocol.
curl -s "${AUTH[@]}" "$H/api/plugins" | grep -q 'fan.summer.email' || fail "Email Center plugin not listed"
EMAIL_ACCOUNTS="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugins/fan.summer.email/invoke" -d '{"action":"email_accounts_list","args":{}}')"
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
  UP="$(curl -s "${AUTH[@]}" -F "file=@$XLSX" "$H/api/plugins/fan.summer.excel/files")"
  SESS="$(printf '%s' "$UP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["session"])')"
  SRCP="$(printf '%s' "$UP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["files"][0]["path"])')"
  [ -n "$SESS" ] && [ -n "$SRCP" ] || fail "excel upload did not return session/path: $UP"
  OUTP="$(printf '%s' "$SRCP" | sed 's#/in/[^/]*$#/out#')"

  curl -s "${AUTH[@]}" -H 'Content-Type: application/json' \
    -d "{\"action\":\"analyze\",\"args\":{\"session\":\"$SESS\",\"sourceFile\":\"$SRCP\"}}" \
    "$H/api/plugins/fan.summer.excel/invoke" | grep -q '"success":true' \
    && echo "PASS: excel analyze" || fail "excel analyze"

  curl -s "${AUTH[@]}" -H 'Content-Type: application/json' \
    -d "{\"action\":\"split\",\"args\":{\"session\":\"$SESS\",\"sourceFile\":\"$SRCP\",\"outputDir\":\"$OUTP\"}}" \
    "$H/api/plugins/fan.summer.excel/invoke" | grep -q '"success":true' \
    && echo "PASS: excel split" || fail "excel split"

  curl -s "${AUTH[@]}" "$H/api/plugins/fan.summer.excel/files/archive?session=$SESS&dir=out" -o "$WORK/r.zip"
  unzip -l "$WORK/r.zip" | grep -q '\.xlsx' && echo "PASS: excel archive" || fail "excel archive"
else
  echo "SKIP: openpyxl unavailable, skipping Excel file-flow (upload/analyze/split/archive)"
fi
