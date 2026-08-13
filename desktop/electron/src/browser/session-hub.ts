import { BrowserSession } from './session'

interface ContextState {
  currentTabId: string
  tabs: Map<string, BrowserSession>
}

interface LogicalSession {
  currentContextId: string
  contexts: Map<string, ContextState>
}

export interface BrowserRoute {
  sessionId: string
  contextId: string
  tabId: string
  session: BrowserSession
}

/**
 * Routes private Java bridge session/context/tab ids to isolated Electron windows.
 * Tabs in one context share the same persistent Chromium partition; different contexts use
 * different partitions. Legacy requests with no routing fields continue to use the original
 * BrowserSession supplied by main.ts.
 */
export class BrowserSessionHub {
  private readonly sessions = new Map<string, LogicalSession>()
  private tabSequence = 0
  private contextSequence = 0

  constructor(private readonly defaultSession: BrowserSession) {
    const context: ContextState = { currentTabId: 'main', tabs: new Map([['main', defaultSession]]) }
    this.sessions.set('default', {
      currentContextId: 'default',
      contexts: new Map([['default', context]]),
    })
  }

  resolve(params: Record<string, unknown>): BrowserRoute {
    const sessionId = cleanId(params._sessionId) ?? 'default'
    const logical = this.logicalSession(sessionId)
    const requestedContext = cleanId(params._contextId)
    const contextId = requestedContext && logical.contexts.has(requestedContext)
      ? requestedContext : logical.currentContextId
    const context = logical.contexts.get(contextId)!
    const requestedTab = cleanId(params._tabId)
    const tabId = requestedTab && context.tabs.has(requestedTab) ? requestedTab : context.currentTabId
    context.currentTabId = tabId
    logical.currentContextId = contextId
    return { sessionId, contextId, tabId, session: context.tabs.get(tabId)! }
  }

  list(params: Record<string, unknown>): Record<string, unknown> {
    const route = this.resolve(params)
    const logical = this.sessions.get(route.sessionId)!
    const context = logical.contexts.get(route.contextId)!
    const tabs = Array.from(context.tabs.entries()).map(([tabId, session]) => ({
      tabId,
      active: tabId === context.currentTabId,
      ...session.describe(),
    }))
    return this.decorate(route, { success: true, summary: `listed ${tabs.length} tab(s)`, tabs })
  }

  listContexts(params: Record<string, unknown>): Record<string, unknown> {
    const route = this.resolve(params)
    const logical = this.sessions.get(route.sessionId)!
    const contexts = Array.from(logical.contexts.entries()).map(([contextId, context]) => ({
      contextId,
      active: contextId === logical.currentContextId,
      tabCount: context.tabs.size,
      activeTabId: context.currentTabId,
    }))
    return this.decorate(route, {
      success: true, summary: `listed ${contexts.length} context(s)`, contexts,
    })
  }

  newContext(params: Record<string, unknown>): Record<string, unknown> {
    const base = this.resolve(params)
    const logical = this.sessions.get(base.sessionId)!
    const contextId = `context_${++this.contextSequence}`
    const session = this.newSession(base.sessionId, contextId, 'main')
    logical.contexts.set(contextId, { currentTabId: 'main', tabs: new Map([['main', session]]) })
    logical.currentContextId = contextId
    session.ensureWindow()
    return this.decorate({ ...base, contextId, tabId: 'main', session }, {
      success: true, summary: `opened ${contextId}`, ...session.describe(),
    })
  }

  selectContext(params: Record<string, unknown>): Record<string, unknown> {
    const base = this.resolve(params)
    const contextId = cleanId(params.contextId)
    if (!contextId) return this.decorate(base, { success: false, summary: "missing 'contextId' parameter" })
    const logical = this.sessions.get(base.sessionId)!
    const context = logical.contexts.get(contextId)
    if (!context) return this.decorate(base, { success: false, summary: `unknown context: ${contextId}` })
    logical.currentContextId = contextId
    const session = context.tabs.get(context.currentTabId)!
    return this.decorate({ ...base, contextId, tabId: context.currentTabId, session }, {
      success: true, summary: `selected ${contextId}`, ...session.describe(),
    })
  }

  closeContext(params: Record<string, unknown>): Record<string, unknown> {
    const base = this.resolve(params)
    const logical = this.sessions.get(base.sessionId)!
    const requested = cleanId(params.contextId) ?? logical.currentContextId
    const target = logical.contexts.get(requested)
    if (!target) return this.decorate(base, { success: false, summary: `unknown context: ${requested}` })
    for (const session of target.tabs.values()) session.close()
    logical.contexts.delete(requested)
    if (logical.contexts.size === 0) {
      const session = this.newSession(base.sessionId, 'default', 'main')
      logical.contexts.set('default', { currentTabId: 'main', tabs: new Map([['main', session]]) })
    }
    if (logical.currentContextId === requested) logical.currentContextId = logical.contexts.keys().next().value ?? 'default'
    const active = logical.contexts.get(logical.currentContextId)!
    const session = active.tabs.get(active.currentTabId)!
    return this.decorate({ ...base, contextId: logical.currentContextId, tabId: active.currentTabId, session }, {
      success: true, summary: `closed ${requested}`, closedContextId: requested, ...session.describe(),
    })
  }

