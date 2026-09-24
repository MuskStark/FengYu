import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(1800)
const res = await page.evaluate(() => {
  const btn = [...document.querySelectorAll('.sidebar-primary-nav .cx-nav-item')][0]
  let f = btn[Object.keys(btn).find(k => k.startsWith('__reactFiber$'))]
  const seen = []
  while (f && seen.length < 14) {
    if (typeof f.type === 'function') {
      const name = f.type?.name || '(anon)'
      const hasHooks = !!f.memoizedState && !!f.memoizedState.next !== undefined && (f.memoizedState.tag !== undefined)
      const refs = []
      if (hasHooks && (f.tag === 0 || f.tag === 11)) {
        let h = f.memoizedState, i = 0
        while (h && i < 45) {
          const st = h.memoizedState
          if (st && typeof st === 'object' && 'current' in st) {
            refs.push('ref{cur=' + String(st.current).slice(0, 14) + '}')
          } else if (typeof st === 'function') {
            refs.push('fn')
          }
          h = h.next; i++
        }
      }
      seen.push(`${name} tag=${f.tag} refs=[${refs.join(',')}]`)
    }
    f = f.return
  }
  return seen
})
console.log(JSON.stringify(res, null, 1))
await browser.close()
