import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.goto('http://localhost:5175/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(2000)
const res = await page.evaluate(() => {
  const btn = [...document.querySelectorAll('.sidebar-primary-nav .cx-nav-item')][0]
  let f = btn[Object.keys(btn).find(k => k.startsWith('__reactFiber$'))]
  while (f && !(f.tag === 0 && f.memoizedState)) f = f.return
  if (!f) return { err: 'none' }
  const out = { component: f.type?.name || '?', hooks: [] }
  let h = f.memoizedState
  let i = 0
  while (h && i++ < 45) {
    const st = h.memoizedState
    let d
    if (st && typeof st === 'object' && 'current' in st) {
      const c = st.current
      d = 'REF current=' + (typeof c === 'object' ? '[obj]' : String(c).slice(0, 16))
    } else if (typeof st === 'function') {
      d = 'FN ' + String(st).slice(0, 46).replace(/\s+/g, ' ')
    } else {
      d = typeof st + ' ' + (st === null || st === undefined ? String(st) : String(st).slice(0, 36))
    }
    out.hooks.push(d)
    h = h.next
  }
  return out
})
console.log(JSON.stringify(res, null, 1))
await browser.close()
