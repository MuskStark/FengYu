#!/usr/bin/env bash
# Focused host smoke for the Offline Python writable-workspace and FileRef bridge.
set -euo pipefail

PORT="${1:-8900}"
TOKEN="${2:-offlinepython-smoke-token}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Resolve by glob (like e2e-smoke.sh) so the script does not break on every version bump:
# exactly one JAR and one .fyp must match.
JAR_GLOB="$ROOT/FengYu/target/FengYu-*.jar"
JAR_COUNT=( $JAR_GLOB )
if [ ${#JAR_COUNT[@]} -ne 1 ]; then
  echo "FAIL: expected exactly one jar matching $JAR_GLOB — build it first (mvn -f FengYu/pom.xml package -DskipTests)" >&2
  exit 1
fi
JAR="${JAR_COUNT[0]}"
# dist/ may carry several historical versions — smoke the newest by version sort.
PACKAGE="$(ls "$ROOT"/OfficialPlugins/plugin-offlinepython/dist/fan.summer.offlinepython-*.fyp 2>/dev/null | sort -V | tail -1)"
[ -n "$PACKAGE" ] || {
  echo "FAIL: no offlinepython .fyp — build it first (fengyu build OfficialPlugins/plugin-offlinepython)" >&2
  exit 1
}
# The official seeder verifies the archive against its checksum sidecar — stage both.
[ -f "$PACKAGE.sha256" ] || { echo "FAIL: missing checksum sidecar: $PACKAGE.sha256"; exit 1; }

WORK="$(mktemp -d)"
OFFICIAL_DIR="$WORK/official-plugins"
mkdir -p "$OFFICIAL_DIR" "$WORK/.fengyu/config" "$WORK/.fengyu/database"
cp "$PACKAGE" "$PACKAGE.sha256" "$OFFICIAL_DIR/"

DB_FILE="$WORK/.fengyu/database/fengyu"
cat > "$WORK/.fengyu/config/datasource.properties" <<EOF
db.type=h2
db.url=jdbc:h2:file:${DB_FILE}
db.driver=org.h2.Driver
db.dialect=org.hibernate.dialect.H2Dialect
db.username=sa
db.file.path=${DB_FILE}
EOF

# Create the pre-seeded database the way the SETUP wizard does (one embedded file: connection).
# The H2 TCP server refuses to create databases (no -ifNotExists), so without this the startup
# probe fails and the backend boots into SETUP mode with none of the plugin endpoints.
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home)}"
JAVA="$JAVA_HOME/bin/java"
echo "SELECT 1;" | "$JAVA" -cp "$JAR" org.h2.tools.Shell \
  -url "jdbc:h2:file:$DB_FILE" -user sa -password "" >/dev/null 2>&1 \
  || { echo "FAIL: could not create the pre-seeded H2 database at $DB_FILE"; exit 1; }

cd "$WORK"
"$JAVA" -Dfengyu.plugins.official-directory="$OFFICIAL_DIR" \
  -Dfengyu.plugins.directory="$WORK/.fengyu/plugins" \
  -Dfengyu.plugins.data-directory="$WORK/.fengyu/plugin-data" \
  -cp "$JAR" fan.summer.fengyu.HeadlessLauncher --port="$PORT" --token="$TOKEN" > server.log 2>&1 &
SRV=$!
# SIGTERM the backend, then reap its worker grandchildren (pkill -P) so a worker JVM cannot
# outlive the backend and keep an exclusive lock on its embedded DB file — that would block the
# rm -rf. The backend's @PreDestroy normally handles this on graceful exit; this trap is the
# backstop for a SIGKILLed or wedged backend. offlinepython also spawns pip grandchildren.
trap '
  kill ${SRV:-} 2>/dev/null || true
  if [ -n "${SRV:-}" ]; then
    pkill -P "$SRV" 2>/dev/null || true
  fi
  rm -rf "$WORK"
' EXIT

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

GET_BODY="$(python3 -c 'import json,sys; print(json.dumps({"callId":"smoke","method":"requirementsGet","params":{"projectDir":json.loads(sys.argv[1])}}))' "$PROJECT")"
GET_RESULT="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "$GET_BODY" "$HOST/api/plugin-runtime/fan.summer.offlinepython/invoke")"
echo "$GET_RESULT" | grep -q 'numpy==1.26.4' || fail "FileRef resolution: $GET_RESULT"

SAVE_BODY="$(python3 -c 'import json,sys; print(json.dumps({"callId":"smoke","method":"requirementsSave","params":{"projectDir":json.loads(sys.argv[1]),"text":"requests==2.32.4\\n"}}))' "$PROJECT")"
SAVE_RESULT="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "$SAVE_BODY" "$HOST/api/plugin-runtime/fan.summer.offlinepython/invoke")"
echo "$SAVE_RESULT" | grep -q '"success":true' || fail "writable workspace: $SAVE_RESULT"

echo "PASS: Offline Python package discovery + read-write workspace + FileRef Worker bridge"
