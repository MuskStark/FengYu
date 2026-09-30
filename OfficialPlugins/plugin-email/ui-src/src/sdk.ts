import { reactive } from 'vue'
import { FengYuClient, type Environment, type FileRef } from '@infinia/plugin-sdk'
import { i18n } from './i18n'
import { createPluginRpc } from './generated/fengyu-rpc'

export const client = new FengYuClient()

/**
 * Typed RPC surface built from the generated client (one method per `manifest.json` `rpc.methods`
 * entry). Replaces the v1 string-based method-dispatch wrapper.
 */
export const rpc = createPluginRpc(client)
const environment = reactive({ theme: 'light', locale: 'en' })
let unsubscribe: (() => void) | undefined

export function applyEnvironment(value: Partial<Environment>): void {
  if (value.theme) environment.theme = value.theme
  if (value.locale) environment.locale = value.locale
  document.documentElement.dataset.theme = environment.theme
  document.documentElement.lang = environment.locale
}

export function readEnvironment() { return { theme: environment.theme, locale: environment.locale } }
export function useEnvironment() { return environment }

export async function initializeSdk(): Promise<void> {
  applyEnvironment(await client.ready())
  unsubscribe = client.on('environment', data => applyEnvironment(data as Partial<Environment>))
}

export function disposeSdk(): void { unsubscribe?.(); client.dispose() }

export function cloneableParams(params: Record<string, unknown>): Record<string, unknown> {
  return JSON.parse(JSON.stringify(params)) as Record<string, unknown>
}

/**
 * Awaits a generated-RPC promise and asserts its success envelope. The manifest now declares a
 * complete, strongly-typed `outputSchema` for every method, so `rpc.method(...)` already resolves
 * to the generated `<Method>Output` (success/summary + payload); this wrapper only throws on
 * `{ success:false }` (surfacing the worker's summary) and returns the typed result unchanged — no
 * caller-side cast is needed.
 */
export async function checked<T extends { success: boolean; summary: string }>(p: Promise<T>): Promise<T> {
  const r = await p
  if (!r.success) throw new Error(r.summary || 'Email operation failed')
  return r
}

export const files = {
  open: (): Promise<FileRef | null> => client.files.open(),
  inputDirectory: (): Promise<FileRef | null> => client.files.inputDirectory(),
  outputDirectory: (): Promise<FileRef | null> => client.files.outputDirectory(),
}

export function actionable(error: unknown, action: string): string {
  let detail: string | undefined
  if (error instanceof Error && error.message.trim()) detail = error.message
  else if (error && typeof error === 'object') {
    const value = error as Record<string, unknown>
    if (typeof value.summary === 'string' && value.summary.trim()) detail = value.summary
    else if (typeof value.message === 'string' && value.message.trim()) detail = value.message
  }
  return detail
    ? i18n.global.t('errors.actionFailed', { action, detail })
    : i18n.global.t('errors.unknown')
}
