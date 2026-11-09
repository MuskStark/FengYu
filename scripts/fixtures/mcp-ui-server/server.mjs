#!/usr/bin/env node
/**
 * Zero-dependency stdio MCP fixture server for the e2e smoke's MCP Apps leg
 * (scripts/e2e-smoke.sh). Speaks newline-delimited JSON-RPC 2.0 over stdin/stdout
 * with just enough of the MCP surface to exercise the SEP-1865 host path:
 *
 *   - one model+app tool (`smoke_ui_chart`) bound to `ui://smoke/view`
 *   - one app-only tool (`smoke_app_only`, visibility ["app"]) bound to the same
 *     resource, so the smoke can assert the claiming gate end to end
 *   - `resources/read` serving the HTML page as `text/html;profile=mcp-app`
 *     with a `_meta.ui` csp declaration
 *
 * Kept dependency-free on purpose: the smoke boots it with plain `node`, no
 * install step, and the backend spawns it exactly like any user stdio server.
 */

import readline from 'node:readline'

const RESOURCE_URI = 'ui://smoke/view'
const PAGE_HTML = `<!doctype html>
<html>
<head><meta charset="utf-8"><title>Smoke UI</title></head>
<body>
  <h1>SMOKE-UI-PAGE</h1>
  <p>MCP Apps e2e fixture view.</p>
</body>
</html>`

const TOOLS = [
  {
    name: 'smoke_ui_chart',
    description: 'Returns chart data rendered by the smoke UI page',
    inputSchema: { type: 'object', properties: {}, additionalProperties: true },
    _meta: { ui: { resourceUri: RESOURCE_URI, visibility: ['model', 'app'] } },
  },
  {
    name: 'smoke_app_only',
    description: 'App-only control the page can call after claiming',
    inputSchema: { type: 'object', properties: {}, additionalProperties: true },
    _meta: { ui: { resourceUri: RESOURCE_URI, visibility: ['app'] } },
  },
]

const send = (message) => {
  process.stdout.write(`${JSON.stringify(message)}\n`)
}

const reply = (id, result) => send({ jsonrpc: '2.0', id, result })
const fail = (id, code, message) => send({ jsonrpc: '2.0', id, error: { code, message } })

const handle = (request) => {
  const { id, method, params } = request
  switch (method) {
    case 'initialize':
      reply(id, {
        protocolVersion: params?.protocolVersion ?? '2025-06-18',
        capabilities: { tools: {}, resources: {} },
        serverInfo: { name: 'smoke-ui-server', version: '1.0.0' },
      })
      return
    case 'ping':
      reply(id, {})
      return
    case 'tools/list':
      reply(id, { tools: TOOLS })
      return
    case 'tools/call': {
      const tool = TOOLS.find((entry) => entry.name === params?.name)
      if (!tool) {
        fail(id, -32602, `Unknown tool: ${params?.name}`)
        return
      }
      reply(id, {
        content: [{ type: 'text', text: `${tool.name} ok` }],
        structuredContent: { ok: true, tool: tool.name, items: [3, 1, 2] },
      })
      return
    }
    case 'resources/list':
      reply(id, {
        resources: [
          {
            uri: RESOURCE_URI,
            name: 'Smoke UI view',
            mimeType: 'text/html;profile=mcp-app',
          },
        ],
      })
      return
    case 'resources/read':
      if (params?.uri !== RESOURCE_URI) {
        fail(id, -32602, `Unknown resource: ${params?.uri}`)
        return
      }
      reply(id, {
        contents: [
          {
            uri: RESOURCE_URI,
            mimeType: 'text/html;profile=mcp-app',
            text: PAGE_HTML,
            _meta: {
              ui: {
                csp: { connectDomains: [], resourceDomains: [], frameDomains: [], baseUriDomains: [] },
              },
            },
          },
        ],
      })
      return
    default:
      // Notifications (no id) are silently absorbed; unknown requests get the
      // standard method-not-found error so a protocol mismatch fails loudly.
      if (id !== undefined) fail(id, -32601, `Method not found: ${method}`)
  }
}

readline.createInterface({ input: process.stdin }).on('line', (line) => {
  const trimmed = line.trim()
  if (!trimmed) return
  try {
    handle(JSON.parse(trimmed))
  } catch {
    // A malformed line never takes the fixture down.
  }
})
