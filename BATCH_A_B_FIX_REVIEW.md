# 批次 A / 批次 B 修复复核报告

**复核日期：** 2026-08-09  
**复核基线：** 分支 `4.0.0`，提交 `f1016b0`，包含当前工作区未提交修复  
**对照报告：** `BETA_READINESS_REVIEW.md`  
**范围：** 批次 A（P0-1、P0-2、P0-3、P0-4、P0-8）与批次 B（P0-5、P0-6、P0-7、P0-9）  
**约束：** 本轮只新增本复核报告，没有修改宿主、插件、工具链或发布代码。

## 1. 总结论

**批次 A 未完成，批次 B 未完成，暂不能关闭原 Beta 发布阻断。**

当前修复有明显进展：AI `FULL_ACCESS` 已与插件沙箱解耦，Windows Job Object 不再被当作文件/网络安全沙箱，JSON-RPC `pending` 成功路径泄漏已修复，SETUP 上下文及原有三项后端测试失败也已修复。但仍存在四个阻断性缺口：

1. macOS profile 仍以 `(allow default)` 开始，插件仍可读取绝大多数未显式列入 denylist 的用户文件；
2. 官方插件没有非对称签名验证，SHA-256 sidecar 还是可选项；
3. 插件升级停机是 best-effort，失败后仍继续换包，且 Worker identity 仅比较版本、不比较包摘要；
4. 未签名桌面更新仍可能被 `electron-updater` 自动下载，并在退出时自动安装。

| 批次 | 完成 | 部分完成 | 未完成/回归 | 结论 |
|---|---:|---:|---:|---|
| A | 1 | 4 | 0 | **不通过** |
| B | 2 | 1 | 1 | **不通过** |

## 2. 发布阻断发现（按严重度）

### [P0] B/P0-9：未签名更新仍可能自动下载并在退出时安装

`desktop/electron/src/updater/auto-updater.ts:33-40` 在判断 `FENGYU_SIGNED_RELEASE` 后选择提示方式，但无论是否签名，都先执行 `autoUpdater.checkForUpdates()`。

当前依赖 `electron-updater` 的运行时默认值是：

- `autoDownload = true`（`node_modules/electron-updater/out/AppUpdater.js:103-109`）；
- `autoInstallOnAppQuit = true`（同文件 `:110-114`）；
- 找到更新时，`checkForUpdates()` 会直接创建 `downloadPromise` 并调用 `downloadUpdate()`（同文件 `:400-423`）；
- 下载完成后会注册退出安装处理，正常退出时调用 installer（`out/BaseUpdater.js:69-88`）。

因此“未显式调用 `downloadUpdate()` / `quitAndInstall()`”不等于没有下载和安装。现有测试把 `checkForUpdates()` mock 成一个普通结果对象，没有模拟依赖的自动下载与退出安装行为，所以 62 项桌面测试通过并不能证明 P0-9 已关闭。

**必须修复：** 在任何 `checkForUpdates()` 调用前，对未签名构建同时设置 `autoUpdater.autoDownload = false` 和 `autoUpdater.autoInstallOnAppQuit = false`；签名状态应来自构建期不可变元数据，而不是可由启动环境修改的 `FENGYU_SIGNED_RELEASE`。增加断言这两个属性在 unsigned 分支为 false 的测试。

### [P0] A/P0-2：macOS 仍是 denylist，不是最小可读文件系统

`ProcessSandbox.java:229-275` 明确使用 `(allow default)`，只拒绝 `.ssh`、`.aws`、gcloud、GitHub Copilot、GPG、Docker、Kubernetes 与 FengYu runtime root。用户文档、浏览器数据、密码管理器数据、其他云工具配置以及未来新增的凭据目录均仍可读取。

这不满足原报告“deny-default / 显式 allow”的验收标准，也不能据此把 `SANDBOX_EXEC` 稳定标记为完整 security isolation。新增测试 `macSandboxDeniesSensitiveHostPaths()` 只检查若干 deny 字符串存在，没有证明未列出的文件不可读。

