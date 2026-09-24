import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const here = dirname(fileURLToPath(import.meta.url))
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 })
await ctx.addInitScript(() => localStorage.setItem('fengyu-theme', JSON.stringify('light')))
const page = await ctx.newPage()
await page.goto('http://localhost:5174/', { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {})
await page.addStyleTag({ content: readFileSync(join(here, 'inject.css'), 'utf8') })
await page.evaluate(() => {
  const root = document.documentElement
  root.classList.remove('dark', 'v-theme--dark', 'theme-zai-dark')
  root.classList.add('v-theme--light', 'theme-zai-light')
})
await page.waitForTimeout(400)
const res = await page.evaluate(() => {
  const avatar = document.querySelector('.sidebar-avatar')
  const av = avatar ? getComputedStyle(avatar) : null
  const send = document.querySelector('.composer-send')
  return {
    avatarBg: av?.backgroundImage?.slice(0, 45),
    sendBg: send ? getComputedStyle(send).backgroundColor : null,
    activeNav: (() => { const n = document.querySelector('.cx-nav-item.active'); return n ? getComputedStyle(n).boxShadow : null })(),
    mainBg: (() => { const m = document.querySelector('.fx-main'); return m ? getComputedStyle(m).backgroundColor : null })(),
    railBg: (() => { const s = document.querySelector('.fx-shell'); return s ? getComputedStyle(s).backgroundColor : null })(),
  }
})
console.log('light-forced:', JSON.stringify(res, null, 2))
await browser.close()
