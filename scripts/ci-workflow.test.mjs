import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const root = new URL('../', import.meta.url)
const readWorkflow = name => readFileSync(new URL(`.github/workflows/${name}`, root), 'utf8')

test('backend changes run the full Maven suite and boot-level smoke outside release', () => {
  const workflow = readWorkflow('backend-ci.yml')
  assert.match(workflow, /paths:[\s\S]*- 'FengYu\/\*\*'/)
  assert.match(workflow, /run: \.\/mvnw -B test/)
  assert.match(workflow, /run: scripts\/e2e-smoke\.sh/)
})

test('frontend CI verifies the production Vite bundle', () => {
  const workflow = readWorkflow('frontend-ci.yml')
  assert.match(workflow, /- name: Production build\s+run: corepack yarn run build/)
})

test('release audits include development-only packaging and runtime dependencies', () => {
  for (const name of ['fengyu-release.yml', 'uos-deb-build.yml', 'windows-portable-build.yml']) {
    const workflow = readWorkflow(name)
    assert.equal(workflow.includes('yarn npm audit --environment production'), false, name)
  }
})
