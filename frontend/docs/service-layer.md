# 前端服务接口层与平台能力层 — 设计与实施(一步到位版)

状态:**已实施**(2026-09-23)。本目录(`frontend/`)是 React 重写后的正式前端工程;
`src/platform/` 与 `src/services/` 是一次建成的目标架构 —— **不做绞杀者式渐进迁移**:
Vue 工程(`frontend/`)整体冻结在它的 `api/client.ts` 上,直到被本工程替代后删除。

背景:ZCode 架构分析(借鉴 `@zcode/client` 服务代理面 + `IPlatformService` 能力注入,
不搬其进程拓扑 — JVM 后端永远进程外,桌面传输保持 loopback HTTP)。原绞杀者方案
(见 git 历史)因 React 迁移并行启动而废弃:迁移 AI 已把 Vue 的 api 层逐字节复制进
`src/api/`(client.ts 1065 行 god-object、28 个引用文件、6 个文件嗅探 `window.fengyu`),
与其逐域绞杀,不如直接建成目标层,让迁移的视图/store 一步消费到位。

## 1. 已交付的结构

```
src/platform/                 # 平台能力层(框架无关,唯一可触 window.fengyu 之处)
  types.ts                    #   PlatformService 接口 + PlatformCapabilities 能力位
  desktop.ts                  #   桌面实现:转发 preload bridge;能力位做一次 typeof 探测
  web.ts                      #   Web 实现:全部方法可调用,Vite env 读 apiBase/token
  url.ts                      #   backendUrl / pluginAssetUrl / pluginAssetIsolated(原 api/config.ts 逻辑)
  index.ts                    #   getPlatform() 单例
src/services/                 # 服务接口层(框架无关:禁止 import react / i18n / axios)
  index.ts                    #   FengYuServices 聚合 + services 单例 + configureServices({locale})
  types.ts                    #   原 api/types.ts 的拷贝(切换时旧文件删除,此份转正)
  system.ts settings.ts ai-config.ts chat.ts workspace.ts agent.ts workflow.ts
  mcp.ts skill.ts plugin.ts store.ts infinia-store.ts app-update.ts notifications.ts account.ts
                              #   每域:接口 + REST 实现同居一文件;方法名按域重新命名
  impl/http.ts                #   唯一 axios 实例(token/Accept-Language/错误提取/AUTH_EXPIRED 事件)
  impl/streams.ts             #   三条 SSE 的传输引擎:ticket 铸造 + 各自重连策略 + replay 去重
  parity.test.ts              #   切换安全网:legacy api 146 个方法 → 域方法全覆盖对照
  agent.test.ts               #   agent 纯函数助手(seq 去重/retry 归一化/gate 提取)单测
```

验证状态:`yarn vitest run src/services/ src/platform/` 全绿(11 用例);
`tsc --noEmit` 中本两层 0 错误(工程内其余报错均为迁移在途文件的预存错误)。

## 2. 平台能力层规则

1. **方法永远可调用,capability 只控制 UI 入口显隐。** 桌面/Web 的分支 =
   `platform.capabilities.nativeFileDialogs` 等,不是 `typeof window.fengyu?.x === 'function'`
   探测(探测已下沉到 desktop.ts 一处)。
2. `window.fengyu` 仅 `platform/desktop.ts` 可访问;`electron-env.d.ts` 仍是 preload 契约声明。
3. 更新分两条线,勿混:`platform.checkForUpdates()` = Electron 自动更新(能力位 `desktopUpdater`);
   `services.appUpdate.check()` = 后端驱动的便携版/Web 更新检查。
4. Web 兜底语义:pickFile/pickDirectory → null(调用方走 `<input type=file>`);
   confirm → 应用内对话框(lib/appDialogs,桌面/Web 同一套,已不再用
   window.confirm / Electron 原生 message box);openExternal → window.open(http(s) 校验保留);
   showNotification → false;downloadAndInstall → throw。

## 3. 服务接口层规则

1. **UI/store 只 import `@/services`(域模块或聚合单例)与 `@/platform`。**
   禁止 import `@/api/client`、axios、直接 fetch 后端。
2. **i18n 不进服务层**:错误以结构化形式上抛(chat 流的 `{ code, message }`,
   code ∈ `ticket_failed | stream_lost | stream_ended | <后端 code>`);Accept-Language 由
   bootstrap 注入:`configureServices({ locale: () => i18n.language })`(main.tsx 一行)。
