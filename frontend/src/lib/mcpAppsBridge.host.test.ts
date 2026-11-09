import { describe, it, expect, vi, beforeEach } from 'vitest'

// The bridge only reaches services through its tool/resource proxies, which these
// tests never invoke — mocking the module keeps axios (a browser/node hybrid) out
// of the node test environment entirely.
vi.mock('@/services', () => ({
  services: {
    mcpApps: {
      read: vi.fn(),
      listResources: vi.fn(),
      listResourceTemplates: vi.fn(),
      subscribe: vi.fn(),
      unsubscribe: vi.fn(),
      call: vi.fn(),
      sampling: vi.fn(),
    },
  },
}))

/**
 * Host-bridge protocol tests for the MCP Apps session (SEP-1865): drives the REAL
 * AppBridge + PostMessageTransport through a minimal DOM shim, so the assertions
 * cover the actual JSON-RPC frames — capability honesty, the handshake data order
 * (tool-input → tool-result → widget-state), the conversation-injection handlers,
 * display-mode resolution, tool-cancelled, and teardown.
 *
 * The node test environment has no DOM; the shims below cover exactly what the
 * session reads (theme tokens via getComputedStyle, window message routing) and
 * nothing more.
 */

class FakeClassList {
  private readonly classes = new Set<string>(['theme-fengyu-light'])
  contains(name: string): boolean {
    return this.classes.has(name)
  }
  add(name: string): void {
    this.classes.add(name)
  }
  remove(name: string): void {
    this.classes.delete(name)
  }
}

/** Token values getComputedStyle reports; the subtle one is INDIRECT on purpose. */
const TOKENS: Record<string, string> = {
  '--ui-font-size': '14px',
  '--color-background': '#faf9f6',
  '--color-foreground': '#18181b',
  '--color-foreground-subtle': 'color-mix(in oklab, var(--color-foreground) 60%, transparent)',
  '--color-border': '#e5e2db',
  '--color-primary': '#eab04b',
  '--font-mono': 'ui-monospace, SFMono-Regular, monospace',
}

type MessageListener = (event: { source: unknown; data: unknown }) => void
const messageListeners = new Set<MessageListener>()

const windowShim = {
  addEventListener: (kind: string, listener: MessageListener) => {
    if (kind === 'message') messageListeners.add(listener)
  },
  removeEventListener: (kind: string, listener: MessageListener) => {
    if (kind === 'message') messageListeners.delete(listener)
  },
}

const documentShim = {
  documentElement: { classList: new FakeClassList(), clientWidth: 600 },
  body: { fontFamily: 'Inter, system-ui, sans-serif' },
  createElement: () => ({ click: () => undefined }),
}

;(globalThis as Record<string, unknown>).window = windowShim
;(globalThis as Record<string, unknown>).document = documentShim
;(globalThis as Record<string, unknown>).getComputedStyle = () => ({
  getPropertyValue: (name: string) => TOKENS[name] ?? '',
})

const { startMcpAppSession, hostContextRefresh } = await import('./mcpAppsBridge')
type McpAppSessionT = Awaited<ReturnType<typeof startMcpAppSession>>
type JsonRpcFrame = Record<string, unknown>

/** Delivers a frame to the bridge exactly as the page's postMessage would. */
function deliver(frame: unknown): void {
  for (const listener of [...messageListeners]) {
    listener({ source: fakeContentWindow, data: frame })
  }
}

let sent: JsonRpcFrame[]
let fakeContentWindow: { postMessage: (message: unknown, origin: string) => void }

interface Harness {
  session: McpAppSessionT
  callbacks: {
    onInitialized: ReturnType<typeof vi.fn>
    onSendMessage?: ReturnType<typeof vi.fn>
    onUpdateModelContext?: ReturnType<typeof vi.fn>
    onSizeChange: ReturnType<typeof vi.fn>
    onOpenLink: ReturnType<typeof vi.fn>
    onRequestTeardown: ReturnType<typeof vi.fn>
    onCallTool: ReturnType<typeof vi.fn>
    onWidgetState?: ReturnType<typeof vi.fn>
  }
}

