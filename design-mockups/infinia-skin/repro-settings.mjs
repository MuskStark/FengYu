import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await ctx.newPage()
const errors = []
page.on('console', m => { if (m.type() === 'error') errors.push(m.text().slice(0, 160)) })
page.on('pageerror', e => errors.push('PAGEERROR: ' + String(e).slice(0, 200)))
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 20000 }).catch(e => errors.push('goto: ' + e.message))
await page.waitForTimeout(2000)
console.log('url after load:', page.url())

// 1) click the account avatar to open the menu
await page.click('.sidebar-user-button').catch(e => errors.push('avatar click: ' + e.message.split('\n')[0]))
await page.waitForTimeout(600)
const menuVisible = await page.evaluate(() => {
  const m = document.querySelector('.sidebar-account-menu')
  return m ? getComputedStyle(m).display + '|' + m.textContent?.replace(/\s+/g, ' ').slice(0, 60) : 'NO MENU ELEMENT'
})
console.log('menu:', menuVisible)

// 2) click the 设置 item
const settingsItem = page.locator('.sidebar-account-menu-item', { hasText: /设置|Settings/ }).first()
const count = await settingsItem.count()
console.log('settings item count:', count)
if (count) {
  await settingsItem.click().catch(e => errors.push('settings click: ' + e.message.split('\n')[0]))
  await page.waitForTimeout(2500)
}
console.log('url after settings click:', page.url())
const settingsDom = await page.evaluate(() => ({
  hasSettings: !!document.querySelector('.set-nav-item, .set-content, [class*="settings"]'),
  bodyText: document.body.innerText?.replace(/\s+/g, ' ').slice(0, 120),
}))
console.log('settings dom:', JSON.stringify(settingsDom))
console.log('errors:', errors.length ? errors.slice(0, 6) : 'none')
await browser.close()
