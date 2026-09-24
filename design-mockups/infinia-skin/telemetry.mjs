import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('console', m => { const t = m.text(); if (t.includes('[NAVTEL]') || t.includes('[shell]')) console.log('[pg]', t.slice(0, 260)) })
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(1800)
await page.evaluate(() => {
  const btn = [...document.querySelectorAll('.sidebar-primary-nav .cx-nav-item')].find(b => /工具/.test(b.textContent || ''))
  let f = btn[Object.keys(btn).find(k => k.startsWith('__reactFiber$'))]
  while (f && !(f.tag === 0 && f.memoizedState)) f = f.return
  let h = f.memoizedState
  while (h) {
    const st = h.memoizedState
    if (typeof st === 'function' && String(st).includes('(to, options = {})')) {
      const orig = st
      const wrapped = (to, options) => {
        console.log('[NAVTEL] navigate called with', JSON.stringify(to))
        try {
          const r = orig(to, options)
          console.log('[NAVTEL] navigate returned', String(r))
        } catch (e) {
          console.log('[NAVTEL] navigate THREW ' + String(e).slice(0, 200))
        }
      }
      h.memoizedState = wrapped
      // also patch the onClick prop to call the wrapped one
      const props = f.memoizedProps
      break
    }
    h = h.next
  }
})
await page.click('.sidebar-user-button')
await page.waitForTimeout(300)
await page.locator('.sidebar-account-menu-item', { hasText: /设置|Settings/ }).first().click()
await page.waitForTimeout(1200)
console.log('final url:', page.url())
await browser.close()
