# FengYu/Infinia Roadmap（2026-10 详细规划）

> **依据**：`COMPETITIVE-RESEARCH-2026-10.md`（16 产品对标结论）+ 2026-10-15 对 `release/4.1.0` 分支的全量代码盘点。
> 每个功能项都锚定真实现状（文件路径），区分【新增】/【优化】；规模 S≤3 天 / M 1–2 周 / L ≥3 周（单人+AI 辅助口径）。
> 本文档是内部规划，不入 `docs/` VitePress；公开 roadmap 可在 4.2 时从本文档脱敏派生。

路径缩写：`BE:` = `FengYu/src/main/java/fan/summer/fengyu` · `FE:` = `frontend/src` · `DT:` = `desktop/electron`。

---

## 0. 总览

| 批次 | 定位 | 窗口 | 内容 |
|---|---|---|---|
| **M0 · 4.1.0 Launch（最大化装载）** | 发布门槛 + 装载批 | 2026-11 中旬冻结 | L0-1/2/3 + 装载批 F1-5/F1-4/F2-6-lite/F2-1-lite/F2-5-lite/F1-1（F1-2 冲刺）——见"4.1.0 最大化装载" |
| **M1 · 4.1.x 快速跟进** | 冻结滑入项 + 商店侧收尾 | GA 后 1–4 周 | 冻结滑入项（→4.1.1/12） · F1-3 知识库插件（商店线） · F1-2（若冲刺未入） |
| **M2 · 4.2.0** | 长任务 UX + 运维成熟度 | launch 后 1–2 季度 | F2-1 记忆转正 · F2-2 检查点回滚 · F2-3 成本台账 · F2-4 Flow 运维 · F2-5 上下文工程 · F2-6 调度增强 · F2-7 审批分档（反编译分析增项） |
| **M3 · 4.3+/方向** | 信号触发，不承诺版本号 | 社区需求驱动 | F3-1 run API/CLI · F3-2 服务器模式 · F3-3 事件触发扩展 · F3-4 常驻 agent · F3-5 IM 通道 |
| **横切** | 持续 | 常态 | 发布节奏 · 安全滚动 · 社区运营 · 竞品季度刷新 |

**盘点带来的重要修正**（相对 `COMPETITIVE-RESEARCH-2026-10.md` §3 初版差距矩阵）：定时任务调度（`BE/ai/tasks/BackgroundTaskScheduler` + `SchedulesPage`）、webhook 触发（`WorkflowWebhookController`）、持久记忆（`BE/ai/memory/AiMemoryService`，实验+默认关）、Flow 版本化（publish + revisions + restore）**均已存在**——这些从"新增"降级为"优化/转正"，M2 因此比原估轻。

### 4.1.0 最大化装载（2026-10-15 范围调整，用户决策）

4.1.0 分支已含大件（MCP Apps、文件活引用零拷贝、工具结果预算、任务坞、三栏节点检查器、显式技能调用、多供应商 roster 等，见 CHANGELOG Unreleased）。在此基础上把 M1/M2 高性价比小件**前拉进 4.1.0**，大件按规则处理：

| 层 | 项 | 规模 | 说明 |
|---|---|---|---|
| **装载批（进 4.1.0）** | F1-5 Ollama 首启探测 | S | 本地优先开箱叙事 |
| | F1-4 浏览器人工接管 | S | ChatGPT 同款交互对齐 |
| | F2-6-lite 调度补 cron 表达式 | S | 调度器已稳，增量小（通知分类可后置） |
| | F2-1-lite 记忆"实验可见" | S | MemorySection 首屏+i18n+docs，默认关不动 |
| | F2-5-lite 上下文水位条 | S | 仅水位+压缩事件可见；可配项留 4.2 |
| | F1-1 MCP 一键 deep link | S-M | launch demo GIF 的现成素材 |
| **冲刺项（评审过+冻结前全绿则入）** | F1-2 Flow 导出 MCP server | M-L | 最佳 launch 叙事；网络边界设计评审是闸门 |
| **解耦项（不阻塞 GA）** | F1-3 知识库插件 | L | 官方插件走商店独立版本线——GA 后数日内上店即可，与 4.1.0 观感同屏 |
| **仍留 4.2** | F2-7 / F2-2 / F2-3 / F2-4 全量 | M×4 | 审批分档/回滚/台账/运维不做半成品 |

