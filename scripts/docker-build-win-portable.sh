#!/usr/bin/env bash
# Build the Infinia Windows PORTABLE (bundled-JRE, extract-and-run) zip with Docker.
#
# ALWAYS cd into a package before invoking yarn: corepack resolves the pinned Yarn 4 from
# the CWD's packageManager field, and the repo root has none (a root-level call falls back
# to Yarn 1.22.22, which would rewrite the Yarn 4 lockfiles).
# Host side builds everything from source (SPA → shaded JAR with the SPA baked in →
# official .fyp plugins → shell TS → Temurin Windows JRE), then a node:24-bookworm
# container cross-packages the win32-x64 zip with electron-builder (no wine needed —
# see scripts/docker/win-portable-container.sh). Mirrors the desktop/windows-portable
# jobs of .github/workflows/fengyu-release.yml and the proven release/4.1.0 local
# cross-build, trimmed to the portable-with-JRE artifact.
#
# Usage: scripts/docker-build-win-portable.sh        (artifacts → release/win-portable/)
set -euo pipefail
cd "$(dirname "$0")/.."
corepack enable

VERSION=$(sed -n 's/^[[:space:]]*<revision>\(.*\)<\/revision>[[:space:]]*$/\1/p' pom.xml | head -1)
if [[ -z "$VERSION" ]]; then echo "cannot read <revision> from pom.xml" >&2; exit 1; fi
echo "[win-portable] app version: ${VERSION}"

echo "[win-portable] 1/6 frontend SPA (FENGYU_DESKTOP_BUILD)"
( cd frontend && corepack yarn install --immutable && FENGYU_DESKTOP_BUILD=1 corepack yarn run build )

echo "[win-portable] 2/6 desktop shell TS + frontend-dist staging"
( cd desktop/electron && corepack yarn install --immutable )
rm -rf desktop/electron/frontend-dist
cp -R frontend/dist desktop/electron/frontend-dist
( cd desktop/electron && corepack yarn run verify:frontend-dist && corepack yarn run build:ts )

echo "[win-portable] 3/6 official plugins (.fyp)"
( cd toolchain/ui && corepack yarn install --immutable && corepack yarn run build )
( cd toolchain/cli && corepack yarn install --immutable )
( cd toolchain/dev && corepack yarn install --immutable )
./mvnw -B -pl toolchain/sdk-java,toolchain/devkit-java -am install -DskipTests
for plugin in markdown excel email offlinepython; do
  node toolchain/cli/bin/fengyu.mjs build "OfficialPlugins/plugin-$plugin"
done

echo "[win-portable] 4/6 shaded app JAR (SPA baked in)"
./mvnw -B -pl FengYu package -DskipTests
jar tf "FengYu/target/FengYu-${VERSION}.jar" | grep -q '^static/index.html' \
  || { echo "static/index.html missing from the shaded JAR" >&2; exit 1; }

echo "[win-portable] 5/6 stage runtime resources + Windows JRE"
rm -rf desktop/electron/resources/binaries/plugins
mkdir -p desktop/electron/resources/binaries/plugins
cp "FengYu/target/FengYu-${VERSION}.jar" desktop/electron/resources/binaries/FengYu.jar
# Only THIS version's packages: plugin dist/ dirs also keep .fyp files from older
# app versions built on this machine, and bundling two versions of a plugin is
# ambiguous at install time.
for plugin_dir in OfficialPlugins/plugin-*/dist; do
  for archive in "${plugin_dir}"/*-"${VERSION}".fyp; do
    test -f "$archive" || { echo "no ${VERSION} .fyp in ${plugin_dir}" >&2; exit 1; }
    test -f "${archive}.sha256" || { echo "missing sidecar: ${archive}.sha256" >&2; exit 1; }
    cp "$archive" "${archive}.sha256" desktop/electron/resources/binaries/plugins/
  done
done
# jlink cannot cross-target Windows from macOS; bundle the stock Temurin Windows x64
# JRE (the release workflow trims with jlink on a real Windows runner — same
# <resources>/jre/bin/java.exe layout contract runtime-layout.ts resolves).
# chmod -R u+w: Temurin ships legal/ 0444, which breaks electron-builder's dest-chmod.
# Mirror first (TUNA mirrors Adoptium; the adoptium API redirects to a CDN that can
# crawl at tens of KB/s from CN networks), upstream API as the fallback.
rm -rf desktop/electron/resources/jre
mkdir -p desktop/electron/resources/jre /tmp/fengyu-win-jre release/cache
JRE_CACHE="release/cache/win-jre.zip"
if [[ -s "${JRE_CACHE}" ]] && [[ $(stat -f%z "${JRE_CACHE}" 2>/dev/null || stat -c%s "${JRE_CACHE}") -gt 10000000 ]]; then
  echo "[win-portable] using cached Windows JRE: ${JRE_CACHE}"
else
  TUNA_BASE="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jre/x64/windows"
  TUNA_FILE=$(curl -fsSL --max-time 30 "${TUNA_BASE}/" \
    | grep -oE 'OpenJDK21U-jre_x64_windows_hotspot_[0-9._]+\.zip' | sort -uV | tail -1 || true)
  if [[ -n "${TUNA_FILE}" ]] \
      && curl -fsSL --retry 3 -o "${JRE_CACHE}.tmp" "${TUNA_BASE}/${TUNA_FILE}"; then
    echo "[win-portable] Windows JRE from TUNA mirror: ${TUNA_FILE}"
  else
    echo "[win-portable] TUNA mirror unavailable, falling back to the adoptium API"
    curl -fsSL --retry 3 -o "${JRE_CACHE}.tmp" \
      "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk"
  fi
  mv "${JRE_CACHE}.tmp" "${JRE_CACHE}"
fi
unzip -q "${JRE_CACHE}" -d /tmp/fengyu-win-jre
inner=$(find /tmp/fengyu-win-jre -mindepth 1 -maxdepth 1 -type d | head -1)
cp -a "$inner"/. desktop/electron/resources/jre/
chmod -R u+w desktop/electron/resources/jre
test -f desktop/electron/resources/jre/bin/java.exe
rm -rf /tmp/fengyu-win-jre /tmp/fengyu-win-jre.zip

echo "[win-portable] 6/6 docker cross-package (node:24-bookworm, no wine)"
OUT_DIR="release/win-portable"
rm -rf "$OUT_DIR" && mkdir -p "$OUT_DIR"
# npmmirror for everything GitHub-served inside the container (the electron win32 zip
# and electron-builder's winCodeSign/nsis binaries) plus the npm registry itself —
# GitHub releases are the slowest hops from CN networks.
docker run --rm \
  -v "$PWD:/src:ro" \
  -v "$PWD/$OUT_DIR:/out" \
  -e ELECTRON_MIRROR="https://npmmirror.com/mirrors/electron/" \
  -e ELECTRON_BUILDER_BINARIES_MIRROR="https://npmmirror.com/mirrors/electron-builder-binaries/" \
  -e YARN_NPM_REGISTRY_SERVER="https://registry.npmmirror.com" \
  -e COREPACK_NPM_REGISTRY="https://registry.npmmirror.com" \
  node:24-bookworm bash /src/scripts/docker/win-portable-container.sh

echo "[win-portable] artifacts:"
ls -lh "$OUT_DIR"
