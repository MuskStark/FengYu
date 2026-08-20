# FengYu 流程图（Flow Builder）交互方案设计

> 状态：设计提案；**P0–P3-lite 已在 `4.0.0-folw` 分支实现**（三态来源控件、变量树、类型化端口、
> 上游数据预览/输出查看器/lastRun、pin/pinnedResult、数组下标引用、Start 节点、调色板全工具、
> 描述符 v2 + schema、快捷键与徽标）。
> 2026-08-20 第二轮落地：**IF 条件分支（P3 控制流）与「只运行此节点」（P2 单步调试）已实现**——
> `flow_if` 宿主工具 + 分支端口 + `AgentStep.runWhen` + skip 传播 + `step.skipped` 事件
> （前端徽标/面板/历史全链路）；单步运行走 `/api/agent/run` 单步临时 plan，上游引用从
> pin/lastRun 代入；模板库扩到 4 个（含随时可用的分支演示）；画布空态增加最近流程；
> `fengyu check` 新增 flowNodes 交叉校验（输入名↔工具参数、widget↔type，顺手修掉了
> email 插件 `body`→`plainText` 的真实错位）；`docs/{en,zh}/guide/flow-nodes.md` 概念页上线。
> 同日追加：`excel_execute` 新增 `outputDir` 输出（实际写入目录的绝对路径，含宿主注入的
> 默认目录），邮件批量发送的附件目录可直接绑定 `{{node.write.result.outputDir}}`，
> excel→email 模板已改为此接法。**P4 的 LLM 节点（`flow_llm`）已实现**：调研 n8n/Dify/
> Flowise 后落地——提示词（可引用上游）+ 系统角色 + 温度 + JSON Schema 结构化输出
>（原文 `text` 永远保留、`data` 解析失败带校验错误定向重试一次）；每次调用新建独立
> 模型客户端，并行步骤与聊天内 `run_current_flow` 执行均不死锁。P4 剩余：
> `edit_current_flow` AI 改流程与画布 diff 预览、模板自存。
> 尚未实现：`{{` 自动补全 pill、汇聚节点 join:any、stopAfterNode/mock 伪造输入、
> 节点禁用 bypass、Tab/Shift 高亮/0-1 缩放等交互细节、AI 改流程（P4，LLM 节点见下方追加）。
> 2026-08-20 修订：① 修复多节点断链 bug——vue-flow 对 `v-model` 重赋值的整表回校验把已存边
> 误判为重复边而静默丢弃（`canConnect` 现按 id 识别回显，见 §6.2）；
> ② 输出端口定为**每节点单一输出口**（命名输出保留在变量树/检查器/端口 tooltip 中，
> 不再逐字段渲染端口，见 §6.1 注；IF 的 true/false 分支端口是唯一例外）。
> 日期：2026-08-19 · 分支：4.0.0
> 范围：`frontend/src/views/FlowBuilder.vue` 及 `frontend/src/components/agent/*` 的画布交互、
> `FengYu/src/main/java/fan/summer/fengyu/ai/{workflow,agent}` 的引擎配套改动、
> `flow-nodes/builtin.json` 与插件 `manifest.json` 的 `flowNodes` 声明协议。
> 调研对象：n8n、Dify、Langflow、Flowise（AgentFlow v2）、Node-RED、ComfyUI（来源见附录 D）。

---

## 1. 背景与目标

### 1.1 现状一句话

当前画布是"描述符驱动"的 vue-flow 编辑器：调色板只展示声明了 `flowNode` 的工具（目前仅 5 个数据类节点），
右侧检查器按声明渲染表单，每个输入可切换「手动 / 工作流输入 / 上游节点输出」三种来源（选择后自动画边），
保存时把画布编译成 `AgentPlan`（DAG + `dependsOn`），后端按拓扑层并发执行，`{{steps.N.result.path}}` 传递数据。

**现有能力（值得保留的地基）：**

| 能力 | 位置 |
|---|---|
| 描述符驱动表单（widget: text/number/switch/select/textarea/json/analyze/rows） | `FlowNodeInspector.vue:70-120` |
| 命名输出句柄（`descriptor.outputs` 渲染多个输出端口） | `WorkflowToolNode.vue:36-39,140-163` |
| 输入来源下拉（手动 / `inputs.*` / 上游节点输出字段，选完自动建边） | `FlowNodeInspector.vue:457-482`、`changeInputSource` 319-337 |
| 编辑态 `{{node.<id>.result[.path]}}` ↔ 持久态 `{{steps.<index>.result[.path]}}` 双语法桥 | `FlowBuilder.vue:479,835-858`、`AgentRunner.java:74-76` |
| 分层校验：节点缺参徽标 → 环检测 → 保存时引用校验 → 运行表单门禁 | `FlowBuilder.vue:796-813,922-933`、`workflow.ts:445-470,651-669` |
| 运行面板：实时状态、每步结果、历史（fork/rewind） | `FlowExecutionPanel.vue`、`agentRunStream.ts` |
| 选项动态加载（catalog RPC）与上下文分析（analyze feeds） | `optionSource.ts`、`FlowNodeInspector.vue:156-224` |
| 便签节点、迷你地图、撤销重做（50 步） | `FlowStickyNote.vue`、`FlowBuilder.vue:1109-1168` |

### 1.2 三大痛点（用户视角）

1. **不会配置节点** —— 打开检查器是一张"表单字段清单"：不知道哪些必填、填什么格式的值、
   高级参数（Arguments JSON）直接暴露原始 JSON；工作流自身的输入还要在设置抽屉里手写 JSON Schema。
2. **看不懂输入输出关系** —— 边只是"依赖声明"，画布上看不出哪条边流的是什么数据；
   输出端口虽有名字但纯粹装饰；没有分支/条件，多输出端口的意义无从体现。
