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

# Python runtime consumer leg: the same lifecycle through the python toolchain
# path (scaffold, UI test/build, worker unittest, packaging). Gates the python
# packaging contract: the .fyp must carry the runnable worker tree (worker.py +
# its runtime-imported contract.py + the vendored SDK) and never dev neighbours
# (bytecode caches, test modules).
PY3_OK="$(python3 -c 'import sys; print(1 if sys.version_info >= (3, 12) else 0)' 2>/dev/null || echo 0)"
if [ "$PY3_OK" = "1" ]; then
  cd "$WORK"
  npm exec --yes --package="./$CLI_TGZ" -- fengyu init demo-py --id com.example.demopy --runtime python --no-install
  cd demo-py/ui-src
  npm install "$WORK/$SDK_TGZ" "$WORK/$UI_TGZ" "$WORK/$DEV_TGZ"
  npm test
  npm run build
  cd "$WORK/demo-py"
  FENGYU_GITHUB_TOKEN="local-smoke-placeholder" GITHUB_ACTOR="fengyu-local-smoke" \
    npm exec --yes --package="$WORK/$CLI_TGZ" -- fengyu build .
  PACKAGE_PY="dist/com.example.demopy-1.0.0.fyp"
  test -f "$PACKAGE_PY"
  unzip -Z1 "$PACKAGE_PY" | sort > py-package-entries.txt
  grep -qx 'backend/worker.py' py-package-entries.txt
  grep -qx 'backend/contract.py' py-package-entries.txt
  grep -qx 'backend/fengyu_plugin_sdk/__init__.py' py-package-entries.txt
  if grep -Eq '(^|/)__pycache__(/|$)|_test\.py$' py-package-entries.txt; then
    echo "FAIL: dev entries leaked into the python package" >&2
    exit 1
  fi
else
  echo "SKIP: python consumer leg (python3 < 3.12 on this machine)"
fi

echo "PASS: clean local plugin consumer lifecycle"
