---
title: Worker（JSON-RPC）
description: FengYu 插件 worker 是一个进程外的可执行文件，通过 stdio 上的换行分隔 JSON-RPC 2.0 通信，其中 FileRef 在派发之前由宿主解析为绝对路径。
lang: zh-CN
---

# Worker（JSON-RPC）

worker 是插件后端，可以是 Java 21 shaded JAR、Python 3.12+ 脚本或 Go 1.26+ 原生可执行
文件。宿主将其作为**独立的操作系统进程**启动，并通过 stdio 上换行分隔的
**JSON-RPC 2.0** 消息驱动。worker 永远不会驻留在宿主 Spring 上下文中。

## 协议

宿主发送一个请求，并按行读取一个响应：

```jsonc
// host → worker（stdin 上的一行）
{"jsonrpc":"2.0","id":"req-1","method":"render","params":{"markdown":"# hi"},"_fengyu":{"locale":"zh"}}

// worker → host（stdout 上的一行）
{"jsonrpc":"2.0","id":"req-1","result":{"success":true,"html":"<h1>hi</h1>"}}
```

| 消息 | 结构 |
| --- | --- |
| 请求 | `{jsonrpc:"2.0", id, method, params, _fengyu?}` |
| 响应（成功） | `{jsonrpc:"2.0", id, result}` |
| 响应（错误） | `{jsonrpc:"2.0", id, error:{code, message}}` |

消息是**换行分隔**的：`stdin` 上每行一个 JSON 对象，`stdout` 上每行一个。`id` 用于把响应与其请求关联起来。

