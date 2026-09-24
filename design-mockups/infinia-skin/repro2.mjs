import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
const failed = []
page.on('requestfailed', r => failed.push('REQFAIL ' + r.url().slice(-80)))
page.on('response', r => { if (r.status() >= 400) failed.push(r.status() + ' ' + r.url().slice(-90)) })
page.on('console', m => { if (m.type() === 'error') failed.push('CONSOLE ' + m.text().slice(0, 140)) })
page.on('pageerror', e => failed.push('PAGEERROR ' + String(e).slice(0, 200)))
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 20000 }).catch(() => {})
await page.waitForTimeout(1500)
// A) direct URL navigation
await page.goto('http://localhost:5173/settings', { waitUntil: 'load', timeout: 20000 }).catch(e => failed.push('goto settings: ' + e.message.split('\n')[0]))
await page.waitForTimeout(2000)
console.log('A direct-url:', page.url(), 'set-nav present:', await page.locator('.set-nav-item').count())
failed.length = 0
// B) click path from home
await page.goto('http://localhost:5173/', { waitUntil: 'load' }).catch(() => {})
await page.waitForTimeout(1500)
failed.length = 0
await page.click('.sidebar-user-button').catch(e => failed.push('avatar: ' + e.message.split('\n')[0]))
await page.waitForTimeout(400)
await page.locator('.sidebar-account-menu-item', { hasText: /设置|Settings/ }).first().click().catch(e => failed.push('item: ' + e.message.split('\n')[0]))
await page.waitForTimeout(2000)
console.log('B click-path url:', page.url(), 'set-nav present:', await page.locator('.set-nav-item').count())
console.log('B failures:', failed.slice(0, 8))
await browser.close()
