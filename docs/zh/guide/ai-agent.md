---
title: AI 智能体
description: 运行「规划-执行」智能体——给它一个目标，在审批关卡审阅计划，通过 SSE 观看每一步的流式进度，并可中途取消。
lang: zh-CN
---

# AI 智能体

AI 智能体是一个**「规划-执行」**运行器。用自然语言给它一个目标，它会起草一个多步骤计划，请求你批准该计划（可选地批准每一步），然后并行执行依赖已满足的步骤——并通过 SSE 流式回传进度。它构建在与对话相同的后端之上，并复用宿主聚合后的工具集（包括 MCP 工具），因此凡能从对话中调用的工具，也能从智能体运行中调用。

## 请求流程

一次运行以一个目标外加一个 `AgentConfig` 开始，然后通过以 `runId` 为键的 SSE 流式传输。

```text
POST /api/agent/run
  Content-Type: application/json
  X-FengYu-Token: <token>
  { "goal": "Split invoices.xlsx by the Region column", "config": { ... } }

  ◄── 200 { "runId": "<uuid>" }

GET /api/agent/stream?runId=<uuid>
  X-FengYu-Token: <token>
  Accept: text/event-stream

  ◄── SSE stream (see below)
```

::: tip
浏览器 `EventSource` 无法设置自定义请求头，因此桌面 UI 使用
`?runId=...&token=...` 打开流；非浏览器客户端也可以使用 `X-FengYu-Token`。
:::

### 调用方提供的 workflow

请求体接受一个可选的 `workflow`（一个 `AgentPlan`）。省略时由当前模型根据 `goal` 规划；提供时则驱动**确定性执行**——运行器会在任何工具运行前校验所提供的计划（模型或用户编写均可），因此 HTTP API 可以执行一个已知图，而不依赖 LLM 规划。

```json
{
  "goal": "按 Region 列拆分 invoices.xlsx",
  "config": { ... },
  "workflow": { "steps": [ ... ] }
}
```

流程构建器的可视化画布（见下）编译后填入的就是这个 `workflow` 字段，因此画布与 AI 规划路径对同一个运行器而言是对等的。

### 可视化流程（Flows 视图）

**流程**（Flows，`/flows`）视图是参考 Flowise 打造的「让模型规划」的无代码对等路径。列表页展示已保存的流程与一键模板；打开后进入全工作区的流程构建器：左侧是分类节点面板（搜索 + 可折叠分组，拖拽添加），中间是 Vue Flow 画布，右侧是节点配置面板，并支持 Flowise 式便签用于批注。画布是 Flowise **AgentFlow v2 深色画布**（即截图中的画布）的一比一复刻：通读原版源码后用纯 Vue + vue-flow 重建——节点类型色直接取自 `tokens.ts`，按 MUI 公式 `darken(color, 0.8)` 着色卡片，配 40px 圆角方形图标徽章、5×20 色条输入 handle、悬停显现的箭头输出 handle、源→目标渐变贝塞尔连线（悬停出删除钮）、#1a1a1a 点阵底、底部居中控制条（吸附/背景开关）与深色小地图。结构性编辑——节点/连线/便签的增删（工具栏、按钮或 Delete 键）与移动——全部可撤销/重做（工具栏按钮或 ⌘/Ctrl+Z / ⇧⌘Z），上限 50 步。`workflow.ts` 会把图编译成发送给 `POST /api/agent/run` 的 `AgentPlan`：同一个运行器、同一套校验与步骤结果引用（如 `steps.N.result` 或 `last.result`，会被替换进后续步骤的参数中）。规划期间工具被禁用，因此模型只负责组织工作流，绝不在规划时执行工具。

画布连线会编译为每个步骤的 `dependsOn`。同一依赖层级的步骤会在虚拟线程上并行运行；后继步骤只有在所有前置步骤完成后才会启动。

### 与流程对话（同一 Toolcall 模式）

借鉴 Flowise「与 chatflow 对话」的闭环，构建器内置了右下角聊天坞。发送消息即把该轮对话
绑定到正在编辑的流程：后端会把该流程——**草稿或已发布**——作为 `run_current_flow` 工具
交给模型，在驱动 AI 对话的同一个聊天工具调用循环中执行（同样的权限模式、同样的审批门、
同样的 SSE `tool` 事件；发送前会先自动保存未保存的修改）。AI 对话侧则以 `run_workflow_<id>`
以同样方式触达已发布流程 —— 聊天与画布是同一个工具调用运行时的对等入口，而不是两套执行模型。

### 可复用工作流：人工与 AI 调用

