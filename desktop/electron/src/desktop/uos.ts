/**
 * UOS (统信) no-sandbox launch policy.
 *
 * Non-root UOS systems forbid starting ANY OS-level sandbox: unprivileged user namespaces are
 * disabled by the security baseline and SUID helpers (chrome-sandbox) are blocked, so Chromium's
 * sandbox cannot — and must not — initialize; a normal launch aborts with the "SUID sandbox
 * helper … not configured correctly" fatal. The UOS build artifact (electron-builder.uos.yml)
 * therefore bakes `fengyu.uos: true` into the packaged package.json, and this module turns that
 * flag into two startup adaptations, both applied BEFORE anything else runs:
 *
 *   1. `app.commandLine.appendSwitch('no-sandbox')` — disables the Chromium OS-level sandbox
 *      for every process (renderers, GPU, utility). It must be called before app ready. The
 *      Electron-level renderer hardening is unaffected: `webPreferences.sandbox: true` still
 *      strips Node.js from renderers, and contextIsolation / the iframe `sandbox` attribute are
 *      orthogonal to the Chromium sandbox.
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
