import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(1800)
const before = await page.evaluate(() => {
  const rows = [...document.querySelectorAll('.sidebar-conversation')]
  return { rows: rows.length, active: rows.findIndex(r => r.classList.contains('active')) }
})
const row = page.locator('.sidebar-conversation').nth(before.active === 0 && before.rows > 1 ? 1 : 0)
if (before.rows > (before.active === 0 && before.rows > 1 ? 1 : 0)) {
  await row.click()
  await page.waitForTimeout(1000)
}
const after = await page.evaluate(() => {
  const rows = [...document.querySelectorAll('.sidebar-conversation')]
  return { active: rows.findIndex(r => r.classList.contains('active')), url: location.pathname }
})
console.log('before:', JSON.stringify(before), 'after:', JSON.stringify(after))
await browser.close()
