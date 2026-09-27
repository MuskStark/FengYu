/**
 * Log domain — the settings page's log panel: the unified list of active log files
 * (plus the plugin ids seen in plugin.log) and whole-line tail reads.
 */
import type { LogsOverview, LogTail } from './types'
import { http } from './impl/http'

export interface LogsService {
  list(): Promise<LogsOverview>
  tail(name: string, maxBytes?: number): Promise<LogTail>
}

export const logsService: LogsService = {
  async list() {
    const { data } = await http.get<LogsOverview>('/api/logs')
    return data
  },
  async tail(name, maxBytes) {
    const { data } = await http.get<LogTail>('/api/logs/' + encodeURIComponent(name) + '/tail', {
      params: maxBytes == null ? undefined : { maxBytes },
    })
    return data
  },
}
