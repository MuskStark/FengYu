# FengYu/Infinia 总体规划（2026-10）

> **定位**：本文件是产品主计划——自洽可读，收拢四份支撑文档的结论并补齐时间线/阶段门/指标/风险/节奏。
> 深度细节按需下钻：竞品结论 → `COMPETITIVE-RESEARCH-2026-10.md`；功能项子任务/验收/代码锚点 → `ROADMAP-2026-10.md`；
> 反编译依据 → `CHATGPT-SOURCE-ANALYSIS-2026-10.md`。内部文档，不入 `docs/` VitePress。
> **维护规则**：季度复审一次（与竞品刷新同窗口）——只更新 §2 基线数字、§6 时间线进度、§9 风险状态；战略层（§3–§5）只在触发事件时改写。

---

## §1 产品定位（一句话）

**本地优先的 AI 原生编排工作台**：plan-and-execute Agent 作为主轴，编排三个扩展面（`.fyp` 进程外沙箱插件 / `.fys` 渐进披露技能 / Flow 可视化工作流），跑在 headless Spring Boot + React 19 + Electron 的 web+desktop 双形态上——**用全场最严的隔离与确认制，换取用户把真实业务操作交给 agent 的信任**。

差异化三柱（每条都有竞品当日证据支撑）：
1. **安全隔离**：进程外 JSON-RPC worker + 沙箱 iframe + Ed25519 签名商店 + 确认制敏感工具 + loopback-only。（对照：Open WebUI 插件进程内执行；Flowise 死于未鉴权 RCE；Cherry zero-click CVE；ZCode 未经同意上传本地库；ChatGPT full-access 下浏览器 origin 自动放行。）
2. **编排原生**：agent 主轴 + flows-as-tools + skills，与行业收敛方向一致（Dify New Agent / n8n Agents / ChatGPT 统一桌面壳均验证此形态）。
3. **本地优先 + BYO 模型**：数据不出机、模型自带（对照 ChatGPT 真 agent 用量 $100–500/月的价格锚）。

## §2 现状基线（2026-10-15 实测）

| 维度 | 状态 |
|---|---|
| 仓库 | `MuskStark/FengYu`，**2 stars / 1 fork**（2026-02-25 建仓），GPL-3.0 |
| 发布线 | v4.0.0 GA（09-30）→ 4.0.1/02/03（10-10/12/14）——节奏达标；`release/4.1.0` 分支待发（含 MCP 深化/商店/调度/记忆实验/flow 版本化） |
| 已具备（盘点确认） | MCP 运行时（官方 SDK 三传输 + MCP Apps 沙箱 + 商店模板）、调度器（间隔+日历+DST+at-most-once）+ webhook 触发、实验性持久记忆（默认关）、Flow publish+revisions+restore、run resume/fork/rewind + rollout 回放、rollout JSONL + Micrometer/OTLP、统一通知中心、Browser Agent（Electron 原生）+ Computer Use、多 DB 向导、toolchain（Java/Python/Go SDK + CLI） |
| 主要缺口 | 分发（2 星）、代码签名（`signedRelease:false`）、RAG/知识库（零代码）、MCP 一键 deep link、flow 对外 MCP 导出、成本台账、工作区快照回滚、审批分档（F2-7）、Ollama 首启探测、上下文水位可见性 |
| 竞争环境 | 四赛道 15 开源对标（4 万–21 万星）+ ChatGPT 桌面闭源基准；品类仍在爆发期（OpenCode 17.5 月 21 万星） |

## §3 战略（三步走）

- **第一步（现在）·被看见**：4.1.0 为 launch 版本正式上市。产品已 GA 级、架构有独占位，最大杠杆是可发现性——launch 的 ROI 高于任何单项功能。
- **第二步（launch 后两个迭代）·过评测线 + 通生态**：补齐表格化必查项（知识库、MCP 一键），把 Flow 变成 MCP 生态节点（对外导出）——让选型第一轮不被划掉，让竞品宿主也能调用 FengYu。
- **第三步（4.2 起）·深护城河**：权限工程（F2-7，反编译产出的最高价值输入）、长任务 UX（检查点/回放/成本台账）、上下文工程可视化——把"信任"从架构属性变成用户可见可量的产品面。