  async newTab(
    params: Record<string, unknown>,
    navigate: (session: BrowserSession, url: string) => Promise<Record<string, unknown>>,
  ): Promise<Record<string, unknown>> {
    const base = this.resolve(params)
    const logical = this.sessions.get(base.sessionId)!
    const context = logical.contexts.get(base.contextId)!
    const tabId = `tab_${++this.tabSequence}`
    const session = this.newSession(base.sessionId, base.contextId, tabId)
    context.tabs.set(tabId, session)
    context.currentTabId = tabId
    const route = { ...base, tabId, session }
    const url = typeof params.url === 'string' && params.url.trim() ? params.url.trim() : null
    if (url) {
      const result = await navigate(session, url)
      return this.decorate(route, { ...result, summary: result.success ? `opened ${tabId}: ${String(result.summary)}` : result.summary })
    }
    session.ensureWindow()
    return this.decorate(route, { success: true, summary: `opened ${tabId}`, ...session.describe() })
  }

  selectTab(params: Record<string, unknown>): Record<string, unknown> {
    const base = this.resolve(params)
    const tabId = cleanId(params.tabId)
    if (!tabId) return this.decorate(base, { success: false, summary: "missing 'tabId' parameter" })
    const logical = this.sessions.get(base.sessionId)!
    const context = logical.contexts.get(base.contextId)!
    const session = context.tabs.get(tabId)
    if (!session) return this.decorate(base, { success: false, summary: `unknown tab: ${tabId}` })
    context.currentTabId = tabId
    return this.decorate({ ...base, tabId, session }, {
      success: true, summary: `selected ${tabId}`, ...session.describe(),
    })
  }

  closeTab(params: Record<string, unknown>): Record<string, unknown> {
    const base = this.resolve(params)
    const logical = this.sessions.get(base.sessionId)!
    const context = logical.contexts.get(base.contextId)!
    const requested = cleanId(params.tabId) ?? context.currentTabId
    const target = context.tabs.get(requested)
    if (!target) return this.decorate(base, { success: false, summary: `unknown tab: ${requested}` })
    target.close()
    context.tabs.delete(requested)
    if (context.tabs.size === 0) context.tabs.set('main', this.newSession(base.sessionId, base.contextId, 'main'))
    if (context.currentTabId === requested) context.currentTabId = context.tabs.keys().next().value ?? 'main'
    const active = context.tabs.get(context.currentTabId)!
    return this.decorate({ ...base, tabId: context.currentTabId, session: active }, {
      success: true, summary: `closed ${requested}`, closedTabId: requested, ...active.describe(),
    })
  }

  decorate(route: BrowserRoute, envelope: Record<string, unknown>): Record<string, unknown> {
    return {
      ...envelope,
      sessionId: route.sessionId,
      contextId: route.contextId,
      tabId: route.tabId,
    }
  }

  closeAll(): void {
    for (const logical of this.sessions.values()) {
      for (const context of logical.contexts.values()) {
        for (const session of context.tabs.values()) {
          if (typeof session.close === 'function') session.close()
        }
      }
    }
    this.sessions.clear()
  }

  private logicalSession(sessionId: string): LogicalSession {
    let logical = this.sessions.get(sessionId)
    if (logical) return logical
    const context: ContextState = {
      currentTabId: 'main',
      tabs: new Map([['main', this.newSession(sessionId, 'default', 'main')]]),
    }
    logical = { currentContextId: 'default', contexts: new Map([['default', context]]) }
    this.sessions.set(sessionId, logical)
    return logical
  }

  private newSession(sessionId: string, contextId: string, tabId: string): BrowserSession {
    const suffix = safePartition(`${sessionId}-${contextId}`)
    const partition = sessionId === 'default' && contextId === 'default'
      ? 'persist:fengyu-browser' : `persist:fengyu-browser-${suffix}`
    return new BrowserSession(partition, `FengYu Browser · ${tabId}`)
  }
}

function cleanId(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const id = value.trim()
  return id && /^[A-Za-z0-9_.:-]{1,160}$/.test(id) ? id : null
}

function safePartition(value: string): string {
  return value.replace(/[^A-Za-z0-9_.-]/g, '_').slice(0, 80) || 'default'
}
