// Synchronizes the docs-site changelog mirrors from the canonical CHANGELOG.md.
//
// Why this exists: docs/{en,zh}/reference/changelog.md used to be hand-maintained
// mirrors of the root CHANGELOG.md, and they drifted (e.g. root reached
// 4.0.0-alpha.8 while the mirrors still advertised alpha.6 as "Latest release").
// This script is the single source of truth for those two pages: the root
// CHANGELOG.md is authored by hand; both mirrors are generated from it.
//
// Wired into docs/package.json as a pre-hook for dev/build/preview, so the
// mirrors can never go stale again. Run it manually with:
//   npm --prefix docs run sync:changelog
//
// Output contract (do not break without coordinating with docs-updater skill):
//   - Reads root CHANGELOG.md.
//   - Parses the latest *released* version (first `## [<ver>] — <date>` heading
//     that is NOT `[Unreleased]`) to fill the "Latest release" callout.
//   - Writes docs/en/reference/changelog.md (English header) + the root body.
//   - Writes docs/zh/reference/changelog.md (Chinese header) + the root body.
//   - Hard-fails if CHANGELOG.md is missing/unreadable (a real error the user
//     must see). Gracefully degrades the callout if no released version is found
//     (e.g. a fresh repo with only an [Unreleased] section).

import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const root = resolve(__dirname, '../..')

const sourcePath = resolve(root, 'CHANGELOG.md')
const targets = [
  { path: resolve(root, 'docs/en/reference/changelog.md'), lang: 'en' },
  { path: resolve(root, 'docs/zh/reference/changelog.md'), lang: 'zh' }
]

const REPO = 'https://github.com/MuskStark/FengYu'

// Match e.g. `## [4.0.0-alpha.8] — 2026-08-04` (em dash) — never `[Unreleased]`.
const RELEASED_RE = /^## \[([^\]]+)\] — (\d{4}-\d{2}-\d{2})/m

function readChangelog() {
  try {
    return readFileSync(sourcePath, 'utf8')
  } catch (err) {
    console.error(`[sync-changelog] cannot read ${sourcePath}: ${err.message}`)
    process.exit(1)
  }
}

// Extract everything after the `# Changelog` title line + intro + `---` rule.
// We rebuild the body from the first `## [Unreleased]` or `## [<ver>]` heading
// onward, so the root intro paragraph (Keep a Changelog blurb) is dropped — the
// locale header supplies its own intro.
function extractBody(rootMd) {
  const match = rootMd.match(/^## \[/m)
  if (!match) {
    console.error('[sync-changelog] no `## [` version heading found in CHANGELOG.md')
    process.exit(1)
  }
  return escapeVueInterpolation(rewriteLinks(rootMd.slice(match.index).trimEnd()))
}

// The root CHANGELOG.md is written for GitHub viewing, so it uses repo-relative
// links like `[docs/en/foo.md](docs/en/foo.md)` or `[migration-3.1.md](migration-3.1.md)`.
// Those resolve fine on GitHub but are dead links inside VitePress (they point
// outside docs/ or to non-page paths). Rewrite every *relative* markdown link to
// an absolute GitHub URL so it still works for readers and the dead-link check
// stays green. Absolute (http/https/mailto) links and pure anchor (`#x`) links
// are left untouched.
const REL_LINK_RE = /(?<!!)\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g
function rewriteLinks(body) {
  return body.replace(REL_LINK_RE, (m, text, href) => {
    if (/^(https?:|mailto:|#)/i.test(href)) return m
    // GitHub serves the raw file at /blob/<default-branch>/<path>. Drop a leading
    // ./ if present and append to the repo blob URL.
    const path = href.replace(/^\.\//, '')
    return `[${text}](${REPO}/blob/main/${path})`
  })
}

// VitePress compiles each page into a Vue SFC, so any literal `{{ ... }}` in the
// CHANGELOG body (e.g. the step-result reference `{{steps.N.result}}`) is parsed
// as a Vue interpolation expression and crashes SSR. Escape double braces so they
// render verbatim. `\{` is a markdown-it escape and renders as a literal `{`.
function escapeVueInterpolation(body) {
  return body.replace(/\{\{/g, '\\{\\{').replace(/\}\}/g, '\\}\\}')
}

function latestRelease(rootMd) {
  // Scan all `## [<ver>] — <date>` headings in order, skip [Unreleased] is
  // implicit since its heading has no date and won't match RELEASED_RE.
  for (const line of rootMd.split('\n')) {
    const m = line.match(RELEASED_RE)
    if (m) return { version: m[1], date: m[2] }
  }
  return null
}

function callout(latest, lang) {
  if (latest) {
    const tag = `v${latest.version}`
    if (lang === 'en') {
      return `::: tip Latest release
**v${latest.version}** — ${latest.date} ·
[GitHub release](${REPO}/releases/tag/${tag})
:::`
    }
    return `::: tip 最新发布
**v${latest.version}** — ${latest.date} ·
[GitHub 发布](${REPO}/releases/tag/${tag})
:::`
  }
  // No released version yet — keep the callout informational rather than stale.
  return lang === 'en'
    ? `::: tip Latest release\nNo stable release yet — see **[Unreleased]** below.\n:::`
    : `::: tip 最新发布\n尚无正式发布版本 —— 见下方 **[Unreleased]**。\n:::`
}

function header(latest, lang) {
  if (lang === 'en') {
    return [
      '---',
      'title: Changelog',
      'lang: en',
      '---',
      '',
      '# Changelog',
      '',
      'All notable changes to **Infinia (蜂语 / FengYu)**. The canonical, always-up-to-date',
      `source is the repository's [CHANGELOG.md](${REPO}/blob/main/CHANGELOG.md) —`,
      'this page is generated from it on every docs build (see',
      '`docs/scripts/sync-changelog.mjs`). Do not edit this file directly; edit the root',
      'CHANGELOG.md instead.',
      '',
      callout(latest, 'en'),
      '',
      '---',
      '',
      ''
    ].join('\n')
  }
  return [
    '---',
    'title: 更新日志',
    'lang: zh-CN',
    '---',
    '',
    '# 更新日志',
    '',
    '**Infinia（蜂语 / FengYu）** 的所有重要变更。仓库中的',
    `[CHANGELOG.md](${REPO}/blob/main/CHANGELOG.md) 是唯一权威、始终最新的来源 —— 本页`,
    '由文档站点在每次构建时自动生成（见 `docs/scripts/sync-changelog.mjs`）。请勿直接编辑',
    '本文件，请在根 CHANGELOG.md 中修改。',
    '',
    callout(latest, 'zh'),
    '',
    '---',
    '',
    ''
  ].join('\n')
}

function main() {
  const rootMd = readChangelog()
  const body = extractBody(rootMd)
  const latest = latestRelease(rootMd)

  for (const target of targets) {
    const out = header(latest, target.lang) + body + '\n'
    writeFileSync(target.path, out)
    console.log(`[sync-changelog] wrote ${target.path} (latest=${latest ? latest.version : 'none'})`)
  }
}

main()
