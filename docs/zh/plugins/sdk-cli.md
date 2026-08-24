---
title: SDK 与 CLI
description: TypeScript 客户端、Java/Python/Go Worker SDK、IDE 模拟器与 Toolchain 2 CLI 参考。
lang: zh
---

# SDK 与 CLI

插件作者使用 iframe TypeScript SDK、三种 Worker SDK 之一、一套 Vite 模拟器 + DevKit，以及
`fengyu` CLI。Java、Python、Go Worker 共用协议版本 1 与同一个保留启动握手。Java Worker SDK
（`fan.summer.fengyu.sdk:fengyu-plugin-sdk:2.1.0`）相对于宿主应用独立版本化，并发布到 GitHub Packages。

## `@infinia/plugin-sdk`（TypeScript）

源码：`toolchain/sdk-ts/src/index.ts`。当前插件工具链版本为 `2.1.0`。导入单例 client 以及辅助方法/类型：

```ts
import { fengyu, FengYuClient, createId, type FileRef, type Environment } from '@infinia/plugin-sdk'
```

### `FengYuClient`

通往宿主的 `postMessage` 桥。可以用选项自行构造，或使用导出的 `fengyu` 单例。

| 成员 | 签名 | 说明 |
| --- | --- | --- |
| `ready(options?)` | `(InvokeOptions?) => Promise<Environment>` | 对协商去重，并要求协议精确为 `3.0.0`；应用、缓存 theme/locale。 |
| `currentEnvironment()` | `→ Environment \| undefined` | 无需访问宿主即可读取最近一次合并后的 ready/event 状态。 |
| `invoke<T>(method, params?, options?)` | `→ Promise<T>` | 对 worker 的 RPC；中止 `signal` 会把取消传递到宿主和 Worker。 |
| `notify(message)` | `→ Promise<boolean>` | 显示一个宿主 toast。 |
| `files.open(opts?, req?)` | `→ Promise<FileRef \| null>` | 单个文件。`{extensions?, filters?}`。需要权限 `files.read`。 |
| `files.inputDirectory(req?)` | `→ Promise<FileRef \| null>` | 输入目录。需要权限 `files.read`。 |
| `files.outputDirectory(req?)` | `→ Promise<FileRef \| null>` | 可写输出目录。需要权限 `files.write`。 |
| `files.export(ref, req?)` | `→ Promise<boolean>` | 打 zip + 下载。需要权限 `files.write`。 |
| `on(event, handler)` | `→ () => void` | 订阅；返回取消订阅函数。会发出 `environment` 更新。 |
| `dispose()` | `→ void` | 销毁监听器 + 拒绝未完成请求。 |

构造选项：`FengYuClientOptions { target?: Window（默认 window.parent）, timeoutMs?: 30_000, allowedOrigin?: '*' }`。

### 类型

```ts
type Theme = 'dark' | 'light'
type FileAccess = 'read' | 'write' | 'read-write'

interface FileRef     { id: string; name: string; kind: 'file'|'directory'; access: FileAccess; size: number }
interface FileFilter  { name: string; extensions: string[] }
interface Environment { protocolVersion: string; theme: Theme; locale: string; platform: 'web'|'desktop'; capabilities: HostMethod[] }
interface InvokeOptions { signal?: AbortSignal; timeoutMs?: number }
```

### `createId()`

`createId(): string`——`postMessage` 请求的关联 id。可用时使用 `crypto.randomUUID()`，在不透明沙箱来源（Web Crypto 不可用）下回退到一个确定性的基于计数器的 id。

## Java Worker SDK

制品 `fan.summer.fengyu.sdk:fengyu-plugin-sdk:2.1.0`（独立版本化，发布到 GitHub Packages）。包 `fan.summer.fengyu.sdk`。运行时是 `JsonRpcWorker`；处理器实现类型化 `@FunctionalInterface RpcHandler<I, O>`：

```java
O handle(I input, RpcContext ctx) throws Exception
```

`I` 与 `O` 是依据 `manifest.json` 的 `rpc.methods` 生成的 `*Input`/`*Output` 记录，方法名常量则集中在生成的 `PluginMethods` 中。把处理器注册抽到一个共享工厂里，让生产入口和 IDE 调试入口运行完全相同的代码：

```java
public final class MyWorker {
    private MyWorker() {}
    public static JsonRpcWorker create() {
        return new JsonRpcWorker()
            .method(PluginMethods.HELLO, HelloInput.class, HelloOutput.class,
                (HelloInput in, RpcContext ctx) -> MyHandler.handle(in, ctx));   // 每次调用注册一个方法
    }
}
```