3. **不知道什么数据能给下一个节点用** —— 来源下拉只列出字段名（静态 schema 展平一层），
   没有类型说明、没有示例值、没有"上次运行的真实值"；更深的嵌套路径只能去高级 JSON 里手写
   `{{node.x.result.a.b}}`，写错只有运行时才报错。

### 1.3 设计目标

- **普通用户**：不读文档就能回答三个问题——"这个节点要我填什么"、"这条线传的是什么"、
  "上游给了我哪些字段、长什么样"。
- **开发者（插件作者）**：一份带 JSON Schema 校验和 IDE 补全的声明协议、一条
  `fengyu dev validate` 校验链路、一篇权威文档；未声明的工具也有降级的默认节点。
- **引擎**：改动保持 `graph_json` / `AgentPlan` 向后兼容，旧流程原样打开运行。

---

## 2. 业界调研摘要

### 2.1 总览

| 项目 | 连接模型 | "数据从哪来"的答案 | 配置体验 | 对本方案最大的启示 |
|---|---|---|---|---|
| **n8n** | main 数据边 + 表达式逐字段绑定；IF/Switch 多输出 + 错误输出 | 节点详情三栏：左 INPUT 表格（上游输出的电子表格视图，可拖拽字段到参数）、中参数、右 OUTPUT；Schema 视图诚实标注"来自上次成功执行" | 双击进全屏详情；Execute step 只跑本节点（自动补跑上游）；Edit Output 可伪造数据并 pin | **拖拽映射 + 单步运行 + pin/mock** 是数据可见性的黄金组合 |
| **Dify** | ReactFlow 边 + `{{#nodeId.var#}}` 引用；IF/ELSE 多 case、变量聚合器 | 变量选择器按上游节点分组、按目标字段类型过滤（`filterVar`）；`/` 斜杠插入；变量渲染成 chip | 节点面板 + 单节点试运行 + 变量检查器（可改值重跑下游） | **类型过滤的变量树 + 变量 chip 化 + 检查器改值重跑** |
| **Langflow** | 强类型彩色端口，拖线时合法端口霓虹高亮、非法端口淡化 | 端口即类型；点击端口打开"兼容组件"过滤面板；Inspect 看上次输出 | 检查面板 + Freeze 上游 + Last Run | **连接合法性在拖线瞬间用视觉反馈，而非事后报错** |
| **Flowise v2** | 独立节点 + `{{ }}` mention pill | 输入 `{{` 弹出"图感知"的变量建议（只列真实上游）；Start 节点声明状态键，后续只许更新不许新增 | 工具参数表单随所选工具自适应；LLM 节点声明 JSON 返回 schema | **`{{` 触发 pill 自动补全**（本仓库画布本就是 Flowise 的 1:1 复刻，基因最合） |
| **Node-RED** | msg 对象流过 wire | typedInput：字段左侧小按钮切换 str/num/msg./flow./global./env./jsonata；debug 树每行"复制路径" | 编辑对话框三 tab；节点内嵌 help（文档输入/输出契约） | **typedInput 三态来源切换** 与 **复制路径** 是最小成本、最高收益的两个控件 |
| **ComfyUI** | 颜色=类型，双端同色才可连；悬空连线弹出兼容节点菜单 | widget 即潜在端口（拖线到控件自动转为输入） | 双击画布搜节点；bypass/mute 两态；子图封装 | **悬空连线→兼容节点菜单**；**bypass（穿通）与 mute（移除）区分** |

### 2.2 提炼出的十条设计准则

1. **选择优于输入**：引用靠点选/拖拽生成，永远不让用户手写语法（n8n 拖拽映射、Dify 变量树、Flowise `{{` 补全）。
2. **数据要"看得见"**：每个节点随时能回答"我输出什么"——声明 schema、示例值、上次真实值，三态逐级降级（n8n Schema 视图的三态文案）。
3. **类型是一等公民**：端口带类型与颜色，绑定处按类型过滤并即时校验（Langflow、Dify、ComfyUI）。
4. **错误前移**：能拖线时拦就不等保存，能保存时拦就不等运行（Langflow 拖线高亮、Dify filterVar）。
5. **空状态必须是行动号召**：每个"没有数据"的画面都配一个一键动作——"运行上游""填一个示例值"（n8n 空状态文案）。
6. **单步可跑、可伪造、可固定**：Execute step / Edit Output / Pin 三件套让调试不依赖整条链路跑通（n8n）。
7. **契约要声明且要文档化**：节点声明协议带 Schema、带校验、带内嵌 help（Node-RED data-help-name、n8n inputs/outputs 数组）。
8. **运行状态画在节点上**：running/success/failed/skipped 徽标 + 边动画，不用切面板就能看懂执行（Dify 状态图标）。
9. **复杂度给出口**：类型转换节点、bypass、子流程/模板复用，严格性不能把用户逼进死胡同（Langflow Type Convert、ComfyUI bypass）。
10. **诚实标注数据来源**：预览值明确写"来自声明 / 示例 / 上次运行"，不假装是真实结果（n8n "The fields below come from the last successful execution"）。

---

## 3. 现状差距分析

| # | 痛点 | 现状根因（代码锚点） | 目标体验 |
|---|---|---|---|
| G1 | 不会配置节点 | 检查器无字段级帮助/示例；必填仅靠徽标计数；工作流输入 = 手写 JSON Schema（`FlowSettingsDrawer.vue` 的 textarea）；高级区直接暴露 Arguments JSON | 字段三态来源控件 + 占位符/示例/帮助；开始节点可视化输入设计器；JSON 只作为"专家模式" |
| G2 | 看不懂输入输出关系 | 边 = 纯依赖（`canConnect` 只查环/重边）；输出端口装饰性（`WorkflowToolNode.vue` 渲染但编译时忽略）；无分支语义 | 类型化端口 + 端口 tooltip；数据流高亮；IF/Switch 分支边带标签；汇聚节点 |
| G3 | 不知道什么数据可用 | 来源下拉只有静态字段名（`flattenWorkflowOutputFields` 只展平一层，`workflow.ts:526-542`）；无类型/示例/运行值；深路径手写无补全无校验 | 上游数据预览面板（schema/示例/上次运行三态）+ 拖拽映射 + `{{` pill 补全 + 绑定时类型校验 + 数组下标路径 |
| G4 | 调试黑盒 | 执行面板只有文本结果；不能只跑一个节点；不能伪造上游数据；画布无运行状态 | 单步运行/运行到此 + 输出 pin/mock + 画布运行徽标 + 边动画 + 变量检查器 |
| G5 | 节点太少、开发者无路径 | 调色板被 `flowNode` 声明门禁（`FlowBuilder.vue:398`），browser/markdown/python 工具不可见；`flowNodes` 无 Schema、无校验、`docs/en/plugins/manifest.md` 未记载 | 默认节点自动派生（高级开关可见）+ 声明协议 v2 带 JSON Schema + `fengyu dev validate` + 官方文档 |

