#!/usr/bin/env bash
# Host-level end-to-end smoke test. Boots the headless backend jar, probes the
# REST/AI/store surfaces, and exercises the plugin runtime with a committed fixture
# plugin (scripts/fixtures/smoke-plugin) installed through the third-party upload API:
# sandboxed JSON-RPC worker, protocol handshake, FileRef workspace bridge, database
# provisioning, AI tool discovery, uninstall/reinstall, and the shutdown worker reap.
#
# The official plugins (markdown/excel/email/offlinepython) live in the store
# repository now — nothing here builds or installs them.
#
# Prerequisites: toolchain/cli deps installed (`cd toolchain/cli && yarn install`)
# and the shaded jar built (mvn -f FengYu/pom.xml package -DskipTests).
#
# Usage: scripts/e2e-smoke.sh [port] [token]
set -euo pipefail

PORT="${1:-8899}"
TOKEN="${2:-e2e-smoke-token}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIXTURE="$ROOT/scripts/fixtures/smoke-plugin"
FIXTURE_ID="dev.fengyu.smoke"
# Resolve the built jar by glob so this script does not break on every version bump.
# Exactly one jar must match; zero or multiple is an error (avoids ambiguity).
JAR_GLOB="$ROOT/FengYu/target/FengYu-*.jar"
JAR_COUNT=( $JAR_GLOB )
if [ ${#JAR_COUNT[@]} -eq 0 ]; then
  echo "FAIL: no jar matches $JAR_GLOB — build it first (mvn -f FengYu/pom.xml package -DskipTests)"
  exit 1
elif [ ${#JAR_COUNT[@]} -gt 1 ]; then
  echo "FAIL: multiple jars match $JAR_GLOB — clean target/ first (mvn -f FengYu/pom.xml clean)"
  exit 1
fi
JAR="${JAR_COUNT[0]}"

# Build the fixture plugin through the same CLI a third-party developer uses, then
# locate its .fyp + checksum sidecar. The fixture is a static-UI manifest-first
# project, so this needs no toolchain/ui dist and no ~/.m2 SDK artifacts.
if ! node "$ROOT/toolchain/cli/bin/fengyu.mjs" build "$FIXTURE" >/dev/null; then
  echo "FAIL: fengyu build $FIXTURE failed (did you run 'cd toolchain/cli && yarn install'?)"
  exit 1
fi
FYP="$(ls "$FIXTURE/dist/$FIXTURE_ID"-*.fyp)"
[ -f "$FYP" ] || { echo "FAIL: fixture package missing: $FIXTURE/dist/$FIXTURE_ID-*.fyp"; exit 1; }
[ -f "$FYP.sha256" ] || { echo "FAIL: fixture checksum sidecar missing: $FYP.sha256"; exit 1; }

WORK=""
SRV=""
STORE_SRV=""
# Defensive `${VAR:-}` so a trap firing before WORK/SRV are set never expands to rm -rf ""
# or kill "" (the latter would be a no-op, but under set -u an unset var is fatal).
#
# The kill chain is layered: `kill $SRV` SIGTERMs the backend JVM, then pkill -P walks its
# descendants (the plugin-worker grandchildren that the backend spawned) so they cannot orphan.
# Without the pkill -P, a worker JVM that outlived the backend would keep an exclusive lock on its
# embedded DB file and block the `rm -rf` below. The backend's own @PreDestroy normally reaps the
# workers on graceful exit; this trap is the backstop for a SIGKILLed or wedged backend.
trap '
  kill ${SRV:-} 2>/dev/null || true
  if [ -n "${SRV:-}" ]; then
    pkill -P "$SRV" 2>/dev/null || true
  fi
  kill ${STORE_SRV:-} 2>/dev/null || true
  rm -rf "${WORK:-}"
' EXIT

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || echo "")}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

WORK="$(mktemp -d)"
cd "$WORK"

# Pre-seed an embedded H2 datasource config so HeadlessLauncher's startup probe (added by the
# multi-datasource setup wizard) picks APP mode instead of SETUP mode. Without this, a fresh
# working dir has no datasource.properties and the backend boots into the minimal setup-wizard
# context, which excludes PluginController/PluginFileController entirely. Pin the runtime root to
# this temp dir via -Dfengyu.runtime.dir so state stays isolated and the run is repeatable
# (RuntimePaths.root() otherwise resolves to <working-directory>/.fengyu).
DB_FILE="$WORK/.fengyu/database/fengyu"
mkdir -p "$WORK/.fengyu/config" "$(dirname "$DB_FILE")"
cat > "$WORK/.fengyu/config/datasource.properties" <<EOF
db.type=h2
db.url=jdbc:h2:file:${DB_FILE}
db.driver=org.h2.Driver
db.dialect=org.hibernate.dialect.H2Dialect
db.username=sa
db.admin.username=sa
db.file.path=${DB_FILE}
EOF

# Create the pre-seeded database the way the SETUP wizard does: ONE embedded file: connection.
# The H2 TCP server refuses to create databases (no -ifNotExists — a loopback caller must not be
# able to mint a database and run Java aliases in the host JVM), so without this the startup
# probe finds no database, backs up the config, and the backend boots into SETUP mode, which
# serves none of the plugin endpoints this smoke asserts on.
echo "SELECT 1;" | "$JAVA" -cp "$JAR" org.h2.tools.Shell \
  -url "jdbc:h2:file:$DB_FILE" -user sa -password "" >/dev/null 2>&1 \
  || { echo "FAIL: could not create the pre-seeded H2 database at $DB_FILE"; exit 1; }

# Stub Infinia Store on loopback so the smoke covers the store integration without a real
# deployment: the backend's fengyu.store.api-base points here, anonymous catalog browsing
# reads the fixture, and killing the server later in the run exercises store-offline
# degradation. Shapes follow the canonical fixtures pinned by the Java contract tests.
STORE_PORT=8897
python3 "$ROOT/scripts/fixtures/store-stub/store_stub.py" "$STORE_PORT" \
  "$ROOT/scripts/fixtures/store-stub" >/dev/null 2>&1 &
STORE_SRV=$!

"$JAVA" -Dfengyu.runtime.dir="$WORK/.fengyu" \
  -Dfengyu.store.api-base="http://127.0.0.1:$STORE_PORT" \
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

# --- Fixture plugin: third-party upload install ---
# The .fyp + .sha256 sidecar go through the real upload API (the user's local-install
# path). The manifest declares permissions, so confirmPermissions=true is required.
CODE="$(curl -s -o /tmp/e2e-smoke-upload.json -w '%{http_code}' "${AUTH[@]}" \
  -F "file=@$FYP" -F "sidecar=@$FYP.sha256" -F 'confirmPermissions=true' \
  "$H/api/plugin-packages/upload")"
[ "$CODE" = 201 ] || fail "fixture upload returned $CODE: $(cat /tmp/e2e-smoke-upload.json)"
grep -q "\"id\":\"$FIXTURE_ID\"" /tmp/e2e-smoke-upload.json \
  || fail "fixture upload response missing id: $(cat /tmp/e2e-smoke-upload.json)"
echo "PASS: fixture plugin installed via upload API (201 + id)"

# Database provisioning is user-authorized, not implicit — exercise that boundary BEFORE
# the first invoke so the lazily spawned worker picks the provisioned credentials up in
# its process environment.
PROVISION="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-db/provision/$FIXTURE_ID" -d '{}')"
echo "$PROVISION" | grep -q '"provisioned":true' \
  && echo "PASS: fixture database provisioned" || fail "fixture database provisioning: $PROVISION"

# Installed package discovery lists the fixture (upload installs are synchronous, but
# poll briefly so a slow disk cannot flake the run).
runtime_lists_fixture() {
  RUNTIME="$(curl -s "${AUTH[@]}" "$H/api/plugin-runtime")"
  echo "$RUNTIME" | grep -q "$FIXTURE_ID"
}
for _ in $(seq 1 30); do
  runtime_lists_fixture && break
  sleep 1
done
runtime_lists_fixture || fail "Fixture plugin not listed (30s after install): $RUNTIME"

# Worker RPC round-trip: the invoke spawns the sandboxed out-of-process worker, runs the
# protocol-v1 handshake, and dispatches the method.
ECHO="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/$FIXTURE_ID/invoke" \
  -d '{"callId":"smoke","method":"echo","params":{"text":"e2e-smoke-round-trip"}}')"
