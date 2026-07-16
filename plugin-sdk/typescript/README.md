# @infinia/plugin-sdk

Browser SDK for sandboxed FengYu `.fyp` plugin UIs. It provides typed RPC, file capabilities,
environment events, request timeouts, cancellation, version negotiation, and lifecycle cleanup.

```ts
import { fengyu } from '@infinia/plugin-sdk'
await fengyu!.ready()
const result = await fengyu!.invoke('render', { markdown: '# Hello' })
```
