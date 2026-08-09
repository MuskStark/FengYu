# 批次 A / 批次 B 修复第二次复核报告

**复核日期：** 2026-08-09  
**复核基线：** 分支 `4.0.0`、提交 `f1016b0`，包含当前未提交修复  
**对照：** `BETA_READINESS_REVIEW.md`、`BATCH_A_B_FIX_REVIEW.md`  
**范围：** A：P0-1/P0-2/P0-3/P0-4/P0-8；B：P0-5/P0-6/P0-7/P0-9  
**约束：** 只新增本报告，没有修改源码、测试、工作流或插件。

## 1. 第二次复核结论

**批次 A 和批次 B 仍不能整体关闭。** 第二轮修复消除了上次发现的 updater 默认自动下载问题，收紧了 Worker 环境变量，补充了 package digest、更新 gate、旧安装摘要迁移，并把 macOS 明确降级为 reduced isolation。但新实现仍有两个 P0 级发布/运行阻断和三个未闭环设计问题。

| 批次 | 完成 | 部分完成 | 未完成 | 结论 |
|---|---:|---:|---:|---|
| A | 2 | 2 | 1 | **不通过** |
| B | 2 | 2 | 0 | **不通过** |

最重要的新结论：

1. **正式 release workflow 不携带 `.fyp.sha256`，而 seeder 现在强制要求 sidecar。发布包中的官方插件将全部被跳过。**
2. **所谓 per-plugin update gate 存在 TOCTOU 竞态，不是原子 gate；invoke 仍可能越过检查并在 beginUpdate 之后启动新 Worker。**
3. `app.isTruested` 没有在任何 workflow/builder 配置中写入，代码仍会落到可变环境变量；signed update 分支尚未形成真实构建契约。

## 2. P0 阻断发现

### [P0] A/P0-8：发布流水线丢弃强制 sidecar，官方插件无法随正式包安装

CLI 已在每个 `.fyp` 旁生成 `<archive>.sha256`（`toolchain/cli/src/build.mjs:125-153`），`OfficialPluginSeeder` 也已改为 sidecar 缺失即跳过（`OfficialPluginSeeder.java:52-72`）。单独看这两端方向正确。

但 `.github/workflows/fengyu-release.yml` 仍然：

- 只执行 `cp OfficialPlugins/plugin-*/dist-package/*.fyp staging/plugins/`（`:137-141`）；
- artifact 只上传 `staging/plugins/*.fyp`（`:169-174`）；
- Web 和 Desktop 后续也只复制 `inputs/*.fyp`（`:214`、`:325`）。

`.fyp.sha256` 不匹配 `*.fyp`，因此不会进入共享 artifact、Web 包或 Electron resources。正式运行时 seeder 会看到所有官方 `.fyp` 缺少 sidecar并跳过。现有 `release-workflow.test.mjs` 只验证“plugins 被打包”，没有验证 sidecar；所以 11 项 release contract 测试通过仍漏掉该断链。

**关闭条件：** workflow、Web packaging、Electron extraResources 和 release contract tests 全链路携带并校验每个 `.fyp.sha256`；增加解包后的 seeder smoke，断言五个官方插件实际安装成功。

### [P0] B/P0-6：更新 gate 存在检查—使用竞态

`invoke()` 只在入口执行一次 `updating.contains(pluginId)`（`PluginProcessManager.java:103-109`），随后读取 manifest、digest，再进入 `workers.compute()`。`beginUpdate()` 则单独执行 `updating.add()` 和 `stop()`（`:215-229`）。两者没有共享锁、读写锁或 generation token。

可能时序：

1. invoke 看到 `updating=false`；
2. beginUpdate 设置 true 并停止旧 Worker；
3. invoke 继续进入 `workers.compute()`，启动一个新 Worker；
4. installer 同时替换包目录。

因此注释中的“原子 package update”不成立。新增测试 `invokeRefusedWhilePluginIsUpdating()` 只测试 gate 已经设置之后才调用 invoke，没有覆盖上述交错。

此外，`readIncomingId()` 预读失败时直接无 gate 安装（`PluginMarketplaceController.java:99-107`）；测试也没有覆盖 stop/close 失败、在途调用 drain、Windows JAR lock。

**关闭条件：** 每个插件使用同一个读写锁或 generation/state machine：invoke 在共享读侧完成 Worker 获取和调用登记，update 在写侧阻止新调用、等待/取消在途调用、确认 Worker 已退出后换包。增加可控 barrier 的并发回归测试。

## 3. 仍未闭环的高风险项

### A/P0-8：sidecar 只有完整性，没有官方发布者真实性

普通上传已不能声明 `official:true` 或使用 `fan.summer.*`，sidecar 也从 optional 改为 required，这是实质进展。但 sidecar 与 `.fyp` 同源同目录，能改包的攻击者也能重算 sidecar；`installTrusted()` 仍依靠调用路径中的布尔信任，没有内置公钥、签名清单或发布者身份验证。

原报告要求“签名/发布公钥和包摘要”。因此即使先修复 workflow 携带 sidecar，P0-8 也只能判部分完成，不能作为公开 Beta 的官方真实性链。

### A/P0-2：旧插件迁移会把当前磁盘状态直接登记为可信

