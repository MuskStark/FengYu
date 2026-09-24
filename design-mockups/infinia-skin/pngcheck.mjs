// Decode local PNGs in a headless page and sample real pixels — no CDN, no vision.
import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const here = dirname(fileURLToPath(import.meta.url))
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 800, height: 600 } })
await page.goto('about:blank')
const files = ['after/01-chat-dark.png', 'after/01-chat-light.png', 'after/08-settings-light.png', 'after/03-flowbuilder-dark.png']
for (const f of files) {
  const b64 = readFileSync(join(here, f)).toString('base64')
  const res = await page.evaluate(async (data) => {
    const img = new Image()
    img.src = 'data:image/png;base64,' + data
    await img.decode()
    const c = document.createElement('canvas')
    c.width = img.width; c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = (x, y) => Array.from(ctx.getImageData(img.width * x, img.height * y, 1, 1).data).slice(0, 3)
    return {
      size: img.width + 'x' + img.height,
      center: px(0.5, 0.5),          // main panel bg
      topLeft: px(0.05, 0.5),        // sidebar rail
      bottomLeft: px(0.03, 0.965),   // account avatar zone
    }
  }, b64)
  console.log(f, JSON.stringify(res))
}
await browser.close()
