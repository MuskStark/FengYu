export declare const SDK_VERSION = "1.2.0";
export type Theme = 'dark' | 'light';
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
export interface Environment {
    sdkVersion?: string;
    theme: Theme;
    locale: string;
    platform?: 'web' | 'desktop';
    capabilities?: string[];
}
export interface InvokeOptions {
    signal?: AbortSignal;
    timeoutMs?: number;
}
export interface FengYuClientOptions {
    target?: Window;
    timeoutMs?: number;
    allowedOrigin?: string;
}
type EventHandler = (data: unknown) => void;
/** Correlation id that also works in opaque sandbox origins where Web Crypto is unavailable. */
export declare function createId(): string;
export declare class FengYuClient {
    private readonly target;
    private readonly timeoutMs;
    private readonly allowedOrigin;
    private readonly pending;
    private readonly handlers;
    private disposed;
    constructor(options?: FengYuClientOptions);
    ready(): Promise<Environment>;
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
    private takePending;
    dispose(): void;
    private onMessage;
    private applyEnvironment;
}
export declare const fengyu: FengYuClient | undefined;
export {};