---

## 4. 方案总览（六层）

```
┌──────────────────────────────────────────────────────────────┐
│ L6 引导与模板   开始节点/空画布引导/模板库/AI 生成（远期）      │
│ L5 运行反馈     节点状态徽标 · 边动画 · 错误定位               │
│ L4 数据可见性   上游预览面板 · 拖拽映射 · 输出查看器 · pin/mock │
│ L3 配置面板     三态来源控件 · 变量 pill · 字段帮助 · 类型校验  │
│ L2 画布与连接   类型化端口 · 连接校验 · 分支边 · 汇聚 · 高亮    │
│ L1 声明协议     flowNode 描述符 v2：类型/嵌套 schema/示例/help │
└──────────────────────────────────────────────────────────────┘
        支撑：引擎（类型化结果、partial-run、分支 skip、pin）
```

三层对应三大痛点：**L3 治"不会配置"，L2 治"看不懂关系"，L4 治"不知道数据"**；
L1 + DX（§11）治开发者侧；L5/L6 闭环调试与上手。

---

## 5. L1 节点声明协议 v2（开发者契约）

### 5.1 原则：v1 是 v2 的子集

现有字段（`tool/label/color/icon/inputs[].{name,widget,title,description,default,options,fields,context,source}`、
`outputs[].{name,title,type}`）全部保留原义；v2 全部新增字段**可选**，缺省即旧行为。
未声明 `type` 的输入/输出一律视为 `any`，端口渲染灰色，不参与类型校验——旧插件零成本兼容。

### 5.2 类型系统

| 类型 | 语义 | 端口/徽标颜色 | 说明 |
|---|---|---|---|
| `string` | 文本 | 靛 `#4f46e5` | 含 enum（`options`/schema `enum`） |
| `number` | 数值 | 青 `#0d9488` | 含 integer |
| `boolean` | 布尔 | 琥珀 `#d97706` | |
| `object` | 对象 | 蓝 `#2563eb` | 可带 `properties`（递归） |
| `array` | 数组 | 紫 `#9333ea` | `items` 声明元素类型 |
| `file` | FengYu 文件引用 | 绿 `#16a34a` | 对应 `format: fengyu-file`、`@file:<name>` |
| `any` | 任意（未声明） | 灰 `#9ca3af` | v1 缺省；与一切类型兼容 |

**兼容规则**（连接校验与变量过滤共用，单一实现，见 §12 工具函数）：`T↔T` 合法；
`any↔T` 合法（灰口是逃生门，对应 ComfyUI 通配符思路）；`number→string` 合法但标"将转为文本"提示；
其余（如 `object→string` 整体绑定、`array→object`）**非法**，拖线时即拦截，并提示用
「JSON 取字段」适配（远期提供转换节点，对应 Langflow Type Convert）。

### 5.3 输入声明增强

```jsonc
{
  "name": "inputDirectory",
  "widget": "text",
  "title": "待发送目录",
  "type": "string",            // 新增：类型（缺省 any）
  "required": true,            // 新增：必填（现在只能从工具 JSON-Schema 推）
  "default": "",               // 已有
  "placeholder": "/share/outputs",   // 新增：占位符
  "examples": ["/share/outputs/2026-08"], // 新增：示例值（预览面板与运行表单共用）
  "help": "选择上游 Excel 拆分的输出目录", // 新增：字段级一句话帮助（比 description 更靠近输入框）
  "advanced": false            // 新增：true 时折叠进"高级设置"
}
```

### 5.4 输出声明增强：嵌套 schema + 示例 + 端口类型

```jsonc
"outputs": [
  { "name": "summary", "title": "汇总", "type": "string",
    "examples": ["共拆分 3 个 sheet，输出 12 个文件"] },
  { "name": "files", "title": "输出文件", "type": "array",
    "items": { "type": "string" },
    "examples": [["/share/outputs/2026-08/sheet1_第1部分.xlsx"]],
    "help": "拆分产物绝对路径列表，可整表绑定到目录类输入" },
  { "name": "confirmation", "title": "放行凭据", "type": "object",
    "properties": {
      "confirmationId": { "type": "string", "title": "凭据 ID" },
      "expiresAt": { "type": "string", "title": "过期时间" }
    },
    "examples": [{ "confirmationId": "cfm_9f2a", "expiresAt": "2026-08-19T18:00:00" }] }
]
```

- `properties`/`items` 递归有效：预览面板与变量树按它渲染**任意深度**路径（替代现在的一层展平）。
- `examples` 是"没有运行数据时"的第二级降级，同时喂给运行表单的占位示例。
- 工具 `outputSchema` 仍作为兜底合并（`toolOutputFields` 逻辑保留），声明优先。

### 5.5 节点级元数据

```jsonc
{
  "tool": "excel_execute",
  "label": "Excel 执行拆分",
  "kind": "action",            // 新增：action（默认）| control | start —— 见 §6.3/L6
  "help": "按「Excel 复杂拆分」节点生成的配置执行拆分，产出文件列表。",  // 新增：检查器帮助抽屉内容（支持 Markdown）
  "docsUrl": "https://…",      // 新增：可选外链
  "inputs": [...], "outputs": [...]
}
```

