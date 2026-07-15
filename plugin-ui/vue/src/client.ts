import type { App } from 'vue'
import { inject, type InjectionKey } from 'vue'
import type { FengYuClient } from '@fengyu/plugin-sdk'

/**
 * Injection key for the per-app {@link FengYuClient}. Plugins call
 * {@link provideFengYuClient} from their root install and retrieve the client
 * anywhere in the component tree via {@link useFengYuClient}.
 */
export const FENGYU_CLIENT_KEY: InjectionKey<FengYuClient> = Symbol('fengyu-client')

/**
 * Provide a {@link FengYuClient} instance to an application so any descendant
 * component can resolve it through {@link useFengYuClient}.
 */
export function provideFengYuClient(app: App, client: FengYuClient): void {
  app.provide(FENGYU_CLIENT_KEY, client)
}

/**
 * Retrieve the {@link FengYuClient} provided at app root. Throws if no client
 * was provided — this surfaces wiring mistakes early instead of failing later
 * on the first host round-trip.
 */
export function useFengYuClient(): FengYuClient {
  const client = inject(FENGYU_CLIENT_KEY)
  if (!client) {
    throw new Error(
      'useFengYuClient() must be called within a component tree that called provideFengYuClient(app, client) at its root.',
    )
  }
  return client
}
