import { test, expect, _electron as electron } from '@playwright/test'
import { join } from 'node:path'
import { existsSync } from 'node:fs'

const JAR = process.env.FENGYU_JAR ?? ''
const haveJar = !!JAR && existsSync(JAR)
// This test opens a REAL BrowserWindow, navigates it to a live URL (example.com), and
// captures a screenshot + CDP a11y tree — so beyond a JAR it needs a display and outbound
// network. That is fragile in CI (xvfb, sandboxed runners, flaky e2gress) and a failure
// here also tears down launch.spec.ts via Playwright's worker teardown timeout. It is
// therefore opt-in: set FENGYU_E2E_BROWSER_BRIDGE=1 to run it (e.g. locally or on a
// dedicated self-hosted runner). The release workflow does NOT set it, so the gating
// desktop E2E there runs only the stable launch.spec.ts.
const enabled = process.env.FENGYU_E2E_BROWSER_BRIDGE === '1'

/**
 * End-to-end test of the browser-automation bridge chain that no unit test covers:
 *   Electron main (startBrowserBridge)  ──HTTP──▶  BrowserSession (real BrowserWindow)
 *   ──webContents + CDP──▶  screenshot file + a11y tree.
 *
 * Unit tests (browser-handlers.test.ts, bridge.test.ts) mock electron/webContents; this
 * one drives the REAL Electron app with a REAL BrowserWindow, exercised through the same
 * node:http loopback endpoint the backend's BrowserTool calls into.
 *
 * Opt-in (FENGYU_E2E_BROWSER_BRIDGE=1): needs a JAR + display + network, so it is skipped
 * in the release workflow by default to keep the desktop E2E gating stable.
 */
