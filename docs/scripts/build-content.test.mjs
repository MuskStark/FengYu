import test from 'node:test'
import assert from 'node:assert/strict'
import { Marked } from 'marked'
import GithubSlugger from 'github-slugger'
import {
  admonitionExtension,
  cjkProcessTerm,
  condensePrerelease,
  DOCS_ROOT,
  headingPlainText,
  linkTarget,
  mergeReleaseLines,
  normalizeRoutePath,
  releasedSections,
  releaseBase,
  resolveHref,
  splitChangelog,
} from './build-content.mjs'

test('normalizeRoutePath collapses trailing slashes but keeps locale roots', () => {
  assert.equal(normalizeRoutePath('/en/skills/'), '/en/skills')
  assert.equal(normalizeRoutePath('/en/'), '/en')
  assert.equal(normalizeRoutePath('/en'), '/en')
})

test('resolveHref handles the four link dialects the content uses', () => {
  assert.deepEqual(resolveHref('https://github.com/x', 'en/index.md'), { kind: 'external', href: 'https://github.com/x' })
  assert.deepEqual(resolveHref('#anchor', 'en/a.md'), { kind: 'anchor', hash: 'anchor' })
  assert.deepEqual(resolveHref('/en/skills/', 'en/guide/a.md'), { kind: 'internal', path: '/en/skills', hash: '' })
  assert.deepEqual(resolveHref('/en/guide/ai-chat#backends', 'en/x.md'), { kind: 'internal', path: '/en/guide/ai-chat', hash: 'backends' })
  // relative .md links resolve file-relative (VitePress-era content carries them)
  assert.deepEqual(resolveHref('./ai-agent.md#workflow-webhooks', 'en/guide/ai-chat.md'), {
    kind: 'internal',
    path: '/en/guide/ai-agent',
    hash: 'workflow-webhooks',
  })
  assert.deepEqual(resolveHref('../plugins/manifest.md#flownodes', 'en/guide/flow-nodes.md'), {
    kind: 'internal',
    path: '/en/plugins/manifest',
    hash: 'flownodes',
  })
  // one level of '..' stays inside the locale tree (the .md extension is stripped into the route); only escaping it leaves the routes
  assert.deepEqual(resolveHref('../plans/foo.md', 'en/guide/a.md'), { kind: 'internal', path: '/en/plans/foo', hash: '' })
  assert.equal(resolveHref('../../plans/foo.md', 'en/guide/a.md').kind, 'other')
})

test('headingPlainText strips the inline markdown github-slugger never saw', () => {
  assert.equal(headingPlainText('Workflow & Webhooks'), 'Workflow & Webhooks')
  assert.equal(headingPlainText('The `--token` flag'), 'The --token flag')
  assert.equal(headingPlainText('[AI Chat](/en/guide/ai-chat) deep dive'), 'AI Chat deep dive')
})

test('linkTarget maps routes to candidate files (leaf first, directory index as fallback)', () => {
  assert.deepEqual(linkTarget('/en/quickstart'), {
    routePath: '/en/quickstart',
    pageKeys: ['en/quickstart.md', 'en/quickstart/index.md'],
    locale: 'en',
  })
  assert.deepEqual(linkTarget('/en/skills/'), { routePath: '/en/skills', pageKeys: ['en/skills.md', 'en/skills/index.md'], locale: 'en' })
  assert.deepEqual(linkTarget('/zh'), { routePath: '/zh', pageKeys: ['zh/index.md'], locale: 'zh' })
})

test('splitChangelog follows the sync script heading contract (dated and undated)', () => {
  const body = [
    'Intro line.',
    '',
    '::: tip Latest release',
    'v1.2.3 — see GitHub.',
    ':::',
    '',
    '## [4.1.0-beta.1] — 2026-10-15',
    'body a',
    '',
    '## [4.0.0] — 2026-09-30',
    'body b',
    '',
    '## [Unreleased]',
    'body c',
  ].join('\n')
  const { intro, sections } = splitChangelog(body)
  assert.match(intro, /Intro line\./)
  assert.match(intro, /Latest release/)
  assert.equal(sections.length, 3)
  assert.deepEqual(
    sections.map((s) => s.version),
    ['4.1.0-beta.1', '4.0.0', 'Unreleased'],
  )
  assert.equal(sections[0].date, '2026-10-15')
  assert.equal(sections[2].date, '')
})

test('cjkProcessTerm bigram-splits CJK and lowercases latin terms', () => {
  assert.equal(cjkProcessTerm('Database'), 'database')
  assert.deepEqual(cjkProcessTerm('数据库'), ['数据', '据库'])
  assert.deepEqual(cjkProcessTerm('规范'), ['规范'])
  assert.deepEqual(cjkProcessTerm('数据库规范'), ['数据', '据库', '库规', '规范'])
  assert.equal(cjkProcessTerm(''), null)
})

