# 编码代理 OS 级沙箱专项（差距清单 ②）——设计文档与实施任务书

更新日期：2026-09-29（立项版 1；同日修订 1：`ToolLoopDriver`/`RolloutEvent` 架构批次已落地——
轮次编排与 rollout 记录自此单点化，§2 地基与 §4.4/§4.5 接线描述已同步，专项不再需要
在两个 backend 各改一遍）

适用项目：Infinia / FengYu 4.1+，Spring Boot 后端（`FengYu` 模块）+ React 前端

编写时源码版本：`release/4.1.0` @ `8d3f2624` + 未提交的 codex 对齐批次
（ApplyPatch/PTY 会话/ToolBatchExecutor/DelegateTask/Review/rollout 等，见根 CHANGELOG 待整理项）。
codex 参照实现在 `/tmp/openai-codex`（openai/codex @ `c248f6d`，仓库 `codex-rs/`）；该检出在 /tmp，
**可能被清理——重启工作时若已丢失，`git clone https://github.com/openai/codex && git checkout c248f6d` 恢复**。

状态：独立专项（与 [[agent-code-mode]] 平行、无依赖）。本文是待执行任务书，不是已完成记录；
实施与发布时必须重新读取当时的真实源码。

## 1. 给实施 AI 的执行指令

只有用户明确要求启动本专项时才开始修改应用代码；生成本文的请求仅授权创建方案文档。
启动时按本文"阶段计划"从 S1 顺序执行，遵守：

1. 先读根 `AGENTS.md` 与 `git status`，保留用户已有修改（4.1.0 分支常有未提交批次）。
2. 本文的 codex 文件索引是"规格说明书"：移植前打开对应源码逐条核对，**不得凭印象自创**
   （用户对移植的硬性要求，历史返工均因第一版臆造被驳回）。
3. 类名/包名建议不高于实际源码；命名可局部自定，但不得静默删减必做验收项。
4. 不主动升级依赖；新依赖必须坐维护线（AGENTS.md「No EOL dependencies」——引入前查维护状态）。
5. 不自动提交/推送/打标签；新 APP-only REST 控制器必须登记 `SetupApplication` 排除清单
   （本专项预计无新控制器，配置走现有设置面——若最终新增，勿忘）。
6. 文档同步用 `docs-updater` 技能；每阶段最小验证 = 相关模块 `./mvnw -f FengYu/pom.xml test -Dtest=…`
   加针对性手工验证，不跑全反应堆"以防万一"。
7. 平台降级必须显式上报（沿用 `ProcessSandbox.Backend` 的诚实分级传统），禁止把降级报告成已沙箱。

## 2. 背景与差距定位

2026-09-29 的 codex 对比结论（记忆 `fengyu-codex-gap-analysis`）把「OS 级沙箱 + network-proxy +
execpolicy」列为第 ② 号差距。对 FengYu 现状审计后，差距精确化为——**不是"没有沙箱"，而是
"沙箱存在但没接到编码代理主执行面，且权限模型、规则引擎、网络策略三层全缺"**：

| # | 差距 | 现状（已核实） | codex 对应 |
|---|---|---|---|
| G1 | `workspace_exec` 裸跑 | `WorkspaceExecTool` 直接 `ProcessBuilder` 起 `/bin/sh -c`，仅 env 白名单 + cwd 监狱 + `READONLY_COMMANDS` 硬编码启发式（`FengYu/.../ai/tools/WorkspaceExecTool.java:151`） | 所有 exec 经 `SandboxManager::transform` |
| G2 | 权限档位缺失 | 只有"workdir 可写+网络开关"二元（`ProcessSandbox.command`） | `PermissionProfile`：read-only / workspace-write(writableRoots, network) / danger-full-access，`protocol/src/permissions.rs` + `config_toml.rs:818-883` |
| G3 | macOS 后端是"减敏"而非围栏 | allow-default + 显式 deny 凭据目录（`ProcessSandbox.java:295` 起）；理由是 JVM 无法在 deny-default 下启动——但 `workspace_exec` 子进程不是 JVM | Seatbelt 严格 SBPL：base policy + 读根/写根 allowlist + 网络 deny/仅 loopback 代理端口（`sandboxing/src/seatbelt.rs:882-1109` + `.sbpl` 模板） |
| G4 | 无用户可扩展规则引擎 | `READONLY_COMMANDS` 等四个硬编码集合 | Starlark `prefix_rule/network_rule/host_executable` + amend 写回（`execpolicy/`） |
| G5 | 网络全有或全无 | Linux `--unshare-net`（`ProcessSandbox.java:276`）或共享；无域名级策略、无代理、无 MITM | 嵌入式 MITM 代理 + 域名 allow/deny + env 注入契约（`network-proxy/`） |
| G6 | 无"沙箱拒绝→审批→逃逸重试"编排 | 审批门在执行前问一次，之后裸跑到底 | attempt loop：先沙箱尝试，`SandboxErr::Denied` → NeedsApproval → 逃逸重试（`core/src/tools/orchestrator.rs`） |
| G7 | Windows 无安全隔离 | Job Object 仅进程树生命周期 | restricted token / AppContainer / MXC（**本专项明确非目标**，见 §7） |

