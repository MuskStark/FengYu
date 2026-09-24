import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('console', m => console.log('[pg]', m.type(), m.text().slice(0, 140)))
await page.goto('http://localhost:5173/', { waitUntil: 'load', timeout: 15000 }).catch(() => {})
await page.waitForTimeout(1800)
const res = await page.evaluate(async () => {
  const rootEl = document.getElementById('root')
  const fkey = Object.keys(rootEl).find(k => k.startsWith('__reactContainer$'))
  let root = rootEl[fkey]
  // walk the fiber tree collecting provider values that look like an RR router
  const found = []
  const visit = (node, depth) => {
    if (!node || depth > 60) return
    const props = node.memoizedProps
    const val = props && props.value
    if (val && typeof val === 'object' && typeof val.navigate === 'function' && val.state) {
      found.push({ depth, navStr: String(val.navigate).slice(0, 60), loc: val.state?.location?.pathname, initState: val.state?.initialized, navState: val.state?.navigation?.state })
    }
    visit(node.child, depth + 1)
    visit(node.sibling, depth + 1)
  }
  visit(root, 0)
  if (!found.length) return { routers: 0 }
  const r = found[0]
  // find the actual router object to call navigate on: search again keeping the value
  let routerObj = null
  const visit2 = (node, depth) => {
    if (!node || routerObj || depth > 60) return
    const props = node.memoizedProps
    const val = props && props.value
    if (val && typeof val === 'object' && typeof val.navigate === 'function' && val.state) routerObj = val
    visit2(node.child, depth + 1)
    visit2(node.sibling, depth + 1)
  }
  visit2(root, 0)
  const before = location.pathname
  try { await routerObj.navigate('/tools') } catch (e) { return { routers: found.length, err: String(e).slice(0, 150) } }
  await new Promise(r => setTimeout(r, 800))
  return { routers: found.length, info: r, before, after: location.pathname }
})
console.log(JSON.stringify(res, null, 1))
await browser.close()
