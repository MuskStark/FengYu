---
title: 更新日志
lang: zh-CN
---

# 更新日志

**Infinia（蜂语 / FengYu）** 的所有重要变更。仓库中的
[CHANGELOG.md](https://github.com/MuskStark/FengYu/blob/4.0.0/CHANGELOG.md) 是唯一权威、始终最新的来源 ——
本页为文档站点镜像其内容。

::: tip 最新发布
**v4.0.0** — 2026-07-29 ·
[GitHub 发布](https://github.com/MuskStark/FengYu/releases/tag/v4.0.0)
:::

---

## [4.0.0] — 2026-07-29

### ✨ 新增
- **内置 `fengyu-plugin-dev` 技能。** 第二个内置技能（与 `fengyu-features` 并列），向应用内
  助手讲授 4.0.0 插件模型：`.fyp` 包（沙箱 iframe UI + 进程外 JSON-RPC worker）、
  `manifest.json` 字段、权限枚举、`aiTools`，以及 `fengyu` CLI 的构建/安装流程。其正文面向
  应用运行时上下文编写，区别于仓库内同名的 agent 工作流技能。

### ♻️ 变更
- **移除旧的 `FengYu-Api` 模块。** 仅供宿主使用的 AI 契约、工具分类和主题状态现已迁入无头
  `FengYu` 应用模块；同时删除过时的 JavaFX 预览资源和进程内插件日志桥，Maven 也不再管理
  JavaFX 构件。
- **应用版本统一为 4.0.0。** Maven、前端、Electron 外壳、内置技能和官方插件包现统一使用
  稳定版本。
- **将 VitePress 工具链限定在 `docs/`。** 文档的包清单、锁文件、已安装依赖、本地命令和 CI
  缓存现与文档源文件放在一起，不再占用仓库根目录。

## [4.0.0-alpha.5] — 2026-07-29

### ✨ 新增
- **Agent 运行记录现已持久化。** 每次「规划-执行」运行都会快照到数据库（`ai_agent_run`），
  并附带顺序化的生命周期事件追加日志（`ai_agent_run_event`），覆盖 `plan_ready`、
  `plan_approval_requested`、`step_start`/`step_complete`、`step_approval_requested`、`complete`
  以及 `error`/`cancelled`。历史可按用户列出/查看详情，失败或取消的运行可从上一个已完成步骤恢复；
  重启时，任何非终态的运行中记录（PLANNING / AWAITING_*_APPROVAL / EXECUTING）会被重分类为 FAILED。
  持久化失败只记日志，绝不中断健康的运行。
- **敏感工具调用需要用户显式批准。** 新的 `ApprovalRequiredTool` 契约标记了那些未经确认绝不可执行
  的宿主工具。在普通对话中，`ChatToolApprovalGate` 会在确认卡片上阻塞每个包含此类调用的模型响应
  （5 分钟超时）；在「规划-执行」Agent 中，每个步骤会为同一道门暂停。取消生成或切换后端会拒绝所有
  待批准请求。
- **`execute_command` 工具，带操作系统级进程沙箱。** AI 编写的 shell 命令在可用时运行于原生隔离器
  内 —— Linux 上用 `bwrap`（bubblewrap），macOS 上用 `sandbox-exec`（Seatbelt）—— 系统文件只读、
  写入限制在工作目录、网络隔离除非显式开启。启动前会剥离继承自宿主的、含有
  `TOKEN`/`SECRET`/`PASSWORD`/`API_KEY`/`CREDENTIAL`/`COOKIE`/`AUTHORIZATION` 的环境变量，
  输出有上限（默认 64 KiB，最大 256 KiB）并标注截断，超时（默认 30s，最大 600s）会强制终止子进程。
  无隔离器时回退为直接执行并在结果中披露 `compatibilityMode`；无论如何批准始终是强制要求。
  `GET /api/security/process-isolation` 报告当前后端。
- **MCP（Model Context Protocol）客户端。** 通过 `spring.ai.mcp.client.*` 配置的 MCP 服务器在启动时
  连接，并向 Agent 暴露其工具。`GET /api/mcp/status` 报告启用标志、连接/工具数，以及每连接详情
  （名称、版本、协议版本、是否初始化）。
- **宿主与 Java 插件 Worker 现在共享同一个实时日志级别。** 设置页持久化
  `TRACE`/`DEBUG`/`INFO`/`WARN`/`ERROR`/`OFF`，应用到宿主的 Logback 命名空间，并无需重启地推送给运行中的
  Worker。Java Worker SDK 用结构化的 stderr provider 取代 `slf4j-simple`，保留 logger 名、线程、级别、
  消息和异常栈，同时把 stdout 留给 JSON-RPC；旧的自由格式 stderr 仍受支持。

### 🔒 安全
- **运行时机密文件在 POSIX 上为属主专属。** `SensitiveFilePermissions` 在 macOS/Linux 上对机密/密钥
  目录应用 `rwx------`、对其文件应用 `rw-------`（Windows 上为 no-op，由用户配置文件 ACL 负责）。

### ♻️ 变更
- **默认运行时状态自包含于启动目录下。** 未显式指定 `fengyu.runtime.dir` 时，应用把内嵌数据库存放在
  `<程序工作目录>/.fengyu/database/`，把配置、日志、插件、技能及其他可写状态放在
  `<程序工作目录>/.fengyu/` 下。
- **插件 Worker 按清单权限沙箱化。** `ProcessSandbox.plugin(...)` 把每个 Worker 的写入限制在其插件自有
  根目录（声明 `files.write` 时放宽），并在受支持的隔离器上按清单隔离网络。

---

## [4.0.0-alpha.4] — 2026-07-28

### 🐛 修复
- **插件 UI 图标在沙箱化的第三方插件中现在能稳定渲染。** 宿主 CSP 明确允许同源和内置的 `data:`
  字体以兼容已有的 `.fyp` 包；`@infinia/plugin-ui` 把它的 MDI 样式表留给消费方 Vite 应用，使新构建产物输出常规的
  hash 字体资源，而不是把数兆字节的字体嵌进库 CSS。官方插件 UI 现在直接声明 `@mdi/font`，这样在严格的
  `npm ci` 下 Vite 构建能解析被外部化的 `@import`。
- **`window.fengyu` 访问已适配 SSR。** 添加到 `settings.ts`、`main.ts` 和 `router/index.ts` 的桌面桥接调用现在用
  `typeof window !== 'undefined'` 守护，因此在 vite SSR / `node --test` 测试套件中不再抛出 `ReferenceError`。

### ♻️ 变更
- **Windows 桌面发布改为提供解压即用的 ZIP，取代自解压便携 `.exe`。** 精简版和 JRE 版都保留 NSIS 安装程序
  （`*-win-x64-setup.exe`），并新增 `*-win-x64-portable.zip`（解压一次后运行 `Infinia.exe`）；旧便携程序在启动时的
  自解压步骤已被移除。产物名称在 macOS / Windows / Linux 上统一为
  `<product>-<version>-<platform>-<arch>[<form>].<ext>`
  （`Infinia-4.0.0-mac-arm64.dmg`、`Infinia-4.0.0-win-x64-setup.exe`、……），发布工作流及其契约测试已同步更新。
- **桌面端启动不再闪烁，且不再依赖在线后端来选择路由。** 外壳在探测后端时显示一个启动闪屏窗口，通过
  `window.fengyu.setupMode()` / `initialTheme()` / `setTheme()` 把预探测到的 setup 状态和所选主题暴露给渲染进程，
  路由在首次导航时消费这份快照，随后才回退到在线检查。

### 🔧 内部
- **后端运行时目录已集中化。** 插件、技能、插件数据以及临时文件目录现在通过新增的 `RuntimePaths`
  从同一个稳定根目录派生，并可通过 `fengyu.runtime.dir` 覆盖（默认 `~/.fengyu`），取代了过去分散的
  `System.getProperty("user.dir")` 路径。`CryptoUtil` 从同一根目录派生 `.machineid`。这让打包的 Electron 外壳与
  便携 Web 发行版在状态存放位置上保持一致。
- **便携 Web 发行版保持状态自包含。** `run.sh` / `run.bat` 现在传入
  `-Dfengyu.runtime.dir=<dist>/data`，使数据库、配置、日志和插件数据落在解压目录内启动器旁边，而不是用户主目录
  —— 维护了“解压即用、移动/删除不留痕迹”的便携性契约。`scripts/e2e-smoke.sh`
  把同一属性固定到其临时目录，保证其运行可重复。

---

## [4.0.0-alpha.3] — 2026-07-25

### ♻️ 变更 — 桌面外壳：Tauri → Electron
- **桌面外壳从 Tauri 2.0（Rust）重写为 Electron 43（TypeScript）。** 后端生命周期**保持不变** —— Electron
  通过回环地址拉起后端 JAR，等待 `/api/health`，并通过 `contextBridge` 预加载脚本（`window.fengyu`）把 token +
  api-base 交给渲染进程。这取代了旧的 Tauri `window.__FENGYU_*` 全局对象。
- **每个平台两个发布变体。** 每个平台都提供**精简版**构建（用户在 `PATH` 中自行提供 Java 21+）和 **JRE 版**构建
  （在 `<resources>/jre/` 下打包一个 `jlink` 最小化的 JRE）：
  - **macOS**（arm64 + x64）—— `Infinia-<ver>-mac.dmg` 和 `Infinia-<ver>-mac-jre.dmg`。
  - **Windows**（x64）—— `Infinia-<ver>-win.exe`（NSIS 安装程序）和便携 exe；外加 `-jre.exe` NSIS 变体。
  - **Linux**（x64）—— `Infinia-<ver>.AppImage` / `.deb` 以及 `-jre.AppImage`。
- **自动更新**（`electron-updater` 对接 GitHub Releases）、**系统托盘**（关闭窗口时隐藏到托盘，后端一直存活直到应用退出）、
  **单实例锁**，以及**文件日志**（`electron-log` → `~/.fengyu/logs/desktop.log`）。
- **开发模式**默认连接 IDE 启动的、地址为 `http://127.0.0.1:24056` 的后端（不拉起、无 token、无监管进程）；
  设置 `FENGYU_JAR=<jar>`（或 `FENGYU_DEV_BACKEND=disabled`）可让外壳以完整发布生命周期拉起自己的后端。
- **安全姿态：** `contextIsolation: true`、`nodeIntegration: false`、`sandbox: true`。导航守卫 ——
  `setWindowOpenHandler` 把 `http(s)` 目标委托给系统浏览器并拒绝 `window.open('file://...')`；`will-navigate`
  阻止跨源页内导航。参见 [/zh/architecture/desktop](/zh/architecture/desktop)。

### ♻️ Toolchain 目录整合
- 7 个插件工具链目录（2 Maven + 4 npm + schema）整合进 `toolchain/`，扁平化中间层，统一语义短名
  （`sdk-java`/`devkit-java`/`sdk-ts`/`ui`/`dev`/`cli`/`spec`）：
  - `FengYu-Plugin-Sdk` → `toolchain/sdk-java`
  - `FengYu-Plugin-DevKit` → `toolchain/devkit-java`
  - `plugin-sdk/typescript` → `toolchain/sdk-ts`
  - `plugin-ui/vue` → `toolchain/ui`
  - `plugin-dev` → `toolchain/dev`
  - `plugin-cli` → `toolchain/cli`
  - `plugin-spec` → `toolchain/spec`
- CI/release workflow 与 skill 重命名为 `toolchain-*`
  （`plugin-tooling.yml` → `toolchain-ci.yml`，`plugin-tooling-release.yml` → `toolchain-release.yml`）。tag 前缀
  `plugin-tooling-v*` 不变。
- **Maven artifactId 与 npm 包名不变**：`fan.summer.fengyu.sdk:fengyu-plugin-sdk`、
  `fan.summer.fengyu.sdk:fengyu-plugin-devkit` 以及 `@infinia/plugin-sdk` / `@infinia/plugin-ui` /
  `@infinia/plugin-cli` / `@infinia/plugin-dev` 仍按原名发布。仓库目录变了，坐标不变。

### ✨ 新增
- **Skills（技能）** —— 继插件和 AI 工具之后的第三种扩展面，采用 Codex 式渐进式披露。已启用的技能在系统提示词中
  以紧凑目录形式出现，助手按需通过内置 `skill` 工具加载技能的完整正文 —— 因此大型指引文档永远不会撑大每次请求的
  token 预算。
  - **像插件一样管理：** 技能打包为 **`.fys` 归档**（zip：`manifest.json` + `SKILL.md`），安装到
    `~/.fengyu/skills/<id>/` —— 与 `~/.fengyu/plugins/<id>/` 是文件系统层面的对等项。完整的安装/卸载/启用/禁用生命周期
    与插件系统一致（在 `SkillPackageService` 中实现原子发布 + 备份回滚）。
  - **市场：** `fengyu.skills.catalog-url` 指向一个远程目录 JSON；`SkillMarketplaceService`
    把远程条目与本地安装状态合并（`PluginMarketplaceService` 的生命周期孪生）。可按 id 从目录安装/更新。
  - **启用状态**是一个 `.disabled` 文件系统标记（不是数据库行），与插件完全一致，因此能在重装后保留。
  - **OfficialSkillSeeder**（`ApplicationRunner`）在启动时幂等地种子化内置 `.fys` 产物，与 `OfficialPluginSeeder` 对应。
  - 两种发现来源：**内置**（`classpath:/skills/<id>/SKILL.md`，打包进 JAR，不可卸载/禁用）和**已安装**
    （`~/.fengyu/skills/` 下的 `.fys` 包）。id 冲突时已安装技能覆盖内置技能。
  - `/api/skills` 下的新 REST 接口：list、detail、market、upload、upload-native、install、update、PATCH enabled、
    DELETE（内置技能返回 409）。全部要求 `X-FengYu-Token`。
  - 前端：技能管理**集成进插件页**（`/plugins`）—— 顶部有一对 Codex 式的 `Plugins | Skills` 标签切换视图，
    带已安装快行和卡片网格。单个 Upload 按钮同时接受 `.fyp` 和 `.fys` 归档，并按扩展名分流。没有单独的 `/skills` 路由。
  - 内置示例技能 `fengyu-features`（回答“FengYu 能做什么”），现在在其 `SKILL.md` 旁配有 `manifest.json`。
  - 技能与插件解耦 —— 它们从不触碰 `plugin-spec/` 或插件清单。
  - 参见 [/zh/skills/](/zh/skills/) 和 [/en/skills/](/en/skills/)。
- **IDE 插件调试（插件工具链 1.1.0）** —— `fengyu plugin dev` 被一条 IDE 原生流程取代，第三方作者可以用真实断点调试
  UI 和 worker，无需 JDWP 远程附加。
  - **`@infinia/plugin-dev`**（新 npm 包，`plugin-dev/`）—— 一个 Vite 插件，把开发服务器变成 FengYu 宿主模拟器：在
    `/__fengyu` 提供 iframe 外壳，桥接 `@infinia/plugin-sdk` 的 `postMessage` 调用，并通过回环 TCP 把 `rpc.invoke`
    转发给开发 worker。
  - **`fengyu-plugin-devkit`**（新 Maven 产物，`fan.summer.fengyu.sdk:fengyu-plugin-devkit`，
    `FengYu-Plugin-DevKit/`）—— 一个仅限回环的 TCP JSON-RPC 服务器（`PluginDevServer`），驱动 worker 的
    `serve(RpcTransport)` 循环。脚手架生成在 `worker/src/test/java` 下的 `PluginDevMain`；声明 `<scope>test</scope>`，
    因此永远不会进入 shaded JAR。
  - Java Worker SDK 中的 **`RpcTransport` 抽象** —— `JsonRpcWorker.serve(RpcTransport)` 在生产 stdio
    （`StdioTransport`）和 devkit 的回环 socket 之间共享调度循环。`run()` /
    `run(InputStream, OutputStream)` 的行为不变。
  - 脚手架现在生成共享的 `<Prefix>Worker.create()` 处理器工厂、生产用的 `<Prefix>WorkerMain`，以及 IDE 调试用的
    `PluginDevMain`。纯 UI 脚手架设置 `mockWorker: true`。
- **真实 LLM 规划器 + 可视化画布工作流构建器（AiAgent）。** 空的 `StubPlanGenerator` 被
  `ChatBackendPlanGenerator` 取代，它向当前激活的 AI 后端请求一个经过校验的结构化工作流，同时在规划期间禁用工具
  （新的 `ChatBackend#chatWithoutTools` 默认 —— `OllamaLocalBackend` 和 `SpringAiCloudBackend` 都遵循该开关）。
  `AgentRunner` 在任何工具运行前校验每个工作流（模型提供或用户提供），解析步骤结果引用
  （<code v-pre>{{steps.N.result}}</code>、<code v-pre>{{last.result}}</code>），并通过 `POST /api/agent/run` 接受调用方提供的工作流，
  使 HTTP API 能驱动确定性执行。前端获得一个 **Vue Flow** 画布（`AiAgent.vue`），带工具面板、
  `WorkflowToolNode`，以及把图编译为发送给后端的 `AgentPlan` 的 `workflow.ts` —— AI 规划路径的无代码对等项。
  EN/ZH 文案已更新。此外：Electron 主窗口在深色表面上以隐藏方式启动，仅在首次绘制（或加载失败）时显现，
  消除了冷启动时的白闪。

### 🐛 修复
- **IDE Worker 失败不再看起来像成功。** 当配置了 `workerEndpoint` 时，`@infinia/plugin-dev` 把连接失败作为 RPC 错误
  返回，而不是静默用 `devMock` 数据替换。所有官方插件 UI 现在都暴露文档所述的 `npm run dev` 入口。
- **插件工具链发布门**现在发布权威清单 schema，把独立版本化的 Worker SDK 从应用父级检查中豁免，并解决打过补丁的
  `fast-uri` 3.1.4。
- **首次启动（SETUP 模式）不再崩溃。** `SkillController` 被组件扫描进无数据库的 SETUP 上下文，但它依赖
  `SkillRegistry`/`SkillPackageService`/`SkillMarketplaceService`（位于 `ai.skill` 包，SETUP 模式不扫描该包），
  导致 `UnsatisfiedDependencyException`，在数据库向导运行前就中断了启动。它现在与其他仅 APP 控制器一起被排除在
  `SetupApplication` 的扫描之外。
- **B1 — actuator `restart` 端点从默认暴露中移除。** `application.yml` 现在仅设置
  `management.endpoints.web.exposure.include: health`。`/actuator/restart` 端点原本可达，而在 Web 包默认无 token 的
  姿态下，任何回环进程都能强制上下文重启（DoS）。SETUP→APP 重启已经通过 `System.exit(SETUP_DONE)` + 桌面监管进程完成，
  因此没有功能损失。
- **B2 — Web 包默认生成按启动一次的 token。** `distribution/web/run.sh` 和 `run.bat` 现在在用户未传 token 时生成
  随机 `--token=`（此前默认禁用认证）。显式 `--token=<t>` 仍可覆盖。
- **D1 — 桌面导航守卫。** `setWindowOpenHandler` 拒绝 `window.open` 并把 `http(s)` 目标委托给系统浏览器；
  `will-navigate` 阻止跨源页内导航。防止被入侵的页面执行 `window.open('file://...')`。
- **D2 — 自动更新器跳过 JRE 变体。** 捆绑 JRE 的构建检测 `resourcesPath/jre` 并跳过更新检查 —— 更新器 feed 只引用
  精简变体，因此自动更新会把 JRE 用户静默降级为依赖 Java 的精简构建。完整的按变体 feed 延后实现。
- **D3 — 监管进程的 `stop()` 现在被保存并调用。** `main.ts` 存储 `superviseSetupRestart` 的返回值并在
  `killBackend()` 中调用它（防御性；防止将来加入持久监听器时出现泄漏）。
- **D4 — APP 模式后端崩溃显示对话框。** APP 模式下的一个轻量退出监听器在意外后端崩溃时显示
  `dialog.showErrorBox` 并退出（此前是静默的 —— 用户只能看到连接错误）。该 alpha 不自动重启。
- **桌面端 —— 窗口右侧的深色细条。** 外壳允许一个文档级滚动条，其透明轨道暴露了 Electron 原生窗口底层作为右侧的
  一条细深色线。`html/body/#app` 现在设置 `overflow: hidden` —— 外壳拥有其面板（侧边栏历史、聊天列）内的滚动，
  永不创建文档滚动条。窗口的 `backgroundColor` 也与深色主题（`#0d0d0d`）对齐，使原生底层永远不与渲染进程形成反差。
- **Electron 迁移与工具链门加固。** 新的 `desktop/electron/scripts/verify-frontend-dist.mjs` 在
  `frontend-dist/` 缺失或过期时阻止桌面构建；`backend/spawn.ts`、`supervisor.ts`、`util/health.ts` 和
  `main.ts` 收到了额外的生命周期加固，并附带新单元测试（`health.test.ts`、`spawn.test.ts`、扩展后的
  `supervisor.test.ts`）。
- **插件工具链 —— `sdk-ts` lockfile 与 1.1.0 不同步**（根版本停留在 1.0.0）；已重新生成，使根与 `packages[""]` 一致。
  另在 `toolchain/ui` 中通过 `npm overrides` 强制 `brace-expansion=5.0.8`，以清除
  `@vue/test-utils → js-beautify → … → brace-expansion@2.1.2` 上的 6 项高严重性审计发现（2.x 线上无上游修复）。
- **启动闪屏 —— 在 JRE 构建变体中已发布。** 当 `resources/splash.html` 被加入桌面 asar 时，
  `electron-builder.jre.yml` 未与精简配置同步，因此自包含的 JRE 构建（旗舰下载）静默地从不显示闪屏。文件列表现在与
  `electron-builder.yml` 匹配，两个配置都包含 `resources/splash.html`。
- **AI 规划器超时不再卡死后端。** 当规划调用超过其 180s 预算（例如卡死的 Ollama 进程或停滞的提供方连接）时，
  `ChatBackendPlanGenerator` 放弃，但底层流一直阻塞在 `blockLast()` 上无法中断。`OllamaLocalBackend` 和
  `SpringAiCloudBackend` 现在持有 Reactor 的 `Disposable` 并等待一个 `CountDownLatch` 而非 `blockLast()`，因此
  `cancelGeneration()` 能在流中途 `dispose()` 并释放 worker。规划器在任何超时/失败路径上调用 `cancelGeneration()`，
  保证 `generating` 标志被清除，此后每个 `chat` / 规划请求不再以 *“Generation already in progress”* 失败。
  由新回归测试（`ChatBackendPlanGeneratorTest`）覆盖。

### ♻️ 变更
- **CLI 范围收窄为 `create` + `build`。** `fengyu plugin dev` 迁移到 IDE
  （`@infinia/plugin-dev` + `fengyu-plugin-devkit`）；`fengyu plugin validate` 现在是 `build` 的内置步骤
  （打包前总是校验暂存树）；`fengyu plugin install` 通过宿主的插件市场 UI
  （`POST /api/plugin-market/upload`）完成。`--port`、`--host`、`--token` 和 `--ui-port` CLI 标志随其命令一并移除。
- **插件工具链锁定为六个产物**，全部作为 `plugin-tooling-vX.Y.Z` 一起发布：Worker SDK、devkit、
  `@infinia/plugin-sdk`、`@infinia/plugin-ui`、`@infinia/plugin-cli` 和 `@infinia/plugin-dev`。
  `plugin-cli/scripts/resolve-tooling-version.mjs` 校验全部六个。

### 🗑️ 移除
- `fengyu plugin dev`、`fengyu plugin validate` 和 `fengyu plugin install` CLI 子命令及其源码
  （`plugin-cli/src/dev.mjs`、`worker.mjs`、`install.mjs`）。开发现在在 IDE 中通过 `@infinia/plugin-dev` 进行；
  `FENGYU_DEBUG` JDWP 远程附加的变通不再需要（运行 `PluginDevMain` 并直接设断点）。

---

## [4.0.0-alpha.1] — 2026-07-19

4.0 线的第一个公开 **alpha**。Infinia（蜂语 / FengYu）从 JavaFX 桌面应用重构为**无头（headless）web + 桌面应用**：
一个仅绑定回环地址的 Spring Boot 后端、一个 Vue 3.5 + TypeScript SPA（浏览器和桌面完全相同），以及一个 sidecar
方式拉起后端的 Tauri 2.0 桌面外壳。内置工具变成隔离的 **`.fyp`** 插件 —— 一个沙箱化的 iframe UI，与一个进程外的
JSON-RPC 2.0 worker 通信。本 alpha 发布了未签名的 Windows/macOS/Linux Tauri 包和一个便携的、仅限回环的 Web 发行版。

### ⚠️ 破坏性变更
- **JavaFX 已移除。** 所有 JavaFX 代码和依赖都被删除 —— `FengYuApp`、`ui/` 外壳、所有内置工具 UI 类、
  v1 的 `PluginRegistry`/`PluginLoader`，以及每个 `org.openjfx:*` 依赖。运行中的后端是无头的（无窗口）。
- **新入口点：** `fan.summer.fengyu.HeadlessLauncher`（原为 `fan.summer.Launcher`）。它启动一个仅回环的 Spring Boot
  web 服务器：`java -jar FengYu-4.0.0-alpha.1.jar --port=<n> --token=<t>`。
- **插件契约 v2**（`FengYuPluginV2`）：`descriptor()` + `invoke(action, args)`（JSON 入 / JSON 出）+ `aiTools()`。
  旧的 `createView()` → JavaFX `Node` 契约被移除；UI 现在是单独提供的微前端 ESM 包（`PluginDescriptor.uiEntry`）。
- **`IconStyle` 与 JavaFX 解耦** —— 颜色为 RGB 整数 + `getColorHex()`（无 `javafx.scene.paint.Color`）。
- **数据库层从 MyBatis 迁移到 Spring Data JPA + Hibernate 7** —— 见“移除”。

### ✨ 新增
- **无头后端**（`fan.summer.fengyu.web.*`）：`GET /api/health`、`GET /api/plugins`、
  `POST /api/plugins/{id}/invoke`、`GET /plugin-ui/{id}/**`（提供 MF 包）、`GET/PUT /api/settings`、
  `POST /api/ai/chat` + `GET /api/ai/stream`（SSE：token / thinking / tool / done / error）。仅回环绑定 + 按启动一次的
  `X-FengYu-Token` 认证（SSE 流用 `?token=`）。
- **Alpha 桌面 + web 发布流水线** —— `v4.0.0-alpha.1` 发布未签名的 Windows/macOS/Linux Tauri 包和一个便携的、仅回环的
  Web 发行版。Vue SPA 被烘焙进 shaded 后端 JAR（`static/`），由新的 `SpaForwardController` 提供；
  一个发布 tag 解析器（`scripts/resolve-release-version.mjs`）驱动版本字符串，`scripts/package-web-release.sh` +
  `test-web-release.sh` 组装并对归档做冒烟测试。代码签名、捆绑 JRE 和自动更新器仍延后到后续版本。
- **多数据源配置向导**：首次启动引导用户完成数据库选择（H2 / SQLite / MySQL / PostgreSQL），支持连接测试和自动 schema
  初始化。当 `~/.fengyu/config/datasource.properties` 不存在时，后端以 **SETUP 模式**（最小 Spring 上下文，无 JPA）
  启动；一旦存在则以 **APP 模式**（完整上下文）启动。Tauri/桌面监管进程在向导完成后重启 sidecar。
- **JPA 迁移**：数据库层从 MyBatis 迁移到 **Spring Data JPA + Hibernate 7**（`ddl-auto=update`）。全部 14 个实体以
  `@Entity` 注解移植；14 个 Spring Data 仓库取代 MyBatis mapper。
- **用户体系基础**：`sys_user` / `sys_session` 表，所有用户范围表上的 `user_id` 行级隔离，以及可插拔的
  `AuthProvider` / `SecurityContext` 接口与一个 Noop 实现（登录 UI 延后到后续阶段）。本地离线模式把所有数据归属于
  单个虚拟用户（id=1，“ZFlow-Summer”），在 APP 模式启动时创建。
- **AES-GCM 加密**（`CryptoUtil`）用于 `datasource.properties` 中的数据源口令字段 —— 密钥通过每台机器的 UUID 与机器绑定。
- **官方插件 UI 套件** `@infinia/plugin-ui` —— 面向生成插件的 Vuetify 3（Material Design 3）组件库。提供
  `FyPluginShell`、`FyPageHeader`、`FyToolbar`、SDK 支撑的 `FyFilePicker` / `FyDirectoryPicker`、`FyStepWizard`、
  `FyTaskTable`、`FyNotificationCenter`、`FyEmptyState` / `FyLoadingState` / `FyErrorState` / `FyPermissionNotice`
  状态面板，以及 `FyConfirmDialog`，外加 `createFengYuVuetify`、`bindFengYuEnvironment` 和
  `provideFengYuClient`，使脚手架生成的 `main.ts` 自动绑定宿主主题/语言。
- **邮件中心 `.fyp`**（`fan.summer.email`）—— 沙箱化的五标签页 Vue/Vuetify/TipTap UI、隔离的官方 SDK Java Worker，
  以及包权限仅限数据库、邮件网络和已授权文件读写。多账号 SMTP/IMAP 配置、AES-GCM 凭证存储、通讯录/标签 CRUD、
  已确认的单发和群发、手动 IMAP `.eml` 收集、归档搜索/详情，以及七个清单声明的 AI 工具。插件自有表在 H2、SQLite、
  MySQL 和 PostgreSQL 上使用 `FengTu_PL_Email_` 命名空间。
- **Excel 拆分器 `.fyp`**（`fan.summer.excel`）—— BY_SHEET / BY_COLUMN / COMPLEX 拆分模式，带状态化的四步向导、
  六个清单声明的 AI 工具，以及已授权文件读写。
- **Markdown 编辑器 `.fyp`**（`fan.summer.markdown`）—— 第一个官方 v2 插件：通过 `invoke("render", {markdown})`
  进行服务端 commonmark 渲染，外加 Vue 分屏编辑器 + 实时预览。
- **`frontend/`** —— Vue 3.5.39 + TS 外壳：侧边栏（可折叠、分类）、主题（深/浅）、设置、AI 对话
  （SSE + markdown + 可折叠思考）、ToolGrid，以及一个动态导入各插件 `uiEntry` 的微前端宿主。
- **`desktop/`** —— Tauri 2.0 外壳：拉起 Java sidecar（`--port=24056`），从 stdout 读取 `FENGYU_PORT`，轮询
  `/api/health`，把后端 URL + token 注入 webview，关闭时杀死 sidecar。
- **可发布的插件工具链** —— Java Worker SDK（`fan.summer.fengyu.sdk:fengyu-plugin-sdk`，独立版本化，GitHub Packages）
  以及 npm 包 `@infinia/plugin-sdk`、`@infinia/plugin-ui`、`@infinia/plugin-cli`，外加一个带干净消费者冒烟任务的
  发布工作流。
- **默认 Vue + Java 脚手架** —— `fengyu plugin create` 默认产出完整插件：一个由 Java JSON-RPC worker（`worker/`）
  支撑的 Vue/Vuetify UI（`ui-src/`），含 Maven Wrapper、构建声明、测试，以及 GitHub Packages 的 `settings.xml`。
  `--ui-only` 保留轻量的纯 UI 模板。
- **真实 worker 开发模拟器** —— `fengyu plugin dev` 构建 worker JAR（若缺失），启动真实 Java JSON-RPC worker，并通过
  `POST /__rpc` 转发 UI 的 `rpc.invoke` 调用。Java 源码编辑触发防抖重建 + worker 重启。
- **声明的构建生命周期** —— `fengyu.plugin.json` 驱动一个有序、原子的流水线
  （prepare → install → test → build → validate staging → package），使用 Maven Wrapper（无系统 Maven 回退）。
  `--skip-tests` 仅跳过测试，绝不跳过类型检查或打包。
- **共享清单契约** —— 一个权威的 `plugin-spec/manifest.schema.json` + 被 CLI 和宿主共享的 fixtures，包括
  `database` 和 `network.email` 权限，以及 AI 工具 `method` / object-schema 校验。
- **离线 Python 构建器 `.fyp`**（`fan.summer.offlinepython`）—— 体检、依赖搜索、项目初始化、带流式日志的异步
  wheelhouse 构建，以及输出校验。

### ♻️ 变更
- **采用 Vuetify 3（Material Design 3）** —— web 外壳和插件微前端的视觉语言全面切换，从旧的 `--sk-*` IntelliJ
  token 系统转向 MD3。主题由 `useThemeStore` 经 Vuetify 全局单例驱动；插件通过 `PluginContext.vuetify` 共享宿主的
  Vuetify 实例。
- **状态化插件工作流** —— `@infinia/plugin-ui` 提供一个受控的、持久化就绪的分步向导，支持显式状态、异步校验、分支、
  失效和快照；官方 Excel 插件采用它，支持重载重新分析与配置回放、worker 忠实的模式校验、安全的输出重选，以及显式的
  完成/下载。
- **精细化的 plugin-ui 表面** —— `@infinia/plugin-ui` 获得一层主题驱动的润色，使使用 `FyPluginShell` 的插件共享一种
  平静、低高度的设计语言（发丝边框、柔和的主色激活 chip、品牌标记、去大写按钮、更紧凑的字段/表格）。每种颜色都通过
  Vuetify 主题变量解析；邮件的绿色调色板被显式排除。
- **Node.js 24.18.0 基线** —— 文档、`plugin-cli` 引擎元数据，以及每个 GitHub Actions 工作流现在使用同一确切的
  Node.js 版本，受一个仓库契约测试保护。
- **由 CLI 构建的官方插件** —— Markdown、Excel 和 Email 由 `fengyu plugin build`（一个 CI 矩阵）打包；
  旧的 shell 打包器和集中式源码清单被移除。
- **离线优先安装** —— `fengyu plugin install` 在任何网络访问前校验归档（限制、路径、清单）；不安全或无效的包在零次
  fetch 调用下被拒绝。
- **严格的 SDK RPC 契约** —— worker 暴露规范的 JSON-RPC 错误（`-32700` 解析、`-32600` 无效请求、`-32601` 未知方法、
  `-32000` 处理器失败）；TypeScript 客户端在每个 settled 路径上移除中止监听器。
- **HeadlessLauncher** 现在根据 `datasource.properties` 是否存在选择 `SetupApplication`（SETUP）或
  `AiApplication`（APP，带 `fengyu.mode=app`）；桌面宿主在 `SETUP_DONE`（退出码 0）时重启 sidecar 以进入 APP 模式。
- `AiConfigService` / `AiConfigServiceHeadless` / `EmailUtil` 从静态工具类转换为按
  `SecurityContext.currentUserId()` 作用域的 Spring bean。配置向导端点（`/api/setup/*`）绕过 token 认证
  （`TokenAuthFilter`）。
- 邮件群发为每个解析出的附件标签创建一条消息；所有匹配联系人共享 To/CC 字段，To 优先于 CC，失败项重试被移除。

### 🗑️ 移除
- `DatabaseInit`、所有 MyBatis mapper 接口（12）、`mybatis-config.xml`、所有 mapper XML（12），以及 MyBatis 依赖。
- 所有 JavaFX 代码和依赖（见上文“破坏性变更”）。

### 🐛 修复
- **无头 fat-jar 启动**：对齐 `logback-classic`/`logback-core` 版本（一对版本不一致会在首次 logger 初始化时于
  `JaninoEventEvaluatorBase` 崩溃）；为 `AutoConfiguration.imports` 添加 shade `AppendingTransformer`
  （Spring Boot 4 把 web/Tomcat 自动配置拆分到多个模块 jar —— 不合并的话内嵌 Tomcat 静默不启动）；emit `-parameters`
  以便 Spring MVC 解析 `@PathVariable`/`@RequestParam` 名称。
- `VirtualUserInitializer` 的原生 INSERT 现在在 `@Transactional` 内运行（原先会抛出
  `TransactionRequiredException`）。
- 原子 `.fyp` 打包：任何阶段的失败都不会留下 `.fyp`、`.tmp-*` 或暂存目录。
- 离线 Python 构建器现在打开可写项目工作区，通过宿主桥接传递完整的 `FileRef` 对象，报告翻译后的作业状态，
  停止失败的轮询，并执行真实的构建/部署取消，而不是仅改变 UI 状态。
- SQLite 上的邮件归档时间戳（含升级迁移）、字面量通配符搜索、账号/文件夹路径隔离、UTF-8 文件名限制，以及临时文件清理。

---

## [3.2.0] — IDEA 2025 新外观重设计

**v3.2.0** — 2026-06-30

本版本把应用外观从 glassmorphism-dark 重新换皮为 JetBrains **IDEA 2025 新外观**：一个扁平的、基于 token 的主题，
带可切换的**深 / 浅**主题、可折叠侧边栏，以及原生 OS 窗口装饰。主题由 JavaFX looked-up color token（`-sk-*`）
驱动，按主题声明在 scene root 上，因此切换主题只是交换根 class —— 无需重载样式表。

### ⚠️ 破坏性变更

- **`.glass-*` CSS 工具类改名为 `.sk-*`**（在 `fengyu-common.css` 中）。调用 `getStyleClass().add("glass-...")`
  或引用 `.glass-*` 选择器的外部插件必须更新。完整映射：

  | 旧 | 新 |
  |---|---|
  | `glass-dialog` | `sk-dialog` |
  | `glass-field` / `glass-field-label` | `sk-field` / `sk-field-label` |
  | `glass-tab-pane` | `sk-tab-pane` |
  | `glass-combo` | `sk-combo` |
  | `glass-table` | `sk-table` |
  | `glass-checkbox` | `sk-checkbox` |
  | `glass-btn-primary` / `glass-btn-secondary` | `sk-btn-primary` / `sk-btn-secondary` |
  | `glass-notif-*` | `sk-notif-*` |

  > 外部插件仓库（[`MuskStark/FengYu-Plugin`](https://github.com/MuskStark/FengYu-Plugin)）单独更新；迁移第三方插件时请标注此改名。

### 🎨 主题（严格 token 化）

- **Token 集从 14 扩展到 19** —— 新增 `-sk-shadow`、`-sk-scrim`、`-sk-success-soft`、`-sk-warning-soft`、
  `-sk-danger-soft`（每个都在 `.theme-dark` 和 `.theme-light` 下）。硬编码了旧 14 个的自定义主题/样式表必须补上这 5 个，
  否则弹窗/对话框/卡片将出现未定义的阴影和状态软填充。
- **修复弹窗渲染为未主题化的白色** —— `GlassNotification`（toast/notify/confirm）加载了样式表，但从未在其 scene 上盖章
  主题 class，因此每个 `-sk-*` token 都未定义，所有弹窗在两种主题下都回退为 JavaFX 默认白色。通过 `Themes.applyTo(scene)`
  根因修复。
- **从弹窗、对话框、`StepWizard`、`ToggleSwitch`、状态标签和 CSS 投影中移除所有硬编码颜色**。现在一切都通过
  `-sk-*` token 解析，并正确适配深色和纯白（浅色）主题。 notably `StepWizard` 的空闲圆点和 `ToggleSwitch` 的关闭轨道
  在浅色主题上原本不可见。

### ✨ 新增

- **深 / 浅主题系统** —— `fan.summer.api.theme.ThemeService`（API 模块，无 DB 依赖）持有激活的
  `Theme.DARK`/`Theme.LIGHT`，在每个已注册的 scene root 上盖章 `theme-dark`/`theme-light` class，并触发
  `onChange` 监听器。可从侧边栏页脚（☀/☾）和设置页切换；持久化在 `theme` 设置中（默认 `dark`）。
- **Looked-up color token**（`-sk-bg`、`-sk-bg-elevated`、`-sk-text`、`-sk-accent`、`-sk-border`、……）按主题声明在
  `fengyu-common.css` 中；交换根 class 即可重新解析每个 token，无需重载样式表。
- **可折叠侧边栏** —— `«`/`»` 在标签视图和 48px 图标条之间切换；折叠状态通过 `sidebar.collapsed` 设置持久化。
- **原生窗口装饰** —— `StageStyle.DECORATED` 给出真实的 OS 标题栏 + 关闭/最小/最大化（macOS 红绿灯），取代自定义的
  透明窗口。
- `MarkdownRenderer.render(md, Theme)` / `renderPlain(md, Theme)` 重载（主题感知的深/浅 CSS 调色板）；无参形式通过
  `ThemeService.current()` 委托。

### ♻️ 变更

- `fengyu-common.css` 重写：token 定义在 `.theme-dark`/`.theme-light` 下，每个组件被扁平化为 IDEA 新外观风格
  （带左侧 accent bar 的中性灰选区、4–8px 纤细滚动条、扁平的字段/按钮/表格/标签页/对话框/通知），所有 `.glass-*` →
  `.sk-*`。
- `shell.css` 基于新外观外壳重写为 token 化（`.app-root`、`.sidebar` + `.collapsed`、胶囊形 `.search-bar`、扁平的
  `.tool-card`、`.detail-panel`、`.statusbar`、`.store-*`）。
- `Themes.applyTo(scene)` 现在委托给 `ThemeService.registerScene(scene)`（加载公共样式表 + 盖章主题 class）；
  共享样式表的加载被抽取为 `Themes.loadCommonStylesheet(scene)`，使委托不递归。
- `FengYuApp` 在启动时读取持久化的主题，并把主 scene 注册到 `ThemeService`。
- `AiChatPlugin` 从激活主题派生其 WebView 背景，并在主题改变时实时重渲染会话。
- 侧边栏和 Markdown 链接 CSS 中的内联 `#5b8cf7` accent 字面量被替换为 `#3574F0` / 深色调色板。

### 🔥 移除

- `fan.summer.ui.titlebar.TitleBar` —— 被原生 OS 窗口装饰取代。
- `fan.summer.ui.util.WindowResizeHelper` —— 原生 `DECORATED` 的缩放/拖动/最大化取代了它；macOS
  `isMaximized()`-on-`TRANSPARENT` 的 bug 随之消失。

---

## [3.1.0] — LangChain4j ChatBackend + 插件自有的 AI 工具

**v3.1.0** — 2026-06-25

本版本在 LangChain4j 上重建 AI 子系统，把两个云提供方（OpenAI + Anthropic）统一到新的 `ChatBackend` 接口背后的单个
`CloudChatBackend` 类中。插件现在可以自声明它们自己的 AI 工具。本地工具调用模型是 Qwen3-4B（Hermes `<tool_call>` + 流式
`<think>` 推理），运行在一个加固的进程外 worker 中。

### ⚠️ 破坏性变更

- **`AiService` 接口移除** —— 被 `ChatBackend` 取代。调用 `AiServiceProvider.getService()` 的外部插件必须把返回类型从
  `AiService` 改为 `ChatBackend`。迁移指南见 `docs/migration-3.1.md`。
- **`OpenAiService` 和 `AnthropicService` 具体类移除** —— 被一个带 `openAi(...)` / `anthropic(...)` 静态工厂的单一
  `CloudChatBackend` 类取代。一个统一类服务两个提供方。
- **`CloudAiConfigProvider` 和独立 `StreamingResponseHandlerBridge` 移除** —— 其逻辑移入 `CloudChatBackend`
  （配置访问器是类上的公开方法；流桥接是一个私有内部类）。
- **`AiServiceImpl` 改名为 `LocalChatBackend`** —— 纯改名，无行为变化。
- **`BuiltinAiToolRegistrar` 移除** —— 插件现在通过 `FengYuPlugin.aiTools()` 自注册 AI 工具；中央注册器及其启动调用已消失。

### ✨ 新增

- **插件通过 `FengYuPlugin.aiTools()` 自声明 AI 工具** —— 仓库在添加/移除时自动注册/注销它们（含 JAR 热重载）。
  无中央注册器。
- `AiTool` 接口声明按模式可见性（`supportsLocal` / `supportsCloud`）和双重描述
  （`getDescription` / `getLocalDescription`）；`AiServiceProvider.getTools()` 按激活的后端模式过滤。
- `AiToolDescriptions` 助手集中管理 cloud 丰富 / local 简洁的描述模板。
- **Qwen3-4B 本地工具调用** —— Hermes `<tool_call>` 解析（`ToolCallParser`）、`ThinkingStreamSegmenter`
  （THINK / CONTENT / tool-call 流拆分）、`Qwen3Adapter`（Hermes 系统提示词 + `/no_think` 切换），以及聊天 UI 中的
  可折叠思考卡片。
- 新 `ChatBackend` 接口（`fan.summer.api.ai.ChatBackend`）。
- 新 `CloudChatBackend` 类，带 `openAi(...)` / `anthropic(...)` 工厂。
- `LocalChatBackend`（从 `AiServiceImpl` 改名而来）。
- `AiToolCall.of(id, name, arguments)` 重载，在从 LangChain4j 桥接时保留服务端签发的工具调用 ID。
- 测试：`CloudChatBackendTest`（11）+ `ChatMessageMapper` / `AiToolToToolSpecification` 的适配器测试；
  `ThinkingStreamSegmenterTest`（11）+ `LocalChatBackendMaxTokensTest`（3）。
- 迁移指南位于 `docs/migration-3.1.md`（EN + ZH）。

### ♻️ 变更

- 全部 16 个内置 AI 工具返回标准化 JSON `{success, summary, ...payload}`；工具描述遵循 cloud 丰富 / local 简洁的
  双重模板。
- `BuiltinToolRegistrar.register()` 经 `PluginRegistry.addPlugins` 路由，以便一次性自动注册插件 AI 工具。
- **`FengYu-Api` 中统一 `ChatBackend` 接口** —— non-sealed（Java 禁止跨模块 sealed permits）。两个已知实现：
  `CloudChatBackend`、`LocalChatBackend`。UI 消费方使用 `instanceof` 检查；接口本身被视为不透明。
- **`CloudChatBackend` 把 OpenAI + Anthropic 统一在一个类中**（约 450 行）。HTTP/SSE、工具循环管道和流桥接委托给
  LangChain4j 的流式模型；提供方差异隔离到一个对内部 `Provider` 枚举做 switch 的 `buildStreamingModel(...)` 中。
- `SynchronousChatHelper`（浏览器规划器）重写为通过 `CloudChatBackend` 配置访问器直接使用 LC4j 的同步
  `OpenAiChatModel`。
- `AiServiceProvider` 到处暴露 `ChatBackend`（方法名不变）。
- 采样参数（temperature / topP / maxTokens）按调用生效 —— 设置更改在下一条消息生效，无需重启聊天。
- 默认 `maxTokens` 从 512 提升到 2048（Qwen3 思考模型的下限），在 `chat()` 入口统一强制一次，使原生和 Java 后端都受益。

### 🐛 修复

- **Qwen3 静默空答案** —— 一个思考模型在 `mid-<think>` 被截断会产出空答案，因为 `stripThink` 抹掉了未闭合的块。
  `maxTokens` 预算现在在统一 `chat()` 入口被下限到 `QWEN3_MIN_MAX_TOKENS`（2048），并在输出仅以思考块幸存时给出
  诊断警告。
- **Java 后端上的 Qwen3** 会把原始 `<think>` 标签泄漏进答案 —— 现在经 `ThinkingStreamSegmenter` 路由
  （思考 → 可折叠卡片）并从最终答案/历史中剥离，与原生路径一致。
- **`AiConfigService.getAiMaxTokens()` 默认值**同步为 2048（原为陈旧的 512，与设置 UI 不一致）。
- **AI worker IPC** —— 子进程固定一个专用 `logback-worker.xml`（无 `ConsoleAppender`），使 worker 日志不再污染
  stdout 上的行分隔 JSON 管道；stderr 在自己的线程上被排空到共享日志。
- **AI worker 原生加载** —— 子 JVM 在启动时加载 llama.cpp 库（`NativeLoader.load()`），使 `LlamaContext` 构造不再抛出
  “Native library not loaded”。
- **AI worker 崩溃恢复** —— `handleChildExit` 等待真实退出码，而不是在 stdout EOF 上抛出
  `IllegalThreadStateException`，因此待处理回调被释放，自动重启可靠运行。
- **Qwen3.5 混合模型警告** —— 文件名匹配 `qwen3.5` / `qwen35` 现在警告原生 worker 在多轮上会 SIGABRT
  （请用 Qwen3-4B）。
- **macOS 上云 `testConnection()` 空消息 bug** —— 带有 `null` 消息的 `ConnectException` 现在回退到
  `e.getClass().getSimpleName() + ": " + e`。
- **Anthropic 多轮工具调用** —— 服务端签发的 `tool_use_id` 在 `AiToolCall → LangChain4j → AiToolCall` 往返中被保留
  （原先在第 2 轮导致 HTTP 400）。
- **多轮对话连续性** —— 助手的最终回复在服务返回前被追加到 `history`。
- **OpenAI 工具轮消息顺序** —— assistant-with-tools 消息在 `ToolExecutor.executeAndFeed` 之前被追加。
- `pdf_merge.filePaths` 参数类型修正（`"array"` → `"string[]"`）；为 `base64.mode`、`hash_calculate.algorithm`、
  `color_convert.from/to` 声明 enum。
- `ToolExecutor` 错误输出始终是 JSON `{success:false,error:...}`；`ExcelConfigureTool` 成功返回 `success:true`。
- `testConnection()` 的 `HttpClient` 用 try-with-resources 包裹；云流处理器上的线程安全加固。

### 🔥 移除

- `BuiltinAiToolRegistrar` —— 被插件自有的 `aiTools()` 取代。
- FunctionGemma 适配器和 `OfflineNlNormalizer` —— 被 Qwen3 路径取代。

### ⬆️ 依赖

- `dev.langchain4j:langchain4j-open-ai:1.2.0`
- `dev.langchain4j:langchain4j-anthropic:1.2.0`
- （原本固定 1.0.1，但 `langchain4j-anthropic` 从未在该版本发布过；提升到两个模块共存的最低 GA 版本）

### ⚠️ 已知行为变更

- 云后端上的 `cancelGeneration()` 是尽力而为（LangChain4j 1.x 不在流式模型上暴露中途取消）；进行中标志仍被清除。
  本地模式不受影响。
- 流中途 SSE 错误现在通过 `callback.onError` 在 JavaFX 应用线程上呈现。
- 本地工具调用模型是 Qwen3-4B；原生 worker 在附带 GPU 后端的构建上自动请求完整 GPU 卸载。

### 📉 代码净变化

- 删除：`AiService`（117 行）、`OpenAiService`（244 行）、`AnthropicService`（283 行）、`CloudAiConfigProvider`
  （22 行）、`StreamingResponseHandlerBridge`（120 行）、`StreamingResponseHandlerBridgeTest`（214 行）、
  `BuiltinAiToolRegistrar`、FunctionGemma 适配器 + `OfflineNlNormalizer` ≈ **删除 1000+ 行**。
- 新增：`ChatBackend`（86 行）、`CloudChatBackend`（450 行）、Qwen3 工具链（`ThinkingStreamSegmenter`、
  `Qwen3Adapter`、`ToolCallParser`）、worker 加固、测试、迁移指南 ≈ **新增 1100+ 行**。
- 净值：行数大致持平，但云代码是一个统一类，本地 AI 拥有专用工具调用模型 + 隔离 worker。

---

## [3.0.1] — FunctionGemma 离线适配

**v3.0.1** — 2026-06-21

### ✨ 新功能

- **FunctionGemma 多轮工具循环**：针对 FunctionGemma-270m-it 本地模型的宿主驱动 `analyze → configure → execute`
  循环；调用轮次期间工具调用 token 被抑制，只有最终响应被转发给 UI
- **离线中→英关键词归一化器**：`OfflineNlNormalizer` 在本地模型解析前把中文工具名关键词改写为英文，无需联网
  （资源支撑的 `nl-normalizer.properties`）
- **enum-schema 工具参数**：`AiToolParam` 新增 `enumValues` 字段；工具声明现在向 FunctionGemma、OpenAI 和 Anthropic
  后端发出 `enum:[...]` 约束 —— 实质性地提升小模型的参数可靠性
- 丰富了 Excel AI 工具描述，并在 `mode`/`action` 参数上添加 enum 约束

### 🐛 修复

- 加固 `FunctionGemmaAdapter` 解析器：🪙（U+1FA99）字符串分隔符能正确处理含逗号、大括号以及单次响应中多个工具调用的值
- 卸载时通过尽力而为的 `unmap` 释放 `GGUFModel` mmap
- 加固 `GGUFReader` 抵御畸形或截断的模型文件
- 在单线程调度器上串行化 `PluginLoader` 的 JAR 加载/卸载
- 在 prefill 期间被取消时干净地完成 `LlamaRunner` 生成
- 把 `TokenBatcher` 的 flush 驱动移出 FX 线程
- 在强杀前让原生 AI worker 优雅退出
- 即使 copy/write 抛出也关闭目标 POI `Workbook`
- 低优先级稳定性清理（MDI 字体日志、守护 UI 线程）

---

## [3.0.0] — JavaFX 迁移

**v3.0.0** — 2026-06-12

- 为 v3.0.0 发布更新应用图标
- 解决跨代码库的静态分析告警（Qodana）

**v3.0.0-rc.3** — 2026-06-10

- **斜杠命令**：在 AI 对话中输入 `/` 列出可用工具、获取某工具的帮助，或无需模型推理直接调用某工具 —— 同时支持
  直接执行和引导式模型参数抽取
- **插件资源隔离**：外部插件使用 child-first `ClassLoader`，确保插件资源先从插件 JAR 再从宿主解析；`PluginContext`
  在每次插件生命周期调用和事件分发上提供 TCCL 切换
- **插件商店重设计**：在线插件商店的可搜索、可过滤卡片网格，带安装状态指示器和版本比较
- **AI 配置服务**：抽取的 `AiConfigService` 集中化 AI 配置访问，使其与 UI 设置代码解耦
- **邮件归档**：新增 `email_archive` 表、实体和 mapper，用于邮件归档存储
- 修复侧边栏图标在 Windows 上不显示 —— 从 JavaFX `Font` 图标改用 MDI web 字体
- 修复邮件设置保存总是失败；现在显示缺失的必填字段名
- 修复 Excel 复杂拆分第 3 阶段破坏既有输出文件 —— 只合并拆分操作期间创建的文件
- 修复数据格式字符串为 null 时跨工作簿单元格样式克隆中的 POI `NullPointerException`
- 用 null 守卫加固 Excel 拆分器进度回调
- 从 `OnlineStorePane` 抽取 `StorePlugin` 和 `StorePluginLogic` 并附带单元测试
- 向仓库添加 GPLv3 许可证文件
- 向 `FengYu` 模块添加 JUnit 5 测试依赖

---

**v3.0.0-rc.2** — 2026-06-05

- **工具收藏**：在工具卡片和详情面板用星标切换收藏工具；收藏通过 H2 数据库跨重启持久化，并可从侧边栏“收藏”分类过滤
- **懒加载 AI 后端**：本地 AI 后端（原生/Java）初始化推迟到首次打开 AI 工具时，改善启动性能；AI 设置中有
  Java/原生推理引擎切换
- **插件卸载**：从详情面板带确认对话框卸载外部插件；关闭 ClassLoader、移除 JAR 文件并从仓库清理
- **安装 Toast 通知**：从在线商店或本地 JAR 安装插件时的成功 toast 通知
- **token 批处理**：AI token 输出按 50ms 间隔批处理，以减少高速生成期间的 FX 线程洪泛
- **崩溃限流**：原生 worker 自动重启遵守时间窗口（5 分钟内 3 次崩溃）以防止重启风暴
- **设置缓存**：应用设置缓存在内存中，带防抖 DB 写入（300ms），以降低快速 UI 交互期间的数据库负载
- 修复加固 Linux 发行版（UOS/Deepin/Kylin）上对未签名 `.so` 文件抛出 `SecurityException` 的原生库加载
- 修复邮件群发在迭代间修改共享收件人列表
- 修复在线商店插件目录解析 —— 用基于 Gson 的 `JsonHelper` 取代手写的字符串切片
- 修复 `WindowResizeHelper` 双重附加导致重复事件过滤器
- 跨 `PluginLoader`、`PluginRegistry` 和 `MainWindow` 的线程安全加固
  （`ConcurrentHashMap`、`volatile`、`synchronizedSet`）
- 限制工具卡片进入动画的错峰上限（最多 30），避免创建数百个 `PauseTransition` 实例
- 修复 Windows 上插件 JAR 删除 —— 用 `System.gc()` 提示重试，若文件仍被锁定则回退到 `deleteOnExit()`
- 修复卸载插件 JAR 时 `onUnload()` 生命周期回调未触发
- 修复卸载非激活插件时缓存插件视图未清除，阻止插件类被 GC
- 修复英文 locale（`Locale.ENGLISH`）在中文 locale 系统上返回中文字符串 —— `ResourceBundle` 不再回退到 JVM 默认
  locale
- 修复 Windows 无 JRE 发布 zip 冗余地与 Launch4j exe（已内嵌它）并排包含 fat JAR

---

**v3.0.0-rc.1** — 2026-06-04

- **浏览器自动化**：AI 可调用的 `browser_automate` 工具，通过自然语言自动化 web 浏览器；使用 Playwright 配合系统已安装的
  Chrome/Edge/Chromium（无需单独下载浏览器）；带页面 DOM 快照、CSS 选择器定位和规划 LLM 的 observe-think-act 循环
- **可缩放窗口**：通过 `WindowResizeHelper` 对无装饰 `StageStyle.TRANSPARENT` 窗口的边缘和角落拖拽缩放；使用屏幕坐标以
  兼容 macOS
- **响应式布局**：动态 `FlowPane` 换行长度绑定到视口宽度；`windowPane` 和 `ContentArea` 通过
  `setMaxWidth/Height(Double.MAX_VALUE)` 正确填充父级
- **纯 Java PDF 转 DOCX**：`PdfBoxToDocxConverter`，用 PDFBox 提取并用 Apache POI 生成 DOCX —— 无需外部 Office 安装；
  三级页面策略（文本 → 提取的图片 → 全页渲染回退）
- **原生后端健康追踪**：`NativeLoader.FailureReason` 枚举用于结构化失败诊断；原生加速不可用时 AI 对话中的降级模式横幅
- 修复 macOS 上窗口缩放因 `StageStyle.TRANSPARENT` 下不可靠的 `stage.isMaximized()` 而失效
- 修复工具网格布局不响应窗口宽度变化
- 修复 Playwright 运行时不必要地尝试下载浏览器驱动
- 修复 AI 浏览器规划器通过工具注入循环递归调用 `browser_automate` 工具

---

**v3.0.0-beta.2** — 2026-05-26
