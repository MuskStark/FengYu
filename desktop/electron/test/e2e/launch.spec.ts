import { test, expect, _electron as electron } from '@playwright/test'
import { join } from 'node:path'
import { existsSync } from 'node:fs'

const JAR = process.env.FENGYU_JAR ?? ''
const haveJar = !!JAR && existsSync(JAR)

test.describe('desktop launch', () => {
  test.skip(!haveJar, 'FENGYU_JAR not set or jar missing — build one with `mvn -pl FengYu -am package -DskipTests`')

  test('window opens and reaches the backend', async () => {
    const lines: string[] = []
    const app = await electron.launch({
      args: [join(__dirname, '../../dist/main.js')],
      env: {
        ...process.env,
        FENGYU_JAR: JAR,
        FENGYU_PLUGINS: process.env.FENGYU_PLUGINS ?? '',
        FENGYU_DEV_BACKEND: 'disabled',
        NODE_ENV: 'test',
      },
    })
    const proc = app.process()
    proc.stdout?.on('data', (d) => lines.push(`[stdout] ${d}`))
    proc.stderr?.on('data', (d) => lines.push(`[stderr] ${d}`))
    app.on('window', (w) => lines.push(`[event] window opened url=${w.url()}`))

    try {
      // The splash window opens first (it loads resources/splash.html and exposes
      // window.splash, NOT window.fengyu). We must wait for the MAIN window — the
      // one that loads the SPA (frontend-dist/index.html or the dev server) and has
      // the fengyu preload injected. Skip both the devtools window (opened when
      // NODE_ENV!=production on some platforms) and the splash window.
      const isAuxWindow = (url: string) =>
        url.startsWith('devtools://') || url.endsWith('splash.html') || url.includes('splash.html')
      const first = await app.firstWindow()
      const win = isAuxWindow(first.url())
        ? await app.waitForEvent('window', { predicate: (c) => !isAuxWindow(c.url()) })
        : first
      lines.push(`[step] firstWindow ok url=${win.url()}`)
      await win.waitForLoadState('domcontentloaded', { timeout: 60_000 })
      lines.push('[step] domcontentloaded ok')

      // The preload injects window.fengyu with apiBase/token. The preload runs
      // before any page script and is URL-independent, so it's present even when
      // the dev Vite server isn't running (the window shows a connection-error
      // page in that case). We read apiBase/token FROM the renderer to prove the
      // preload bridge works, then verify the backend is reachable via a Node-side
      // fetch using that token — proving the full chain (shell spawns backend →
      // preload exposes credentials → backend accepts them) without depending on
      // the renderer page having loaded the SPA.
      const bridge = await win.evaluate(() => ({
        apiBase: (window as any).fengyu?.apiBase?.(),
        token: (window as any).fengyu?.token?.(),
      }))
      lines.push(`[step] apiBase=${bridge.apiBase}`)
      expect(bridge.apiBase).toMatch(/^http:\/\/127\.0\.0\.1:\d+$/)
      expect(bridge.token).toMatch(/^zf-[0-9a-f]+-[0-9a-f]+$/)

      // Backend reachable at that base, with the token the shell generated.
      const r = await fetch(`${bridge.apiBase}/api/health`, {
        headers: { 'X-FengYu-Token': bridge.token },
      })
      lines.push(`[step] health status=${r.status}`)
      expect(r.status).toBe(200)
    } catch (err) {
      // Surface captured backend/main logs on failure — otherwise Playwright only
      // shows the bare timeout with no clue where the boot stalled.
      test.info().annotations.push({ type: 'capture', description: lines.join('\n') })
      // eslint-disable-next-line no-console
      console.log('\n===== CAPTURED BACKEND/MAIN LOGS =====\n' + lines.join('') + '\n======================================\n')
      throw err
    } finally {
      await app.close().catch(() => {})
    }
  })
})
