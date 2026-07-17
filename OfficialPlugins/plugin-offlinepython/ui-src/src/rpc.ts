import type { FengYuClient } from '@infinia/plugin-sdk'

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

export async function callChecked(
  client: FengYuClient,
  method: string,
  params: Record<string, unknown> = {},
): Promise<RpcResult> {
  const result = await call(client, method, params)
  if (!result.success) throw new Error(result.summary)
  return result
}

/** Read a typed field off an RpcResult payload (undefined if absent). */
export function field<T>(res: RpcResult, key: string): T | undefined {
  return res[key] as T | undefined
}