生产入口——通过 stdin/stdout 传输 JSON-RPC（宿主驱动 worker 的方式）：

```java
public final class MyWorkerMain {
    public static void main(String[] args) throws Exception {
        MyWorker.create().run();                      // 阻塞，读 stdin / 写 stdout
    }
}
```

- `method(name, inputClass, outputClass, handler)` 会拒绝重复、空白方法名以及 `null` 处理器；它反序列化入参、绑定 `RpcContext`，再把返回的 `*Output` 序列化回响应。
- `run()` 在运行循环期间把 `System.out` 重定向到 `System.err`——保持协议输出的干净。
  `run(InputStream, OutputStream)`（接收显式输入/输出流的重载）也应用同样的重定向，因此两个 stdio
  入口都强制"stdout 仅用于 JSON-RPC"契约。
- 自带的 SLF4J provider 会把结构化事件写入 `stderr`；本地需要显式覆盖时可调用
  `PluginLogging.setLevel(...)`。生产环境由宿主提供 `FENGYU_LOG_LEVEL`，并自动更新运行中的 Worker。
- `serve(RpcTransport)`（1.1.0 新增）在任意传输层上驱动同一个 dispatch 循环，**不做**
  `System.setOut` 重定向——该行为仅属于 stdio 入口。devkit 的回环 TCP 服务器用 `serve()` 把你的处理器暴露给 IDE。
- 严格请求解析会暴露规范的 JSON-RPC 错误码：`-32700`（解析错误）、`-32600`（非法请求——方法缺失/空白或 `jsonrpc` 版本错误）、`-32601`（未知方法）和 `-32000`（处理器失败）。只要请求 `id` 可解析，就会被原样回传。
- 抛出 `RpcException(code, message)` 以返回结构化错误；其他异常都以 `-32000` 上报。
- 用 `maven-shade-plugin` 构建 shaded fat JAR；把 `mainClass` 设为你的 `*WorkerMain`。参见 [构建与部署](/zh/plugins/build-deploy)。

### 数据库环境

清单声明 `database` 权限后，宿主会向 Worker 注入 `FENGYU_DB_TYPE`、`FENGYU_DB_DRIVER`、
`FENGYU_DB_URL`、`FENGYU_DB_USERNAME`、`FENGYU_DB_PASSWORD` 和 `FENGYU_PLUGIN_DATA_DIR`。
最后一项默认指向稳定私有目录 `<运行目录>/.fengyu/plugin-data/<pluginId>/`。

```java
PluginDatabaseConfig database = PluginDatabaseConfig.fromEnvironment(System.getenv())
    .orElseThrow(() -> new IllegalStateException("database permission is required"));
```

这些环境变量只属于 Worker，不得转发给 iframe。插件自行负责迁移、表名前缀和凭据加密；
详见[插件数据库规范](/zh/plugins/database)。

## Python 与 Go Worker SDK

- `toolchain/sdk-python` 为 Python 3.12+ 提供 `fengyu_plugin_sdk.Worker`。通过
  `worker.on(name, handler)` 注册方法并调用 `worker.run()`；SDK 持有 stdout，并实现
  `$/fengyu/initialize`、取消、locale 元数据与结构化 JSON-RPC 错误。契约使用类型化
  dataclass、`Annotated[..., Field(...)]` 与 `Contract.rpc(...)` 声明。
- `toolchain/sdk-go` 为 Go 1.26+ 提供 `fengyu` package。通过
  `fengyu.New().On(name, handler)` 注册，并调用 `worker.Run()`；其握手、取消与
  换行分隔传输契约完全相同；Schema 使用带标签的 struct 与 `NewContract(...).RPC(...)` 声明。

两种脚手架都会把小型 runtime vendored 到生成项目中，因此第三方构建不依赖本地 FengYu
checkout。宿主也绝不执行 manifest 命令，只会启动 `backend/worker.py` 或
`backend/worker[.exe]`。

## IDE 开发

开发在编辑器里完成，不通过 CLI。脚手架生成的 `vite.config.ts` 加载了 `@infinia/plugin-dev`，
它把 Vite dev server 变成 FengYu 宿主模拟器：在 `/__fengyu` 提供一个 iframe 外壳（运行你
真实的插件 UI 并带 HMR），桥接 `@infinia/plugin-sdk` 的 `postMessage` 调用，并把 `rpc.invoke`
转发给开发 worker。