已有地基（直接复用，勿重建）：`fan.summer.fengyu.security.ProcessSandbox`
（bwrap/sandbox-exec/JobObject 三后端 + `Backend.providesSecurityIsolation()` 诚实分级 +
`isNativeSandboxAvailable()`），已被全局 `CommandExecuteTool` 与插件 worker 使用；
`ChatToolApprovalGate`（审批批处理，拒绝不终止轮次）、`ToolGuardService`（hooks+权限规则）、
`ToolApprovalPolicy`、`ToolEffect{READ,WRITE,COMMAND,EXTERNAL}`、`ToolBatchExecutor`（READ 并发/WRITE 屏障）、
`ToolLoopDriver`（轮次编排单点：prompt 组装、动态工具加载、审批门调用、批执行调用、rollout
记录、mid-turn 压缩、取消——`SpringAiCloudBackend`/`OllamaLocalBackend` 已缩为只供模型差异的
Transport；loop 级改动只碰这一处）、`RolloutEvent`（rollout 日志的类型化 sealed 事件模型，
`Recorder.append` 单一写路径；未知事件类型回落 `Unknown` 跳过，专项的审计字段在此扩展）、
`WorkspacePathPolicy`（Java 侧文件工具路径监狱）、`WorkspaceExecSessions`（交互会话）。

**目标范式转移**：从"白名单只读→自动放行，其余→问用户然后裸跑"翻转为 codex 的
"默认进沙箱自动跑，审批只为逃逸沙箱"——既降审批疲劳，又给已批准命令真实边界。

## 3. codex 参照实现索引（规格来源）

> 路径相对 `/tmp/openai-codex/codex-rs/`。下表是工作地图；实施某阶段前重读对应源码与其测试。

### 3.1 execpolicy/（规则引擎，S5 参照）

- `execpolicy/Cargo.toml` — 用 Google starlark-rust 0.14.2（**Java 侧不引 Starlark，见 §4 决策 D4**）。
- `execpolicy/src/parser.rs:347-473` — 三个全局：`prefix_rule(pattern, decision, match, not_match,
  justification)`（pattern=token 序列，内层 list=备选；decision∈allow/prompt/forbidden）；
  `network_rule(host, protocol, decision)`（protocol∈http/https/socks5_tcp/socks5_udp，host 必须具体）；
  `host_executable(name, paths)`（约束 basename 回退解析）。
- `execpolicy/src/policy.rs` / `rule.rs` / `decision.rs` — `Policy` 匹配、`Decision{Allow,Prompt,Forbidden}`
  最严者胜、`RuleMatch`。
- `execpolicy/src/amend.rs` — "always allow" 追加生成的规则行到 `~/.codex/rules/default.rules`（带锁）。
- `core/src/exec_policy.rs` — 宿主侧：规则加载（各配置层 `rules/*.rules` 排序合并）、
  `create_exec_approval_requirement_for_parsed_commands`（先 `bash -lc` 降级为多条纯命令再逐条匹配）、
  未命中走 `heuristics_fallback`（危险命令表→Prompt）、
  全部显式 allow → `bypass_sandbox=true`、修订建议跳过 `BANNED_PREFIX_SUGGESTIONS`（shell/解释器/sudo 等，
  `exec_policy.rs:57-146`）。
- 测试规格：`execpolicy/tests/basic.rs`（963 行）、`core/src/exec_policy_tests.rs`。

### 3.2 平台沙箱（S2/S3 参照）

