# FengYu 4.0.0 Beta 发布前代码与设计审查

**审查日期：** 2026-08-08  
**审查基线：** 分支 `4.0.0`，提交 `f1016b0`，并包含审查时工作区内尚未提交的 17 个文件修改  
**审查范围：** 宿主后端、Vue 宿主前端、Electron 壳、宿主—插件运行时、官方插件、数据库隔离、日志链路、进程沙箱、浏览器自动化与发布流水线  
**交付约束：** 本次仅生成本报告；没有修改任何宿主、插件、工具链或发布代码。

## 1. 结论

**当前版本不应发布公开 Beta。** 可以作为内部修复候选继续迭代，但必须先关闭本文 P0 项并让发布门禁全部通过。

主要原因不是代码风格，而是插件信任边界尚未成立：Worker 继承宿主全部环境变量、POSIX 沙箱可读整个根文件系统、插件安装目录可写，Windows Job Object 又被误当成文件/网络沙箱。任意第三方插件因此可能读取宿主密钥、改写自己的 manifest 后提升权限，并在具有网络权限时外传数据。与此同时，宿主后端测试当前失败，SETUP 模式上下文不能干净启动。

| 结论维度 | 结果 | 说明 |
|---|---|---|
| 宿主—插件日志 | **不通过** | 主链路结构良好，但 SDK DEBUG 日志会记录参数值，异步任务仍有栈丢失点 |
| 数据库与数据交互 | **不通过** | 按插件凭据/文件隔离方向正确，但卸载、失败恢复、数据保留行为有实质缺陷 |
| 宿主整体质量 | **不通过** | 前端/桌面验证通过；后端 345 项中 2 失败、1 错误，且存在 P0 安全与生命周期问题 |
| 跨平台/无沙箱运行 | **不通过** | 有显式无沙箱开关，但安全等级建模错误，Windows 实际无文件/网络隔离 |
| Codex 风格浏览器能力 | **尚未实现于宿主** | 当前是插件内九个 CSS/Playwright 工具；能力、审计与安全模型不足 |

## 2. P0：Beta 发布阻断项

### P0-1 Worker 继承宿主全部环境变量，插件可直接取得宿主秘密

**证据：** `PluginProcessManager.start()` 创建 `ProcessBuilder` 后直接追加插件环境，没有清空或白名单化继承环境（`FengYu/.../plugin/runtime/PluginProcessManager.java:207-220`）。相反，内置命令工具明确调用 `removeSensitiveEnvironment()`（`CommandExecuteTool.java:93-97,173-184`），说明项目已经知道环境变量泄密风险，但插件路径没有采用同一策略。

**影响：** Worker 可读取 `OPENAI_API_KEY`、`ANTHROPIC_API_KEY`、`GH_TOKEN`、代理凭据、CI 密钥及任意宿主启动环境。`SensitiveValueRedactor` 只从宿主主动注入的插件环境构建词表，既不能阻止读取，也无法覆盖继承环境中的秘密。

**必须修复：** 启动 Worker 前执行 `builder.environment().clear()`，再按平台白名单恢复运行 Java/本地化所需变量（如 `PATH`、`JAVA_HOME`、临时目录、语言/编码）以及 `FENGYU_*` 协议变量。不得用“删除名称含 TOKEN/SECRET 的变量”替代正向白名单。

### P0-2 POSIX 沙箱允许读取整个主机，并允许插件改写自身安装目录

**证据：** Linux 使用 `--ro-bind / /`（`ProcessSandbox.java:151-175`）；macOS profile 从 `(allow default)` 开始，仅限制网络与写入（`:179-193`）。`PluginProcessManager` 又无条件把插件包根目录加入 `writableRoots`（`PluginProcessManager.java:193-203`）。

**影响：** `files.read` 权限没有实际保密意义；插件可读取用户主目录、SSH 配置、FengYu 配置和其他只读文件。更严重的是，插件可改写自己的 `manifest.json`，声明新的 `network`/`database`/AI tool 权限，主动退出后由宿主按被篡改的新 manifest 重启，形成自我提权。具有网络权限的插件可把读取到的数据直接外传。

**必须修复：** 插件包目录始终只读；只有独立 `plugin-data/<id>`、专属临时目录和经授权的写 FileRef 可写。Linux 不应只读挂载整个 `/`，应建立运行时最小只读视图；macOS 应改为 deny-default/显式 allow。安装后 manifest 必须按安装记录中的摘要复验，运行时不得信任可变文件。

