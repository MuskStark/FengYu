---
title: 术语表
description: Infinia 4.0.0 文档中各处使用的术语定义——FileRef、微前端、SETUP/APP 模式、sidecar、虚拟用户、JSON-RPC worker、.fyp、uiEntry、ToolCallback、MD3，以及 Ollama 后端。
lang: zh-CN
---

# 术语表

Infinia 文档中使用的领域术语，每条配有一段定义以及指向其深入讲解位置的链接。

## FileRef

宿主铸造的一种**不透明句柄**，让沙箱化的插件能够引用某个文件或目录，却永远看不到真实路径。其结构为 `{id, name, kind, access, size}`，其中 `id` 以 `ref_` 开头。插件 UI 把 FileRef 直接传入某次 RPC；宿主的 `PluginProcessManager` 会在派发**之前**将其改写为绝对文件系统路径，因此 worker 收到的是一个它能打开的真实路径。授权仅存在于内存中，不会在宿主重启后保留。参见 [文件 I/O](/zh/plugins/file-io)。

## MF（微前端）

一种自包含的 UI 包（插件的 `ui/` 目录），宿主将其作为静态资产提供在 `/plugin-runtime/{id}/**` 下，并加载进一个处于严格内容安全策略（CSP）下的**沙箱化 iframe**。在 iframe 内部，`@fengyu/plugin-sdk` 的 `FengYuClient` 通过 `postMessage` 与宿主桥接。宿主的 MF 加载器也可以通过 `import(uiEntry)` → `default.mount(el, ctx)` 直接挂载插件的 ESM 包。参见 [UI 微前端](/zh/plugins/ui-microfrontend)。

## SETUP 模式 / APP 模式

后端在启动时根据 `~/.fengyu/config/datasource.properties` 处的数据源配置自动选择的两类运行时模式：

- **SETUP 模式**启动 `SetupApplication`，**不带 JPA**，并在 `/api/setup/*` 下提供首次启动向导（绕过令牌校验）。用于尚未配置任何数据库时。
- **APP 模式**启动 `FengYuApplication`，带上 `fengyu.mode=app` 以及完整的持久化 + AI + 插件技术栈。

如果配置存在但数据库不可达，启动器会把配置备份为一个 `.bak` 同名文件，并回退到 SETUP 模式。参见 [后端——SETUP 与 APP 模式](/zh/architecture/backend#setup-vs-app-mode)。

## Sidecar

插件自带的 worker 进程——通常是一个以 `java -jar` 启动的 shaded `backend/worker.jar`。它作为**自己的操作系统进程**运行，由宿主的 `PluginProcessManager` 拉起并持有，通过 stdio 通信 JSON-RPC 2.0。正因为它是进程外的，一个 worker 的崩溃或挂起无法拖垮宿主，并且 worker 也无法触及宿主的 bean 或 JPA 会话。「Sidecar」一词强调它依附在宿主身旁、而非驻留在宿主内部。参见 [Worker（JSON-RPC）](/zh/plugins/worker)。

## 虚拟用户

Infinia 在首次以 APP 模式启动时创建的本地身份：**id** `1`，**name** `ZFlow-Summer`，**role** 为 admin/local。会话及其他以用户为作用域的记录都挂在这个身份上。不存在密码或登录流程——在单用户安装上，虚拟用户是隐含的。参见 [数据库——虚拟用户](/zh/guide/database#virtual-user)。

## JSON-RPC worker

插件后端的协议与进程模型。worker 从 `stdin` 读取换行分隔的 JSON-RPC 2.0 请求（`{jsonrpc:"2.0", id, method, params}`），派发给已注册的处理器，并按行向 `stdout` 写入一个响应（`result` 或 `error`）。`stderr` 留给日志。`FengYu-Plugin-Sdk` 制品提供了 `JsonRpcWorker`——一个带 `.on(method, handler)` 注册、极轻量的运行时。参见 [Worker（JSON-RPC）](/zh/plugins/worker)。

## `.fyp`

插件包格式——一个 zip 归档，包含三部分：`manifest.json`（元数据、权限、AI 工具）、`ui/`（微前端资产），以及 `backend/worker.jar`（sidecar 可执行文件）。通过插件市场安装（针对 `.fyp` 用 `POST /api/plugin-market/upload`，针对本地路径用 `upload-native`）。用 `fengyu plugin build` 或 Maven shade 流程构建。参见 [插件概述](/zh/plugins/overview) 与 [构建与部署](/zh/plugins/build-deploy)。

## `uiEntry`

`InstalledPluginDescriptor` 上已解析的 UI 入口 URL——即宿主 MF 加载器为挂载插件而 import 的地址（`import(uiEntry)` → `default.mount(el, ctx)`）。它衍生自清单中的 `ui.entry`（通常是 `ui/index.html`），并被提供在 `/plugin-runtime/{id}/<entry>` 下。参见 [插件系统——已安装插件描述符](/zh/architecture/plugin-system#installed-plugin-descriptor)。

## `ToolCallback`

Spring AI 对「模型可调用工具」的抽象。Infinia 把每一个内置的 `@FengYuTool` 与每一个已启用插件所声明的 `aiTools` 聚合成单个 `ToolCallback[]`，因此插件工具与内置工具在传输上无法区分——两者都出现在 `GET /api/agent/tools` 中，也都能从对话或智能体运行中被调用。参见 [AI 工具](/zh/plugins/ai-tools)。

## MD3

Material Design 3——宿主 UI（Vuetify）所实现的设计系统。宿主通过 `ctx.vuetify` 把自己的 Vuetify 实例传递给插件 MF，使插件复用宿主的主题与组件，而非各自打包；这便是「不要打包 Vuetify」规则的由来。紫色 `#6750A4` 主题色是 MD3 的基线。参见 [设计系统](/zh/design-system) 与 [UI 微前端](/zh/plugins/ui-microfrontend)。

## Ollama 后端

`local` AI 后端模式。它通过 Ollama 的本地 HTTP API 与**外部的 `ollama serve` 进程**通信——后端**不会**在进程内加载 GGUF 模型。要使用它，请单独运行 `ollama serve`，并在 AI 配置中选择 `local`。其余模式为 `openai`、`anthropic` 与 `deepseek`（兼容 OpenAI）。参见 [AI 对话——后端](/zh/guide/ai-chat#backends)。

## 下一步

- [REST API](/zh/reference/rest-api)——这些术语出现在哪些 endpoint 中。
- [架构——插件系统](/zh/architecture/plugin-system)——FileRef、MF 与 worker 如何相互衔接。
- [架构——后端](/zh/architecture/backend)——SETUP/APP 模式与 sidecar 进程模型。