- **macOS**：`sandboxing/src/seatbelt.rs`（1125 行）+ `seatbelt_base_policy.sbpl` / 
  `seatbelt_read_only_platform_defaults.sbpl` / `seatbelt_network_policy.sbpl`。
  执行器硬编码 `/usr/bin/sandbox-exec`（防 PATH 注入，`:62`）；
  `create_seatbelt_command_args_with_profile`（`:882-1109`）拼装：base + 读根 `file-read* subpath` +
  写根 `file-write*`（根目录追加 `deny file-write-unlink` 防搬移边界）+ `require-not` 排除只读子路径 +
  `.git`/`.codex` 等元数据正则 deny + `(deny mach-lookup (xpc-service-name-prefix ""))` +
  `(deny system-fcntl (fcntl-command 80 110))` + glob deny + 祖先改名 deny；
  通过 `-DREADABLE_ROOT_i=…` 传参。网络：有代理端点→仅 `network-outbound localhost:<port>`+DNS:53
  （`:319-384`），配置存在但端点不可用→**fail-closed 空策略**；全网络→allow + mach-lookup。
  符号链接可写根默认拒绝（`:440-488`）。测试：`sandboxing/src/seatbelt_tests.rs`（3083 行，挑核心子集镜像）。
- **Linux**：`linux-sandbox/`（helper 二进制）+ `sandboxing/src/landlock.rs:26-67`（argv 构造）+ 
  `sandboxing/src/bwrap.rs`（系统 bwrap 探测：PATH 但不在 cwd 下、`--argv0/--ro-bind-fd` 支持探测、
  用户命名空间 500ms 探测）。bwrap 参数（`linux-sandbox/src/bwrap.rs` / `linux_run_main.rs:167-361`）：
  `--ro-bind / /` + 写根 `--bind` + `.git`/`.codex` carve-out 重 ro-bind + `--dev /dev` + `--proc /proc` +
  `--unshare-user` 恒定 + `--unshare-pid` + `--unshare-ipc` + 断网时 `--unshare-net` + `--cap-drop ALL` +
  `--new-session` + `--die-with-parent`；WSL 掩蔽。seccomp（`--apply-seccomp-then-exec`，
  `linux-sandbox/src/landlock.rs:179-300`）：PR_SET_NO_NEW_PRIVS + deny connect/accept/bind/listen/ptrace/
  process_vm_*/io_uring_*，socket 仅 AF_UNIX——**V1 不做（D5 决策），记为已知差距**。
  测试：`linux-sandbox/tests/`（all.rs + suite/）。

### 3.3 network-proxy/（S6+ 参照）

- `network-proxy/src/proxy.rs` — 进程内嵌入式（非守护进程），默认 `127.0.0.1:3128` HTTP / `:8081` SOCKS5；
  `apply_proxy_env_overrides`（`:769-845`）改写子进程 env：`HTTP(S)_PROXY`/`YARN_*`/`NPM_CONFIG_*`/
  `PIP_PROXY`/`ALL_PROXY=socks5h://…` 等 → 本地代理；`ManagedNetworkSandboxContext`（`:486`）= 传给 OS
  沙箱的「仅放行 loopback:port」投影。
- `network-proxy/src/certs.rs` — 托管 CA（ECDSA P-256，CN `network_proxy MITM CA`）：私钥**只留内存**，
  磁盘仅证书+不可变信任束；11 个 CA env key（`SSL_CERT_FILE`/`REQUESTS_CA_BUNDLE`/
  `NODE_EXTRA_CA_CERTS`/`GIT_SSL_CAINFO`…，`:260`）。
- `network-proxy/src/config.rs` / `network_policy.rs` — 域名映射（精确/`*.sub`/`**.apex`/`?`；全局 `*` 拒绝）；
  **allowlist 优先：无 allow 项即全断**；deny 恒胜；私网 IP 默认断；Limited 模式仅 GET/HEAD/OPTIONS；
  拒绝→403 + `x-proxy-error` 归因头。
- 测试：`network-proxy/src/*_tests.rs` + `proxy/managed_routing_tests.rs`。

### 3.4 core 编排（S4 参照）

- `core/src/tools/orchestrator.rs`（556 行）— attempt loop：沙箱首尝试 → `SandboxErr::Denied` →
  审批（含修订建议）→ 逃逸重试；拒绝读策略永不放行逃逸。
