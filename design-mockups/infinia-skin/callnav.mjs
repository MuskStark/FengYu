import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('console', m => console.log('[pg]', m.type(), m.text().slice(0, 200)))
await page.goto('http://localhost:5175/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(2000)
const res = await page.evaluate(() => {
  const btn = [...document.querySelectorAll('.sidebar-primary-nav .cx-nav-item')][0]
  let f = btn[Object.keys(btn).find(k => k.startsWith('__reactFiber$'))]
  while (f && !(f.tag === 0 && f.memoizedState)) f = f.return
  let h = f.memoizedState, nav = null
  while (h) {
    if (typeof h.memoizedState === 'function' && String(h.memoizedState).includes('(to, options')) { nav = h.memoizedState; break }
    h = h.next
  }
  if (!nav) return { found: false }
  const src = String(nav)
  let out = { found: true, srcHead: src.slice(0, 90).replace(/\s+/g, ' ') }
  try {
    const r = nav('/tools')
    out.callResult = String(r)
  } catch (e) { out.callThrew = String(e).slice(0, 200) }
  return out
})
console.log(JSON.stringify(res, null, 1))
await page.waitForTimeout(1000)
console.log('url:', page.url())
await browser.close()