echo "$ECHO" | grep -q 'e2e-smoke-round-trip' \
  && echo "PASS: fixture worker echo RPC" || fail "fixture echo RPC: $ECHO"

# The provisioned credentials flow into the worker process environment.
DB_ENV="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/$FIXTURE_ID/invoke" \
  -d '{"callId":"smoke","method":"db_env","params":{}}')"
echo "$DB_ENV" | grep -q 'jdbc:' \
  && echo "PASS: provisioned DB credentials reached the worker env" || fail "fixture db_env RPC: $DB_ENV"

# FileRef bridge: a host-granted read-write workspace directory, resolved by the host
# into a native path before dispatch. The worker reads a file the host staged, writes
# one back, and reads it again — the full grant → resolve → sandboxed-IO round trip.
printf 'smoke-workspace-v1\n' > "$WORK/workspace-notes.txt"
GRANT="$(curl -s "${AUTH[@]}" -F "files=@$WORK/workspace-notes.txt" -F 'paths=workspace-notes.txt' \
  "$H/api/plugin-runtime/$FIXTURE_ID/files/upload-directory?access=read-write")"
echo "$GRANT" | grep -q '"access":"read-write"' || fail "fixture workspace grant: $GRANT"
READ_BODY="$(python3 -c 'import json,sys; print(json.dumps({"callId":"smoke","method":"file_read","params":{"dir":json.loads(sys.argv[1]),"name":"workspace-notes.txt"}}))' "$GRANT")"
READ="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/$FIXTURE_ID/invoke" -d "$READ_BODY")"
echo "$READ" | grep -q 'smoke-workspace-v1' \
  && echo "PASS: FileRef read through granted workspace" || fail "fixture file_read: $READ"