可选的顶层 `_fengyu` 对象是一个**保留的、宿主拥有的元数据信封**——参见[保留元数据通道](#保留元数据通道)。插件必须把任何以 `_fengyu` 开头的帧根键视为宿主拥有，且不得将其声明为方法输入。

## 启动握手与运维状态

新 manifest 设置 `backend.protocolVersion: 1`。任何插件方法可用前，宿主先以宿主/插件版本及
能力调用保留方法 `$/fengyu/initialize`。SDK 返回自己的协议与 runtime（`java`、`python`、
`go`）；不匹配会令启动失败，更新流程会回滚到上一份健康包。

运行状态可通过 `GET /api/plugin-runtime/status` 与
`GET /api/plugin-runtime/{id}/status` 查询。状态包括 `STOPPED`、`STARTING`、`HEALTHY`、
`DEGRADED`、`BACKOFF`、`FAILED`、`UPDATING`、`DISABLED`；故障按兼容性、完整性/签名、
spawn/握手/协议、超时/崩溃、沙箱、资源、权限分类。三次快速启动崩溃会启用指数惰性重启退避：
30、60、120、240 秒，最多 300 秒。

`backend.resources.memoryMb` 与 `maxProcesses` 限制完整 worker 进程树。Linux/macOS 由宿主
watchdog 强制，Windows 使用内核 Job Object 内存/进程限制；越界会终止整棵进程树并记录
`RESOURCE_LIMIT`。

## 保留元数据通道

除标准 JSON-RPC 2.0 字段（`jsonrpc`、`id`、`method`、`params`）外，宿主可附加一个顶层 `_fengyu` 对象，携带宿主拥有的传输级元数据。它**绝不**属于 `params`，因此不会与插件方法自身的输入字段冲突。Worker SDK 读取它并绑定到本次调用的上下文；插件通过 `RpcContext` 读取，绝不直接从帧中读取。

| 字段 | 含义 |
| --- | --- |
| `_fengyu.locale` | 请求的 locale（如 `"zh"`、`"en"`），绑定到 `RpcContext.locale()` 与 `WorkerLocale`，使消息束解析遵循调用方的语言。当宿主对该调用没有 locale 时省略（worker 随后默认英文）。 |

`RpcContext.callId()` 暴露 JSON-RPC 请求 ID。普通调用使用唯一 ID；Flow 运行使用稳定的
`<根运行>:step:<索引>`，重启恢复后仍复用它。具备幂等保证的写入/外部处理器应把该键与
副作用/结果一起持久化，再次看到同一键时返回已记录结果；稳定 ID 本身不会自动让处理器幂等。

> **保留键。** 任何以 `_fengyu` 开头的帧根键都由宿主拥有。插件方法完全可以在其 `inputSchema` 中声明名为 `locale`（或任何其他非保留名称）的参数；它从 `params` 反序列化，绝不会被请求 locale 覆盖。Worker SDK 仍接受遗留的 `params.locale` 键作为回退，以便尚未采用 `_fengyu` 信封的宿主在滚动升级期间继续工作。

> **日志走 stderr。** `stdout` 专用于协议消息。Worker SDK 通过在运行循环期间把 `System.out` 重定向到 `System.err` 来强制这一点——参见 [常见陷阱](/zh/plugins/pitfalls)。

## 日志

Java 插件代码直接使用标准 SLF4J API：

```java
private static final Logger log = LoggerFactory.getLogger(MyHandler.class);

log.debug("Loaded {} rows", rowCount);
log.error("Export failed", exception);
```

SDK 自带 Worker 专用的 SLF4J provider。每个事件会作为一行结构化数据写入 `stderr`，完整保留
`TRACE`、`DEBUG`、`INFO`、`WARN`、`ERROR`，以及 logger 名称、线程、格式化消息和异常栈。
宿主解析事件、脱敏注入的密钥，以相同等级转发到宿主日志，并通过现有插件日志 REST/SSE
接口发布。旧 Worker 直接写出的自由格式 `System.err` 仍然兼容；无法识别等级时默认为 `INFO`。

转发的事件还会落盘到各自的滚动文件 `<LOG_DIR>/plugin-<pluginId>.log`（按 10 MB 与每日滚动，保留 7 天，每个插件上限 50 MB），因此宿主重启后近期的插件输出仍然可查。共享的 `fengyu.log` 仍会包含全部事件。

设置页控制主程序与所有 Worker 共享的单一阈值：`TRACE`、`DEBUG`、`INFO`、`WARN`、`ERROR`
或 `OFF`。新进程通过 `FENGYU_LOG_LEVEL` 接收该值；运行中的 SDK Worker 通过内置 JSON-RPC
通知 `$/fengyu/logging/setLevel` 接收更新，并立即作用于已有 logger 实例。插件不得自行注册
这个保留方法。

## 每次调用超时

每次 invoke 都被一个超时约束。宿主（`PluginProcessManager`）会等待那么多秒以获取 worker 的响应；超时后**worker 进程会被杀掉**，下一次调用会懒重启它。这是有意为之：SDK 的 dispatch 循环是单线程的，因此一个卡死的 handler 无法通过其他方式取消——唯一的恢复手段就是把进程拆掉。

| 来源 | 优先级 |
| --- | --- |
| `aiTools[].timeoutSeconds`（清单） | 最高——当 AI 工具路径调用该方法时使用 |
| 调用方提供的超时（宿主内部） | 为特定调用覆盖默认值 |
| `backend.callTimeoutSeconds`（清单） | 插件级默认值 |
| 内置默认值 | `60` |

所有声明值都会被钳制到 `[1, 600]` 秒（此上限可防止恶意清单无限期地占用一个 worker）。只有确实需要更长运行时间的方法才声明更长超时；当工作时长无界时，请使用下面的 job 模式。

## Worker 生命周期与宿主退出

worker 是一个独立的操作系统进程（JVM），绝不能比宿主存活更久。自 SDK 1.2.0 起，生产入口
`run()` 安装了两个互补的看门狗，无论宿主以何种方式退出，worker 都会在宿主消失的瞬间终止：

- **stdin EOF（主）。** 宿主关闭 worker 的 stdin 管道时（宿主 JVM 死亡时——无论是优雅退出还是被信号
  杀死——OS 会自动关闭该管道），dispatch 循环返回，worker 调用 `System.exit(0)`。
- **父进程存活轮询（辅）。** 一个守护线程轮询启动时快照的父进程 `ProcessHandle`；若 dispatch 循环仍
  阻塞在 stdin 上时父进程已消失，worker 退出。这覆盖了管道被中间启动器保持打开的少数场景。

两条路径都汇聚到一次显式的 `System.exit(0)`，因此即便插件创建了非守护线程（HikariCP 连接池、
scheduled executor 等），也无法在宿主消失后继续撑着 JVM、继续持有嵌入式数据库的文件锁。SDK 2.0.0
新增 `JsonRpcWorker.onClose(AutoCloseable)`：注册的资源会在强制退出前按注册逆序关闭且只关闭一次。
handler 持有的 job 注册表、连接池和存储应在此注册，而不是只依赖进程终止。宿主自身也
注册了独立的 JVM shutdown hook，直接调用 `PluginProcessManager.close()`——它会 `destroy()`/
`destroyForcibly()` 每个被追踪的 worker，并递归杀掉 worker 的后代进程（如 `pip` 子进程）——作为
Spring `@PreDestroy` 之外的兜底。桌面 shell 在退出时 tree-kill 整棵 backend 进程树。Linux 上
`bwrap --die-with-parent --new-session` 在内核层面提供了同样的保证；Windows 上宿主把每个 worker
分配给一个 Win32 **Job Object**（设置 `KILL_ON_JOB_CLOSE`），因此关闭 job 句柄（或调用
`TerminateJobObject`）即可可靠地拆掉整棵树——即[插件系统 → 进程隔离后端](/zh/architecture/plugin-system#进程隔离后端)
中描述的进程层隔离后端。macOS 上由看门狗 + tree-kill 两层提供同样的保证。

## 长任务（job 模式）

任何可能超过其声明超时的操作都必须拆分为 **start / status / cancel** 三元组，而不是单个阻塞方法。启动器立即返回 `jobId`；UI 或 AI 用游标轮询 `*_status` 以排空流式日志；`*_cancel` 中止。这是唯一被支持的无界工作模式——`pip download`、大工作簿拆分、批量发送等。

SDK 提供了一个 `Jobs` 注册表（`fan.summer.fengyu.sdk.Jobs`），用三行代码即可实现：

```java
import fan.summer.fengyu.sdk.Jobs;

public final class MyWorkerMain {
    public static void main(String[] args) {
        Jobs jobs = new Jobs();
        // ... handler 持有 Jobs 引用
    }
}

// 启动 handler——先做前置校验，然后把工作交给一个虚拟线程。
Jobs.Job job = jobs.start("EXPORT", handle -> {
    handle.onCancel(() -> pool.shutdownNow());            // 协作式取消钩子
    Result res = doExpensiveWork(handle::log, handle::isCancelled);
    if (handle.isCancelled()) throw new Jobs.CancellationException();
    handle.setSummary(Map.of("fileCount", res.files()));  // 由 status 轮询暴露
});
return Map.of("success", true, "jobId", job.id);

// 状态 handler——从游标起排空日志；完成后出现 result。
return jobs.snapshot(jobId, cursor);

// 取消 handler——触发启动时注册的 onCancel 钩子。
return jobs.cancel(jobId);
```

`Jobs` 的关键属性：

- **有界保留。** 已完成的 job 保留 30 分钟（可配置）且上限为 200 条；最旧的已完成 job 优先驱逐。这防止了在 fan out 大量 job 的 worker 中出现无界的内存增长。
- **协作式取消。** `Cancellable.onCancel(Runnable)` 在宿主调用 `*_cancel` 时触发一次；job 体还应在长步骤之间轮询 `Cancellable.isCancelled()`，并抛出 `Jobs.CancellationException` 以表示一次干净的放弃。
- **流式日志。** `Cancellable.log(String)` 追加到每个 job 的队列；`snapshot(jobId, cursor)` 返回从 `cursor` 起的尾部以及下一个游标，UI 可据此增量轮询。
- **locale 与关闭安全。** 虚拟线程 job 继承启动请求的 locale，因此延迟生成的摘要/日志保持同一语言。
  `Jobs.close()` 会取消所有运行中的 job 并拒绝新任务；即使取消与钩子注册发生竞态，取消钩子也只执行一次。

参考实现：

- **`plugin-offlinepython`**——`build.start` / `build.status` / `build.cancel`（以及 `deploy.*` 三元组）包装了 `pip download`，它通常超过 60 秒。启动器把 `ProcessRunner::cancel` 注册为取消钩子。
- **`plugin-excel`**——`split_start` / `split_status` / `split_cancel`（UI）以及 `excel_execute_start` / `excel_execute_status`（AI）包装了大工作簿拆分；`ExcelSplitter` 引擎接受一个 `Supplier<Boolean> shouldCancel` 探针，接到 `handle::isCancelled`。

## 错误对象

一次失败的调用返回一个 `error` 对象而非 `result`：

```json
{"jsonrpc":"2.0","id":"req-2","error":{"code":-32601,"message":"Unknown method: frobnicate"}}
```

| code | 含义 |
| --- | --- |
| `-32601` | 未知/未注册的方法 |
| `-32000` | 处理器中未捕获的异常 |
| (自定义) | 你通过 `RpcException` 抛出的任何 code |

## FileRef 解析

当 UI 通过 SDK 传递一个挑选到的文件（见 [文件 I/O](/zh/plugins/file-io)）时，它会以 **FileRef** 的形式到达——一个不透明的 `{id, name, kind, access, size}` 对象，其 `id` 以 `ref_` 开头。worker 永远不会收到原始的上传字节。取而代之的是：

1. UI 调用 `rpc.analyze({ filePath: <FileRef> })`（`rpc` 是由 `rpc.methods` 生成的类型化客户端，基于 `FengYuClient`）。
2. 宿主的 `PluginProcessManager` 遍历 params，找出任何形如 FileRef 的值（`{id:"ref_..."}`），并利用其内存中的授权表**将其改写为绝对文件系统路径**。
3. worker 收到 `{filePath: "/tmp/fengyu/runtime-files/.../in/data.xlsx"}`——一个它能直接打开的真实路径。

worker 把这些当作普通的字符串路径处理；它无需知道 FileRef 的结构。

## Worker SDK

三套规范 runtime 位于 `toolchain/sdk-java`、`toolchain/sdk-python` 与 `toolchain/sdk-go`。
它们都持有 stdout、处理启动/控制方法，并提供方法注册与阻塞 run loop。以下展示 Java；生成的
Python/Go 项目分别使用等价的 `Worker.on(...)/run()` 与
`fengyu.New().On(...).Run()` API。

### Java

`toolchain/sdk-java` 制品提供了 `JsonRpcWorker`，一个轻量、依赖极少的运行时，它从 `stdin` 读取请求、派发给已注册的处理器，并把响应写入 `stdout`。处理器通过类型化 API `worker.method(name, InputClass, OutputClass, handler)` 注册：SDK 依据 `manifest.json` 的 `rpc.methods` 生成 `PluginMethods`（方法名常量）以及每方法一对 `*Input`/`*Output` 记录，把入参反序列化为 `*Input`、为本次调用绑定一个 `RpcContext`，再把返回的 `*Output` 序列化回响应。最后调用 `.run()` 阻塞读 stdin/写 stdout：

```java
package com.example.myplugin;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.RpcContext;
import com.example.myplugin.generated.PluginMethods;
import com.example.myplugin.generated.HelloInput;
import com.example.myplugin.generated.HelloOutput;

public final class MyWorkerMain {
    private MyWorkerMain() {}
    public static void main(String[] args) throws Exception {
        new JsonRpcWorker()
            .method(PluginMethods.HELLO, HelloInput.class, HelloOutput.class,
                (HelloInput input, RpcContext ctx) -> hello(input, ctx))
            .run();
    }

    static HelloOutput hello(HelloInput input, RpcContext ctx) {
        return new HelloOutput(true, "echo: " + input.name());
    }
}
```

`RpcHandler<I, O>` 是一个 `@FunctionalInterface`——`O handle(I input, RpcContext ctx) throws Exception`。抛出 `RpcException(code, message)` 以返回结构化错误；任何其他异常都会以 `-32000` 上报。`RpcContext` 暴露协作式取消（`ctx.cancellation().throwIfCancelled()`）与 logger（`ctx.logger()`）。**不要**直接读取原始 `Map<String,Object>` params——让 SDK 把它反序列化为生成的 `*Input` 记录。

### 参考实现

两个官方插件是权威示例：

- **`MarkdownWorkerMain`** 注册了单个方法：

  ```java
  return new JsonRpcWorker().method(
          PluginMethods.RENDER, RenderInput.class, RenderOutput.class,
          (RenderInput input, RpcContext ctx) -> handlers.render(input, ctx));
  ```

- **`ExcelWorkerMain`** 注册了 UI 工作流方法（`analyze`/`configure`/`split` 等）外加多个 AI 工具方法：

  ```java
  return new JsonRpcWorker()
      .onClose(handlers)
      .method(PluginMethods.ANALYZE, AnalyzeInput.class, AnalyzeOutput.class,
          (AnalyzeInput in, RpcContext ctx) -> handlers.analyze(in, ctx))
      .method(PluginMethods.CONFIGURE, ConfigureInput.class, ConfigureOutput.class,
          (ConfigureInput in, RpcContext ctx) -> handlers.configure(in, ctx))
      .method(PluginMethods.SPLIT, SplitInput.class, SplitOutput.class,
          (SplitInput in, RpcContext ctx) -> handlers.split(in, ctx))
      // ... estimate, split_start/status/cancel, excel_analyze, excel_configure, ...
      ;
  ```

  入口 `main` 调用 `worker(handlers).run()`——`.run()` 在运行循环期间把 `System.out` 重定向到 `System.err`，以保持协议输出干净。

完整讲解见 [官方插件——Markdown](/zh/plugins/official-markdown) 与 [官方插件——Excel](/zh/plugins/official-excel)。

## 打包 worker

用 `maven-shade-plugin` 把 worker 构建为 shaded fat JAR，并把 `mainClass` 设为你的 `*WorkerMain`；`fengyu build` 会发现唯一的 `target/*-worker.jar` 并暂存为 `backend/worker.jar`。详见[构建与部署](/zh/plugins/build-deploy)。

## 下一步

- [UI 微前端](/zh/plugins/ui-microfrontend)——通过生成 client 调用方法的 UI 侧。
- [文件 I/O](/zh/plugins/file-io)——FileRef 如何被创建并解析。
- [常见陷阱](/zh/plugins/pitfalls)——stdio 纪律、FileRef 时机等。
