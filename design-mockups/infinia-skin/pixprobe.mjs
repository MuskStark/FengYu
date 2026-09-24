// Deterministic pixel-level verification of the injected theme in screenshots.
import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const here = dirname(fileURLToPath(import.meta.url))
const theme = process.argv[2] ?? 'dark'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 })
await ctx.addInitScript(t => localStorage.setItem('fengyu-theme', JSON.stringify(t)), theme)
const page = await ctx.newPage()
await page.goto('http://localhost:5174/', { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {})
await page.addStyleTag({ content: readFileSync(join(here, 'inject.css'), 'utf8') })
await page.waitForTimeout(700)
const res = await page.evaluate(() => {
  const px = (el, x = 0.5, y = 0.5) => {
    if (!el) return null
    const r = el.getBoundingClientRect()
    const el2 = document.elementFromPoint(r.left + r.width * x, r.top + r.height * y)
    const s = getComputedStyle(el2)
    return s.backgroundColor + ' | bg-img:' + (s.backgroundImage !== 'none' ? 'yes' : 'no')
  }
  const avatar = document.querySelector('.sidebar-avatar')
  const av = avatar ? getComputedStyle(avatar) : null
  return {
    avatarBg: av?.backgroundImage?.slice(0, 50),
    avatarFg: av?.color,
    send: px(document.querySelector('.composer-send')),
    sendFg: (() => { const b = document.querySelector('.composer-send'); return b ? getComputedStyle(b).color : null })(),
    activeNav: (() => { const n = document.querySelector('.cx-nav-item.active'); return n ? getComputedStyle(n).boxShadow : null })(),
    mainBg: (() => { const m = document.querySelector('.fx-main'); return m ? getComputedStyle(m).backgroundColor : null })(),
    railBg: (() => { const s = document.querySelector('.fx-shell'); return s ? getComputedStyle(s).backgroundColor : null })(),
  }
})
console.log(theme, JSON.stringify(res, null, 2))
await browser.close()
