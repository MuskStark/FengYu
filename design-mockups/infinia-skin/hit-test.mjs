import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 20000 }).catch(() => {})
await page.waitForTimeout(1800)
await page.click('.sidebar-user-button')
await page.waitForTimeout(500)
const probe = await page.evaluate(() => {
  const item = [...document.querySelectorAll('.sidebar-account-menu-item')].find(el => /设置|Settings/.test(el.textContent || ''))
  if (!item) return { found: false }
  const r = item.getBoundingClientRect()
  const x = r.left + r.width / 2, y = r.top + r.height / 2
  const hit = document.elementFromPoint(x, y)
  const chain = []
  let el = item
  while (el && el !== document.documentElement) {
    const s = getComputedStyle(el)
    chain.push(`${el.tagName}.${String(el.className).slice(0, 30)} pe=${s.pointerEvents} zi=${s.zIndex}`)
    el = el.parentElement
  }
  return {
    found: true, rect: { x: r.x, y: r.y, w: r.width, h: r.height },
    hitIsItem: hit === item || item.contains(hit),
    hitElement: hit ? hit.tagName + '.' + String(hit.className).slice(0, 40) : null,
    chain: chain.slice(0, 8),
  }
})
console.log(JSON.stringify(probe, null, 1))
await browser.close()