**主动不做**（季度复审）：语音/图像生成（commodity，走 MCP/插件）、marketplace 规模竞赛、Tauri 重写、手拖低代码画布扩张（Flowise 判词）、license 变更、Popcorn 式工件套件/Codex Micro 硬件/Daybreak 类侧翼。

## §4 完整路线图（M0–M3 全条目）

规模：S≤3 天 / M 1–2 周 / L ≥3 周。细节（子任务/验收/代码锚点）见 `ROADMAP-2026-10.md`。

### M0 · 4.1.0 Launch（3 项 + 装载批）

> 2026-10-15 调整（用户决策：4.1.0 最大化装载）：F1-5/F1-4/F2-6-lite/F2-1-lite/F2-5-lite/F1-1 前拉进 4.1.0；F1-2 为冲刺项（网络边界评审为闸）；F1-3 知识库走商店独立线解耦；F2-7/F2-2/F2-3/F2-4 留 4.2 不做半成品。纪律：11 月中旬冻结（全门禁绿+72h 浸泡），滑入项一律 →4.1.1。详见 `ROADMAP-2026-10.md` "4.1.0 最大化装载"。
| ID | 功能 | 类型 | 规模 |
|---|---|---|---|
| L0-1 | 安装信任分级（0 元：brew cask 主渠道+双 OS"仍要打开"引导+Web 包备选；并行申请 SignPath 免费签名；macOS $99 公证降为触发式） | 新增 | S-M |
| L0-2 | README/docs 门面翻新（badge/JDK25/特性清单/安全叙事第一条/social preview） | 优化 | S |
| L0-3 | 渠道执行（Show HN / r/LocalLLaMA / r/selfhosted / V2EX / 少数派 / 掘金 + 视频复用 + 漏斗实测） | 新增 | S |

### M1 · 4.1.x 快速跟进（5 项，launch 后 4–8 周）
| ID | 功能 | 类型 | 规模 |
|---|---|---|---|
| F1-1 | MCP 一键安装 deep link（`infinia://` scheme + 商店卡片直装） | 新增 | S-M |
| F1-2 | Flow 导出为 MCP server（默认 loopback、显式监听需 token；复用 executeForAi 审批语义） | 新增 | M-L ★ |
| F1-3 | 知识库官方 `.fyp` 插件（导入/切块/embedding/kb_search 工具；provider embedding + 无 embedding 降级） | 新增 | L ★ |
| F1-4 | Browser Agent 人工接管（handoff API + 通知提示；二期页内批注） | 优化 | S |
| F1-5 | Ollama 首启探测引导（11434 探测 + roster 一键指引） | 优化 | S |

### M2 · 4.2.0（7 项，launch 后 1–2 季度）
| ID | 功能 | 类型 | 规模 |
|---|---|---|---|
| F2-1 | 持久记忆转正（检索升级/预算注入/管理增强/首屏可见） | 优化 | M |
| F2-2 | Run 检查点与工作区回滚（run 打快照 + 一键回滚；边界=只回滚工作区文件） | 新增 | M |
| F2-3 | 成本/用量台账（per-run/conversation ledger + 仪表盘 + token 分 cached/uncached/output） | 新增 | M |
| F2-4 | Flow 运维增强（revision diff/发布说明/run tracing 时间线/webhook 日志页） | 优化 | M |
| F2-5 | 上下文工程可视化（水位条/压缩可配可感知） | 优化 | S-M |
| F2-6 | 调度增强（cron 表达式/补跑策略/通知分类） | 优化 | S |
| F2-7 | 审批分档与权限档位（三联审批+前缀放行+三档权限+超时自动拒+统一 pending-input 面；远期 guardian） | 优化 | M（反编译增项） |

