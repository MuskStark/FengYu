import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
for (const theme of ['light', 'dark']) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.addInitScript(t => localStorage.setItem('fengyu-theme', JSON.stringify(t)), theme)
  const page = await ctx.newPage()
  await page.goto('http://localhost:5173/settings', { waitUntil: 'load', timeout: 20000 }).catch(() => {})
  await page.waitForTimeout(1200)
  // Synthesize a checked switch with the real classes — CSS verification, data-independent.
  const res = await page.evaluate(() => {
    const host = document.createElement('div')
    host.innerHTML = '<div class="mcp-switch"><input type="checkbox" checked><span></span></div>'
    document.body.appendChild(host)
    const span = host.querySelector('span')
    return { track: getComputedStyle(span).backgroundColor, thumb: getComputedStyle(span, '::after').backgroundColor }
  })
  console.log(theme, JSON.stringify(res))
  await ctx.close()
}
await browser.close()
