#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cd "$ROOT"
./mvnw -f toolchain/sdk-java/pom.xml install -DskipTests
./mvnw -f toolchain/devkit-java/pom.xml install -DskipTests

cd "$ROOT/toolchain/sdk-ts"
npm ci
npm test
SDK_TGZ="$(npm pack --ignore-scripts --silent --pack-destination "$WORK")"

cd "$ROOT/toolchain/ui"
npm ci
npm run typecheck
npm test
npm run build
UI_TGZ="$(npm pack --ignore-scripts --silent --pack-destination "$WORK")"

cd "$ROOT/toolchain/dev"
npm ci
npm test
DEV_TGZ="$(npm pack --ignore-scripts --silent --pack-destination "$WORK")"

cd "$ROOT/toolchain/cli"
npm ci
npm test
CLI_TGZ="$(npm pack --ignore-scripts --silent --pack-destination "$WORK")"

cd "$WORK"
npm exec --yes --package="./$CLI_TGZ" -- fengyu init demo --id com.example.demo --no-install
cd demo/ui-src
npm install "$WORK/$SDK_TGZ" "$WORK/$UI_TGZ" "$WORK/$DEV_TGZ"
npm test
npm run build

cd "$WORK/demo"
test -x mvnw
./mvnw -f worker/pom.xml test
FENGYU_GITHUB_TOKEN="local-smoke-placeholder" GITHUB_ACTOR="fengyu-local-smoke" \
  npm exec --yes --package="$WORK/$CLI_TGZ" -- fengyu build .

PACKAGE="dist/com.example.demo-1.0.0.fyp"
test -f "$PACKAGE"
unzip -Z1 "$PACKAGE" | sort > package-entries.txt
grep -qx 'manifest.json' package-entries.txt
grep -qx 'backend/worker.jar' package-entries.txt
grep -qx 'ui/index.html' package-entries.txt
if grep -Eq '(^|/)(src|node_modules|target|\.git)(/|$)' package-entries.txt; then
  echo "FAIL: source/build files leaked into generated package" >&2
  exit 1
fi

echo "PASS: clean local plugin consumer lifecycle"
