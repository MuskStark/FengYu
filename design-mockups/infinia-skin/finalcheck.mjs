import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const here = dirname(fileURLToPath(import.meta.url))
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage()
await page.goto('about:blank')
const files = readdirSync(join(here, 'after')).filter(f => f.endsWith('.png') && !f.includes('-r.png')).sort()
const bad = []
for (const f of files) {
  const b64 = readFileSync(join(here, 'after', f)).toString('base64')
  const res = await page.evaluate(async (data) => {
    const img = new Image()
    img.src = 'data:image/png;base64,' + data
    await img.decode()
    const c = document.createElement('canvas')
    c.width = img.width; c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const d = ctx.getImageData(0, 0, c.width, c.height).data
    let white = 0, gold = 0, dark = 0, total = c.width * c.height
    for (let i = 0; i < d.length; i += 16) { // sample every 4th px
      const [r, g, b] = [d[i], d[i + 1], d[i + 2]]
      if (r > 250 && g > 250 && b > 250) white++
      if (r > 195 && g > 140 && g < 215 && b < 130 && r - b > 100) gold++
      if (r < 40 && g < 40 && b < 45) dark++
    }
    return { white, gold, dark, total: total / 4 }
  }, b64)
  const isBlank = res.white / res.total > 0.97
  const themed = f.includes('dark') ? res.dark > res.total * 0.3 : true
  if (isBlank || !themed) bad.push(f)
  console.log(f.padEnd(28), isBlank ? 'BLANK!' : 'ok ', 'gold:' + res.gold, f.includes('dark') ? 'darkpx:' + res.dark : '')
}
console.log(bad.length ? 'BAD: ' + bad.join(', ') : 'ALL PASS')
await browser.close()
