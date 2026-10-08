import { ipcMain } from 'electron'
import { isMainWindowSender } from './sender-guard'
import type { DesktopLogger } from '../desktop/logger'

/**
 * Renderer log forwarding: the SPA has no filesystem access, so its error surface
 * (window.onerror / unhandledrejection) rides this one-way channel into desktop.log —
 * prefixed and length-capped so a renderer spamming errors cannot balloon the file.
 * One-way `send` (not `invoke`): the renderer never blocks on logging.
 */
const MAX_MESSAGE_CHARS = 2_000

export function registerLogIpc(logger: DesktopLogger): void {
  ipcMain.on('log:renderer', (event, payload: { level?: string; message?: string }) => {
    if (!isMainWindowSender(event?.sender)) return
    const level = payload?.level === 'warn' || payload?.level === 'error' ? payload.level : 'info'
    const raw = typeof payload?.message === 'string' ? payload.message : String(payload?.message ?? '')
    const message = raw.length > MAX_MESSAGE_CHARS ? `${raw.slice(0, MAX_MESSAGE_CHARS)}…[truncated]` : raw
    if (level === 'error') logger.error(`[renderer] ${message}`)
    else if (level === 'warn') logger.warn(`[renderer] ${message}`)
    else logger.info(`[renderer] ${message}`)
  })
}
