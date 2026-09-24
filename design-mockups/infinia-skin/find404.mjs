import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('response', r => { if (r.status() >= 400) console.log('HTTP', r.status(), r.url()) })
page.on('requestfailed', r => console.log('REQFAIL', r.failure()?.errorText, r.url()))
await page.goto('http://localhost:5173/', { waitUntil: 'networkidle', timeout: 20000 }).catch(() => {})
await page.waitForTimeout(1000)
await browser.close()
