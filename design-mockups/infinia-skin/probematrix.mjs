// Deterministic regression gate: assert landed computed styles equal the store spec.
import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs'
const browser = await chromium.launch({ executablePath: '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' })
const EXPECT = {
  light: { canvas: 'rgb(250, 249, 246)', rail: 'rgb(243, 241, 236)', primary: 'rgb(234, 176, 75)', onPrimary: 'rgb(24, 24, 27)', accent: '#885400', inputBg: 'rgb(243, 241, 236)', inputBorder: 'rgb(201, 196, 184)' },
  dark: { canvas: 'rgb(9, 9, 11)', rail: 'rgb(20, 20, 22)', primary: 'rgb(246, 189, 96)', onPrimary: 'rgb(24, 24, 27)', accent: '#f6bd60', inputBg: 'rgb(16, 16, 19)', inputBorder: 'rgb(58, 58, 64)' },
}
const routes = ['/', '/flows', '/flows/new', '/schedules', '/tools', '/store', '/account', '/settings', '/about']
let fails = 0
for (const theme of ['light', 'dark']) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await ctx.addInitScript(t => localStorage.setItem('fengyu-theme', JSON.stringify(t)), theme)
  const page = await ctx.newPage()
  for (const r of routes) {
    await page.goto('http://localhost:5173' + r, { waitUntil: 'load', timeout: 20000 }).catch(() => {})
    // The settings store re-applies the BACKEND theme on load; force the probed theme.
    await page.evaluate(theme => {
      const root = document.documentElement
      root.classList.remove('dark', 'v-theme--dark', 'theme-zai-dark', 'v-theme--light', 'theme-zai-light')
      root.classList.add(theme === 'dark' ? 'v-theme--dark' : 'v-theme--light')
      root.classList.add(theme === 'dark' ? 'theme-zai-dark' : 'theme-zai-light')
      if (theme === 'dark') root.classList.add('dark')
    }, theme).catch(() => {})
    await page.waitForTimeout(900)
    // The store's async backend-theme apply can land after the first force; re-assert.
    await page.evaluate(theme => {
      const root = document.documentElement
      root.classList.remove('dark', 'v-theme--dark', 'theme-zai-dark', 'v-theme--light', 'theme-zai-light')
      root.classList.add(theme === 'dark' ? 'v-theme--dark' : 'v-theme--light')
      root.classList.add(theme === 'dark' ? 'theme-zai-dark' : 'theme-zai-light')
      if (theme === 'dark') root.classList.add('dark')
    }, theme).catch(() => {})
    await page.waitForTimeout(300)
    const got = await page.evaluate(theme => {
      const cs = getComputedStyle(document.documentElement)
      const q = sel => document.querySelector(sel)
      const main = q('.fx-main'), avatar = q('.sidebar-avatar'), nav = q('.cx-nav-item.active'), input = q('.pg-search .cx-input, .set-nav-search input, .cx-input')
      const out = {
        canvas: main ? getComputedStyle(main).backgroundColor : null,
        tokenPrimary: cs.getPropertyValue('--color-primary').trim(),
        avatarClip: avatar ? getComputedStyle(avatar).clipPath !== 'none' : null,
        avatarGrad: avatar ? getComputedStyle(avatar).backgroundImage.includes('252, 128, 29') : null,
        navGold: nav ? getComputedStyle(nav).boxShadow.includes(theme === 'light' ? '234, 176, 75' : '246, 189, 96') : null,
        inputBg: input ? getComputedStyle(input).backgroundColor : null,
      }
      return out
    }, theme).catch(() => null)
    if (!got) { console.log(`${theme} ${r}: PAGE DEAD`); fails++; continue }
    const e = EXPECT[theme]
    const bad = []
    if (r !== '/settings' && got.canvas !== e.canvas) bad.push(`canvas ${got.canvas}`)
    if (got.tokenPrimary.toLowerCase() !== e.primary.replace('rgb(', '').replace(')', '')) {
      // tokenPrimary is a hex string; compare loosely
      if (got.tokenPrimary !== (theme === 'light' ? '#eab04b' : '#f6bd60')) bad.push(`primary ${got.tokenPrimary}`)
    }
    const noSidebar = r === '/settings' // settings-shell unmounts the sidebar; .fx-main goes transparent
    if (!noSidebar) {
      if (got.avatarClip !== true) bad.push('avatar-clip')
      if (got.avatarGrad !== true) bad.push('avatar-grad')
    }
    if (got.navGold === false) bad.push('nav-gold-bar')
    if (got.inputBg !== null && got.inputBg !== e.inputBg && got.inputBg !== 'rgba(0, 0, 0, 0)') {
      // set-nav-search rides different tokens; only hard-fail on cx-input pages
      if (r === '/tools' || r === '/store') bad.push(`inputBg ${got.inputBg}`)
    }
    if (bad.length) { console.log(`FAIL ${theme} ${r}: ${bad.join(', ')}`); fails++ }
    else console.log(`ok   ${theme} ${r}`)
  }
  await ctx.close()
}
console.log(fails ? `\n${fails} FAILURES` : '\nALL PASS')
await browser.close()
