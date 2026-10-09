---
title: 桌面端
description: Infinia 4.0.0 桌面外壳是一个 Electron 43.x 应用（productName Infinia，版本 4.0.0，TypeScript 主进程），在环回地址上拉起 Java 后端，监管 SETUP 到 APP 的切换，通过 contextBridge 向渲染器暴露 API，并掌管窗口、托盘、日志与自动更新器。
lang: zh-CN
---

# 桌面端

Infinia 桌面外壳是一个用 TypeScript 编写（主进程）的 **Electron 43.x** 应用。它的职责是进程监管：拉起 Java 后端、发现它的端口、驱动它从 SETUP 进入 APP 模式、把 UI 所需的凭据交给它，并在用户退出时把一切拆除。产品名为 **Infinia**，版本 **4.0.0**（见 `desktop/electron/package.json`，`productName: "Infinia"`）。后端生命周期与之前的 Tauri 外壳**保持不变**——被替换的只是实现它的外壳本身。

## 开发版与发布版

外壳的行为取决于是否已打包：

| Profile | 后端 | 窗口 |
| --- | --- | --- |
| **Dev — 外部**（默认；`!app.isPackaged`，无 env 或设置了 `FENGyu_DEV_BACKEND`） | 无——连接你自行启动的后端（IDE / `mvn spring-boot:run`）`http://127.0.0.1:24056`。不拉起、不生成 token、无监管。 | Vite 就绪即打开；应用内启动屏扣住直到外部后端 `/api/health` 可达 |
| **Dev — 自拉起**（`!app.isPackaged`，设置了 `FENGyu_JAR` 或 `FENGyu_DEV_BACKEND=disabled`） | 由外壳以 jar sidecar 方式拉起，使用 `FENGyu_JAR` 指向的 jar | Vite 就绪即打开，加载 `localhost:5173`，启动屏覆盖整个后端启动过程 |
| **Release**（`app.isPackaged`） | 由外壳以 jar sidecar 方式拉起 | 启动一开始即打开（先于拉起），加载内嵌的 SPA 并由其启动屏扣住 |

默认情况下，`yarn run dev` 连接你在 IDE 中**不带** `--token=` 启动的后端——此时 `TokenAuthFilter` 禁用认证，外壳传入空 token，与 SPA 的空 token 回退一致。外壳不拉起 java、不生成 token、不运行 SETUP→APP 监管；后端的生命周期由你掌控。如果你带 `--token=<t>` 启动了后端，也需设置 `FENGyu_TOKEN=<t>`。要指向其他端口，设置 `FENGyu_DEV_BACKEND=http://127.0.0.1:<端口>`。

要让外壳自行拉起后端（自包含开发），设置 `FENGyu_JAR=<路径>`（或 `FENGyu_DEV_BACKEND=disabled`）：拉起 jar、生成单次启动 token、运行健康检查 + 监管——完整的发布生命周期，只是从开发版 Vite 服务器加载。该路径下 `FENGyu_JAR` **必填**（未设置时外壳会抛出 `Dev mode requires FENGyu_JAR...`）。Vite 开发服务器（端口 5173）会把 `/api` 代理到当前生效的后端，开发者也可借此单独在浏览器中运行前端。在发布模式下，外壳端到端地掌管后端进程。

## 后端拉起（发布版）

发布版以一种固定的命令形式拉起打包好的 jar（从旧的 Rust 实现逐字移植）：

```bash
java -cp <jar> \
     fan.summer.fengyu.HeadlessLauncher \
     --port=24056 \
     --token=<t>
```

外壳读取子进程的 stdout 寻找 `FENGYU_PORT=<n>` 这一行，期限为 **120 秒**（可取消，因此缓慢启动期间关闭窗口不会挂起）。端口行要等到 `WebServerInitializedEvent`（即整个 Spring 上下文构建完毕）才会输出，慢速硬件（UOS 实测冷启动约 39 秒）可能远超半分钟，因此期限必须宽松；JVM 崩溃则通过 stdout 关闭立即失败，不会拖满期限。如果该行在期限内没有出现，启动即告失败。启动窗口期的后端 stdout（端口行之前的全部输出）与后端 stderr（子进程整个生命周期）以
`[backend]`/`[backend-err]` 前缀记入 `<运行目录>/.fengyu/logs/desktop.log`；运行期的后端日志在 `fengyu.log`。

上文的 `<运行目录>` 是外壳的**运行时锚点**（`bootstrap-cwd.ts`），打包版本按平台选取：

