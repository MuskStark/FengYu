export const SDK_VERSION = '1.0.0'

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

export class FengYuClient {
  private readonly target: Window
  private readonly timeoutMs: number
  private readonly allowedOrigin: string
  private readonly pending = new Map<string, Pending>()
  private readonly handlers = new Map<string, Set<EventHandler>>()
  private disposed = false

  constructor(options: FengYuClientOptions = {}) {
    this.target = options.target ?? window.parent
    this.timeoutMs = options.timeoutMs ?? 30_000
    this.allowedOrigin = options.allowedOrigin ?? '*'
    window.addEventListener('message', this.onMessage)
  }

  async ready(): Promise<Environment> {
    const env = await this.request<Environment>('host.ready', { sdkVersion: SDK_VERSION })
    if (env.sdkVersion && env.sdkVersion.split('.')[0] !== SDK_VERSION.split('.')[0]) {
      throw new Error(`Incompatible FengYu SDK: host=${env.sdkVersion}, plugin=${SDK_VERSION}`)
    }
    this.applyEnvironment(env)
    return env
  }

  invoke<T = unknown>(method: string, params: Record<string, unknown> = {}, options?: InvokeOptions): Promise<T> {
    return this.request<T>('rpc.invoke', { method, params }, options)
  }
  notify(message: string): Promise<boolean> { return this.request('notify', { message }) }
  files = {
    open: (options: { extensions?: string[]; filters?: FileFilter[] } = {}, request?: InvokeOptions) =>
      this.request<FileRef | null>('files.open', options, request),
    inputDirectory: (request?: InvokeOptions) =>
      this.request<FileRef | null>('files.inputDirectory', {}, request),
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
      this.target.postMessage({ source: 'fengyu-plugin', type: 'request', sdkVersion: SDK_VERSION, id, method, params }, this.allowedOrigin)
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
      this.handlers.get(message.event)?.forEach(handler => handler(message.data))
    }
  }

  private applyEnvironment(value: Partial<Environment>): void {
    if (value.theme) document.documentElement.dataset.theme = value.theme
    if (value.locale) document.documentElement.lang = value.locale
  }
}

export const fengyu = typeof window === 'undefined' ? undefined : new FengYuClient()