**纪律（质量红线不动摇）**：装载批约 3 周 → 2026-11 中旬冻结；冻结 = 全门禁绿 + 72h 浸泡 + 双语 changelog；**未赶上者一律滑入 4.1.1（1–2 周内，4.0.x 三补丁先例节奏）**，不为赶期降门禁；装载中段先发 4.1.0-beta.1 预热发布管线。

### 4.1.0 测试版 → GA 发布规划（2026-10-15 制定；beta.1 当轮落地）

| 阶段 | 时点 | 内容 | 出口门（全部满足才进下一阶段） |
|---|---|---|---|
| **beta.1**（首个测试版） | 2026-10-15 起 | 当前分支 + 版本 bump（alpha.1→beta.1），发布管线预热。**用户硬闸门（2026-10-15）：≥3 轮代码审核 + ≥2 轮实机检验全过，才允许进入发布流程** | 发 tag 前：tag 契约绿 + `assert-release-versions` ok + 契约测试双绿 + 预发布验证全套（backend package / frontend audit+build+test:unit+typecheck / desktop audit+test+build:ts / e2e-smoke / web 打包自检）+ 双语 changelog + **3 轮审核/2 轮实机记录**。发 tag 后：release workflow 三平台×双变体 + web 归档全绿 |
| **装载批窗口** | 10-15 ~ 11-上旬 | F1-5→F1-4→F2-6-lite→F2-1-lite→F2-5-lite→F1-1 逐项落地（每项独立走门禁）；L0-1a 安装信任分级 + L0-2 门面并行；消化 beta.1 安装反馈 | 每项：模块门禁绿 + e2e 相关探针 + i18n/文档同步 |
| **beta.2** | ~11-上旬末 | 装载批完成后的特性完整预览版（F1-2 若冲刺成功随版） | 装载批全部合入；全门禁绿；CHANGELOG/docs 同步；若滚动修复则以 beta.3 顺延 |
| **rc.1（冻结候选）** | ~11-中旬 | 代码冻结——只修不增 | 全门禁绿 + **72h 浸泡无新增 P0/P1** + 安装漏斗实测（brew/引导路径走通）+ launch 物料就绪（L0-3 草稿、demo GIF、安装引导页） |
| **GA v4.1.0** | ~11-中旬末 | rc 无阻断性修复即转正：tag `v4.1.0` + 六渠道 launch | GA 后 72h P0 追踪=0；滑入项→4.1.1（1–2 周）；F1-3 知识库插件走商店线（GA 同周~+1 周） |

**通用规则**：prerelease tag 重发 = force-tag（仅限未面向真实受众的 prerelease，AGENTS.md 先例）；GA 后绝不 force；每版 `git diff --check` 干净 + EN/ZH changelog 镜像一致；官方插件零捆绑（契约测试钉死）。

#### beta.1 审核与实机记录（用户硬闸门）

