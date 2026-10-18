#!/usr/bin/env node
/**
 * emit-static.mjs — post-build static emission for GitHub Pages.
 *
 * The site is a React SPA, but it must keep what the VitePress era gave the
 * hosted site: clean extensionless URLs, real per-page <title>/description/
 * canonical/OG in the served HTML, a 404 shell, sitemap.xml and robots.txt.
 *
 * For every content route this stamps a copy of the built SPA shell at
 * dist/<route>.html (GitHub Pages serves /route from route.html — the same
 * mechanism cleanUrls relied on). Deep links land on a real 200 page with
 * static meta; the shell then hydrates and the router takes over. Index-style
 * routes (…/index) additionally get dist/<route>/index.html so the trailing-
 * slash form works too. True unknown paths fall through to 404.html (the same
 * shell) and render the SPA NotFound page.
 */
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const DIST = path.join(ROOT, 'dist')
const MANIFEST = path.join(ROOT, 'src', '.content', 'manifest.json')
const SITE_URL = 'https://muskstark.github.io/FengYu/'

const SITE_TITLE = 'Infinia Docs'
const SITE_DESCRIPTION = 'Infinia (蜂语 / FengYu) documentation — architecture, plugins, skills, and reference for the AI-native orchestration platform.'
const NOT_FOUND_TITLE = 'Page not found · Infinia Docs'

function stamp(shell, { title, description, canonicalUrl, lang }) {
  let html = shell.replace(/<title>[\s\S]*?<\/title>/, `<title>${title}</title>`)
  html = html.replace(
    /<meta\s+name="description"\s+content="[^"]*"\s*\/?>/,
    `<meta name="description" content="${description.replace(/"/g, '&quot;')}" />`,
  )
  const canonical = canonicalUrl
    ? `<link rel="canonical" href="${canonicalUrl}" /><meta property="og:url" content="${canonicalUrl}" />`
    : ''
  const og = `<meta property="og:type" content="website" /><meta property="og:site_name" content="Infinia Docs" />` +
    `<meta property="og:title" content="${title.replace(/"/g, '&quot;')}" />` +
    `<meta property="og:description" content="${description.replace(/"/g, '&quot;')}" />`
  html = html.replace(
    /<meta\s+name="description"\s+content="[^"]*"\s*\/?>/,
    (meta) => `${meta}\n    ${canonical}\n    ${og}`,
  )
  if (lang) html = html.replace(/<html[^>]*lang="[^"]*"/, (match) => match.replace(/lang="[^"]*"/, `lang="${lang}"`))
  return html
}

async function writeHtml(file, html) {
  await fs.mkdir(path.dirname(file), { recursive: true })
  await fs.writeFile(file, html)
}

export async function emitStatic(root = ROOT) {
  const dist = path.join(root, 'dist')
  const manifestPath = path.join(root, 'src', '.content', 'manifest.json')
  const shell = await fs.readFile(path.join(dist, 'index.html'), 'utf8')
  const { routes } = JSON.parse(await fs.readFile(manifestPath, 'utf8'))

  await writeHtml(path.join(dist, 'index.html'), stamp(shell, { title: SITE_TITLE, description: SITE_DESCRIPTION, canonicalUrl: SITE_URL }))
  await writeHtml(path.join(dist, '404.html'), stamp(shell, { title: NOT_FOUND_TITLE, description: SITE_DESCRIPTION }))

  const sitemapEntries = [{ loc: SITE_URL, lastmod: null }]
  for (const [route, meta] of Object.entries(routes)) {
    const canonicalUrl = `${SITE_URL}${route.slice(1)}`
    const stamped = stamp(shell, {
      title: meta.title ? `${meta.title} · ${SITE_TITLE}` : SITE_TITLE,
      description: meta.description || SITE_DESCRIPTION,
      canonicalUrl,
      lang: meta.lang,
    })
    await writeHtml(path.join(dist, `${route.replace(/^\//, '')}.html`), stamped)
    if (meta.key.endsWith('/index')) {
      await writeHtml(path.join(dist, route.replace(/^\//, ''), 'index.html'), stamped)
    }
    sitemapEntries.push({ loc: canonicalUrl, lastmod: meta.updatedAt })
  }

  const today = new Date().toISOString().slice(0, 10)
  const sitemap = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${sitemapEntries
    .map((entry) => `  <url><loc>${entry.loc}</loc>${entry.lastmod ? `<lastmod>${String(entry.lastmod).slice(0, 10)}</lastmod>` : `<lastmod>${today}</lastmod>`}</url>`)
    .join('\n')}\n</urlset>\n`
  await fs.writeFile(path.join(dist, 'sitemap.xml'), sitemap)
  await fs.writeFile(path.join(dist, 'robots.txt'), `User-agent: *\nAllow: /\n\nSitemap: ${SITE_URL}sitemap.xml\n`)

  const pages = Object.keys(routes).length
  console.log(`[static] stamped ${pages} routes + index/404, sitemap.xml, robots.txt → dist/`)
  return { pages }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  emitStatic().catch((error) => {
    console.error(`[static] ${error.message}`)
    process.exitCode = 1
  })
}