- **Windows** —— 可执行文件所在目录：NSIS 安装版的安装根目录、便携 ZIP 的解压目录。整棵
  `.fengyu` 树（配置、内嵌数据库、日志、插件、技能、聊天数据）随应用走，与 Web 发行版的
  `<解压目录>\data` 一致。早期版本遗留在 `%APPDATA%\fengyu-desktop\.fengyu` 的运行时树会在首次
  可写启动时自动迁移过去（同盘原子重命名；跨盘安装先复制到 `.fengyu.migrating` 暂存目录再原子
  改名落位，中断的复制不会留下半成品树，下次启动从完好的旧树重试）。迁移只由持有单实例锁的
  实例执行；安装目录不可写（如未提权的 Program Files）时保持原有的 `userData` 锚点，遗留树
  不会被挪走。
- **macOS / Linux** —— Electron 的 `userData` 目录（`~/Library/Application Support/…` /
  `~/.config/…`）。macOS 的 .app 包内不能存放用户数据（zip 自动更新会整体替换 .app），而
  Linux AppImage 的可执行路径是只读的临时 squashfs 挂载点。

开发运行保持自己的工作目录（`desktop/electron/`）；UOS 版本则按下文所述重新锚定到用户主目录。Electron 自身的配置数据（浏览器分区、Local Storage、更新缓存）始终留在操作系统的用户数据目录（Windows 上为 `%APPDATA%\fengyu-desktop`）——移动的只是 FengYu 的运行时树。

Java 在运行时解析：**带 JRE** 版本优先使用 `<resourcesPath>/jre/bin/java`；**不带 JRE** 版本使用 `PATH` 中的 `java`。若找不到 `java`，外壳会弹出一个原生错误对话框并退出。

## 健康检查与初始化编排

主窗口**最先创建——先于后端拉起**——由应用自身的加载面接管此前由独立启动卡片覆盖的整个启动过程（启动卡片已彻底移除：没有第二个窗口，也没有交接）。主窗口的入口文档自带一层**静态启动壳**（`frontend/index.html` 中的 `#boot-loading`：内联品牌标与巡游进度轨道，零脚本，因此不改变 CSP 哈希集合），先于任何打包 CSS/JS 到达即完成首绘；React 首次 commit 时，`main.tsx` 的 `StartupReady` 向 `<body>` 添加 `fengyu-startup-ready`，将启动壳淡出、`#root` 淡入，随后移除启动壳。SPA 在启动门控（`shell/BootGate.tsx`，展示与 HTML 启动壳同一品牌标与同一进度轨道的 `StartupScreen`）后挂载：整个外壳被自身的 `/api/health` 轮询挡住，后端应答后判定 SETUP 模式并启用功能。细粒度启动进度复用失败恢复所在的 `boot:state` 通道：外壳推送 `{phase:'booting', stage}`，依次为 `spawning` → `port-ready` → `health-ready` → `loading-ui`，`StartupScreen` 把每个阶段渲染为进度轨道上方的本地化文案（渲染端先订阅、再拉取一次 `boot:get-state`——对未加载页面的推送是丢弃而非排队）。门控 → 应用的交接为交叉淡出：App 让 BootGate 以固定定位、指针穿透的覆盖层形态保持挂载，在新挂载的应用外壳之上淡出；包裹元素在切换前后保持稳定，因此启动屏正在运行的动画（轨道巡游、阶段文案）在淡出中无缝延续（减少动效模式下跳过淡出）。

由于入口文档先于后端端口可知而加载，端点在首次加载时无法走 preload 的环境变量快照：外壳在拉起解析出端口的那一刻，经 `endpoint:ready` 通道（`ipc/endpoint.ts`）推送 `{apiBase, token}`；渲染端 platform 层优先采用该实时推送而非快照（axios 客户端按请求解析 baseURL，因此启动门控的健康轮询无需重载即可用上新端点；启动完成后的页面加载重新读取环境变量快照）。端点未知期间，响应头 CSP 放行环回通配（`http://127.0.0.1:*` / `http://localhost:*`）——与入口文档自身 meta CSP 已授予的基线一致——替代精确的后端 origin。与此同时，外壳与渲染端并行地驱动后端经过三个阶段：

