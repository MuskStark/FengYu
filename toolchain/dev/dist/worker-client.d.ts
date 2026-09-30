/**
 * A minimal newline-delimited JSON-RPC 2.0 client that connects to a loopback TCP server
 * (fengyu-plugin-devkit's `PluginDevServer`). One line per request, one line per response,
 * matched by `id`.
 *
 * This is the development twin of the production stdio client that used to live in
 * `plugin-cli/src/worker.mjs`. It speaks the SAME JSON-RPC framing; only the transport differs
 * (TCP vs the worker's stdin/stdout). The dev server drives the worker's
 * `JsonRpcWorker.serve(LineFramedSocketTransport)` loop, so handler breakpoints fire directly
 * under the IDE — no JDWP remote attach.
 *
 * The connection is lazy and self-healing: a failed connect (dev server not started yet) is
 * surfaced as a per-call rejection so the simulator can expose the real failure. Each call opens
 * a fresh socket; this keeps the protocol stateless and lets the dev server's per-connection
 * virtual threads clean up naturally.
 */
export interface WorkerClientOptions {
    host: string;
    port: number;
    /** Per-call timeout. Defaults to 30s, matching the production host's callTimeoutSeconds range. */
    timeoutMs?: number;
}
export interface WorkerClient {
    invoke(method: string, params: Record<string, unknown>, options?: {
        timeoutMs?: number;
    }): Promise<unknown>;
}
export declare function createWorkerClient(options: WorkerClientOptions): WorkerClient;
/**
 * Probe whether a dev server is reachable. Resolves true on a successful TCP connect,
 * false otherwise. Cheap (opens + immediately closes a socket), used to decide whether to
 * report a precise connection error on the first request.
 */
export declare function probeWorker(host: string, port: number, timeoutMs?: number): Promise<boolean>;