### 5.6 开发者工具链（治 G5）

1. **JSON Schema 发布**：新增 `toolchain/spec/flow-node.schema.json`（描述符 v2 的权威 Schema），
   `manifest.schema.json` 中 `flowNodes` 改为 `$ref` 引用它——插件作者在 IDE 里写 manifest 即得补全与校验。
2. **校验命令**：`fengyu dev validate`（devkit/cli）增加 flowNodes lint：输入名必须与工具 JSON-Schema
   参数对齐、outputs 类型合法、widget 与 type 匹配（如 `number` widget 配 `string` 报错）、
   `examples` 与类型吻合。`fengyu-plugin-dev` skill 同步收编该步骤。
3. **默认节点派生**：调色板加「显示全部工具」开关。未声明 `flowNode` 的工具按现有 legacy 派生逻辑
   （JSON-Schema → 表单）生成**降级节点**：灰色 any 端口、标"未声明"角标、详情里给出
   "补一份 flowNodes 声明以获得类型化体验" 的指引链接。browser/markdown/python 立即上架。
4. **文档**：`docs/{en,zh}/plugins/manifest.md` 补 "Flow nodes" 章节（现状缺口），配 v2 完整示例；
   新增 `docs/{en,zh}/guide/flow-nodes.md` 面向使用者的"节点数据流"概念页。

---

## 6. L2 画布与连接语义

### 6.1 类型化端口

> **实现注（2026-08-20）**：画布最终落地为**每节点单一输出口**（整节点连线语义，与引擎的
> `dependsOn` 一致）；命名字段输出不再逐个渲染端口，改为在唯一输出口的 tooltip（名称·类型·说明）
> 与变量树/检查器输出区呈现。类型着色保留在变量树、Start 节点字段行与检查器中。
> 未来若引入 IF（§6.3），分支端口将以 `true`/`false` 两个显式端口作为唯一的多端口例外。

- 输入/输出句柄按 §5.2 着色（沿用现有 5×20 色条输入句柄样式，叠加类型色）；
  `any` 保持现状灰色。
- **端口 hover tooltip**：`名称 · 类型徽标 · help/description · 示例值`。
  例如悬停 `files` 输出口：「输出文件 · array<string> · 拆分产物绝对路径列表 · 例: [/share/outputs/…xlsx]」。
  这是"这条线流的是什么"的最小可见性单元（Node-RED 端口 hover 标签模式）。

### 6.2 连接校验与辅助

- `canConnect` 在现有 环/重边/自连 检查之前加**类型检查**（§5.2 规则）。
  > **实现注（2026-08-20）**：vue-flow 会把 `v-model:edges` 的整表重赋值逐边回校验
  > （每条已存边都会带着自己的 id 再过一遍 `isValidConnection`）。校验函数必须按 id
  > 识别这种"回显"并放行——否则每次新增一条边，先前已连接的链路会被误判为重复边而
  > 静默丢弃（表现为"只支持两个节点连接，多连一个就断链"）。已修复：`canConnect`
  > 移至 `workflow.ts` 纯函数，带 id 回显直接放行（含运行中），无 id 才按新连接做
  > 重复/环/自连校验。
- **拖线即时反馈**（Langflow 霓虹模式）：从端口拖出时，画布上所有合法目标端口放大 + 呼吸高亮，
  非法目标缩小淡化；悬停在非法目标上时 tooltip 说明原因（"目录需要 string，此端口输出 object"）。
- **悬空连线 = 提问**（ComfyUI/n8n 模式）：把线拖到空白处松手，弹出节点面板，且只显示
  **输出类型与拖出端口兼容**的节点（拖出的是输入口则反向过滤）。
- **数据流高亮**（Dify Shift-hover）：按住 Shift 悬停节点，该节点所有上下游边加粗发光，
  同时其它边降透明度——长流程里看清"这条链路"。
- **边 tooltip**：hover 边显示「Excel 执行拆分 → 邮件批量发送 · 依赖（整体结果）」；
  分支边显示端口名（见 6.3）。

### 6.3 控制流节点（kind: control）

现状引擎只有"依赖边"一种语义。新增两类宿主内置节点（进 `flow-nodes/builtin.json`，走同一协议）：

**IF 条件节点**（对应 n8n IF / Dify IF-ELSE）：

```jsonc
{
  "tool": "flow_if", "label": "条件分支", "kind": "control", "icon": "mdi-source-branch",
  "inputs": [
    { "name": "condition", "widget": "condition", "title": "条件", "type": "any", "required": true }
  ],
  "outputs": [
    { "name": "true",  "title": "满足", "type": "any" },
    { "name": "false", "title": "不满足", "type": "any" }
  ]
}
```

- `condition` 是一个新的宿主 widget：左侧值（支持引用上游，同其它字段三态来源）、运算符
  （按左侧值类型过滤：文本 contains/starts with/is empty…，数值 >/=/…，布尔 is）、右侧值（字面量或引用），
  多条件 AND/OR（n8n 条件构建器模式，第一版只做单条件 + AND）。
- **边语义升级**：从命名输出端口（`true`/`false`）连出的边是**分支边**，编译进
  `AgentStep.runWhen: [{ step: N, equals: "true" }]`（新增字段，向后兼容）；边中段显示端口名标签
  （`FlowGradientEdge` 加 label 渲染，沿用渐变样式）。
- **引擎 skip 传播**：IF 步骤产出 `{ branch: "true"|"false" }`；下游步骤的 `runWhen` 不满足 → 标记
  `skipped`，其下游默认连带 skip；「汇聚」节点可中止传播（见下）。skip 步骤在画布上灰显（§8.3）。

**汇聚节点（Merge，join: all）**：多个上游依赖全部成功才执行（即现有 `dependsOn` 多依赖行为，
显式化为节点）；后续版本加 `join: any`（任一分支到达即执行，用于 IF 两路汇合）。
UI 上节点标注「等待全部上游」/「任一上游」。