- **第 1 轮审核（2026-10-15，6 域并行）**：1×P0（Electron 43.4.0 重新引入 GHSA-qmv3-fv6v-rmhq）+ 2×P1（子代理供应商解析不走注册表；3 个 i18n key 缺失）+ P2/P3 若干。**修复后**：全量后端 1853 测试 0 失败、frontend 247 测试 + typecheck + build 绿、desktop 349 测试 + audit 零建议 + build:ts 绿、修复核验 14/14 PASS。
- **第 2 轮审核（2026-10-15，核验 + 3 盲区域）**：第 1 轮修复全数核验通过（含循环引用修复，Spring 上下文测试绿）；i18n 全量奇偶校验 **0 缺失**（en/zh 1576=1576 键、占位符全对齐）。新发现：**P0** toolchain/sdk-ts dist 移出 git 后无 workflow 先构建 link: 目标——CI 自 10-14 红，发布必失败；**P1** H2 插件库授权在全默认安装必挂（f2eb13f8 删除了管理员回退；真实 H2 实验证实）；P2×3（商店安装日志跨版本楔死、MCP Apps 事件流断线不重连、商店安装不刷新插件/技能镜像）+ P3 若干。修复进行中。
- **第 3 轮审核**：待第 2 轮修复落地后执行（终审发布就绪扫描）。
- **实机检验第 1 轮（2026-10-16，生产 JAR @ /tmp 全新工作目录，端口 24500）**：✅ 全点通过、零产品缺陷。T1 首启 SETUP 向导（H2 默认/连接测试"连接成功（H2 2.4.240）"/初始化/重启交接提示）；T2 裸 JAR 手动重启进 APP 模式（~6s 健康）；T3 主界面+iFlow 画布（模板/引导/工具栏）+ 本地草稿恢复对话框真实弹出（10-12 残留 localStorage）；T4/T5 商店在线目录（聚合技能+官方插件+分页+类型过滤）；T6-T8 设置全分区（AI Providers 四内置+自定义端点+Thinking Auto；MCP 空态+传输过滤；Memory 实验默认关）；T9 对话错误路径（Ollama 守护未跑→内联错误+后端引导告警，环境性非缺陷）；T10 **真实流式成功**（qwen3:4b thinking 卡→"1+1等于2。"→Copy/Regenerate/水位条 4.7k/131k）；T11 About 页版本 v4.1.0-beta.1 正确。全程截图留证（IAB artifacts）。
- **实机检验第 2 轮（2026-10-16，桌面 shell 链路）**：✅ 全点通过。后端移驻默认端口 24056（无 token）→ `desktop/electron yarn run dev` 启动：外部后端连接 ✓、浏览器桥 127.0.0.1:61989+token ✓、Vite :5173 ✓、health-ready +1.3s / main-ready +2.2s、启动遥测 total 5.6s（reactCommit 37ms）✓；System Events 确认 Electron 前窗 "Infinia"（1280×820）✓；同源渲染器（5173）视觉验证 ✓；**经桌面链路的真实对话成功**（qwen3:4b → 思考 4s → "蓝色" → Copy/Regenerate/水位 4.4k/131k）✓。零产品缺陷。
- **发布流程（2026-10-16 完成）**：e2e-smoke 复跑 exit 0 + web 打包自检 ALL PASS → 提交 `55361879`（72 文件）→ tag `v4.1.0-beta.1`。三次 tag 派发：①staging 目录缺失（a8ef9268 迁店删除插件打包步骤时带走 mkdir 副作用，4.1 线从未打过 tag 故未暴露）→ 修复 `29ba06f7`；②Windows 腿 bootstrap-cwd 测试 4 挂（范围内简化掉了 fs mock 的分隔符无关垫片，注释里写明的 Windows 故障模式复活）→ 恢复 `aca9d758`；③ChatCancelInterruptsToolTest 弱机时序抖动 → rerun-failed 通过。**最终 workflow `completed/success`，GitHub Release "Infinia 4.1.0-beta.1 (Pre-release)" 发布（2026-10-15T17:36Z，26 项资产：三平台×双变体安装包 + UOS + web zip/tar.gz + checksums + updater feeds）**。
- **遗留（4.1.1 候选）**：ChatCancelInterruptsToolTest / HookDispatcherTest 为负载敏感型抖动用例，建议加 await 硬化；test-web-release.sh 的"Vue shell"文案过时。

---

## 1. M0 —— 4.1.0 Launch（当前分支合并即启动）

### L0-1 【新增】安装信任分级（签名分级策略）—— 规模 S-M（2026-10-15 依用户成本约束改写：个人开发者证书成本高，公证后置为触发式）

