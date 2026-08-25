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

### 节点配置：一个输入的三种填法

节点面板上的每个输入都有**手动 / 引用 / 表达式**三态来源控件：

- **手动**：即原有的表单控件（文本、数字、下拉、行编辑器、工作簿分析……），并以声明中的占位符和示例值作提示。
- **引用**：打开变量树——工作流输入 + 每个上游节点的输出，按递归树展开（对象字段与数组元素可继续展开，`[0]` 示例子节点让数组下标路径可见）。行按目标输入的期望类型过滤（类型不匹配的灰显并说明原因），可点击选择、拖拽到输入框绑定，或一键复制引用路径；选择上游输出会自动补画连线。
- **表达式**：文本模板，可内嵌 <code v-pre>{{node.&lt;id&gt;.result.&lt;path&gt;}}</code> / <code v-pre>{{inputs.&lt;name&gt;}}</code> 引用；未知引用在保存前即被行内标出。

引用语法现在支持**数组下标**——<code v-pre>{{node.n.result.files[0].name}}</code>——画布与后端运行器行为一致。

### 看见数据：类型端口、数据预览与固定结果

- **类型化端口。** 声明过的输出按数据类型给端口着色（文本 / 数字 / 对象 / 列表 / 文件；灰色 = 未声明 = 任意），端口标签的 tooltip 显示类型、说明和示例值。
- **上游数据预览。** 节点面板折叠展示上游节点能提供的一切：先是带示例的声明字段，流程跑过之后升级为**上次运行的实际值**（按字段路径解析）。每行都有复制引用按钮。
- **输出查看器与固定（pin）。** 节点自身的输出同样按 声明 → 示例 → 上次运行 逐级展示；已完成步骤的真实结果可以**固定**：之后的运行直接复用该值、不再执行该工具（节点带 📌 角标，依赖关系保持不变）。固定是调试脚手架，取消固定即恢复执行。
- **运行徽标。** 运行期间节点卡片直接显示 运行中 / 完成 / 失败 状态，无需打开运行面板也能看懂执行进度。

### 开始节点

新建流程自带**开始节点**——工作流运行时输入的可视化编辑器。添加字段（名称、显示名、类型、必填、选项、示例）后，它们既是运行表单的 Schema，也是卡片上的类型化字段行；节点里以 <code v-pre>{{inputs.名称}}</code> 引用。设置抽屉与开始面板内仍保留 JSON Schema 视图给高级用户。

### 所有工具皆可上画布

调色板优先展示显式声明的节点（类型化端口、示例、帮助），并在「显示全部工具」开关后列出其余全部可编排工具作为 Schema 推导的降级节点——不会再有工具对编排者隐身。插件作者为 manifest 补一条 `flowNodes` 声明即可升级降级节点（见插件文档）；声明协议现已覆盖类型、嵌套输出字段、示例、字段级帮助与节点级帮助。

### 对话、生成与诊断流程

构建器内置了右下角 AI 面板，并与 AI 对话复用同一条工具调用循环。每轮请求都会携带一份
**实时画布**快照——包括尚未保存或当前无效的图——并绑定三个请求级编排工具：

- `inspect_current_flow` 返回实时图、输入契约、当前编辑器诊断，以及已安装工具的输入/输出契约。
- `diagnose_current_flow` 检查不可用工具、缺失的必填参数、错误引用、悬空连线、依赖环和上次运行错误，
  全程不修改画布。
- `edit_current_flow` 可从空白画布生成完整替换提案，也可修改现有图；它绝不会直接写入工作流。

修改结果以节点/连线 diff 呈现。点击**应用并保存**时，若画布或修订号已经变化会先拒绝过期提案；
之后再通过实时工具目录还原节点，并复用正常的画布编译、校验、乐观修订检查、撤销历史与保存路径。
校验失败时，提案图会留在画布上供用户检查；忽略提案则不会产生任何修改。

当未保存修改能够组成有效图时，发送前会先自动保存，并把这个干净的已保存定义——**草稿或已发布**——
作为 `run_current_flow` 暴露，沿用 AI 对话相同的权限模式、审批门和 SSE `tool` 事件。无效图仍可交给
检查与诊断工具，但不会作为 `run_current_flow` 执行。已发布流程仍以 `run_workflow_<id>` 提供给普通 AI 对话。

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

