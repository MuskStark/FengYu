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
JAR="$ROOT/ZhiFlow/target/ZhiFlow-4.0.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
  echo "FAIL: jar not found at $JAR — build it first (mvn -f ZhiFlow/pom.xml package -DskipTests)"
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || echo "")}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

WORK="$(mktemp -d)"
cd "$WORK"
"$JAVA" -cp "$JAR" fan.summer.zhiflow.HeadlessLauncher --port="$PORT" --token="$TOKEN" > server.log 2>&1 &
SRV=$!
trap 'kill $SRV 2>/dev/null; rm -rf "$WORK"' EXIT

H="http://127.0.0.1:$PORT"
AUTH=(-H "X-ZhiFlow-Token: $TOKEN")

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
