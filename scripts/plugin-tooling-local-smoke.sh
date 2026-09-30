#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# The toolchain packages install with Yarn 4 through corepack (Node >=25 dropped the
# bundled corepack — install it standalone there: `npm install -g corepack`).
command -v corepack >/dev/null 2>&1 \
  || { echo "FAIL: corepack is required (npm install -g corepack)" >&2; exit 1; }

cd "$ROOT"
./mvnw -f toolchain/sdk-java/pom.xml install -DskipTests
./mvnw -f toolchain/devkit-java/pom.xml install -DskipTests

cd "$ROOT/toolchain/sdk-ts"
corepack yarn install --immutable
corepack yarn test
SDK_TGZ="$(npm pack --ignore-scripts --silent --pack-destination "$WORK")"

cd "$ROOT/toolchain/ui"
corepack yarn install --immutable
corepack yarn run typecheck
corepack yarn test
corepack yarn run build
UI_TGZ="$(npm pack --ignore-scripts --silent --pack-destination "$WORK")"

cd "$ROOT/toolchain/dev"
corepack yarn install --immutable
corepack yarn test
DEV_TGZ="$(npm pack --ignore-scripts --silent --pack-destination "$WORK")"

cd "$ROOT/toolchain/cli"
corepack yarn install --immutable
corepack yarn test
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