### P0-3 Windows Job Object 被误标为安全沙箱

**证据：** `WINDOWS_JOB` 使 `Launch.sandboxed()` 返回 true（`ProcessSandbox.java:26-55`），`isNativeSandboxAvailable()` 也返回 true；但 `WindowsJobSandbox` 自己明确说明它“不是文件系统/网络沙箱”，只管理进程树生命周期（`WindowsJobSandbox.java:14-25`）。`ProcessSandbox.wrap()` 的 Windows 分支没有应用文件或网络策略（`ProcessSandbox.java:135-149`）。

**影响：** Windows 上 `files.*` 与 `network` manifest 权限无法由 OS 强制执行，UI 却把它描述为原生沙箱；设置页还禁止用户选择无沙箱兼容模式，因为 Job Object 被判断为“native sandbox”。这是安全状态误报，不只是文档问题。

**必须修复：** 将“进程树约束”和“文件/网络安全隔离”拆成两个能力维度。Windows 至少实现 AppContainer/受限令牌 + ACL + 网络 capability；在此之前必须报告 `securityIsolation=none`、`lifecycleIsolation=job-object`，并按无安全沙箱策略处理。

### P0-4 AI `FULL_ACCESS` 与插件沙箱错误耦合

**证据：** `PluginProcessManager.invoke()` 在 AI 上下文为 `FULL_ACCESS` 时直接选择 `sandbox.unrestricted()`（`PluginProcessManager.java:106-113,204-206`）。

**影响：** 用户给 AI 命令/文件的“完全访问”会同时让所有被调用插件裸跑，绕过插件自身声明的权限与平台隔离。该授权语义过宽，且裸跑 Worker 会被缓存并与其他并发调用发生关闭/重启竞争。

**必须修复：** AI 工具授权和插件进程隔离必须独立。`FULL_ACCESS` 最多批准某次工具的外部效果，不得关闭插件 OS 边界。无沙箱启动只能通过独立、按插件、显示摘要/签名的信任授权。

### P0-5 JSON-RPC 成功调用永久泄漏 `pending` 项

**证据：** `Worker.invoke()` 在调用前 `pending.put(id, future)`，所有异常分支会 remove，但成功返回前没有 remove；reader 也只 `pending.get(responseId)`（`PluginProcessManager.java:425-438,449-485`）。

**影响：** 每次成功插件调用永久保留 UUID、Future 和结果对象；长时间运行后可能 OOM。浏览器/Agent 等高频工具调用会放大该问题。

**必须修复：** reader 用原子 `pending.remove(responseId)` 取得槽位，或在 invoke 的 `finally` 中条件删除；补充连续十万次成功响应后 map 归零的回归测试。

### P0-6 插件升级不会停止旧 Worker，新版本可能不生效

**证据：** enable=false 与 uninstall 会先 `processes.stop(id)`，但 upload/install/update 直接替换包目录（`PluginMarketplaceController.java:56-79`）；Worker 缓存条件只比较存活、FileRef 版本和 fullAccess，不比较插件版本或包摘要（`PluginProcessManager.java:109-114`）。

**影响：** 更新后仍运行旧代码直到进程偶然重启；Windows 还可能因运行中的 JAR 被占用而无法原子移动/删除。数据库迁移和 UI/Worker 版本也可能短暂错配。

**必须修复：** 更新事务顺序应为：禁用新调用 → 等待/取消在途调用 → 停 Worker → 校验并原子换包 → 失效 manifest/tool cache → 启用。Worker identity 必须包含包摘要/版本。

### P0-7 当前宿主后端测试不通过，SETUP 模式无法干净启动

执行 `./mvnw -f FengYu/pom.xml test`：**345 tests，2 failures，1 error，2 skipped**。

- `SetupApplicationContextTest`：`SetupApplication` 未排除新增的 `PluginDbController`，SETUP 模式缺少 `PluginPackageService`，上下文启动失败。证据：`SetupApplication.java:58-69` 的排除清单没有该 controller。
- `DbDialectStatementsTest`：PostgreSQL DDL 已改为 `DO $$ ... DUPLICATE_OBJECT`，测试仍期待旧 `CREATE ROLE`；这是当前未提交修改与测试不同步。
- `ConnectionTesterTest.testCloud_anthropic_hitsV1Messages`：本地 `/v1/messages` 探测断言失败，需要确认 URL normalize 或 JDK 21/25 行为，不可忽略。

发布 workflow 会运行 reactor test，因此当前状态本身无法形成合格发布构建。

### P0-8 官方插件身份可由上传包自行声明

