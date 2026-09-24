// Pixel-diff landed/ vs after/ (approved mockups) — same viewport, same @2x.
import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const here = dirname(fileURLToPath(import.meta.url))
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage()
await page.goto('about:blank')
const files = readdirSync(join(here, 'landed')).filter(f => f.endsWith('.png')).sort()
for (const f of files) {
  const load = async (dir) => {
    const b64 = readFileSync(join(here, dir, f)).toString('base64')
    return page.evaluate(async data => {
      const img = new Image()
      img.src = 'data:image/png;base64,' + data
      await img.decode()
      const c = document.createElement('canvas')
      c.width = img.width; c.height = img.height
      c.getContext('2d').drawImage(img, 0, 0)
      return c.getContext('2d').getImageData(0, 0, c.width, c.height).data
    }, b64)
  }
  const [a, b] = await Promise.all([load('after'), load('landed')])
  if (a.length !== b.length) { console.log(f, 'SIZE MISMATCH'); continue }
  let diff = 0
  const step = 8 // sample every 2nd pixel
  let total = 0
  for (let i = 0; i < a.length; i += 4 * step) {
    total++
    if (Math.abs(a[i] - b[i]) > 24 || Math.abs(a[i + 1] - b[i + 1]) > 24 || Math.abs(a[i + 2] - b[i + 2]) > 24) diff++
  }
  console.log(f.padEnd(28), 'diff:', (100 * diff / total).toFixed(2) + '%')
}
await browser.close()