1. **`wait_for_health`**——以 **300 毫秒**为间隔、**每次请求 2 秒超时**、**总体 120 秒**为期限，带上 `X-FengYu-Token` 头轮询 `GET /api/health`。只有 HTTP 200 才算就绪。使用 Node 24.18 内置的 `fetch` + `AbortController`。
2. **`check_setup_mode`**——探测 `GET /api/setup/status`，以判断后端启动进入了 SETUP 还是 APP 模式（响应体含 `"initialized":false` → SETUP）。
3. **`run_backend_until_app_mode`**——把整个循环串起来：（创建窗口）→ 拉起 → 等待健康 → 检查初始化模式。等待期间后端退出会立即失败（不会让一个已死的 JVM 挂满整个期限）。如果后端处于 SETUP 模式，外壳会等待该进程以退出码 `0`（`SETUP_DONE`）退出，然后**重新拉起**后端，此时它会带着已生效的数据源以 APP 模式重新启动。重新拉起后，外壳会校验端口未改变、且后端已进入 APP 模式；任一不满足即视为致命错误。

整条启动链路以 **T0–T6 埋点**计时——T0–T3 由主进程记录（`desktop/launch-marks.ts`：进程创建、主包执行、`whenReady`、主窗口加载），T4–T6 由渲染端记录（`shell/launch-perf.ts`：bundle 执行、React 首次 commit、进入应用外壳）。渲染端到达 T6 时经单向 `perf:launch-report` IPC 上报，主进程把合并后的 `[perf] launch …` 一行写入 `desktop.log`，与其他启动耗时日志并列（首次安装的 SETUP 流程不计入）。

**后端等待期间的启动失败可在应用内恢复。** 后端退出、健康等待超期、setup 探测损坏不再以原生对话框 + 退出了事：外壳推送 `boot:state {phase:'failed', reason, exitCode, detail}`（`ipc/boot.ts`），启动门控切换到 `StartupFailureScreen`——失败原因 + 退出码 + **重试 / 打开日志 / 复制诊断 / 退出**。重试复用同一 token 与端口（渲染端端点不可变更），先强制终结残留 JVM 进程树再重新拉起，并用退出监听与健康等待竞速，让死掉的重试立刻失败而非空等期限。失败可能在页面尚未加载完成时就发生（对未加载页面的 `webContents.send` 是丢弃而非排队），因此渲染端先订阅、再主动拉取一次当前状态（`boot:get-state`）。失败屏挂载即回执可见性（`boot:failure-visible`）；15 秒内未收到回执的失败回退为与旧版完全一致的原生对话框 + 退出（渲染端从未加载 / SPA 损坏的情形）。桌面端的启动门控如今也真正**扣住**启动屏直到后端健康——挂载时的 setup 探测面对的是尚未启动完成的后端、必然失败，由门控的健康轮询接管并在成功后重新判定 SETUP / APP；浏览器端保持原来的直落应用外壳行为。

## 前端 bridge（contextBridge）

外壳的 preload 脚本在页面加载前，通过 `contextBridge` 在 `window.fengyu` 上暴露一个受控的 API：

```js
window.fengyu.apiBase()        // 'http://127.0.0.1:<port>'——环境变量快照；首次加载时为空（窗口先于拉起解析端口而创建）
window.fengyu.onEndpoint(cb)   // 实时端点交接：端口解析出后回调 cb({apiBase, token})（endpoint:ready 推送）
window.fengyu.getEndpoint()    // → Promise<{apiBase, token} | null>——拉取最近一次推送的端点（补上与页面加载竞速的推送）
window.fengyu.token()          // 每次启动的 X-FengYu-Token——环境变量快照；platform 层优先采用实时端点推送
window.fengyu.desktop          // true——特性标志
window.fengyu.initialTheme()   // 'dark' | 'light'——外壳在启动时确定的主题（避免闪烁）
window.fengyu.setupMode()      // boolean | null——预先探测的 setup 状态；首次启动为 null（SPA 在启动门控处自行探测），浏览器中也为 null
window.fengyu.setTheme(theme)  // 请求外壳持久化/应用主题
window.fengyu.pickFile(filters)   // → 原生打开对话框（IPC）
window.fengyu.pickDirectory()     // → 原生打开对话框（IPC）
window.fengyu.openExternal(url)   // → 在系统浏览器打开校验后的 http(s) URL（IPC）
```

