---
title: AI 对话
description: 使用多后端 AI 对话——支持流式（SSE）回复、思考卡片、工具调用展示与已保存的会话。
lang: zh-CN
---

# AI 对话

AI 对话是 Infinia 中的会话界面。选择一个后端，发送一条提示，然后以服务器推送事件（SSE）流的方式逐 token 阅读回复。在模型工作的过程中，它可以流式输出自己的思考内容，并内联调用插件工具；完成的会话会被持久化，以便你之后重新打开。

## 后端

对话运行在四个后端之上，可在[配置](/zh/guide/configuration)的 AI 配置下选择：

| 模式 | 后端 | 说明 |
| --- | --- | --- |
| `local` | Ollama | 通过 Ollama 的本地 HTTP API 与**外部 `ollama serve` 进程**通信。后端不会在进程内加载 GGUF。 |
| `openai` | OpenAI | 标准 OpenAI API。 |
| `anthropic` | Anthropic | Anthropic Messages API。 |
| `deepseek` | DeepSeek | DeepSeek **兼容 OpenAI**——通过 OpenAI 风格的适配器驱动。 |

当前生效的模式即 `PUT /api/ai/config` 最后持久化的那个；它在运行时通过 `BackendReactivator.reactivate()` 热切换，因此模式切换无需重启即可生效。

## 上下文管理

完整聊天记录仍会被持久化并显示，但发送给模型的副本会在长对话超出 provider 窗口前自动
压缩。FengYu 根据 UTF-8 字节估算 token；当用量达到已配置上下文窗口的 60% 时，它会把
最早的完整轮次总结为一条带标记的助手上下文说明，保留系统消息，并原样保留最近八轮。
摘要失败不会中断对话，而是安全回退到原始历史。

请在 AI 配置中把**上下文窗口**设为所选模型实际支持的大小。默认值为 32,768 token；`0`
表示禁用自动压缩。工具结果另有独立治理：超过 64 KiB 的结果在模型上下文中保留首尾，
避免单个工具耗尽剩余窗口；实时 SSE 活动仍会收到完整结果。

## 请求流程

一轮对话是一个两步请求：先启动运行，再打开 SSE 流。

```text
POST /api/ai/chat
  Content-Type: application/json
  X-FengYu-Token: <token>
  { "messages": [ { "role": "user", "content": "Summarize this workbook" } ],
    "permissionMode": "ask-for-approval",
    "workflowId": "<可选：把该轮对话绑定到某个流程——见 AI Agent → 可视化流程>" }

  ◄── 200 { "streamId": "<uuid>", "activeFileRefs": [...] }

GET /api/ai/stream?streamId=<uuid>
  X-FengYu-Token: <token>
  Accept: text/event-stream

  ◄── SSE stream (see below)
```

流上的第一帧是一个 `:connected` 注释心跳——它在任何事件到来之前确认流已打开。

### 文件与目录

你可以选择文件/目录，也可以在最新一条用户消息中直接输入本机已存在的绝对路径。宿主会为每个已启用且声明了 `files.read` 的后端插件分别创建不透明 FileRef；通过选择器明确选择的目录，对同时声明 `files.write` 的插件还会获得 `read-write` 权限。直接输入的路径始终只读。响应中的 `activeFileRefs` 会让新识别的路径授权在后续轮次继续有效，同时不向插件 iframe 暴露绝对路径。

::: warning
SSE 端点是 `GET /api/ai/stream?streamId=...`。**没有** `?token=` 查询参数。请用 `X-FengYu-Token` 头来认证流请求，这与其他所有端点一致。
:::

## SSE 事件

每个事件都是一个以其类型命名的 SSE 帧。完整的分类体系请参见 [SSE 事件](/zh/reference/sse-events)。

| 事件 | 数据 | 含义 |
| --- | --- | --- |
| `token` | `{text}` | 助手回复的一个片段。逐个拼接以重建完整消息。 |
| `thinking` | `{text}` | 模型思维链的一个片段。 |
| `tool` | `call`：`{phase:"call", name, arguments}` | 模型决定调用一个工具（插件或内置）。 |
| `tool` | `result`：`{phase:"result", id, success, output}` | 工具返回结果。`success:false` 时 `output` 中携带错误信息。 |
| `done` | `{text, tokens, tps}` | 本轮完成。`text` 是完整回复；`tps` 是每秒 token 数。 |
| `error` | `{message}` | 运行失败。此帧之后流结束。 |

一个具有代表性的流：

```text
: connected

event: token
data: {"text":"Let me check "}

event: tool
data: {"phase":"call","name":"excel_analyze","arguments":"{\"filePath\":...}"}

event: tool
data: {"phase":"result","id":"...","success":true,"output":"..."}

event: token
data: {"text":"the workbook has 3 sheets."}

event: done
data: {"text":"Let me check the workbook has 3 sheets.","tokens":42,"tps":18.6}
```

### 渲染

- **思考内容**渲染为折叠卡片——每段思考一张卡片，点击可展开，这样它平时不碍事，需要时才展开。
- **工具调用**显示为紧凑状态行，例如 `Read FengYu Plugin Dev skill`，并在执行和完成时原位更新。
- **审批**显示在输入框区域内、文本框正上方；聊天记录只保留紧凑状态行，不再把大块审批卡片插进消息之间。