**证据：** 安装校验只要求 `official=true` 时 id 以 `fan.summer.` 开头（`PluginPackageService.java:263-276`），没有签名、内置公钥或官方摘要校验；上传/更新路径允许覆盖同 id 包。

**影响：** 任意包可以伪装成官方插件，甚至替换 `fan.summer.browser`，UI 的 OFFICIAL 标记不代表真实性。

**必须修复：** 保留 `fan.summer.*` 命名空间；官方包须验证签名/发布公钥和包摘要。手工上传不得设置 official，也不得覆盖官方 id，除非进入明确的开发者模式。

### P0-9 未签名桌面包仍启用自动更新

**证据：** 发布 workflow 明确产出 unsigned Alpha；`checkForUpdates()` 仍可下载并 `quitAndInstall()`（`desktop/electron/src/updater/auto-updater.ts:7-31`）。JRE 版被跳过，但 lite 版仍启用。

**影响：** 更新链安全依赖 GitHub 发布账户和 feed，缺少 OS 代码签名/公证提供的发布者验证。公开 Beta 不应自动安装未签名二进制。

**必须修复：** 完成 Windows 签名、macOS Developer ID + notarization 后再开启自动更新；签名前只提示并打开人工下载页，不调用安装器。

## 3. P1：高优先级缺陷与设计不一致

### P1-1 SDK DEBUG 日志泄露 RPC 参数值

`PluginHandlerSupport.handle()` 通过 `abbreviateParams()` 对每个值调用 `toString()` 并写 DEBUG 日志（`toolchain/sdk-java/.../PluginHandlerSupport.java:43-52,110-125`）。这会记录邮箱密码、正文、路径和令牌，违反宿主“只记录参数键”的策略。环境 redactor 无法识别由请求携带的新秘密。

**修复：** 只记录 method、参数键、request id、耗时与结果状态；禁止记录值。异常日志保留 throwable，但共享日志中的 message 应使用稳定错误码/异常类型，业务敏感详情只进入受控诊断通道。

### P1-2 异步 Jobs 的失败栈仍会丢失，且单 Job 日志无界

Excel 已采用 try/catch-log-rethrow；Email `collectStart`、Offline Python `buildStart/deployStart` 没有。`Jobs.start()` 最终仅把异常压成一行 message。`Job.logs` 是无界 `ConcurrentLinkedQueue`，虽然 Job 数量与 TTL 有界，单个长任务仍可耗尽内存。

**修复：** 所有异步 body 统一 catch + SLF4J throwable + rethrow；SDK 提供统一 wrapper；Job 日志使用按行数和总字节双重有界 ring buffer，并返回 dropped count。

### P1-3 数据库 deprovision 失败后仍删除唯一重试记录

`PluginDbProvisioner.deprovision()` 捕获 DDL 错误并记录“left for retry”，随后无条件 `store.remove(pluginId)`（`PluginDbProvisioner.java:112-129`）。之后已无 schema/user/密码信息可重试，数据库里留下孤儿账户与数据。

**修复：** 用 `ACTIVE / DELETE_PENDING / DELETED` 状态持久化；DDL 失败保留记录并后台重试。卸载 UI 应区分“插件已移除”和“数据库清理待处理”。

### P1-4 卸载不删除 `plugin-data/<id>`，与文档和用户预期不一致

`PluginPackageService.uninstall()` 只删除 `.fengyu/plugins/<id>`；运行数据实际位于 `.fengyu/plugin-data/<id>`（`RuntimePaths.java:42-48`）。SQLite 数据库、浏览器 profile/cookie、截图、邮件密钥等都会保留。当前数据库文档却称 SQLite 文件会随卸载清理。

**修复：** 卸载对话框提供“保留数据/彻底删除”选择，默认行为必须清晰；彻底删除时在 Worker 停止、DB deprovision 成功或进入 DELETE_PENDING 后安全删除数据目录。禁止静默声称已清理。

### P1-5 Provisioning 非原子，部分 DDL 会造成不可恢复状态

DDL 逐条执行且未显式事务；数据库对象创建成功但 store 写入失败时会遗留账户。下一次生成新密码，而 `CREATE USER IF NOT EXISTS`/PostgreSQL duplicate-role 分支不会更新已有账户密码，最终存储凭据可能无法登录。

**修复：** 设计可恢复状态机；对已存在用户显式 `ALTER USER/ROLE ... PASSWORD`；DDL 与本地记录采用补偿事务，并对 MySQL/PostgreSQL/H2 做真实容器集成测试，而非只测字符串。

