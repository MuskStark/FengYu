// Locate WHERE the diff concentrates (grid cells) on tools-dark.
import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const here = dirname(fileURLToPath(import.meta.url))
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage()
await page.goto('about:blank')
const load = async f => {
  const b64 = readFileSync(join(here, f)).toString('base64')
  return page.evaluate(async data => {
    const img = new Image()
    img.src = 'data:image/png;base64,' + data
    await img.decode()
    const c = document.createElement('canvas')
    c.width = img.width; c.height = img.height
    c.getContext('2d').drawImage(img, 0, 0)
    return { d: c.getContext('2d').getImageData(0, 0, c.width, c.height).data, w: c.width, h: c.height }
  }, b64)
}
const a = await load('after/05-tools-light.png')
const b = await load('landed/05-tools-light.png')
const cols = 8, rows = 6
const grid = Array.from({ length: rows }, () => Array(cols).fill(0))
const counts = Array.from({ length: rows }, () => Array(cols).fill(0))
for (let y = 0; y < a.h; y += 3) {
  for (let x = 0; x < a.w; x += 3) {
    const i = (y * a.w + x) * 4
    const gc = Math.min(cols - 1, Math.floor((x / a.w) * cols))
    const gr = Math.min(rows - 1, Math.floor((y / a.h) * rows))
    counts[gr][gc]++
    if (Math.abs(a.d[i] - b.d[i]) > 24 || Math.abs(a.d[i + 1] - b.d[i + 1]) > 24 || Math.abs(a.d[i + 2] - b.d[i + 2]) > 24) grid[gr][gc]++
  }
}
for (let r = 0; r < rows; r++) console.log(grid[r].map((v, c) => (100 * v / counts[r][c]).toFixed(0).padStart(4)).join(' '))
await browser.close()
