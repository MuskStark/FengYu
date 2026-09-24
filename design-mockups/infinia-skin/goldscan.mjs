// Scan local screenshots for the gold family (#eab04b / #f6bd60) — proof the accent landed.
import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const here = dirname(fileURLToPath(import.meta.url))
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage()
await page.goto('about:blank')
for (const f of ['after/01-chat-dark.png', 'after/01-chat-light.png', 'after/08-settings-dark.png', 'after/06-store-dark.png']) {
  const b64 = readFileSync(join(here, f)).toString('base64')
  const res = await page.evaluate(async (data) => {
    const img = new Image()
    img.src = 'data:image/png;base64,' + data
    await img.decode()
    const c = document.createElement('canvas')
    c.width = img.width; c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const d = ctx.getImageData(0, 0, c.width, c.height).data
    let gold = 0, orange = 0
    for (let i = 0; i < d.length; i += 4) {
      const [r, g, b] = [d[i], d[i + 1], d[i + 2]]
      // gold family: amber fill or gradient orange
      if (r > 195 && g > 140 && g < 215 && b < 130 && r - b > 100) gold++
      if (r > 230 && g > 90 && g < 160 && b < 90) orange++
    }
    return { goldPx: gold, orangePx: orange, total: c.width * c.height }
  }, b64)
  console.log(f, JSON.stringify(res))
}
await browser.close()
