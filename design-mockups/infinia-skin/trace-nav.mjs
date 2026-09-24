import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const tryClick = async (desc, locator) => {
  await page.goto('http://localhost:5175/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
  await page.waitForTimeout(1200)
  await locator.click().catch(e => console.log(desc, 'CLICK-ERR', e.message.split('\n')[0]))
  await page.waitForTimeout(900)
  console.log(desc, '→', page.url())
}
// primary sidebar nav buttons
await tryClick('nav-工具', page.locator('.sidebar-primary-nav .cx-nav-item', { hasText: /工具/ }).first())
await tryClick('nav-商店', page.locator('.sidebar-primary-nav .cx-nav-item', { hasText: /商店/ }).first())
await tryClick('nav-新对话', page.locator('.sidebar-primary-nav .cx-nav-item').first())
// menu items
await page.goto('http://localhost:5175/', { waitUntil: 'load' }).catch(() => {})
await page.waitForTimeout(1200)
await page.click('.sidebar-user-button')
await page.waitForTimeout(400)
await page.locator('.sidebar-account-menu-item', { hasText: /用户中心|Account/ }).first().click().catch(e => console.log('menu-account ERR', e.message.split('\n')[0]))
await page.waitForTimeout(900)
console.log('menu-用户中心 →', page.url())
await browser.close()