发布采用快照语义。编辑已发布流程只会产生更新的草稿修改，AI 仍执行最后一次审核过的不可变
修订；点击**发布修改**才会把草稿提升为新快照。设置抽屉列出全部已发布版本，也能把旧版本恢复
成新草稿——再次发布前不会改变当前生效版本。保存、发布和恢复都校验修订号；另一编辑器已经
推进定义时返回 HTTP 409。

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
- **安全失败重试。** 可安全重试的节点可以设置 1–5 次总尝试次数和指数退避。只读工具自动
  符合条件；写入/外部插件工具只有在 manifest 显式声明 `idempotent: true` 时才符合条件。
  其它工具不能配置重试，伪造的不安全重试计划也会在执行前被拒绝。
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
`task_capacity` 会返回全局队列压力与限制、交互/普通/批处理任务的组成与预留、活跃所有者数量、
按优先级分类的最早排队耗时、饱和状态和调度策略，以及当前用户自己的占用量和 32 个任务的排队配额，但不暴露
其他用户的任务详情。同一注册表支撑 UI 侧的
`GET /api/agent/tasks` 和
`GET /api/agent/tasks/capacity`。任务快照和截断后的输出按用户隔离并持久化，
最近 100 个已结束任务在重启后仍可查看。若进程停止时任务还在运行，重启后会明确恢复为
“被重启中断”的失败状态且不会重放，因为其进程内工作和外部副作用无法安全续跑；排队中的
任务在重启时也采用相同处理。

宿主最多同时运行 16 个后台任务正文，短时突发中另外 128 个提交会进入全局有界队列；单个
所有者最多排队 32 个。这个所有者上限可以避免一个并发提交者在其他用户到达前抢占全部队列
槽位。在这些边界内，批处理任务最多占用每个所有者 16 个、全局 64 个排队槽；批处理加普通任务
最多占用每个所有者 24 个、全局 96 个槽，从而即使低优先级生产者并发提交，仍为交互任务保留
每个所有者 8 个、全局 32 个槽。Webhook 投递属于交互任务，模型提交的工作流属于普通任务，
定时触发属于批处理任务。任务在每个“所有者 + 优先级”子队列内保持 FIFO；不同所有者轮流取得
新释放的槽位，而每个所有者按 4:2:1 的有界周期选择交互/普通/批处理任务
（`owner-round-robin-weighted-priority`）。持续的交互负载下批处理仍有固定轮次；其他类别或
所有者为空时则继续充分利用空闲槽位。`task_list`、REST API 与运行面板会显示 `priority` 并区分
“排队中”和“运行中”。终止排队任务会在正文开始前将其取消并释放队列容量。运行面板打开期间会
定期刷新、展示全局/当前用户利用率和优先级组成，并在任一队列上限已满或最早任务等待至少 30 秒
时告警 —— 容量 API 在全局 `oldestQueueWaitMs` 之外还返回
`oldestInteractiveQueueWaitMs`/`oldestNormalQueueWaitMs`/`oldestBatchQueueWaitMs`，
因此告警会指明真正在积压的是哪一类（交互、普通或批处理）任务。每个
任务快照记录 `queueWaitMs`，正文开始后再记录 `startedAt` 和 `runDurationMs`；活跃任务的
耗时会持续增长直至开始或结束，因此无需翻查日志也能识别持续的队列压力。总容量耗尽时，HTTP
调用方会收到带 `Retry-After: 1` 的 `429 Too Many Requests`；`task_submit_workflow` 返回等价
的结构化 `retryable` 与 `retryAfterSeconds` 字段；两者还会通过 `capacityScope` 说明触发的是
`owner`、`global`、`owner-priority` 或 `global-priority` 上限，优先级预留失败时还会返回
`capacityPriority`。`task_capacity` 另外返回全局和当前所有者的批处理/非交互限制与计数，及
`ownerQueueLimit`、`ownedQueueAvailable` 和 `ownerSaturated`。Webhook 若在接纳前被拒绝，会释放其哈希化
幂等声明，因此等待后使用相同事件 ID 重试是安全的。取消注册也具备竞态
安全性：若工作流或进程的
取消器在终止请求到达后才挂接，它会立即执行，不会丢失该请求。

