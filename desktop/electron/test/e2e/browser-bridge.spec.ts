import { test, expect, _electron as electron } from '@playwright/test'
import { join } from 'node:path'
import { existsSync } from 'node:fs'

const JAR = process.env.FENGYU_JAR ?? ''
const haveJar = !!JAR && existsSync(JAR)

/**
 * End-to-end test of the browser-automation bridge chain that no unit test covers:
 *   Electron main (startBrowserBridge)  ──HTTP──▶  BrowserSession (real BrowserWindow)
 *   ──webContents + CDP──▶  screenshot file + a11y tree.
 *
 * Unit tests (browser-handlers.test.ts, bridge.test.ts) mock electron/webContents; this
 * one drives the REAL Electron app with a REAL BrowserWindow, exercised through the same
 * node:http loopback endpoint the backend's BrowserTool calls into.
 *
 * NOT executed in CI without a JAR + display + network — see the skip guard below.
 */
test.describe('browser bridge chain', () => {
  test.skip(!haveJar, 'FENGYU_JAR not set or jar missing — build one with `mvn -pl FengYu -am package -DskipTests`')

  test('navigate → screenshot → close over the loopback bridge', async () => {
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
      // The bridge port + token are set on the MAIN process's process.env by main.ts
      // (before the JVM spawn so the backend inherits them). app.evaluate runs the
      // callback in the main Electron process, where `process` is a Node global — so
      // we read it directly rather than via an IPC round-trip. The electron module
      // exports do NOT include `process`, so we reference the global (not destructured
      // off the electron param).
      const bridgeInfo = await app.evaluate(async () => ({
        port: process.env.FENGYU_BROWSER_BRIDGE_PORT,
        token: process.env.FENGYU_BROWSER_BRIDGE_TOKEN,
      }))
      lines.push(`[step] bridge port=${bridgeInfo.port}`)
      expect(bridgeInfo.port).toMatch(/^\d+$/)
      expect(bridgeInfo.token).toMatch(/^zf-[0-9a-f]{64}$/)

      const base = `http://127.0.0.1:${bridgeInfo.port}`
      const headers = { 'X-Browser-Token': bridgeInfo.token as string, 'Content-Type': 'application/json' }

      // 1. Wrong token → 401 (auth guard enforced by bridge.ts before any handler runs).
      const bad = await fetch(`${base}/invoke`, {
        method: 'POST',
        headers: { 'X-Browser-Token': 'wrong-token', 'Content-Type': 'application/json' },
        body: JSON.stringify({ method: 'browser_navigate', params: { url: 'https://example.com' } }),
      })
      lines.push(`[step] bad-token status=${bad.status}`)
      expect(bad.status).toBe(401)

      // 2. Navigate to a real page. handleBrowserOp returns {success, summary, url, title}.
      const nav = await fetch(`${base}/invoke`, {
        method: 'POST',
        headers,
        body: JSON.stringify({ method: 'browser_navigate', params: { url: 'https://example.com' } }),
      })
      expect(nav.status).toBe(200)
      const navJson = (await nav.json()) as { success: boolean; summary?: string; url?: string; title?: string }
      lines.push(`[step] navigate success=${navJson.success} title=${navJson.title}`)
      expect(navJson.success).toBe(true)
      expect(navJson).toHaveProperty('summary')
      expect(navJson).toHaveProperty('url')
      expect(navJson).toHaveProperty('title')

      // 3. Screenshot — exercises capturePage + writeFileSync + CDP a11y capture.
      const shot = await fetch(`${base}/invoke`, {
        method: 'POST',
        headers,
        body: JSON.stringify({ method: 'browser_screenshot', params: {} }),
      })
      expect(shot.status).toBe(200)
      const shotJson = (await shot.json()) as {
        success: boolean
        imagePath?: string
        width?: number
        height?: number
        a11yTree?: string
      }
      lines.push(`[step] screenshot success=${shotJson.success} imagePath=${shotJson.imagePath}`)
      expect(shotJson.success).toBe(true)
      expect(shotJson).toHaveProperty('imagePath')
      expect(shotJson).toHaveProperty('width')
      expect(shotJson).toHaveProperty('height')
      expect(shotJson).toHaveProperty('a11yTree')
      expect(typeof shotJson.imagePath).toBe('string')
      // The screenshot PNG must actually exist on disk.
      expect(existsSync(shotJson.imagePath as string)).toBe(true)

      // 4. Close — destroys the BrowserWindow created by navigate.
      const close = await fetch(`${base}/invoke`, {
        method: 'POST',
        headers,
        body: JSON.stringify({ method: 'browser_close', params: {} }),
      })
      expect(close.status).toBe(200)
      const closeJson = (await close.json()) as { success: boolean; closed?: boolean }
      lines.push(`[step] close success=${closeJson.success} closed=${closeJson.closed}`)
      expect(closeJson.success).toBe(true)
      expect(closeJson.closed).toBe(true)
    } catch (err) {
      // Surface captured backend/main logs on failure — otherwise Playwright only shows
      // the bare timeout with no clue where the chain broke.
      test.info().annotations.push({ type: 'capture', description: lines.join('\n') })
      // eslint-disable-next-line no-console
      console.log('\n===== CAPTURED BACKEND/MAIN LOGS =====\n' + lines.join('') + '\n======================================\n')
      throw err
    } finally {
      await app.close().catch(() => {})
    }
  })
})
