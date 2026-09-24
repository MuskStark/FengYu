import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const here = dirname(fileURLToPath(import.meta.url))
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
await ctx.addInitScript(() => localStorage.setItem('fengyu-theme', JSON.stringify('dark')))
const page = await ctx.newPage()
await page.goto('http://localhost:5174/', { waitUntil: 'networkidle', timeout: 30000 }).catch(e => console.log('goto:', e.message.split('\n')[0]))
await page.addStyleTag({ content: readFileSync(join(here, 'inject.css'), 'utf8') })
await page.waitForTimeout(900)
const check = await page.evaluate(() => ({
  primary: getComputedStyle(document.documentElement).getPropertyValue('--color-primary').trim(),
  avatarClip: getComputedStyle(document.querySelector('.sidebar-avatar')).clipPath !== 'none',
}))
console.log('at-screenshot-time:', JSON.stringify(check))
await page.screenshot({ path: join(here, 'verify-dark.png') })
await browser.close()
