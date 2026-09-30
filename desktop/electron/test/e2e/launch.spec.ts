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
        FENGYU_DEV_BACKEND: 'disabled',
        NODE_ENV: 'test',
      },
    })
    const proc = app.process()
    proc.stdout?.on('data', (d) => lines.push(`[stdout] ${d}`))
    proc.stderr?.on('data', (d) => lines.push(`[stderr] ${d}`))
    app.on('window', (w) => lines.push(`[event] window opened url=${w.url()}`))

    try {
      // The MAIN window is the first (and only) app window — it loads the SPA
      // (frontend-dist/index.html or the dev server) and has the fengyu preload
      // injected. Skip only the devtools window (opened when NODE_ENV!=production
      // on some platforms).
      const isAuxWindow = (url: string) => url.startsWith('devtools://')
      const first = await app.firstWindow()
      const win = isAuxWindow(first.url())
        ? await app.waitForEvent('window', { predicate: (c) => !isAuxWindow(c.url()) })
        : first
      lines.push(`[step] firstWindow ok url=${win.url()}`)
      await win.waitForLoadState('domcontentloaded', { timeout: 60_000 })
      lines.push('[step] domcontentloaded ok')

      // The preload injects window.fengyu before any page script, but the window
      // is created BEFORE the backend spawn (the in-app startup screen owns the
      // whole boot), so the preload's env snapshot of apiBase/token is EMPTY at
      // page load — the endpoint arrives via the endpoint:ready IPC once the
      // spawn resolves the port. Poll the bridge's getEndpoint() until it lands,
      // proving the handoff chain (spawn → endpoint push/pull → renderer).
      let bridge: { apiBase?: string; token?: string } | null = null
      for (let attempt = 0; attempt < 150 && !bridge?.apiBase; attempt++) {
        bridge = await win.evaluate(() =>
          (window as any).fengyu?.getEndpoint?.() ?? null)
        if (!bridge?.apiBase) await new Promise(resolve => setTimeout(resolve, 200))
      }
      lines.push(`[step] apiBase=${bridge?.apiBase}`)
      expect(bridge?.apiBase).toMatch(/^http:\/\/127\.0\.0\.1:\d+$/)
      // genToken() emits `zf-` + 32 random bytes as 64 hex chars (a single segment,
      // not two). Match the real shape so this stays in sync with util/token.ts.
      expect(bridge?.token).toMatch(/^zf-[0-9a-f]{64}$/)

      // Backend reachable at that base, with the token the shell generated. The
      // renderer's boot gate polls health in parallel; mirror it from Node until
      // the backend answers (the JVM boot can far outlast domcontentloaded).
      let healthStatus = 0
      for (let attempt = 0; attempt < 150 && healthStatus !== 200; attempt++) {
        try {
          const r = await fetch(`${bridge!.apiBase}/api/health`, {
            headers: { 'X-FengYu-Token': bridge!.token },
          })
          healthStatus = r.status
        } catch {
          // backend not listening yet — retry
        }
        if (healthStatus !== 200) await new Promise(resolve => setTimeout(resolve, 200))
      }
      lines.push(`[step] health status=${healthStatus}`)
      expect(healthStatus).toBe(200)

      // The app:// shell protocol (M-6) is registered in every mode — including this dev
      // launch — so exercising it from the MAIN process proves scheme registration and the
      // frontend-dist handler end-to-end, without needing a packaged build here.
      const appUrlStatus = await app.evaluate(async ({ net }) => {
        const resp = await net.fetch('app://shell/index.html')
        return resp.status
      })
      lines.push(`[step] app://shell/index.html status=${appUrlStatus}`)
      expect(appUrlStatus).toBe(200)
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