function startHarness(opts: {
  toolArgs?: Record<string, unknown>
  initialWidgetState?: unknown
  widgetMeta?: string
  samplingEnabled?: boolean
  conversation?: boolean
  onDisplayMode?: (mode: 'inline' | 'fullscreen' | 'pip') => Promise<'inline' | 'fullscreen' | 'pip'>
}): Harness {
  sent = []
  fakeContentWindow = { postMessage: (message) => { sent.push(message as JsonRpcFrame) } }
  const callbacks = {
    onInitialized: vi.fn(),
    onSizeChange: vi.fn(),
    onOpenLink: vi.fn(),
    onRequestTeardown: vi.fn(),
    onCallTool: vi.fn(),
    ...(opts.conversation
      ? {
          onSendMessage: vi.fn(() => ({})),
          onUpdateModelContext: vi.fn(),
        }
      : {}),
    ...(opts.onDisplayMode
      ? { onRequestDisplayMode: vi.fn(opts.onDisplayMode) }
      : {}),
  }
  const session = startMcpAppSession({
    // Cast through unknown: the shims satisfy exactly the fields the session reads.
    frame: { contentWindow: fakeContentWindow } as unknown as HTMLIFrameElement,
    ui: {
      serverId: 'srv', serverName: 'Srv', tool: 't', resourceUri: 'ui://t/view',
      content: JSON.stringify([{ type: 'text', text: 'done' }]),
      structuredContent: JSON.stringify({ ok: true }),
      ...(opts.widgetMeta !== undefined ? { widgetMeta: opts.widgetMeta } : {}),
    },
    prepared: {
      sandboxId: 'sbx', sandboxUrl: 'http://127.0.0.1:2/sandbox/app/sbx', serverName: 'Srv',
      resourceUri: 'ui://t/view', samplingEnabled: opts.samplingEnabled === true,
    },
    displayMode: 'inline',
    scopeId: 'tool:1',
    ...(opts.toolArgs !== undefined ? { toolArgs: opts.toolArgs } : {}),
    ...(opts.initialWidgetState !== undefined ? { initialWidgetState: opts.initialWidgetState } : {}),
    callbacks: callbacks as Parameters<typeof startMcpAppSession>[0]['callbacks'],
  })
  return { session, callbacks }
}

/** Drives the full handshake: initialize request → response, initialized → data. */
async function handshake(_harness: Harness): Promise<JsonRpcFrame> {
  deliver({
    jsonrpc: '2.0', id: 1, method: 'ui/initialize',
    params: {
      appInfo: { name: 'page', version: '1.0.0' },
      appCapabilities: {},
      protocolVersion: '2026-01-26',
    },
  })
  const initializeResponse = await waitFor(
    () => sent.find((frame) => frame.id === 1 && frame.result !== undefined),
  )
  deliver({ jsonrpc: '2.0', method: 'ui/notifications/initialized' })
  return initializeResponse as JsonRpcFrame
}

async function waitFor(predicate: () => unknown, timeoutMs = 1500): Promise<JsonRpcFrame> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const match = predicate()
    if (match) return match as JsonRpcFrame
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
  throw new Error('waitFor timed out; frames so far: ' + JSON.stringify(sent))
}

const notifications = () => sent.filter((frame) => frame.method !== undefined && frame.id === undefined)
const method = (name: string) => notifications().find((frame) => frame.method === name)

beforeEach(() => {
  messageListeners.clear()
})

describe('MCP Apps host bridge capabilities', () => {
  it('declares only what the surface implements (honest capabilities)', async () => {
    const plain = startHarness({})
    const response = await handshake(plain) as { result: { hostCapabilities: Record<string, unknown> } }
    const caps = response.result.hostCapabilities
    expect(caps.message).toBeUndefined()
    expect(caps.updateModelContext).toBeUndefined()
    expect(caps.sampling).toBeUndefined()
    expect(caps.openLinks).toEqual({})
    expect(caps.downloadFile).toEqual({})
    expect((caps.experimental as Record<string, unknown>)['fengyu/widgetState']).toEqual({})
    await plain.session.dispose()
  })

  it('declares message/updateModelContext on conversation surfaces and sampling.tools when enabled', async () => {
    const harness = startHarness({ conversation: true, samplingEnabled: true })
    const response = await handshake(harness) as { result: { hostCapabilities: Record<string, unknown> } }
    const caps = response.result.hostCapabilities
    expect(caps.message).toEqual({ text: {} })
    expect(caps.updateModelContext).toEqual({ text: {}, structuredContent: {} })
    expect(caps.sampling).toEqual({ tools: {} })
    await harness.session.dispose()
  })
})

describe('MCP Apps handshake data order', () => {
  it('sends tool-input before tool-result, then the widget-state extension', async () => {
    const harness = startHarness({
      toolArgs: { city: 'Tokyo' },
      initialWidgetState: { zoom: 2 },
      widgetMeta: JSON.stringify({ heightHint: 300 }),
    })
    await handshake(harness)
    await waitFor(() => method('ui/notifications/widget-state'))

    const names = notifications().map((frame) => frame.method)
    const inputIndex = names.indexOf('ui/notifications/tool-input')
    const resultIndex = names.indexOf('ui/notifications/tool-result')
    const widgetIndex = names.indexOf('ui/notifications/widget-state')
    expect(inputIndex).toBeGreaterThanOrEqual(0)
    expect(resultIndex).toBeGreaterThan(inputIndex)
    expect(widgetIndex).toBeGreaterThan(resultIndex)

    const toolInput = method('ui/notifications/tool-input') as { params: { arguments: unknown } }
    expect(toolInput.params.arguments).toEqual({ city: 'Tokyo' })
    const toolResult = method('ui/notifications/tool-result') as { params: { structuredContent: unknown } }
    expect(toolResult.params.structuredContent).toEqual({ ok: true })
    const widgetState = method('ui/notifications/widget-state') as { params: Record<string, unknown> }
    expect(widgetState.params.widgetState).toEqual({ zoom: 2 })
    expect(widgetState.params.scopeId).toBe('tool:1')
    expect(widgetState.params.widgetMeta).toEqual({ heightHint: 300 })
    expect(harness.callbacks.onInitialized).toHaveBeenCalled()
    await harness.session.dispose()
  })

  it('skips tool-input when the surface has no tool arguments (panel previews)', async () => {
    const harness = startHarness({})
    await handshake(harness)
    await waitFor(() => method('ui/notifications/tool-result'))
    expect(method('ui/notifications/tool-input')).toBeUndefined()
    await harness.session.dispose()
  })
})