### M3 · 4.3+/方向（信号触发，不排期）
| ID | 功能 | 触发信号 |
|---|---|---|
| F3-1 | Agent run API / `fengyu run` CLI（安全蓝本=OS 钥匙串设备密钥+step-up） | CI/脚本集成需求 |
| F3-2 | 服务器/团队模式（多用户+RBAC） | 团队部署 issue 密度 |
| F3-3 | 事件触发编排扩展（邮件/文件监视/通知→flow；webhook 骨架已有） | F1-2 采用率 |
| F3-4 | 常驻 agent（Dots 方向；调度+通知+记忆基座） | ChatGPT 下放/竞品跟进 |
| F3-5 | IM 通道（微信/飞书/Telegram） | 明确需求（与 loopback 有张力） |

### 横切（常态）
双周发布节奏 · 安全滚动（依赖 EOL 政策+季度安全自查） · 社区运营（issue 模板/CONTRIBUTING → 4.2 公开 roadmap 页） · 竞品季度刷新（watchlist 脚本） · 商店生态维护。

## §5 阶段门（进入下一批次的判定条件）

| 门 | 条件 | 未达标动作 |
|---|---|---|
| G1：M0→M1 | 4.1.0 已发布且安装漏斗实测通过（brew/引导路径：下载→完成安装→首启向导→跑通一个官方插件）；六渠道已发 | M1 顺延，优先补安装引导/物料，不抢跑功能 |
| G2：M1→M2 | F1-1/F1-2/F1-3 至少落地并随一个 4.1.x 发布；launch 后 ≥6 周；无未关闭的 P0 回归 | 砍 F1-4/F1-5 进下个 patch，M2 照常启动 |
| G3：M2→M3 | 4.2.0 发布；至少一个 M3 信号阈值触发（见 §7 指标）；单人带宽评估通过 | M3 继续观察，横切加固/补 4.2.x |

## §6 时间线（日历视角，2026-10-15 制定）

```
2026-10 下旬~11 上旬  装载批开发（~3 周）：F1-5→F1-4→F2-6-lite→F2-1-lite→F2-5-lite→F1-1；L0-1a 安装信任分级 + L0-2 门面并行；提交 SignPath 申请
2026-11 上旬          4.1.0-beta.1 预热（发布管线+全门禁实测）→ F1-2 冲刺（评审过+冻结前绿则入）
2026-11 中旬          冻结（全门禁绿+72h 浸泡+双语 changelog）→ 4.1.0 GA + launch（L0-3 六渠道）
2026-11 下旬~12       冻结滑入项 → 4.1.1/4.1.2；F1-3 知识库插件走商店线（目标 GA 同周~+1 周）
2027-Q1        M2 开发：F2-7/F2-2/F2-3 三件先行（信任面优先），F2-1/F2-4/F2-5/F2-6 并行小步
2027-Q1 末~Q2  4.2.0 发布 + 公开 roadmap 页 + 社区配套（CONTRIBUTING/issue 模板升级）
2027-H2        M3 按信号逐项评估（4.3 线或直接 5.0 方向：服务器模式+run API 为候选主线）
每季度末       竞品刷新 + 本计划 delta 复审（§7 脚本 + 阶段门核对）
```

关键路径仅一条：**F1-3 知识库→F2-1 记忆检索升级**（embedding 复用）。证书已移出关键路径——签名分级策略下，公证（$99/年）在转化数据或首笔收入触发时再启用（2026-10-15 用户成本约束反馈）。

## §7 指标体系（launch 起按月记录）

