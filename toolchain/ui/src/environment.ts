import { HOST_CAPABILITIES, PROTOCOL_VERSION, type Environment, type FengYuClient } from '@infinia/plugin-sdk'
import type { FengYuI18n } from './i18n'

export type FengYuEnvironmentBindingOptions = {
  /** Apply host environment updates (theme/locale already applied before this fires). */
  onEnvironment?: (environment: Environment) => void
  /** Called when the host ready handshake fails/times out (UI still renders with defaults). */
  onReadyError?: (error: unknown) => void
}

/** Map an Environment.theme value to the Infinia theme class set on `<html>`. */
export function themeClass(value?: string): 'light' | 'dark' {
  return value === 'light' ? 'light' : 'dark'
}

/** Map an Environment.locale (BCP-47-ish) to a FengYu i18n locale id. */
export function localeName(value?: string): string {
  return value?.trim().toLowerCase().startsWith('zh') ? 'zh' : 'en'
}

/**
 * Connect a {@link FengYuClient} to the document root and (optionally) an i18n
 * runtime: apply the current host environment once, then react to
 * `environment` events. Theme flips the `.dark` class on `<html>`; locale is
 * pushed into the i18n runtime. Returns the unsubscribe function so callers
 * can dispose the binding on teardown.
 *
 * Same lifecycle contract as the Vue 2.x kit: subscribe BEFORE the ready
 * handshake (the host may publish its first environment event as soon as the
 * iframe loads), and fall back to defaults after a short timeout when there
 * is no host (standalone `vite dev`) so the UI still renders.
 */
export async function bindFengYuEnvironment(
  client: FengYuClient,
  options: FengYuEnvironmentBindingOptions & { i18n?: FengYuI18n } = {},
): Promise<() => void> {
  const { i18n, onEnvironment, onReadyError } = options
  let current: Environment = {
    protocolVersion: PROTOCOL_VERSION,
    theme: 'dark',
    locale: 'en',
    platform: 'web',
    capabilities: HOST_CAPABILITIES,
    // Placeholder identity/permissions until the host's real `ready`
    // environment arrives; `apply(...)` merges the live values over these.
    pluginId: '',
    pluginVersion: '',
    permissions: [],
  }
  const apply = (environment: Partial<Environment>) => {
    current = { ...current, ...environment }
    document.documentElement.classList.toggle('dark', themeClass(current.theme) === 'dark')
    i18n?.applyEnvironment({ locale: current.locale })
    onEnvironment?.({ ...current })
  }
  const dispose = client.on('environment', (value) => apply(value as Partial<Environment>))
  try {
    apply(await client.ready({ timeoutMs: 3_000 }))
  } catch (error) {
    apply({})
    onReadyError?.(error)
  }
  return dispose
}
