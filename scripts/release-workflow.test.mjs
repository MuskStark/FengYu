import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const workflow = readFileSync(new URL('../.github/workflows/fengyu-release.yml', import.meta.url), 'utf8')
const tauriConfig = JSON.parse(
  readFileSync(new URL('../desktop/src-tauri/tauri.conf.json', import.meta.url), 'utf8'),
)
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

test('installs plugin-cli dependencies before building plugins', () => {
  assert.match(
    workflow,
    /- name: Install plugin-cli deps\s+run: npm ci\s+working-directory: plugin-cli/,
  )
})

test('builds Maven artifacts with the full release version', () => {
  assert.match(workflow, /\.\/mvnw -am test package -Drevision="\$VERSION"/)
  assert.doesNotMatch(workflow, /\.\/mvnw -am test package -Drevision="\$APP_VERSION"/)
})

test('runs the production frontend build from the Tauri app directory', () => {
  assert.equal(tauriConfig.build.beforeBuildCommand, 'cd ../frontend && npm run build')
  assert.equal(tauriConfig.build.frontendDist, '../../frontend/dist')
})

test('prepares the frontend in every clean desktop runner', () => {
  assert.match(desktopJob, /FENGYU_RELEASE_VERSION: \${{ needs\.setup\.outputs\.version }}/)
  assert.match(
    desktopJob,
    /- name: Install frontend deps\s+run: npm ci\s+working-directory: frontend/,
  )
})

test('flattens nested desktop installers before checksums and release upload', () => {
  assert.match(workflow, /find artifacts -type f/)
  assert.match(workflow, /release-files\/\$\(basename "\$file"\)/)
  assert.match(workflow, /files: \|\s+release-files\/\*/)
})