- **现状**：`DT/electron-builder.yml` 显式 `signedRelease: false`（"P0-9 build-time signed-state contract"，签名版可 `--config` 覆盖）；**updater 已按签名分流**（signed→auto-install，unsigned→notify+手动下载——无签名下更新器不坏）；Web 发行包（zip+run.sh）天然无 OS 签名摩擦。
- **分级子任务**：
  - **L0-1a（M0 必做，0 元）安装摩擦最小化**：① macOS 主渠道走 **Homebrew cask**（brew 经 curl 下载，不写 com.apple.quarantine → 无 Gatekeeper 拦截，免公证）；② 安装页/README 双 OS 引导——macOS 15+ 右键"打开"已失效，必须指向 系统设置→隐私与安全性→**"仍要打开"**；Windows 指向 "更多信息→仍要运行"；③ Web 发行包作为零摩擦备选并列展示；④ README "unsigned" 表述改为诚实的"签名路线图"说明。
  - **L0-1b（M0 并行提交申请，0 元）Windows 免费签名**：申请 **SignPath Foundation** 开源免费签名（OV 级、以基金会名义、绑定仓库构建验证、免个人身份材料；GPL OSS 符合画像，2026 年项目活跃）。SmartScreen 信誉随下载量积累。备选：Certum Open Source（€25–29，仅身份签名，SmartScreen 不认——价值低）。EV 不碰。落地后走签名版 builder override + **同步 `scripts/release-workflow.test.mjs`**（AGENTS.md 铁律）。
  - **L0-1c（触发式，$99/年）macOS Developer ID + 公证**：触发条件=brew 外浏览器直下的转化数据/用户反馈显示摩擦真实、或首笔捐赠/收入可覆盖。届时购 Apple Developer Program + notarytool 公证。
- **验收**：L0-1a：brew cask 安装路径零红屏实测 + 引导页双 OS 截图准确；L0-1b：SignPath 签名产物 SmartScreen 信誉路径启动（不再未知发布者警告或逐版本改善）；L0-1c（届时）：公证产物 Gatekeeper 直开。
- **风险**：macOS 15+ 浏览器直下确有摩擦（中低，brew 缓解）；SignPath 审核周期（并行不阻塞）；UOS 变体签名另议。

### L0-2 【优化】README / docs 门面翻新 —— 规模 S

- **现状**：README badge 停在 `4.0.0--beta.2`；技术栈表 Java 21 vs 需求 JDK 25 不一致；4.1.0 新特性（MCP/MCP Apps、调度、记忆、webhook、商店）未上特性清单。
- **子任务**：badge 与版本镜像修正；特性清单补全（安全隔离叙事放第一条：进程外 worker + iframe 沙箱 + Ed25519 签名商店 + 确认制）；JDK 25 口径统一；GitHub topics + social preview 图；README 内嵌 30s demo GIF；`docs-updater` 流程过一遍（EN/ZH 镜像 + changelog sync）。
- **验收**：`cd docs && yarn run build` 绿；两语言结构一致；版本一致性断言（`scripts/assert-release-versions.mjs`）通过。

### L0-3 【新增】渠道执行与发布物料 —— 规模 S（运营）

- **内容**：Show HN / r/LocalLLaMA / r/selfhosted（EN）、V2EX / 少数派 / 掘金（ZH）launch 帖草稿；复用已有两代宣传视频；www.infinia.fyi 落地页与下载链路核对；awesome-lists PR。
- **验收**：完整装机漏斗人工走一遍（帖子→落地页→下载签名包→首启向导→跑通一个官方插件）；渠道清单全发。

---

## 2. M1 —— 4.1.x 快速跟进（launch 后 4–8 周，逐项独立发版）

### F1-1 【新增】MCP 一键安装（deep link）—— 规模 S-M

- **现状**：MCP 运行时已很深——`BE/ai/mcp/McpRuntimeManager`（官方 SDK，stdio/SSE/streamable-HTTP，命名空间工具、退避自愈）；商店已分发 MCP 模板（`InstallerDispatcher` 装 `mcp-servers/<uid>.json`）；设置页 `FE/components/settings/McpSection.tsx` 是**手动表单**添加。
- **子任务**：注册自定义 URL scheme（`infinia://`，desktop shell `DT/src/ipc/` + main 注册）；`infinia://mcp/install?...` 处理器→复用 StoreController 安装链路；商店页 MCP 卡片加"一键安装"按钮；未运行 app 时拉起。
- **验收**：从网页商店卡片点击→app 拉起→安装→启用，全程无手动抄配置；`scripts/e2e-smoke.sh` 加 deep-link 安装路径探针（或桌面 E2E 单独 opt-in spec，遵循 AGENTS.md 的 gating 稳定性规则）。

