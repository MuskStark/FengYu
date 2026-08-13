#!/usr/bin/env bash
# Build every official plugin, stage the resulting .fyp + checksum pair, and
# install it into a running local FengYu development backend.
#
# Usage:
#   ./dev-install-official-plugins.sh [--base-url URL] [--token TOKEN] [--skip-tests]
#
# Environment alternatives:
#   FENGYU_BASE_URL  Backend URL (default: http://127.0.0.1:24056)
#   FENGYU_TOKEN     Value for the X-FengYu-Token header (default: empty)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${FENGYU_BASE_URL:-http://127.0.0.1:24056}"
TOKEN="${FENGYU_TOKEN:-}"
SKIP_TESTS=0
OFFICIAL_PLUGINS=(markdown excel email offlinepython)
PACKAGE_DIR="$ROOT/OfficialPlugins/target/packages"
CLI="$ROOT/toolchain/cli/bin/fengyu.mjs"

usage() {
  echo "Usage: $0 [--base-url URL] [--token TOKEN] [--skip-tests]"
  echo
  echo "Environment:"
  echo "  FENGYU_BASE_URL  Backend URL (default: http://127.0.0.1:24056)"
  echo "  FENGYU_TOKEN     Value for the X-FengYu-Token header"
}

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url)
      [[ $# -ge 2 ]] || fail "--base-url requires a value"
      BASE_URL="$2"
      shift 2
      ;;
    --token)
      [[ $# -ge 2 ]] || fail "--token requires a value"
      TOKEN="$2"
      shift 2
      ;;
    --skip-tests)
      SKIP_TESTS=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown option: $1 (use --help for usage)"
      ;;
  esac
done

BASE_URL="${BASE_URL%/}"

command -v node >/dev/null 2>&1 || fail "node is required"
command -v curl >/dev/null 2>&1 || fail "curl is required"
[[ -f "$CLI" ]] || fail "plugin CLI not found: $CLI"

# Keep this array non-empty for macOS Bash 3.2: with `set -u`, expanding an
# empty array raises "unbound variable". curl treats an empty-value header as
# a request to omit that header, which is correct when the backend has no token.
AUTH_HEADERS=(-H "X-FengYu-Token: $TOKEN")

RESPONSE_FILE="$(mktemp)"
trap 'rm -f "$RESPONSE_FILE"' EXIT

echo "Checking FengYu backend at $BASE_URL ..."
HEALTH_STATUS="$(curl -sS -o "$RESPONSE_FILE" -w '%{http_code}' \
  "${AUTH_HEADERS[@]}" "$BASE_URL/api/health" || true)"
if [[ "$HEALTH_STATUS" != "200" ]]; then
  if [[ "$HEALTH_STATUS" == "401" ]]; then
    fail "backend requires a token; pass --token or set FENGYU_TOKEN"
  fi
  fail "backend health check returned HTTP ${HEALTH_STATUS:-unreachable}: $(<"$RESPONSE_FILE")"
fi

echo
echo "Building ${#OFFICIAL_PLUGINS[@]} official plugins ..."
for plugin in "${OFFICIAL_PLUGINS[@]}"; do
  PLUGIN_DIR="$ROOT/OfficialPlugins/plugin-$plugin"
  [[ -f "$PLUGIN_DIR/manifest.json" ]] || fail "manifest not found: $PLUGIN_DIR/manifest.json"

  echo
  echo "==> Building $plugin"
  BUILD_ARGS=(build "$PLUGIN_DIR")
  if [[ "$SKIP_TESTS" == "1" ]]; then
    BUILD_ARGS+=(--skip-tests)
  fi
  node "$CLI" "${BUILD_ARGS[@]}"
done

mkdir -p "$PACKAGE_DIR"
shopt -s nullglob

echo
echo "Staging and installing official plugins ..."
for plugin in "${OFFICIAL_PLUGINS[@]}"; do
  PLUGIN_DIR="$ROOT/OfficialPlugins/plugin-$plugin"
  MANIFEST_VALUES="$(node -e '
    const manifest = require(process.argv[1]);
    process.stdout.write(`${manifest.id}\t${manifest.version}`);
  ' "$PLUGIN_DIR/manifest.json")"
  IFS=$'\t' read -r PLUGIN_ID PLUGIN_VERSION <<< "$MANIFEST_VALUES"

  [[ "$PLUGIN_ID" == fan.summer.* ]] || fail "unexpected official plugin id: $PLUGIN_ID"
  ARCHIVE="$PLUGIN_DIR/dist/$PLUGIN_ID-$PLUGIN_VERSION.fyp"
  SIDECAR="$ARCHIVE.sha256"
  [[ -f "$ARCHIVE" ]] || fail "build output not found: $ARCHIVE"
  [[ -f "$SIDECAR" ]] || fail "checksum sidecar not found: $SIDECAR"

  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$(dirname "$ARCHIVE")" && sha256sum -c "$(basename "$SIDECAR")")
  elif command -v shasum >/dev/null 2>&1; then
    (cd "$(dirname "$ARCHIVE")" && shasum -a 256 -c "$(basename "$SIDECAR")")
  else
    fail "neither sha256sum nor shasum is available"
  fi

  # Keep exactly the current development package for this id. The backend seeder
  # reads this directory on restart, so leaving an older same-version archive here
  # could overwrite the package that this script just installed.
  STALE_PACKAGES=(
    "$PACKAGE_DIR/$PLUGIN_ID"-*.fyp
    "$PACKAGE_DIR/$PLUGIN_ID"-*.fyp.sha256
  )
  if [[ ${#STALE_PACKAGES[@]} -gt 0 ]]; then
    rm -f -- "${STALE_PACKAGES[@]}"
  fi

  STAGED_ARCHIVE="$PACKAGE_DIR/$(basename "$ARCHIVE")"
  cp "$ARCHIVE" "$SIDECAR" "$PACKAGE_DIR/"

  PAYLOAD="$(node -e \
    'process.stdout.write(JSON.stringify({ path: process.argv[1] }))' \
    "$STAGED_ARCHIVE")"
  HTTP_STATUS="$(curl -sS -o "$RESPONSE_FILE" -w '%{http_code}' \
    "${AUTH_HEADERS[@]}" \
    -H 'Content-Type: application/json' \
    -X POST "$BASE_URL/api/plugin-market/upload-native" \
    --data "$PAYLOAD" || true)"

  if [[ ! "$HTTP_STATUS" =~ ^2[0-9][0-9]$ ]]; then
    fail "installing $PLUGIN_ID returned HTTP ${HTTP_STATUS:-unreachable}: $(<"$RESPONSE_FILE")"
  fi
  echo "==> Installed $PLUGIN_ID $PLUGIN_VERSION"
done

echo
echo "All official plugins were built, staged, and installed successfully."