### P1-6 JSON-RPC 帧无大小上限

宿主 stdout/stderr reader、SDK `StdioTransport` 与 devkit 均直接 `readLine()`。恶意/失控 Worker 可输出超大单行导致宿主内存压力。

**修复：** 协议两端实施 UTF-8 字节上限（建议默认 16 MiB，可配置且有硬上限）；超限立即关闭 Worker，失败所有 pending，并记录结构化原因。stderr 也需要单行/速率限制。

### P1-7 Worker 启动后 Windows Job 分配失败会遗留裸进程

进程先 `builder.start()`，再调用 `launch.onStarted()`；hook 抛异常时 `start()` 只捕获 IOException，没有销毁已启动进程。命令工具路径也有同样窗口。

**修复：** 对 hook 全异常包围，失败时立即 destroyForcibly + 关闭 job handle，再向上抛出；增加 assign failure 测试并断言 PID 消失。

### P1-8 日志 SSE 的 replay/live 切换存在竞态

controller 先订阅、启动独立 drainer，再读取历史并更新普通 `long[]` high-water。新日志可能在历史 replay 前被 drainer 发送，造成乱序或重复。注释描述的顺序保证并未由同一锁/队列实现。

**修复：** 在 store 内提供原子的 `subscribeWithSnapshot()`，返回 snapshot high-water，并在 subscriber 队列中从该序号后开始；不要由 controller 跨线程拼接一致性。

### P1-9 权限名称与实际强制点不一致

`clipboard.*`、`notifications` 目前没有宿主 capability 实现；POSIX 下 `files.read` 不控制读取；`network.email` 与 `database` 都被转换为完整网络访问。AI tool 的 `effect` 只控制审批，不约束 Worker 内行为。

**修复：** manifest 权限必须映射到可验证的宿主 capability 或 OS policy。不能强制的权限应标记为 advisory 并从“安全隔离”文案中移除；`network.email` 应由宿主代理 SMTP/IMAP 或使用目的地址策略，而不是开放所有网络。

## 4. 日志、数据库与数据交互的正向评价

以下设计值得保留：

- stdout 专用于 newline-delimited JSON-RPC，SDK 将普通输出导向 stderr；宿主能识别非 JSON stdout。
- Worker 的结构化 `@fengyu-log:` 帧保留 level/logger/thread/throwable，宿主按插件写日志并向 REST/SSE 暴露最近历史。
- `PluginLogStore` 的历史、subscriber queue 与慢订阅者均有容量限制，stderr drain 不会被慢 SSE 客户端阻塞。
- 宿主调用日志目前只记录参数键，不记录值；数据库环境秘密会在 Worker stderr 与 RPC error 边界脱敏。
- server DB 使用按插件用户/schema/database，宿主运行凭据与 admin provisioning 凭据不直接注入 Worker；embedded DB 使用独立插件文件，避免宿主文件锁冲突。
- FileRef 使用 opaque id 并按 plugin id 校验；上传目录有遍历、文件数与大小限制；只读 native 输入会 snapshot 且拒绝符号链接。
- iframe UI 使用独立 loopback origin、严格 CSP 与 `postMessage` 的 source/origin 双校验；方向正确，应保留并补充自定义 hostname 的 fail-closed 测试。

## 5. 不同平台及无沙箱场景

### 5.1 当前行为矩阵

| 平台/条件 | 当前行为 | 实际保护 | 结论 |
|---|---|---|---|
| Linux + bwrap | Worker 可启动 | 写/网络部分限制；全盘可读；插件包可写 | 不满足权限隔离 |
| Linux 无 bwrap/受 AppArmor 阻止 | 默认拒绝 Worker；全局设置开启后裸跑 | 无 | 兼容路径存在，但授权粒度过粗 |
| macOS + sandbox-exec | Worker 可启动 | deny write/network 的增量 profile；全盘可读；插件包可写 | 不满足最小权限 |
| macOS 无 sandbox-exec | 默认拒绝；设置开启后裸跑 | 无 | 同上 |
| Windows + JNA Job Object | Worker 可启动 | 仅进程树回收；文件/网络完全开放 | 被错误报告为安全沙箱 |
| Windows Job 初始化失败 | 默认拒绝；设置开启后裸跑 | 无 | hook 失败还可能遗留进程 |

### 5.2 建议的宿主隔离模型

不要再使用一个 `sandboxed:boolean` 表示所有能力。至少暴露：

