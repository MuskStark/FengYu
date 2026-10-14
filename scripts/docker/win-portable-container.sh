#!/usr/bin/env bash
# Windows portable ZIP cross-build (JRE variant), run INSIDE a node:24-bookworm container.
# Mirrors release/4.1.0's .tmp-crossbuild/docker-build-win-jre.sh, which proved this path:
# current electron-builder edits the win32 exe metadata with the pure-JS resedit, so no
# wine is needed — the plain node image is enough.
#
# The host repo is mounted read-only at /src with everything pre-staged:
#   desktop/electron/frontend-dist/          (SPA, FENGYU_DESKTOP_BUILD=1)
#   desktop/electron/dist/main.js            (compiled shell TS)
#   desktop/electron/resources/binaries/     (FengYu.jar + official .fyp packages)
#   desktop/electron/resources/jre/          (Temurin Windows x64 JRE, u+w)
# The artifactName override pins "win-x64" into the zip name: the config's ${platform}
# token renders the HOST (linux) platform name when cross-building, not the target.
# This script copies the desktop project into container-local /work (dropping the host's
# darwin node_modules), reinstalls the Linux deps there, and packages the win32-x64 ZIP.
# Artifacts land in /out (host-mounted) — the host tree stays untouched.
set -euo pipefail

echo "[docker] preparing /work (copy project, drop host node_modules)"
rm -rf /work && mkdir -p /work /out
tar -C /src/desktop/electron \
  --exclude=./node_modules \
  --exclude=./test-results \
  --exclude=./.fengyu \
  -cf - . | tar -C /work -xf -

cd /work
corepack enable
echo "[docker] yarn install (linux)"
yarn install

echo "[docker] === JRE variant (self-contained, extract-and-run) ==="
FENGYU_WINDOWS_PORTABLE_ZIP=1 npx electron-builder --win zip --x64 --publish never \
  --config electron-builder.jre.yml \
  --config.directories.output=/out \
  --config.win.artifactName='${productName}-${version}-win-x64-portable.${ext}'

echo "[docker] artifacts:"
ls -la /out
echo "[docker] DONE"
