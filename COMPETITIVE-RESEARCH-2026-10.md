# FengYu 竞品调研与优化策略（2026-10）

> **数据权威性**：开源项目的星数/发布数据于 **2026-10-15** 通过 GitHub REST API 实时拉取（精确值，非估算）；
> 闭源产品（ChatGPT 桌面版）来自 OpenAI 官方发布说明/帮助中心与主流媒体报道。特性/情绪结论来自各项目
> release notes、官方博客、GitHub issues、Reddit/HN（来源见 §8）。
> 本文档是内部策略文档，不入 `docs/` VitePress 站点。刷新协议见 §7。
> **合规注**：按 AGENTS.md 规则，ZCode 字样仅允许出现在本内部文档与 README 既有致谢中，不得进入
> shipped docs / UI 文案 / commit message。

---

## TL;DR

1. **FengYu 的最大差距不是功能，是分发。** 四赛道 15 个开源对标项目多数在 4 万–21 万星区间（新开源的 ZCode 25 天 7.8k 星是唯一新生例外），FengYu 是 **2 星**（2026-02-25 建仓）。架构上 FengYu 在"安全隔离 × 可扩展性 × AI 原生编排"三个维度领先大多数对手，但没有人知道它存在。
2. **行业已完成收敛**：一切产品 Agent 化、MCP 表格化、RAG 表格化、纯手拖低代码画布已死（Flowise 2026-08 归档并留下讣告式声明）、上下文工程与检查点成为编码 Agent 护城河。FengYu 的既有路线（agent 主轴、flows-as-tools、AI-proposal+diff）与收敛方向一致，不需要转向，需要**补齐表格化能力 + 加码独占优势**。
3. **P0 三件事**：① 以 4.1.0 为 launch 版本做正式上市（含代码签名——未签名 + auto-updater 是桌面信任杀手）；② 知识库最小面（官方插件形态）；③ MCP 深化（一键安装 + Flow 导出为 MCP server）。
4. **新增两块基准改变叙事弹药而非方向**：ChatGPT 桌面版（2026-07 把 Chat+Work+Codex 统一进一个桌面壳、Atlas 独立浏览器关停折入内置浏览器、Tasks/Dots 常驻 agent）**验证了 FengYu 押注的形态**；它的无插件生态、云端闭源、$100–500/月 Pro 层与隐私争议，正是"本地优先 + 可扩展 + BYO 模型"的 FengYu 的楔子。上游 ZCode 与 FengYu **架构同源**（headless loopback server + token + 多前端），可作 agent 层 fast-follow 参照；其 2026-09 未经同意上传本地代码库事件给"确认制"叙事再添一枚行业级弹药。

---

## §1 竞品全景（四赛道 + 闭源基准，2026-10-15 实测）

### 赛道 A：桌面 AI 助手（FengYu 最直接的对标）

| 项目 | Stars | 最新版（日期） | 节奏 | 许可 | 变现 | 关键事实 |
|---|---|---|---|---|---|---|
| Cherry Studio | 52,657 | v2.1.6（10-11） | 周更 | AGPL-3.0 | 企业版 | Electron+React 19；300+ 模型、内置 Agent 运行时、Code Mate、WebDAV 备份；≤1.9.7 有 zero-click CVE（2026-07 政府通报）；**尚无第三方插件 SDK**（roadmap） |
| Jan | 44,956 | v0.8.6（10-09） | 放缓（3 个月空窗） | Apache-2.0 | 无（研究资助） | **Tauri+Rust** 重写；捆绑 llama.cpp（CUDA/Vulkan/MLX）；开放扩展体系（SEP 规范）；痛点=本地引擎兼容性 |
| Chatbox | 42,027 | v1.23.5（09-24） | 月更 | GPL-3.0 | 订阅 $3.99–19.99/月 | Work Mode（agentic）+ Skills 库；MCP deep-link 一键配置；平台覆盖最广（含 Android/iOS/Web） |

### 赛道 B：自托管 AI 平台