test('admonitionExtension renders localized titles and keeps nested block syntax', async () => {
  const md = new Marked({ gfm: true, extensions: [admonitionExtension('zh')] })
  const html = await md.parse('::: warning 自定义标题\n\n- 项目一\n- 项目二\n\n:::')
  assert.match(html, /doc-note doc-note--warning/)
  assert.match(html, /自定义标题/)
  assert.match(html, /<li>/)
  const mdEn = new Marked({ gfm: true, extensions: [admonitionExtension('en')] })
  const htmlEn = await mdEn.parse('::: tip\nplain\n:::')
  assert.match(htmlEn, /doc-note-title">Tip</)
})

test('github-slugger ids for CJK headings match VitePress-era anchors (zh trees keep their ids)', () => {
  const slugger = new GithubSlugger()
  assert.equal(slugger.slug(headingPlainText('用户设置')), '用户设置')
  assert.equal(slugger.slug(headingPlainText('Worker（JSON-RPC）')), 'workerjson-rpc')
})

test('only dated changelog sections publish — undated ([Unreleased]) working-area entries never reach the timeline', () => {
  const { sections } = splitChangelog('## [Unreleased]\nwip\n\n## [4.1.0-beta.1] — 2026-10-15\nbody\n\n## [4.0.0] — 2026-09-30\nbody')
  assert.deepEqual(
    releasedSections(sections).map((s) => s.version),
    ['4.1.0-beta.1', '4.0.0'],
  )
})

test('releaseBase strips the prerelease suffix, keeps patches whole, passes non-semver through', () => {
  assert.equal(releaseBase('4.0.0-rc.3'), '4.0.0')
  assert.equal(releaseBase('4.0.0-beta.1'), '4.0.0')
  assert.equal(releaseBase('4.0.1'), '4.0.1')
  assert.equal(releaseBase('Unreleased'), 'Unreleased')
})

test('condensePrerelease keeps one bold headline per bullet and drops prose/headings', () => {
  const headlines = condensePrerelease([
    '### ✨ Added',
    'Intro prose paragraph that is not a bullet and must be dropped entirely.',
    '- **Durable agent runs.** Each plan-and-execute run is snapshotted to the',
    '  database (`ai_agent_run`) with a sequenced lifecycle-event append log.',
    '- No bold lead here. Just a first sentence. The rest is dropped.',
    '',
    '### 🐛 Fixed',
    '- **Fixed the boot crash.** Explanation continues',
    '  across wrapped lines.',
  ])
  assert.deepEqual(headlines, [
    'Durable agent runs.',
    'No bold lead here',
    'Fixed the boot crash.',
  ])
})

test('mergeReleaseLines: stable absorbs its prerelease cycle; in-flight line keeps the newest pre', () => {
  const { sections } = splitChangelog(
    [
      '## [4.1.0-beta.2] — 2026-10-20', '### Added', '- **New beta two thing.**', '## [4.1.0-beta.1] — 2026-10-15', '### Added', '- **Beta one thing.**', '## [4.0.0-rc.1] — 2026-09-01', '- **RC fix.**', '## [4.0.0] — 2026-09-30', '- **GA summary.**', '## [3.2.0] — IDEA 2025 New UI Redesign', '- **Old entry.**',
    ].join('\n'),
  )
  const lines = mergeReleaseLines(releasedSections(sections))
  assert.deepEqual(
    lines.map((line) => line.version),
    ['4.1.0-beta.2', '4.0.0', '3.2.0'],
  )
  // In-flight line: newest pre's own body, older pres condensed in.
  assert.deepEqual(lines[0].headlines, ['Beta one thing.'])
  // Stable line: GA body + the whole prerelease cycle condensed (file order).
  assert.deepEqual(lines[1].headlines, ['RC fix.'])
  assert.equal(lines[1].date, '2026-09-30')
  // Non-ISO date titles pass through untouched.
  assert.equal(lines[2].date, 'IDEA 2025 New UI Redesign')
  assert.deepEqual(lines[2].headlines, [])
})

test('mergeReleaseLines dedupes repeated headlines across a prerelease cycle and tolerates a misfiled stable date', () => {
  const { sections } = splitChangelog(
    ['## [4.0.0-rc.1] — 2026-09-20', '- **Same change.**', '- **Unique rc change.**', '## [4.0.0] — 2026-07-29', '- **GA.**', '## [4.0.0-beta.1] — 2026-08-01', '- **Same change.**'].join('\n'),
  )
  const [line] = mergeReleaseLines(releasedSections(sections))
  assert.equal(line.version, '4.0.0')
  // The line's display date is the NEWEST ISO date in it, even when the stable
  // section itself is misdated mid-cycle (the historical 4.0.0 case).
  assert.equal(line.date, '2026-09-20')
  assert.deepEqual(line.headlines, ['Same change.', 'Unique rc change.'])
})

test('the shipped navigation module satisfies the linkTarget contract for every entry', async () => {
  const { en, zh } = await import('./navigation.mjs')
  for (const nav of [en, zh]) {
    for (const link of [...nav.nav, ...nav.sidebar.flatMap((group) => group.items)]) {
      const { routePath, locale } = linkTarget(link.link)
      assert.equal(typeof routePath, 'string')
      assert.ok(['en', 'zh'].includes(locale), `locale for ${link.link}`)
    }
  }
})

test('DOCS_ROOT points at the docs package', () => {
  assert.ok(DOCS_ROOT.endsWith('docs'))
})
