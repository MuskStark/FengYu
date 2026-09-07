export declare const SDK_VERSION = "2.1.0";
import { type HostEnvironment, type HostError } from './protocol.js';
export * from './protocol.js';
export type FileAccess = 'read' | 'write' | 'read-write';
export interface FileRef {
    id: string;
    name: string;
    kind: 'file' | 'directory';
    access: FileAccess;
    size: number;
}
export interface FileFilter {
    name: string;
    extensions: string[];
}
export type Environment = HostEnvironment;
export interface InvokeOptions {
    signal?: AbortSignal;
    timeoutMs?: number;
}
export interface FengYuClientOptions {
    target?: Window;
    timeoutMs?: number;
    allowedOrigin?: string;
}
export declare class FengYuHostError extends Error {
    readonly code: HostError['code'];
    readonly details?: unknown;
    constructor(error: HostError);
}
type EventHandler = (data: unknown) => void;
/** Correlation id that also works in opaque sandbox origins where Web Crypto is unavailable. */
export declare function createId(): string;
export declare class FengYuClient {
    private readonly target;
    private readonly timeoutMs;
    private readonly allowedOrigin;
    /**
     * False when no origin could be resolved (no option, no ?shellOrigin param): the client
     * then refuses every request and ignores every message instead of bridging to '*' —
     * a wildcard would hand everything the user types into this frame, and forged
     * "responses", to ANY website that embeds this loopback-served page.
     */
    private readonly bridging;
    private readonly pending;
    private readonly handlers;
    private readyPromise?;
    private environment?;
    private pendingEnvironment;
    private disposed;
    constructor(options?: FengYuClientOptions);
    ready(options?: InvokeOptions): Promise<Environment>;
    /** Last environment received from ready/environment events; undefined before negotiation. */
    currentEnvironment(): Environment | undefined;
    invoke<T = unknown>(method: string, params?: Record<string, unknown>, options?: InvokeOptions): Promise<T>;
    notify(message: string): Promise<boolean>;
    files: {
        open: (options?: {
            extensions?: string[];
            filters?: FileFilter[];
        }, request?: InvokeOptions) => Promise<FileRef | null>;
        inputDirectory: (request?: InvokeOptions) => Promise<FileRef | null>;
        workspaceDirectory: (request?: InvokeOptions) => Promise<FileRef | null>;
        outputDirectory: (request?: InvokeOptions) => Promise<FileRef | null>;
        export: (ref: FileRef, request?: InvokeOptions) => Promise<boolean>;
    };
    on(event: string, handler: EventHandler): () => void;
    request<T>(method: string, params?: unknown, options?: InvokeOptions): Promise<T>;
    /**
     * Verify the host advertised the capability for {@link method} in the negotiated environment.
     * Exempts {@link HOST_METHODS.ready} (the bootstrap that negotiates the environment) and any
     * call made before the environment is known. Throws {@link FengYuHostError} (code
     * `PERMISSION_DENIED`) when the capability is missing.
     */
    private requireCapability;
    private takePending;
    dispose(): void;
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
    private acceptsInboundOrigin;
    private onMessage;
    private applyEnvironment;
}
export declare const fengyu: FengYuClient | undefined;
