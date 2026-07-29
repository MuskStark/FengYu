---
title: SDK 与 CLI
description: "@infinia/plugin-sdk TypeScript 客户端、独立版本化的 Java Worker SDK（1.1.0）、用于 IDE 调试的 @infinia/plugin-dev Vite 插件 + fengyu-plugin-devkit，以及两个 fengyu plugin CLI 子命令——create、build 的参考。"
lang: zh
---

# SDK 与 CLI

插件作者使用两套 SDK（运行时两侧各一套）、一套用于 IDE 调试的 Vite 开发插件 + devkit，以及一套 CLI。TypeScript SDK 运行在 iframe UI 中；Java Worker SDK 用来构建 `worker.jar`；`@infinia/plugin-dev` + `fengyu-plugin-devkit` 把你的编辑器变成 FengYu 宿主模拟器，让你用断点调试 UI 和 worker；`fengyu plugin` CLI 负责脚手架（`create`）和打包（`build`）——开发在 IDE 里完成。Java Worker SDK（`fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.1.0`）相对于宿主应用**独立版本化**，并发布到 GitHub Packages。

## `@infinia/plugin-sdk`（TypeScript）

源码：`toolchain/sdk-ts/src/index.ts`。当前 SDK 版本为 `1.1.0`。导入单例 client 以及辅助方法/类型：

```ts
import { fengyu, FengYuClient, createId, type FileRef, type Environment } from '@infinia/plugin-sdk'
```

### `FengYuClient`

通往宿主的 `postMessage` 桥。可以用选项自行构造，或使用导出的 `fengyu` 单例。

