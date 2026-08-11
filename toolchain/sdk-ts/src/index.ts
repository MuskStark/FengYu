// MUST stay in lockstep with the package version (package.json "version"). This is the single
// value sent on host.ready and used for the major-version compatibility gate below; if it drifts
// from the published version the gate becomes decorative. resolve-tooling-version.mjs asserts the
// six toolchain artifacts agree — this constant must agree with them too.
export const SDK_VERSION = '1.3.0'

export type Theme = 'dark' | 'light'
export type FileAccess = 'read' | 'write' | 'read-write'
export interface FileRef { id: string; name: string; kind: 'file' | 'directory'; access: FileAccess; size: number }
export interface FileFilter { name: string; extensions: string[] }
export interface Environment { sdkVersion?: string; theme: Theme; locale: string; platform?: 'web' | 'desktop'; capabilities?: string[] }
export interface InvokeOptions { signal?: AbortSignal; timeoutMs?: number }
export interface FengYuClientOptions { target?: Window; timeoutMs?: number; allowedOrigin?: string }

type Pending = {
  resolve(value: unknown): void
  reject(error: Error): void
  timer: ReturnType<typeof setTimeout>
  signal?: AbortSignal
  abort?: () => void
}
type EventHandler = (data: unknown) => void
let fallbackIdSequence = 0

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
  private readonly pending = new Map<string, Pending>()
  private readonly handlers = new Map<string, Set<EventHandler>>()
  private readyPromise?: Promise<Environment>
  private environment?: Environment
  private disposed = false

  constructor(options: FengYuClientOptions = {}) {
    this.target = options.target ?? window.parent
    this.timeoutMs = options.timeoutMs ?? 30_000
    this.allowedOrigin = options.allowedOrigin ?? '*'
    window.addEventListener('message', this.onMessage)
  }

  async ready(options: InvokeOptions = {}): Promise<Environment> {
    if (this.environment) return { ...this.environment }
    if (!this.readyPromise) {
      this.readyPromise = this.request<Environment>('host.ready', { sdkVersion: SDK_VERSION }, options)
        .then(env => {
          if (env.sdkVersion && env.sdkVersion.split('.')[0] !== SDK_VERSION.split('.')[0]) {
            throw new Error(`Incompatible FengYu SDK: host=${env.sdkVersion}, plugin=${SDK_VERSION}`)
          }
          this.applyEnvironment(env)
          return { ...env }
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
    return this.request<T>('rpc.invoke', { method, params }, options)
  }
  notify(message: string): Promise<boolean> { return this.request('notify', { message }) }
  files = {
    open: (options: { extensions?: string[]; filters?: FileFilter[] } = {}, request?: InvokeOptions) =>
      this.request<FileRef | null>('files.open', options, request),
    inputDirectory: (request?: InvokeOptions) =>
      this.request<FileRef | null>('files.inputDirectory', {}, request),
    workspaceDirectory: (request?: InvokeOptions) =>
      this.request<FileRef | null>('files.workspaceDirectory', {}, request),
    outputDirectory: (request?: InvokeOptions) => this.request<FileRef | null>('files.outputDirectory', {}, request),
    export: (ref: FileRef, request?: InvokeOptions) => this.request<boolean>('files.export', ref, request),
  }

  on(event: string, handler: EventHandler): () => void {
    const set = this.handlers.get(event) ?? new Set<EventHandler>(); set.add(handler); this.handlers.set(event, set)
    return () => set.delete(handler)
  }

  request<T>(method: string, params: unknown = {}, options: InvokeOptions = {}): Promise<T> {
    if (this.disposed) return Promise.reject(new Error('FengYu client is disposed'))
    if (options.signal?.aborted) return Promise.reject(new DOMException('Aborted', 'AbortError'))
    const id = createId()
    return new Promise<T>((resolve, reject) => {
      const settle = (action: () => void) => { this.takePending(id); action() }
      const timer = setTimeout(() => settle(() => reject(new Error(`Host request timed out: ${method}`))), options.timeoutMs ?? this.timeoutMs)
      const abort = options.signal ? () => settle(() => {
        reject(new DOMException('Aborted', 'AbortError'))
        this.target.postMessage({ source: 'fengyu-plugin', type: 'cancel', id }, this.allowedOrigin)
      }) : undefined
      options.signal?.addEventListener('abort', abort!, { once: true })
      this.pending.set(id, { resolve: resolve as (value: unknown) => void, reject, timer, signal: options.signal, abort })
      // Strip Proxy/reactivity wrappers before posting — postMessage's structured clone rejects
      // Vue reactive values with DataCloneError, silently breaking every invoke() call.
      this.target.postMessage({ source: 'fengyu-plugin', type: 'request', sdkVersion: SDK_VERSION, id, method, params: toCloneable(params) }, this.allowedOrigin)
    })
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

  private onMessage = (event: MessageEvent): void => {
    if (event.source !== this.target || (this.allowedOrigin !== '*' && event.origin !== this.allowedOrigin)) return
    const message = event.data
    if (message?.source !== 'fengyu-host') return
    if (message.type === 'response') {
      const item = this.takePending(message.id); if (!item) return
      message.error ? item.reject(new Error(message.error)) : item.resolve(message.result)
    } else if (message.type === 'event') {
      if (message.event === 'environment') this.applyEnvironment(message.data)
      const data = message.event === 'environment' ? (this.currentEnvironment() ?? message.data) : message.data
      this.handlers.get(message.event)?.forEach(handler => handler(data))
    }
  }

  private applyEnvironment(value: Partial<Environment>): void {
    if (this.environment) this.environment = { ...this.environment, ...value }
    else if (value.theme && value.locale) this.environment = value as Environment
    if (value.theme) document.documentElement.dataset.theme = value.theme
    if (value.locale) document.documentElement.lang = value.locale
  }
}

export const fengyu = typeof window === 'undefined' ? undefined : new FengYuClient()