3. **三条 SSE 的语义差异是契约的一部分**(impl/streams.ts):
   - chat:单发不重连(后端掉线即取消生成),错误走结构化 code;
   - agent run:有界重连(5 × 800ms)+ 单调 `seq` replay 去重(去重在传输内,
     新开流重置高水位,重连保留);`onReconnecting/onTransportLost/onOpenFailed` 通知调用方;
   - notifications:无界重连(1s→15s 封顶退避),`onOpen` = "请重拉历史补缺口"。
4. 方法名按域重命名(`api.aiChat` → `chat.send`,`api.agentRuns` → `agent.runs`),
   **完整映射表就是 `parity.test.ts` 的 MAPPING** —— 改方法名必须同步改它。
5. Vue 侧 `useAgentRunStream` 里的纯负载助手已移植到 `services/agent.ts`
   (`agentStepRetryFromData / agentGateIdFromData / failActiveAgentSteps /
   isAgentEventReplayed / newAgentStreamSeqState`);React 的 run 状态机 store
   消费 `agent.openRunStream` 的原始事件 + 这些助手,自己维护状态。

## 4. AI 协作契约(谁在改 frontend 谁读这节)

**新代码(视图/store/组件):**
- 后端调用一律 `services.<domain>.<method>()`;桌面能力一律 `getPlatform()`。
- store 测试 mock `services` 的域接口(vi.mock `@/services`),不再 mock `@/api/client`。
- 聊天/代理/通知的流式 UI 接 `chat.openChatStream / agent.openRunStream / notifications.subscribe`,
  错误 code 在调用方映射 i18n 文案(沿用 Vue 侧 `agent.streamTicketFailed` 等键)。

**既有 28 个 `api.*` 调用点的改写:**按 `parity.test.ts` 的 MAPPING 逐个替换
(`api.X(...)` → `services.<domain>.<method>(...)`),改完的文件删除 `@/api/client` import。
**不要往 `src/api/client.ts` 加新方法** —— 新端点直接进对应域文件。

**仍在 Vue 侧的对应物(React 港区缺什么照什么):**
- 插件宿主视图(iframe postMessage 桥):handler 改调 `services.plugin.*` +
  `getPlatform().pickFile/pickDirectory` + `platform/pluginAssetUrl/pluginAssetIsolated`;
  **postMessage 协议面(8 个 host 方法,3.0.0)与 `@infinia/plugin-sdk` 一行不动**。
- `notificationStream/agentRunStream/sse.ts` 的功能已在 impl/streams.ts,勿再移植。
- `api/desktop.ts`(openExternalUrl 等)功能已在 platform/web.ts + desktop.ts,勿再移植。

**切换(cutover,单个原子提交):**
1. 最后一个 `@/api/*` 引用消失后,删除 `src/api/` 整目录与 `parity.test.ts`(它随 legacy 退役);
2. `services/types.ts` 转正为唯一类型源(切换前它只是 api/types.ts 的受控拷贝,两份并存期间以后端为准手工同步);
3. main.tsx 接 `configureServices({ locale: () => i18n.language })`;
4. CI 加 grep 约束:`src/(platform|services)/` 无 `from 'react'|from 'react-dom'|axios|i18n` import;
   `window.fengyu` 仅出现在 `platform/desktop.ts` 与 `electron-env.d.ts`。

## 5. 不变式(红线)

1. REST/SSE 线上协议零变化:端点、ticket 流、SSE 事件名原样(实现是 1:1 移植,已由 parity 对照锁定)。
2. `window.fengyu` preload 契约零变化(`electron-env.d.ts`、desktop/electron 不改)。
3. 插件两条协议零变化:postMessage 3.0.0 与 worker NDJSON JSON-RPC v1;iframe CSP 数据路径不变。
4. 后端 Java 本阶段零改动。
5. Vue 工程(`frontend/`)冻结:不再为其做任何 api 层改造,4.1 线上修 bug 照旧,
   新功能只落在 React 工程的服务层。

## 6. 后续(独立提案,不阻塞)

- 类型生成:springdoc-openapi → openapi-typescript 替代手写 `services/types.ts`
  (生成链只服务 app 前端内部,`toolchain/sdk-ts` 对外类型不绑入)。
- 编码代理事件通道 WS 化:域接口不动,只增补 impl 传输。
- services/platform 若出现第二个消费工程(如 mobile),再升格为 workspace 包。
