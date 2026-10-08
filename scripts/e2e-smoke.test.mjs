import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// Pins the host-level smoke's isolation contract: dynamic loopback ports (concurrent
// runs / a developer's live app must never collide), a health-polled store stub (the
// backend negative-caches failed catalog fetches), and run-scoped temp files. The
// richer e2e-smoke pins (fixture plugin chain) live in release-workflow.test.mjs.
const smoke = readFileSync(new URL('./e2e-smoke.sh', import.meta.url), 'utf8')

test('jar glob resolves with nullglob so the zero-match branch is reachable', () => {
  assert.match(smoke, /shopt -s nullglob\nJAR_COUNT=\( \$JAR_GLOB \)\nshopt -u nullglob/)
  assert.match(smoke, /\$\{#JAR_COUNT\[@\]\} -eq 0 /)
})

test('ports are picked dynamically, never hardcoded', () => {
  assert.match(smoke, /free_port\(\) \{/)
  assert.match(smoke, /PORT="\$\{1:-\$\(free_port\)\}"/)
  assert.match(smoke, /STORE_PORT="\$\(free_port\)"/)
  assert.match(smoke, /export STORE_PORT/)
  // The old fixed ports (8899/8897) must not come back as bindings.
  assert.doesNotMatch(smoke, /^PORT="[0-9]+"/m)
  assert.doesNotMatch(smoke, /^STORE_PORT=[0-9]+/m)
})

test('the store stub is health-polled before the backend boots', () => {
  const poll = smoke.indexOf('stub_ready=0')
  const boot = smoke.indexOf('fan.summer.fengyu.HeadlessLauncher')
  assert.notEqual(poll, -1, 'smoke must poll the store stub for readiness')
  assert.notEqual(boot, -1, 'smoke must boot the backend')
  assert.ok(poll < boot, 'the stub must be healthy first — the backend negative-caches failed catalog fetches')
  assert.match(smoke, /api\/v1\/catalog/)
})

test('curl response bodies are captured under the run workspace, not /tmp', () => {
  assert.match(smoke, /-o "\$WORK\/upload\.json"/)
  assert.match(smoke, /-o "\$WORK\/reupload\.json"/)
  assert.doesNotMatch(smoke, /\/tmp\/e2e-smoke-/)
})