- `core/src/tools/sandboxing.rs` — `ExecApprovalRequirement{Forbidden,NeedsApproval,Skip{bypass_sandbox}}`、
  `Sandboxable`/`ToolRuntime`/`Approvable`。
- `sandboxing/src/manager.rs:352-562` — `SandboxManager::transform` 唯一 choke point（argv 变换+env 准备+
  CA 可读根注入）；`sandboxing/src/violation.rs`/`denial.rs` — 违规审计 + `is_likely_sandbox_denied`
  （exit code 启发式：`128+SIGSYS`、stderr 关键词）。
- `core/src/exec.rs` / `core/src/sandboxing/mod.rs` — spawn 与 `CODEX_SANDBOX`/`CODEX_SANDBOX_NETWORK_DISABLED` env。

## 4. 设计方案

新包 `fan.summer.fengyu.ai.sandbox`（与 `security/` 的关系：`ProcessSandbox` 是平台后端细节的既有
封装，保留；新包放编码代理的权限模型/规则/编排，向下调 `ProcessSandbox` 或新增后端方法）。

### 4.1 核心类型

```java
// 权限档位（镜像 codex PermissionProfile，sealed）
sealed interface PermissionProfile {
    ReadOnly(Path root);                       // 只读： inspections 免审批自动跑
    WorkspaceWrite(Path root, List<Path> writableRoots,
                   boolean networkAccess);      // 工作区写：项目工作免审批
    FullAccess();                              // 显式全通（= 现状）
}
```

- `AgentSandboxManager`：`Launch plan(String command, PermissionProfile, Path cwd)` 与
  `void transform(ProcessBuilder builder, PermissionProfile)` —— 全部 exec 的唯一咽喉
  （对应 `SandboxManager::transform`）。macOS/Linux 在此拼 SBPL/bwrap argv；平台无后端时返回
  降级标记（沿用 `Backend.NONE` 语义并上报 UI/审计）。
- `ExecPolicyRules` + `ExecPolicyEngine`：规则加载/匹配/修订（S5）。规则文件
  `~/.fengyu/ai/rules/*.json`，条目语义=codex prefix_rule（见 §3.1）：`{"pattern": ["git", ["status",
  "diff"]], "decision": "allow"}`。**决策 D4：不引 Starlark**——Maven 生态无可维护的 Starlark 实现，
  JSON 规则语义等价（token 序列/备选/最严者胜/amend 追加），语言只是壳。内置默认规则集由现有
  `READONLY_COMMANDS`/`GIT_READONLY_SUBCOMMANDS`/`FIND_MUTATING_FLAGS` 等迁移生成，成为第一条
  内置规则而非硬编码；启发式 fallback（未命中→危险命令表→Prompt）保留为最后一级。
- `SandboxDenialDetector`：exit code/SIGSYS/stderr 关键词启发式（镜像 `denial.rs`），供编排流判定。
- 编排流（S4）：在 `WorkspaceExecTool` 执行路径外包 attempt loop——
  readonly 判定→`ReadOnly` 档自动跑；写命令→`WorkspaceWrite` 档自动跑；检测到沙箱拒绝且
  规则/审批策略允许→`ChatToolApprovalGate` 发**逃逸审批**（新审批语义："在沙箱外运行"，
  复用 `ApprovalRequiredTool` 机制+前端卡新增按钮）→ 批准后 `FullAccess` 重试一次；拒绝→把拒绝
  反馈合成为工具结果返回模型（沿用审批门"拒绝不终止轮次"的既有语义）。

### 4.2 平台后端

- **Linux（S2）**：升级 `ProcessSandbox` 的 bwrap 路径为 codex 模型：`--ro-bind / /` 全根只读
  （现有 minimal view 是为 JVM worker 设计的——`workspace_exec` 跑用户工具链需要宽读面）+
  写根 `--bind` + `.git`/`.fengyu` ro 重保护 + `--unshare-net`（networkAccess=false 时）+
  `--cap-drop ALL --new-session --die-with-parent --unshare-user --unshare-pid --unshare-ipc`。
  系统无 bwrap→降级 `Backend.NONE` 显式上报（不自动下载，与 codex 打包分发策略不同——桌面发行
  可评估后续随安装包分发，记入 S2 开放问题）。