### 6.4 节点重命名与禁用

- **重命名**：节点卡双击标题行内编辑（Langflow 模式）。引用仍按节点 id 存储不受影响，
  所有展示位（来源下拉、pill、变量树、运行日志）显示「自定义名 · 工具名」。
- **禁用（bypass）**：右键菜单「禁用此节点」→ 画布半透明 + 虚线边，编译时剔除并将上下游直接缝合
  （ComfyUI bypass 模式），用于临时摘除一环做 A/B 对比。存于 `graph_json` 节点 data，不动 plan 兼容性。

---

## 7. L3 节点配置面板（治"不会配置"）

### 7.1 字段三态来源控件（typedInput 模式）

把现在的"来源 `<select>` + 表单控件"两行结构，合并为**每个字段一个统一的来源控件**：

```
┌ 待发送目录 inputDirectory * ── string ──────────────────────┐
│ [✏️ 手动] [🔗 引用] [ƒ 表达式]      ← 分段按钮（默认手动）      │
│                                                              │
│ ✏️ 手动:  [ /share/outputs____________ ]  占位符+示例浮出     │
│ 🔗 引用:  [ Excel 执行拆分 ▾ › files 输出文件 (array) ]       │
│          预览: "/share/outputs/2026-08/sheet1_第1部分.xlsx"  │
│          （来自上次运行 · 查看完整 ▾）                         │
│ ƒ 表达式: [ {{node.excel_2.result.files[0]}}____________ ]   │
│          ↳ 自动补全 pill（见 7.2）                            │
└──────────────────────────────────────────────────────────────┘
```

- **✏️ 手动**：现有 widget 表单（text/number/switch/select/json/rows…）原样保留，叠加
  `placeholder` + `examples`（首个示例做占位符浮层"例：…"）。
- **🔗 引用**：打开**变量树选择器**（见 7.3），选中后渲染为 **chip**：
  `〔 Excel 执行拆分 · files 输出文件 〕`，chip 可点击换绑/清除；仍是 `{{node.<id>.result.files}}`
  的语法糖，旧数据无损。选完自动建边（保留现有 `changeInputSource` 行为）。
- **ƒ 表达式**：现在的 Arguments JSON 能力下放到字段级——多行输入 + `{{` 自动补全，
  支持字符串模板拼接（如 `"拆分-${{node.a.result.summary}}"`）。专家通道保留，但不再是唯一深路径入口。
- 来源选择持久化为 `data.sources[name] = 'manual'|'ref'|'expr'`（存 graph，不进 plan），
  Node-RED `typeField` 的持久化模式。

### 7.2 `{{` 自动补全 pill（Flowise 模式）

在 ƒ 表达式框与 textarea 类 widget（邮件正文、prompt 类）里，输入 `{{` 弹出浮层：
按「工作流输入 / 各上游节点（按拓扑距离排序）」分组，只列**该节点真实上游**的输出字段
（沿边回溯，与 `availableSourceNodes` 同源逻辑），显示 `字段名 · 类型 · 示例`，
键盘 ↑↓+Enter 选中后插入为 pill；pill 序列化仍是 `{{node.<id>.result.path}}` 文本。
非法路径（字段不存在/类型不匹配）在保存校验时红波浪线标注（§7.4）。

### 7.3 变量树选择器（类型过滤）

引用态弹出的树状面板（Dify `getNodeAvailableVars` 模式）：

```
🔗 选择数据来源
▾ ▶ 工作流输入
    email      文本      （开始节点）
▾ ▼ ● Excel 执行拆分
    ○ summary    汇总      string   "共拆分 3 个…"
    ● files      输出文件  array    [/share/… ×12]   ← 类型不匹配的灰显不可选
    ▾ ○ confirmation 放行凭据 object
        ○ confirmationId  凭据 ID  string
▾ ▶ ● 邮件批量发送（被跳过：未连接）
```

- 树按输出 `properties`/`items` 递归展开（治 G3 的深路径问题，替代一层展平）。
- **按目标字段类型过滤**：不匹配项灰显并注明原因而非直接隐藏（让用户知道"有这数据但用不上"）。
- 每行右侧 ⠿ 拖拽把手 + ⧉ 复制路径（拖拽映射见 §8.2，复制路径见 Node-RED 模式）。

### 7.4 字段级校验与帮助

- 必填空值：输入框红框 + 面板顶部缺口计数（保留现有 `missingInputs` 徽标，视觉强化到字段级）。
- 引用路径校验：保存时对每个 `{{node.*}}` 引用走一遍输出 schema 树（新函数
  `validateReferencePath`，与 `undeclaredWorkflowInputReferences` 并列），未知路径标错、
  类型不匹配标警告（"files 是 array，此输入期望 string——是否要用 files[0]？"，一键修正建议）。
- **帮助抽屉**：检查器右上 `?` 按钮展开节点 `help`（Markdown）+ 字段清单 + 输出契约 +
  可选 `docsUrl`。Node-RED "help 即契约文档" 模式，`flowNodes` 声明一次、面板与文档同源。

---

## 8. L4 数据可见性与调试（治"不知道什么数据"）

### 8.1 三级数据降级（诚实标注来源）

任何展示"上游/本节点输出"的地方（预览面板、变量树、端口 tooltip、输出查看器）统一三态：

| 级别 | 数据源 | 标注文案（i18n） |
|---|---|---|
| ① 声明 | 描述符 `outputs`（含工具 outputSchema 兜底） | 「节点声明」 |
| ② 示例 | 描述符 `examples` | 「示例值」 |
| ③ 运行 | 上次执行的真实输出（运行历史已有存储） | 「上次运行 · 14:32 · 12 KB」 |

无 ②③ 时展示 ① 且给行动号召按钮：「运行到此节点，查看真实数据 →」（n8n 空状态模式，见 §8.2）。

