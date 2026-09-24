import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.goto('http://localhost:5175/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(2500)
const res = await page.evaluate(() => {
  const btns = [...document.querySelectorAll('.sidebar-primary-nav .cx-nav-item')]
  const btn = btns[0]
  if (!btn) return { err: 'no nav buttons', bodyHead: document.body.innerText?.slice(0, 80) }
  let f = btn[Object.keys(btn).find(k => k.startsWith('__reactFiber$'))]
  const chain = []
  while (f && chain.length < 10) {
    chain.push(`tag=${f.tag} type=${typeof f.type === 'string' ? f.type : (f.type?.name || 'anon')} hasMS=${!!f.memoizedState}`)
    f = f.return
  }
  return { chain }
})
console.log(JSON.stringify(res, null, 1))
await browser.close()