| 成员 | 签名 | 说明 |
| --- | --- | --- |
| `ready()` | `() => Promise<Environment>` | 协商 `sdkVersion`；**主版本不匹配时抛出异常**。把 theme/locale 应用到文档。 |
| `invoke<T>(method, params?, options?)` | `→ Promise<T>` | 对 worker 的 RPC。`InvokeOptions { signal?, timeoutMs? }`。 |
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
interface Environment { sdkVersion?: string; theme: Theme; locale: string; platform?: 'web'|'desktop'; capabilities?: string[] }
interface InvokeOptions { signal?: AbortSignal; timeoutMs?: number }
```

### `createId()`

`createId(): string`——`postMessage` 请求的关联 id。可用时使用 `crypto.randomUUID()`，在不透明沙箱来源（Web Crypto 不可用）下回退到一个确定性的基于计数器的 id。

## Java Worker SDK

制品 `fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.1.0`（独立版本化，发布到 GitHub Packages）。包 `fan.summer.fengyu.sdk`。运行时是 `JsonRpcWorker`；处理器实现 `@FunctionalInterface PluginHandler`：

```java
Object handle(Map<String, Object> params) throws Exception
```

把处理器注册抽到一个共享工厂里，让生产入口和 IDE 调试入口运行完全相同的代码：

```java
public final class MyWorker {
    private MyWorker() {}
    public static JsonRpcWorker create() {
        return new JsonRpcWorker()
            .on("hello", MyHandler::handle);         // 每次调用注册一个方法
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

- `on(method, handler)` 会拒绝重复、空白方法名以及 `null` 处理器。
- `run()` 在运行循环期间把 `System.out` 重定向到 `System.err`——保持协议输出的干净。
- 自带的 SLF4J provider 会把结构化事件写入 `stderr`；本地需要显式覆盖时可调用
  `PluginLogging.setLevel(...)`。生产环境由宿主提供 `FENGYU_LOG_LEVEL`，并自动更新运行中的 Worker。
- `serve(RpcTransport)`（1.1.0 新增）在任意传输层上驱动同一个 dispatch 循环。`run()` 和 `run(InputStream, OutputStream)` 行为不变；devkit 的回环 TCP 服务器用 `serve()` 把你的处理器暴露给 IDE。
- 严格请求解析会暴露规范的 JSON-RPC 错误码：`-32700`（解析错误）、`-32600`（非法请求——方法缺失/空白或 `jsonrpc` 版本错误）、`-32601`（未知方法）和 `-32000`（处理器失败）。只要请求 `id` 可解析，就会被原样回传。
- 抛出 `JsonRpcWorker.RpcException(code, message)` 以返回结构化错误；其他异常都以 `-32000` 上报。
- 辅助方法：`JsonRpcWorker.string(params, key)`、`JsonRpcWorker.integer(params, key, fallback)`。
- 用 `maven-shade-plugin` 构建 shaded fat JAR；把 `mainClass` 设为你的 `*WorkerMain`。参见 [构建与部署](/zh/plugins/build-deploy)。

### 数据库环境

清单声明 `database` 权限后，宿主会向 Worker 注入 `FENGYU_DB_TYPE`、`FENGYU_DB_DRIVER`、
`FENGYU_DB_URL`、`FENGYU_DB_USERNAME`、`FENGYU_DB_PASSWORD` 和 `FENGYU_PLUGIN_DATA_DIR`。
最后一项默认指向稳定私有目录 `~/.fengyu/plugin-data/<pluginId>/`。

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

## `fengyu plugin` CLI

源码：`toolchain/cli/src/cli.mjs`。CLI 只负责脚手架和打包——开发和校验都在别处完成（IDE 做开发；
`build` 自动校验）。用法：

```
fengyu plugin <create|build> [path] [options]
```

恰好有**两个**子命令。

| 子命令 | 选项 | 说明 |
| --- | --- | --- |
| `create <path> --id <id>` | `--id`（必填）、`--no-install`、`--ui-only` | 脚手架生成一个新插件。默认产出一个完整的 Vue + Java 项目（`vue-java` 模板）：`manifest.json`、`fengyu.plugin.json`、`ui-src/`（Vue，`vite.config.ts` 已接入 `@infinia/plugin-dev`）、`worker/`（Java + Maven Wrapper，`PluginDevMain` 已生成在 `src/test/java` 下）以及 `.mvn/settings.xml`。`--ui-only` 保留轻量的纯 UI 模板。默认运行 `npm install`（`--no-install` 可跳过）。拒绝覆盖已存在的目录。 |
| `build [path] [--out <file>]` | `--out`（默认 `dist-package/<id>-<version>.fyp`）、`--skip-tests` | 对于声明式项目，运行完整的分阶段生命周期（prepare → install → test → build → **校验 staging** → package）。`--skip-tests` 仅跳过测试——绝不跳过类型检查、校验或打包。零配置的 Vue/Vite 与静态项目保留其现有的构建探测。归档写入是原子的——失败时不会留下半成品 `.fyp`、`.tmp-*` 或 staging 目录。 |

::: tip `dev` / `validate` / `install` 去哪了？
`fengyu plugin dev` 迁移到了 IDE，通过 `@infinia/plugin-dev` + `fengyu-plugin-devkit` 实现
（见上方 [IDE 开发](#ide-开发)）——你得到的是真实断点，而不是一个 CLI 管理的进程。`validate`
现在是 `build` 的内建步骤（staging 树在打包前总会被校验）。`install` 通过宿主的插件市场 UI
完成（`POST /api/plugin-market/upload`）；详见[插件市场](/zh/plugins/marketplace)。
:::

### 示例

```bash
# 脚手架生成（默认安装依赖；加 --no-install 可跳过）
fengyu plugin create ./my-plugin --id com.example.my-plugin

# 开发：在 IDE 里打开项目，然后
#   UI:    cd ui-src && npm run dev
#   Worker: Debug PluginDevMain（见上方“IDE 开发”）

# 打包（先跑前端构建，校验 staging，原子化打 zip）
fengyu plugin build . --out dist-package/com.example.my-plugin-1.0.0.fyp
```

脚手架生成的项目同时依赖 `@infinia/plugin-sdk` 与 [`@infinia/plugin-ui`](/zh/plugins/ui-components)；它的 `src/main.ts` 已经调用 `bindFengYuEnvironment` 同步主题/locale，并调用 `provideFengYuClient` 在全应用注入 SDK client。旧式静态插件（没有构建工具的纯 `ui/`）依然被 `build` 接受。

## 下一步

- [入门](/zh/plugins/getting-started)——以叙述形式讲解 create + IDE 调试循环。
- [UI 组件](/zh/plugins/ui-components)——`@infinia/plugin-ui` Vuetify 套件。
- [Worker（JSON-RPC）](/zh/plugins/worker)——`JsonRpcWorker` 实现的协议。
- [构建与部署](/zh/plugins/build-deploy)——shaded-JAR + `.fyp` 流程。