### F1-2 【新增】Flow 导出为 MCP server —— 规模 M-L ★M1 核心

- **现状**：flow 对内已可发布为 AI 工具（`WorkflowExecutionService.executeForAi`，15 分钟超时、审批/授权语义齐全）；MCP 消费侧全套已有；**缺对外暴露层**。
- **子任务**：新增 streamable-HTTP MCP server 端点（默认 **loopback-only**，显式配置才监听局域网且强制 token——与现有安全模型一致）；把 published flow 的输入 schema 映射为 MCP tools；复用 executeForAi 的授权/审批/预算语义；Flow 发布页与 McpSection 加开关与调用地址展示；调用日志。
- **验收**：第三方 MCP 宿主能发现并调用 FengYu flow；含敏感节点的 flow 调用走既有审批门；外部监听默认关闭、开启需显式确认（安全测试用例固化）。
- **战略意义**：Dify v1.6 / Langflow 同款能力——让竞品宿主也能调用 FengYu 编排，把产品变成生态节点。
- **风险**：网络边界设计须评审（UrlPolicy 现有语义可复用）。

### F1-3 【新增】知识库官方插件（.fyp）—— 规模 L ★M1 最大件

- **现状**：RAG 全仓 **NOT FOUND**（`AiMemoryService` 注释明确"core 层暂缓向量检索直到 embedding 后端有保证"——本项以插件层落地正合该设计哲学）；插件已具备 database 权限（注入数据源）与确认制工具面。
- **子任务**：官方 `.fyp`（Java worker）：文档导入（PDF/MD/txt/Office）→ 切块 → embedding → 存储（插件库表 + 自管相似度检索，先余弦扫描后升级）；embedding 后端 = 已配置 provider 的 embedding API（`ModelMetadataCatalog` 加 embedding 能力标记，fail-safe 回退清晰）+ 无 embedding 时的降级（BM25/关键词）；agent 工具 `kb_search`（引用返回，走插件工具面）；UI tabs（文档列表/导入/检索测试）。
- **验收**：导入百页文档→agent 提问命中并给出引用；断 provider 时降级可用且 UI 明示；e2e 用 smoke-plugin 同款通道加 kb 断言。
- **风险**：与 `AiMemoryService` 检索的关系要在文档里讲清（记忆=agent 自记，KB=用户文档，两套并存）。

### F1-4 【优化】Browser Agent 人工接管 —— 规模 S

- **现状**：`BE/ai/tools/BrowserTool` + `DT/src/browser/`（可见窗口、持久登录 partition、CDP、元素 stamping、批量/eval）；**无显式接管 API**（用户只能物理抢鼠标）。
- **子任务**：`BrowserTool` 增加 `handoff`（暂停 agent 控制、窗口置前、保留会话）；前端 run 详情/通知中心提示"已接管"；接管后 resume 语义定义。
- **验收**：agent 浏览中一键接管，登录态与标签页保留；ChatGPT 桌面同款交互对齐（对标其内置浏览器接管）。

### F1-5 【优化】Ollama 首启探测引导 —— 规模 S

- **现状**：Ollama 一等后端（`BE/ai/service/OllamaLocalBackend`，`ProviderModelsFetcher` 拉取 `/api/tags`，`ConnectionTester` 有 ollama 分支）；**无首启探测**（NOT FOUND）。
- **子任务**：首启向导/设置页探测 `localhost:11434`（复用 fetcher）→ 检测到即提示启用 + 推荐 roster 模型（`FE/lib/aiProviders.ts` 已有清单）一键 `ollama pull` 指引。
- **验收**：装有 Ollama 的机器首启即被识别并完成接入；未装时零打扰。

---

## 3. M2 —— 4.2.0（长任务 UX + 运维成熟度，launch 后 1–2 个季度）