describe('MCP Apps page → host requests', () => {
  it('routes ui/message text blocks into the conversation', async () => {
    const harness = startHarness({ conversation: true })
    await handshake(harness)
    deliver({
      jsonrpc: '2.0', id: 7, method: 'ui/message',
      params: { role: 'user', content: [{ type: 'text', text: 'show details' }] },
    })
    const response = await waitFor(() => sent.find((frame) => frame.id === 7 && frame.result !== undefined))
    expect((response as { result: unknown }).result).toEqual({})
    expect(harness.callbacks.onSendMessage).toHaveBeenCalledWith('show details')
    await harness.session.dispose()
  })

  it('rejects ui/message when the surface has no conversation wiring', async () => {
    const harness = startHarness({ conversation: false })
    await handshake(harness)
    deliver({
      jsonrpc: '2.0', id: 8, method: 'ui/message',
      params: { role: 'user', content: [{ type: 'text', text: 'nope' }] },
    })
    const response = await waitFor(() => sent.find((frame) => frame.id === 8)) as { result?: unknown; error?: unknown }
    expect(response.result).toEqual({ isError: true })
    await harness.session.dispose()
  })

  it('parks ui/update-model-context content for the next turn', async () => {
    const harness = startHarness({ conversation: true })
    await handshake(harness)
    deliver({
      jsonrpc: '2.0', id: 9, method: 'ui/update-model-context',
      params: {
        content: [{ type: 'text', text: 'cart: 3 items' }],
        structuredContent: { items: 3 },
      },
    })
    await waitFor(() => sent.find((frame) => frame.id === 9 && frame.result !== undefined))
    expect(harness.callbacks.onUpdateModelContext).toHaveBeenCalledWith('cart: 3 items', { items: 3 })
    await harness.session.dispose()
  })

  it('answers ui/request-display-mode with the mode the host actually applied', async () => {
    const harness = startHarness({
      onDisplayMode: async (mode) => (mode === 'fullscreen' ? 'fullscreen' : 'inline'),
    })
    await handshake(harness)
    deliver({ jsonrpc: '2.0', id: 10, method: 'ui/request-display-mode', params: { mode: 'pip' } })
    const pip = await waitFor(() => sent.find((frame) => frame.id === 10)) as { result: { mode: string } }
    expect(pip.result.mode).toBe('inline')

    deliver({ jsonrpc: '2.0', id: 11, method: 'ui/request-display-mode', params: { mode: 'fullscreen' } })
    const full = await waitFor(() => sent.find((frame) => frame.id === 11)) as { result: { mode: string } }
    expect(full.result.mode).toBe('fullscreen')
    // The applied mode reaches the page as a host-context-changed diff.
    await waitFor(() => method('ui/notifications/host-context-changed'))
    const changed = method('ui/notifications/host-context-changed') as { params: Record<string, unknown> }
    expect(changed.params.displayMode).toBe('fullscreen')
    await harness.session.dispose()
  })
})

describe('MCP Apps host → page notifications', () => {
  it('notifies tool-cancelled with the reason', async () => {
    const harness = startHarness({})
    await handshake(harness)
    await harness.session.notifyToolCancelled('user stopped the run')
    await waitFor(() => method('ui/notifications/tool-cancelled'))
    const frame = method('ui/notifications/tool-cancelled') as { params: { reason: string } }
    expect(frame.params.reason).toBe('user stopped the run')
    await harness.session.dispose()
  })

  it('emits host-context-changed diffs on explicit refreshes', async () => {
    const harness = startHarness({})
    await handshake(harness)
    harness.session.updateHostContext(hostContextRefresh())
    // No field changed → the SDK diff emits nothing.
    expect(method('ui/notifications/host-context-changed')).toBeUndefined()
    harness.session.updateHostContext({ theme: 'dark' })
    const changed = await waitFor(() => method('ui/notifications/host-context-changed')) as { params: Record<string, unknown> }
    expect(changed.params.theme).toBe('dark')
    await harness.session.dispose()
  })
})

describe('MCP Apps teardown', () => {
  it('sends ui/resource-teardown and closes the transport', async () => {
    const harness = startHarness({})
    await handshake(harness)
    const disposing = harness.session.dispose()
    const request = await waitFor(
      () => sent.find((frame) => frame.method === 'ui/resource-teardown' && frame.id !== undefined),
    ) as { id: number }
    deliver({ jsonrpc: '2.0', id: request.id, result: {} })
    await disposing
    expect(messageListeners.size).toBe(0)
  })
})
