import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const here = dirname(fileURLToPath(import.meta.url))
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage()
await page.goto('about:blank')
for (const f of ['after/05-tools-dark.png', 'landed/05-tools-dark.png']) {
  const b64 = readFileSync(join(here, f)).toString('base64')
  const res = await page.evaluate(async data => {
    const img = new Image()
    img.src = 'data:image/png;base64,' + data
    await img.decode()
    const c = document.createElement('canvas')
    c.width = img.width; c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = (x, y) => Array.from(ctx.getImageData(img.width * x, img.height * y, 1, 1).data).slice(0, 3)
    return { mid1: px(0.42, 0.3), mid2: px(0.42, 0.45), mid3: px(0.45, 0.6), row: px(0.6, 0.35) }
  }, b64)
  console.log(f.padEnd(26), JSON.stringify(res))
}
await browser.close()
