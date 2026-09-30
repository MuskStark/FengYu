// Package version for diagnostics and release consistency. Wire compatibility is governed by the
// independently explicit PROTOCOL_VERSION exported from the side-effect-free protocol module.
export const SDK_VERSION = '2.1.0'

import {
  HOST_METHODS,
  PLUGIN_MESSAGE_SOURCE,
  PROTOCOL_VERSION,
  isHostMessage,
  type HostEnvironment,
  type HostError,
  type HostMethod,
  type Theme,
} from './protocol.js'

export * from './protocol.js'

export type FileAccess = 'read' | 'write' | 'read-write'
export interface FileRef { id: string; name: string; kind: 'file' | 'directory'; access: FileAccess; size: number }
export interface FileFilter { name: string; extensions: string[] }
export type Environment = HostEnvironment
export interface InvokeOptions { signal?: AbortSignal; timeoutMs?: number }
export interface FengYuClientOptions { target?: Window; timeoutMs?: number; allowedOrigin?: string }

export class FengYuHostError extends Error {
  readonly code: HostError['code']
  readonly details?: unknown

  constructor(error: HostError) {
    super(error.message)
    this.name = 'FengYuHostError'
    this.code = error.code
    this.details = error.details
  }
}

type Pending = {
  resolve(value: unknown): void
  reject(error: Error): void
  timer: ReturnType<typeof setTimeout>
  signal?: AbortSignal
  abort?: () => void
}
type EventHandler = (data: unknown) => void
let fallbackIdSequence = 0

type ResolvedOrigin = { origin: string; bridging: boolean }

/**
 * Resolves the single origin this client may bridge with. Order:
 *  1. an explicit {@link FengYuClientOptions.allowedOrigin} (tests, custom embeds);
 *  2. the embedding shell's `?shellOrigin=<urlencoded origin>` query parameter;
 *  3. nothing → bridging disabled with a console error. The historical default of `'*'`
 *     let any website that iframed this loopback-served page silently receive every
 *     invoke response (and everything the user typed into plugin forms).
 *
 * The parameter removes that unsafe wildcard default and pins the bridge to the host
 * shell for well-behaved hosts — it is NOT an authenticity guarantee: whoever controls
 * the iframe URL (the embedder) can append a forged `?shellOrigin=` and thus choose the
 * pinned origin, and a client-side script cannot tell the real shell from that embedder.
 * Genuine origin assurance has to be enforced server-side (by the host serving this
 * page), not by this client-side parameter.
 */
function resolveAllowedOrigin(explicit?: string): ResolvedOrigin {
  if (explicit) return { origin: explicit, bridging: true }
  if (typeof window === 'undefined') return { origin: '*', bridging: false }
  try {
    const param = new URLSearchParams(window.location.search).get('shellOrigin')
    if (param) {
      // Used verbatim (must be a serialized origin exactly as window.location.origin
      // produces it) — normalizing through URL().origin would corrupt the "file://"
      // origin Electron shells run under into the string "null".
      return { origin: param, bridging: true }
    }
  } catch {
    // Malformed param — fall through to disabled.
  }
  console.error(
    '[fengyu-sdk] no allowedOrigin resolved: the embedding shell must append ' +
      '?shellOrigin=<its origin> to the plugin URL (or the option must be passed). ' +
      'Refusing to bridge — no wildcard postMessage.',
  )
  return { origin: '*', bridging: false }
}

/** Correlation id that also works in opaque sandbox origins where Web Crypto is unavailable. */
export function createId(): string {
  const secureUuid = globalThis.crypto?.randomUUID
  if (secureUuid) return secureUuid.call(globalThis.crypto)
  fallbackIdSequence += 1
  return `fy_${Date.now().toString(36)}_${fallbackIdSequence.toString(36)}_${Math.random().toString(36).slice(2)}`
}

/**
 * Rebuild a value as a plain object/array tree so it is safe for {@link Window.postMessage}'s
 * structured-clone algorithm. Plugin UIs often pass Vue reactive values (a `ref()`/`reactive()`
 * FileRef, form state, …) straight into {@link FengYuClient.invoke}; those are Proxies that
 * structured clone CANNOT transfer and postMessage rejects with `DataCloneError` — every call
 * then fails silently. This walks the value and reconstructs it without Proxy wrappers (and
 * drops non-cloneable leaves like functions), so the host always receives plain JSON-like data.
 */
function toCloneable(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(toCloneable)
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {}
    for (const key of Object.keys(value as Record<string, unknown>)) {
      const entry = (value as Record<string, unknown>)[key]
      // Skip non-cloneable leaves (functions, symbols) — postMessage would reject them.
      if (typeof entry === 'function' || typeof entry === 'symbol') continue
      out[key] = toCloneable(entry)
    }
    return out
  }
  return value
}

