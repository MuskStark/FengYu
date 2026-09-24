import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('console', m => console.log('[console]', m.type(), m.text().slice(0, 150)))
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(1500)
const r = await page.evaluate(() => {
  const btn = [...document.querySelectorAll('.sidebar-primary-nav .cx-nav-item')].find(b => /工具/.test(b.textContent || ''))
  const key = Object.keys(btn).find(k => k.startsWith('__reactFiber$'))
  const f = btn[key]
  const onClick = f.memoizedProps.onClick
  try {
    onClick(new MouseEvent('click', { bubbles: true }))
    return { called: true }
  } catch (e) {
    return { called: false, err: String(e).slice(0, 200) }
  }
})
console.log('direct onClick call:', JSON.stringify(r))
await page.waitForTimeout(1200)
console.log('url after:', page.url())
await browser.close()
