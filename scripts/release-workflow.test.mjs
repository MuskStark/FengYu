import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { validateManifestObject } from '../toolchain/cli/src/manifest.mjs'

const workflow = readFileSync(new URL('../.github/workflows/fengyu-release.yml', import.meta.url), 'utf8')
const builderConfig = readFileSync(new URL('../desktop/electron/electron-builder.yml', import.meta.url), 'utf8')
const jreBuilderConfig = readFileSync(new URL('../desktop/electron/electron-builder.jre.yml', import.meta.url), 'utf8')
const uosBuilderConfig = readFileSync(new URL('../desktop/electron/electron-builder.uos.yml', import.meta.url), 'utf8')
const rootPom = readFileSync(new URL('../pom.xml', import.meta.url), 'utf8')
const mavenConfig = readFileSync(new URL('../.mvn/maven.config', import.meta.url), 'utf8')
const desktopJob = workflow.slice(
  workflow.indexOf('\n  desktop:'),
  workflow.indexOf('\n  release:'),
)
const buildRuntimeJob = workflow.slice(
  workflow.indexOf('\n  build-runtime:'),
  workflow.indexOf('\n  web:'),
)
const webJob = workflow.slice(
  workflow.indexOf('\n  web:'),
  workflow.indexOf('\n  desktop:'),
)
const packageWebRelease = readFileSync(new URL('./package-web-release.sh', import.meta.url), 'utf8')
const testWebRelease = readFileSync(new URL('./test-web-release.sh', import.meta.url), 'utf8')
const e2eSmoke = readFileSync(new URL('./e2e-smoke.sh', import.meta.url), 'utf8')

test('uses the runner-provided GITHUB_OUTPUT file', () => {
  assert.doesNotMatch(workflow, /^\s+GITHUB_OUTPUT:/m)
})

test('runs release contract tests in the shared runtime job', () => {
  assert.match(
    workflow,
    /node --test scripts\/resolve-release-version\.test\.mjs scripts\/release-workflow\.test\.mjs scripts\/node-version\.test\.mjs/,
  )
})

test('installs toolchain/cli dependencies before building plugins', () => {
  // Yarn 4 (via corepack) installs the toolchain; the pinned release lives in package.json.
  assert.match(workflow, /- name: Enable corepack \(pinned Yarn 4 for toolchain \+ plugins\)\s+run: corepack enable/)
  assert.match(
    workflow,
    /- name: Install toolchain\/cli deps\s+run: yarn install --immutable\s+working-directory: toolchain\/cli/,
  )
})

test('builds @infinia/plugin-ui dist before packaging official plugins', () => {
  // Official plugins depend on @infinia/plugin-ui via a `file:` link to toolchain/ui, whose
  // package entry resolves to ./dist/index.js. dist/ is gitignored and absent from a fresh
  // checkout, so build-runtime must build the library before the plugin builds install +
  // bundle it — otherwise vite/vitest fail to resolve the package (see beta.3 build-runtime).
  const uiBuild = buildRuntimeJob.indexOf('working-directory: toolchain/ui')
  const pluginBuild = buildRuntimeJob.indexOf('Build official plugins')
  assert.notEqual(uiBuild, -1, 'build-runtime must build @infinia/plugin-ui (toolchain/ui)')
  assert.notEqual(pluginBuild, -1, 'build-runtime must build the official plugins')
  assert.ok(uiBuild < pluginBuild, '@infinia/plugin-ui must be built before plugins are packaged')
})

test('installs the Java plugin toolchain (sdk + devkit) before packaging official plugins', () => {
  // Plugin workers depend on fengyu-plugin-sdk + fengyu-plugin-devkit (independently versioned,
  // never published to Maven Central). build-runtime must install them to ~/.m2 before the
  // plugin worker builds resolve them, or Maven fails with "Could not find artifact" (beta.3).
  const install = buildRuntimeJob.indexOf('toolchain/sdk-java,toolchain/devkit-java')
  const pluginBuild = buildRuntimeJob.indexOf('Build official plugins')
  assert.notEqual(install, -1, 'build-runtime must install toolchain/sdk-java + devkit-java')
  assert.notEqual(pluginBuild, -1, 'build-runtime must build the official plugins')
  assert.ok(install < pluginBuild, 'sdk + devkit must be installed before plugins are packaged')
})