```text
lifecycleIsolation = none | process-group | job-object
filesystemIsolation = none | allowlist-ro-rw
networkIsolation = none | denied | brokered | unrestricted
credentialIsolation = none | environment-allowlist
effectiveLevel = strict | reduced | unrestricted
```

无安全沙箱平台的兼容方案应满足：

1. 默认 fail closed；用户可按插件包摘要授权 `reduced/unrestricted`，不能用全局永久开关覆盖所有插件。
2. 授权页面显示发布者、签名、请求权限、缺失的隔离维度和数据目录；插件更新后摘要变化，授权自动失效。
3. 即使裸跑也清空继承环境、固定工作目录、使用专属临时目录、保持 FileRef 与数据库最小凭据、执行协议限流和进程树回收。
4. Windows 优先实现 AppContainer/受限令牌；不能实现前，明确标为 unrestricted，不以 Job Object 冒充 sandbox。
5. 发布流水线增加 Linux bwrap、macOS sandbox-exec、Windows Job/AppContainer、强制 NONE 四类测试。每类验证读/写/网络/环境变量/子进程五个维度。

## 6. 宿主原生 Codex 风格浏览器操作能力设计

### 6.1 当前能力差距

当前 `fan.summer.browser` 是插件 Worker 中的 Playwright Java 实现，提供 navigate/click/type/get_text/query/screenshot/wait/eval/close 九个工具。它还不能等价于 Codex 风格的宿主浏览器控制：

- 只支持 CSS selector，没有每次快照生成的稳定 element ref、role/name 定位和失效检测。
- 只有一个 Page；缺少 tab、popup、back/forward、hover、select、keyboard、drag、dialog、download/upload、console/network 观察。
- screenshot 返回插件 data dir 的绝对路径和 a11y 文本，模型没有统一的图像 artifact 通道。
- `eval_js` 默认暴露任意页面执行能力；navigate 没有 SSRF、私网、`file:`、重定向/DNS rebinding 策略。
- 浏览器 profile 默认持久化，cookie/login 跨任务保留；没有按会话隔离、显式登录态授权和审计。
- 浏览器路径配置 UI 尚未端到端接通（`BrowserSettings` 源码已标注 follow-up）。
- 测试使用 mock launcher；`scripts/e2e-smoke.sh` 明确不实际启动 Chromium，跨平台可运行性没有证据。

### 6.2 目标架构：能力属于宿主，不属于普通插件

新增宿主接口 `BrowserAutomationPort`，让 AI registry 注册内置 `browser_*` 工具。普通插件不得直接拥有持久登录 profile。底层使用可替换 provider：

```text
AI Tool / AgentRunner
        |
BrowserAutomationService  -- approval / policy / audit / artifact / quota
        |
BrowserAutomationPort
        +-- ElectronCdpProvider（桌面：独立 BrowserWindow/WebContents + CDP）
        +-- PlaywrightSidecarProvider（纯 Web/headless：宿主管理的受限 sidecar）
```

桌面优先复用 Electron 自带 Chromium，避免首次下载约 150 MB 浏览器；通过 Electron main process 的 `webContents.debugger`/CDP 控制专用、不可访问宿主 UI 的浏览上下文。Java 宿主与 Electron provider 通过一次性 token 的 loopback WebSocket/HTTP capability channel 通信。纯 Web/headless 发行使用宿主拥有的 Playwright sidecar；两者实现相同的端口契约。

### 6.3 建议工具面

第一阶段必须实现：

- `browser_session_open`：创建临时 session，可选经过批准的 named profile。
- `browser_navigate`：URL、wait 条件、最终 URL、title。
- `browser_snapshot`：返回精简 accessibility/DOM snapshot 与稳定 `ref`，附 session/page/revision。
- `browser_click`、`browser_type`、`browser_press_key`、`browser_select`：优先 ref，其次 role/name；revision 不匹配即要求重新 snapshot。
- `browser_screenshot`：返回宿主 artifact id + 可供多模态模型读取的图像，不返回绝对路径。
- `browser_tabs`：list/open/switch/close，显式处理 popup。
- `browser_wait`：element/text/url/load state，统一毫秒超时。
- `browser_back`、`browser_forward`、`browser_close`。

第二阶段增加 download/upload、dialog、hover/drag、console/network 摘要、受限 JS evaluate。任意 JS evaluate 默认禁用，只有开发者模式或逐次批准后开放。

### 6.4 必须内建的安全策略