### 8.2 单步运行与"运行到此"（引擎配套，见 §10.2）

- 检查器与右键菜单新增：
  - **「只运行此节点」**：用当前已填参数 + 上游**上次运行结果或 pin 值**作为输入，单独执行该工具；
  - **「运行到此为止」**：从起点执行到当前节点后停下（`stopAfterNode`）。
- 两者都复用现有 `POST /api/agent/run`（临时 plan）或新增 partial-run 参数，结果写回该节点的
  "上次运行" 缓存（`data.lastRun`，随 graph 保存），供 §8.1 的 ③ 级展示。
- **输出 pin / mock**（n8n Edit Output 模式）：输出查看器里「固定此结果」把 ③ 级数据钉住
  （`data.pinnedOutput`，存 graph_json）；后续整流运行时引擎对该步直接采用 pinned 值跳过执行
  （plan 编译为字面量 result，向后兼容）；「编辑模拟数据」允许直接改 JSON 伪造上游。
  节点卡右下角 📌 角标标识已 pin。

### 8.3 输出查看器与变量检查器

- **输出查看器**：检查器输入区下方新增「输出」区：每个输出字段一行
  `名称 · 类型 · ③或②值摘要`，展开看 JSON 树（上限 64KB，超出折叠 + 「复制完整 JSON」）。
- **变量检查器**：`FlowExecutionPanel` 升级为表格（Dify Variable Inspector 模式）——
  每步一行：`节点 · 耗时 · 状态 · 输入摘要 · 输出摘要`，点开看完整 JSON；
  每行字段 hover 出 ⧉「复制引用路径」（复制 `{{node.<id>.result.<path>}}`，可直接粘进表达式框）。
  现有 fork/rewind 保留，与 pin 配合构成"改一处、续跑下游"的调试闭环。

### 8.4 运行状态上画布（L5 一并落地）

- 节点卡右上角状态徽标：`待运行(灰) → 运行中(旋转,边动画) → 成功(✓+耗时) / 失败(✗,点击看错误) / 已跳过(灰,虚线)`。
  数据源是现有 SSE 步骤事件（`agentRunStream.ts` 已有 seq 去重），新增 `step.skipped` 事件类型。
- 边动画：源节点运行中时其出边加流动虚线（vue-flow edge class 切换）。
- 失败节点点击直达：徽标点击 → 打开检查器 → 定位到出错字段/显示错误详情 +
  「查看该节点实际上游输入」按钮（很多报错根因是上游数据形状不对）。

---

## 9. L6 开始节点、引导与模板

### 9.1 开始节点（Start，kind: start）

- 每张流程**有且仅有一个**，新建流程自动放置于画布左侧，调色板不再显示；旧流程无 Start 时
  在工具栏提示「点击补一个开始节点」（迁移即把 `input_schema_json` 可视化，数据不动）。
- **可视化输入设计器**取代 `FlowSettingsDrawer` 的手写 JSON Schema：

```
┌ ▶ 开始 ────────────────────────────────┐
│ 运行此流程时需要填写：                   │
│ ┌ email    收件邮箱   [文本▾] ✓必填  ✕ ┐│
│ │ sheetNo  Sheet 序号 [数字▾]  ☐必填  ✕ ││
│ │ roster   花名册     [文件▾]  ✓必填  ✕ ││
│ └ ＋ 添加输入字段                       ││
└────────────────────────────────────────┘
```

  字段类型：文本/段落/数字/开关/下拉（选项内联编辑）/JSON/文件（fengyu-file）——即 Dify
  User Input 的类型集，与现有运行表单（`FlowRunDialog` 已按 schema 渲染）一一对应。
  每字段可填 `示例值`（喂给运行表单占位与 §8.1 ②级）。
- 持久化仍是 `input_schema_json`——Start 节点只是它的可视化编辑器；设置抽屉保留
  「JSON 模式」切换给高级用户。Start 的输出端口即「各输入字段」（string/number/file…按声明着色），
  引用语法就是现有 `{{inputs.email}}`。

### 9.2 引导与模板

- **空画布引导**：新建流程 = Start 节点 + 一张便签（现有 sticky note 基建）写三步指引：
  「① 从左侧拖一个节点 → ② 点节点配置参数 → ③ 用🔗把上游输出接进来」。
- **模板库扩充**：现有 1 个模板扩到覆盖每个节点类别的 4-6 个（`workflowTemplates.ts`），
  模板自带输入示例值与教练便签；「另存为模板」让用户沉淀自己的流程（存 `~/.fengyu` 或库表，远期）。
- **AI 生成/修改流程**（远期 Phase 4）：现有 `run_current_flow` 已证明宿主可绑流程上下文工具；
  扩展一个 `edit_current_flow` 工具（增删节点/连线/填参，操作 graph JSON），FlowChatPanel 发出的
  修改建议先以**画布 diff 预览**（新节点虚线入场）呈现，用户点「应用」才落盘——n8n AI Builder 的
  审慎版。

---

## 10. 引擎与后端改动（支撑层）

### 10.1 类型化结果与路径升级（`AgentRunner.java`）

- 现状 `Map<Integer,String>` 全字符串。改为内部 `record StepResult(String raw, JsonNode parsed, Instant finishedAt)`
  ——解析一次、缓存复用；对外持久化/REST 响应仍是字符串，行为不变。
- `STEP_RESULT` 路径正则（74-76 行）增加**数组下标**段：`result.files[0]`、`result.rows[2].name`；
  `referencedResult`（764-776）同步支持 JsonNode 数组导航，错误信息带上可用字段列表
  （"Tool result has no output field 'fil' — available: summary, files, confirmation"）。
- **后端原生接受 `{{node.<id>.result…}}`**：编译期已重写为 `steps.N`，此处仅作为防御性兜底
  （API 直接提交 plan 的调用方），解析时按 graph 的 id→index 映射转换。

### 10.2 partial-run 与 pin

