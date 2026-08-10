#!/usr/bin/env bash
# Smoke-test a packaged portable Web distribution archive.
#
# Usage: scripts/test-web-release.sh <archive.zip>
# Extracts the archive, asserts the expected layout (Infinia.jar, executable run.sh, and the four
# official .fyp plugins with valid checksum sidecars), boots run.sh on a free loopback port, reads FENGYU_PORT= from stdout,
# asserts that the Vue shell (/) and /api/health both return 200, then kills the backend.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <archive.zip>" >&2
  exit 2
fi

ARCHIVE="$1"
[ -f "$ARCHIVE" ] || { echo "FAIL: archive not found: $ARCHIVE" >&2; exit 1; }
# Resolve to an absolute path before any cd below changes the working directory.
ARCHIVE="$(cd "$(dirname "$ARCHIVE")" && pwd)/$(basename "$ARCHIVE")"

WORK="$(mktemp -d)"
SRV=""
trap 'kill "$SRV" 2>/dev/null || true; rm -rf "$WORK"' EXIT

# Unzip into the work dir (zip stores a top-level Infinia-<version>-web/ folder).
( cd "$WORK" && unzip -q "$ARCHIVE" )
PKG_DIR="$(find "$WORK" -maxdepth 1 -type d -name 'Infinia-*-web' | head -1)"
[ -n "$PKG_DIR" ] || { echo "FAIL: no Infinia-*-web directory in archive" >&2; exit 1; }

fail() { echo "FAIL: $1" >&2; exit 1; }
verify_sha256() {
  local directory="$1"
  local sidecar="$2"
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$directory" && sha256sum -c "$sidecar" >/dev/null)
  elif command -v shasum >/dev/null 2>&1; then
    (cd "$directory" && shasum -a 256 -c "$sidecar" >/dev/null)
  else
    fail "neither sha256sum nor shasum is available"
  fi
}

# --- layout checks ---
[ -f "$PKG_DIR/Infinia.jar" ]      || fail "Infinia.jar missing"
[ -x "$PKG_DIR/run.sh" ]           || fail "run.sh missing or not executable"
[ -f "$PKG_DIR/run.bat" ]          || fail "run.bat missing"
[ -f "$PKG_DIR/README.md" ]        || fail "README.md missing"
for id in markdown excel email offlinepython; do
  archives=("$PKG_DIR/plugins"/fan.summer."$id"-*.fyp)
  [ -f "${archives[0]}" ] || fail "official plugin fan.summer.$id missing"
  [ "${#archives[@]}" -eq 1 ] || fail "expected exactly one fan.summer.$id package"
  [ -f "${archives[0]}.sha256" ] || fail "checksum sidecar for fan.summer.$id missing"
  verify_sha256 "$PKG_DIR/plugins" "$(basename "${archives[0]}.sha256")" \
    || fail "checksum mismatch for fan.summer.$id"
done
echo "PASS: layout (jar, run.sh, run.bat, README, plugins + SHA-256 sidecars)"

# --- boot on a free port, read FENGYU_PORT= ---
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || echo "")}"
cd "$PKG_DIR"
./run.sh --port=0 > "$WORK/server.log" 2>&1 &
SRV=$!

# Wait up to 40s for the FENGYU_PORT= line.
PORT=""
for _ in $(seq 1 40); do
  PORT="$(grep -o 'FENGYU_PORT=[0-9]*' "$WORK/server.log" | head -1 | cut -d= -f2 || true)"
  [ -n "$PORT" ] && break
  # Bail early if the launcher exited.
  kill -0 "$SRV" 2>/dev/null || { echo "FAIL: run.sh exited before reporting a port" >&2; tail -40 "$WORK/server.log"; exit 1; }
  sleep 1
done
[ -n "$PORT" ] || { echo "FAIL: no FENGYU_PORT= within 40s" >&2; tail -40 "$WORK/server.log"; exit 1; }

BASE="http://127.0.0.1:$PORT"

# run.sh now generates a per-launch token by default (B2 security fix: auth no longer disabled
# when no --token is passed). The generated token is printed to stderr, which run.sh redirects
# into server.log. Extract it so the protected-endpoint checks below can authenticate.
TOKEN="$(grep -o 'Generated per-launch token.*: zf-[0-9a-f-]*' "$WORK/server.log" | head -1 | sed 's/.*: //')"
[ -n "$TOKEN" ] || { echo "FAIL: no generated token found in server.log (run.sh changed?)" >&2; tail -40 "$WORK/server.log"; exit 1; }
AUTH=(-H "X-FengYu-Token: $TOKEN")

# Wait for /api/health 200 (health bypasses token auth).
ready=0
for _ in $(seq 1 40); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/health" 2>/dev/null || true)"
  [ "$code" = "200" ] && { ready=1; break; }
  sleep 1
done
[ "$ready" = "1" ] || { echo "FAIL: /api/health never returned 200" >&2; tail -40 "$WORK/server.log"; exit 1; }
echo "PASS: /api/health 200 (port=$PORT)"

# The Vue shell (/) is served from the bundled jar (SpaForwardController → index.html).
# It is token-protected (not in the bypass list), so pass the generated token.
code="$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$BASE/" 2>/dev/null || true)"
[ "$code" = "200" ] || { echo "FAIL: / returned $code (expected 200 with token)" >&2; exit 1; }
echo "PASS: / serves Vue shell (200)"

# Confirm auth is actually enforced: a tokenless request to a protected endpoint must 401.
code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/plugins" 2>/dev/null || true)"
[ "$code" = "401" ] || { echo "FAIL: tokenless /api/plugins returned $code (expected 401 — auth should be on)" >&2; exit 1; }
echo "PASS: token auth enforced (401 without X-FengYu-Token)"

echo "ALL SMOKE CHECKS PASSED"
