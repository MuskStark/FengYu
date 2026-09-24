// Probe computed styles to verify the theme injection actually applied.
import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const theme = process.argv[2] ?? 'dark'
const browser = await chromium.launch({
  executablePath:
    '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
})
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await ctx.addInitScript(t => localStorage.setItem('fengyu-theme', JSON.stringify(t)), theme)
const page = await ctx.newPage()
await page.goto('http://localhost:5174/', { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {})

const before = await page.evaluate(() => ({
  avatarClip: getComputedStyle(document.querySelector('.sidebar-avatar')).clipPath,
  sendBg: getComputedStyle(document.querySelector('.composer-send'))?.background,
  primary: getComputedStyle(document.documentElement).getPropertyValue('--color-primary').trim(),
}))

const css = readFileSync(join(here, 'inject.css'), 'utf8')
await page.addStyleTag({ content: css })
await page.waitForTimeout(300)

const after = await page.evaluate(() => ({
  avatarClip: getComputedStyle(document.querySelector('.sidebar-avatar')).clipPath,
  avatarBg: getComputedStyle(document.querySelector('.sidebar-avatar')).backgroundImage.slice(0, 60),
  sendBg: getComputedStyle(document.querySelector('.composer-send'))?.background,
  sendColor: getComputedStyle(document.querySelector('.composer-send'))?.color,
  primary: getComputedStyle(document.documentElement).getPropertyValue('--color-primary').trim(),
  border: getComputedStyle(document.documentElement).getPropertyValue('--color-border').trim(),
  navActiveShadow: getComputedStyle(document.querySelector('.cx-nav-item.active'))?.boxShadow,
}))
console.log(JSON.stringify({ theme, before, after }, null, 2))
await browser.close()
