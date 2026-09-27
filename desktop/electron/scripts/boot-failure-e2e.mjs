/**
 * Boot-failure E2E (manual, not part of the CI Playwright suite): launches the real
 * shell against a fake JAR that prints the FENGYU_PORT handshake and then exits(1),
 * proving the P2 chain end-to-end:
 *
 *   1. the in-app failure screen mounts (not a native dialog),
 *   2. its visibility ACK keeps the shell alive past the 15s native fallback,
 *   3. Retry respawns and fails fast again (exit race, not the 120s deadline),
 *   4. the Quit button exits the app cleanly.
 *
 * The fake JAR must expose the real main class (spawn runs
 * `java -cp <jar> fan.summer.fengyu.HeadlessLauncher`, NOT `java -jar`). Rebuild:
 *
 *   mkdir -p /tmp/fengyu-fake-jar/fan/summer/fengyu && cd /tmp/fengyu-fake-jar
 *   cat > fan/summer/fengyu/HeadlessLauncher.java <<'J'
 *   package fan.summer.fengyu;
 *   public class HeadlessLauncher {
 *     public static void main(String[] a) throws Exception {
 *       System.out.println("FENGYU_PORT=24999"); System.out.flush();
 *       Thread.sleep(400); System.exit(1);
 *     }
 *   }
 *   J
 *   javac fan/summer/fengyu/HeadlessLauncher.java
 *   jar cfe fake-backend.jar fan.summer.fengyu.HeadlessLauncher fan/summer/fengyu/HeadlessLauncher.class
 *
 * Run from desktop/electron: node scripts/boot-failure-e2e.mjs
 */
import { _electron as electron } from 'playwright'
import { join } from 'node:path'

const FAKE_JAR = '/tmp/fengyu-fake-jar/fake-backend.jar'
const root = process.cwd()
const fail = (msg) => {
  console.error(`FAIL: ${msg}`)
  process.exitCode = 1
}

const app = await electron.launch({
  args: [join(root, 'dist/main.js')],
  env: {
    ...process.env,
    FENGYU_JAR: FAKE_JAR,
    FENGYU_DEV_BACKEND: 'disabled',
    NODE_ENV: 'test',
  },
})
const proc = app.process()
proc.stdout?.on('data', (d) => console.log(`[stdout] ${d}`))
proc.stderr?.on('data', (d) => console.log(`[stderr] ${d}`))
let exited = false
proc.once('exit', (code) => {
  exited = true
  console.log(`[proc] exited with code ${code}`)
})

const isAuxWindow = (url) => url.startsWith('devtools://') || url.includes('splash.html')
const first = await app.firstWindow({ timeout: 90_000 })
const win = isAuxWindow(first.url())
  ? await app.waitForEvent('window', { predicate: (c) => !isAuxWindow(c.url()) })
  : first
await win.waitForLoadState('domcontentloaded', { timeout: 60_000 })

// 1. Failure screen mounts after spawn → port line → JVM exit(1) → exit race.
await win.waitForSelector('.fx-bootfail', { timeout: 60_000 })
// Let the HTML boot shell finish its 0.16s cross-fade + removal (~600ms) so the
// screenshot captures the settled failure screen, not a mid-fade ghost frame.
await win.waitForSelector('#boot-loading', { state: 'detached', timeout: 10_000 })
const title = await win.textContent('.fx-bootfail__title')
console.log(`[1] failure screen mounted: "${title}"`)
await win.screenshot({ path: join(root, 'test-results', 'boot-failure-screen.png') })
console.log('[1b] screenshot saved to test-results/boot-failure-screen.png')
const detail = await win.textContent('.fx-bootfail__detail').catch(() => '')
if (!/code 1/.test(detail ?? '')) fail(`expected exit-code detail, got: ${detail}`)

// 2. ACK must keep the shell alive past the 15s native fallback window.
await win.waitForTimeout(17_000)
if (exited) fail('app quit despite the failure screen ACKing visibility')
else console.log('[2] ACK disarmed the native fallback — app alive after 17s')

// 3. Retry: swaps to the startup screen, respawns, fails fast again.
await win.click('.fx-bootfail__actions .cx-btn--primary')
await win.waitForSelector('.fx-bootfail', { state: 'hidden', timeout: 10_000 })
console.log('[3a] retry swapped back to the startup screen')
await win.waitForSelector('.fx-bootfail', { timeout: 20_000 })
console.log('[3b] retry failed fast again (exit race, not the 120s deadline)')

// 4. Quit button exits the app.
await win.click('.fx-bootfail__actions .cx-btn--text')
for (let i = 0; i < 100 && !exited; i++) {
  await new Promise((r) => setTimeout(r, 200))
}
if (!exited) {
  fail('app did not exit after the Quit button')
  await app.close().catch(() => {})
} else {
  console.log('[4] Quit button exited the app cleanly')
  console.log('PASS: boot-failure E2E complete')
}