- **macOS（S3）**：新增严格 SBPL 生成器（照 `seatbelt.rs` 移植，见 §3.2 清单），仅在
  `workspace_exec`/编码 exec 链路启用；现有插件 worker 的减敏 profile 不动（它们的约束是 JVM
  启动，仍然成立）。网络默认 `(deny network*)`。已知风险：Apple 标注 sandbox-exec deprecated，
  codex HEAD 仍在使用——接受并记录；替代（Endpoint Security）需原生代码，非本专项范围。
- **Windows**：保持 Job Object 生命周期隔离 + 审批兜底，UI 如实显示"无安全沙箱"（沿用现状）。
- seccomp/Landlock syscall 过滤：**D5 决策 V1 不做**——bwrap 的 mount/netns/cap-drop 已覆盖文件与
  网络边界；syscall 过滤是纵深防御，列为 S7 候选（可为 shaded JAR 附带小体积 seccomp helper 或
  评估 `bwrap --seccomp <fd>`）。

### 4.3 网络（S6 起，可选排期）

V1 网络= 断（`--unshare-net`/`(deny network*)`）或全通，无中间态。S6 引入
`AgentNetworkProxy`（Spring 内嵌 loopback HTTP CONNECT 代理）：域名 allow/deny（语义照 §3.3：
allowlist 优先、deny 恒胜、私网默认断、403+归因头）；`WorkspaceExecTool` 的 env 白名单位点
（现透传宿主 `http_proxy`）改为注入代理 env（key 集照抄 codex `apply_proxy_env_overrides` 清单）。
**MITM CA/HTTPS 内容级策略与证书虚拟化为 S7+，默认不做**（复杂度/信任成本高；CONNECT 目标级
域名过滤已覆盖主要诉求）。规则引擎的 `network_rule` 在 S6 一并接通（V1 规则里 protocol 字段先
忽略）。

### 4.4 配置与 UI

- H2 设置键（经 `AiConfigService`，模式同现有 `ai.*`）：
  `ai.sandbox.mode ∈ {off, read-only, workspace-write}`（**默认 off=现状不变**，稳定后再翻默认值——
  单独变更评审）、`ai.sandbox.extra-writable-roots`（逗号分隔绝对路径）、
  `ai.sandbox.network ∈ {denied, open}`（S6 后加 `proxy`）。会话级：审批卡"沙箱外运行"授予 =
  单次逃逸（会话级 always-escape 不做，比 codex 保守）。
- rollout：扩展 `RolloutEvent.ToolResultRecorded`（sealed 事件模型是唯一写路径）增
  `sandbox{backend, profile, escaped, degraded}` 字段——记录调用点在 `ToolLoopDriver`
  的轮次编排里，tool_result 事件随批执行结果一起落盘（审计可见）。
- 前端：设置页沙箱段（含平台支持状态徽标：Linux 完整/macOS 围栏/Windows 仅生命周期）+
  审批卡新"沙箱外运行"动作 + 转录里命令结果标 sandbox 档位。

### 4.5 接入点清单（改哪里）

| 位置 | 改动 |
|---|---|
| `ai/tools/WorkspaceExecTool.workspaceExec` | builder 构建后过 `AgentSandboxManager.transform`；readonly 判定改走规则引擎；attempt loop 包执行段 |
| `ai/service/ToolLoopDriver` | 轮次编排单点：rollout 的 tool_result 事件在此记录，sandbox 审计字段随此附加（§4.4）；审批门与批执行的调用编排也在此——loop 级改动不再涉及两个 backend |
| `ai/tools/WorkspaceExecSessions` / `startInteractive` | 同样过 transform（交互会话也必须沙箱内） |
| `ai/tools/ChatToolApprovalGate` | 新增逃逸审批请求类型（携带命令+拒绝原因+建议规则） |
| `ai/tools/DelegateTaskTool`/`WorktreeIsolation`/`CloudSubagentRunner` | 子代理 exec 透传同一 profile（默认继承工作区档位） |
| `security/ProcessSandbox` | Linux 全根视图新方法；macOS 严格 SBPL 生成器（新类 `StrictSeatbeltProfile`，不碰 worker 减敏路径） |
| `ai/config/*` | 设置键读写 + `AiToolRegistry` 不变（沙箱不是工具） |
| 前端 `services`/设置页/ChatTranscript | §4.4 |

## 5. 阶段计划与验收

每阶段独立可验证、可停；顺序执行（S6/S7 可选）。

