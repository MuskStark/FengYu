import { app, Tray, Menu, nativeImage, BrowserWindow } from 'electron'
import { join } from 'node:path'

let tray: Tray | null = null

/**
 * Create the system tray icon + menu (Show / Hide / Quit).
 * The window's close button hides to tray; only "Quit" tears down the backend.
 */
export function createTray(win: BrowserWindow, onQuit: () => void): Tray {
  // Resolve the tray icon in BOTH dev and packaged modes.
  //
  // Dev:      tray.js lives in dist/desktop/, so ../../resources/ reaches the
  //           project resources/ dir (icon-32.png / icon.png).
  // Packaged: electron-builder's extraResources flattens icon-32.png + icon.png
  //           to the ROOT of process.resourcesPath (see electron-builder.yml),
  //           so they resolve at <resourcesPath>/icon-32.png — NOT inside
  //           app.asar (asar entries are not real files nativeImage can read).
  const base = app.isPackaged ? process.resourcesPath : join(__dirname, '../../resources')
  const iconPath = join(base, 'icon-32.png')
  const raw = nativeImage.createFromPath(iconPath)
  const source = raw.isEmpty() ? nativeImage.createFromPath(join(base, 'icon.png')) : raw
  // Resize to the platform's standard tray size. macOS menu bar is ~22px (logical); Windows/Linux
  // tray icons are ~16px. nativeImage.resize takes logical pixels and handles @2x scaling. Without
  // this, a 32px (or 512px fallback) icon renders oversized and misaligned in the menu bar.
  const size = process.platform === 'darwin' ? 22 : 16
  const image = source.resize({ width: size, height: size })
  tray = new Tray(image)
  tray.setToolTip('FengYu')

  const menu = Menu.buildFromTemplate([
    { label: 'Show', click: () => { win.show(); win.focus() } },
    { label: 'Hide', click: () => win.hide() },
    { type: 'separator' },
    {
      label: 'Quit',
      click: () => {
        onQuit()
        app.quit()
      },
    },
  ])
  tray.setContextMenu(menu)
  tray.on('click', () => {
    if (win.isVisible()) win.hide()
    else { win.show(); win.focus() }
  })
  return tray
}
