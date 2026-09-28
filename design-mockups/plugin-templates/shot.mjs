import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = join(here, 'shots')
mkdirSync(outDir, { recursive: true })

const base = 'http://localhost:4173/'

const pages = [
  ['00-overview', 'overview', 'light', true],
  ['01-t1-markdown-light', 't1', 'light', false],
  ['02-t1-markdown-dark', 't1', 'dark', false],
  ['03-t2-excel-light', 't2', 'light', false],
  ['04-t2-excel-dark', 't2', 'dark', false],
  ['05-t3-email-light', 't3', 'light', false],
  ['06-t3-email-dark', 't3', 'dark', false],
  ['07-t4-python-light', 't4', 'light', false],
  ['08-t4-python-dark', 't4', 'dark', false],
]

const browser = await chromium.launch({
  executablePath:
    '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
})
const ctx = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  deviceScaleFactor: 2,
})
const page = await ctx.newPage()

for (const [name, id, theme, fullPage] of pages) {
  const url = `${base}?page=${id}&theme=${theme}`
  await page.goto(url, { waitUntil: 'networkidle' })
  // 让动效进入稳定的中段状态（流水线阶段、终端打字、卡片堆）
  await page.waitForTimeout(4200)
  await page.screenshot({ path: join(outDir, `${name}.png`), fullPage })
  console.log('shot', name)
}

await browser.close()