| 项目 | Stars | 最新版（日期） | 许可 | 变现 | 关键事实 |
|---|---|---|---|---|---|
| Open WebUI | 155,770 | v0.12.2（10-14） | 自定义（50 用户以上品牌条款，引争议） | 企业版/托管 | 赛道王者；Functions/Tools/Skills 插件（**Python 进程内执行**）；姊妹项目 Computer（VM 沙箱）、Open Terminal；痛点=回归多、数据丢失报告 |
| LobeHub (lobe-chat) | 83,241 | v2.2.19（10-07）+ nightly | 自定义（"基于 Apache 2.0"表述引争议） | 云 ~$10–40/月 | v2 转型 "Chief Agent Operator"（多 Agent 协作）；**505 个 Agent 市场 + MCP 市场一键装**；设计最佳 |
| AnythingLLM | 67,135 | v1.18.0（10-13） | MIT | 云 ~$50/月 | 桌面优先（零 API key 全本地）；工作区 RAG、无代码 agent flows、cron 定时、智能路由（宣称省 80% token）；YC 背书 |
| LibreChat | 45,726 | v0.8.8（10-01） | MIT | 被 **ClickHouse 收购**（2025-11） | 最广供应商矩阵；Agents+子代理+MCP+**SKILL.md 渐进披露**（与 FengYu 同构！）；痛点=自托管 footprint 最重 |

### 赛道 C：可视化工作流 / 编排平台（对标 iFlow）

| 项目 | Stars | 最新版（日期） | 许可 | 变更/变现 | 关键事实 |
|---|---|---|---|---|---|
| n8n | 207,452 | 2.43.6（10-15） | Sustainable Use（fair-code） | $180M C 轮 @$2.5B（2025-10）；SAP 入股后 $5.2B | **周多次发布**；2.0 引入 Publish-vs-Save（草稿/生产分离）；400+ 集成、Evals、n8n Agents（定义一次随处调用） |
| Dify | 158,545 | v1.17.1（09-10） | Apache-2.0+条件（多租户限制引争议） | $30M Pre-A @$180M（2026-03） | v1.6 双向 MCP（消费 MCP + **工作流导出为 MCP server**）；New Agent（2026-08）：AI 引导 Build mode + 可审查 Build Draft；Marketplace 宣称 10 万插件 |
| Langflow | 155,699 | v1.12.6（10-13） | MIT | IBM（收购 DataStax 而来）；托管版 2026-04 关停 | **桌面版**（Mac/Win）；flow 可导出为 MCP server；OTel 遥测（不外泄 prompt）；1 年星数翻倍 |
| ~~Flowise~~ | 55,506 | 3.1.4（07-29） | Apache-2.0 | **已归档（2026-08-13）** | 维护者原话：模型推理变强后开发者改用编码 Agent，"刚性 workflow 低代码方式迅速撞墙"；另有 **CVE-2025-59528 未鉴权 RCE 被在野利用**（2026-04 CSA 通报）——双重死因 |

### 赛道 D：编码 Agent（FengYu 内嵌 agent 能力的对标）

| 项目 | Stars | 最新版（日期） | 许可 | 变现 | 关键事实 |
|---|---|---|---|---|---|
| OpenCode | 213,720 | v1.19.0（10-11） | MIT | Zen 网关 + Go 订阅 $10/月 | **17.5 个月 21 万星（全场最快）**；client-server（TUI 只是 HTTP server 的一个客户端）；2.0 公测（可嵌入 SDK）；子代理树；细粒度权限 glob |
| OpenHands | 90,955 | v1.27.1（10-12） | MIT | 云免费层+企业版 | 平台化（GUI+SDK+automation+云+桌面）；**事件溯源 replay**；Agent Canvas 编排第三方 agent |
| Cline | 70,354 | v0.0.46 桌面（10-13） | Apache-2.0 | credits/ClinePass $9.99/月，$32M 融资 | **检查点（影子 git undo/redo）**；多 Agent 团队；cron 调度；SDK 化改造中；痛点=token 膨胀（#1 投诉） |
| Aider | 49,562 | v0.86.0（2025-08-09） | Apache-2.0 | 无 | **实质停滞**（最后 push 2026-05-22，作者消失）；无 MCP；repo-map 与 benchmark 影响深远 |
| ZCode（zai-org） | 7,843 | v3.15.1（10-10；仓库滞后官网 3.15.3） | Apache-2.0 | GLM Coding Plan $12.6–117.6/月 | 2026-09-20 开源、25 天 7.8k 星；**与 FengYu 架构同源**（headless server 127.0.0.1+token，Electron/Web/TUI 三前端）；Goals 计划-执行-验证、skills+斜杠命令、MCP、子代理、动态工作流、browser/computer-use、memory、hooks、Bash-AST 权限；Claude-Code 兼容插件布局+市场；issue 区关闭、仓库滞后发布；**2026-09-21 被曝未经同意上传本地代码库（Reuters），相关功能被停** |

