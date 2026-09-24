import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(1800)
const found = await page.evaluate(() => {
  const rootEl = document.getElementById('root')
  const ckey = Object.keys(rootEl).find(k => k.startsWith('__reactContainer$'))
  const hits = []
  const visit = (node, depth) => {
    if (!node || depth > 90 || hits.length > 3) return
    if (node.tag === 11) {
      const v = node.memoizedProps?.value
      if (v && v.navigator && typeof v.navigator.push === 'function') {
        hits.push({
          basename: v.basename,
          pushStr: String(v.navigator.push).slice(0, 70).replace(/\s+/g, ' '),
          goStr: typeof v.navigator.go === 'function',
          createHrefOk: typeof v.navigator.createHref === 'function',
          loc: v.navigator.location ? v.navigator.location.pathname : v.navigatorLocation,
        })
        window.__nav = v.navigator
      }
    }
    visit(node.child, depth + 1)
    visit(node.sibling, depth + 1)
  }
  visit(rootEl[ckey], 0)
  return hits
})
console.log('navigator providers:', JSON.stringify(found))
if (found.length) {
  const r = await page.evaluate(() => {
    try { window.__nav.push('/tools', { wtf: 1 }); return 'push returned' } catch (e) { return 'ERR ' + String(e).slice(0, 150) }
  })
  console.log('direct push:', r)
  await page.waitForTimeout(700)
  console.log('url:', page.url())
}
await browser.close()
