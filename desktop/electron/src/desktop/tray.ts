import { app, Tray, Menu, nativeImage, BrowserWindow } from 'electron'
import { join } from 'node:path'

let tray: Tray | null = null

/**
 * Create the system tray icon + menu (Show / Hide / Quit).
 * The window's close button hides to tray; only "Quit" tears down the backend.
 */
export function createTray(win: BrowserWindow, onQuit: () => void): Tray {
  const iconPath = join(__dirname, '../resources/icon-32.png')
  const image = nativeImage.createFromPath(iconPath)
  tray = new Tray(
    image.isEmpty()
      ? nativeImage.createFromPath(join(__dirname, '../resources/icon.png'))
      : image,
  )
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