`migrateIntegrityRecords()` 对缺记录的已安装插件直接计算当前 manifest/目录摘要并写入（`PluginPackageService.java:113-127`）。旧版本曾允许 Worker 写自身安装目录；如果插件已在此前运行中修改 manifest 或 Worker JAR，升级后的首次启动会把被修改状态“洗白”为可信基线。

而且 Worker 启动只实时复验 manifest digest；package digest 从记录中读取用于 cache identity，没有重新计算整个 live package。安装后的 Worker JAR 被宿主外部篡改时，manifest 仍匹配，重启会运行被修改代码。

**关闭条件：** 旧插件从可信 bundled/marketplace 包重新安装或由可信签名清单迁移，不能对未知当前状态自动背书；Worker 启动时复验整个安全相关包摘要，或保证安装目录具有宿主级不可写 ACL 并验证签名。

### B/P0-9：unsigned 默认路径已修，但 signed-state 构建契约不存在

当前代码已在 `checkForUpdates()` 之前设置：

```text
autoUpdater.autoDownload = signedRelease
autoUpdater.autoInstallOnAppQuit = signedRelease
```

因此在默认 unsigned 状态下，上一轮发现的隐式下载与退出安装已被关闭，相关桌面测试通过。

但 `readSignedReleaseFlag()` 读取自定义的 `app.isTruested`；仓库里没有任何 workflow、`electron-builder.yml` 或 package metadata 设置该字段，且代码没有读取打包后的 `package.json`。当前实际会回落到 `process.env.FENGYU_SIGNED_RELEASE`，它仍可由启动环境修改。字段名 `isTruested` 也疑似拼写错误，会固化错误公共契约。

**关闭条件：** 使用明确、可测试的构建期常量或读取受控 package metadata；unsigned packaged build 必须忽略环境变量。签名/公证落地前 signed 分支保持不可达，并增加对真实 packaged metadata 的 contract test。

## 4. 批次 A 逐项状态

| 项目 | 第二次状态 | 依据 |
|---|---|---|
| P0-1 Worker 环境继承 | **完成（代码级）** | clear + 正向白名单；已移除 `JAVA_TOOL_OPTIONS`、`JAVA_OPTS`、`XAUTHORITY`，秘密变量回归测试通过 |
| P0-2 POSIX 隔离/manifest 自提权 | **部分完成** | Linux 最小视图、包目录只读、缺摘要 fail-closed；macOS 正确标为 reduced，但旧迁移会背书未知状态，whole-package 不做 live verify |
| P0-3 Windows Job 误报 | **完成（代码级）/平台验收待办** | Windows Job 只报 lifecycle，macOS reduced，Linux full；仍没有真实 Windows 安装包验收或 AppContainer |
| P0-4 AI FULL_ACCESS 解沙箱 | **完成** | AI permission 与插件 OS sandbox 已解耦，forced NONE 回归测试覆盖 |
| P0-8 官方身份 | **未完成** | upload namespace 已封锁；无非对称签名，且 release workflow 丢失强制 sidecar导致官方插件无法安装 |

**批次 A：不通过。**

## 5. 批次 B 逐项状态

| 项目 | 第二次状态 | 依据 |
|---|---|---|
| P0-5 pending 泄漏 | **完成** | response reader 原子 `pending.remove`；回归测试通过，发布前仍需 10 万次长稳 |
| P0-6 更新旧 Worker | **部分完成** | 增加 content digest 与 updating 状态；gate 存在 TOCTOU，未 drain 在途调用、未验证 Windows 锁 |
| P0-7 宿主测试/SETUP | **完成** | Java 21 下 372 项测试通过，0 failure/error，2 skipped |
| P0-9 未签名自动更新 | **部分完成** | unsigned 默认已关闭自动下载/退出安装；build-time signed flag 尚未真正接入，env fallback 仍可启用 |

**批次 B：不通过。**

## 6. 本轮验证

| 验证 | 结果 |
|---|---|
| Java 21 `./mvnw -f FengYu/pom.xml test` | **通过：372 tests，0 failure，0 error，2 skipped** |
| Desktop `npm test` | **通过：63 tests** |
| Desktop `npm run build:ts` | **通过** |
| `scripts/release-workflow.test.mjs` | **通过：11 tests**，但没有 sidecar contract，存在漏检 |
| Toolchain CLI `npm test` | **失败：90 tests 中 1 failure**；`package-scope.test.mjs` 与当前 toolchain release workflow 不一致 |
| `git diff --check` | **通过** |

工具链测试失败不是 A/B 新修复本身造成的直接证据，但它意味着当前工作区仍不能通过完整发布门禁。

## 7. 建议下一轮最小修复顺序

1. 修复 release workflow 对 `.fyp.sha256` 的 stage/upload/Web/Desktop 全链路携带，并增加 contract + packaged smoke；
2. 用真正同步原语重写 plugin update gate，增加 barrier 并发测试；
3. 删除 packaged build 的环境变量 signed fallback，建立真实构建期 metadata contract；
4. 官方包实现公钥签名验证，不再把同目录 SHA-256 当作发布者身份；
5. 旧插件改为可信重装迁移，并在 Worker 启动前复验 whole-package digest；
6. 修复当前 toolchain CLI 测试失败后，再跑主 release workflow、Linux e2e 及 Windows/macOS 实机验收。
