import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('console', m => { if (m.type() === 'warn' || m.type() === 'error') console.log('[pg]', m.type(), m.text().slice(0, 250)) })
await page.goto('http://localhost:5175/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(2200)
const res = await page.evaluate(() => {
  const btn = [...document.querySelectorAll('.sidebar-primary-nav .cx-nav-item')][0]
  let f = btn[Object.keys(btn).find(k => k.startsWith('__reactFiber$'))]
  while (f && !(f.tag === 0 && f.memoizedState)) f = f.return
  let h = f.memoizedState
  const fns = []
  while (h) {
    if (typeof h.memoizedState === 'function') fns.push(String(h.memoizedState).slice(0, 44).replace(/\s+/g, ' '))
    h = h.next
  }
  // exact hooks3 approach
  let h2 = f.memoizedState, nav = null
  while (h2) {
    const st = h2.memoizedState
    if (typeof st === 'function' && String(st).includes('(to, options = {})')) { nav = st; break }
    h2 = h2.next
  }
  const out = { component: f.type?.name, fnCount: fns.length, navFound: !!nav, fns: fns.filter(s => s.includes('to,')) }
  if (nav) {
    try { nav('/tools'); out.called = true } catch (e) { out.threw = String(e).slice(0, 150) }
  }
  return out
})
console.log(JSON.stringify(res, null, 1))
await page.waitForTimeout(800)
console.log('url after nav() call:', page.url())
await browser.close()