### F2-1 【优化】持久记忆转正 —— 规模 M

- **现状**：`BE/ai/memory/AiMemoryService` + `memory_remember|search|list|forget` 工具 + `MemorySection.tsx` 设置面**已存在**（实验、默认关、keyword+recency 检索、7 天半衰期）。
- **子任务**：检索质量升级（可选挂 F1-3 的 embedding，保持无向量时的现行为）；预算化注入（与 compaction/工具预算协同）；记忆管理 UI 增强（批量清理、按会话来源过滤）；默认策略评审（保持 opt-in，但设置页首屏可见 + 首次使用引导）；记忆条目可解释（来源会话/时间）。
- **验收**：跨会话记忆命中端到端用例；开关关闭时行为与现状完全一致（零回归）；i18n en/zh 同步。

### F2-2 【新增】Run 检查点与工作区回滚 —— 规模 M

- **现状**：run 级 resume/fork/rewind 已有（`AiRolloutService` + `/api/agent/runs/{id}/resume|fork|rewind`）；`markInterruptedRuns` 重启恢复已有；**工作区快照/回滚无产品面**（`WorkspaceGitController`/`WorkspaceChangesController` 的 git 基础已在，Cline 影子 git 的原料齐了）。
- **子任务**：agent run 开始/结束自动 commit/tag（对话工作区）；run 详情页"回滚到此 run 之前"（走确认门）；回滚范围文档化（只回滚工作区文件，不动插件 DB 副作用——边界写进 docs）；与 resume/fork 的交互矩阵理清。
- **验收**：破坏性 run 后一键恢复工作区；回滚动作本身需审批；desktop E2E 加稳定用例（或 opt-in 门）。

### F2-3 【新增】成本/用量台账 —— 规模 M

- **现状**：素材全有但无台账——rollout `TurnEnded.completionTokens` 记每轮、`ModelMetadataCatalog.CostRates` 有费率、SSE 有 `outputCostEstimate`、`AiUsageMetrics`（Micrometer/OTLP）有指标；**无 per-run/per-conversation 累积实体**。
- **子任务**：ledger 实体（run/conversation 维度：tokens in/out、缓存命中、估算成本、按 provider/model 分解）；会话头与 run 详情展示；设置页用量仪表盘（本期/累计）；与 OTLP 导出对齐口径。
- **验收**：与 provider 控制台抽样对账误差可解释（缓存/多模态口径注释）；分 run/分会话可查；历史会话补不补录明确（不补录，从启用起算）。

### F2-4 【优化】Flow 运维增强 —— 规模 M

- **现状**：publish + revisions + restore 已有；webhook delivery log 后端已有；**缺 UI 聚合**（版本 diff、发布说明、run tracing 时间线视图）。
- **子任务**：revision diff 视图（节点/边变更高亮）；发布说明字段；run tracing 时间线（每步耗时/状态/产物，数据源=既有事件流与 rollout）；webhook 投递日志页面化；evals 留观察项（n8n/Dify 方向，暂不做）。
- **验收**：发布→回滚闭环全 UI；单 run 时间线可查每步结果；delivery log 可检索。

### F2-5 【优化】上下文工程可视化 —— 规模 S-M

- **现状**：`ConversationCompactor`（60% 阈值触发、8 轮保留、硬截断兜底）+ `ToolResultBudget`（插件 256KiB/MCP 64KiB 溢出转存 artifacts）已较完整；**用户不可见**（对标 Cline/OpenCode 的 #1 痛点=token 膨胀不可感知）。
- **子任务**：context meter（会话头水位条：当前占用/压缩阈值/最近压缩点）；compaction 参数可配（阈值/保留轮数，高级设置）；压缩事件在时间线可见。
- **验收**：长会话压力下水位与实际行为一致；压缩前后有可感知提示；默认参数不变。

### F2-6 【优化】调度增强 —— 规模 S

