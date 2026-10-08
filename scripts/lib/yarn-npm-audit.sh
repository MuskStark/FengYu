#!/usr/bin/env bash
# EOL-policy audit gate (AGENTS.md): `corepack yarn npm audit` in the given project
# directory, wrapped in bounded network-only retries.
#
# Usage: scripts/lib/yarn-npm-audit.sh <project-dir>
#   <project-dir> — package root to audit, relative to the repo root (frontend,
#                   desktop/electron). Runs from the repo root so Windows runners
#                   (git-bash) never needs a workspace path env.
#
# Any advisory — including maintenance/EOL deprecations without an exploitable CVE —
# fails the build (the release/portable workflows' EOL gate). The npm audit endpoint
# has flaky windows (2026-09-04 incident: 60s socket timeouts on the bulk advisories
# route), so transient NETWORK symptoms only get three bounded retries — a real
# advisory is a deterministic failure and still fails on the first attempt.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <project-dir>" >&2
  exit 2
fi

PROJECT_DIR="$1"
[ -f "$PROJECT_DIR/package.json" ] || { echo "FAIL: no package.json under $PROJECT_DIR" >&2; exit 1; }
cd "$PROJECT_DIR"

# Yarn's default httpTimeout (60s) is the real flake source: during the 2026-09-04 npm
# incident the bulk-advisories route ANSWERED, just in 60-300s — the same audit with a
# 5-minute budget completed cleanly. The two official mirrors (registry.yarnpkg.com,
# registry.npmjs.org) flapped independently with timeouts AND 503s, so retries alternate
# between them; the advisory data is identical.
export YARN_HTTP_TIMEOUT="${YARN_HTTP_TIMEOUT:-300000}"

for attempt in 1 2 3; do
  case "$attempt" in
    2) export YARN_NPM_REGISTRY_SERVER="https://registry.npmjs.org" ;;
    *) unset YARN_NPM_REGISTRY_SERVER ;;
  esac
  log="$(mktemp)"
  if corepack yarn npm audit >"$log" 2>&1; then
    cat "$log"; rm -f "$log"; exit 0
  fi
  cat "$log"
  if ! grep -qE 'RequestError|Timeout awaiting|ENOTFOUND|EAI_AGAIN|ECONNRESET|ETIMEDOUT|socket hang up|YN0035|Service Unavailable' "$log"; then
    rm -f "$log"; exit 1
  fi
  rm -f "$log"
  echo "audit network/service failure (attempt $attempt/3); retrying in 60s" >&2
  sleep 60
done
exit 1
