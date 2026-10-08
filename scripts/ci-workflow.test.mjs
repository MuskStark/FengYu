import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'

const root = new URL('../', import.meta.url)
const readWorkflow = name => readFileSync(new URL(`.github/workflows/${name}`, root), 'utf8')

test('backend changes run the full Maven suite and boot-level smoke outside release', () => {
  const workflow = readWorkflow('backend-ci.yml')
  assert.match(workflow, /paths:[\s\S]*- 'FengYu\/\*\*'/)
  assert.match(workflow, /run: \.\/mvnw -B test/)
  assert.match(workflow, /run: scripts\/e2e-smoke\.sh/)
})

test('every workflow that runs backend tests or the smoke bootstraps the process sandbox', () => {
  // ProcessSandbox fails closed without bubblewrap + relaxed AppArmor userns on
  // ubuntu-latest; all four executors must declare the same bootstrap step.
  for (const name of [
    'backend-ci.yml', 'fengyu-release.yml', 'uos-deb-build.yml', 'windows-portable-build.yml',
  ]) {
    const workflow = readWorkflow(name)
    assert.match(workflow, /sudo apt-get install -y -qq bubblewrap/, `${name}: bubblewrap install`)
    assert.match(
      workflow, /kernel\.apparmor_restrict_unprivileged_userns=0/, `${name}: AppArmor userns relax`,
    )
    assert.match(workflow, /bwrap --unshare-all/, `${name}: bwrap sanity probe`)
  }
})

test('release concurrency is keyed on the tag so dispatch and tag-push runs share one group', () => {
  // A dispatch targeting vX runs from a branch ref while a tag push of the same vX runs
  // from refs/tags/vX — keying the group on the raw ref splits them into two racing
  // groups. Workflow-level concurrency cannot read job outputs, so the tag is derived
  // from the context: inputs.tag on dispatch, else github.ref_name — which IS the bare
  // tag on a tag push. (Actions expressions have no replace(); an earlier version used
  // it and the workflow failed validity on every trigger.)
  const workflow = readWorkflow('fengyu-release.yml')
  assert.match(
    workflow,
    /group: fengyu-release-\$\{\{ inputs\.tag \|\| github\.ref_name \}\}/,
  )
  assert.doesNotMatch(workflow, /replace\(github\.ref/, 'replace() is not an Actions expression function')
})

test('EOL audit gates run through the shared network-only retry wrapper', () => {
  // scripts/lib/yarn-npm-audit.sh owns the bounded retry loop (2026-09-04 npm incident);
  // no build workflow may regress to a bare `yarn npm audit`.
  const wrapper = readFileSync(new URL('./lib/yarn-npm-audit.sh', import.meta.url), 'utf8')
  assert.match(wrapper, /YARN_HTTP_TIMEOUT:-300000/)
  assert.match(wrapper, /RequestError\|Timeout awaiting\|ENOTFOUND\|EAI_AGAIN/)
  assert.match(wrapper, /registry\.npmjs\.org/)
  for (const name of ['fengyu-release.yml', 'uos-deb-build.yml', 'windows-portable-build.yml']) {
    const workflow = readWorkflow(name)
    assert.match(workflow, /run: scripts\/lib\/yarn-npm-audit\.sh frontend/, name)
    assert.match(workflow, /run: scripts\/lib\/yarn-npm-audit\.sh desktop\/electron/, name)
    assert.doesNotMatch(workflow, /run: corepack yarn npm audit/, name)
  }
})

test('dispatch inputs never interpolate directly into run: scripts', () => {
  // env indirection (the fengyu-release.yml version-resolver pattern) keeps untrusted
  // dispatch input text out of shell command lines.
  for (const name of ['uos-deb-build.yml', 'windows-portable-build.yml', 'fengyu-release.yml']) {
    const workflow = readWorkflow(name)
    assert.doesNotMatch(workflow, /run:[^\n]*\$\{\{ inputs\./, name)
  }
  for (const name of ['uos-deb-build.yml', 'windows-portable-build.yml']) {
    const workflow = readWorkflow(name)
    assert.match(workflow, /BUILD_REF: \$\{\{ inputs\.ref \|\| github\.ref_name \}\}/, name)
  }
})

test('qodana scans with least privilege and skips JVM-irrelevant PRs', () => {
  const workflow = readWorkflow('qodana_code_quality.yml')
  assert.match(workflow, /contents: read/)
  assert.doesNotMatch(workflow, /contents: write/)
  // The JVM-community linter analyzes Java only — PRs without Java-relevant paths
  // must not burn a full scan.
  assert.match(workflow, /pull_request:[\s\S]*?paths:[\s\S]*?- 'FengYu\/src\/\*\*'/)
})

test('all workflow actions are pinned to full commit SHAs', () => {
  for (const name of readdirSync(new URL('.github/workflows', root))) {
    const workflow = readWorkflow(name)
    for (const line of workflow.split('\n')) {
      const uses = line.match(/^\s*-?\s*(?:id:\s*\S+\s+)?uses: (.+)$/)
      if (!uses) continue
      const ref = uses[1].trim()
      // Local composite actions (./...) are trusted repo content; everything else
      // must be owner/repo@<40-hex> with the moving tag kept as a comment.
      if (ref.startsWith('./')) continue
      assert.match(
        ref, /^[^@\s]+@[0-9a-f]{40}( # .+)?$/,
        `${name}: action ref must pin a commit SHA: ${ref}`,
      )
    }
  }
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
