import type { ComponentType, ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import type { Environment, FengYuClient } from '@infinia/plugin-sdk'
import { FengYuClientProvider } from './client'
import { FengYuI18nProvider, createFengYuI18n, type FengYuI18n, type FengYuMessageTables } from './i18n'
import { bindFengYuEnvironment } from './environment'
import { NotifyProvider } from './notify'
import '@/styles/plugin-ui.css'

export type MountFengYuAppOptions = {
  /** Root React component (or a ready-made element) of the plugin UI. */
  root: ComponentType | ReactNode
  client: FengYuClient
  /** CSS selector or element to mount into. Defaults to `#app`. */
  target?: string | Element
  /** Flat-key message tables (`{ en: {...}, zh: {...} }`); wired to host locale. */
  messages?: FengYuMessageTables
  /** Escape hatch for plugin-specific providers; receives the default tree. */
  wrap?: (tree: ReactNode) => ReactNode
  onEnvironment?: (environment: Environment) => void
  onReadyError?: (error: unknown) => void
}

/**
 * Bootstrap and own the complete iframe UI lifecycle: client provider, i18n
 * (host-driven), theme binding, notification host, and the React root.
 * Returns an idempotent disposer.
 */
export async function mountFengYuApp(options: MountFengYuAppOptions): Promise<() => void> {
  const i18n: FengYuI18n | undefined = options.messages
    ? createFengYuI18n(options.messages)
    : undefined
  const disposeEnvironment = await bindFengYuEnvironment(options.client, {
    i18n,
    onEnvironment: options.onEnvironment,
    onReadyError: options.onReadyError,
  })

  const Root = typeof options.root === 'function' ? (options.root as ComponentType) : () => options.root as ReactNode
  let tree: ReactNode = (
    <FengYuClientProvider client={options.client}>
      <NotifyProvider client={options.client}>
        <Root />
      </NotifyProvider>
    </FengYuClientProvider>
  )
  if (i18n) {
    tree = <FengYuI18nProvider i18n={i18n}>{tree}</FengYuI18nProvider>
  }
  if (options.wrap) tree = options.wrap(tree)

  const targetSpec = options.target ?? '#app'
  const target =
    typeof targetSpec === 'string' ? document.querySelector(targetSpec) : targetSpec
  if (!target) {
    disposeEnvironment()
    options.client.dispose()
    throw new Error(`mountFengYuApp: mount target not found: ${String(targetSpec)}`)
  }

  let root: ReturnType<typeof createRoot>
  try {
    root = createRoot(target)
    root.render(tree)
  } catch (error) {
    disposeEnvironment()
    options.client.dispose()
    throw error
  }

  let disposed = false
  const dispose = () => {
    if (disposed) return
    disposed = true
    window.removeEventListener('pagehide', dispose)
    try {
      root.unmount()
    } finally {
      disposeEnvironment()
      options.client.dispose()
    }
  }
  window.addEventListener('pagehide', dispose, { once: true })
  return dispose
}
