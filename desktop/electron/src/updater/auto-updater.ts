import { autoUpdater } from 'electron-updater'
import { app, dialog, shell } from 'electron'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { isWindowsPortable } from './portable-updater'
import { configureUpdateFeed, updateDownloadPageUrl } from './update-feed'
import { markUpdateInstallRestart } from '../desktop/graceful-quit'

/**
 * Check for updates (async, non-blocking). Source: GitHub Releases by default, or FY-Proxy's
 * variant-specific generic feed when FENGYU_UPDATE_API_BASE is configured.
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
  // on GitHub is shared by both variants and currently references just one of them. FY-Proxy
  // exposes separate feeds, so JRE updates are safe only when the intranet feed is configured.
  const hasBundledJre = existsSync(join(process.resourcesPath, 'jre'))

  // Windows portable zip: electron-updater (NsisUpdater) cannot self-install it — there is no
  // setup.exe / elevate.exe / app-update.yml in a portable extract. The renderer-driven path
  // (ipc/update.ts) handles portable updates via portable-updater.ts; skip the startup notify
  // here so NsisUpdater never tries to run a non-existent installer.
  if (isWindowsPortable()) {
    console.log('[updater] Windows portable build detected; skipping electron-updater (no NSIS installer)')
    return
  }

  let intranetFeed: string | null
  try {
    intranetFeed = configureUpdateFeed(autoUpdater, hasBundledJre)
  } catch (err) {
    console.error('[updater] invalid intranet update feed:', err)
    return
  }
  if (hasBundledJre && !intranetFeed) {
    console.log('[updater] JRE variant detected; skipping shared GitHub update feed')
    return
  }

  const signedRelease = readSignedReleaseFlag()
  const canAutoInstall = signedRelease && intranetFeed !== null
  // CRITICAL: disable electron-updater's implicit download + quit-and-install for unsigned builds
  // BEFORE checkForUpdates() — otherwise the library starts downloading the moment it finds an
  // update and installs it on next quit regardless of our dialog choice.
  autoUpdater.autoDownload = canAutoInstall
  autoUpdater.autoInstallOnAppQuit = canAutoInstall

  try {
    const result = await autoUpdater.checkForUpdates()
    if (!result?.updateInfo) return
    // The public GitHub release currently has one shared latest*.yml for both lite and JRE; the
    // JRE build overwrites it. Never auto-install from that ambiguous feed. FY-Proxy is safe
    // because configureUpdateFeed selected a variant-specific URL above.
    if (canAutoInstall) {
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
export function readBakedPackageMetadata(): Record<string, unknown> {
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
    // Tell the before-quit graceful-shutdown handler to skip its wait: this quit hands over to
    // the installer, which must not sit behind a backend shutdown grace window.
    markUpdateInstallRestart()
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
      'This update cannot be installed automatically from the current release feed. Open the download page to install it manually.',
  })
  if (choice.response === 0) {
    await shell.openExternal(updateDownloadPageUrl())
  }
}
