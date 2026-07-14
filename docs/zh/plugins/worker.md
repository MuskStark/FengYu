---
title: Worker（JSON-RPC）
description: FengYu 插件 worker 是一个进程外的可执行文件，通过 stdio 上的换行分隔 JSON-RPC 2.0 通信，其中 FileRef 在派发之前由宿主解析为绝对路径。
lang: zh-CN
---

# Worker（JSON-RPC）

worker 是插件的后端。它是一个普通的可执行文件——通常是一个由 `java -jar backend/worker.jar` 启动的 shaded JAR——宿主将其作为**独立的操作系统进程**启动，并通过 stdio 上换行分隔的 **JSON-RPC 2.0** 消息驱动它。worker 永远不会驻留在宿主 Spring 上下文中。

## 协议

宿主发送一个请求，并按行读取一个响应：

```jsonc
// host → worker（stdin 上的一行）
{"jsonrpc":"2.0","id":"req-1","method":"render","params":{"markdown":"# hi"}}

// worker → host（stdout 上的一行）
{"jsonrpc":"2.0","id":"req-1","result":{"success":true,"html":"<h1>hi</h1>"}}
```

| 消息 | 结构 |
| --- | --- |
| 请求 | `{jsonrpc:"2.0", id, method, params}` |
| 响应（成功） | `{jsonrpc:"2.0", id, result}` |
| 响应（错误） | `{jsonrpc:"2.0", id, error:{code, message}}` |

消息是**换行分隔**的：`stdin` 上每行一个 JSON 对象，`stdout` 上每行一个。`id` 用于把响应与其请求关联起来。

> **日志走 stderr。** `stdout` 专用于协议消息。Worker SDK 通过在运行循环期间把 `System.out` 重定向到 `System.err` 来强制这一点——参见 [常见陷阱](/zh/plugins/pitfalls)。

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

1. UI 调用 `client.invoke("analyze", { filePath: <FileRef> })`。
2. 宿主的 `PluginProcessManager` 遍历 params，找出任何形如 FileRef 的值（`{id:"ref_..."}`），并利用其内存中的授权表**将其改写为绝对文件系统路径**。
3. worker 收到 `{filePath: "/tmp/fengyu/runtime-files/.../in/data.xlsx"}`——一个它能直接打开的真实路径。

worker 把这些当作普通的字符串路径处理；它无需知道 FileRef 的结构。

## Worker SDK（Java）

`FengYu-Plugin-Sdk` 制品提供了 `JsonRpcWorker`，一个轻量、依赖极少的运行时，它从 `stdin` 读取请求、派发给已注册的处理器，并把响应写入 `stdout`。用 `.on(method, handler)` 注册处理器，再调用 `.run()`：

```java
package com.example.myplugin;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import java.util.Map;

public final class MyWorkerMain {
    private MyWorkerMain() {}
    public static void main(String[] args) throws Exception {
        new JsonRpcWorker()
            .on("hello", MyWorkerMain::hello)
            .run();
    }

    static Object hello(Map<String, Object> params) {
        return Map.of("success", true, "echo", params.get("name"));
    }
}
```

`PluginHandler` 是一个 `@FunctionalInterface`——`Object handle(Map<String,Object> params) throws Exception`。抛出 `JsonRpcWorker.RpcException(code, message)` 以返回结构化错误；任何其他异常都会以 `-32000` 上报。辅助访问器 `JsonRpcWorker.string(params, key)` 与 `JsonRpcWorker.integer(params, key, fallback)` 可安全地读取 params。

### 参考实现

两个官方插件是权威示例：

- **`MarkdownWorkerMain`** 注册了单个方法：

  ```java
  new JsonRpcWorker().on("render", params -> plugin.invoke("render", params)).run();
  ```

- **`ExcelWorkerMain`** 注册了三个 action 方法外加六个 AI 工具方法：

  ```java
  return new JsonRpcWorker()
      .on("analyze",       p -> plugin.invoke("analyze", p))
      .on("configure",     p -> plugin.invoke("configure", p))
      .on("split",         p -> plugin.invoke("split", p))
      .on("excel_analyze", p -> analyze.analyze(JsonRpcWorker.string(p, "filePath")))
      // ... excel_configure, excel_complex_config, excel_execute, excel_query, excel_cancel
      ;
  ```

完整讲解见 [官方插件——Markdown](/zh/plugins/official-markdown) 与 [官方插件——Excel](/zh/plugins/official-excel)。

## 打包 worker

用 `maven-shade-plugin` 把 worker 构建为 shaded fat JAR，把 `mainClass` 设为你的 `*WorkerMain`，并把产物复制到 `backend/worker.jar`。官方构建流程以及等价的单插件 `fengyu plugin build` 见 [构建与部署](/zh/plugins/build-deploy)。

## 下一步

- [UI 微前端](/zh/plugins/ui-microfrontend)——调用 `client.invoke` 的 UI 侧。
- [文件 I/O](/zh/plugins/file-io)——FileRef 如何被创建并解析。
- [常见陷阱](/zh/plugins/pitfalls)——stdio 纪律、FileRef 时机等。
