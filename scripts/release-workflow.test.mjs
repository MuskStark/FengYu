import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const workflow = readFileSync(new URL('../.github/workflows/fengyu-release.yml', import.meta.url), 'utf8')
const builderConfig = readFileSync(new URL('../desktop/electron/electron-builder.yml', import.meta.url), 'utf8')
const rootPom = readFileSync(new URL('../pom.xml', import.meta.url), 'utf8')
const mavenConfig = readFileSync(new URL('../.mvn/maven.config', import.meta.url), 'utf8')
const desktopJob = workflow.slice(
  workflow.indexOf('\n  desktop:'),
  workflow.indexOf('\n  release:'),
)

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

test('electron-builder targets NSIS + portable on Windows, DMG arm64 on macOS, AppImage + deb on Linux', () => {
  // Windows ships BOTH an installer (nsis) and a no-install portable (.exe) target.
  assert.match(builderConfig, /win:\s*\n\s*target:\s*\n\s*-\s+target:\s*nsis/)
  assert.match(builderConfig, /-\s+target:\s*portable/)
  // macOS: arm64 only (no x64).
  assert.match(builderConfig, /mac:\s*\n\s*target:\s*\n\s*-\s+target:\s*dmg\s*\n\s*arch:\s*\[arm64\]/)
  // Linux: AppImage (single-file) + deb (Debian/Ubuntu package).
  assert.match(builderConfig, /linux:\s*\n\s*target:\s*\n\s*-\s+target:\s*AppImage/)
  assert.match(builderConfig, /-\s+target:\s*deb/)
})

test('artifact names include version + platform + arch', () => {
  // Top-level uniform scheme: <product>-<version>-<platform>-<arch>.<ext>
  assert.match(builderConfig, /artifactName: \$\{productName\}-\$\{version\}-\$\{platform\}-\$\{arch\}\.\$\{ext\}/)
  // Windows disambiguates installer vs portable with a form suffix (both keep version+platform+arch).
  assert.match(builderConfig, /nsis:[\s\S]*?artifactName: \$\{productName\}-\$\{version\}-\$\{platform\}-\$\{arch\}-setup\.\$\{ext\}/)
  assert.match(builderConfig, /portable:[\s\S]*?artifactName: \$\{productName\}-\$\{version\}-\$\{platform\}-\$\{arch\}-portable\.\$\{ext\}/)
})

test('electron-builder bundles the FengYu jar + plugins as extraResources', () => {
  assert.match(builderConfig, /from: resources\/binaries\/FengYu\.jar/)
  assert.match(builderConfig, /from: resources\/binaries\/plugins/)
})

test('desktop job builds two variants and runs unit plus launch tests', () => {
  assert.match(desktopJob, /FENGYU_RELEASE_VERSION: \${{ needs\.setup\.outputs\.version }}/)
  assert.match(desktopJob, /- name: Install frontend deps\s+run: npm ci\s+working-directory: frontend/)
  assert.match(desktopJob, /- name: Run desktop unit tests\s+run: npm test\s+working-directory: desktop\/electron/)
  assert.match(desktopJob, /FENGYU_DESKTOP_BUILD: '1'/)
  assert.match(desktopJob, /- name: Verify file-compatible frontend asset paths\s+run: npm run verify:frontend-dist/)
  assert.match(desktopJob, /xvfb-run -a npm run test:e2e/)
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