export class FengYuClient {
  private readonly target: Window
  private readonly timeoutMs: number
  private readonly allowedOrigin: string
  /**
   * False when no origin could be resolved (no option, no ?shellOrigin param): the client
   * then refuses every request and ignores every message instead of bridging to '*' —
   * a wildcard would hand everything the user types into this frame, and forged
   * "responses", to ANY website that embeds this loopback-served page.
   */
  private readonly bridging: boolean
  private readonly pending = new Map<string, Pending>()
  private readonly handlers = new Map<string, Set<EventHandler>>()
  private readyPromise?: Promise<Environment>
  private environment?: Environment
  private pendingEnvironment: Partial<Environment> = {}
  private disposed = false

  constructor(options: FengYuClientOptions = {}) {
    this.target = options.target ?? window.parent
    this.timeoutMs = options.timeoutMs ?? 30_000
    const resolved = resolveAllowedOrigin(options.allowedOrigin)
    this.allowedOrigin = resolved.origin
    this.bridging = resolved.bridging
    window.addEventListener('message', this.onMessage)
  }

  async ready(options: InvokeOptions = {}): Promise<Environment> {
    if (this.environment) return { ...this.environment }
    if (!this.readyPromise) {
      this.readyPromise = this.request<Environment>(HOST_METHODS.ready, {}, options)
        .then(env => {
          if (env.protocolVersion !== PROTOCOL_VERSION) {
            throw new FengYuHostError({
              code: 'INCOMPATIBLE_PROTOCOL',
              message: `Incompatible FengYu protocol: host=${env.protocolVersion}, plugin=${PROTOCOL_VERSION}`,
            })
          }
          this.applyEnvironment(env, true)
          return { ...this.environment! }
        })
        .catch(error => {
          this.readyPromise = undefined
          throw error
        })
    }
    return this.readyPromise
  }

  /** Last environment received from ready/environment events; undefined before negotiation. */
  currentEnvironment(): Environment | undefined { return this.environment ? { ...this.environment } : undefined }

  invoke<T = unknown>(method: string, params: Record<string, unknown> = {}, options?: InvokeOptions): Promise<T> {
    return this.request<T>(HOST_METHODS.invoke, { method, params }, options)
  }
  notify(message: string): Promise<boolean> { return this.request(HOST_METHODS.notify, { message }) }
  files = {
    open: (options: { extensions?: string[]; filters?: FileFilter[] } = {}, request?: InvokeOptions) =>
      this.request<FileRef | null>(HOST_METHODS.filesOpen, options, request),
    inputDirectory: (request?: InvokeOptions) =>
      this.request<FileRef | null>(HOST_METHODS.filesInputDirectory, {}, request),
    workspaceDirectory: (request?: InvokeOptions) =>
      this.request<FileRef | null>(HOST_METHODS.filesWorkspaceDirectory, {}, request),
    outputDirectory: (request?: InvokeOptions) => this.request<FileRef | null>(HOST_METHODS.filesOutputDirectory, {}, request),
    export: (ref: FileRef, request?: InvokeOptions) => this.request<boolean>(HOST_METHODS.filesExport, ref, request),
  }

  on(event: string, handler: EventHandler): () => void {
    const set = this.handlers.get(event) ?? new Set<EventHandler>(); set.add(handler); this.handlers.set(event, set)
    return () => set.delete(handler)
  }

  request<T>(method: string, params: unknown = {}, options: InvokeOptions = {}): Promise<T> {
    if (this.disposed) return Promise.reject(new Error('FengYu client is disposed'))
    if (!this.bridging) {
      return Promise.reject(new FengYuHostError({
        code: 'PERMISSION_DENIED',
        message: 'FengYu bridge disabled: no allowedOrigin (shell must append ?shellOrigin=<its origin>)',
      }))
    }
    if (options.signal?.aborted) return Promise.reject(new FengYuHostError({ code: 'ABORTED', message: 'Aborted' }))
    // Capability pre-check (bullet 2): validate the host advertised the capability BEFORE we
    // post. ready() is the bootstrap handshake and runs before the environment is negotiated,
    // so it is exempt. Throws synchronously; convert to a rejected promise to keep the contract
    // that request() never throws.
    try {
      this.requireCapability(method)
    } catch (error) {
      return Promise.reject(error instanceof Error ? error : new Error(String(error)))
    }
    const id = createId()
    return new Promise<T>((resolve, reject) => {
      const settle = (action: () => void) => { this.takePending(id); action() }
      // Both timeout and abort post the cancel notification (identical wire format) and then
      // surface a typed FengYuHostError — TIMEOUT vs ABORTED respectively (bullet 4).
      const cancel = () => this.target.postMessage({ source: PLUGIN_MESSAGE_SOURCE, type: 'cancel', protocolVersion: PROTOCOL_VERSION, id }, this.allowedOrigin)
      const timer = setTimeout(() => settle(() => {
        cancel()
        reject(new FengYuHostError({ code: 'TIMEOUT', message: `Host request timed out: ${method}` }))
      }), options.timeoutMs ?? this.timeoutMs)
      const abort = options.signal ? () => settle(() => {
        cancel()
        reject(new FengYuHostError({ code: 'ABORTED', message: 'Aborted' }))
      }) : undefined
      options.signal?.addEventListener('abort', abort!, { once: true })
      this.pending.set(id, { resolve: resolve as (value: unknown) => void, reject, timer, signal: options.signal, abort })
      // Strip Proxy/reactivity wrappers before posting — postMessage's structured clone rejects
      // Vue reactive values with DataCloneError, silently breaking every invoke() call.
      this.target.postMessage({ source: PLUGIN_MESSAGE_SOURCE, type: 'request', protocolVersion: PROTOCOL_VERSION, id, method, params: toCloneable(params) }, this.allowedOrigin)
    })
  }

