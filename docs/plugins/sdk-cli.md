# Plugin SDK and CLI

FengYu provides two SDKs and one cross-platform CLI for `.fyp` development.

## TypeScript UI SDK

Install `@fengyu/plugin-sdk` or let `fengyu plugin create` vendor its browser build into the
generated project. The SDK negotiates its major version with the host and provides typed requests,
timeouts, `AbortSignal` cancellation, environment events, file capabilities, and cleanup.

```ts
import { FengYuClient } from '@fengyu/plugin-sdk'

const fengyu = new FengYuClient({ timeoutMs: 30_000 })
const environment = await fengyu.ready()
const input = await fengyu.files.open({ extensions: ['xlsx'] })
const result = await fengyu.invoke('analyze', { input })
const off = fengyu.on('environment', value => console.log(value))

off()
fengyu.dispose()
```

## Java Worker SDK

Add `fan.summer.fengyu.sdk:fengyu-plugin-sdk:4.0.0-SNAPSHOT`, then register methods on
`JsonRpcWorker`. The SDK owns UTF-8 line framing, request parsing, dispatch, result serialization,
standard JSON-RPC errors, and per-call exception isolation.

```java
public static void main(String[] args) throws Exception {
    new JsonRpcWorker()
        .on("hello", params -> Map.of("message", "Hello " + params.get("name")))
        .run();
}
```

### Database environment contract

When the host grants a worker database access, it provides the following environment variables:

| Variable | Required | Meaning |
|---|---|---|
| `FENGYU_DB_TYPE` | Yes | Database type, such as `h2` |
| `FENGYU_DB_DRIVER` | Yes | JDBC driver class name |
| `FENGYU_DB_URL` | Yes | JDBC connection URL |
| `FENGYU_DB_USERNAME` | No | JDBC username; defaults to an empty string |
| `FENGYU_DB_PASSWORD` | No | JDBC password; defaults to an empty string |
| `FENGYU_PLUGIN_DATA_DIR` | Yes | Plugin-specific persistent data directory |

Use `PluginDatabaseConfig.fromEnvironment(System.getenv())` to parse the contract. It returns an
empty `Optional` when none of these variables are present and rejects partial configurations.
`PluginDatabaseConfig.toString()` always renders the password as `<redacted>`; validation errors
name missing variables but never echo environment values or secrets.

## CLI

```bash
fengyu plugin create my-plugin --id com.example.my-plugin
fengyu plugin dev my-plugin --port 4173
fengyu plugin validate my-plugin
fengyu plugin build my-plugin
fengyu plugin install my-plugin/dist-package/com.example.my-plugin-1.0.0.fyp \
  --host http://127.0.0.1:24056 --token "$FENGYU_TOKEN"
```

- `create` generates a valid manifest, standalone UI, and a vendored SDK runtime.
- `dev` starts a sandboxed host simulator with hot reload and a live RPC inspector.
- `validate` checks IDs, semver, UI/backend entries, permissions, AI schemas, and duplicates.
- `build` validates first, then creates a portable ZIP-format `.fyp` with an internal CRC32 writer.
- `install` uploads the package to a running FengYu host using its authenticated market API.

Node.js 20 or newer is required. Neither `build` nor `create` depends on a platform `zip`, Maven,
or shell-specific copy commands.