`fengyu dev` 会先提取代码优先契约并写出 Vite 实际读取的
`target/fengyu-manifest/manifest.json`。随后单独启动相应语言的开发 Worker；三者都在经过
令牌认证的 `127.0.0.1:24057` 上运行与生产环境相同的处理器。

```bash
# UI 侧（在 ui-src/ 下）
npm run dev                       # → http://127.0.0.1:5173/__fengyu

# Worker 侧（按项目语言选择）
Debug PluginDevMain.main()        # Java，在 IDE 中运行
cd worker && python3 worker.py --dev
cd worker && go run . --dev
```

纯 UI 插件可设 `mockWorker: true`（或省略 `workerEndpoint`）——`rpc.invoke` 会返回一个确定性
的桩响应，让你在 worker 还不存在时就能迭代 UI。完整指南见
[`toolchain/dev/README.md`](https://github.com/MuskStark/FengYu/tree/main/toolchain/dev)。如果配置了
`workerEndpoint`，连接失败会作为 RPC 错误返回，绝不会被 mock 响应静默替代。

## `fengyu` CLI

源码：`toolchain/cli/src/cli.mjs`。Toolchain 2 使用扁平且遵循约定的命令：

| 子命令 | 选项 | 说明 |
| --- | --- | --- |
| `init <path> --id <id>` | `--runtime java\|python\|go`、`--no-install`、`--ui-only` | 创建标准 Vue + Worker 项目或纯 UI 项目。 |
| `dev [path]` | — | 先提取契约/清单，再启动 UI 模拟器；另行启动 Java `PluginDevMain`、Python `worker.py --dev` 或 Go `go run . --dev` 以调试 Worker。 |
| `check [path]` | — | 不打包，校验 manifest（代码优先项目则编译合并后的 manifest）与标准 UI/Worker 布局。 |
| `generate [path]` | — | 仅限代码优先项目：运行契约提取（Maven `generate-resources`，`proc:only`），把合并后的 manifest 编译到 `target/fengyu-manifest/`，并再生成类型化 RPC 客户端与方法常量。绝不修改手写源码。 |
| `migrate manifest-codegen <path>` | — | 从 manifest-first 项目一次性生成草稿：拆出 `manifest.base.json`/Flow overlay/i18n，并生成注解化 Contract（DTO 命名与 manifest-first 生成器一致）。绝不删除 `manifest.json`——作者审阅后手动切换。 |
| `build [path]` | `--out <file>`、`--skip-tests` | 执行 npm/Maven 生命周期、校验 staging，并原子写入 `.fyp` 与校验和。 |
| `sign <file>` | `--key <private.pem>`、`--key-id <id>` | 为目录条目生成 Ed25519 `<file>.sig.json` sidecar。 |

不再支持旧版独立构建配置文件与任意命令数组。新 Worker 项目使用短小的
`manifest.base.json`、各语言自己的契约源码和 `ui-src/package.json`；`fengyu generate` 生成
完整清单与类型化 UI 绑定。Java 产出唯一的 `target/*-worker.jar`，Python 打包
`backend/worker.py`，Go 构建 `backend/worker`（Windows 为 `worker.exe`）。默认输出为
`dist/<id>-<version>.fyp`。

### 示例

```bash
# 脚手架生成（默认安装依赖；加 --no-install 可跳过）
fengyu init ./my-plugin --id com.example.my-plugin --runtime python

fengyu dev ./my-plugin
# 同时启动上面对应语言的开发 Worker。

# 打包（先跑前端构建，校验 staging，原子化打 zip）
fengyu check .
fengyu build . --out dist/com.example.my-plugin-1.0.0.fyp
fengyu sign dist/com.example.my-plugin-1.0.0.fyp --key publisher.pem --key-id example-2026
```

脚手架生成的项目同时依赖 `@infinia/plugin-sdk` 与 [`@infinia/plugin-ui`](/zh/plugins/ui-components)；它的 `src/main.ts` 调用 `mountFengYuApp`，统一持有环境同步、client 注入、挂载与 pagehide 销毁。

## 下一步

- [入门](/zh/plugins/getting-started)——以叙述形式讲解 create + IDE 调试循环。
- [UI 组件](/zh/plugins/ui-components)——`@infinia/plugin-ui` Vuetify 套件。
- [Worker（JSON-RPC）](/zh/plugins/worker)——`JsonRpcWorker` 实现的协议。
- [构建与部署](/zh/plugins/build-deploy)——shaded-JAR + `.fyp` 流程。
