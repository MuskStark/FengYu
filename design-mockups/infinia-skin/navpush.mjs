import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(1800)
const res = await page.evaluate(() => {
  const rootEl = document.getElementById('root')
  const ckey = Object.keys(rootEl).find(k => k.startsWith('__reactContainer$'))
  const providers = []
  const visit = (node, depth) => {
    if (!node || depth > 80 || providers.length > 4) return
    if (node.tag === 11 && node.elementType?._context?.displayName) {
      const v = node.memoizedProps?.value
      providers.push({ ctx: node.elementType._context.displayName, hasPush: !!(v && typeof v.push === 'function'), pushStr: v && typeof v.push === 'function' ? String(v.push).slice(0, 60).replace(/\s+/g, ' ') : null })
    }
    visit(node.child, depth + 1)
    visit(node.sibling, depth + 1)
  }
  visit(rootEl[ckey], 0)
  // find the navigator with push (Navigation context)
  let navigatorObj = null
  const visit2 = (node, depth) => {
    if (!node || navigatorObj || depth > 80) return
    if (node.tag === 11) {
      const v = node.memoizedProps?.value
      if (v && typeof v.push === 'function') navigatorObj = v
    }
    visit2(node.child, depth + 1)
    visit2(node.sibling, depth + 1)
  }
  visit2(rootEl[ckey], 0)
  const out = { providers }
  if (navigatorObj) {
    out.calledPush = true
    const url0 = location.pathname
    try { navigatorObj.push('/tools', { test: 1 }) } catch (e) { out.pushErr = String(e).slice(0, 120) }
  } else out.calledPush = false
  return out
})
console.log(JSON.stringify(res))
await page.waitForTimeout(600)
console.log('url after direct navigator.push:', page.url())
await browser.close()