- **现状**：`BackgroundTaskScheduler` 已有固定间隔 + 日历调度（DAILY/WEEKLY/MONTHLY、DST 规则、错过合并）+ at-most-once；`SchedulesPage` UI 已有。
- **子任务**：cron 表达式类型（用户强诉求的通用口径）；错过运行补跑策略开关（现固定 coalescing）；任务结果通知分类细化（成功/失败/跳过差异化文案，`NotificationService` 管线现成）。
- **验收**：cron 用例矩阵（含 DST 边界）过；三类通知文案区分；与 at-most-once 语义的文档说明。

### F2-7 【优化】审批分档与权限档位 —— 规模 M（来源：ChatGPT 桌面反编译分析，2026-10-15）

- **现状**：确认制为单次审批粒度（`ToolLoopDriver` 取消/审批门 + flow run 授权回收），无会话/持久域、无权限档位模型。ChatGPT 桌面端已把该面做成"三维五档 + 审批三联 + 自动审查"（详见 `CHATGPT-SOURCE-ANALYSIS-2026-10.md` §3）。
- **子任务**：审批卡片三联（Allow once / 本会话 / 始终 + deny-with-reason）；"相似命令前缀放行"与"本会话全部编辑"域语义；权限档位三档（read-only / workspace-write / full）映射到 agent 工具面与 flow 运行授权；审批/elicitation 前台无操作超时自动拒（宁拒不等待）；统一 pending-input 面板（计划确认与审批平级）。远期：guardian 式 reviewer 子代理（挂 M3 常驻 agent 基座）。
- **验收**：三档权限矩阵端到端用例；前缀放行的边界用例（防通配过宽）；超时自动拒用例；档位切换即时生效且 UI 明示风险文案；既有单次审批路径零回归。

---

## 4. M3 —— 4.3+/方向性（信号触发，不排期）

| ID | 类型 | 功能 | 现状锚点/前提 | 触发信号 |
|---|---|---|---|---|
| F3-1 | 新增 | Agent run API / `fengyu run` CLI（headless CI） | headless server 天生具备；需 `--listen` 逃生舱 + token 边界评审 | 社区要 CI/脚本集成 |
| F3-2 | 新增 | 服务器/团队模式（多用户+RBAC） | 远程 DB（MySQL/PG）向导已支持；Cherry Enterprise 先例 | 团队部署 issue 密度 |
| F3-3 | 新增 | 事件触发编排扩展（邮件/文件监视/通知→flow） | webhook 触发器（HMAC、delivery log）已是骨架 | F1-2 落地后的采用率 |
| F3-4 | 新增 | 常驻 agent（Dots 方向） | 调度+通知+记忆三件套 M2 后齐备 | ChatGPT Dots 下放/竞品跟进 |
| F3-5 | 观察 | IM 通道（微信/飞书/Telegram bot） | ZCode 先例；与 loopback 安全模型有张力 | 明确社区需求 |

---

## 5. 横切（常态运行）

1. **发布节奏**：双周 minor/patch 维持（GA 后两周三补丁已达标）；每版 EN/ZH changelog（`sync:changelog` 机制已有）。
2. **安全滚动**：依赖 EOL 政策（AGENTS.md 已固化）；季度安全自查（对照竞品 CVE：Flowise RCE、Cherry zero-click 是检查单背景）；CSP/沙箱边界随新端点（F1-2）评审。
3. **社区运营**：issue 模板 + CONTRIBUTING（launch 前最低配）；4.2 时从本文档脱敏派生公开 roadmap 页入 docs。
4. **竞品季度刷新**：按 `COMPETITIVE-RESEARCH-2026-10.md` §7 脚本与节奏执行，事件驱动即时记。
5. **商店生态**：官方插件（KB、既有 Excel/Email/Markdown/OfflinePython）版本独立线维护；第三方插件提交流程与签名审核文档化。

---

## 6. 功能项总表（优化/新增清单）

