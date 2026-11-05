# 插件 App Cards 整体设计（2026-11）

> **状态**：设计文档（方案阶段，未实施）。基线 `release/4.1.0 @ 2114f52b`，文中接缝为实码钉位
> （文件:行号）。背景分析见 `PLUGIN-TO-MCP-ANALYSIS-2026-11.md` §6——本设计即其中"渲染层合流"
> 路线的具体化。
> **一句话**：插件工具在聊天中获得与 MCP Apps 同级的"结果附挂 UI"（卡片/弹窗），复用 MCP 的
> `ui` 描述符管线与插件的 iframe 宿主面，**worker 协议与桥协议零变更**。

---

## 0. 目标 / 非目标

**目标**
1. AI 调用插件工具后，聊天流内弹出该插件声明的界面（收起卡片 → 点击展开为内嵌面板或对话框），
   体验对齐 MCP Apps（`McpAppCard`）。
2. 弹窗即"控制台页的聊天态"：享有 sdk-ts 全桥（`rpc.invoke`、`files.*`、theme/locale 推送、notify），
   权限模型与工具页控制台完全一致。
3. 渲染层合流：`ToolCard` 按 `ui.kind` 分发，MCP 与插件共用同一条 SSE→store→卡片管线。
4. 声明式优先：v1 由 manifest 静态声明（哪个工具带哪个视图），宿主零猜测、可回放。

**非目标（v1 明确不做）**
- worker 按调用动态返回视图（P2；需输出信封剥离，见 §9）。
- 运行中向用户要输入（类 elicitation；worker 协议无反向通道，另立项）。
- 弹窗内跨插件/宿主 AI 工具调用（v1 只允许本插件 rpc 方法，与控制台同权）。
- 把插件 UI 塞进 `McpAppsController`/`ui://` 资源面（信任模型与桥能力都不匹配，分析报告已否决）。
- 桌面壳真 OS 窗口（容器策略预留：`AppCard` 换壳即可，管线不动）。

---

## 1. 现状接缝（实码钉位）

| 环节 | 位置 | 现状 |
|---|---|---|
| 结果信封 | `ai/AiToolResult.java` | record 已含第三分量 `ui`（Map），MCP 专用 |
| 注入点 | `ai/service/ToolLoopDriver.java:1437-1442` `fireToolEvents` | **唯一**choke point：`McpUiRuntimeHolder.attachUi(name, args, result)`，模型可见文本不动 |
| MCP 投影 | `ai/mcp/apps/McpToolUiMeta.java:67` `McpToolUiDescriptor(resourceUri, visibility, displayMode, csp, prefersBorder, surface, showInline)` + `UiToolInfo(serverId, serverName, toolName, wireName, descriptor)` | 描述符来自服务器 `_meta`，原始结果走 capture store |
| SSE | `web/controller/AiController.java:1255` `onToolResult` | `payload.ui` 非空才放，附加字段 |
| 前端解析 | `frontend/src/lib/toolActivity.ts:79-106` | `parseMcpToolUi`：要求 `serverId` + `resourceUri` 以 `ui://` 开头 |
| 前端分发 | `frontend/src/components/chat/ToolCard.tsx:251-252` | `if (activity.ui) return <McpAppCard ui scopeId={`tool:${id}`}/>` |
| 插件 UI 服务 | `web/controller/PluginRuntimeController` | `/plugin-runtime/{id}/**`：一次性 `uiTicket`（`POST .../ui-ticket`）、严格 CSP（`connect-src 'none'`）、`Sec-Fetch-Site` 门 |
| 插件 iframe 宿主 | `frontend/src/pages/PluginPage.tsx`（318 行） | sandbox 属性、`?shellOrigin=` origin 钉死、environment 推送、rpc/files 代理 |
| 工具目录 | `ai/config/AiToolRegistry.java:128` | 插件 `aiTools[]` 包装处持有 manifest 声明（effect 等）——`app` 声明的权威来源 |

**结论**：MCP 侧管线从信封到渲染全部现成且是"附加式"设计；插件缺的只是——描述符从哪来（§3.2）、
前端怎么认（§5.1）、卡片怎么宿主（§5.2）。

---

## 2. 总体架构

