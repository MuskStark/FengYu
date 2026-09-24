import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const here = dirname(fileURLToPath(import.meta.url))
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage()
await page.goto('about:blank')
for (const f of ['after/08-settings-dark.png', 'after/08-settings-light.png']) {
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
    let gold = 0
    for (let i = 0; i < d.length; i += 4) {
      const [r, g, b] = [d[i], d[i + 1], d[i + 2]]
      if (r > 195 && g > 140 && g < 215 && b < 130 && r - b > 100) gold++
    }
    const px = (x, y) => Array.from(ctx.getImageData(img.width * x, img.height * y, 1, 1).data).slice(0, 3)
    return { gold, nav: px(0.08, 0.5), content: px(0.55, 0.5), card: px(0.55, 0.3) }
  }, b64)
  console.log(f, JSON.stringify(res))
}
await browser.close()
