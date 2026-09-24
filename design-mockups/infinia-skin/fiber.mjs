import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(1500)
const res = await page.evaluate(() => {
  const fiberOf = el => {
    const key = Object.keys(el).find(k => k.startsWith('__reactFiber$'))
    return key ? el[key] : null
  }
  const navBtn = [...document.querySelectorAll('.sidebar-primary-nav .cx-nav-item')].find(b => /工具/.test(b.textContent || ''))
  const userBtn = document.querySelector('.sidebar-user-button')
  const describe = (el) => {
    if (!el) return 'no element'
    const f = fiberOf(el)
    if (!f) return 'NO FIBER KEY: ' + Object.keys(el).filter(k => k.startsWith('__react')).join(',')
    const props = f.memoizedProps || {}
    return {
      tag: f.tag, type: typeof f.type === 'string' ? f.type : (f.type?.name || 'fn'),
      hasOnClick: typeof props.onClick === 'function',
      propKeys: Object.keys(props).slice(0, 10),
      disabled: props.disabled ?? null,
    }
  }
  return { navBtn: describe(navBtn), userBtn: describe(userBtn) }
})
console.log(JSON.stringify(res, null, 1))
await browser.close()