`apiBase`/`token` 是在页面加载时捕获的**环境变量快照**（首次加载时可能为空——见上文的 `endpoint:ready` 实时交接）。SPA 直接通过环回地址与后端通信——AI 对话的 SSE 流、文件上传、插件微前端宿主都需要原生的 `fetch`/`EventSource`/`FormData`，而 IPC 无法承载这些，因此令牌以快照形式暴露，而非隐藏在完整的 IPC 代理背后。该令牌每次启动重新生成、仅限环回地址，且后端无论如何都强制执行 endpoint ACL。这取代了旧的 Tauri `window.__FENGYU_*` 全局变量。React SPA 通过 `connection` store 与 `src/platform` 层读取它们来配置每一次 API 调用。在普通浏览器中 `window.fengyu` 为 `undefined`，因此 Web 模式会回退到环境变量。见[前端](/zh/architecture/frontend)。

云账号登录使用 `openExternal`：无头后端启动 PKCE 尝试并返回 authorization URL，renderer
再请求 Electron 打开它。主进程会重新解析 URL，在调用 `shell.openExternal` 前拒绝除
`http:`、`https:` 以外的所有 scheme。普通浏览器模式则打开新标签页。

**BrowserWindow 安全姿态：** `contextIsolation: true`、`nodeIntegration: false`、`sandbox: true`、`webSecurity: true`（默认）——标准的 Electron 安全姿态。CSP 由后端的 SPA 响应头治理，主进程中不会设为 `null`。

## 桌面增强能力

旧的 Tauri 外壳所不具备的四项能力：

- **单实例锁**——`app.requestSingleInstanceLock()`。再次启动会显示并聚焦已有窗口（也会从托盘恢复）。
- **系统托盘**——图标从旧外壳迁移而来；菜单：显示 / 隐藏 / 退出。驱动下文的关闭语义。
- **文件日志**——`electron-log` 把主进程日志写入
  `<运行目录>/.fengyu/logs/desktop.log`（与后端日志同目录），同文件还收纳后端启动 stdout（`[backend]`）、
  后端 stderr（`[backend-err]`）与转发来的渲染进程报错（`[renderer]`），5 MB 滚动。更新管线在同一目录维护
  `update.log`（2 MB 轮转）。
- **自动更新**——`electron-updater`，源为 GitHub Releases（`latest*.yml` 由 electron-builder 生成）。在 `app.whenReady()` 之后做非阻塞检查。自动安装（下载 + `quitAndInstall`）以**已签名发行版**为门禁（`FENGYU_SIGNED_RELEASE=true`，由未来的签名+公证构建注入）。当前构建为未签名，因此发现更新时只**通知**用户并提供打开手动下载页——绝不调用安装器，因为仅凭 GitHub feed 无法校验发布者（尚无 OS 代码签名 / macOS 公证）。

## 关停语义（已变更——重要）

由于引入了托盘，后端的生命周期现在绑定到**应用退出**，而非窗口关闭：

| 动作 | Tauri（旧） | Electron（新） |
| --- | --- | --- |
| 窗口关闭按钮 | 杀死后端并退出 | **隐藏到托盘**，后端保持存活 |
| 托盘「退出」/ Cmd+Q / Alt+F4 | 不适用 | 杀死后端（SIGTERM，兜底 SIGKILL）并退出 |

主进程在 `before-quit` 事件（而非 `window.on('close')`）时杀死后端。close 处理器在应用并非真正退出时会调用 `preventDefault()` + `window.hide()`。

## 窗口与对话框集成

- **窗口尺寸：** `1280 × 820`，最小 `960 × 640`（与之前的外壳一致）。
- **原生对话框：** `pickFile` / `pickDirectory` 通过 IPC 走 Electron 的原生对话框，并暴露在 `window.fengyu` 上；前端通过 `desktop.ts` 外观来访问它们。

### macOS 标题栏对齐不变量

渲染器接管的窗口栏高度为 **48 px**。在 macOS 上，原生红绿灯、侧栏折叠按钮与路由工具栏
控件必须共用 `y = 24` 中心线。`desktop/electron/src/window/create-window.ts` 中必须保留以下
BrowserWindow 组合：

```ts
frame: false,
titleBarStyle: 'hidden',

win.setWindowButtonVisibility(true)
win.setWindowButtonPosition({ x: 14, y: 18 })
```

不要把它简化为 `frame: false` 加 `setWindowButtonVisibility(true)`。使用默认标题栏样式时，
Electron 43 不会创建原生 `WindowButtonsProxy`；此时位置 API 虽会保存坐标，却没有代理负责
重绘，红绿灯仍停留在系统默认高度。`titleBarStyle: 'hidden'` 用来初始化该代理，而
`frame: false` 仍保留完全无边框的渲染器及其可交互 HTML 控件。