WRITE_BODY="$(python3 -c 'import json,sys; print(json.dumps({"callId":"smoke","method":"file_write","params":{"dir":json.loads(sys.argv[1]),"name":"reply.txt","content":"written-by-worker"}}))' "$GRANT")"
curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/$FIXTURE_ID/invoke" -d "$WRITE_BODY" | grep -q '"bytes":' \
  || fail "fixture file_write"
VERIFY_BODY="$(python3 -c 'import json,sys; print(json.dumps({"callId":"smoke","method":"file_read","params":{"dir":json.loads(sys.argv[1]),"name":"reply.txt"}}))' "$GRANT")"
curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/$FIXTURE_ID/invoke" -d "$VERIFY_BODY" | grep -q 'written-by-worker' \
  && echo "PASS: FileRef write + read-back through granted workspace" \
  || fail "fixture file_write read-back"

# The manifest's aiTools surface as agent tools.
curl -s "${AUTH[@]}" "$H/api/agent/tools" | grep -q 'smoke_echo' \
  && echo "PASS: fixture AI tool discovered" || fail "smoke_echo missing from /api/agent/tools"

# token enforcement: no token → 401.
CODE="$(curl -s -o /dev/null -w '%{http_code}' "$H/api/plugin-runtime")"
[ "$CODE" = 401 ] || fail "expected 401 without token, got $CODE"

# unified log surface: the backend's own fengyu.log is listable and tail-readable.
LOGS="$(curl -s "${AUTH[@]}" "$H/api/logs")"
echo "$LOGS" | grep -q '"name":"fengyu.log"' || fail "log listing missing fengyu.log: $LOGS"
TAIL="$(curl -s "${AUTH[@]}" "$H/api/logs/fengyu.log/tail?maxBytes=1024")"
echo "$TAIL" | grep -q '"content"' || fail "log tail missing content: $TAIL"

echo "PASS: health + fixture plugin (install, RPC, FileRef, provision, AI tool) + token auth all OK (port=$PORT)"

# --- Shaded-jar config loading gate ---
# application.yml exposes actuator health,metrics; Spring Boot's default exposure is
# health-only. A 200 here proves the fat jar loaded application.yml (the 4.0.0-rc.1 shade
# bug — spring.factories key collisions dropping the ConfigData listener — regressed
# exactly this; annotation defaults masked it for every fengyu.store.* property).
sleep 3
CODE="$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$H/actuator/metrics")"
[ "$CODE" = 200 ] || fail "actuator/metrics returned $CODE — application.yml is NOT loading in the fat jar"
echo "PASS: shaded jar loads application.yml (actuator/metrics exposed)"

# --- Store chain (stub store on loopback) ---
# Channel status routes through the launch api-base; anonymous catalog browsing reaches the
# stub's fixture; the signed-out account view degrades to authenticated:false without any
# store round-trip. The full signed-in plugin loop (download → install → update → uninstall)
# runs against the store repository's own e2e and stays out of this host smoke.
STATUS="$(curl -s "${AUTH[@]}" "$H/api/store/status")"
echo "$STATUS" | grep -q "127.0.0.1:$STORE_PORT" || fail "store status not routed at the stub: $STATUS"
CATALOG="$(curl -s "${AUTH[@]}" "$H/api/store/catalog")"
echo "$CATALOG" | grep -q 'fan.summer.smoke/stub-plugin' || fail "catalog browse missed the stub fixture: $CATALOG"
ACCOUNT="$(curl -s "${AUTH[@]}" "$H/api/account/me")"
echo "$ACCOUNT" | grep -q '"authenticated":false' || fail "signed-out account view: $ACCOUNT"
echo "PASS: store channel status + anonymous catalog + signed-out account degradation"