- 仅允许 `http/https`；默认阻断 `file:`, `javascript:`, `data:`, `ftp:`。
- 每次导航与每个 redirect 都解析 DNS，默认阻断 loopback、RFC1918、link-local、IPv6 ULA、云 metadata；显式“本地测试模式”可按 origin 放行。
- 临时 profile 为默认；持久 profile 按用户和名称加密/隔离，首次使用及域名变化需用户确认。
- type 的值不进入日志；密码字段、token、cookie、Authorization、表单正文统一标记 secret。
- 点击提交、发送、购买、上传、下载、授权、执行 JS 按 external/write effect 进入现有审批门；普通 snapshot 为 read。
- 限制 session 数、页面数、snapshot 字节、截图尺寸、下载总量、单次动作与总任务时长；崩溃后保证回收整棵进程树。
- 页面文本属于不可信输入，必须在 agent prompt 中标记为网页内容，防止 prompt injection 伪装系统指令。

### 6.5 实施顺序与验收

1. 先关闭 P0-1～P0-4，建立可信进程/凭据边界。
2. 在宿主定义 provider-neutral session/page/ref/artifact contract 和审计事件。
3. 实现 Electron CDP provider，并用本地 fixture 覆盖导航、表单、popup、下载、截图、崩溃回收。
4. 实现 headless Playwright sidecar provider；打包固定版本 Chromium 或提供可校验下载，不使用运行时任意 CLI 下载。
5. 注册内置工具与审批 effect；前端展示实时页面、动作轨迹、待批准操作和截图。
6. 将 `fan.summer.browser` 标为兼容层/弃用，迁移后移除其持久 profile 与 `eval_js` 默认能力。

验收必须在 Windows、macOS arm64、Linux x64 上运行真实浏览器；还要强制模拟无沙箱 provider。不能再以 mocked Page 测试或“不调用浏览器的 e2e”作为发布证据。

## 7. 本次验证结果

| 命令 | 结果 |
|---|---|
| `git diff --check` | 通过 |
| `npm test && npm run build`（frontend） | 通过：12 tests；生产构建通过；有 Vuetify deprecated API 与 >500 kB chunk 警告 |
| `npm test && npm run build:ts`（desktop/electron） | 通过：59 tests；TypeScript 构建通过 |
| `./mvnw -f OfficialPlugins/pom.xml test` | 通过：五个官方插件模块全部成功；browser 35 tests 通过但未启动真实 Chromium |
| `./mvnw -f FengYu/pom.xml test` | **失败：345 tests，2 failures，1 error，2 skipped** |
| `scripts/e2e-smoke.sh` | 未运行：后端测试未通过，且该脚本本身不启动真实 Chromium |

补充说明：测试运行在本机 Java 25.0.4，而项目发布基线为 Java 21。发布门禁仍必须在 Temurin 21 重跑；不能把 Java 25 下的失败直接归因于 JDK 差异，也不能据此豁免失败。

## 8. Beta 放行门槛

全部满足后才建议发布公开 Beta：

- [ ] P0-1～P0-9 全部关闭，并各有失败优先的回归测试。
- [ ] 宿主、官方插件、前端、桌面全部测试在 Java 21/Node 24 基线上通过。
- [ ] `scripts/e2e-smoke.sh` 在 Linux CI 真实通过，并增加真实 browser smoke。
- [ ] Windows/macOS/Linux 各自完成安装包启动、SETUP→APP、插件安装/更新/禁用/卸载、DB provision/deprovision、崩溃恢复测试。
- [ ] 强制 NONE 环境验证：默认拒绝、按插件摘要授权后可运行、UI 明确显示 unrestricted。
- [ ] 插件包签名/官方身份校验到位；更新后旧 Worker 不再存活。
- [ ] 未签名发行不自动安装更新；公开 Beta 桌面包完成签名与 macOS 公证。
- [ ] 长稳测试覆盖 10 万次 RPC、日志洪泛、超大帧、慢 SSE、浏览器多 session、Worker/Chromium 异常退出，内存与进程数保持有界。

## 9. 建议修复批次

**批次 A（安全边界）：** P0-1、P0-2、P0-3、P0-4、P0-8。  
**批次 B（正确性与发布门禁）：** P0-5、P0-6、P0-7、P0-9。  
**批次 C（数据与可观测性）：** P1-1～P1-9。  
**批次 D（宿主浏览器）：** 按第 6 节分阶段实现并做三平台真实浏览器验收。

在批次 A～C 完成前，不建议把“无沙箱兼容”或当前 Browser Agent 作为公开 Beta 的安全能力进行宣传。