| 层 | 指标 | 3 个月目标 | 6 个月目标 |
|---|---|---|---|
| 获客（代理，无遥测） | GitHub stars / clone / release 下载量 | **1,000 星**（对照斜率：AnythingLLM≈1.9k/月、Cherry≈2.2k/月——目标刻意保守） | 3,000 星 |
| 转化 | 落地页→下载→安装漏斗实测无红屏；首启向导完成率（商店 opt-in install-events 为代理） | 漏斗全通 | 安装量趋势向上 |
| 生态 | 外部插件/skill/MCP 提交数；外部 contributor PR | 首个外部提交 | ≥5 |
| 质量 | 门禁全绿率；发布后 72h P0 回归数 | 100% / 0 | 100% / 0 |
| 采用深度 | 商店 MCP/知识库插件安装激活（opt-in 事件） | 建立基线 | 环比增长 |

M3 触发阈值示例：F3-1=出现 ≥3 个独立 CI/脚本集成 issue；F3-2=团队部署类 issue ≥10 或有企业询问；F3-4=ChatGPT 把 Dots/常驻 agent 下放至免费层。

## §8 验收门禁（repo-native，每项通用）

后端 `./mvnw clean package -f FengYu/pom.xml` · 前端 `yarn run typecheck && yarn test:unit && yarn run build` · 打包/签名 `node --test scripts/release-workflow.test.mjs` · 端到端 `scripts/e2e-smoke.sh` · 桌面链路 CI desktop E2E（gating 稳定性规则） · 文档 docs build + `sync:changelog` · i18n en/zh 同步。红线：任何发布前 `git diff --check` 干净、两语言 changelog 镜像一致。

## §9 风险登记册

| 风险 | 等级 | 缓解 |
|---|---|---|
| macOS 未公证的浏览器直下摩擦（macOS 15+ 右键"打开"已失效） | 中低 | brew cask 主渠道（curl 下载无隔离标记）；安装页"仍要打开"引导；Web 包零摩擦备选；SignPath 免费签名先覆盖 Windows；$99/年公证触发式后置 |
| 竞品降维（OpenAI 下放 agent 用量/降价、开源竞品融资加速） | 中 | 差异化锚在隔离/本地/插件生态而非价格；季度刷新盯信号 |
| 单点人力范围蔓延 | 中 | M3 信号触发制 + 主动不做清单季度复审；AI 辅助流水线已验证产能 |
| F1-2/F3-1 的网络边界破坏 loopback 安全模型 | 中 | 设计评审点前置（UrlPolicy 复用；默认关、显式开+token）；安全用例固化进 e2e |
| 合规/声誉 | 低-中 | 反编译材料仅内部架构级引证；ZCode 字样不进产品面；GPL 边界保持干净 |
| launch 后回归伤口碑 | 中 | 72h P0 追踪指标 + 双周节奏里留热修窗口（4.0.x 先例：GA 后两周三补丁） |

## §10 运营节奏（周/季）

- **每周**：发布窗口（双周 minor/patch）；issue 清零日。
- **每季**：竞品刷新（`COMPETITIVE-RESEARCH-2026-10.md` §7 脚本 + 事件日志）；本计划 delta 复审；安全自查（对照竞品 CVE/暴露面清单）；"主动不做"清单复审。
- **事件驱动**：license 变更/仓库归档/融资/CVE/大版本发布 → 即时记入竞品事件日志并评估对本计划的影响。

---

## 附：文档索引

| 文件 | 角色 |
|---|---|
| `MASTER-PLAN-2026-10.md`（本文件） | 主计划：定位/路线图全表/时间线/阶段门/指标/风险/节奏 |
| `COMPETITIVE-RESEARCH-2026-10.md` | 竞品调研（16 对标 + 差距矩阵 + 趋势 + 刷新机制） |
| `ROADMAP-2026-10.md` | 功能级明细（子任务/验收/代码锚点/规模） |
| `CHATGPT-SOURCE-ANALYSIS-2026-10.md` | 反编译分析（权限模型/安全姿态/功能面/落点映射） |
