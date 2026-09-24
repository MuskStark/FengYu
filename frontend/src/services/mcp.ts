/**
 * MCP domain — server registry, discovery, live tool diagnostics, and the
 * host process-isolation status.
 */
import type {
  McpCallResult,
  McpPrompt,
  McpResource,
  McpServer,
  McpServerRequest,
  McpStatus,
  ProcessIsolationStatus,
} from './types'
import { http } from './impl/http'

export interface McpService {
  status(): Promise<McpStatus>
  servers(): Promise<McpServer[]>
  createServer(request: McpServerRequest): Promise<McpServer>
  updateServer(id: string, request: McpServerRequest): Promise<McpServer>
  deleteServer(id: string): Promise<{ deleted: boolean }>
  testServer(id: string): Promise<McpServer>
  prompts(id: string): Promise<McpPrompt[]>
  resources(id: string): Promise<McpResource[]>
  callTool(id: string, tool: string, args?: Record<string, unknown>): Promise<McpCallResult>
  processIsolation(): Promise<ProcessIsolationStatus>
}

export const mcpService: McpService = {
  status: () => http.get<McpStatus>('/api/mcp/status').then((r) => r.data),
  servers: () => http.get<McpServer[]>('/api/mcp/servers').then((r) => r.data),
  createServer: (request) => http.post<McpServer>('/api/mcp/servers', request).then((r) => r.data),
  updateServer: (id, request) =>
    http.put<McpServer>(`/api/mcp/servers/${encodeURIComponent(id)}`, request).then((r) => r.data),
  deleteServer: (id) =>
    http.delete<{ deleted: boolean }>(`/api/mcp/servers/${encodeURIComponent(id)}`).then((r) => r.data),
  testServer: (id) =>
    http.post<McpServer>(`/api/mcp/servers/${encodeURIComponent(id)}/test`).then((r) => r.data),
  prompts: (id) =>
    http.get<McpPrompt[]>(`/api/mcp/servers/${encodeURIComponent(id)}/prompts`).then((r) => r.data),
  resources: (id) =>
    http.get<McpResource[]>(`/api/mcp/servers/${encodeURIComponent(id)}/resources`).then((r) => r.data),
  callTool: (id, tool, args = {}) =>
    http.post<McpCallResult>(`/api/mcp/servers/${encodeURIComponent(id)}/call`, { tool, arguments: args }).then((r) => r.data),
  processIsolation: () =>
    http.get<ProcessIsolationStatus>('/api/security/process-isolation').then((r) => r.data),
}
