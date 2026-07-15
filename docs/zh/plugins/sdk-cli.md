---
title: SDK 与 CLI
description: 参考 @fengyu/plugin-sdk TypeScript 客户端、FengYu-Plugin-Sdk Java worker 运行时，以及五个 fengyu plugin CLI 子命令——create、dev、build、validate、install。
lang: zh-CN
---

# SDK 与 CLI

插件作者使用两套 SDK（运行时两侧各一套）和一套 CLI。TypeScript SDK 运行在 iframe UI 中；Java Worker SDK 用来构建 `worker.jar`；`fengyu plugin` CLI 负责脚手架、开发、打包、校验和安装插件。

## `@fengyu/plugin-sdk`（TypeScript）

源码：`plugin-sdk/typescript/src/index.ts`。当前 SDK 版本为 `1.0.0`。导入单例 client 以及辅助方法/类型：

```ts
import { fengyu, FengYuClient, createId, type FileRef, type Environment } from '@fengyu/plugin-sdk'
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

制品 `FengYu-Plugin-Sdk`，包 `fan.summer.fengyu.sdk`。运行时是 `JsonRpcWorker`；处理器实现 `@FunctionalInterface PluginHandler`：

```java
Object handle(Map<String, Object> params) throws Exception
```

构建一个 worker 主类：

```java
public final class MyWorkerMain {
    private MyWorkerMain() {}
    public static void main(String[] args) throws Exception {
        new JsonRpcWorker()
            .on("hello", MyHandler::handle)         // 每次调用注册一个方法
            .run();                                  // 阻塞，读 stdin / 写 stdout
    }
}
```

- `on(method, handler)` 会拒绝重复和空白方法名。
- `run()` 在运行循环期间把 `System.out` 重定向到 `System.err`——保持协议输出的干净。
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

## `fengyu plugin` CLI

源码：`plugin-cli/src/cli.mjs`。用法：

```
fengyu plugin <create|dev|build|validate|install> [path] [options]
```

恰好有**五个**子命令——没有 `init`。

| 子命令 | 选项 | 说明 |
| --- | --- | --- |
| `create <path> --id <id>` | `--id`（必填）、`--no-install` | 基于 Vue/Vuetify 的 `vue-codex` 模板脚手架生成一个新插件目录：写入 `manifest.json`、`package.json`、`index.html`、`vite.config.ts` 与 `src/{main.ts,App.vue}`。默认执行 `npm install`；`--no-install` 可跳过。拒绝覆盖已存在的目录。 |
| `dev [path] [--port <n>]` | `--port`（默认 `4173`） | 启动一个回环开发宿主。对 Vue/Vite 项目会拉起 Vite（HMR）并提供一个指向它的模拟器；对静态项目则提供 `ui/` + SSE 热重载监听。两种方式都会模拟宿主的 `postMessage` 桥。 |
| `build [path] [--out <file>]` | `--out`（默认 `dist-package/<id>-<version>.fyp`） | 对 Vue/Vite 项目，先执行 `npm run build`（产出 `ui/`），再校验并打成 `.fyp`。对静态项目，跳过构建直接校验 + 打包。归档写入是原子的——失败时不会留下半成品 `.fyp`。 |
| `validate [path]` | — | 检查包的清单/结构错误；失败时以非零退出码加一条消息退出。 |
| `install <file> [--host <url>] [--token <t>]` | `--host`（默认 `http://127.0.0.1:24056`）、`--token`（默认 `$FENGYU_TOKEN`） | 把一个 `.fyp` 上传到市场的 `POST /api/plugin-market/upload`。 |

### 示例

```bash
# 脚手架生成（默认安装依赖；加 --no-install 可跳过）
fengyu plugin create ./my-plugin --id com.example.my-plugin

# 开发（Vue/Vite：拉起 Vite + 在 /__fengyu 提供模拟器）
fengyu plugin dev . --port 4173

# 打包（先跑前端构建，校验，原子化打 zip）
fengyu plugin build . --out dist-package/com.example.my-plugin-1.0.0.fyp

# 发布前做一次健全性检查
fengyu plugin validate

# 安装到一个运行中的宿主
fengyu plugin install ./com.example.my-plugin-1.0.0.fyp \
  --host http://127.0.0.1:24056 --token "$FENGYU_TOKEN"
```

脚手架生成的项目同时依赖 `@fengyu/plugin-sdk` 与 [`@fengyu/plugin-ui`](/zh/plugins/ui-components)；它的 `src/main.ts` 已经调用 `bindFengYuEnvironment` 同步主题/locale，并调用 `provideFengYuClient` 在全应用注入 SDK client。旧式静态插件（没有构建工具的纯 `ui/`）依然被 `dev` 与 `build` 接受。

## 下一步

- [入门](/zh/plugins/getting-started)——以叙述形式讲解 create + dev 循环。
- [UI 组件](/zh/plugins/ui-components)——`@fengyu/plugin-ui` Vuetify 套件。
- [Worker（JSON-RPC）](/zh/plugins/worker)——`JsonRpcWorker` 实现的协议。
- [构建与部署](/zh/plugins/build-deploy)——shaded-JAR + `.fyp` 流程。