| ID | 功能 | 类型 | 批次 | 规模 | 一句话现状→目标 |
|---|---|---|---|---|---|
| L0-1 | 安装信任分级（签名分级） | 新增 | M0 | S-M | `signedRelease:false` → 0 元摩擦最小化（brew/引导）+ SignPath 免费申请 + $99 公证触发式后置 |
| L0-2 | README/docs 门面 | 优化 | M0 | S | badge/JDK/特性清单过时 → launch 级门面 |
| L0-3 | 渠道执行与物料 | 新增 | M0 | S | 无公开渠道 → 六渠道 launch + 漏斗验证 |
| F1-1 | MCP 一键安装 deep link | 新增 | M1 | S-M | 手动表单添加 → URL scheme 一键装 |
| F1-2 | Flow 导出 MCP server | 新增 | M1 | M-L | 只对内暴露 → 对外 MCP 端点（loopback 默认） |
| F1-3 | 知识库官方插件 | 新增 | M1 | L | RAG 零代码 → .fyp 插件全套（导入/向量/检索工具） |
| F1-4 | 浏览器人工接管 | 优化 | M1 | S | 只能物理抢 → handoff API + 提示 |
| F1-5 | Ollama 首启探测 | 优化 | M1 | S | 手动测试连接 → 首启探测+引导 |
| F2-1 | 持久记忆转正 | 优化 | M2 | M | 实验默认关 → 质量升级+管理增强+首屏可见 |
| F2-2 | Run 检查点/工作区回滚 | 新增 | M2 | M | run resume/fork/rewind 已有 → 工作区快照+一键回滚 |
| F2-3 | 成本/用量台账 | 新增 | M2 | M | 素材散落 → per-run/conversation ledger+仪表盘 |
| F2-4 | Flow 运维增强 | 优化 | M2 | M | revisions 有面无 diff → diff+发布说明+tracing 时间线 |
| F2-5 | 上下文工程可视化 | 优化 | M2 | S-M | 压缩/预算不可见 → context meter+可配+时间线事件 |
| F2-6 | 调度增强 | 优化 | M2 | S | 间隔+日历已有 → +cron+补跑策略+通知分类 |
| F2-7 | 审批分档与权限档位 | 优化 | M2 | M | 单次审批 → 三联审批+三档权限+超时自动拒（反编译增项） |
| F3-1 | run API / CLI | 新增 | M3 | M | —（信号触发） |
| F3-2 | 服务器/团队模式 | 新增 | M3 | L | —（信号触发） |
| F3-3 | 事件触发扩展 | 新增 | M3 | M | webhook 骨架已有 |
| F3-4 | 常驻 agent | 新增 | M3 | M-L | 调度+通知+记忆为基座 |
| F3-5 | IM 通道 | 观察 | M3 | — | 安全模型张力待评审 |

**统计**：新增 11 项 / 优化 8 项（+1 观察）；M0 共 3 项（S×2 + M×1），M1 共 5 项（L×1 主导），M2 共 7 项（M 为主，F2-7 来自反编译分析增项）。

---

## 7. 依赖与风险

- **外部依赖**：F1-3 embedding API 费率/可用性；L0-1b SignPath 申请审核周期（并行提交，不阻塞 launch）；L0-1c macOS 公证（$99/年，触发式，不再阻塞任何阶段）。
- **关键路径**：F1-3 → F2-1（记忆检索升级可复用 KB embedding）；L0-1a 安装引导 → L0-3（漏斗验证依赖无摩擦引导而非签名）。
- **设计评审点**：F1-2 网络边界（loopback 原则的显式逃生舱语义）；F2-2 回滚边界（工作区 vs 插件 DB 副作用）；F1-3 与 AiMemory 的检索分工。
- **明确不做**（沿策略文档）：语音/图像生成、marketplace 规模竞赛、Tauri 重写、手拖画布扩张、license 变更。

## 8. 验收门禁（repo-native，每项通用）

后端改动：`./mvnw clean package -f FengYu/pom.xml`（相关模块）· 前端改动：`yarn run typecheck && yarn test:unit && yarn run build` · 打包/签名配置：`node --test scripts/release-workflow.test.mjs` · 端到端：`scripts/e2e-smoke.sh` · 桌面链路：CI desktop E2E（gating spec 稳定性规则见 AGENTS.md）· 文档：docs build + `sync:changelog` · i18n：en/zh 同步提交。