# --- FengyuFlow (visual workflows): CRUD, layout round-trip, deterministic manual run ---
# The plan runs json_format — a built-in tool that needs no LLM and no plugin — so this
# probes persistence, input binding and the agent execution path end to end.
WF_CREATE="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST "$H/api/workflows" -d '{
  "name": "Smoke flow",
  "description": "smoke",
  "inputSchema": {"type":"object","properties":{"payload":{"type":"string"}},"required":["payload"]},
  "plan": {
    "goal": "Format {{inputs.payload}}",
    "steps": [
      {"index": 0, "toolName": "json_format", "args": {"json": "{{inputs.payload}}"},
       "description": "Format", "requiresApproval": false}
    ],
    "reasoning": ""
  },
  "layout": {"0": {"x": 10, "y": 20}}
}')"
echo "$WF_CREATE" | grep -q '"id"' || fail "workflow create: $WF_CREATE"
echo "$WF_CREATE" | grep -q '"x":10.0' || fail "workflow layout round-trip: $WF_CREATE"
WF_ID="$(printf '%s' "$WF_CREATE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"

curl -s "${AUTH[@]}" "$H/api/workflows" | grep -q 'Smoke flow' || fail "workflow list after create"

WF_RUN="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' \
  -X POST "$H/api/workflows/$WF_ID/run" -d '{
    "inputs": {"payload": "{\"a\":1}"},
    "config": {"requirePlanApproval": false, "requireStepApproval": false,
               "replanOnFailure": false, "maxReplans": 0, "permissionMode": "full-access"}
  }')"
echo "$WF_RUN" | grep -q '"runId"' || fail "workflow run start: $WF_RUN"
WF_RUN_ID="$(printf '%s' "$WF_RUN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["runId"])')"

wf_done=""
for _ in $(seq 1 30); do
  WF_DETAIL="$(curl -s "${AUTH[@]}" "$H/api/agent/runs/$WF_RUN_ID")"
  WF_STATUS="$(printf '%s' "$WF_DETAIL" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status",""))' 2>/dev/null || true)"
  case "$WF_STATUS" in COMPLETED|FAILED|CANCELLED) wf_done=1; break ;; esac
  sleep 1
done
[ -n "$wf_done" ] || fail "workflow run never reached a terminal state"
[ "$WF_STATUS" = "COMPLETED" ] || fail "workflow run failed ($WF_STATUS): $WF_DETAIL"
# The step result is JSON-escaped inside the detail payload, so assert on the extracted text.
# executions records both the RUNNING and the terminal entry per step — take the terminal one.
WF_RESULT="$(printf '%s' "$WF_DETAIL" | python3 -c '
import json,sys
d = json.load(sys.stdin)
results = [e.get("result") for e in d.get("executions") or [] if e.get("result")]
print(results[-1] if results else "")')"
printf '%s' "$WF_RESULT" | grep -q '"a"' || fail "workflow run result missing formatted JSON: $WF_DETAIL"
echo "PASS: workflow create + manual run + result binding"

# Unified notifications: the run's terminal event fans out through
# AgentNotificationSink → NotificationService into the persisted center, so the
# notification list must now carry an agent-source row linked to the agent page.
NTF="$(curl -s "${AUTH[@]}" "$H/api/notifications?limit=5")"
echo "$NTF" | grep -q '"source":"agent"' || fail "agent terminal notification missing: $NTF"
NTF_COUNT="$(curl -s "${AUTH[@]}" "$H/api/notifications/unread-count")"
echo "$NTF_COUNT" | grep -q '"count":[1-9]' || fail "notification unread count not bumped: $NTF_COUNT"
echo "PASS: agent run terminal → unified host notification"

# Publish exposes the workflow as a run_workflow_* AI tool.
curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/workflows/$WF_ID/publish" -d '{"published": true}' | grep -q '"published":true' \
  || fail "workflow publish"
curl -s "${AUTH[@]}" "$H/api/agent/tools" | grep -q 'run_workflow_' || fail "published workflow not in AI tool catalog"
echo "PASS: published workflow discovered as AI tool"