调度器还会通过 Micrometer 发布排队压力（本地经 Actuator 的 `/actuator/metrics`，
生产环境可经 `management.otlp.metrics.export.url` 导出至 OTLP 收集器），指标语义对齐
Kubernetes API Priority and Fairness 与 Temporal 的 schedule-to-start 延迟：
按优先级计数的 `fengyu.bg.tasks.dispatched` 与 `fengyu.bg.tasks.rejected`
（拒绝带触发上限的 `owner`/`global`/`owner-priority`/`global-priority` 标签）、
按 `executed` 与 `cancelled` 结果区分的 `fengyu.bg.task.queue.wait`
schedule-to-start 直方图，以及按优先级的 `fengyu.bg.queue.inqueue` 和
`fengyu.bg.queue.oldest_wait_ms` 两个 Gauge。基于这些序列可以直接表达分优先级
SLO（例如交互任务排队等待的 p99，或某一类任务最老排队超过 30 秒的卡滞告警）。

## 工作流定时任务

已发布的工作流可以按计划运行（`POST /api/agent/schedules`，或 `task_schedule` 工具）：
最小间隔 60 秒、最多 50 个活跃任务、7 天后自动过期、可选立即首跑，`recurring: false`
即为延迟一次性任务。定时触发的运行会提交为普通后台任务，因此 `task_output`/
`task_wait`/`task_kill` 与运行面板对它们一视同仁。

调度定义会持久化，并在应用重启后恢复。周期任务沿用最初的固定节拍，不会按最近一次唤醒时间
不断漂移；若停机期间跨过多个触发点，只会立即合并补跑一次，多余次数通过 API 与运行面板的
`missedFires` 暴露。调度器会在提交任务前先持久化一次 at-most-once 投递声明：若应用恰好在
该窗口崩溃，会记录但不重放该次触发，因为少执行一次比重复发送消息、写入或扣费更安全。
在插件沙箱开启时创建的调度，如果宿主后来降低隔离强度会暂停，恢复沙箱后才继续。删除工作流
会在同一数据库事务中取消其所有活跃调度。

## 工作流 Webhook

已发布工作流也可以由其它本地应用启动。打开运行对话框，填写默认输入、选择权限模式，再点
**创建 Webhook**。触发器会持久化并按所有者隔离；每次被接受的投递都会成为普通的
`workflow-webhook` 后台任务，因此重启后仍可在运行面板查看状态和截断后的输出。

端点有意只在环回地址可用：`POST /api/workflow-hooks/{triggerId}`。请求体是 JSON 对象，字段会
覆盖触发器保存的默认输入；同时携带创建时仅展示一次的 `X-FengYu-Webhook-Secret`。FengYu
只保存该密钥的 SHA-256 摘要，轮换后旧密钥立即失效。可选的 `X-FengYu-Event-Id`（最长 200
字符）同样只保存摘要，并在提交任务前通过数据库原子声明；并发重试会拿到原任务而不是重复执行。
只有每次投递确实都应独立运行时才省略它。请求体上限为 256 KiB。

Webhook 触发器不能绑定文件选择器输入或自动创建的共享目录，因为这些授权会随交互会话过期；
请改用插件管理的持久化数据，并传递其稳定标识。若触发器创建时插件沙箱开启、之后隔离强度被
降低，它会暂停。崩溃窗口内已声明但未确认的事件会标记为中断且不重放——宁可显式漏跑一次，
也不重复发送消息、写入或扣费。删除工作流会在同一事务中禁用其调度和 Webhook 触发器。

在运行面板展开触发器即可查看最近投递。每条只读审计记录展示生命周期状态（`CLAIMED`、`QUEUED`、`SUBMITTED`、
`COMPLETED`、`FAILED`、`CANCELLED` 或 `INTERRUPTED`）、接纳与完成时间、耗时、所属后台任务
ID、是否提供幂等键，以及存在时的终态错误。即使没有事件 ID，每次调用仍会获得独立审计记录。
每个触发器最多保留 1,000 条，每次请求最多读取 100 条；请求正文、密钥、原始事件 ID 与事件
ID 哈希都不会被保存或返回。FengYu 有意不提供盲目重放按钮：崩溃窗口状态不确定时，重放含
写入能力的工作流可能复制外部副作用；确认恢复安全后，应由调用方提交一个经过审阅的新事件。

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
