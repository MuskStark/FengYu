#!/usr/bin/env bash
# Focused host smoke for the Offline Python writable-workspace and FileRef bridge.
set -euo pipefail

PORT="${1:-8900}"
TOKEN="${2:-offlinepython-smoke-token}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/FengYu/target/FengYu-4.0.0.jar"
PACKAGE="$ROOT/OfficialPlugins/plugin-offlinepython/dist-package/fan.summer.offlinepython-4.0.0.fyp"

[ -f "$JAR" ] || { echo "FAIL: main JAR is missing: $JAR"; exit 1; }
[ -f "$PACKAGE" ] || { echo "FAIL: plugin package is missing: $PACKAGE"; exit 1; }

WORK="$(mktemp -d)"
OFFICIAL_DIR="$WORK/official-plugins"
mkdir -p "$OFFICIAL_DIR" "$WORK/.fengyu/config" "$WORK/.fengyu/database"
cp "$PACKAGE" "$OFFICIAL_DIR/"

DB_FILE="$WORK/.fengyu/database/fengyu"
cat > "$WORK/.fengyu/config/datasource.properties" <<EOF
db.type=h2
db.url=jdbc:h2:file:${DB_FILE}
db.driver=org.h2.Driver
db.dialect=org.hibernate.dialect.H2Dialect
db.file.path=${DB_FILE}
EOF

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home)}"
JAVA="$JAVA_HOME/bin/java"
cd "$WORK"
"$JAVA" -Dfengyu.plugins.official-directory="$OFFICIAL_DIR" \
  -Dfengyu.plugins.directory="$WORK/.fengyu/plugins" \
  -Dfengyu.plugins.data-directory="$WORK/.fengyu/plugin-data" \
  -cp "$JAR" fan.summer.fengyu.HeadlessLauncher --port="$PORT" --token="$TOKEN" > server.log 2>&1 &
SRV=$!
trap 'kill "$SRV" 2>/dev/null || true; rm -rf "$WORK"' EXIT

HOST="http://127.0.0.1:$PORT"
AUTH=(-H "X-FengYu-Token: $TOKEN")
for _ in $(seq 1 40); do
  if curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$HOST/api/health" | grep -q 200; then
    break
  fi
  sleep 1
done

fail() { echo "FAIL: $1"; tail -100 server.log; exit 1; }
HEALTH_CODE="$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$HOST/api/health")"
[ "$HEALTH_CODE" = 200 ] || fail "backend health (HTTP $HEALTH_CODE)"
RUNTIME="$(curl -s "${AUTH[@]}" "$HOST/api/plugin-runtime")"
echo "$RUNTIME" | grep -q 'fan.summer.offlinepython' || fail "plugin discovery: $RUNTIME"

printf 'numpy==1.26.4\n' > requirements.txt
PROJECT="$(curl -s "${AUTH[@]}" -F 'files=@requirements.txt' -F 'paths=requirements.txt' \
  "$HOST/api/plugin-runtime/fan.summer.offlinepython/files/upload-directory?access=read-write")"
echo "$PROJECT" | grep -q '"access":"read-write"' || fail "workspace grant: $PROJECT"

GET_BODY="$(python3 -c 'import json,sys; print(json.dumps({"method":"requirements.get","params":{"projectDir":json.loads(sys.argv[1])}}))' "$PROJECT")"
GET_RESULT="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "$GET_BODY" "$HOST/api/plugin-runtime/fan.summer.offlinepython/invoke")"
echo "$GET_RESULT" | grep -q 'numpy==1.26.4' || fail "FileRef resolution: $GET_RESULT"

SAVE_BODY="$(python3 -c 'import json,sys; print(json.dumps({"method":"requirements.save","params":{"projectDir":json.loads(sys.argv[1]),"text":"requests==2.32.4\\n"}}))' "$PROJECT")"
SAVE_RESULT="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "$SAVE_BODY" "$HOST/api/plugin-runtime/fan.summer.offlinepython/invoke")"
echo "$SAVE_RESULT" | grep -q '"success":true' || fail "writable workspace: $SAVE_RESULT"

echo "PASS: Offline Python package discovery + read-write workspace + FileRef Worker bridge"