调用顺序同样是有意的：先恢复可见性，再应用坐标。当前 macOS 版本中的原生可见性更新可能
重新布局或重置按钮 frame。`y = 18` 的顶端内缩会把 12 px 原生按钮的中心放在 `y = 24`；
28 px 的 HTML 折叠按钮使用 `top: 10px`，因此中心线相同。

修改这项约束前，必须运行两组聚焦检查并观察真实 macOS 窗口——裸 JAR 冒烟测试不会经过
Electron 原生窗口装饰：

```bash
cd desktop/electron
yarn build:ts
yarn vitest run test/window-open-handler.test.ts

cd ../../frontend
yarn node --test test/sidebar-collapse.test.mjs

cd ../desktop/electron
yarn run dev  # IDE 后端运行于 :24056 时启动，并观察激活状态下的窗口
```

## 打包

打包由 **electron-builder** 处理（`desktop/electron/electron-builder.yml`）。每个平台发布两种安装包变体，由 CI 的 `--config` 覆盖从同一份基础配置构建。产物遵循统一命名 `<product>-<version>-<platform>-<arch>[<form>].<ext>`（例如 `Infinia-4.0.0-mac-arm64.dmg`、`Infinia-4.0.0-win-x64-setup.exe`）：

| 平台 | 不带 JRE（lite） | 带 JRE（自包含） |
| --- | --- | --- |
| macOS（arm64） | `Infinia-<ver>-mac-arm64.dmg` | `Infinia-<ver>-mac-arm64-jre.dmg` |
| Windows（x64） | `Infinia-<ver>-win-x64-setup.exe`（NSIS）+ `*-portable.zip` | `Infinia-<ver>-win-x64-setup-jre.exe` + `*-portable-jre.zip` |
| Linux（x64） | `Infinia-<ver>-linux-x64.AppImage` + `.deb` | `Infinia-<ver>-linux-x64-jre.AppImage` |

Windows 的**便携版**是解压即用的 ZIP（解压后直接运行 `Infinia.exe`）——无需安装，启动时也无需自解压。带 JRE 的变体在 `<resources>/jre/` 下内嵌一个 **jlink 最小化的** JRE（由 CI 从 JDK 25 通过 `jdeps` + `jlink --strip-debug` 生成），并附带一份 CI 训练的 **AOT 启动缓存**（`<resources>/jre/FengYu.aot`，由 `desktop/electron/scripts/train-aot-cache.sh` 在 jlink 之后生成），可将后端就绪时间缩短约一半；lite 变体与 CLI 启动则在首次成功启动后于后台自行训练同一份缓存（`src/backend/aot-cache.ts`）。Alpha 构建为**未签名**。

另有仅 Linux 的 **UOS（统信）变体**，产物为 `Infinia-UOS-<ver>-linux-x64.AppImage` + `.deb`（`desktop/electron/electron-builder.uos.yml`，基于 JRE、自包含）。它的启动入口在**真实命令行**上以 `--no-sandbox` 启动 Electron（`linux.executableArgs` 把该开关写入 deb 的 `/usr/share/applications/infinia-uos.desktop` 菜单快捷方式以及 AppImage 内嵌的 desktop 文件）——UOS 上 Chromium 依据进程 argv 决定沙箱行为，仅靠 JS 内添加开关并不可靠。deb 升级会覆盖旧版安装的无参数菜单快捷方式，postinst（`scripts/uos-deb-postinstall.sh`）刷新 desktop 数据库使菜单立即生效、无需重新登录。该变体同时把 `fengyu.uos: true` 烙入包元数据；主进程（`src/desktop/uos.ts`）检测到该标志即追加进程内 `appendSwitch` 兜底（覆盖绕过 desktop 文件的直接启动），并把工作目录重定向到用户主目录——UOS 非 root 环境严禁启动任何 OS 级沙箱，且从菜单启动时初始工作目录不可写。渲染进程自身的加固（`webPreferences.sandbox`、contextIsolation）不受影响。

## 下一步

- [后端](/zh/architecture/backend)——sidecar 实际在运行什么，以及外壳所驱动的 SETUP/APP 模式。
- [前端](/zh/architecture/frontend)——SPA 如何消费 `window.fengyu` bridge。
- [快速开始](/zh/quickstart)——`cd desktop/electron && yarn run dev` 与 `yarn run build`。