构建器可以把图持久化为可复用工作流，而不只是发送一次性的 `AgentPlan`。每个定义保存名称、
描述、JSON Schema 输入契约、编译后的计划、原始画布图、发布状态和修订号。可在目标或任意节点参数中使用
<code v-pre>{{inputs.name}}</code>：参数值完全等于占位符时会保留原始 JSON 类型，嵌入文本时则渲染为字符串。
原有的 <code v-pre>{{steps.N.result...}}</code> 引用继续负责连接步骤输出。

- **人工调用：** 选择已保存工作流，输入 JSON 对象并运行。宿主绑定输入、校验必填字段和
  基础 JSON Schema 类型，然后启动一次普通智能体运行。
- **AI 调用：** 发布工作流后，它会立即以 `run_workflow_<id>` 出现在实时 Spring AI 工具目录中，
  并把工作流输入 Schema 作为工具 Schema。模型调用时绑定同一套输入，并复用同一个 DAG
  运行器、持久运行历史和工具回调。

AI 调用位于同步工具调用边界内，无法在内部暂停等待人工审批，因此已发布工作流沿用外层
对话工具调用已经授予的权限；人工运行仍保留通常的逐步审批策略。保存的定义不能嵌套工作流
工具，以避免递归调用，并保持执行与审计边界清晰。

### 工作流编辑防护

画布把失败尽量前置到编写阶段，而不是运行中途才暴露，并且不会悄悄丢失用户工作：

- **图持久化。** 定义原样保存编写时的画布图——节点、连线、便签和节点 id——重新打开时
  按原样还原（对节点 id 寻址的节点引用在重载后依然有效）。
  图持久化之前保存的定义则从编译后的计划 + 布局重建画布。
- **保存时校验。** 保存会拒绝引用了输入 Schema 中未声明变量的 <code v-pre>{{inputs.*}}</code> 占位符——
  这种图在绑定阶段必然失败——并把定义限制在 64 个步骤以内。
- **运行前输入把关。** 运行对话框在必填的工作流输入填写完整前不允许启动，并明确列出
  缺失的字段；宿主在 `POST /api/workflows/{id}/run` 时会再次校验。
- **未保存保护。** 画布存在未保存修改时，切换、新建或删除工作流都会先弹出确认；
  关闭标签页或离开页面会触发浏览器的离开拦截。
- **保存副本。** 流程列表中的每张卡片都提供一键复制，是从现成示例到「我的版本」的
  最快路径。
- **逐步结果可见。** 运行面板与计划视图展示每个已完成步骤的实际输出（折叠在「执行结果」
  之后），实时运行与回看历史运行都适用。
- **错误本地化。** 宿主的校验消息（缺少输入、引用未声明变量、名称超限、发布状态等）
  在界面中以当前语言呈现，而不是裸露的英文异常。

### 一键模板与运行期选择器

从空白画布搭建仍需要了解工具。针对常见的「拆分工作簿、再按人发邮件」场景，流程视图内置了
模板库（列表页与构建器空状态均可入口）：**Excel 拆分 → 批量发送邮件** 预先连好
`excel_complex_config → excel_execute → email_send_batch → confirm_send`，预置全部输出
引用（包括嵌套的 `confirmation.confirmationId`），并自带一份普通用户直接填写的运行表单
输入 Schema：

- **文件输入**（`format: "fengyu-file"`）在运行对话框渲染为上传选择器。选中的文件会被
  授权给所有符合条件的插件并随运行传递；节点参数以 `@file:<输入名>` 占位符携带，宿主在
  分发前把它替换为当前插件的 FileRef。
- **共享输出目录**（`"x-fengyu-auto": "shared-directory"`）无需用户操作：运行时宿主创建
  一个专属临时目录，并以**实时（live）**方式授权给所有符合条件的插件 —— Excel 步骤写入的
  文件，后续 Email 步骤立即可读，且在所有沙箱后端上都成立（插件私有默认输出目录无法提供
  这种跨插件交接）。
- **动态选项输入**（`"x-fengyu-enum"`，指向插件列表工具如 `email_accounts_list` 或
  `email_tags_list`）渲染为实时下拉框 —— 用户选择的是「alice@example.com」或收件人分组，
  而不是数字 id。
- **发送步骤受审批门保护。** `confirm_send` 是 `external` 效果工具，且模板将其标记为
  *需要审批*：除完全访问外的所有权限模式都会在该步骤暂停，运行面板一键放行 —— 即工作流
  版的聊天确认卡片。

底层实现：`POST /api/agent/run` 与 `POST /api/workflows/{id}/run` 接受 `files` 数组
（`{name, refs | nativePath | createSharedDirectory}`）；解析出的授权挂到运行上，在步骤
分发时绑定 `@file:<name>` 占位符。

