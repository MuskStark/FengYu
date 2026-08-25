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
# Plugin UIs install with Yarn 4 through corepack (Node >=25 dropped the bundled
# corepack — install it standalone there: `npm install -g corepack`).
command -v corepack >/dev/null 2>&1 \
  || { echo "FAIL: corepack is required (npm install -g corepack)" >&2; exit 1; }
corepack enable >/dev/null 2>&1 || true
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

# Build the official plugins through the CLI into each plugin's dist/,
# then stage their .fyp outputs and required checksum sidecars into a single official-packages directory the
# host is pointed at. This replaces the old per-plugin shell packager.
OFFICIAL_DIR="$(mktemp -d)"
# WORK is assigned below; pre-declare so the EXIT trap can reference it safely even if the
# script is interrupted between setting the trap and assigning WORK.
WORK=""
SRV=""
for plugin in markdown excel email offlinepython; do
  if ! node "$ROOT/toolchain/cli/bin/fengyu.mjs" build "$ROOT/OfficialPlugins/plugin-$plugin" >/dev/null; then
    echo "FAIL: fengyu build OfficialPlugins/plugin-$plugin failed"
    rm -rf "$OFFICIAL_DIR"
    exit 1
  fi
done
mkdir -p "$OFFICIAL_DIR"
for fyp in "$ROOT"/OfficialPlugins/plugin-*/dist/*.fyp; do
  [ -f "$fyp.sha256" ] || { echo "FAIL: missing checksum sidecar: $fyp.sha256"; exit 1; }
  cp "$fyp" "$fyp.sha256" "$OFFICIAL_DIR/"
done
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
  kill ${SMTP_SINK:-} 2>/dev/null || true
  rm -rf "${WORK:-}" "$OFFICIAL_DIR"
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

"$JAVA" -Dfengyu.runtime.dir="$WORK/.fengyu" \
  -Dfengyu.plugins.official-directory="$OFFICIAL_DIR" \
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
  -d '{"callId":"smoke","method":"render","params":{"markdown":"# Hello\n\n**bold**"}}')"
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
OPB_GET_BODY="$(python3 -c 'import json,sys; print(json.dumps({"callId":"smoke","method":"requirementsGet","params":{"projectDir":json.loads(sys.argv[1])}}))' "$OPB_PROJECT")"
OPB_GET="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.offlinepython/invoke" -d "$OPB_GET_BODY")"
echo "$OPB_GET" | grep -q 'numpy==1.26.4' || fail "offlinepython FileRef resolution: $OPB_GET"
OPB_SAVE_BODY="$(python3 -c 'import json,sys; print(json.dumps({"callId":"smoke","method":"requirementsSave","params":{"projectDir":json.loads(sys.argv[1]),"text":"requests==2.32.4\\n"}}))' "$OPB_PROJECT")"
curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.offlinepython/invoke" -d "$OPB_SAVE_BODY" | grep -q '"success":true' \
  && echo "PASS: offlinepython writable workspace + FileRef bridge" || fail "offlinepython requirements save"

# --- Excel plugin (web upload -> analyze -> split -> archive) ---

# /api/plugins lists Excel — closes Task 11's deferred registration check.
echo "$RUNTIME" | grep -q 'fan.summer.excel' || fail "Excel plugin not listed"
echo "PASS: excel plugin registered"

# plugin-browser was removed in favor of the host-embedded (desktop-only) browser capability.
# It must NOT be registered in web mode.
if echo "$RUNTIME" | grep -q 'fan.summer.browser'; then
  fail "fan.summer.browser should not be registered after plugin removal"
fi
echo "PASS: browser plugin correctly absent"

# Email Center is seeded as an isolated .fyp and its Worker answers through the official SDK protocol.
echo "$RUNTIME" | grep -q 'fan.summer.email' || fail "Email Center plugin not listed"
# Database access is intentionally user-authorized rather than implicit at install/start. Exercise
# that public boundary before starting the Email Worker, then verify the ACTIVE credentials flow
# into its process environment by making a real database-backed RPC.
EMAIL_DB="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-db/provision/fan.summer.email" -d '{}')"
echo "$EMAIL_DB" | grep -q '"provisioned":true' \
  && echo "PASS: email database provisioned" || fail "email database provisioning: $EMAIL_DB"
EMAIL_ACCOUNTS="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.email/invoke" -d '{"callId":"smoke","method":"email_accounts_list","params":{}}')"
echo "$EMAIL_ACCOUNTS" | grep -q '"success":true' \
  && echo "PASS: email worker discovered" || fail "email account RPC: $EMAIL_ACCOUNTS"

# Email batch preview parses the final filename tag and resolves the attachment/group intersection.
EAST_TAG="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.email/invoke" \
  -d '{"callId":"smoke","method":"email_tag_save","params":{"name":"East"}}')"
GROUP_TAG="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.email/invoke" \
  -d '{"callId":"smoke","method":"email_tag_save","params":{"name":"Smoke recipients"}}')"
CONTACT="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.email/invoke" \
  -d '{"callId":"smoke","method":"email_contact_save","params":{"email":"smoke@example.com","nickname":"Smoke"}}')"
EAST_ID="$(printf '%s' "$EAST_TAG" | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag"]["id"])')"
GROUP_ID="$(printf '%s' "$GROUP_TAG" | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag"]["id"])')"
CONTACT_ID="$(printf '%s' "$CONTACT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["contact"]["id"])')"
ASSIGN_BODY="$(python3 -c 'import json,sys; print(json.dumps({"callId":"smoke","method":"email_tags_assign","params":{"contactIds":[int(sys.argv[1])],"tagIds":[int(sys.argv[2]),int(sys.argv[3])]}}))' "$CONTACT_ID" "$EAST_ID" "$GROUP_ID")"
curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.email/invoke" -d "$ASSIGN_BODY" | grep -q '"success":true' \
  || fail "email tag assignment"
printf 'report' > "$WORK/report_East.pdf"
EMAIL_DIR="$(curl -s "${AUTH[@]}" -F "files=@$WORK/report_East.pdf" -F 'paths=report_East.pdf' \
  "$H/api/plugin-runtime/fan.summer.email/files/upload-directory")"
PREVIEW_BODY="$(python3 -c 'import json,sys; print(json.dumps({"callId":"smoke","method":"email_batch_preview","params":{"accountId":1,"recipientGroupTagIds":[int(sys.argv[1])],"ccGroupTagIds":[],"inputDirectory":json.loads(sys.argv[2]),"commonAttachments":[],"subject":"Smoke","plainText":"Preview"}}))' "$GROUP_ID" "$EMAIL_DIR")"
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
ANALYZE_BODY="$(python3 -c 'import json,sys; print(json.dumps({"callId":"smoke","method":"analyze","params":{"session":"e2e-smoke","sourceFile":json.loads(sys.argv[1])}}))' "$UP")"
SPLIT_BODY="$(python3 -c 'import json,sys; print(json.dumps({"callId":"smoke","method":"split","params":{"session":"e2e-smoke","sourceFile":json.loads(sys.argv[1]),"outputDir":json.loads(sys.argv[2])}}))' "$UP" "$OUT")"
OUT_REF="$(printf '%s' "$OUT" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')"

curl -s "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "$ANALYZE_BODY" "$H/api/plugin-runtime/fan.summer.excel/invoke" | grep -q '"success":true' \
  && echo "PASS: excel analyze" || fail "excel analyze"

curl -s "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "$SPLIT_BODY" "$H/api/plugin-runtime/fan.summer.excel/invoke" | grep -q '"success":true' \
  && echo "PASS: excel split" || fail "excel split"

curl -s "${AUTH[@]}" "$H/api/plugin-runtime/fan.summer.excel/files/export/$OUT_REF" -o "$WORK/r.zip"
# List to a file first: grep -q closing the pipe early can SIGPIPE unzip under pipefail.
unzip -l "$WORK/r.zip" > "$WORK/r-zip-list.txt" 2>&1
grep -q '\.xlsx' "$WORK/r-zip-list.txt" && echo "PASS: excel archive" \
  || fail "excel archive (zip bytes=$(wc -c < "$WORK/r.zip" | tr -d ' '), listing: $(cat "$WORK/r-zip-list.txt"))"

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

# --- FengyuFlow × Excel complex split: one node configures ALL rules + the file path ---
# The canvas shape users actually build: complex_config(filePath + entries[]) chained into
# excel_execute. Both the workbook and output directory ride run-scoped native-path grants; the
# step args reference them as @file: placeholders. This is the exact desktop-picker path: typing
# an arbitrary native output path is not enough because sandboxed workers only see host-authorized
# FileRefs. Assert on the host directory after the run so a false-positive plugin result cannot
# hide a missing writable-directory bridge.
# Also proves plugin failures surface their localized reason in run details.
CX_OUTPUT="$WORK/native-flow-output"
mkdir -p "$CX_OUTPUT"
CX_RUN="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' \
  -X POST "$H/api/agent/run" -d '{
    "goal": "complex split",
    "config": {"requirePlanApproval": false, "requireStepApproval": false,
               "replanOnFailure": false, "maxReplans": 0, "permissionMode": "full-access"},
    "files": [{"name": "sample", "nativePath": "'"$XLSX"'", "kind": "file"},
              {"name": "outputDir", "nativePath": "'"$CX_OUTPUT"'", "kind": "directory",
               "writableDirectory": true}],
    "workflow": {"goal": "complex split", "reasoning": "", "steps": [
      {"index": 0, "toolName": "excel_complex_config",
       "args": {"action": "add", "filePath": "@file:sample",
                "entries": [{"sheetName": "Alpha", "headerIndex": 1, "columnName": "region"}]},
       "description": "rules", "requiresApproval": false, "dependsOn": []},
      {"index": 1, "toolName": "excel_execute",
       "args": {"outputDir": "@file:outputDir"},
       "description": "split", "requiresApproval": false, "dependsOn": [0]}
    ]}
  }')"
echo "$CX_RUN" | grep -q '"runId"' || fail "complex-split run start: $CX_RUN"
CX_RUN_ID="$(printf '%s' "$CX_RUN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["runId"])')"
cx_done=""
for _ in $(seq 1 30); do
  CX_DETAIL="$(curl -s "${AUTH[@]}" "$H/api/agent/runs/$CX_RUN_ID")"
  CX_STATUS="$(printf '%s' "$CX_DETAIL" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status",""))' 2>/dev/null || true)"
  case "$CX_STATUS" in COMPLETED|FAILED|CANCELLED) cx_done=1; break ;; esac
  sleep 1
done
[ -n "$cx_done" ] || fail "complex-split run never reached a terminal state"
[ "$CX_STATUS" = "COMPLETED" ] || fail "complex-split run failed ($CX_STATUS): $CX_DETAIL"
CX_RESULT="$(printf '%s' "$CX_DETAIL" | python3 -c '
import json,sys
d = json.load(sys.stdin)
results = [e.get("result") for e in d.get("executions") or [] if e.get("result")]
print(results[-1] if results else "")')"
printf '%s' "$CX_RESULT" | grep -q 'sample_east' || fail "complex-split output missing sample_east: $CX_DETAIL"
find "$CX_OUTPUT" -maxdepth 1 -type f -name 'sample_east*.xlsx' | grep -q . \
  || fail "complex-split native output directory remained empty: $CX_OUTPUT"
echo "PASS: excel complex split via single workflow node (file + writable directory grants)"

# --- FengyuFlow × Excel split → Email batch send (the ordinary-user template chain) ---
# Full chain through ONE workflow run with run-scoped file grants: an uploaded workbook
# (@file:workbook placeholder), a host-minted cross-plugin shared dir (@file:outputDir),
# the excel complex split writing into it, the email batch prepare reading from it, and
# confirm_send dispatching to a local SMTP sink. Proves the whole template scenario.
cat > "$WORK/smtp_sink.py" << 'PYEOF'
import socket, sys, threading

port, log_path = int(sys.argv[1]), sys.argv[2]

def handle(conn):
    f = conn.makefile('rb')
    conn.sendall(b'220 e2e-sink\r\n')
    in_data = False
    while True:
        line = f.readline()
        if not line:
            break
        text = line.decode('utf-8', 'replace').rstrip('\r\n')
        if in_data:
            if text == '.':
                in_data = False
                with open(log_path, 'a') as log:
                    log.write('MSG\n')
                conn.sendall(b'250 accepted\r\n')
            continue
        upper = text.upper()
        if upper.startswith('EHLO'):
            conn.sendall(b'250-e2e-sink\r\n250 8BITMIME\r\n')
        elif upper.startswith('HELO'):
            conn.sendall(b'250 e2e-sink\r\n')
        elif upper.startswith('DATA'):
            in_data = True
            conn.sendall(b'354 end\r\n')
        elif upper.startswith('RCPT'):
            with open(log_path, 'a') as log:
                log.write(text + '\n')
            conn.sendall(b'250 OK\r\n')
        elif upper.startswith('QUIT'):
            conn.sendall(b'221 bye\r\n')
            break
        else:
            conn.sendall(b'250 OK\r\n')
    conn.close()

server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(('127.0.0.1', port))
server.listen(8)
while True:
    conn, _ = server.accept()
    threading.Thread(target=handle, args=(conn,), daemon=True).start()
PYEOF
SMTP_PORT=8898
python3 "$WORK/smtp_sink.py" "$SMTP_PORT" "$WORK/smtp.log" >/dev/null 2>&1 &
SMTP_SINK=$!

# Sender account pointing at the sink (plain SMTP; password is required by the plugin).
SMTP_ACCOUNT="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/plugin-runtime/fan.summer.email/invoke" \
  -d '{"callId":"smoke","method":"email_account_save","params":{"displayName":"Smoke","email":"sender@example.com","password":"sink","smtpHost":"127.0.0.1","smtpPort":'"$SMTP_PORT"',"smtpSecurity":"NONE"}}')"
echo "$SMTP_ACCOUNT" | grep -q '"success":true' || fail "email account save: $SMTP_ACCOUNT"
ACCOUNT_ID="$(printf '%s' "$SMTP_ACCOUNT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["account"]["id"])')"

# The workflow definition mirrors the built-in canvas template: four steps, {{inputs.*}}
# placeholders, and the send step mapped to the prepare step's nested confirmation id.
AI_UPLOAD="$(curl -s "${AUTH[@]}" -F "file=@$XLSX" "$H/api/ai/files/upload")"
echo "$AI_UPLOAD" | grep -q 'fan.summer.excel' || fail "ai file upload fan-out: $AI_UPLOAD"
cat > "$WORK/flow-create.json" << 'JSONEOF'
{
  "name": "Split then batch email",
  "description": "e2e",
  "inputSchema": {"type":"object","required":["workbook","rules","accountId","recipientTagIds","subject"],
    "properties":{"workbook":{"type":"string","format":"fengyu-file"},
      "rules":{"type":"array","items":{"type":"object","required":["sheetName","columnName"],
        "properties":{"sheetName":{"type":"string"},"columnName":{"type":"string"}}}},
      "outputDir":{"type":"string"},"accountId":{"type":"integer"},
      "recipientTagIds":{"type":"array"},"subject":{"type":"string"},"body":{"type":"string"}}},
  "plan": {"goal": "Split the workbook by the configured rules, then batch email", "reasoning": "",
    "steps": [
      {"index": 0, "toolName": "excel_complex_config",
       "args": {"action": "add", "filePath": "{{inputs.workbook}}", "entries": "{{inputs.rules}}"},
       "description": "rules", "requiresApproval": false, "dependsOn": []},
      {"index": 1, "toolName": "excel_execute",
       "args": {"outputDir": "{{inputs.outputDir}}"},
       "description": "split", "requiresApproval": false, "dependsOn": [0]},
      {"index": 2, "toolName": "email_send_batch",
       "args": {"accountId": "{{inputs.accountId}}", "recipientGroupTagIds": "{{inputs.recipientTagIds}}",
                "ccGroupTagIds": [], "inputDirectory": "{{inputs.outputDir}}",
                "subject": "{{inputs.subject}}", "plainText": "{{inputs.body}}"},
       "description": "prepare batch", "requiresApproval": false, "dependsOn": [1]},
      {"index": 3, "toolName": "confirm_send",
       "args": {"confirmationId": "{{steps.2.result.confirmation.confirmationId}}"},
       "description": "send batch", "requiresApproval": true, "dependsOn": [2]}
    ]}
}
JSONEOF
SE_CREATE="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST "$H/api/workflows" -d @"$WORK/flow-create.json")"
SE_ID="$(printf '%s' "$SE_CREATE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
[ -n "$SE_ID" ] || fail "split+email workflow create: $SE_CREATE"

# Two split rules — one per worksheet — exercising the multi-rule complex split the
# canvas template ships; the plugin defaults an omitted headerIndex to the first row.
SE_RUN_BODY="$(python3 -c 'import json,sys; print(json.dumps({
  "inputs": {"rules": [{"sheetName": "Alpha", "columnName": "region"},
                       {"sheetName": "Beta", "columnName": "dept"}],
             "accountId": int(sys.argv[1]), "recipientTagIds": [int(sys.argv[2])],
             "subject": "E2E split batch", "body": "hello from the workflow",
             "workbook": "@file:workbook", "outputDir": "@file:outputDir"},
  "config": {"requirePlanApproval": False, "requireStepApproval": False,
             "replanOnFailure": False, "maxReplans": 0, "permissionMode": "full-access"},
  "files": [{"name": "workbook", "refs": json.loads(sys.argv[3])},
            {"name": "outputDir", "createSharedDirectory": True}]
}))' "$ACCOUNT_ID" "$GROUP_ID" "$AI_UPLOAD")"
SE_RUN="$(curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
  "$H/api/workflows/$SE_ID/run" -d "$SE_RUN_BODY")"
SE_RUN_ID="$(printf '%s' "$SE_RUN" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("runId",""))')"
[ -n "$SE_RUN_ID" ] || fail "split+email run start: $SE_RUN"

se_done=""
for _ in $(seq 1 90); do
  SE_DETAIL="$(curl -s "${AUTH[@]}" "$H/api/agent/runs/$SE_RUN_ID")"
  SE_STATUS="$(printf '%s' "$SE_DETAIL" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status",""))' 2>/dev/null || true)"
  case "$SE_STATUS" in
    # The send step is authored requiresApproval — the human go-ahead the two-phase
    # email protocol needs. Play the user clicking 批准 in the run panel.
    AWAITING_PLAN_APPROVAL|AWAITING_STEP_APPROVAL)
      curl -s "${AUTH[@]}" -H 'Content-Type: application/json' -X POST \
        "$H/api/agent/$SE_RUN_ID/approve" -d '{}' >/dev/null ;;
    COMPLETED|FAILED|CANCELLED) se_done=1; break ;;
  esac
  sleep 1
done
[ -n "$se_done" ] || fail "split+email run never reached a terminal state (last: $SE_STATUS): $SE_DETAIL"
[ "$SE_STATUS" = "COMPLETED" ] || fail "split+email run failed ($SE_STATUS): $SE_DETAIL"
printf '%s' "$SE_DETAIL" > "$WORK/se-detail.json"
python3 -c 'import json,sys
d = json.load(open(sys.argv[1]))
steps = {e["index"]: e.get("status") for e in d.get("executions") or []}
assert all(steps.get(i) == "COMPLETED" for i in range(4)), steps
results = [e.get("result") for e in d.get("executions") or [] if e.get("result")]
assert any("sample_east" in r for r in results), results
assert any("sample_sales" in r for r in results), results
assert any("confirmationId" in r for r in results), results' "$WORK/se-detail.json" \
  || fail "split+email step assertions: $SE_DETAIL"
# The send step dispatched to the local sink: the east file's recipient received mail.
sleep 1
grep -q 'RCPT TO:<smoke@example.com>' "$WORK/smtp.log" \
  || fail "split+email SMTP sink saw no recipient (log: $(cat "$WORK/smtp.log" 2>/dev/null))"
grep -c '^MSG$' "$WORK/smtp.log" | grep -q '^[1-9]' \
  || fail "split+email SMTP sink accepted no messages"
echo "PASS: excel split → email batch prepare → confirm_send through one workflow run (shared dir + file grants)"
kill "$SMTP_SINK" 2>/dev/null || true
SMTP_SINK=""

# Active orphan check (graceful-shutdown reap): SIGTERM the backend and assert its @PreDestroy
# reaps every plugin worker — no worker JVM may outlive the backend (a survivor would orphan and
# hold resources, e.g. an exclusive embedded-DB lock). Covers the shutdown-reap path; crash/cancel
# reap need a richer harness (real iframe UI, per-plugin $/cancelRequest, AI-tool invocation) that
# this HTTP smoke does not provide — see the T2-P5 record for the exact coverage boundary.
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