```
model tool call ──► ToolLoopDriver.fireToolEvents (choke point)
                        │  UiProjection 链：先问 MCP（现状 Holder），未中再问 Plugin 目录
                        ▼
              AiToolResult.ui = {kind:'plugin', pluginId, path, mode, title}   (mcp: 现状不变)
                        │  模型可见 output 不动
                        ▼
              SSE `tool` 事件 payload.ui ──► rollout 持久化（声明性，可回放）
                        ▼
              toolActivity.parseToolUi（union 判别）──► ToolCard
                        ▼
              AppCard 分发器 ──► McpAppCard   （现状：prepare → /sandbox/app/{id}）
                            └─► PluginAppCard（新：现取 uiTicket → /plugin-runtime/{id}/{path}
                                                + sdk-ts 桥，宿主逻辑与 PluginPage 共享）
```

---

## 3. 协议面设计

### 3.1 SSE `ui` 描述符 union

后端 `ui` Map 增加一个判别键，前端类型对应拆分：

```
// 现状（MCP）不动：有 resourceUri(ui://…) + serverId
// 新增（插件）：
{ kind: 'plugin',
  pluginId: string,          // manifest id
  path: string,              // ui 子树内相对路径，如 "ui/tool.html"
  mode: 'card' | 'dialog',   // card=展开内嵌面板（默认）；dialog=模态弹窗
  title?: string,            // 卡片标题（i18n 解析后的展示值，缺省用插件名+工具名）
  params?: object }          // 可选启动参数，作为 query 附加（不做 postMessage 注入）
```

判别规则：`kind === 'plugin'` → 插件分支；否则按现状（`resourceUri` 存在 → MCP）。旧前端忽略
未知键，天然向后兼容。**`uiTicket` 绝不进描述符**（描述符随 rollout 落盘，票必须短命）。

### 3.2 manifest：`aiTools[].app`（v1 静态声明）

```
"aiTools": [{ ..., "app": { "path": "ui/tool.html", "mode": "card", "title": "..." } }]
```

- 可选对象字段；`path` 必须落在 `ui.entry` 同一 ui 子树内且包内存在（安装期校验，规则同
  `ui.entry`）；`mode` 枚举缺省 `card`；`title` 可被 `i18n` 表按 locale 覆盖（`ManifestI18n`
  扩展 `aiTools[].app.title` 键）。
- schema 是 `additionalProperties:false` 的全量契约——加字段意味着：**宿主先发**（新宿主认旧清单，
  旧宿主拒新清单），工具链（schema/CLI check/devkit 生成器）随后跟版。smoke-plugin 夹具同步加示例。
- 插件级兜底：无 `app` 声明但存在 `ui.entry` 的工具，P0 阶段宿主注入
  `{path: ui.entry, mode:'card'}`（"打开插件面板"语义）；P1 起仅 `app` 声明者注入，避免噪声。

### 3.3 uiTicket 生命周期

卡片挂载（用户点击展开）→ `POST /api/plugin-runtime/{id}/ui-ticket` 现取 → 拼进 iframe URL。
TTL/一次性/SseFetch-Site 门全部沿用现状；对话回放/重开 = 重新渲染 = 重新取票，无安全衰减。

---

## 4. 后端设计

1. **投影链而非并行 Holder**：`fireToolEvents` 处把 `McpUiRuntimeHolder.attachUi` 泛化为
   `UiProjection.attach(name, args, result)`——内部先查 MCP（现状逻辑原样），未命中再查插件目录。
   保持单一 choke point 与两条既有不变式：*模型文本不动*、*投影失败不破坏工具事件*（沿用
   attachUi 的 catch-degrade 契约）。
2. **插件目录反查**：`AiToolRegistry` 暴露 `pluginTool(wireName) → {pluginId, app 声明}`（它已在
   `pluginCallback()` 包装处持有 manifest 的 `aiTools` 声明，加一张反查表即可）。不引入工具名前缀
   约定——wire name 的权威形态以注册表为准（实施时确认，见 §9）。
3. **持久化**：tool 事件的 extras 落 `ui` map（声明性字段，回放可重新渲染）。`chatExport` 忽略
   `ui`（导出纯文本语义不变，`chatExport.test.ts` 补钉）。
4. **AiToolResult 无结构变更**（第三分量复用）；`AiController.onToolResult` 无变更。

## 5. 前端设计

1. **类型与解析**：`ToolUi = McpToolUiInfo | PluginToolUiInfo`；`parseMcpToolUi` 改名
   `parseToolUi` 做 union 判别（保留 `ui://` 分支原逻辑）。
