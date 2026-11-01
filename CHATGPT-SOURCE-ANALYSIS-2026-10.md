# ChatGPT/Codex 桌面端反编译分析（2026-10）

> **材料**：用户本机 `/Applications/ChatGPT.app`（`openai-codex-electron` 26.908.70816，Electron 42.3，Codex build 9275）
> 反编译产物（webcrack 2.16：结构/控制流还原，变量名压缩）。基础层用 REA 静态图报告（主进程侧完整），
> 本次补两个深挖：主进程安全与审批语义 + webview 功能面（REA 未解析的 ~7000 chunk）。
> **合规**：内部竞研文档——只做架构级结论与小片段引证，不大规模复制专有代码；不入 `docs/` 站点。
> 关联：`COMPETITIVE-RESEARCH-2026-10.md`（赛道 E）· `ROADMAP-2026-10.md`（F 编号引用）。

---

## §1 架构总览（在 REA 基础上修正/加深）

- **三进程分工**：Electron 主进程（`main-DaMR-wdT.js` 5.5MB）+ `worker_threads` 工作线程（`worker.js`：承载 better-sqlite3、OTel/Sentry、git 安全子命令白名单、PR 工具，经 MessagePort 转移通信，可自动重生）+ Rust `codex app-server`（stdio JSON-RPC，真正的 agent 运行时）。**UI 只是 JSON-RPC 客户端**——审批是 server request，客户端渲染并回传 decision。
- **运行时分发**：`codex` 二进制可 PATH 解析，也可从远端推送的 `codex_runtimes_config` 目录安装/更新"primary runtime"（带安装进度 UI）——**运行时本身是推送更新的**。
- **跨窗口协同**：主进程内嵌 yjs（CRDT）做跨窗口/协作状态；worker 线程维护 `thread_turn_summaries`（按 principal/host/thread 键）。
- **webview 层**：Vite/rolldown 产物，chunk 按特性命名（可读性极好）；`app-initial` 14.3MB 入口 + `app-primary` 8MB（composer/审批/浏览器侧栏）；service-worker 仅做 web-push（目标域硬白名单）。`runtime.worker-*` 是幻灯片/文档渲染运行时（"Popcorn"=其 office/artifact 套件代号）。
- **修正 REA**：capnweb 在主进程 bundle 中 0 次出现（疑在渲染层）；preload 里有 WebCodecs `VideoDecoder` 管道用于远程会话的硬件解码 PiP 视频流。

## §2 安全姿态（值得抄的与暴露的）

**抄**：
1. **webview attach 加固三件套**：`will-attach-webview` 时删除攻击者可控属性（`disablewebsecurity/webpreferences/preload/allowpopups`）→ 换专属 partition → attach 后主框架导航锁定为"与 attach 时 URL 精确相等"（协议+主机+路径+查询），弹窗全拒、下载取消、右键/对话框禁用。
2. **MCP widget 沙箱**：per-sandboxId partition；`codex-sandbox:` 协议只回放从 `web-sandbox.oaiusercontent.com` 拿到的 **HTTP 缓存命中**（force-cache、`redirect:error`、`credentials:omit`，透传上游 CSP + 补 `nosniff`）——widget 代码不落盘、不直连网络；会话级网络白名单（about/blob/data/https/wss/devtools + codex-sandbox）；guest IPC 握手校验 initId+origin+端口数后才转发 MessagePort。
3. **权限极小化**：app 会话仅放行 `clipboard-sanitized-write`；深链只接受 `chatgpt.com/codex/deeplink` 且恰好一个 `url` 参数；全局 UA 去除 Electron 特征。
4. **审计级日志**：每个安全决策（request_blocked/popup_blocked 等）走结构化日志。
5. **远程控制设备密钥**：OS 钥匙串不可导出密钥（Secure Enclave 级）+ 域分隔签名信封（audience/scopes/nonce/tokenSha256 全精确匹配）+ step-up token（新输密码）注册 + WS 每连接挑战重认证。

**暴露（FengYu 叙事弹药，内部）**：`webviewTag:true` 仅靠 JS 缓解；生产强制开启 sandbox guest 的 devTools；**full-access 模式下 browser-use 的 origin elicitation 被自动放行**（钓鱼暴露面）；skybridge 沙箱 URL 带 `unsafeSkipTargetOriginCheck=true`；无证书固定。

## §3 审批与权限模型（对 FengYu 最有价值的一节）

OpenAI 把 agent 权限做成了**三维正交 + 五档 UX**：

