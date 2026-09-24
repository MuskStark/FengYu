/**
 * App-update domain — the BACKEND-driven update check (portable/web builds).
 * The Electron auto-update cycle is a platform capability
 * (getPlatform().checkForUpdates), not this service.
 */
import type { UpdateApplyResult, UpdateCheckResult } from './types'
import { http } from './impl/http'

export interface AppUpdateService {
  /** Probe the latest GitHub release against the running build's version. */
  check(force?: boolean): Promise<UpdateCheckResult>
  /** Portable-mode only: download + verify + spawn the JAR self-restart. */
  applyPortable(): Promise<UpdateApplyResult>
}

export const appUpdateService: AppUpdateService = {
  check: (force = false) =>
    http.get<UpdateCheckResult>('/api/updates/check', { params: { force } }).then((r) => r.data),
  applyPortable: () => http.post<UpdateApplyResult>('/api/updates/apply').then((r) => r.data),
}