## 权限规则与生命周期钩子

在粗粒度权限模式与每次工具调用之间是一层用户可配置的守卫，按固定顺序求值：

```text
PreToolUse 钩子 → deny 规则 → ask 规则 → allow 规则 → 权限模式默认
```

**规则**在设置页配置（每行一条），求值与声明顺序无关 —— deny 永远优先于 allow：

| 规则 | 匹配对象 |
| --- | --- |
| `Command(git status)`、`Command(git:*)` | `execute_command` —— 词边界前缀或通配 |
| `Tool(excel_*)`、`Tool(browser_navigate)` | 工具名（通配） |
| `Effect(read)` | 所有声明了该效应的工具 |
| `Mcp(github__*)`、`mcp__github` | 按限定名匹配 MCP 工具 |
| `WebFetch(domain:example.com)` | 域名或其子域上的 `web_fetch`/`web_search` |

Shell 链按段检查：deny/ask 规则匹配 `a && b | c` 链中的**任意**一段，而 allow 规则
必须**每一段**独立匹配才放行 —— 因此 `Command(git status)` 无法授权
`git status && rm -rf /`。危险命令地板（`rm`、`sudo`、`kill`、`git push` 等）使 allow
规则失效，这些命令总是询问。被拒绝的调用会以规则原因使步骤失败，模型能看到原因
并调整计划。

**钩子**扩展同一条管线。钩子形如 `{name, event, matcher, type, command|url,
timeoutSeconds, enabled}`；`command` 钩子从 stdin 收到 JSON 事件信封，HTTP 钩子收到
POST 请求体：

- `pre_tool_use` —— 门禁：退出码 2 拒绝（stderr 首行为原因）；stdout JSON
  `{"decision":"deny","reason":"…"}` 在任意退出码下都拒绝；退出码 0（或 JSON allow）
  放行。
- `post_tool_use` / `post_tool_use_failure` —— 观察已完成的调用（参数 + 结果）。
- `run_complete` / `run_error` —— 观察智能体运行的终止。

钩子失败（崩溃、未知退出码、超时）按**放行**处理（fail-open）：失败被记录、调用继续。
FengYu 是本地个人工具，钩子被故意弄坏不属于威胁模型；因为一个钩子崩溃而阻断全部
调用只会把功能变成自我事故。

**插件也能贡献钩子**（`.fyp` 包内的 `hooks/hooks.json`，兼容 grok 形态
`{"hooks": {"PreToolUse": […]}}` 或 FengYu 的扁平列表）。安装或启用插件**不会**激活其
钩子 —— 用户必须显式信任该插件（`POST /api/plugin-hooks/{id}/trust`）；取消信任对下一次
调用立即生效。受信的插件钩子以插件安装目录为工作目录运行，环境变量中携带
`FENGYU_PLUGIN_ROOT`/`FENGYU_PLUGIN_DATA`，名称以 `plugin/<id>/<name>` 命名空间化以便审计。

## 后台任务

长工作流不再占用同步工具槽。模型可以调用 `task_submit_workflow(workflowId, inputs)`
在后台启动已发布的工作流（立即返回 `taskId`），再用 `task_output(taskId, timeoutMs)`
轮询或阻塞等待、用 `task_wait(ids, "any"|"all", timeoutMs)` 一次等待至多 20 个任务、
用 `task_kill(taskId)` 终止失控任务 —— 先协作取消，进程型任务升级 SIGTERM → SIGKILL。
同一注册表支撑 UI 侧的 `GET /api/agent/tasks`。

## 工作流定时任务

已发布的工作流可以按计划运行（`POST /api/agent/schedules`，或 `task_schedule` 工具）：
最小间隔 60 秒、最多 50 个活跃任务、7 天后自动过期、可选立即首跑，`recurring: false`
即为延迟一次性任务。定时触发的运行会提交为普通后台任务，因此 `task_output`/
`task_wait`/`task_kill` 与运行面板对它们一视同仁。调度器为内存态 —— 重启即清空
（刻意的非持久默认，与所参考的模型一致）。

## 运行历史：搜索、分叉、回退

- **搜索** —— `GET /api/agent/runs?q=…` 按目标/摘要/错误文本过滤历史。
- **分叉（fork）** —— `POST /api/agent/runs/{id}/fork` 把已完成运行的计划复制为新的
  平行运行（“换个思路再来”），执行前需计划审核。
- **回退（rewind）** —— `POST /api/agent/runs/{id}/rewind {keepSteps}` 把计划截断为前
  N 步，只继承该边界以下已完成的执行，并带计划审核恢复。被丢弃步骤的副作用**不会**
  回滚 —— 审核门正是为了让人类核对它们。

