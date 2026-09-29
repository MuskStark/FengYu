import { app, Tray, Menu, nativeImage, BrowserWindow } from 'electron'
import { join } from 'node:path'

let tray: Tray | null = null

/**
 * Create the system tray icon + menu (Show / Hide / Quit).
 * The window's close button hides to tray; only "Quit" tears down the backend.
 *
 * The mark is the "hive lemniscate" (resources/tray-mark.svg — an infinity
 * whose lobes are honeycomb facets). Platform variants:
 *   macOS        trayTemplate.png (22px black+alpha) + @2x (44px), used as a
 *                TEMPLATE image so the system recolors it for light/dark
 *                menu bars — full-color squircles read as noise next to
 *                system glyphs.
 *   Win/Linux    icon-16.png (gold core + ink outline) with icon-32.png as
 *                the 2x representation; colorful survives both light and
 *                dark taskbars.
 */
export function createTray(win: BrowserWindow, onQuit: () => void): Tray {
  // Resolve the tray icons in BOTH dev and packaged modes.
  //
  // Dev:      tray.js lives in dist/desktop/, so ../../resources/ reaches the
  //           project resources/ dir (trayTemplate.png / icon-16.png / …).
  // Packaged: electron-builder's extraResources flattens the tray PNGs
  //           to the ROOT of process.resourcesPath (see electron-builder.yml),
  //           so they resolve at <resourcesPath>/trayTemplate.png — NOT inside
  //           app.asar (asar entries are not real files nativeImage can read).
  const base = app.isPackaged ? process.resourcesPath : join(__dirname, '../../resources')
  const iconAt = (name: string) => nativeImage.createFromPath(join(base, name))

  let image: Electron.NativeImage
  if (process.platform === 'darwin') {
    // 22px logical menu-bar size; the 44px @2x keeps retina crisp.
    image = iconAt('trayTemplate.png')
    const retina = iconAt('trayTemplate@2x.png')
    if (!image.isEmpty() && !retina.isEmpty()) {
      image.addRepresentation({ scaleFactor: 2, buffer: retina.toPNG(), width: 44, height: 44 })
    }
    if (!image.isEmpty()) image.setTemplateImage(true)
  } else {
    // Windows/Linux tray glyphs are 16px logical; add icon-32 as the 2x rep.
    image = iconAt('icon-16.png')
    const hd = iconAt('icon-32.png')
    if (!image.isEmpty() && !hd.isEmpty()) {
      image.addRepresentation({ scaleFactor: 2, buffer: hd.toPNG(), width: 32, height: 32 })
    }
    if (image.isEmpty()) image = hd
  }
  if (image.isEmpty()) {
    // Last-resort fallback (asset files missing): downscale the app icon.
    const source = iconAt('icon.png')
    const size = process.platform === 'darwin' ? 22 : 16
    image = source.resize({ width: size, height: size })
  }
  tray = new Tray(image)
  tray.setToolTip('FengYu')

  const menu = Menu.buildFromTemplate([
    { label: 'Show', click: () => { win.show(); win.focus() } },
    { label: 'Hide', click: () => { win.hide() } },
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