test.describe('browser bridge chain', () => {
  test.skip(!haveJar, 'FENGYU_JAR not set or jar missing — build one with `mvn -pl FengYu -am package -DskipTests`')
  test.skip(!enabled, 'browser-bridge E2E is opt-in (set FENGYU_E2E_BROWSER_BRIDGE=1); needs a display + network')

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
      // Wait for the MAIN window (skip splash + devtools) to reach domcontentloaded.
      // This guarantees main.ts has finished its async init — including
      // startBrowserBridge(), which sets FENGYU_BROWSER_BRIDGE_PORT/TOKEN on the main
      // process's process.env BEFORE the JVM spawn. Reading those vars earlier (e.g.
      // immediately after launch) races main init and yields undefined / an
      // "Execution context was destroyed" error, because app.evaluate runs against an
      // execution context that splash→main navigation tears down. Mirrors launch.spec.
      const isAuxWindow = (url: string) =>
        url.startsWith('devtools://') || url.endsWith('splash.html') || url.includes('splash.html')
      const first = await app.firstWindow()
      const win = isAuxWindow(first.url())
        ? await app.waitForEvent('window', { predicate: (c) => !isAuxWindow(c.url()) })
        : first
      await win.waitForLoadState('domcontentloaded', { timeout: 60_000 })
      lines.push(`[step] main window ready url=${win.url()}`)

      // The bridge port + token are set on the MAIN process's process.env by main.ts
      // (before the JVM spawn so the backend inherits them). app.evaluate runs the
      // callback in the main Electron process, where `process` is a Node global — so
      // we read it directly rather than via an IPC round-trip. The electron module
      // exports do NOT include `process`, so we reference the global (not destructured
      // off the electron param).
      const bridgeInfo = await app.evaluate(() => ({
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

      // 3. Full-page screenshot — exercises CDP captureBeyondViewport + writeFileSync +
      //    CDP a11y capture against a real BrowserWindow.
      const shot = await fetch(`${base}/invoke`, {
        method: 'POST',
        headers,
        body: JSON.stringify({ method: 'browser_screenshot', params: { fullPage: true } }),
      })
      expect(shot.status).toBe(200)
      const shotJson = (await shot.json()) as {
        success: boolean
        imagePath?: string
        imageBase64?: string
        mimeType?: string
        width?: number
        height?: number
        a11yTree?: string
      }
      lines.push(`[step] screenshot success=${shotJson.success} imagePath=${shotJson.imagePath}`)
      expect(shotJson.success).toBe(true)
      expect(shotJson).toHaveProperty('imagePath')
      expect(shotJson.mimeType).toBe('image/png')
      expect(shotJson.imageBase64).toMatch(/^iVBOR/)
      expect(shotJson).toHaveProperty('width')
      expect(shotJson).toHaveProperty('height')
      expect(shotJson).toHaveProperty('a11yTree')
      expect(typeof shotJson.imagePath).toBe('string')
      expect(shotJson.width).toBeGreaterThan(0)
      expect(shotJson.height).toBeGreaterThan(0)
      // The screenshot PNG must actually exist on disk.
      expect(existsSync(shotJson.imagePath as string)).toBe(true)

      // 4. Inject a login form with a controlled-component mirror (the span's textContent
      //    updates on a real 'input' event, mimicking how React/Vue controlled inputs
      //    observe value changes). Direct el.value= assignment would NOT fire the handler
      //    that updates the mirror — only a real input event (from CDP Input.insertText)
      //    does. This is the regression guard for the original "type doesn't fill" bug.
      const inject = await fetch(`${base}/invoke`, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          method: 'browser_eval_js',
          params: {
            script: `(() => {
              document.body.innerHTML = `
              + '`'
              + `<form id="login"><input id="user" name="user" type="text" placeholder="Username"/>`
              + `<input id="pwd" name="pwd" type="password" placeholder="Password"/>`
              + `<button id="go" type="submit">Go</button></form>`
              + `<span id="mirror"></span>`
              + '`'
              + `;
              document.getElementById('user').addEventListener('input', (e) => {
                document.getElementById('mirror').textContent = 'user=' + e.target.value;
              });
              document.getElementById('login').addEventListener('submit', (event) => {
                event.preventDefault();
                document.getElementById('mirror').textContent = 'submitted user=' + document.getElementById('user').value;
              });
              return 'ok';
            })()`,
          },
        }),
      })
      const injectJson = (await inject.json()) as { success: boolean; value?: string }
      lines.push(`[step] inject form success=${injectJson.success} value=${injectJson.value}`)
      expect(injectJson.success).toBe(true)

      // 5. Codex-style DOM snapshot → type by semantic ref on the username input, then
      //    assert the controlled mirror updated. No CSS discovery/eval loop is needed.
      const snapshot = await fetch(`${base}/invoke`, {
        method: 'POST', headers,
        body: JSON.stringify({ method: 'browser_snapshot', params: {} }),
      })
      const snapshotJson = (await snapshot.json()) as { success: boolean; snapshot?: string; count?: number }
      lines.push(`[step] snapshot success=${snapshotJson.success} count=${snapshotJson.count}`)
      expect(snapshotJson.success).toBe(true)
      expect(snapshotJson.snapshot).toContain('textbox "Username"')
      expect(snapshotJson.snapshot).toContain('button "Go"')
      const userRef = snapshotJson.snapshot?.match(/^\[([^\]]+)\] textbox "Username"/m)?.[1]
      expect(userRef).toMatch(/^snap_/)

      // The mirror only reflects the typed value if a real input event fired, proving
      // CDP Input.insertText (not el.value assignment) drove the change.
      const typeUser = await fetch(`${base}/invoke`, {
        method: 'POST',
        headers,
        body: JSON.stringify({ method: 'browser_type', params: { ref: userRef, text: 'alice' } }),
      })
      const typeUserJson = (await typeUser.json()) as { success: boolean; filled?: boolean }
      lines.push(`[step] type user success=${typeUserJson.success}`)
      expect(typeUserJson.success).toBe(true)

      const mirror = await fetch(`${base}/invoke`, {
        method: 'POST',
        headers,
        body: JSON.stringify({ method: 'browser_eval_js', params: { script: `document.getElementById('mirror').textContent` } }),
      })
      const mirrorJson = (await mirror.json()) as { success: boolean; value?: string }
      lines.push(`[step] mirror value=${mirrorJson.value}`)
      expect(mirrorJson.value).toBe('user=alice')

      // 6. Submit through a real Enter keypress on the same snapshot ref — the normal search
      //    box path used by Codex when a page has no discoverable submit button.
      const pressEnter = await fetch(`${base}/invoke`, {
        method: 'POST',
        headers,
        body: JSON.stringify({ method: 'browser_press', params: { ref: userRef, key: 'Enter' } }),
      })
      const pressEnterJson = (await pressEnter.json()) as { success: boolean; pressed?: boolean }
      lines.push(`[step] press Enter success=${pressEnterJson.success}`)
      expect(pressEnterJson.success).toBe(true)

      const submitted = await fetch(`${base}/invoke`, {
        method: 'POST',
        headers,
        body: JSON.stringify({ method: 'browser_eval_js', params: { script: `document.getElementById('mirror').textContent` } }),
      })
      const submittedJson = (await submitted.json()) as { success: boolean; value?: string }
      lines.push(`[step] submit mirror value=${submittedJson.value}`)
      expect(submittedJson.value).toBe('submitted user=alice')

      // 7. Close — destroys the BrowserWindow created by navigate.
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
