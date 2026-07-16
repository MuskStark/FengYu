import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'

/**
 * Typed wrappers around the worker JSON-RPC surface. Every worker result follows the
 * {success, summary, ...payload} contract.
 */

export interface RpcResult {
  success: boolean
  summary: string
  // The payload is free-form per method; panels narrow it with `field()`.
  [k: string]: unknown
}

/**
 * Invoke a worker method. `FengYuClient.invoke(method, params)` posts `rpc.invoke` to the host,
 * which bridges the call to the worker process over JSON-RPC and returns its `result`.
 */
export async function call(
  client: FengYuClient,
  method: string,
  params: Record<string, unknown> = {},
): Promise<RpcResult> {
  return client.invoke<RpcResult>(method, params)
}

/** Read a typed field off an RpcResult payload (undefined if absent). */
export function field<T>(res: RpcResult, key: string): T | undefined {
  return res[key] as T | undefined
}

/**
 * Extract a usable path string for the worker from a FileRef. The host resolves FileRef objects
 * to filesystem path strings before params reach the worker, and the worker's `requiredPath()`
 * accepts either a plain string or a leftover `{id}` map — so passing the FileRef's `id` (or the
 * resolved path) both work. Returns null when nothing was picked.
 */
export function refPath(ref: FileRef | string | null | undefined): string | null {
  if (!ref) return null
  if (typeof ref === 'string') return ref
  return ref.id ?? null
}
