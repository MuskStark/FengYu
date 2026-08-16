#!/usr/bin/env bash
# Build every official plugin and stage the resulting .fyp + checksum pair into the
# official-plugin seeder directory.
#
# Official plugins (official:true / fan.summer.*) can ONLY be installed through the
# host-trusted seeder path (P0-8 anti-impersonation): the upload/marketplace API rejects
# them by design. The seeder scans fengyu.plugins.official-directory — for an IDE-started
# dev backend that defaults to <repo>/OfficialPlugins/target/packages — AT STARTUP,
# verifies each .sha256 sidecar, upgrades newer versions, and refreshes same-version
# bundles whose bytes changed. So local "installation" = stage below + restart the backend.
#
# Usage:
#   ./dev-install-official-plugins.sh [--skip-tests]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKIP_TESTS=0
OFFICIAL_PLUGINS=(markdown excel email offlinepython)
PACKAGE_DIR="$ROOT/OfficialPlugins/target/packages"
CLI="$ROOT/toolchain/cli/bin/fengyu.mjs"

usage() {
  echo "Usage: $0 [--skip-tests]"
  echo
  echo "Builds all official plugins and stages them into $PACKAGE_DIR."
  echo "Restart your dev backend afterwards — the official-plugin seeder installs"
  echo "them from that directory at startup (trusted path; uploads cannot do this)."
}

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
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

command -v node >/dev/null 2>&1 || fail "node is required"
[[ -f "$CLI" ]] || fail "plugin CLI not found: $CLI"
# Official plugin UIs install with Yarn 4 through corepack (Node >=25 dropped the
# bundled corepack — install it standalone there: `npm install -g corepack`).
command -v corepack >/dev/null 2>&1 \
  || fail "corepack is required (npm install -g corepack)"
corepack enable >/dev/null 2>&1 || true

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
echo "Staging official plugins into $PACKAGE_DIR ..."
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

  # Keep exactly the current development package for this id. The seeder keeps only the
  # highest version per id anyway, but an older same-version archive left here would
  # carry a stale checksum pair and could win the scan nondeterministically.
  STALE_PACKAGES=(
    "$PACKAGE_DIR/$PLUGIN_ID"-*.fyp
    "$PACKAGE_DIR/$PLUGIN_ID"-*.fyp.sha256
  )
  if [[ ${#STALE_PACKAGES[@]} -gt 0 ]]; then
    rm -f -- "${STALE_PACKAGES[@]}"
  fi

  cp "$ARCHIVE" "$SIDECAR" "$PACKAGE_DIR/"
  echo "==> Staged $PLUGIN_ID $PLUGIN_VERSION"
done

echo
echo "All official plugins were built and staged successfully."
echo
echo "To activate them:"
echo "  1. Restart your dev backend (IDE run config). At startup the official-plugin"
echo "     seeder scans $PACKAGE_DIR, verifies each .sha256 sidecar, and"
echo "     installs/upgrades/refreshes the staged packages through the trusted path."
echo "  2. The desktop shell is NOT affected by this directory — it points the seeder"
echo "     at its own bundled plugins directory via -Dfengyu.plugins.official-directory."
echo
echo "Note: a plugin you uninstalled in the host UI stays skipped while its uninstall"
echo "tombstone exists (<runtime data root>/manifest-digests/<id>.uninstalled); reinstall"
echo "it through the UI (or delete the tombstone) to let the seeder manage it again."