# Deleting removes the definition (run history is intentionally kept).
curl -s "${AUTH[@]}" -X DELETE "$H/api/workflows/$WF_ID" | grep -q '"ok":true' || fail "workflow delete"
curl -s "${AUTH[@]}" "$H/api/workflows" | grep -q 'Smoke flow' && fail "workflow still listed after delete"
echo "PASS: workflow delete"

# --- Uninstall + reinstall over the tombstone ---
# Uninstall removes the package (and stops the worker); reinstalling the same archive
# through the upload API must work over the recorded uninstall tombstone.
CODE="$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" -X DELETE \
  "$H/api/plugin-packages/$FIXTURE_ID?deleteData=true")"
[ "$CODE" = 204 ] || fail "fixture uninstall returned $CODE (expected 204)"
sleep 1
curl -s "${AUTH[@]}" "$H/api/plugin-runtime" | grep -q "$FIXTURE_ID" \
  && fail "fixture still listed after uninstall"
echo "PASS: fixture plugin uninstalled"

CODE="$(curl -s -o /tmp/e2e-smoke-reupload.json -w '%{http_code}' "${AUTH[@]}" \
  -F "file=@$FYP" -F "sidecar=@$FYP.sha256" -F 'confirmPermissions=true' \
  "$H/api/plugin-packages/upload")"
[ "$CODE" = 201 ] || fail "fixture reinstall returned $CODE: $(cat /tmp/e2e-smoke-reupload.json)"
echo "PASS: fixture plugin reinstalled over the uninstall tombstone"

# Spawn the worker again so the shutdown-reap check below has a live worker to reap
# (a fresh install runs no runtime preflight — only an invoke starts the process).
ECHO2="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/$FIXTURE_ID/invoke" \
  -d '{"callId":"smoke","method":"echo","params":{"text":"reinstall"}}')"
echo "$ECHO2" | grep -q 'reinstall' || fail "fixture echo after reinstall: $ECHO2"

# --- Store-offline degradation (RC chain 1) ---
# Kill the stub store: the app must stay healthy and answer a clean JSON error instead of
# hanging or crashing — installed plugins and anonymous features keep working above.
kill "$STORE_SRV" 2>/dev/null || true
STORE_SRV=""
sleep 1
# A type-filtered request uses a DIFFERENT cache key than the same-key page fetched above
# (cached for 5 minutes — it would legitimately answer 200 from cache with the store down),
# so this probe still exercises the real offline-fetch degradation path.
CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "${AUTH[@]}" "$H/api/store/catalog?type=PLUGIN")"
case "$CODE" in
  5*) echo "PASS: store offline → clean HTTP $CODE degradation" ;;
  *) fail "store-offline catalog returned $CODE, expected a 5xx JSON error" ;;
esac
CODE="$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$H/api/health")"
[ "$CODE" = 200 ] || fail "app health degraded after store went offline ($CODE)"
INSTALLED="$(curl -s "${AUTH[@]}" "$H/api/store/installed")"
echo "$INSTALLED" | grep -q '\[' || fail "installed list must still answer while the store is offline: $INSTALLED"
echo "PASS: app healthy + installed list answers while the store is offline"

# Active orphan check (graceful-shutdown reap): SIGTERM the backend and assert its @PreDestroy
# reaps every plugin worker — no worker JVM may outlive the backend (a survivor would orphan and
# hold resources, e.g. an exclusive embedded-DB lock). The reinstall + echo above left the
# fixture worker running, so there is a real worker to reap. Covers the shutdown-reap path;
# crash/cancel reap need a richer harness (real iframe UI, per-plugin $/cancelRequest,
# AI-tool invocation) that this HTTP smoke does not provide — see the T2-P5 record for the
# exact coverage boundary.
kill "${SRV:-}" 2>/dev/null || true
SRV=""
sleep 3
# Scope the scan to THIS run's temp plugins dir: a developer's long-running backend
# elsewhere on the machine also spawns backend/worker.jar processes that are none of
# this test's business (a global pgrep made the check fail on any active dev host).
ORPHANS="$(pgrep -f "$WORK/.fengyu/plugins/.*/backend/worker.jar" 2>/dev/null || true)"
if [ -n "$ORPHANS" ]; then
  echo "FAIL: orphan plugin-worker process(es) survived backend shutdown: $ORPHANS" >&2
  exit 1
fi
echo "PASS: no orphan plugin-worker processes after backend shutdown"