每次运行还会记录创建时的插件沙箱姿态；当宿主以非沙箱方式运行插件时，恢复、分叉或
回退一个沙箱姿态的运行会被拒绝 —— 重放绝不允许悄悄削弱隔离。

## 只读批量能力档

`POST /api/agent/batch` 接受 `capabilityMode: "read-only"`，把每个子运行限制为
`read` 效应工具 —— 这是“并行调研/审查”任务的声明式形态。包含任何非只读步骤的计划
会在任何工具执行前被整体拒绝。

## 跨会话记忆（实验性，默认关闭）

在设置中开启后，AI 获得 `memory_remember` / `memory_search` / `memory_list` /
`memory_forget`：按用户存储的长期事实，按关键词重合度 × 7 天半衰期新近度加权检索，
相关记忆会注入智能体运行的规划上下文。实验开关是有意的克制 —— 记忆功能可能记错
东西，所以保持主动开启。

## 端到端流程

```text
goal
  │
  ▼
plan_token ──► plan_ready ──► plan_approval_requested
                                   │
                                   │  POST /api/agent/{runId}/approve
                                   ▼
                          step_start ──► step_complete
                                   │              │
                                   │   step_approval_requested ──► approve
                                   ▼
                               complete
```

## SSE 事件

每个事件都是一个以其类型命名的 SSE 帧。完整的分类体系请参见 [SSE 事件](/zh/reference/sse-events)。

| 事件 | 何时 | 携带 |
| --- | --- | --- |
| `plan_token` | 模型正在流式输出草稿计划 | 计划文本片段 |
| `plan_ready` | 计划已定稿 | 完整的 `AgentPlan` |
| `plan_approval_requested` | 运行器在执行前等待你批准计划 | 关卡详情 |
| `step_start` | 某一步已开始 | 该步描述符 |
| `step_complete` | 某一步已完成 | 该步结果 |
| `step_approval_requested` | 某一步在运行前需要你的批准 | 关卡详情 |
| `complete` | 整次运行完成 | 最终结果 |
| `error` | 运行失败 | `{message}`——此帧之后流结束 |

## 审批关卡

智能体在审批关卡处暂停，在你放行之前不会继续。向运行（而非流）发送批准：

```text
POST /api/agent/{runId}/approve
  X-FengYu-Token: <token>

# 可选——发送一份已编辑的计划以覆盖模型的草稿：
  Content-Type: application/json
  { /* an edited AgentPlan */ }
```

- **不带请求体**时，当前计划按原样批准。
- **带一份已编辑的 `AgentPlan` 请求体**时，运行器在继续之前采纳你的修改——适用于裁剪步骤、重排序或收紧指令。

同一个端点同时放行 `plan_approval_requested` 与 `step_approval_requested` 关卡。

## 取消

取消是**协作式**的——运行器检查标志位并在下一个安全点停止，因此取消可能不会立即生效。

```text
POST /api/agent/{runId}/cancel
  X-FengYu-Token: <token>
```

取消后流结束；运行不会发出 `complete`。

## 持久历史与恢复

运行快照和有序生命周期事件会持久化。`GET /api/agent/runs` 列出历史，
`GET /api/agent/runs/{runId}` 返回计划、执行结果与审计事件。失败、取消或因应用重启而
中断的运行可通过 `POST /api/agent/runs/{runId}/resume` 恢复：已完成步骤直接复用，
只执行未完成部分，并且恢复后的计划必定先暂停等待审阅。

对于相互独立的目标，`POST /api/agent/batch` 可并行启动 1–8 个隔离的运行生命周期并返回
各自的 `runIds`。每个运行分别维护审批、取消、历史和 SSE 观察。

## 可用工具

`GET /api/agent/tools` 返回智能体在其步骤中可调用的可编排工具列表：

```text
GET /api/agent/tools
  X-FengYu-Token: <token>

  ◄── 200 [
        { "name": "...", "description": "...", "inputSchema": { /* JSON Schema */ } },
        ...
      ]
```

该列表由宿主聚合的 Spring AI `ToolCallback[]` 构成——每一个内置的 `@FengYuTool`、
每一个已启用插件所声明的 `aiTools`，以及已配置 MCP 服务器提供的工具。插件与 MCP
工具在传输上与内置工具无法区分（参见 [AI 工具](/zh/plugins/ai-tools)）。

## 下一步

- [AI 对话](/zh/guide/ai-chat)——智能体的会话式对应物。
- [配置](/zh/guide/configuration)——选择智能体运行所使用的后端。
- [AI 工具](/zh/plugins/ai-tools)——工具如何变得可从智能体运行中编排。
