import { reactive } from 'vue'
import { FengYuClient, type Environment, type FileRef } from '@fengyu/plugin-sdk'
import { i18n } from './i18n'

export type RpcEnvelope<T = Record<string, unknown>> = T & { success: boolean; summary: string }

export const client = new FengYuClient()
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

export async function invoke<T extends Record<string, unknown>>(method: string, params: Record<string, unknown> = {}) {
  const result = await client.invoke<RpcEnvelope<T>>(method, cloneableParams(params))
  if (!result.success) throw new Error(result.summary || `Email operation failed: ${method}`)
  return result
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
