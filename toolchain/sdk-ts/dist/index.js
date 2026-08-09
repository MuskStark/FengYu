// MUST stay in lockstep with the package version (package.json "version"). This is the single
// value sent on host.ready and used for the major-version compatibility gate below; if it drifts
// from the published version the gate becomes decorative. resolve-tooling-version.mjs asserts the
// six toolchain artifacts agree — this constant must agree with them too.
export const SDK_VERSION = '1.2.0';
let fallbackIdSequence = 0;
/** Correlation id that also works in opaque sandbox origins where Web Crypto is unavailable. */
export function createId() {
    const secureUuid = globalThis.crypto?.randomUUID;
    if (secureUuid)
        return secureUuid.call(globalThis.crypto);
    fallbackIdSequence += 1;
    return `fy_${Date.now().toString(36)}_${fallbackIdSequence.toString(36)}_${Math.random().toString(36).slice(2)}`;
}
/**
 * Rebuild a value as a plain object/array tree so it is safe for {@link Window.postMessage}'s
 * structured-clone algorithm. Plugin UIs often pass Vue reactive values (a `ref()`/`reactive()`
 * FileRef, form state, …) straight into {@link FengYuClient.invoke}; those are Proxies that
 * structured clone CANNOT transfer and postMessage rejects with `DataCloneError` — every call
 * then fails silently. This walks the value and reconstructs it without Proxy wrappers (and
 * drops non-cloneable leaves like functions), so the host always receives plain JSON-like data.
 */
function toCloneable(value) {
    if (Array.isArray(value))
        return value.map(toCloneable);
    if (value && typeof value === 'object') {
        const out = {};
        for (const key of Object.keys(value)) {
            const entry = value[key];
            // Skip non-cloneable leaves (functions, symbols) — postMessage would reject them.
            if (typeof entry === 'function' || typeof entry === 'symbol')
                continue;
            out[key] = toCloneable(entry);
        }
        return out;
    }
    return value;
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
        workspaceDirectory: (request) => this.request('files.workspaceDirectory', {}, request),
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
            // Strip Proxy/reactivity wrappers before posting — postMessage's structured clone rejects
            // Vue reactive values with DataCloneError, silently breaking every invoke() call.
            this.target.postMessage({ source: 'fengyu-plugin', type: 'request', sdkVersion: SDK_VERSION, id, method, params: toCloneable(params) }, this.allowedOrigin);
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