Linux 已移除 `--ro-bind / /`，方向正确，但测试只检查生成的命令字符串；尚未在装有 bwrap 的 Linux 上启动真实 Worker，验证 JDK、插件目录、plugin-data bind、网络隔离和被拒绝路径。

**必须修复：** macOS 使用 deny-default + 最小系统/JDK/plugin/package/data allowlist，或将当前模式明确降级为 reduced/advisory，不能报告为完整安全隔离。Linux/macOS 都需加入真实进程探针测试，而不是只断言命令/profile 文本。

### [P0] A/P0-8：官方身份只做命名空间阻断，没有发布者真实性

`PluginPackageService.validate()` 已正确阻止普通上传声明 `official:true` 或使用 `fan.summer.*`，这关闭了最直接的 UI/API 冒充路径。

但 `OfficialPluginSeeder.java:45-58` 只有在 sidecar 存在时才验证 SHA-256；没有 sidecar 仍调用 `installTrusted()`。SHA-256 sidecar 本身也只能证明“包与 sidecar 一致”，不能证明发布者身份。`installTrusted(Path)` 最终只把一个布尔参数传给校验器，没有公钥签名或可信发布记录。

**必须修复：** bundled/marketplace 官方包必须携带签名或由签名清单覆盖，宿主内置可信公钥并验证包摘要、插件 id、版本和渠道；缺少签名/摘要必须 fail closed。`installTrusted` 不应仅依赖调用路径赋予官方身份。

### [P0] B/P0-6：升级流程仍不是强一致停机换包事务

Marketplace controller 在上传、安装、更新前调用了 `processes.stop(id)`，且 Worker cache 增加 manifest version 比较。这能覆盖正常的版本升级路径。

但当前实现仍有三处缺口：

- `stopWorkerIfKnown()` 和 `stopWorkerForIncomingPackage()` 吞掉全部异常并继续安装（`PluginMarketplaceController.java:86-107`），因此无法保证旧 Worker 已停止；
- 没有“禁止新调用 → 等待/取消在途调用”的门闩，stop 与并发 invoke/install 之间仍有竞态；
- Worker identity 仅比较 `manifest.version()`（`PluginProcessManager.java:115-125`），相同版本重新打包、内容变更或版本未正确递增时仍无法从 identity 层识别；测试也在安装前手工 `manager.stop()`，没有验证 controller 的并发/失败路径。

**必须修复：** 以插件 id 加更新锁和调用 gate；停止失败应中止换包；Worker identity 使用安装包摘要而非只用版本；补充 stop 失败、并发 invoke、same-version 内容变化及 Windows 文件锁测试。

### [P1] A/P0-1：正向环境白名单已实现，但白名单仍包含高风险变量

`PluginProcessManager.applyEnvironmentAllowlist()` 已先清空继承环境再恢复白名单，`OPENAI_API_KEY`、`GH_TOKEN` 等普通宿主秘密不会再默认进入 Worker，主漏洞已显著缓解。

但白名单仍包含 `JAVA_TOOL_OPTIONS`、`JAVA_OPTS`、`HOME` 和 `XAUTHORITY`。其中 `JAVA_TOOL_OPTIONS` 会被新 JVM自动解释，可携带 `-javaagent`、系统属性或其他宿主敏感配置；这些变量的原值也可被 Worker 直接读取。它们并非启动 Java Worker 的必要最小变量。

**建议关闭条件：** 去掉 `JAVA_TOOL_OPTIONS`、`JAVA_OPTS`、`XAUTHORITY`；按平台论证 `HOME` 是否必需，若必需则重定向到插件专属 home。新增测试证明 Java agent/代理凭据/工具选项不会继承。

### [P1] A/P0-2：manifest 摘要缺失时仍放行旧插件

`PluginIntegrityStore.verify()` 在记录不存在时返回 empty（`:100-103`）；`PluginProcessManager.start()` 只在结果明确为 false 时拒绝（`:211-219`）。因此升级前已经安装、尚无摘要记录的插件仍可启动，测试还明确把 `absentRecordIsNotEnforced()` 固定为预期行为。