- `POST /api/workflows/{id}/run` 请求体新增可选 `stopAfterNode`/`onlyNode`/`mockInputs`
  （复用 `compile` + 截断 plan；`onlyNode` 走 `POST /api/agent/run` 单步 plan 更简单）。
- plan 编译时若节点 `data.pinnedOutput` 存在 → 该步 args 直接绑定字面量结果、`dependsOn` 保留
  （保证顺序）但执行器跳过工具调用（`AgentStep` 新增可选 `pinnedResult` 字段，向后兼容）。
- SSE 新增 `step.skipped` 事件；`step.end` 增加耗时字段。

### 10.3 分支执行

- `AgentStep` 新增可选 `runWhen: [{step, equals}]`；`executeSteps`（328-443）在派发每层前
  评估 `runWhen`：不满足 → 记 `skipped`，其下游在依赖全部 skipped 时连带 skip；
  汇聚节点 `join: any` 打断传播（任一活跃依赖完成即执行）。审批门与工具守卫逻辑不动。

### 10.4 校验前移（`WorkflowService.java`）

- `validateGraph`（164-187）目前只查形状。新增：引用路径存在性（按描述符 outputs 树）、
  绑定类型兼容性（§5.2 规则）——前端已拦，后端兜底（防止 API 直写的 plan）。
- 错误信息全部本地化键化，前端 `friendlyWorkflowError` 映射表同步扩充。

---

## 11. 交互细节清单（附录级汇总）

**快捷键**：`Tab`/`N` 打开节点面板（现有搜索框升级为浮层）；双击画布空白 = 节点搜索；
`Shift`+hover = 数据链路高亮；`Del` 删除选中；`Cmd/Ctrl+Z / +Shift+Z` 撤销重做（现有）；
`0` 复位缩放、`1` 适配全图（n8n 惯例）；`Esc` 关闭浮层。

**节点右键菜单**：打开配置 · 只运行此节点 · 运行到此为止 · 固定/取消固定结果 · 重命名 ·
复制节点 · 禁用（bypass） · 删除。

**画布右键菜单**：添加节点（按上次悬空连线类型过滤）· 添加便签 · 整理布局（自动排列，远期）。

**空状态行动号召**（全部 i18n，en/zh 同步）：上游预览无数据 → 「运行到此节点，查看真实数据 →」；
变量树只有声明 → 「显示的是节点声明，运行后可见真实值」；必填缺口 → 「还有 N 项必填未完成 → 定位」。

---

## 12. 实施路线图

| 阶段 | 内容 | 主要文件 | 验收标准（对痛点） |
|---|---|---|---|
| **P0 快赢**（纯前端，无协议改动） | 端口 tooltip（类型+说明+现有描述）；字段 placeholder/hint；`Tab/N`+双击搜索；节点重命名；引用值 chip 化显示 + 复制路径按钮 | `WorkflowToolNode.vue`、`FlowNodeInspector.vue`、`FlowBuilder.vue` | 悬停任何端口能说出它输出什么、什么类型（G2/G3 起步） |
| **P1 协议 v2 + 类型端口 + 变量树** | `flow-node.schema.json` + devkit 校验 + manifest 文档；类型化端口着色与拖线校验/高亮；变量树（递归 schema + 类型过滤 + 示例值）；「显示全部工具」默认节点派生 | `toolchain/spec/`、`toolchain/cli|dev`、`builtin.json`、`WorkflowToolNode.vue`、新 `FlowVariableTree.vue`、`FlowNodeInspector.vue` | 新插件声明获 IDE 补全；连不上的线当场拦下；引用不再需要手写深路径（G2/G3/G5） |
| **P2 数据可见性与调试** | 三态预览面板 + 拖拽映射；`{{` pill 补全；单步运行/运行到此 API+UI；输出查看器 + pin/mock；画布运行徽标 + 边动画；变量检查器 | `FlowNodeInspector.vue`、新 `FlowDataPreview.vue`、`AgentRunner.java`、`WorkflowExecutionService.java`、`AgentController.java`、`FlowExecutionPanel.vue` | 用户能在 30 秒内回答"这个节点上次输出了什么"；不用跑通全链也能调试单节点（G3/G4） |
| **P3 控制流 + 开始节点** | Start 节点与可视化输入设计器；IF/分支边/skip 传播；汇聚节点；字段级表达式（ƒ 态）；禁用 bypass | `builtin.json`、`AgentStep.java`、`AgentRunner.java`、`WorkflowService.java`、`FlowSettingsDrawer.vue` 退役为 JSON 模式 | 普通用户全程不写 JSON 即可建带分支的流程（G1 收口） |
| **P4 AI 节点与 AI 编辑**（远期） | LLM 节点（prompt 组装 + JSON 结构化输出 schema，Flowise Return Data Schema 模式）；`edit_current_flow` 工具 + 画布 diff 预览；模板库扩充/自存模板 | `builtin.json`、新 `ai/tools/` 工具、`AiToolRegistry.java`、`FlowChatPanel.vue` | 用自然语言改流程且每一步可预览可回滚 |

每阶段独立可发布；P1 起 `docs-updater` 同步 `docs/{en,zh}`，`CHANGELOG.md` 记录协议增量。

---

## 13. 兼容性与风险

- **旧数据**：v1 描述符缺 `type` → `any`，不参与校验，行为与今天完全一致；旧 `graph_json`/`AgentPlan`
  原样加载（`runWhen`/`pinnedResult`/`sources` 均为可选新字段）。
- **不做完整表达式语言**（如 n8n 的 JS 表达式）：理由是受众（普通用户）与安全（表达式在后端求值，
  需沙箱）；受控占位符 + 字符串模板已覆盖现有场景。若未来需要，再评估受限表达式子集。
- **预览数据量**：JSON 树渲染上限 64KB、数组摘要 `×N 计数`（n8n column limit 模式），
  防止大结果卡死画布。