- **三维**：`sandboxMode`（read-only / workspace-write / danger-full-access）× `approvalPolicy`（never / on-failure / on-request / untrusted）× `approvalsReviewer`（user / guardian_subagent）。五档 UX 预设：`read-only`、`auto`、`granular`（细粒度压制某几类提示：sandbox_approval/rules/skill_approval 关，request_permissions/mcp_elicitations 开）、`guardian-approvals`、`full-access`；服务端可下发 `allowed*` 约束集（企业策略面）。持久化在 `~/.codex/config.toml`（与 codex-rs 同构）。
- **审批卡片三联**（webview i18n 证据）：`Allow once / Allow for this conversation / Always allow` + **deny with reason**；范围随类型变体——命令="allow **similar** commands"（前缀匹配）、文件编辑="本会话全部编辑"、网站="不再询问任何网站"（显式升级提问）；diff 请求显示文件数/统计；**子代理署名替换主语**（"Do you want {actor} to run…"）。
- **持久化语义**：session 级 `acceptForSession` 与持久 `execpolicy_amendment`（app-server 侧落盘）；Windows 计算机使用的"始终允许的应用"写入 `computer-use/config.toml` 的 always_allowed_app_ids。
- **Guardian（自动审查）**：一个被提示词约束的 reviewer 子代理代批，记录 `riskLevel/userAuthorization/rationale/decisionSource`；动作类型含 execve/apply_patch/network_access(host,protocol,port,含 socks5)/mcp_tool_call；**连续拒绝过多触发熔断提示**（autoReviewInterruptionWarning）；用户侧有非打扰式转化引导（"Want fewer approval prompts?" → "Approve for me"，并注明可能消耗更多 credit）。
- **elicitation 超时**：MCP elicitation 在前台 90 秒无操作或 server 自设 5–300s 后**自动拒绝**（宁拒不等待）。
- **leader/follower 窗口**：审批只能由发起会话的窗口产生，follower 窗口只能转发决策。
- **pending-input 统一面**：`userInput / optionPicker / approval / permissionRequest / implementPlan / mcpServerElicitation` 共用一个带稳定 id 的中断面板——**计划确认（"Implement this plan?"）与审批平级**。

## §4 功能面全景（webview 证据）

| 域 | 现状（chunk 证据） |
|---|---|
| Agent 审批/状态 | 审批三联 + 自动审查 + 子代理行（waiting/working/done/failed）+ 时长文案（"Working for→Worked for/You stopped after {time}"）+ 拒绝动作计数 |
| 排队消息 | 暂停中新发消息强制二选一：**Clear queue vs Send**（保护排队工作）；有排队消息时禁用线程移交 |
| 浏览器 | 内置浏览器（Atlas 血统：profile 导入/设默认浏览器）、每标签页静音、`focusTab1-9` 键位、云浏览器侧板（click-to-activate）；**页面区域批注 overlay（75 个 i18n key）+ 实时 CSS 属性设计编辑器**——agent 在活页面上钉"tweaks"、用户在页内回复 |
| 任务/自动化 | automations 页面 + 频率/状态/webhook 细节 + `pull-request-fix-automation`（agent 自动修 PR）；后端 `wham` 服务（/wham/tasks/list、workspace-messages） |
| MCP | 设置页/工具项/扩展视图/快动作对话框；elicitation 表单（逐字段校验）；工具调用审批可展开参数 + `elevatedRiskLabel`；**WebMCP 站点工具发现**需用户显式授权；`localConversation.mcpToolActivity.*` 是最大 i18n 命名空间（900 key） |
| IDE/终端 | `vscode://codex/{method}` fetch 拦截传输（渲染层在 VS Code 内复用）；xterm 面板/后台终端标签 |
| 硬件 | **Codex Micro**：实体外设（摇杆 HUD、可编程按键、迷你游戏、宠物动画、手机配对）——"手持 companion"方向 |
| 语音 | realtime 语音 + 全局听写（hold-to-dictate，WebAudio worklet）+ 语音工具（end/transfer call） |
| 记忆/项目/工件 | 记忆开关+历史+授权文案（"允许在今后记忆中使用此对话"）；Projects；Popcorn 工件套件（文档/表格/幻灯） |
| 用量/上下文 | **thread-usage-breakdown**：credits·成本按模型%/推理档（none→Ultra）/速度档（Fast/Ultrafast/Standard）/token 种类（cached/uncached input、output）拆分；侧栏常驻剩余百分比；"Compact this chat" 手动压缩入口（运行中禁用） |
| 远程 | SSH/WSL/云环境页 + worktree（上下文引导横幅+自动修复）+ "Keep this Mac awake" + 远端 app-server 安装/重启面 |
| **agent 驱动 App 自身** | **61 个工具**：侧栏/分区/线程 CRUD+置顶、fork/read/send-to-thread、`handoff_thread`、open_browser/open_terminal、`capture_screen_context`、`draw_screen_stroke`（屏幕画笔）、read/write_settings、automation CRUD、`fire_confetti`、`uninstall_plugin`…——agent 是自家 App 的超级用户 |
| 其他 | "Daybreak"（授权网络安全项目门槛/Cyber 模型 Full-Access 警告）；`signInWithChatGPT` OAuth（第三方 App 用 ChatGPT 额度）；statsig 实验名暴露其路线（multi_agent_mode、thread_timeline_history、automatic_checkpoint_titles、local_thread_store_compression、curated_remote_marketplace、codex_replay…） |

