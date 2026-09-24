import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('console', m => { if (m.type() === 'warn' || m.type() === 'error') console.log('[pg]', m.type(), m.text().slice(0, 220)) })
await page.goto('http://localhost:5175/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(2200)
const res = await page.evaluate(() => {
  // instrument history BEFORE anything
  const calls = []
  const push = history.pushState.bind(history)
  history.pushState = function (...a) { calls.push('pushState→' + String(a[2])); return push(...a) }
  window.__histCalls = calls

  const btn = [...document.querySelectorAll('.sidebar-primary-nav .cx-nav-item')].find(b => /工具/.test(b.textContent || ''))
  let f = btn[Object.keys(btn).find(k => k.startsWith('__reactFiber$'))]
  while (f && !(f.tag === 0 && f.type?.name === 'Sidebar')) f = f.return
  let h = f.memoizedState, nav = null
  while (h) {
    const st = h.memoizedState
    if (typeof st === 'function' && /\(to, options/.test(String(st))) { nav = st; break }
    h = h.next
  }
  const out = { navFound: !!nav }
  if (nav) {
    try { nav('/tools'); out.navCalled = true } catch (e) { out.navThrew = String(e).slice(0, 150) }
  }
  // find BrowserRouter fiber → its historyRef hook value
  let bf = f
  while (bf && bf.type?.name !== 'BrowserRouter') bf = bf.return
  if (bf) {
    let hh = bf.memoizedState
    while (hh) {
      const st = hh.memoizedState
      if (st && typeof st === 'object' && 'current' in st && st.current && typeof st.current.push === 'function') {
        out.historyFound = true
        out.historyKeys = Object.keys(st.current).slice(0, 8)
        try { st.current.push('/store', {}); out.historyPushCalled = true } catch (e) { out.historyPushThrew = String(e).slice(0, 150) }
        break
      }
      hh = hh.next
    }
  }
  out.histCalls = calls
  out.urlNow = location.pathname
  return out
})
console.log(JSON.stringify(res, null, 1))
await page.waitForTimeout(800)
console.log('url after:', page.url(), 'histCalls:', await page.evaluate(() => window.__histCalls))
await browser.close()
