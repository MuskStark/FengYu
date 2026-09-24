/**
 * System domain — health probe and the first-launch SETUP wizard surface.
 */
import type { ConnectionTestRequest, ConnectionTestResult, DbTypeMeta, HealthResponse, InitializeResult, SetupStatus } from './types'
import { http } from './impl/http'

export interface SystemService {
  health(): Promise<HealthResponse>
  setupStatus(): Promise<SetupStatus>
  setupTypes(): Promise<DbTypeMeta[]>
  testConnection(req: ConnectionTestRequest): Promise<ConnectionTestResult>
  initialize(req: ConnectionTestRequest): Promise<InitializeResult>
}

export const systemService: SystemService = {
  async health() {
    const { data } = await http.get<HealthResponse>('/api/health')
    return data
  },
  async setupStatus() {
    const { data } = await http.get<SetupStatus>('/api/setup/status')
    return data
  },
  async setupTypes() {
    const { data } = await http.get<DbTypeMeta[]>('/api/setup/types')
    return data
  },
  async testConnection(req) {
    const { data } = await http.post<ConnectionTestResult>('/api/setup/test-connection', req)
    return data
  },
  async initialize(req) {
    const { data } = await http.post<InitializeResult>('/api/setup/initialize', req)
    return data
  },
}
