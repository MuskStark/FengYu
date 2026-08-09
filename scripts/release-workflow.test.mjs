import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const workflow = readFileSync(new URL('../.github/workflows/fengyu-release.yml', import.meta.url), 'utf8')
const builderConfig = readFileSync(new URL('../desktop/electron/electron-builder.yml', import.meta.url), 'utf8')
const jreBuilderConfig = readFileSync(new URL('../desktop/electron/electron-builder.jre.yml', import.meta.url), 'utf8')
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
  assert.match(
    workflow,
    /- name: Install toolchain\/cli deps\s+run: npm ci\s+working-directory: toolchain\/cli/,
  )
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
  // macOS: arm64 only (no x64).
  assert.match(builderConfig, /mac:\s*\n\s*target:\s*\n\s*-\s+target:\s*dmg\s*\n\s*arch:\s*\[arm64\]/)
  // Linux: AppImage (single-file) + deb (Debian/Ubuntu package).
  assert.match(builderConfig, /linux:\s*\n\s*target:\s*\n\s*-\s+target:\s*AppImage/)
  assert.match(builderConfig, /-\s+target:\s*deb/)
})

test('artifact names include version + platform + arch', () => {
  // Top-level uniform scheme: <product>-<version>-<platform>-<arch>.<ext>
  assert.match(builderConfig, /artifactName: \$\{productName\}-\$\{version\}-\$\{platform\}-\$\{arch\}\.\$\{ext\}/)
  // Windows disambiguates installer vs portable ZIP with a form suffix.
  assert.match(builderConfig, /nsis:[\s\S]*?artifactName: \$\{productName\}-\$\{version\}-\$\{platform\}-\$\{arch\}-setup\.\$\{ext\}/)
  assert.match(builderConfig, /win:[\s\S]*?artifactName: \$\{productName\}-\$\{version\}-\$\{platform\}-\$\{arch\}-portable\.\$\{ext\}/)
  assert.match(jreBuilderConfig, /win:[\s\S]*?artifactName: \$\{productName\}-\$\{version\}-\$\{platform\}-\$\{arch\}-portable\.\$\{ext\}/)
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
  assert.match(packageWebRelease, /OFFICIAL_PLUGINS=\(markdown excel email offlinepython browser\)/)
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
  assert.match(desktopJob, /- name: Install frontend deps\s+run: npm ci\s+working-directory: frontend/)
  assert.match(desktopJob, /- name: Install Electron binary\s+run: npx install-electron --no\s+working-directory: desktop\/electron\s+timeout-minutes: 15/)
  assert.match(desktopJob, /- name: Run desktop unit tests\s+run: npm test\s+working-directory: desktop\/electron/)
  assert.match(desktopJob, /FENGYU_DESKTOP_BUILD: '1'/)
  assert.match(desktopJob, /- name: Verify file-compatible frontend asset paths\s+run: npm run verify:frontend-dist/)
  assert.match(desktopJob, /xvfb-run -a npm run test:e2e/)
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