- **pin 的正确性风险**：pinned 值可能让用户误以为流程"真的跑过了"。角标 + 运行面板明示
  「本步使用固定数据」；发布（publish）时提示存在 pin（可一键清除）。
- **vue-flow 能力边界**：多输出句柄、边 label、edge class 动画均为现成能力；
  拖线高亮需在 drag 事件里操作节点 class，属常规定制，无库替换风险（@vue-flow/core ^1.48.2）。

---

## 附录 A：描述符 v2 完整示例（email_send_batch 升级版）

```jsonc
{
  "tool": "email_send_batch",
  "label": "邮件批量发送",
  "kind": "action",
  "color": "#e07a5f",
  "icon": "mdi-email-send-outline",
  "help": "把一个目录下的文件作为附件，按标签组批量发送。需要先在「邮件账户」中配置账户。",
  "inputs": [
    { "name": "accountId", "widget": "select", "title": "发件账户", "type": "string",
      "required": true, "source": { "method": "email_accounts_list" },
      "help": "从已配置的邮件账户中选择" },
    { "name": "recipientGroupTagIds", "widget": "select", "title": "收件标签组", "type": "array",
      "source": { "method": "email_tags_list", "multiple": true },
      "help": "收件人按标签组圈定" },
    { "name": "inputDirectory", "widget": "text", "title": "待发送目录", "type": "string",
      "required": true, "placeholder": "/share/outputs",
      "examples": ["/share/outputs/2026-08"],
      "help": "通常绑定上游 Excel 拆分的 files 输出所在目录" },
    { "name": "subject", "widget": "text", "title": "邮件主题", "type": "string",
      "required": true, "examples": ["【自动】8 月报表拆分结果"] },
    { "name": "body", "widget": "textarea", "title": "正文", "type": "string",
      "help": "支持 {{ }} 引用上游输出，如把拆分汇总写进正文" },
    { "name": "commonAttachments", "widget": "json", "title": "公共附件", "type": "array",
      "advanced": true }
  ],
  "outputs": [
    { "name": "summary", "title": "发送汇总", "type": "string",
      "examples": ["已向 3 个标签组发送 12 封邮件"] },
    { "name": "confirmation", "title": "放行凭据", "type": "object",
      "properties": {
        "confirmationId": { "type": "string", "title": "凭据 ID" },
        "expiresAt": { "type": "string", "title": "过期时间" }
      },
      "examples": [{ "confirmationId": "cfm_9f2a", "expiresAt": "2026-08-19T18:00:00" }],
      "help": "接「放行发送」节点的 confirmationId" }
  ]
}
```

## 附录 B：引用语法规范（现状 + 增量）

| 形态 | 语法 | 状态 |
|---|---|---|
| 工作流输入 | `{{inputs.<name>}}`（含点路径） | 现有 |
| 上游结果（编辑态） | `{{node.<nodeId>.result}}` / `{{node.<nodeId>.result.<a.b.c>}}` | 现有 |
| 上游结果（持久态 plan） | `{{steps.<index>.result...}}` | 现有（编译时重写，用户不可见） |
| 数组下标 | `{{node.<id>.result.files[0]}}`、`result.rows[2].name` | **新增**（P2） |
| 上一次执行结果 | `{{last.result}}` | 现有（保留） |
| 字符串模板 | 纯文本内嵌 `{{…}}`（如 `拆分完成：{{node.a.result.summary}}`）渲染为字符串 | 现有规则保留（对象/数组内嵌渲染为 JSON 文本） |
| 分支端口 | `{{node.<if>.result.branch}}`（IF 节点产出，一般无需手写） | **新增**（P3） |

## 附录 C：本方案新增/改动文件一览

- 新增：`toolchain/spec/flow-node.schema.json`、`frontend/src/components/agent/FlowVariableTree.vue`、
  `FlowDataPreview.vue`、`FlowOutputViewer.vue`（可并入检查器）、`docs/{en,zh}/guide/flow-nodes.md`
- 修改：`flow-nodes/builtin.json`（v2 字段 + flow_if/merge/start 声明）、`WorkflowToolNode.vue`
  （类型色/徽标/pin 角标）、`FlowNodeInspector.vue`（三态来源控件/帮助抽屉/输出区）、
  `FlowBuilder.vue`（连接校验/悬空连线/快捷键/重命名/禁用）、`workflow.ts`
  （路径校验/递归展平/sources 持久化）、`AgentStep.java`（runWhen/pinnedResult）、
  `AgentRunner.java`（StepResult/下标路径/skip）、`WorkflowService.java`（校验前移/partial-run）、
  `AgentController.java`（run 参数）、`FlowSettingsDrawer.vue`（降级为 JSON 模式）、
  `i18n/{en,zh}.json`（全部新文案成对）、`toolchain/cli|dev`（validate）、
  `docs/{en,zh}/plugins/manifest.md`（Flow nodes 章节）

## 附录 D：调研来源

- n8n — docs.n8n.io（Work with nodes / Understand data structure / Pin and mock data /
  Flow logic / Node UI elements）；github.com/n8n-io/n8n（interfaces.ts、IfV2/SwitchV2 源码、en.json locale）
- Dify — docs.dify.ai（Workflow、User Input、IF/ELSE、Iteration、Variable Aggregator、
  Step run、Variable Inspector、Shortcuts、Snippets）；github.com/langgenius/dify
  （web/app/components/workflow/{nodes,hooks,block-selector}）
- Langflow — docs.langflow.org（Components、Data types、Playground）；
  github.com/langflow-ai/langflow（reactflowUtils.ts、handleRenderComponent）
- Flowise — docs.flowiseai.com（AgentFlowV2）；github.com/FlowiseAI/Flowise
  （customMention、useAvailableVariables）
- Node-RED — nodered.org/docs（TypedInput widget、Messages、Debug/Context/Help sidebar、
  Creating nodes）
- ComfyUI — docs.comfy.org（Nodes、Links、Subgraph、Template）；comfyui-wiki（widget 转输入、Primitive）