**S1 权限模型与咽喉骨架**
交付：`PermissionProfile` + `AgentSandboxManager`（transform 目前仅 env/审计透传）+ 设置键（默认 off）
+ 纯函数单测（profile→计划）。
验收：off 模式下全部现有行为与测试不变（`WorkspaceExecTool`/`ChatToolApprovalGateTest` 等回归绿）；
on 模式下 `workspace_exec` 结果 JSON 新增 `sandbox` 字段（含 backend/profile/降级标记）。

**S2 Linux bwrap 升级 + workspace_exec 接入**
交付：全根 RO 视图+写根 bind+元数据保护+netns 隔离；`WorkspaceExecTool`/`startInteractive` 接入。
验收（真实 bwrap 集成测试，容器/CI Linux 必跑，无 bwrap 环境跳过并记录）：沙箱内写工作区成功、
写 `/tmp` 外被 EROFS/EACCES 拒、断网档 `curl` 失败、`.git` 只读、无 bwrap 时结果如实标注降级。
镜像测试参照：`linux-sandbox/tests/suite/*` 的断言子集。

**S3 macOS 严格 SBPL**
交付：`StrictSeatbeltProfile`（base+读根/写根+网络 deny+元数据/mach-lookup/system-fcntl deny+
`-D` 参数化，清单照 §3.2）+ 集成测试（本机 macOS 跑）。
验收：写根外写被拒（exit≠0 且被 `SandboxDenialDetector` 识别）、断网档出网被拒、
`--output /etc/x` 类逃逸被拒、workspace 项目内 mvn/测试可正常只读运行；SBPL 生成器单测快照
（镜像 `seatbelt_tests.rs` 的 profile 文本断言）。

**S4 拒绝检测 + 逃逸编排 + 前端**
交付：`SandboxDenialDetector` + attempt loop + 审批卡"沙箱外运行" + rollout 字段。
验收：端到端——read-only 档 `git status` 免审批；`rm 工作区外文件` 被沙箱拒→审批卡出现→
批准后 FullAccess 重试成功、拒绝后模型收到合成工具结果继续轮次；全链路 rollout 事件完整。

**S5 规则引擎**
交付：JSON 规则加载/合并（`~/.fengyu/ai/rules/*.json` 按名排序）+ 匹配（最严者胜）+
内置默认规则集（自现有硬编码迁移）+ amend（审批卡"总是允许"写回规则文件，跳过
BANNED 前缀建议清单——照 `exec_policy.rs:57-146` 迁移危险表）+ heuristics fallback 保留。
验收：语义子集单测镜像 `execpolicy/tests/basic.rs`（token 备选、未命中 fallback、最严者胜、
amend 幂等）；迁移后 `WorkspaceExecToolTest` 既有 readonly 断言全绿（行为等价）。

**S6（可选）网络代理**：`AgentNetworkProxy` + env 注入 + 域名规则；验收=403 归因头/allowlist 优先/
私网默认断（镜像 `network_policy` 语义测试）。
**S7（候选）**：seccomp helper、MITM CA、Windows restricted token（各自单独评审，本文不承诺）。

最终验收（专项收官）：开启 `workspace-write` 后，编码代理全流程（含交互会话与子代理）无需逐条
审批即可完成项目工作，且任何 OS 级越界（写根外、断网档出网、`.git` 破坏）被硬性拒绝并进入
逃逸审批；Windows 用户全程看到真实降级状态。文档经 `docs-updater` 同步（README 安全节 +
docs/en|zh + CHANGELOG）。

## 6. 风险与开放问题

- sandbox-exec 弃用风险（§4.2）；bwrap 不在用户机上的分发策略（S2 开放问题）；
  GraalJS/依赖无关本专项；`--ro-bind / /` 与符号链接宿主目录的边界（codex `allow_symlinked_codex_home`
  的教训：写根为符号链接时默认拒）。
- seccomp 缺位（D5）在威胁模型上弱于 codex——如实记录于安全文档，不粉饰。
- 与 `CommandExecuteTool`（全局工具）的统一：S2 完成后评估是否把全局 exec 也切到
  `AgentSandboxManager`（本文不强制，避免动插件链路）。

## 7. 明确非目标

Windows 安全沙箱（restricted token/AppContainer——codex 侧最大后端，需原生代码，远期另立）；
MITM 内容级策略与凭据虚拟化的默认启用；Landlock；跨机/容器分发。