### 授权模式

输入框提供三种逐轮生效的模式：

| 模式 | 行为 |
| --- | --- |
| **请求批准** | 读取直接执行；命令执行、文档/文件修改和外部操作会在执行前询问。 |
| **替我批准** | 安全的沙箱命令以及已声明的读取/写入自动执行；检测为高风险的命令和外部/网络操作仍会询问。 |
| **完全访问** | 工具无需审批，命令也不使用原生文件/网络沙箱；继承环境中的敏感变量仍会被移除。 |

插件通过清单声明工具副作用，因此内置工具和进程外插件操作都经过同一个审核门。没有声明副作用的旧插件按外部操作保守处理。

### 命令结果

`execute_command` 会分别返回 stdout 与 stderr，并为两条流各自提供截断标志。输出超过配置
的捕获上限时，FengYu 会保留开头与结尾，并插入省略字符数标记，因此编译器或 shell 在
末尾给出的错误仍然可见。为兼容已有消费者，原有的合并 `output` 与 `truncated` 字段仍然保留。

### Web 检索与视觉浏览器结果

两个宿主内嵌读取工具让普通资料查询不必进入有状态浏览器：`web_search` 返回紧凑的公网
结果标题/URL，`web_fetch` 获取有界的可读正文。两者都会拒绝本地/私有网络目标，并以
`read` 副作用运行。只有任务需要导航、页面状态、登录上下文或交互时，才使用仅桌面端
可用的 `browser_*` 工具。

`browser_screenshot` 会在工具响应之后把真实 PNG 作为 `image/png` media part 发送给
Spring AI，因此支持视觉的模型能直接检查像素。同一结果也包含 DOM snapshot 与可访问性树，
供纯文本模型使用。图片会保留在内存中的工具历史里供后续模型轮次使用；会话持久化仍为纯文本。
只接受字符串 `content`（不支持多模态数组）的网关会被自动适配：当轮去图重试一次，此后该端点
保持纯文本——截图仍会正常出现在聊天界面。

### 电脑操作（Computer Use）

桌面构建额外提供 `computer_*` 工具族——由后端 JVM 内的 `java.awt.Robot` 驱动的
ChatGPT 桌面版式电脑操作：`computer_screenshot` 捕获真实屏幕（PNG 与
`browser_screenshot` 一样直达视觉模型），`computer_displays` / `computer_apps` /
`computer_cursor_position` 观察环境，`computer_click` / `computer_double_click` /
`computer_mouse_move` / `computer_drag` / `computer_scroll` / `computer_type` /
`computer_key` 注入真实输入。`computer_app_launch` 与 `computer_app_activate`
负责打开或聚焦应用（`open -a`、PowerShell `Start-Process`/`AppActivate`、
`gtk-launch`/`wmctrl`）。所有坐标均为逻辑屏幕点；截图响应会报告 Hi-DPI `scale`，
模型在点击前据此换算图像像素。

每个注入输入的调用都是 `external` 副作用，必须通过每轮审批门；只有观察类工具
（`computer_screenshot`、`computer_displays`、`computer_apps`、
`computer_cursor_position`、`computer_wait`）归类为 `read`。整个工具族可通过
**设置 → 运行时与安全 → 电脑操作**开关（`computerUseEnabled`，默认开启）隐藏。
同一套实现可运行于 Windows、macOS 与 Linux：**Windows 无需任何额外权限**
（应用列举/启动/聚焦走 PowerShell；UAC 安全桌面与以管理员运行的窗口仍受系统保护）；
**macOS 需要授予应用「屏幕录制」**（捕获）**与「辅助功能」**（输入）**权限**——缺失时
捕获只剩壁纸、输入被系统静默丢弃。截图会镜像保存到 `.fengyu/computer-screenshots/`。
当无可用显示器时，所有调用都降级为 `"computer use unavailable"` 响应而非抛出异常。

## 会话

会话存储在后端。所有端点都要求带 `X-FengYu-Token` 头。

| 方法 + 路径 | 请求体 / 查询 | 返回 |
| --- | --- | --- |
| `GET /api/ai/conversations` | — | 会话摘要列表，**按时间倒序**（最新在前）。 |
| `GET /api/ai/conversations/{id}` | — | 单个会话（标题 + 消息）。 |
| `POST /api/ai/conversations` | `{title, messages}` | 创建的会话及其 `id`。 |
| `PUT /api/ai/conversations/{id}` | `{title, messages}` | 整体替换标题和消息。 |
| `DELETE /api/ai/conversations/{id}` | — | 删除该会话。 |

`PUT` 是一次整体替换——请发送你希望存储的完整 `messages` 数组，而不是增量。

## 下一步

- [AI 智能体](/zh/guide/ai-agent)——基于同一后端构建的「规划-执行」智能体。
- [配置](/zh/guide/configuration)——设置当前模式与 API 密钥。
- [AI 工具](/zh/plugins/ai-tools)——插件工具如何变得可从对话中调用。