  /**
   * Verify the host advertised the capability for {@link method} in the negotiated environment.
   * Exempts {@link HOST_METHODS.ready} (the bootstrap that negotiates the environment) and any
   * call made before the environment is known. Throws {@link FengYuHostError} (code
   * `PERMISSION_DENIED`) when the capability is missing.
   */
  private requireCapability(method: string): void {
    if (method === HOST_METHODS.ready) return
    if (!this.environment) return
    if (!this.environment.capabilities.includes(method as HostMethod)) {
      throw new FengYuHostError({
        code: 'PERMISSION_DENIED',
        message: `Host did not grant capability for ${method}`,
      })
    }
  }

  private takePending(id: string): Pending | undefined {
    const item = this.pending.get(id)
    if (!item) return undefined
    this.pending.delete(id)
    clearTimeout(item.timer)
    if (item.signal && item.abort) item.signal.removeEventListener('abort', item.abort)
    return item
  }

  dispose(): void {
    if (this.disposed) return; this.disposed = true; window.removeEventListener('message', this.onMessage)
    for (const id of [...this.pending.keys()]) {
      const item = this.takePending(id)
      item?.reject(new Error('FengYu client disposed'))
    }
    this.handlers.clear()
  }

  /**
   * Inbound half of the origin pin. A file:// shell (packaged Electron builds — including the
   * Windows portable) reports {@code location.origin === 'file://'}, so that is the pinned value,
   * yet Chromium serializes that same parent's origin as the string 'null' in
   * {@code MessageEvent.origin} as seen from this frame. Without accepting both serializations
   * every host response and environment event is dropped inside packaged desktop builds:
   * {@link FengYuClient.ready} falls back after its timeout, every invoke times out, and
   * theme/locale never follow the host. Outbound posts keep targeting 'file://' — posting to the
   * literal 'null' throws a SyntaxError — so the pin is intentionally asymmetric. The
   * {@code event.source === this.target} check still constrains messages to the actual embedder,
   * so this adds no acceptance beyond the documented shellOrigin forgery limit.
   */
  private acceptsInboundOrigin(origin: string): boolean {
    if (this.allowedOrigin === '*') return true
    if (origin === this.allowedOrigin) return true
    return this.allowedOrigin === 'file://' && origin === 'null'
  }

  private onMessage = (event: MessageEvent): void => {
    if (!this.bridging) return
    if (event.source !== this.target || !this.acceptsInboundOrigin(event.origin)) return
    const message = event.data
    if (!isHostMessage(message)) return
    if (message.type === 'response') {
      // Unknown response id (a stranger not in `pending`) is dropped silently — do not
      // resolve/reject on behalf of an unrelated request (bullet 5).
      const item = this.takePending(message.id); if (!item) return
      message.error ? item.reject(new FengYuHostError(message.error)) : item.resolve(message.result)
    } else if (message.type === 'event') {
      if (message.event === 'environment') this.applyEnvironment(message.data)
      const data = message.event === 'environment' ? (this.currentEnvironment() ?? message.data) : message.data
      this.handlers.get(message.event)?.forEach(handler => handler(data))
    }
  }

  private applyEnvironment(value: Partial<Environment>, negotiated = false): void {
    if (this.environment) this.environment = { ...this.environment, ...value }
    else if (negotiated) {
      this.environment = { ...value, ...this.pendingEnvironment } as Environment
      this.pendingEnvironment = {}
    } else this.pendingEnvironment = { ...this.pendingEnvironment, ...value }
    if (value.theme) document.documentElement.dataset.theme = value.theme
    if (value.locale) document.documentElement.lang = value.locale
  }
}

export const fengyu = typeof window === 'undefined' ? undefined : new FengYuClient()