2. **分发**：`ToolCard` 的 `activity.ui` 分支改为 `<AppCard ui scopeId>`；`AppCard` 按 `kind`
   渲染 `McpAppCard`（原样搬入）或 `PluginAppCard`（新）。scopeId 沿用 `tool:{callId}`，天然支持
   同工具多次调用各弹各卡。
3. **PluginAppCard**：
   - 收起态：图标 + title + "打开"——**不自动加载 iframe**（对齐 MCP 卡的确认语义：人先点）。
   - 展开：现取 uiTicket → `<iframe src="/plugin-runtime/{id}/{path}?shellOrigin=…&uiTicket=…">`，
     sandbox 属性/origin 钉死/referrerPolicy 照抄 `PluginPage`；`mode:'dialog'` 用现有 Dialog
     组件承载同一 iframe 宿主。
   - 卸载即 dispose iframe；worker 无需停（`PluginProcessManager` 本就按插件缓存，卡片与控制台、
     AI 调用共享同一 worker 会话——这正是"弹窗即控制台"的一致性来源）。
4. **共享宿主桥**：从 `PluginPage.tsx` 抽 `usePluginHostBridge(pluginId)`（environment 推送订阅、
   rpc 代理、files 管线、notify 转发），`PluginPage` 与 `PluginAppCard` 共用，消除两份漂移。
5. **样式**：遵循公共样式体系——卡片外壳用 `cx-*` kit + `features/chat.css` 增补，不写内联静态样式。

## 6. 安全模型（资产 → 门，全部沿用现状，无新面）

| 资产 | 门 |
|---|---|
| iframe 资产 | 一次性短 TTL uiTicket + `Sec-Fetch-Site` 门 + 严格 CSP（`connect-src 'none'`，弹窗内一切后端访问经桥代理） |
| 桥调用 | sdk-ts 逐方法能力检查 × manifest `permissions`（与控制台同一套） |
| AI 发起的调用 | 照旧 `ChatToolApprovalGate` effect 门——弹窗是**结果后** UX，不碰审批语义 |
| 弹窗内文件访问 | `files.*` 原生/上传选择器，用户驱动，与控制台同权 |
| 持久化 | rollout 只存声明性描述符；票不落盘 |
| 自动化风险 | 无自动加载（点击展开）；dialog 关闭即销毁 |

## 7. 工具链与文档

`toolchain/spec/manifest.schema.json`（+fixtures）→ CLI `check`/`build`（app.path 包内存在性）→
devkit `@FengYuAiTool` 增 `app` 属性与生成器 → `fengyu-plugin-dev` skill 与 `docs/{en,zh}/plugins/`
指南补章节（真产品功能，入 docs 站）→ smoke-plugin 夹具加 `app` 视图 → `scripts/e2e-smoke.sh`
断言 SSE `ui` 字段与 `/plugin-runtime` 服务面。工具链随独立小版本（2.1.x → 2.2.0）发。

## 8. 分阶段交付与门禁

| 阶段 | 内容 | 门禁 |
|---|---|---|
| **P0**（1–2 天） | ui.entry 兜底注入 + `AppCard`/`PluginAppCard` + 共享桥抽取；无 schema 变更 | 后端 plugin 测试 + 前端 typecheck/test:unit + e2e-smoke + diff-check |
| **P1**（3–5 天） | `aiTools[].app` 全家桶：schema/校验/CLI/devkit/i18n/夹具/docs/skill | P0 门禁 + toolchain 测试 + docs build（sync:changelog 流程照旧） |
| **P2**（另立项） | 动态视图（输出信封 `x-fengyu-app-view` 剥离注入）、运行中交互、桌面真窗口容器 | 届时另评估 |

## 9. 风险与开放问题

1. **插件工具 wire name 形态**：以 `AiToolRegistry` 注册表反查为准，实施第一步先确认（避免前缀
   假设）；`plugin://` mention 路径不影响（那是激活语义，不是名字）。
2. **transcript 卡片堆积**：长会话多次调用同工具 → 多张卡。沿用 `scopeId=tool:{callId}` 不去重，
   收起态视觉弱化即可；如需"仅最新展开"属产品微调。
3. **P2 动态视图的模型可见性**：输出信封字段必须从送模型文本中剥离——`ToolResultBudget` 已有
   裁剪先例，但需明确"剥离发生在投影层而非预算层"。
4. **旧插件新宿主**：无 `app` 声明的存量插件在 P1 后退回无卡（P0 的 entry 兜底是过渡行为）——
   CHANGELOG 明示，避免插件作者误以为 entry 会永远弹。