test('installs toolchain/dev deps before building plugins (ui-src link: portals)', () => {
  // Plugin ui-src builds portal @infinia/plugin-dev via a Yarn link: into toolchain/dev,
  // whose committed dist runtime-imports @infinia/plugin-sdk and vite — resolved from
  // toolchain/dev's own node_modules through the portal symlink's real path. Before the
  // Yarn migration the ui-src deps were npm file:-copies (self-contained), so no workflow
  // installed toolchain/dev; every plugin vite config then dies with
  // ERR_MODULE_NOT_FOUND (2026-08-17 windows-portable run 31986878462).
  const devInstall = buildRuntimeJob.indexOf("working-directory: toolchain/dev")
  const pluginBuild = buildRuntimeJob.indexOf('Build official plugins')
  assert.notEqual(devInstall, -1, 'build-runtime must install toolchain/dev deps')
  assert.ok(devInstall < pluginBuild, 'toolchain/dev must be installed before plugins are packaged')
})

test('enables corepack before setup-node runs its yarn cache probe', () => {
  // The runner images' global yarn is bare 1.22.22 (since 2026-08-16) and refuses to run in
  // a project whose package.json pins "packageManager". setup-node's `cache: yarn` probe
  // runs bare `yarn` inside each cache-dependency-path directory BEFORE any later
  // `corepack enable` step, so corepack must come first in every job that enables the
  // yarn cache (windows cannot be fixed this way — the desktop job disables the cache
  // there instead).
  for (const job of [buildRuntimeJob, desktopJob]) {
    const corepack = job.indexOf('corepack enable')
    // Plain `cache: yarn` (build-runtime) or the Windows-conditional cache
    // expression (desktop matrix) both trigger the probe inside setup-node.
    // Anchored to the line-start YAML attribute so explanatory comments
    // mentioning "cache: yarn" don't match.
    const cacheProbe = job.search(/^\s+cache: (\$\{\{|yarn)/m)
    assert.notEqual(cacheProbe, -1, 'job must set up the yarn cache')
    assert.notEqual(corepack, -1, 'job enabling the yarn cache must run corepack enable')
    assert.ok(corepack < cacheProbe, 'corepack enable must precede setup-node (cache: yarn)')
  }
})

test('builds Maven artifacts with the full release version', () => {
  assert.match(workflow, /\.\/mvnw -am test package -Drevision="\$VERSION"/)
  assert.doesNotMatch(workflow, /\.\/mvnw -am test package -Drevision="\$APP_VERSION"/)
})

test('Maven wrapper default revision matches the application version', () => {
  const appVersion = rootPom.match(/<revision>([^<]+)<\/revision>/)?.[1]
  assert.ok(appVersion, 'root pom.xml must declare the application revision')
  assert.match(mavenConfig, new RegExp(`^-Drevision=${appVersion}$`, 'm'))
})

test('electron-builder targets NSIS + extract-and-run ZIP on Windows, DMG arm64 on macOS, AppImage + deb on Linux', () => {
  // Windows ships an installer plus a ZIP whose contents run directly after extraction.
  assert.match(builderConfig, /win:\s*\n\s*target:\s*\n\s*-\s+target:\s*nsis/)
  assert.match(builderConfig, /-\s+target:\s*zip/)
  assert.doesNotMatch(builderConfig, /-\s+target:\s*portable/)
  assert.match(jreBuilderConfig, /win:\s*\n\s*target:\s*\n\s*-\s+target:\s*nsis/)
  assert.match(jreBuilderConfig, /-\s+target:\s*zip/)
  assert.doesNotMatch(jreBuilderConfig, /-\s+target:\s*portable/)
  // macOS: arm64 only (no x64). Ships BOTH dmg (first-time install) and zip
  // (REQUIRED by electron-updater for in-place auto-update — dmg can't be used for updates).
  // The dmg/zip target lines may carry explanatory comments above them.
  assert.match(builderConfig, /mac:\s*\n\s*target:\s*\n(?:\s*#[^\n]*\n)*\s*-\s+target:\s*dmg\s*\n\s*arch:\s*\[arm64\]/)
  assert.match(builderConfig, /-\s+target:\s*zip\s*\n\s*arch:\s*\[arm64\]/)
  // Linux: AppImage (single-file) + deb (Debian/Ubuntu package).
  assert.match(builderConfig, /linux:\s*\n\s*target:\s*\n\s*-\s+target:\s*AppImage/)
  assert.match(builderConfig, /-\s+target:\s*deb/)
})

test('Windows release builds mark only the dedicated portable ZIP pass', () => {
  assert.match(builderConfig, /afterPack:\s*scripts\/after-pack\.cjs/)
  assert.match(jreBuilderConfig, /afterPack:\s*scripts\/after-pack\.cjs/)
  assert.match(desktopJob, /electron-builder --win nsis[^\n]*dist-electron-lite/)
  assert.match(desktopJob, /FENGYU_WINDOWS_PORTABLE_ZIP=1 npx electron-builder --win zip[^\n]*dist-electron-lite/)
  assert.match(desktopJob, /electron-builder --win nsis[^\n]*electron-builder\.jre\.yml/)
  assert.match(desktopJob, /FENGYU_WINDOWS_PORTABLE_ZIP=1 npx electron-builder --win zip[^\n]*electron-builder\.jre\.yml/)
})

test('artifact names include version + platform + arch', () => {
  // Top-level uniform scheme: <product>-<version>-<platform>-<arch>.<ext>
  assert.match(builderConfig, /artifactName: \$\{productName\}-\$\{version\}-\$\{platform\}-\$\{arch\}\.\$\{ext\}/)
  // Windows disambiguates installer vs portable ZIP with a form suffix.
  assert.match(builderConfig, /nsis:[\s\S]*?artifactName: \$\{productName\}-\$\{version\}-\$\{platform\}-\$\{arch\}-setup\.\$\{ext\}/)
  assert.match(builderConfig, /win:[\s\S]*?artifactName: \$\{productName\}-\$\{version\}-\$\{platform\}-\$\{arch\}-portable\.\$\{ext\}/)
  assert.match(jreBuilderConfig, /win:[\s\S]*?artifactName: \$\{productName\}-\$\{version\}-\$\{platform\}-\$\{arch\}-portable\.\$\{ext\}/)
})

test('UOS variant bakes fengyu.uos, bundles the JRE, and stays Linux-only', () => {
  // The UOS artifact is the no-sandbox build: its package metadata must carry fengyu.uos so the
  // main process (src/desktop/uos.ts) switches to no-sandbox launch mode, and it must bundle
  // the jlink JRE (UOS targets cannot assume a system Java 21). Linux targets only — it is
  // built with --linux and must never grow win/mac targets.
  assert.match(uosBuilderConfig, /productName: Infinia-UOS/)
  assert.match(uosBuilderConfig, /uos: true/)
  assert.match(uosBuilderConfig, /from: resources\/jre/)
  assert.match(uosBuilderConfig, /from: resources\/binaries\/FengYu\.jar/)
  assert.match(uosBuilderConfig, /linux:\s*\n\s*target:\s*\n\s*-\s+target:\s+AppImage/)
  assert.match(uosBuilderConfig, /-\s+target:\s+deb/)
  assert.doesNotMatch(uosBuilderConfig, /win:|mac:|nsis:/)
})

test('desktop job builds the UOS variant on Linux after the jlink JRE exists', () => {
  assert.match(desktopJob, /- name: Build Electron bundle \(UOS\)\s+if: runner\.os == 'Linux'\s+working-directory: desktop\/electron\s+shell: bash\s+run: npx electron-builder --linux --publish never --config electron-builder\.uos\.yml/)
  const jlink = desktopJob.indexOf('- name: Generate jlink JRE')
  const uos = desktopJob.indexOf('- name: Build Electron bundle (UOS)')
  assert.notEqual(jlink, -1, 'desktop job must generate the jlink JRE')
  assert.notEqual(uos, -1, 'desktop job must build the UOS bundle')
  assert.ok(jlink < uos, 'the UOS bundle needs the jlink JRE staged at resources/jre')
  assert.match(desktopJob, /desktop\/dist-electron-uos\/\*\*/)
})

test('release body documents the UOS no-sandbox artifacts', () => {
  assert.match(workflow, /Infinia-UOS-\*-linux-x64\.AppImage/)
  assert.match(workflow, /no-sandbox/)
})

test('release describes ZIP extraction and no longer publishes self-extracting portable executables', () => {
  assert.match(workflow, /\*-win-x64-portable\.zip/)
  assert.match(workflow, /run `Infinia\.exe`/)
  assert.doesNotMatch(workflow, /portable\.exe/)
})

test('electron-builder bundles the FengYu jar + plugins as extraResources', () => {
  assert.match(builderConfig, /from: resources\/binaries\/FengYu\.jar/)
  assert.match(builderConfig, /from: resources\/binaries\/plugins/)
})

test('keeps official plugin checksum sidecars through staging and shared artifacts', () => {
  assert.match(buildRuntimeJob, /test -f "\$archive\.sha256"/)
  assert.match(buildRuntimeJob, /cp "\$archive" "\$archive\.sha256" staging\/plugins\//)
  assert.match(buildRuntimeJob, /staging\/plugins\/\*\.fyp\s+staging\/plugins\/\*\.fyp\.sha256/)
})

test('keeps official plugin checksum sidecars in Web and desktop assembly', () => {
  assert.match(webJob, /cp inputs\/\*\.fyp inputs\/\*\.fyp\.sha256 out\/plugins\//)
  assert.match(desktopJob, /cp inputs\/\*\.fyp inputs\/\*\.fyp\.sha256 desktop\/electron\/resources\/binaries\/plugins\//)
  assert.match(packageWebRelease, /OFFICIAL_PLUGINS=\(markdown excel email offlinepython\)/)
  assert.match(packageWebRelease, /sha256sum -c/)
  assert.match(packageWebRelease, /shasum -a 256 -c/)
  assert.match(packageWebRelease, /cp "\$\{archives\[0\]\}" "\$\{archives\[0\]\}\.sha256" "\$DEST\/plugins\/"/)
  assert.match(testWebRelease, /sha256sum -c/)
  assert.match(testWebRelease, /shasum -a 256 -c/)
})

test('end-to-end smoke stages official plugin checksum sidecars', () => {
  assert.match(e2eSmoke, /\[ -f "\$fyp\.sha256" \]/)
  assert.match(e2eSmoke, /cp "\$fyp" "\$fyp\.sha256" "\$OFFICIAL_DIR\//)
  assert.match(e2eSmoke, /api\/plugin-db\/provision\/fan\.summer\.email/)
  assert.match(e2eSmoke, /'"provisioned":true'/)
})

test('desktop job builds two variants and runs unit plus launch tests', () => {
  assert.match(desktopJob, /FENGYU_RELEASE_VERSION: \${{ needs\.setup\.outputs\.version }}/)
  assert.match(desktopJob, /- name: Install frontend deps\s+run: corepack yarn install --no-immutable\s+working-directory: frontend/)
  assert.match(desktopJob, /- name: Install Electron binary\s+run: npx install-electron --no\s+working-directory: desktop\/electron\s+timeout-minutes: 15/)
  assert.match(desktopJob, /- name: Run desktop unit tests\s+run: corepack yarn test\s+working-directory: desktop\/electron/)
  assert.match(desktopJob, /FENGYU_DESKTOP_BUILD: '1'/)
  assert.match(desktopJob, /- name: Verify file-compatible frontend asset paths\s+run: corepack yarn run verify:frontend-dist/)
  // Yarn 4's `yarn run` requires the project's install state (Yarn 1 didn't), so the
  // verify script cannot run before desktop/electron's deps are installed
  // (2026-08-17 windows-portable run 31989356980).
  const desktopInstall = desktopJob.indexOf('- name: Install desktop deps')
  const verify = desktopJob.indexOf('- name: Verify file-compatible frontend asset paths')
  assert.notEqual(desktopInstall, -1, 'desktop job must install desktop deps')
  assert.ok(desktopInstall < verify, 'desktop deps must be installed before verify:frontend-dist')
  assert.match(desktopJob, /xvfb-run -a corepack yarn run test:e2e/)
  // Windows E2E stalls post-launch (see run 30332280958); it is non-blocking so the
  // Windows desktop bundles still ship, while macOS E2E stays gating.
  assert.match(desktopJob, /- name: Run Electron launch E2E\s+if: runner\.os != 'Linux'\s+continue-on-error: \$\{\{ runner\.os == 'Windows' \}\}/)
  assert.match(desktopJob, /FENGYU_JAR: \${{ github\.workspace }}\/desktop\/electron\/resources\/binaries\/FengYu\.jar/)
  assert.match(desktopJob, /Build Electron bundle \(without JRE\)/)
  assert.match(desktopJob, /Build Electron bundle \(with JRE\)/)
  assert.match(desktopJob, /Generate jlink JRE/)
})

test('flattens nested desktop installers before checksums and release upload', () => {
  assert.match(workflow, /find artifacts -type f/)
  assert.match(workflow, /release-files\/\$\(basename "\$file"\)/)
  assert.match(workflow, /files: \|\s+release-files\/\*/)
})

test('schema v1 manifests are rejected — releases ship v2-only, no v1 compat', () => {
  // A release must never accept a legacy schemaVersion:1 manifest. The CLI validator is the
  // gate both `fengyu build` (per-plugin) and the host installer use; pinning it here means a
  // regression that silently re-admits v1 fails the release contract test suite.
  const v1 = {
    schemaVersion: 1, id: 'com.example.legacy', name: 'Legacy', description: 'd',
    version: '1.0.0', author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' },
    backend: { command: 'java -jar backend/worker.jar', protocol: 'json-rpc-2.0' },
  }
  const errors = validateManifestObject(v1)
  assert.ok(errors.some((e) => /schemaVersion/i.test(e)),
    `schemaVersion:1 must be rejected, got: ${errors.join('; ')}`)
})

test('schema v2 manifests with v1-only fields (backend.command/protocol) are rejected', () => {
  const v2WithV1Backend = {
    schemaVersion: 2, id: 'com.example.mix', name: 'Mix', description: 'd',
    version: '1.0.0', author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' },
    backend: { command: 'java -jar backend/worker.jar', protocol: 'json-rpc-2.0' },
  }
  const errors = validateManifestObject(v2WithV1Backend)
  assert.ok(errors.some((e) => /backend|additional prop/i.test(e)),
    `v2 manifest must reject v1 backend.command/protocol, got: ${errors.join('; ')}`)
})
