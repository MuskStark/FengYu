// High-fidelity verification for the tools expandable-card implementation.
// Runs a real Chromium (not the throttled IAB): screenshots light/dark,
// collapsed/expanded, a mid-morph frame, and asserts the interaction split.
import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = join(here, 'impl')
mkdirSync(outDir, { recursive: true })

const BASE = 'http://127.0.0.1:5173'
const results = []
const note = (name, ok, extra = '') => {
  results.push({ name, ok, extra })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? ' — ' + extra : ''}`)
}

async function shoot(theme) {
  // The desktop tree's playwright (1.63) expects browser build 1243, but the
  // machine cache holds 1234 — point launch at the cached executable directly.
  const browser = await chromium.launch({
    executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium_headless_shell-1234/chrome-headless-shell-mac-arm64/chrome-headless-shell',
  })
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } })
  await ctx.addInitScript(t => {
    localStorage.setItem('fengyu-theme', JSON.stringify(t))
  }, theme)
  const page = await ctx.newPage()
  await page.goto(`${BASE}/tools`, { waitUntil: 'networkidle', timeout: 30000 })
  await page.waitForSelector('.tools-card', { timeout: 10000 })
  await page.waitForTimeout(700)

  // 1. collapsed grid
  await page.screenshot({ path: join(outDir, `${theme}-grid.png`) })
  const cardCount = await page.locator('.tools-card').count()
  note(`${theme}: cards render`, cardCount > 0, `${cardCount} cards`)

  // 2. click 详情 button → expansion (not navigation)
  const firstCard = page.locator('.tools-card').filter({ hasText: '邮件中心' }).first()
  await firstCard.locator('.tools-card-foot .cx-btn').click()
  // mid-morph frame (~120ms after click)
  await page.waitForTimeout(120)
  await page.screenshot({ path: join(outDir, `${theme}-midmorph.png`) })
  await page.waitForTimeout(1100)
  await page.screenshot({ path: join(outDir, `${theme}-expanded.png`) })

  const expanded = await page.evaluate(() => {
    const card = document.querySelector('.tools-x-card')
    const dialog = document.querySelector('[role="dialog"]')
    const focused = document.activeElement?.getAttribute('aria-label')
    const r = card?.getBoundingClientRect()
    return {
      overlay: !!card,
      dialogSemantics: dialog?.getAttribute('role') === 'dialog' && dialog.getAttribute('aria-modal') === 'true',
      focusedClose: focused,
      centered: r ? Math.abs(r.x + r.width / 2 - innerWidth / 2) < 20 : false,
      perms: [...(card?.querySelectorAll('.cx-chip') ?? [])].map(c => c.textContent.trim()).slice(0, 8),
      switchOn: (card?.querySelector('.pg-switch input'))?.checked,
    }
  })
  note(`${theme}: expanded overlay`, expanded.overlay)
  note(`${theme}: dialog semantics`, expanded.dialogSemantics)
  note(`${theme}: focus → close button`, expanded.focusedClose === '关闭' || expanded.focusedClose === 'Close', String(expanded.focusedClose))
  note(`${theme}: centered morph target`, expanded.centered)
  note(`${theme}: permissions visible`, expanded.perms.length > 0, expanded.perms.join(','))
  note(`${theme}: enable switch on`, expanded.switchOn === true)
  note(`${theme}: still on /tools`, page.url().endsWith('/tools'), page.url())

  // 3. ESC closes
  await page.keyboard.press('Escape')
  await page.waitForTimeout(900)
  const closed = await page.evaluate(() => !document.querySelector('.tools-x-card'))
  note(`${theme}: ESC closes`, closed)

  // 4. outside click closes
  await firstCard.locator('.tools-card-foot .cx-btn').click()
  await page.waitForTimeout(800)
  await page.mouse.click(40, 400)
  await page.waitForTimeout(900)
  const closed2 = await page.evaluate(() => !document.querySelector('.tools-x-card'))
  note(`${theme}: outside-click closes`, closed2)

  // 5. card body click → opens plugin
  await firstCard.locator('.pg-card-desc').click()
  await page.waitForTimeout(1200)
  note(`${theme}: card body opens plugin`, /\/plugin\//.test(page.url()), page.url())

  // 6. collapsed badges
  await page.goto(`${BASE}/tools`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.tools-card', { timeout: 10000 })
  const badges = await page.evaluate(() => {
    const card = [...document.querySelectorAll('.tools-card')].find(c => c.textContent.includes('邮件中心'))
    return [...(card?.querySelectorAll('.tools-card-foot .cx-chip') ?? [])].map(c => `${c.textContent.trim()}(${[...c.classList].find(x => x.startsWith('cx-chip--')) ?? 'neutral'})`)
  })
  note(`${theme}: official badge on card`, badges.some(b => b.startsWith('官方')), badges.join(', '))

  await browser.close()
}

await shoot('light')
await shoot('dark')

const failed = results.filter(r => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} checks passed`)
process.exit(failed.length ? 1 : 0)
