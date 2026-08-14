const { rm, writeFile } = require('node:fs/promises')
const { join } = require('node:path')

const PORTABLE_MARKER = 'fengyu-portable-zip'

/**
 * Add a target-specific runtime marker to Windows ZIP packages.
 *
 * electron-builder writes app-update.yml into the shared appOutDir whenever the target set
 * contains NSIS. That makes app-update.yml unusable for distinguishing the extract-and-run ZIP
 * from an installed NSIS build. Release workflows therefore build NSIS and ZIP in separate
 * passes and set FENGYU_WINDOWS_PORTABLE_ZIP=1 only for the ZIP pass.
 */
module.exports = async function afterPack(context) {
  if (context.electronPlatformName !== 'win32') return

  const marker = join(context.appOutDir, 'resources', PORTABLE_MARKER)
  if (process.env.FENGYU_WINDOWS_PORTABLE_ZIP === '1') {
    await writeFile(marker, 'portable-zip\n', 'utf8')
  } else {
    await rm(marker, { force: true })
  }
}