## §5 对 FengYu 的落点（映射 ROADMAP）

| ChatGPT 机制 | FengYu 现状 | 动作 |
|---|---|---|
| 审批三联 + 前缀相似命令 + 会话域编辑放行 | 确认制为单次审批 | **新增 F2-7：审批分档与权限档位**（见下） |
| 三维五档权限档位 + 企业约束集 | 有 run 授权/审批门，无档位模型 | 并入 F2-7（read-only/workspace/full 三档起步） |
| guardian 自动审查 + 熔断 + 转化引导 | 无 | F2-7 远期子项（reviewer 子代理），M3 常驻 agent 基座复用 |
| elicitation 自动拒绝计时 | 无 | F2-7 子项（前台无操作超时自动拒） |
| pending-input 统一面（计划确认与审批平级） | 各审批分散 | F2-7 UX 参照 |
| 用量拆解 + 侧栏水位 + 手动 Compact | F2-5 已规划 | **验证并加细**：token 分 cached/uncached/output；"Compact this chat" 入口文案直接对标 |
| 浏览器接管后的区域批注 + CSS 实时编辑 | F1-4 仅 handoff | F1-4 验收注：后续可扩"页内批注"二期 |
| MCP widget 沙箱（cache-only 协议 + origin/op 白名单 + initId 握手） | `McpSandboxRegistry`（127.0.0.2 iframe + op 白名单）**同构** | 已对齐 ✓——对标叙事资产；可补 initId 握手与缓存化加载两细节 |
| 排队消息 Clear vs Send | 任务坞有 todo，无消息队列语义 | 观察项（若做"运行中排队追问"采纳此交互） |
| OS 钥匙串设备密钥 + step-up 注册 | 无远程面 | M3 F3-1（run API）/远程功能的安全设计蓝本 |
| 运行时推送更新（codex_runtimes_config） | 工具链独立版本线 | 观察项：官方 worker 运行时的远端目录化更新（可选） |
| 特性开关（statsig）驱动渐进 rollout | 无本地 feature-flag 层 | 观察项：轻量本地开关层可助力渐进发布（不引第三方遥测） |
| 61 个"agent 驱动 App"工具 | 有 flow-authoring 工具族 | 方向参照：让 agent 操作 FengYu 自身（线程/设置/自动化 CRUD）——与编排定位天然契合，列 M3 观察方向 |

**新增 F2-7【优化】审批分档与权限档位（M2，规模 M）**——本分析直接产出的 roadmap 增项：
现状锚点：`ToolLoopDriver` 取消/审批门 + flow run 授权回收（`81dfb1cf` 批次）。子任务：审批卡片三联（once/会话/始终 + deny-with-reason）；相似命令前缀放行与"本会话全部编辑"域；权限档位三档（read-only/workspace-write/full）映射到 agent 与 flow 运行的工具面；elicitation/审批前台超时自动拒；统一 pending-input 面板（计划确认入列）。验收：三档下端到端权限矩阵用例；超时自动拒用例；档位切换即时生效且 UI 明示风险文案。远期：guardian reviewer 子代理。

## §6 结论

1. OpenAI 桌面端的护城河在**权限工程的完成度**（三维模型/自动审查/超时语义/署名替换）与 **agent 驱动自家 App 的 61 工具面**——这两块是 FengYu 编排路线的下一步最佳参照，已落为 F2-7 与 M3 观察项。
2. 其 MCP widget 沙箱与 FengYu `McpSandboxRegistry` 设计同构（iframe+origin/op 白名单），FengYu 并未落后；其暴露面（webviewTag/devTools/自动放行）继续充实"最严隔离"叙事。
3. webview 证据确认：用量透明（credits/token 拆解+水位）与手动 Compact 已是头部标配——F2-5/F2-3 方向再次验证；"Popcorn"工件套件、Codex Micro 硬件、Daybreak 网安项目是其差异化侧翼，FengYu 不跟。
