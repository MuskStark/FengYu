/**
 * Dev-only FileRef registry: maps an opaque FileRef id the iframe UI receives to a real
 * filesystem path the developer typed into the simulator's path prompt.
 *
 * The browser can't pop a native file picker, so when the plugin iframe calls
 * `files.open` / `files.inputDirectory` / `files.outputDirectory` the simulator renders a path
 * input, registers the typed path here, and hands the iframe a FileRef. When the iframe later
 * passes that FileRef as a parameter to `rpc.invoke`, `/__fengyu/rpc` rewrites the ref back to
 * the path string before forwarding to the worker — mirroring the production host's
 * `PluginProcessManager.resolveRefs`.
 *
 * @infinia/plugin-dev keeps this entirely in memory; it never persists across server restarts.
 */
export interface DevFileRef {
    id: string;
    name: string;
    kind: 'file' | 'directory';
    access: 'read' | 'write' | 'read-write';
    size: number;
}
export declare class FileRefRegistry {
    private readonly refs;
    register(path: string, kind: 'file' | 'directory', access: 'read' | 'write' | 'read-write'): DevFileRef;
    /** Resolve a single ref id to its path, or undefined if unregistered. */
    pathOf(id: string): string | undefined;
    /**
     * Recursively rewrite FileRef params to their registered filesystem path. A value is treated
     * as a FileRef when it is a plain object with an `id` starting `"ref_"` and a non-null `kind`.
     * Arrays and nested objects are recursed; primitives are returned unchanged.
     */
    resolve<T>(value: T): T;
    private resolveValue;
}