> **ZCode 特殊关系**：FengYu agent 层的上游设计来源（README 存留的唯一致谢，Apache-2.0）。作为基准的用法：
> 机制层 fast-follow（Goals、动态工作流、hooks、memory、权限语义），默认行为不跟（其信任事件是反例）。

### 赛道 E：闭源基准 —— ChatGPT 桌面版（无星数可比，按规模与特性对标）

| 维度 | 现状（2026-10） |
|---|---|
| 平台与时间线 | macOS 2024-05 → Windows 2024-11 全量免费 → Linux 预览 2026-08；**2026-07-09 大改版**：Chat + Work + Codex 统一进一个桌面壳（旧版改名 Classic） |
| Agent 能力 | Work（前 agent mode）：云端沙箱计算机 + 可视浏览器/终端/Python/文件；桌面 App **内置多标签浏览器**，agent 操作、用户可接管；**Atlas 独立浏览器 2026-07 弃用、08 关停**，功能折入桌面内置浏览器 |
| 自动化 | Tasks 2026 刷新：定时 + 事件触发自动化，可调 connectors/MCP；**Dots**（2026-09 DevDay）：常驻持久 agent（Pro/Business Premium 专属） |
| 连接器 | 2025-03 起 connectors/MCP；2026-02 deep research 可连任意 MCP server |
| 其他 | GPT-Live 语音、Work with Apps（VS Code/Xcode/Cursor/Terminal 读写）、记忆、Projects、companion window、Alt+Space 热键 |
| 规模/定价 | 900M WAU（2026-02，TechCrunch）；Free/Go $8/Plus $20/Business $25/**Pro $100–500**；agent 额度 Plus ~40 条/月 vs Pro ~400 条/月 |
| 痛点 | 2026-07 统一改版引发大规模不满（TechRadar）；macOS 被指绕过隐私设置索引本地文件（未经证实的投诉，传播广）；无 E2E 加密；**无第三方插件面**；Windows 冻结、Linux 功能缺口 |

**对 FengYu 的三行启示**：① 最大玩家用行动投票——把 agent+浏览器+编码+定时任务**整合进一个桌面壳**（连独立浏览器都砍掉），FengYu 的 Electron 内置 Browser Agent + Computer Use + Flow 路线押对了形态；② 它没有插件/SDK 生态且推理闭源云端——"进程外沙箱插件 + 本地优先 + BYO 模型"是结构性空位；③ 真正的 agent 用量被定价锁在 $100–500/月——平价 BYO 模型叙事有真实价格锚点可打。

**反编译核验（2026-10-15 增补，详见 `CHATGPT-SOURCE-ANALYSIS-2026-10.md`）**：本机反编译产物（webcrack，结构还原）深挖证实并深化了上表——
- **权限工程是它的真护城河**：三维五档权限模型（sandbox×policy×reviewer）+ 审批三联（once/会话/始终+deny-reason，前缀相似命令/会话域编辑）+ guardian 自动审查子代理 + elicitation 前台超时自动拒 + leader/follower 审批发起约束。→ 已转化为 FengYu ROADMAP 新增项 **F2-7（审批分档与权限档位）**。
- **MCP widget 沙箱与 FengYu `McpSandboxRegistry` 同构**（iframe + origin/op 白名单 + 协议隔离），FengYu 未落后；其暴露面（webviewTag:true 仅 JS 缓解、生产开 devTools、full-access 下浏览器 origin 自动放行、skybridge 跳过 origin 校验、无证书固定）继续充实"最严隔离"叙事弹药。
- **功能面证据**：61 个"agent 驱动 App 自身"工具（线程/设置/自动化 CRUD、handoff、屏幕画笔）、浏览器页面区域批注 + 实时 CSS 编辑器、automations + PR 自动修复、用量透明面板（credits/token 按 cached/uncached/output 拆分 + 侧栏水位 + 手动 Compact）、排队消息 Clear-vs-Send、OS 钥匙串设备密钥远程控制注册。statsig 实验名暴露其后续路线（multi-agent、thread timeline、checkpoint 标题、本地线程库压缩、curated marketplace、replay）。
- **差异化侧翼 FengYu 不跟**：Popcorn 工件套件、Codex Micro 硬件 companion、Daybreak 网安项目、signInWithChatGPT OAuth。

### FengYu 自身基线（同日实测）

`MuskStark/FengYu`：**2 stars / 1 fork**，2026-02-25 建仓，GPL-3.0。发布线：beta.2→GA（09-30）→4.0.1（10-10）→4.0.2（10-12）→4.0.3（10-14）——GA 后两周三个补丁，**发布节奏本身已是头部水平**；4.1.0 在 `release/4.1.0` 分支待发。

---

## §2 行业趋势（2025→2026，带证据）

1. **"聊天应用"框架已死，一切 Agent 化。** Cherry 2.0 内置 Agent 运行时、Chatbox Work Mode、LobeHub v2 更名 "Chief Agent Operator"、Open WebUI 加 Open Terminal + Computer、AnythingLLM agent flows；ChatGPT 桌面版 2026-07 更进一步——Chat+Work+Codex 统一进一个桌面壳、独立浏览器 Atlas 关停折入内置浏览器。FengYu 的 plan-and-execute 主轴 + Electron 内置 Browser Agent 是这个方向的先发者之一，且形态与终局一致。
2. **MCP 全面表格化，分化在"深度"。** 14 个项目全部支持消费 MCP；差异化移到一键市场（LobeHub）、deep-link 配置（Chatbox）、MCPO（Open WebUI）、**工作流导出为 MCP server**（Dify v1.6、Langflow）。后者是平台把自身能力暴露给世界的通用通道。
3. **RAG/知识基地表格化。** 赛道 A/B 全部 7 家都有知识库+检索。它不再是卖点，而是**评测清单必查项**——没有会在选型第一轮被划掉。
4. **纯低代码画布死了，混合形态活着。** Flowise 归档声明是行业判词；幸存者的答案一致：n8n 画布内嵌 Code 节点、Dify New Agent 的 AI 引导 Build（带可审查 Draft）、Langflow 走 IBM 企业线。**FengYu 的 AI-proposal + diff + 确认机制正是幸存者路线，且做得更早。**
5. **上下文工程与长任务 UX 成为编码 Agent 护城河。** 自动压缩/后台 agent（OpenCode、Cline）、事件溯源 replay（OpenHands）、检查点回滚（Cline 影子 git）；token 膨胀是全场 #1 投诉与流失原因。FengYu 已有自动压缩 + tool-result 预算，缺检查点/回放。
6. **Agent 成为可复用资源而非画布节点。** n8n Agents、Dify New Agent、Langflow task-handoff 收敛于"定义一次、随处调用、可追踪使用处"。FengYu 的 flow 发布为动态发现 AI 工具已是同构实现，缺"使用处地图"这类运维面。
7. **信任（license + 安全）是生死线。** Open WebUI、LobeHub、Dify 三家 license 争议都引发社区反弹；Flowise 死于 RCE；Cherry 有 zero-click CVE；**ZCode 2026-09 被曝未经同意上传本地代码库（564 次尝试外传 313MB，Reuters）后停用相关功能**；ChatGPT 桌面版有绕过 macOS 隐私设置的指控（未经证实但传播广）。**FengYu 的进程外沙箱 worker + 签名商店 + 确认制敏感工具 + loopback-only 是全场最严隔离架构——信任事件越多，这越是可讲成核心卖点的差异化。**
8. **变现收敛于 open-core/推理网关，多面板（SDK/CLI/CI）是获取 funnel。** OpenCode Zen、Cline credits、OpenHands Cloud、ZCode GLM Coding Plan（$12.6 起）；MIT/Apache 核心做获客。价格带两极化：闭源旗舰把真 agent 用量锁在 $100–500/月（ChatGPT Pro），开源侧 GLM Plan 以 ~$12.6/月打平价——**BYO 模型 + 本地优先的平价叙事有真实价格锚点**。
9. **常驻 Agent 与持久记忆进入主线。** ChatGPT Dots（常驻 agent，Pro 专属）+ Tasks 事件触发、ChatGPT/ZCode/Chatbox 的记忆系统相继落地；"用过即走"的会话模型在头部产品里已被淘汰。FengYu 有长会话压缩，缺跨会话记忆与调度面（见 §4 P1）。

---

## §3 FengYu 对标：优势与差距

### 独占优势（应加码，竞品短期学不会）

| 优势 | 证据/对比 |
|---|---|
| **最严插件隔离**：进程外 JSON-RPC worker + 沙箱 iframe + Ed25519 签名商店 + 确认制敏感工具 | Open WebUI 的 Functions 是 Python 进程内执行；Flowise 未鉴权 API 直接 RCE 致死；Cherry 连插件 SDK 都没有 |
| **三语言 worker SDK（Java/Python/Go）+ 完整工具链**（CLI/dev/devkit/规范 schema） | 同类最强扩展面；Dify 靠 marketplace 规模，FengYu 靠架构质量 |
| **AI 原生编排**：agent 主轴 + flows-as-tools + .fys 渐进披露 | 与行业收敛方向一致；LibreChat 2026 年才上 SKILL.md 同构机制 |
| **Browser Agent 复用 Electron 原生引擎（免 Playwright 下载）+ Computer Use 内置** | Open WebUI 用独立 VM 项目（Computer）追赶的方向 |
| **隐私默认**：loopback-only + 每启动 token + 数据库密码 AES-GCM | 与 Jan "offline-first" 同类叙事，且有更完整的权限/审批模型 |

### 差距矩阵（✗ 缺失 / ~ 部分 / ✓ 有）

| 能力 | 竞品覆盖 | FengYu 现状 | 判断 |
|---|---|---|---|
| 知识库/RAG | 赛道 A/B 全部 7 家 | ✗ 无 | **P0**：选型必查项 |
| MCP 一键安装/deep link | LobeHub/Chatbox/OWUI | ~ **盘点（10-15）**：运行时很深（官方 SDK 三传输 + MCP Apps 沙箱 + 商店模板安装），但添加靠手动表单 | P0（F1-1） |
| Flow 导出为 MCP server | Dify、Langflow | ✗（对内有 flow-as-AI-tool，缺对外导出层） | P0/P1 |
| 桌面代码签名 | 全部主流桌面竞品 | ✗ README 明言 deferred；**未签名 + auto-updater = Gatekeeper/SmartScreen 转化杀手** | **P0** |
| 定时任务/自动化 | Cherry、OWUI、AnythingLLM、Cline（cron） | ✓ **盘点修正（10-15）**：`BackgroundTaskScheduler`（间隔+日历+DST+at-most-once）+ SchedulesPage + webhook 触发器已有；缺 cron 表达式与补跑策略 | M2 优化（F2-6） |
| 检查点/回滚/回放 | Cline checkpoints、OpenHands replay | ✓ **盘点修正（10-15）**：run resume/fork/rewind + rollout 回放 + 重启恢复已有 | 缺工作区快照回滚（F2-2） |
| Flow 版本化/evals/tracing/成本 | n8n 2.0 Publish-Save、Dify tracing/usage、Langflow OTel | ✓ **盘点修正（10-15）**：publish + revisions + restore 已有；rollout JSONL + Micrometer/OTLP 已有 | 缺成本台账与 tracing/diff UI（F2-3/F2-4） |
| 本地模型开箱 | Jan（捆绑 llama.cpp）、Cherry（MLX 实验） | ~ 有 Ollama 后端，无首启探测/引导 | M1（F1-5：引导优先，不捆绑） |
| Agent run API/CLI/SDK | OpenCode/Cline/OpenHands 收敛点 | ~ headless server 天生存在，但 loopback-only + 无公开 run API | P2 |
| 多用户/RBAC | OWUI 最深；Cherry 企业版 | ~ 远程 DB 选项已在（MySQL/PG 向导） | P2 |
| 持久记忆（跨会话） | ChatGPT（memories/Dots）、ZCode（memory 模块）、Chatbox | ✓ **盘点修正（10-15）**：`AiMemoryService` + 4 个记忆工具 + 设置面已有（实验、默认关、keyword+recency 检索） | M2 转正（F2-1） |
| 常驻/事件触发 agent | ChatGPT Dots（Pro）、Tasks 事件触发 | ~ webhook 触发器已有（HMAC + delivery log）；无常驻 agent | P2：事件触发随 webhook 骨架扩展（F3-3），常驻 F3-4 |
| IM/远程控制通道 | ZCode（微信/飞书/Telegram bot） | ✗ | P2 观察（与 loopback 安全模型有张力） |
| 桌面内置浏览器 + 人工接管 | ChatGPT 桌面（Atlas 折入） | ✓ Browser Agent（Electron 原生引擎，免 Playwright） | 已对齐，补"人工接管"交互即可 |
| 语音通话/图像生成 | 多家 | ✗ | **不做**（commodity，走 MCP/插件） |
| 分发/社区 | 4 万–21 万星 | ✗✗ **2 星** | **P0 之首** |

### 顺带发现的文档保健问题（launch 前必修）

- README badge 仍是 `4.0.0--beta.2`；技术栈表写 Java 21 但需求是 JDK 25；未提 4.1.0 的 MCP/商店/新特性。走一遍 docs-updater。
- README 说 "unsigned … deferred to a later release" —— 签名问题被写在了门面上。

---

## §4 优化策略

### P0 —— 4.1.0 launch 窗口（评测通过 + 信任门槛 + 被看见）

1. **正式上市（最大的 ROI 项）。** 4.1.0 作为 launch 版本：Show HN、r/LocalLLaMA、r/selfhosted、V2EX、少数派、掘金；已有宣传视频产能（4.0 实机剪辑 + 4.1 动效版）直接复用；GitHub topics/social preview/README 首屏重写（安全隔离叙事放第一条）；awesome-lists PR；中英双语文档已是存量优势。**叙事弹药（2026 年行业自己送来的）**：Flowise 死于 RCE、Cherry zero-click CVE、ZCode 未经同意上传本地代码库、ChatGPT 隐私指控与 $100–500 Pro 定价——"唯一进程外沙箱 + 确认制 + 本地优先 + BYO 模型"每一条都有当日新闻可引。**证据**：OpenCode 17.5 个月 21 万星证明这个品类仍在爆发期，先发质量 + 迭代速度 + 可发现性=增长；FengYu 节奏已达标，唯独缺可发现性。
2. **代码签名 + 公证（macOS notarization / Windows）。** 未签名的 auto-updater 在两大 OS 都是红色警告；Cherry 的 zero-click CVE 说明桌面信任是用户真实决策维度。与 launch 同窗口完成，否则 launch 流量会漏在安装环节。
3. **知识库最小面 —— 以官方 `.fyp` 插件形态交付**（复用 database 权限 + 注入数据源 + 确认制工具）：工作区文档导入 → 向量检索 → 作为 agent 可调用工具。核心保持精简，深度迭代看采用率。**不做第五个全功能 RAG 平台**——表格化能力只需过评测线。
4. **MCP 深化**：(a) 商店内 MCP 一键安装/deep-link（对标 LobeHub/Chatbox）；(b) **Flow 导出为 MCP server**（对标 Dify v1.6/Langflow）——FengYu 对内的 flow-as-AI-tool 机制已具备，导出层是增量工程，收益是让任何 MCP 宿主（含竞品）都能调用 FengYu flow，把编排器变成生态节点。

### P1 —— 4.2（长任务 UX + 运维成熟度）

> **2026-10-15 代码盘点修正**：下述 5（定时任务）与 6（记忆）的后端基础在 release/4.1.0 已存在
> （调度器+webhook、实验性 AiMemory），实际执行为"优化/转正"而非新建——逐项展开见 `ROADMAP-2026-10.md`。

5. **定时任务/自动化**：flow 运行与插件操作可 cron 调度 + 通知中心联动（通知管线已统一）。实现面窄、验证面最厚：Cherry/OWUI/AnythingLLM/Cline 四家 + **ChatGPT Tasks 2026 刷新（定时+事件触发）与 Dots 常驻 agent** 都已把调度做成主线能力。
6. **持久记忆（跨会话）**：轻量起步——agent 可读写的记忆存储（按会话/全局）+ 设置页管理面 + 一键清空。ChatGPT（memories/Dots）、ZCode（memory 模块）、Chatbox（per-copilot 记忆）三家验证；与长会话压缩互补而非互替。
7. **检查点与回放**：agent/flow run 的快照 + 一键回滚（Cline 影子 git 是可借鉴的最小实现——run 级快照目录 + manifest）；run 事件流回放（OpenHands 先例）。与已有的 durable history/restart 恢复同基座。
8. **Flow 运维成熟度**：draft/published 版本化（n8n 2.0 先例）、run tracing 视图、token/成本计量（按会话/run 累计，Dify usage 方向）。evals 放远期。
9. **本地模型开箱引导**：首启检测已装的 Ollama（端口/模型枚举）→ 一键接入向导。不捆绑 llama.cpp（JVM 侧维护成本高，Jan 的引擎兼容 issue 列表是前车之鉴）。
10. **持续发布节奏维持**：GA 后两周三补丁的节奏保持；每次 release 带 EN/ZH changelog（已有 sync 机制）。

### P2 —— 战略储备（按社区信号触发）

10. **Headless 服务器/团队模式**：远程 DB 已支持，补多用户 + RBAC（Cherry Enterprise 证明了私有化部署付费意愿）。
11. **Agent run API / CLI / SDK**：headless-by-design 是天然优势，暴露受限 run API + `fengyu run` CLI（CI/脚本场景），对标 OpenCode/Cline/OpenHands/ZCode 的 SDK 收敛点（ZCode 的 server+多前端与此同构，路线已被验证可行）。注意与 loopback-only 安全模型的边界设计（显式 `--listen` 逃生舱 + token）。
12. **推理网关类变现**（Zen/Cline credits/GLM Coding Plan 模式）：远期，社区规模达标前不动。
13. **常驻 agent / 事件触发 / IM 通道**（Dots 与 ZCode bot 控制的方向）：以定时任务 + 通知中心为基座渐进扩展；IM 入口与 loopback 安全模型有张力，只在社区明确要时开。

**ZCode 上游跟踪策略（内部）**：agent 层机制 fast-follow 上游开源实现（Apache-2.0，README 已致谢）——Goals 式计划-执行-验证、动态工作流、hooks、memory、Bash-AST 权限语义都是可合法借鉴的机制；但其默认行为与信任记录（未经同意上传事件）恰是 FengYu 确认制设计的反面教材——**跟机制、不跟默认**。上游仓库滞后官网发布、issue 区关闭，跟踪以 release tag + 官网 changelog 双源为准。

### 主动不做（及理由）

- **语音/视频通话、图像生成**：commodity 且重；MCP + 插件面已可覆盖。
- **追 marketplace 规模**（Dify 宣称 10 万插件）：签名审核的质 > 数；规模随分发来。
- **Tauri 重写**：Jan 的 RAM 收益不值得重写；Electron+JVM sidecar 已定，且桌面 AI 的 RAM 痛点主要来自本地推理，FengYu 主打编排不受此限。
- **扩张手拖低代码画布**：Flowise 讣告原文——刚性画布撞墙；继续加码 AI-built（AI 提案 + diff + 确认 + 可审查 Build Draft 方向）。
- **多租户 SaaS 化 / 改 license**：Open WebUI/LobeHub/Dify 三家 license 争议的教训；GPL-3.0 保持干净本身就是卖点。

---

## §5 落地节奏建议

> 详细版见 **`ROADMAP-2026-10.md`**（含代码锚点 / 子任务 / 验收 / 规模的逐项展开与功能总表）；本节为摘要。

| 窗口 | 内容 |
|---|---|
| **4.1.0（当前 release 分支）** | launch 包：代码签名/公证、README+docs 刷新（badge/JDK/新特性）、launch 材料与渠道执行 |
| **4.1.x 快速跟进** | MCP 一键安装（F1-1）；Flow 导出 MCP server（F1-2）；知识库官方插件（F1-3）；浏览器接管（F1-4）；Ollama 探测（F1-5） |
| **4.2** | 记忆转正（F2-1）；工作区检查点回滚（F2-2）；成本台账（F2-3）；Flow 运维增强（F2-4）；上下文可视化（F2-5）；调度增强（F2-6） |
| **信号触发** | 服务器模式 / run API / 网关变现 / 常驻 agent / IM 通道（以 issue 需求与社区规模为准） |

---

## §6 增长基线与衡量

- 基线（2026-10-15）：**2 stars / 1 fork / 0 open issues**。launch 后按月记录 stars、clone、issue 首条、插件商店提交数。
- 对标斜率参考：OpenCode ≈ 12k 星/月（爆发期）、Cherry ≈ 2.2k 星/月（近 9 个月）、AnythingLLM ≈ 1.9k 星/月、ZCode ≈ 313 星/天（开源前 25 天，背靠 Z.ai 品牌与既有用户，参考性打折）。**首个现实里程碑：launch 后 3 个月 1,000 星。**
- 转化漏斗前置条件：签名安装包、README 首屏 30 秒讲清"这是唯一进程外沙箱插件的 AI 编排台"、可一键跑通的 demo（e2e-smoke 已有，考虑录屏嵌入 README）。

---

## §7 保持更新机制（本调研的持续化）

**Watchlist（16 开源竞品 + 自身；ChatGPT 桌面版无仓库，按 OpenAI release notes / 帮助中心人工核对，同季度节奏）**。实测注：本网络 `api.github.com` 可直连，`github.com` HTML 抓取超时——用 API，不要抓页面。ZCode 仓库滞后官网版本，须 release tag + 官网 changelog 双源核对。

```bash
# 一键快照（输出: repo  stars  archived  pushed_at）
for r in cherryhq/cherry-studio chatboxai/chatbox janhq/jan \
         lobehub/lobehub open-webui/open-webui Mintplex-Labs/anything-llm LibreChat-AI/LibreChat \
         n8n-io/n8n langflow-ai/langflow langgenius/dify FlowiseAI/Flowise \
         anomalyco/opencode OpenHands/OpenHands cline/cline Aider-AI/aider zai-org/zcode MuskStark/FengYu; do
  curl -s --max-time 20 "https://api.github.com/repos/$r" | \
    python3 -c "import json,sys;d=json.load(sys.stdin);print(d['full_name'],d['stargazers_count'],d.get('archived'),d['pushed_at'],sep='\t')"
done
```

- **节奏**：每季度全面刷新一次（数字 + 与本期 delta + 事件）；每次 minor 发布前跑一次快照；事件驱动即时记录（license 变更、仓库归档、大额融资、高危 CVE、被收购）。
- **刷新本文档时**：更新 §1 表格数字并在表头改 as-of 日期；在 §7 末尾追加"事件日志"（一行一事件）；结论层（§2/§4）只在趋势变化时改写。
- **建议新增关注**：Open WebUI 的姊妹项目（Computer/Open Terminal，其 agent 化动向）、Anthropic/Google 的 agent 与 Skills 规范上游（LibreChat SKILL.md 与 FengYu .fys 同构，规范上游会影响两者）。

### 事件日志

- 2026-10-15：初版。Flowise 已归档（2026-08-13）；Aider 实质停滞；OpenCode 17.5 个月 21 万星；SAP 入股 n8n（$5.2B）；ClickHouse 收购 LibreChat；Open WebUI/LobeHub/Dify license 争议；Cherry ≤1.9.7 zero-click CVE。
- 2026-10-15（增补）：加入 ZCode 与 ChatGPT 桌面版。ZCode 2026-09-20 开源、25 天 7.8k 星、09-21 信任事件（Reuters：未经同意上传本地代码库）；ChatGPT 桌面 2026-07-09 Chat+Work+Codex 统一改版、Atlas 关停（07 弃用/08 死）、Tasks 刷新、Dots 常驻 agent（09-29，Pro 专属）、Linux 预览（08）。

---

## §8 来源（主要）

- GitHub REST API（2026-10-15 实拉）：全部星数/发布/归档状态
- Dify 官方博客：v1-6-0 双向 MCP、New Agent、10 万星里程碑、融资公告
- n8n：Series C 公告、2.0 变更、社区帖（Agents/Evals）
- Flowise 归档声明 discussion #6727；CSA CVE-2025-59528 在野利用通报（2026-04）
- Cline token 膨胀投诉（r/CLine、issue #7383）；OpenHands replay 论文与发布页
- LibreChat×ClickHouse 收购公告；Open WebUI/LobeHub license 争议 issue/discussion
- ZCode：GitHub API（2026-10-15）、Reuters（2026-09-21 信任事件）、Tom's Hardware（564 次外传尝试报道）、eesel.ai 评测、r/ZaiGLM 对比讨论
- ChatGPT 桌面版：OpenAI release notes / 帮助中心 / 官方公告（Work 统一、Dots、Atlas）、TechCrunch（900M WAU，2026-02）、9to5Mac（Atlas 关停）、TechRadar（07 改版反弹）、learn.chatgpt.com（Linux 预览）
- Reddit r/LocalLLaMA、r/OpenWebUI、r/Rag、HN 相关讨论（各项目痛点）
