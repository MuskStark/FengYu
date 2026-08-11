/**
 * Typed RPC surface for the Offline Python Builder UI.
 *
 * The generated {@link createPluginRpc} client (from `manifest.json` `rpc.methods`) replaces the
 * v1 string-based `client.invoke('method', ...)` calls. Every panel builds one `rpc` instance from
 * its host-provided `FengYuClient` and calls typed methods, passing an `AbortSignal` (via
 * `InvokeOptions`) so an in-flight call is transport-cancelled when the panel unmounts.
 *
 * The worker result envelope `{ success, summary, ...payload }` is expressed as fields of the
 * generated Output types; {@link checked} asserts `success` and throws the summary otherwise
 * (mirrors the legacy `callChecked`). {@link RpcResult} is the base structural shape used by the
 * shared job-snapshot reader.
 */
export { createPluginRpc } from './generated/fengyu-rpc'

/** Base envelope shape every worker result follows (generated Output types are structurally compatible). */
export interface RpcResult {
  success: boolean
  summary: string
  [k: string]: unknown
}

/**
 * Assert success and throw the worker summary otherwise. Use for calls where a `success:false`
 * envelope is an error the caller wants to surface via try/catch (init, save, build.start, ...).
 * Status-polling calls (build/deploy status) do NOT use this — a failed job is a normal snapshot.
 */
export function checked<T extends RpcResult>(r: T): T {
  if (!r.success) throw new Error(r.summary)
  return r
}
