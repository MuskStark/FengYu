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
        NODE_ENV: 'test',
      },
    })
    const proc = app.process()
    proc.stdout?.on('data', (d) => lines.push(`[stdout] ${d}`))
    proc.stderr?.on('data', (d) => lines.push(`[stderr] ${d}`))
    app.on('window', (w) => lines.push(`[event] window opened url=${w.url()}`))

    try {
      const win = await app.firstWindow()
      lines.push(`[step] firstWindow ok url=${win.url()}`)
      await win.waitForLoadState('domcontentloaded', { timeout: 60_000 })
      lines.push('[step] domcontentloaded ok')

      // The preload injects window.fengyu with apiBase/token.
      const apiBase = await win.evaluate(() => (window as any).fengyu?.apiBase?.())
      lines.push(`[step] apiBase=${apiBase}`)
      expect(apiBase).toMatch(/^http:\/\/127\.0\.0\.1:\d+$/)

      // The backend is reachable at that base.
      const ok = await win.evaluate(async (base: string) => {
        const r = await fetch(`${base}/api/health`, { headers: { 'X-FengYu-Token': (window as any).fengyu.token() } })
        return r.status === 200
      }, apiBase)
      lines.push(`[step] health ok=${ok}`)
      expect(ok).toBe(true)
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
