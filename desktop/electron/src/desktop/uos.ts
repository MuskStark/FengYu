/**
 * UOS (统信) no-sandbox launch policy.
 *
 * Non-root UOS systems forbid starting ANY OS-level sandbox: unprivileged user namespaces are
 * disabled by the security baseline and SUID helpers (chrome-sandbox) are blocked, so Chromium's
 * sandbox cannot — and must not — initialize; a normal launch aborts with the "SUID sandbox
 * helper … not configured correctly" fatal.
 *
 * The PRIMARY mechanism is the launch entry, not this module: `electron-builder.uos.yml` sets
 * `linux.executableArgs: [--no-sandbox]`, so every generated launch entry (the deb's
 * `/usr/share/applications/infinia-uos.desktop` menu shortcut, the AppImage's embedded desktop
 * file) starts Electron with the switch on the REAL process argv. Chromium on UOS reads sandbox
 * decisions from the process command line before a JS-added switch reliably applies, so the
 * menu shortcut carrying the argument — overwriting the arg-less entry from earlier builds,
 * with the deb postinst refreshing the desktop database — is what makes menu launches work.
 *
 * This module's adaptations apply at main-process startup, covering launches that bypass a
 * desktop entry (direct terminal / double-click) and the cwd problem no launch entry can fix:
 *
 *   1. `app.commandLine.appendSwitch('no-sandbox')` — an in-process FALLBACK for the sandbox
 *      (harmless when the argv already carries --no-sandbox). It must be called before app
 *      ready. The Electron-level renderer hardening is unaffected: `webPreferences.sandbox:
 *      true` still strips Node.js from renderers, and contextIsolation / the iframe `sandbox`
 *      attribute are orthogonal to the Chromium sandbox.
 *   2. `process.chdir(home)` — menu-launched apps get cwd `/`, which is unwritable for
 *      non-root; everything under runtimeRoot() (`<cwd>/.fengyu`: logs, config, backend cwd)
 *      would die on the logger's unprotected mkdirSync. Re-anchoring to the user's home makes
 *      the whole runtime tree land in `~/.fengyu` without touching the shared path logic.
 *
 * Only the UOS artifact opts in; regular Linux/macOS/Windows builds are untouched, and a dev
 * run (never packaged) can never trip this.
 */
import { app } from 'electron'
import { readBakedPackageMetadata } from '../updater/auto-updater'

/** Pure decision (unit-testable without a real packaged app): is this the UOS variant on Linux? */
export function isUosBuild(
  metadata: Record<string, unknown> = readBakedPackageMetadata(),
  platform: NodeJS.Platform = process.platform,
): boolean {
  if (platform !== 'linux') return false
  const value = (metadata?.fengyu as Record<string, unknown> | undefined)?.uos
  return value === true
}

/**
 * Apply the UOS launch adaptations. MUST run before `app.whenReady` (for appendSwitch) and
 * before the logger initializes (for the chdir). Returns true when the policy was applied.
 */
export function applyUosLaunchPolicy(): boolean {
  if (!app.isPackaged) return false
  if (!isUosBuild()) return false

  process.chdir(app.getPath('home'))
  app.commandLine.appendSwitch('no-sandbox')
  return true
}
