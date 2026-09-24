import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(1800)
const res = await page.evaluate(() => {
  const btn = [...document.querySelectorAll('.sidebar-primary-nav .cx-nav-item')][0]
  let f = btn[Object.keys(btn).find(k => k.startsWith('__reactFiber$'))]
  // climb to the component fiber that owns hooks (Sidebar function component)
  while (f && (typeof f.type !== 'function' || !f.memoizedState || f.tag !== 1)) f = f.return
  if (!f) return { err: 'no component fiber' }
  const comp = f.type?.name || f.type?.displayName || '?'
  const hooks = []
  let h = f.memoizedState
  let i = 0
  while (h && i < 40) {
    const st = h.memoizedState
    let desc = typeof st
    if (st && typeof st === 'object' && 'current' in st) desc = 'ref{current=' + String(st.current).slice(0, 12) + '}'
    else if (typeof st === 'function') desc = 'fn:' + String(st).slice(0, 40).replace(/\s+/g, ' ')
    else if (Array.isArray(st)) desc = 'arr[' + st.length + ']'
    else desc = String(st)?.slice(0, 30)
    hooks.push(desc)
    h = h.next; i++
  }
  return { comp, hooks }
})
console.log(JSON.stringify(res, null, 1))
await browser.close()
