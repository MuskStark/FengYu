import { createContext, useContext, type ReactNode } from 'react'
import type { FengYuClient } from '@infinia/plugin-sdk'

/**
 * React context carrying the per-app {@link FengYuClient}. `mountFengYuApp`
 * installs the provider at the root; any descendant resolves the client via
 * {@link useFengYuClient}.
 */
export const FengYuClientContext = createContext<FengYuClient | null>(null)
FengYuClientContext.displayName = 'FengYuClient'

export function FengYuClientProvider({ client, children }: { client: FengYuClient; children: ReactNode }) {
  return <FengYuClientContext.Provider value={client}>{children}</FengYuClientContext.Provider>
}

/**
 * Retrieve the {@link FengYuClient} provided at app root. Throws when no
 * client is in scope — surfacing wiring mistakes early instead of failing on
 * the first host round-trip.
 */
export function useFengYuClient(): FengYuClient {
  const client = useContext(FengYuClientContext)
  if (!client) {
    throw new Error(
      'useFengYuClient() must be called within a tree mounted by mountFengYuApp() (or an explicit FengYuClientProvider).',
    )
  }
  return client
}
