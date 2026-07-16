export const SDK_VERSION = '1.0.0';
let fallbackIdSequence = 0;
/** Correlation id that also works in opaque sandbox origins where Web Crypto is unavailable. */
export function createId() {
    const secureUuid = globalThis.crypto?.randomUUID;
    if (secureUuid)
        return secureUuid.call(globalThis.crypto);
    fallbackIdSequence += 1;
    return `fy_${Date.now().toString(36)}_${fallbackIdSequence.toString(36)}_${Math.random().toString(36).slice(2)}`;
}
export class FengYuClient {
    target;
    timeoutMs;
    allowedOrigin;
    pending = new Map();
    handlers = new Map();
    disposed = false;
    constructor(options = {}) {
        this.target = options.target ?? window.parent;
        this.timeoutMs = options.timeoutMs ?? 30_000;
        this.allowedOrigin = options.allowedOrigin ?? '*';
        window.addEventListener('message', this.onMessage);
    }
    async ready() {
        const env = await this.request('host.ready', { sdkVersion: SDK_VERSION });
        if (env.sdkVersion && env.sdkVersion.split('.')[0] !== SDK_VERSION.split('.')[0]) {
            throw new Error(`Incompatible FengYu SDK: host=${env.sdkVersion}, plugin=${SDK_VERSION}`);
        }
        this.applyEnvironment(env);
        return env;
    }
    invoke(method, params = {}, options) {
        return this.request('rpc.invoke', { method, params }, options);
    }
    notify(message) { return this.request('notify', { message }); }
    files = {
        open: (options = {}, request) => this.request('files.open', options, request),
        inputDirectory: (request) => this.request('files.inputDirectory', {}, request),
        outputDirectory: (request) => this.request('files.outputDirectory', {}, request),
        export: (ref, request) => this.request('files.export', ref, request),
    };
    on(event, handler) {
        const set = this.handlers.get(event) ?? new Set();
        set.add(handler);
        this.handlers.set(event, set);
        return () => set.delete(handler);
    }
    request(method, params = {}, options = {}) {
        if (this.disposed)
            return Promise.reject(new Error('FengYu client is disposed'));
        if (options.signal?.aborted)
            return Promise.reject(new DOMException('Aborted', 'AbortError'));
        const id = createId();
        return new Promise((resolve, reject) => {
            const settle = (action) => { this.takePending(id); action(); };
            const timer = setTimeout(() => settle(() => reject(new Error(`Host request timed out: ${method}`))), options.timeoutMs ?? this.timeoutMs);
            const abort = options.signal ? () => settle(() => {
                reject(new DOMException('Aborted', 'AbortError'));
                this.target.postMessage({ source: 'fengyu-plugin', type: 'cancel', id }, this.allowedOrigin);
            }) : undefined;
            options.signal?.addEventListener('abort', abort, { once: true });
            this.pending.set(id, { resolve: resolve, reject, timer, signal: options.signal, abort });
            this.target.postMessage({ source: 'fengyu-plugin', type: 'request', sdkVersion: SDK_VERSION, id, method, params }, this.allowedOrigin);
        });
    }
    takePending(id) {
        const item = this.pending.get(id);
        if (!item)
            return undefined;
        this.pending.delete(id);
        clearTimeout(item.timer);
        if (item.signal && item.abort)
            item.signal.removeEventListener('abort', item.abort);
        return item;
    }
    dispose() {
        if (this.disposed)
            return;
        this.disposed = true;
        window.removeEventListener('message', this.onMessage);
        for (const id of [...this.pending.keys()]) {
            const item = this.takePending(id);
            item?.reject(new Error('FengYu client disposed'));
        }
        this.handlers.clear();
    }
    onMessage = (event) => {
        if (event.source !== this.target || (this.allowedOrigin !== '*' && event.origin !== this.allowedOrigin))
            return;
        const message = event.data;
        if (message?.source !== 'fengyu-host')
            return;
        if (message.type === 'response') {
            const item = this.takePending(message.id);
            if (!item)
                return;
            message.error ? item.reject(new Error(message.error)) : item.resolve(message.result);
        }
        else if (message.type === 'event') {
            if (message.event === 'environment')
                this.applyEnvironment(message.data);
            this.handlers.get(message.event)?.forEach(handler => handler(message.data));
        }
    };
    applyEnvironment(value) {
        if (value.theme)
            document.documentElement.dataset.theme = value.theme;
        if (value.locale)
            document.documentElement.lang = value.locale;
    }
}
export const fengyu = typeof window === 'undefined' ? undefined : new FengYuClient();