**建议关闭条件：** 首次启动时由宿主对现有包做受控迁移登记，或要求重新安装；迁移完成后“缺记录”必须 fail closed。摘要应覆盖整个 `.fyp`/安装目录的安全相关内容，而不只是 `manifest.json`。

## 3. 批次 A 逐项结论

| 项目 | 状态 | 复核结论 |
|---|---|---|
| P0-1 Worker 环境泄密 | **部分完成** | 已 clear + allowlist；白名单仍包含 `JAVA_TOOL_OPTIONS` 等不必要高风险值 |
| P0-2 POSIX 文件系统/自提权 | **部分完成** | 包目录不再写入 writable roots，Linux 移除全根 bind，并增加 manifest 摘要；macOS 仍 allow-default，旧插件无摘要仍放行 |
| P0-3 Windows Job 误报 | **部分完成** | lifecycle/security 已拆分，API 对 Windows 报 compatibility；Windows 仍没有 AppContainer/文件网络隔离，需按无安全沙箱验收 |
| P0-4 AI FULL_ACCESS 解除沙箱 | **完成** | 代码不再读取 AI permission context 决定 Worker sandbox，回归测试覆盖 forced NONE + FULL_ACCESS |
| P0-8 官方插件冒充 | **部分完成** | 普通上传已禁止 official/reserved namespace；无签名、sidecar 可缺失，真实性链未成立 |

**批次 A 结论：1/5 完成，不通过。**

## 4. 批次 B 逐项结论

| 项目 | 状态 | 复核结论 |
|---|---|---|
| P0-5 pending 泄漏 | **完成** | reader 使用 `pending.remove(responseId)`；成功调用后 map 归零测试通过。当前回归仅 50 次，发布前仍应执行 10 万次长稳测试 |
| P0-6 更新旧 Worker | **部分完成** | 正常 controller 路径会 stop，cache 比较版本；stop 失败仍换包、无调用 gate、无摘要 identity |
| P0-7 后端测试/SETUP | **完成** | SETUP 排除补齐，DDL 与连接测试同步；Java 21 和 Java 25 下后端测试均通过 |
| P0-9 未签名自动更新 | **未完成** | 依赖默认会在 `checkForUpdates()` 内自动下载，并在退出时安装；mock 测试漏检 |

**批次 B 结论：2/4 完成，1 项部分完成，1 项仍未完成，不通过。**

## 5. 本轮验证结果

| 验证 | 结果 |
|---|---|
| `JAVA_HOME=...21.0.12 ./mvnw -f FengYu/pom.xml test` | **通过：366 tests，0 failure，0 error，2 skipped** |
| 默认 Java 25.0.4 后端测试 | **通过：366 tests，0 failure，0 error，2 skipped** |
| `npm test`（desktop/electron） | **通过：62 tests**；其中一项旧测试产生被业务 catch 吞掉的 `TypeError` stderr，测试仍绿 |
| `npm run build:ts`（desktop/electron） | **通过** |
| `git diff --check` | **通过** |

本轮没有完成以下真实环境验收，因此不能由单元测试推导其安全性：

- Linux bwrap 真实 Worker 启动与文件/网络拒绝探针；
- Windows Job Object + forced NONE 真实安装包流程；
- macOS 对任意非 allowlist 用户文件的拒绝测试；
- 插件更新时的并发调用、停止失败与 Windows JAR 锁；
- unsigned Electron 包的真实更新下载/退出安装行为。

## 6. 建议重新放行顺序

1. 先修 P0-9，在调用 updater 前关闭自动下载和退出安装；
2. 将 macOS 当前策略降级为 reduced，或实现真正最小 allowlist；
3. 官方插件签名与强制摘要 fail-closed；
4. 升级流程增加 per-plugin gate、摘要 identity 和 stop-failure abort；
5. 收紧 Worker 环境白名单及旧插件摘要迁移；
6. 在 Windows/macOS/Linux 真实平台完成强制 NONE 与真实 Worker 探针后再关闭批次 A/B。
