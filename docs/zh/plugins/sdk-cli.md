---
title: SDK 与 CLI
description: 共享协议 TypeScript 客户端、Java Worker SDK 1.3.0、IDE 模拟器与 Toolchain 2 CLI 参考。
lang: zh
---

# SDK 与 CLI

插件作者使用两套 SDK、一套 Vite 模拟器 + DevKit，以及 `fengyu` CLI。TypeScript SDK 从 sandbox
iframe 使用共享协议；Java SDK 运行进程外 Worker。Java Worker SDK
（`fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.3.0`）相对于宿主应用独立版本化，并发布到 GitHub Packages。

## `@infinia/plugin-sdk`（TypeScript）

源码：`toolchain/sdk-ts/src/index.ts`。当前插件工具链版本为 `1.3.0`。导入单例 client 以及辅助方法/类型：

```ts
import { fengyu, FengYuClient, createId, type FileRef, type Environment } from '@infinia/plugin-sdk'
```

### `FengYuClient`

通往宿主的 `postMessage` 桥。可以用选项自行构造，或使用导出的 `fengyu` 单例。

| 成员 | 签名 | 说明 |
| --- | --- | --- |
| `ready(options?)` | `(InvokeOptions?) => Promise<Environment>` | 对协商去重，并要求协议精确为 `2.0.0`；应用、缓存 theme/locale。 |
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

制品 `fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.3.0`（独立版本化，发布到 GitHub Packages）。包 `fan.summer.fengyu.sdk`。运行时是 `JsonRpcWorker`；处理器实现类型化 `@FunctionalInterface RpcHandler<I, O>`：

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

## IDE 开发

开发在编辑器里完成，不通过 CLI。脚手架生成的 `vite.config.ts` 加载了 `@infinia/plugin-dev`，
它把 Vite dev server 变成 FengYu 宿主模拟器：在 `/__fengyu` 提供一个 iframe 外壳（运行你
真实的插件 UI 并带 HMR），桥接 `@infinia/plugin-sdk` 的 `postMessage` 调用，并把 `rpc.invoke`
转发给开发 worker。

对于 worker，在 IDE 里用 **Debug** 运行 `PluginDevMain.main()`（脚手架生成在
`worker/src/test/java/...`）。它会启动 `fengyu-plugin-devkit` 的回环 TCP 服务器
（`127.0.0.1:24057`），提供与生产 worker **相同的处理器**——所以你在 `JsonRpcWorker` 处理器
里设的断点会直接命中，无需 JDWP 远程附加。devkit 是 test scope 依赖，绝不会打进生产 shaded JAR。

```bash
# UI 侧（在 ui-src/ 下）
npm run dev                       # → http://127.0.0.1:5173/__fengyu

# Worker 侧（在你的 IDE 里）
Debug PluginDevMain.main()        # → 监听 127.0.0.1:24057
```

纯 UI 插件可设 `mockWorker: true`（或省略 `workerEndpoint`）——`rpc.invoke` 会返回一个确定性
的桩响应，让你在 worker 还不存在时就能迭代 UI。完整指南见
[`toolchain/dev/README.md`](https://github.com/MuskStark/FengYu/tree/main/toolchain/dev)。如果配置了
`workerEndpoint`，连接失败会作为 RPC 错误返回，绝不会被 mock 响应静默替代。

## `fengyu` CLI

源码：`toolchain/cli/src/cli.mjs`。Toolchain 2 使用扁平且遵循约定的命令：

| 子命令 | 选项 | 说明 |
| --- | --- | --- |
| `init <path> --id <id>` | `--no-install`、`--ui-only` | 创建标准 Vue + Java 项目或纯 UI 项目。 |
| `dev [path]` | — | 通过标准 `npm run dev` 启动 UI 模拟器；Java 断点仍单独 Debug `PluginDevMain`。 |
| `check [path]` | — | 不打包，校验 manifest 与标准 UI/Worker 布局。 |
| `build [path]` | `--out <file>`、`--skip-tests` | 执行 npm/Maven 生命周期、校验 staging，并原子写入 `.fyp` 与校验和。 |

不再支持旧版独立配置文件（已统一为 `manifest.json`）与任意命令数组。标准布局使用 `ui-src/package.json` 和
`worker/pom.xml`（或根 `pom.xml`）；Worker 必须产出唯一的 `target/*-worker.jar`。默认输出为
`dist/<id>-<version>.fyp`。

### 示例

```bash
# 脚手架生成（默认安装依赖；加 --no-install 可跳过）
fengyu init ./my-plugin --id com.example.my-plugin

fengyu dev ./my-plugin
# Java Worker 同时在 IDE 中 Debug PluginDevMain。

# 打包（先跑前端构建，校验 staging，原子化打 zip）
fengyu check .
fengyu build . --out dist/com.example.my-plugin-1.0.0.fyp
```

脚手架生成的项目同时依赖 `@infinia/plugin-sdk` 与 [`@infinia/plugin-ui`](/zh/plugins/ui-components)；它的 `src/main.ts` 调用 `mountFengYuApp`，统一持有环境同步、client 注入、挂载与 pagehide 销毁。

## 下一步

- [入门](/zh/plugins/getting-started)——以叙述形式讲解 create + IDE 调试循环。
- [UI 组件](/zh/plugins/ui-components)——`@infinia/plugin-ui` Vuetify 套件。
- [Worker（JSON-RPC）](/zh/plugins/worker)——`JsonRpcWorker` 实现的协议。
- [构建与部署](/zh/plugins/build-deploy)——shaded-JAR + `.fyp` 流程。
