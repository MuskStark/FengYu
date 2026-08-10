import { autoUpdater } from 'electron-updater'
import { app, dialog, shell } from 'electron'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { isWindowsPortable } from './portable-updater'

/**
 * Check for updates (async, non-blocking). Source: GitHub Releases (`latest*.yml`).
 *
 * P0-9 — auto-download/auto-install must be disabled for unsigned builds. `electron-updater`'s
 * defaults are `autoDownload = true` and `autoInstallOnAppQuit = true`, so merely *not calling*
 * `downloadUpdate()` / `quitAndInstall()` is NOT enough: a bare `checkForUpdates()` already starts
 * the download when an update is found, and registers an exit handler that runs the installer on
 * quit. For an unsigned build that means an unverified binary is fetched and installed behind the
 * user's back.
 *
 * Mitigation: before any `checkForUpdates()` call, force `autoDownload = false` AND
 * `autoInstallOnAppQuit = false`. They stay off for unsigned builds; a signed build may re-enable
 * them (then the offerAutoInstall path runs as before).
 *
 * Signed-state source: a build-time `fengyu.signedRelease` boolean baked into the packaged app's
 * `package.json` by electron-builder `extraMetadata`. The contract is:
 *   - `electron-builder.yml` / `electron-builder.jre.yml` set `extraMetadata.fengyu.signedRelease`
 *     (default `false` → unsigned). A FUTURE signed+notarized release workflow overrides it to
 *     `true` via `--config.extraMetadata.fengyu.signedRelease=true`.
 *   - The packaged build reads ONLY that field from its own `package.json` (`app.getAppPath()`).
 *     There is deliberately NO `process.env` fallback: a packaged build must ignore the launch
 *     environment so a launcher cannot flip a build to "signed". (Dev runs never reach this code —
 *     `main.ts` only calls `checkForUpdates()` when `app.isPackaged`.)
 */
export async function checkForUpdates(): Promise<void> {
  // JRE variant bundles its own jlink JRE under <resourcesPath>/jre. The updater feed
  // (latest*.yml) only references the lite variant, so auto-update would silently downgrade
  // JRE users to the Java-dependent lite build. Skip the check until per-variant feeds exist.
  if (existsSync(join(process.resourcesPath, 'jre'))) {
    console.log('[updater] JRE variant detected; skipping auto-update (would downgrade to lite)')
    return
  }

  // Windows portable zip: electron-updater (NsisUpdater) cannot self-install it — there is no
  // setup.exe / elevate.exe / app-update.yml in a portable extract. The renderer-driven path
  // (ipc/update.ts) handles portable updates via portable-updater.ts; skip the startup notify
  // here so NsisUpdater never tries to run a non-existent installer.
  if (isWindowsPortable()) {
    console.log('[updater] Windows portable build detected; skipping electron-updater (no NSIS installer)')
    return
  }

  const signedRelease = readSignedReleaseFlag()
  // CRITICAL: disable electron-updater's implicit download + quit-and-install for unsigned builds
  // BEFORE checkForUpdates() — otherwise the library starts downloading the moment it finds an
  // update and installs it on next quit regardless of our dialog choice.
  autoUpdater.autoDownload = signedRelease
  autoUpdater.autoInstallOnAppQuit = signedRelease

  try {
    const result = await autoUpdater.checkForUpdates()
    if (!result?.updateInfo) return
    if (signedRelease) {
      await offerAutoInstall(result.updateInfo.version)
    } else {
      await offerManualDownload(result.updateInfo.version)
    }
  } catch (err) {
    console.error('[updater] check failed:', err)
  }
}

/**
 * Read the signed-release flag from the packaged app's build-time metadata ONLY. The value is
 * baked into `package.json` (under `fengyu.signedRelease`) by electron-builder `extraMetadata` and
 * read from `app.getAppPath()/package.json`. There is intentionally no environment-variable
 * fallback: a packaged build must not let a launcher flip it to "signed", and dev runs never reach
 * here (the caller is gated on `app.isPackaged`). A missing/ malformed field is treated as
 * unsigned (fail safe).
 *
 * Test seam: `readSignedReleaseFlag` takes an optional explicit metadata reader so the contract
 * can be unit-tested without a real packaged app.
 */
export function readSignedReleaseFlag(metadata: Record<string, unknown> = readBakedPackageMetadata()): boolean {
  const fengyu = metadata?.fengyu
  const value = (fengyu as Record<string, unknown> | undefined)?.signedRelease
  return value === true
}

/**
 * Read the `fengyu` block from the packaged app's `package.json`. Returns `{}` on any read/parse
 * failure so the caller fails safe (unsigned). Reads the file fresh each call (cheap, once at
 * startup); wrapped so a malformed packaged build never crashes the updater.
 */
function readBakedPackageMetadata(): Record<string, unknown> {
  try {
    const pkgPath = join(app.getAppPath(), 'package.json')
    const pkg = JSON.parse(readFileSync(pkgPath, 'utf8')) as Record<string, unknown>
    return pkg
  } catch (err) {
    console.error('[updater] cannot read baked package metadata; treating build as unsigned:', err)
    return {}
  }
}

/** Signed-release path: prompt to download & install, then hand off to electron-updater. */
async function offerAutoInstall(version: string): Promise<void> {
  const choice = await dialog.showMessageBox({
    type: 'question',
    buttons: ['Download & install', 'Later'],
    defaultId: 0,
    title: 'Update available',
    message: `Infinia ${version} is available. Download and install now?`,
  })
  if (choice.response === 0) {
    await autoUpdater.downloadUpdate()
    autoUpdater.quitAndInstall()
  }
}

/**
 * Unsigned-release path: notify the user an update exists and offer to open the manual download
 * page. Never invokes the installer — an unsigned update feed has no publisher verification.
 */
async function offerManualDownload(version: string): Promise<void> {
  const choice = await dialog.showMessageBox({
    type: 'info',
    buttons: ['Open download page', 'Later'],
    defaultId: 0,
    title: 'Update available',
    message: `Infinia ${version} is available.`,
    detail:
      'This build is not code-signed, so it will not auto-install. Open the releases page to download and install manually.',
  })
  if (choice.response === 0) {
    await shell.openExternal('https://github.com/MuskStark/FengYu/releases')
  }
}
