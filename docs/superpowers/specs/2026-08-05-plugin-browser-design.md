# plugin-browser — AI 浏览器操作插件设计

- **日期**: 2026-08-05
- **状态**: 设计已确认,待实现
- **范围**: 新增官方插件 `plugin-browser`,为 AI 提供通用浏览器代理能力

## 1. 目标与非目标

### 目标
为 AI 提供**通用浏览器代理**:导航、点击、输入、读取内容、截图、等待元素、执行 JS。
覆盖三类用途:
- 网页抓取 / 数据提取
- 网页自动化 / 表单操作(点击、填表、提交)
- 通用浏览能力(截图、内容查询、JS 执行)

### 非目标(MVP 不做)
- 多标签页管理(单 Page 会话;协议向前兼容,后续可加 `browser_new_tab`)
- Cookie / 登录态管理 UI(profile 目录天然保留登录态,但不暴露管理界面)
- 录制与回放
- 浏览器画面内嵌到 FengYu 窗口(浏览器是独立窗口,对用户可见)
- headless 模式(默认有头;headless 参数留作后续扩展)

## 2. 已确认的关键决策

| 决策点 | 选定 |
|---|---|
| 引擎 | Playwright(父 pom 已管理 `playwright.version=1.49.0`) |
| 进程模型 | 官方插件 worker(JSON-RPC 2.0 over stdio) |
| 实现语言 | Java(`playwright-java`,复用 `JsonRpcWorker` SDK) |
| 显示模式 | 默认有头(headed),浏览器窗口对用户可见 |
| Chromium 来源 | **不打包进 .fyp**;首次使用自动下载到插件自己的 data 目录,用户可在 UI 指定自定义路径 |
| 工具粒度 | 方案 A — 细粒度原子工具(Playwright API 一对一映射) |
| 会话模型 | worker 进程内单 `BrowserContext` + 单 `Page`,跨多次 aiTool 调用复用 |
| Windows 兼容 | 复用宿主现有 `unsandboxedPlugins` 全局开关,插件内零平台特判 |

## 3. 架构

### 3.1 运行时拓扑

```
AI 模型
  │  工具调用(execute_command 同款审批路径)
  ▼
AiToolRegistry ── 生成 AuditedToolCallback(effect=EXTERNAL)
  │
  ▼
PluginProcessManager.invoke("fan.summer.browser", method, params)
  │  JSON-RPC 2.0 over stdio
  ▼
BrowserWorkerMain (Java, plugin SDK)
  ├── ChromiumResolver   # 解析 executablePath(三档优先级)
  ├── BrowserSession     # 持有 Playwright Browser/Context/Page 单例
  ├── BrowserHandlers    # 9 个 handler 方法
  └── JsonRpcWorker.run()
        │
        ▼ (Playwright 驱动)
        Chromium 进程(有头窗口,对用户可见)
```

### 3.2 与宿主的边界

- **主仓库零代码改动**。`AiToolRegistry` 的 plugin 分支(`AiToolRegistry.pluginCallback(...)`,`AiToolRegistry.java:107-140`)已自动收纳声明了 `aiTools` 的已启用插件。
- 每个 aiTool 声明 `effect: "external"` → 生成的 callback 走现有审批门(`APPROVE_FOR_ME` 下仍需批准;`ASK_FOR_APPROVAL` 下总是批准;`FULL_ACCESS` 放行)。
- manifest `permissions: ["network"]` → `PluginProcessManager.start` 放行 worker 网络(`PluginProcessManager.java:189-191`)。

## 4. Chromium 获取策略

`executablePath` 解析三档优先级(`ChromiumResolver`):

| 档 | 来源 | 说明 |
|---|---|---|
| 1 | 用户在插件 UI 配置的路径 | 用户自行上传的 Chrome/Edge/Chromium;直接透传 `executablePath` |
| 2 | `<插件 data 目录>/chromium/` 内已下载的 Chromium | 存在即用 |
| 3 | 自动下载 | 设置 `PLAYWRIGHT_BROWSERS_PATH=<插件 data 目录>/chromium`,调 `com.microsoft.playwright.CLI install chromium`,下载到档 2 位置 |

下载到**插件自己的 data 目录**(非 Playwright 默认目录),实现隔离、卸载即清。

**档 2 可执行文件名按平台:**
- Windows: `chrome.exe`
- macOS: `Chromium.app/Contents/MacOS/Chromium`
- Linux: `chrome`

OS 判定复用现有模式:`System.getProperty("os.name").toLowerCase().contains("win")`(与 `PluginProcessManager.isWindows()` 一致)。

**worker 启动流程:**
```
BrowserWorkerMain
 ├── ensureChromium()         # 三档解析;必要时 CLI install(带进度日志)
 ├── launchPersistentContext( # userDataDir = <插件 data 目录>/profile/
 │     executablePath = <解析结果或 null>,   # null 时 Playwright 用已安装的
 │     headless = false,                     # 默认有头
 │     args = [--window-size=1280,900])
 └── JsonRpcWorker().on("browser_navigate", ...)....run()
```

## 5. aiTools 清单

9 个原子工具,全部 `effect: "external"`。每个工具对应 worker 内一个 handler 方法,通过 `JsonRpcWorker.on(method, handler)` 注册。

| 工具 | 入参 | 返回 | 用途 |
|---|---|---|---|
| `browser_navigate` | `url`, `waitUntil?`(load/domcontentloaded/networkidle,默认 load) | `{url, title}` | 打开页面 |
| `browser_click` | `selector` | `{clicked: bool}` | 点击元素 |
| `browser_type` | `selector`, `text`, `clear?`(默认 true) | `{filled: bool}` | 输入文字 |
| `browser_get_text` | `selector?`(空则整页) | `{text, length}` | 读取文本 |
| `browser_query` | `selector` | `{count, samples[]}` | 查询元素存在性 + 取样(不返回全量) |
| `browser_screenshot` | `fullPage?`(默认 false), `selector?` | `{imagePath, width, height, a11yTree}` | 截图存文件(给用户看)+ 返回页面可访问性树文本(给 AI 读,因 AI 工具层无图像注入) |
| `browser_wait_for` | `selector`, `state?`(attached/detached/visible/hidden,默认 visible), `timeout?`(默认 30s) | `{ok: bool}` | 等待元素状态 |
| `browser_eval_js` | `script` | `{value}` | 执行页面 JS,返回序列化结果 |
| `browser_close` | — | `{closed: bool}` | 关闭浏览器释放资源 |

### 关键设计点

1. **effect 统一 `external`** — 走现有审批门,无需改宿主审批逻辑。
2. **`browser_screenshot` 返回文本而非图像** — FengYu 的 AI 工具层只处理文本/JSON 结果,**无多模态/图像注入通道**(已确认:`FengYu/src/.../ai/` 下无 image/base64/media_type 处理)。因此截图工具返回:`imagePath`(截图文件绝对路径,给用户在 UI 里看)+ `a11yTree`(页面可访问性树的文本快照,给 AI 模型读)。视觉注入(工具结果 → 模型 image part)是**后续独立 feature**,需改 `FengYu/src/.../ai/` 核心子系统,不在本插件范围。
3. **`browser_get_text` 不返回全量 HTML** — 只返回纯文本 + 长度,长内容截断(参考 `CommandExecuteTool` 的输出上限模式,默认上限 64K,防 token 爆炸)。
4. **`browser_query` 返回取样而非全量** — `samples[]` 最多 N 个元素(如前 5 个的文本/属性),避免大列表撑爆上下文。
5. **错误处理** — 选择器找不到、JS 抛异常、超时等,统一返回 `{success: false, error, summary}`,worker 不崩(`PluginHandlerSupport` 已有异常兜底,把异常压扁成失败响应)。

## 6. Maven 模块与 .fyp 结构

新增官方插件 Maven 模块,挂在 `OfficialPlugins` 聚合器下(与 plugin-markdown/excel/email 平级)。

```
OfficialPlugins/plugin-browser/
├── pom.xml                  # 父 pom 已管理 playwright 1.49.0,直接引依赖
└── src/main/
    ├── java/fan/summer/fengyu/plugin/browser/
    │   ├── BrowserWorkerMain.java       # 入口,仿 MarkdownWorkerMain
    │   ├── BrowserHandlers.java         # 9 个 handler 方法(继承 PluginHandlerSupport)
    │   ├── BrowserSession.java          # Playwright Browser/Context/Page 单例 + 生命周期
    │   └── ChromiumResolver.java        # 三档优先级解析 executablePath
    └── resources/
        ├── manifest.json                # 见 §7
        ├── plugin.json                  # fengyu.plugin.json 构建元数据
        └── ui/
            └── index.html               # iframe 配置面板
```

打包走现有 `fengyu-plugin-dev` skill 的 `.fyp` 流程(manifest + ui + shaded backend jar)。**Chromium 不打进 .fyp**。

### pom.xml 依赖

```xml
<dependencies>
    <!-- Worker SDK(同其他官方插件) -->
    <dependency>
        <groupId>fan.summer.fengyu.sdk</groupId>
        <artifactId>fengyu-plugin-sdk</artifactId>
    </dependency>
    <!-- Playwright(父 pom dependencyManagement 已管理版本) -->
    <dependency>
        <groupId>com.microsoft.playwright</groupId>
        <artifactId>playwright</artifactId>
    </dependency>
</dependencies>
```

## 7. manifest.json

```json
{
  "schemaVersion": 1,
  "id": "fan.summer.browser",
  "name": "Browser Agent",
  "description": "AI-driven browser automation: navigate, click, type, scrape, screenshot, eval JS",
  "version": "<app version>",
  "author": "FengYu",
  "icon": "browser",
  "category": "automation",
  "ui": { "entry": "ui/index.html" },
  "backend": {
    "command": "java -jar backend/worker.jar",
    "protocol": "json-rpc-2.0",
    "callTimeoutSeconds": 120
  },
  "permissions": ["network", "files.write"],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "aiTools": [
    { "name": "browser_navigate", "effect": "external", "method": "browser_navigate",
      "description": "Navigate the browser to a URL. Returns the final URL and page title.",
      "timeoutSeconds": 60,
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"Absolute URL to open\"},\"waitUntil\":{\"type\":\"string\",\"enum\":[\"load\",\"domcontentloaded\",\"networkidle\"],\"default\":\"load\"}},\"required\":[\"url\"]}",
      "outputSchema": "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"summary\":{\"type\":\"string\"},\"url\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"}},\"required\":[\"success\",\"summary\"]}" },
    { "name": "browser_click", "effect": "external", "method": "browser_click",
      "description": "Click an element matched by a CSS selector.",
      "timeoutSeconds": 30,
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"selector\":{\"type\":\"string\",\"description\":\"CSS selector\"}},\"required\":[\"selector\"]}",
      "outputSchema": "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"summary\":{\"type\":\"string\"},\"clicked\":{\"type\":\"boolean\"}},\"required\":[\"success\",\"summary\"]}" },
    { "name": "browser_type", "effect": "external", "method": "browser_type",
      "description": "Type text into an element matched by a CSS selector. Clears first by default.",
      "timeoutSeconds": 30,
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"selector\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"},\"clear\":{\"type\":\"boolean\",\"default\":true}},\"required\":[\"selector\",\"text\"]}",
      "outputSchema": "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"summary\":{\"type\":\"string\"},\"filled\":{\"type\":\"boolean\"}},\"required\":[\"success\",\"summary\"]}" },
    { "name": "browser_get_text", "effect": "external", "method": "browser_get_text",
      "description": "Read text content of an element (or the whole page if selector omitted). Truncated to 64K.",
      "timeoutSeconds": 30,
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"selector\":{\"type\":\"string\"}}}",
      "outputSchema": "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"summary\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"},\"length\":{\"type\":\"integer\"}},\"required\":[\"success\",\"summary\"]}" },
    { "name": "browser_query", "effect": "external", "method": "browser_query",
      "description": "Query elements by CSS selector; return count and up to 5 sample texts.",
      "timeoutSeconds": 30,
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"selector\":{\"type\":\"string\"}},\"required\":[\"selector\"]}",
      "outputSchema": "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"summary\":{\"type\":\"string\"},\"count\":{\"type\":\"integer\"},\"samples\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"success\",\"summary\"]}" },
    { "name": "browser_screenshot", "effect": "external", "method": "browser_screenshot",
      "description": "Capture a screenshot (viewport, full page, or element). Saved to plugin data dir; returns the file path plus the page accessibility tree as text (the AI cannot see images, so it reads the a11y tree).",
      "timeoutSeconds": 30,
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"fullPage\":{\"type\":\"boolean\",\"default\":false},\"selector\":{\"type\":\"string\"}}}",
      "outputSchema": "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"summary\":{\"type\":\"string\"},\"imagePath\":{\"type\":\"string\"},\"width\":{\"type\":\"integer\"},\"height\":{\"type\":\"integer\"},\"a11yTree\":{\"type\":\"string\"}},\"required\":[\"success\",\"summary\"]}" },
    { "name": "browser_wait_for", "effect": "external", "method": "browser_wait_for",
      "description": "Wait for an element to reach a state (attached/detached/visible/hidden).",
      "timeoutSeconds": 40,
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"selector\":{\"type\":\"string\"},\"state\":{\"type\":\"string\",\"enum\":[\"attached\",\"detached\",\"visible\",\"hidden\"],\"default\":\"visible\"},\"timeout\":{\"type\":\"integer\",\"default\":30,\"minimum\":1,\"maximum\":600}},\"required\":[\"selector\"]}",
      "outputSchema": "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"summary\":{\"type\":\"string\"},\"ok\":{\"type\":\"boolean\"}},\"required\":[\"success\",\"summary\"]}" },
    { "name": "browser_eval_js", "effect": "external", "method": "browser_eval_js",
      "description": "Evaluate a JavaScript expression in the page and return the serialized result.",
      "timeoutSeconds": 30,
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"script\":{\"type\":\"string\",\"description\":\"JS expression to evaluate\"}},\"required\":[\"script\"]}",
      "outputSchema": "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"summary\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"}},\"required\":[\"success\",\"summary\"]}" },
    { "name": "browser_close", "effect": "external", "method": "browser_close",
      "description": "Close the browser and release resources. The next browser_* call will relaunch.",
      "timeoutSeconds": 15,
      "inputSchema": "{\"type\":\"object\",\"properties\":{}}",
      "outputSchema": "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"summary\":{\"type\":\"string\"},\"closed\":{\"type\":\"boolean\"}},\"required\":[\"success\",\"summary\"]}" }
  ]
}
```

**permissions 说明:**
- `network` — worker 进程联网(下载 Chromium + 浏览器出站访问)。
- `files.write` — 截图存盘 + Chromium 下载/解压到插件 data 目录。

## 8. 会话与生命周期

### 8.1 会话模型
- `BrowserSession` 是单例,懒加载:首次 `browser_navigate`(或任何需要 Page 的工具)时启动 Chromium 并创建 `BrowserContext` + `Page`。
- 同一 worker 进程内的后续工具调用复用同一 `Page`(导航、点击、输入在同一个页面累积)。
- `browser_close` 主动关闭;下一次工具调用会重新启动。
- worker 进程结束(插件禁用 / 宿主关闭 / worker 超时重建)即会话结束,所有内存状态丢失(profile 目录里的 Cookie/登录态保留)。

### 8.2 进程回收

> **playwright-java 运行时架构**(影响进程树深度):`playwright-java` 并非纯 Java 引擎,它在 `Playwright.create()` 时把一个**捆绑的 Node.js 驱动**解压到临时目录并 spawn 为子进程,Java 客户端经 stdin/stdout 协议与该 Node 驱动通信,Node 驱动再管理 Chromium 进程。因此实际进程树是三层:**Host JVM → worker JVM → Node driver → Chromium(browser + renderer/GPU/utility)**。这使进程回收比普通插件更深。

- **正常关闭**:`BrowserSession.close()` 调 `page.context().browser().close()`(Playwright 终止 Chromium 子进程)+ `playwright.close()`(终止 Node driver)。worker 内注册 JVM `shutdownHook` 兜底,确保即便 handler 路径异常也能清理。
- **worker 被强杀**:依赖宿主 `PluginProcessManager.close()` 的 `process.toHandle().descendants().forEach(...destroyForcibly())` + 根进程 `destroyForcibly()`(现有逻辑)。`descendants()` 应能覆盖 worker JVM 的直接子进程(Node driver);Chromium 进程由 Node driver 派生,正常关闭路径已处理。
- **Windows 强杀残留(已知限制)**:Windows 上 `descendants()` 枚举不可靠,若 worker 被强杀而 `playwright.close()` 没来得及跑,可能残留孤儿 Node driver / Chromium 进程。**MVP 不修**,作为已知限制 + follow-up issue(需在宿主层 `PluginProcessManager` 加 `taskkill /F /T /PID`,与 Electron 已用的 `tree-kill` 同策略,超出本插件范围)。

## 9. Windows 兼容性

plugin-browser 在 Windows 上**完全复用宿主现有机制,零插件内平台特判**:

| 场景 | 行为 |
|---|---|
| Windows + `unsandboxedPlugins` OFF | worker 走 `sandbox.plugin()` → `Backend.NONE` → `IllegalStateException` → 插件无法启动(fail-closed,与所有插件一致) |
| Windows + `unsandboxedPlugins` ON | worker 走 `sandbox.unrestricted()` → 正常启动,无沙箱隔离 |
| macOS/Linux | 原生沙箱(sandbox-exec / bwrap);`unsandboxedPlugins` 开关不显示 |

### 宿主现有开关(引用,不在本插件实现)
- API 字段:`unsandboxedPlugins`;DB key:`plugin.unsandboxed`;Java:`AiConfigServiceHeadless.isUnsandboxedPluginsEnabled()`。
- `PluginProcessManager.java:106-107` 的 `fullAccess` = `AiPermissionContext.current() == FULL_ACCESS` **OR** `isUnsandboxedPluginsEnabled()`。
- 开启后 `PluginProcessManager.start(...)` 走 `sandbox.unrestricted(...)`(`PluginProcessManager.java:203-205`),绕过 `ProcessSandbox.plugin()` 在 `Backend.NONE` 时的 `IllegalStateException`。
- UI:`Settings → 运行时与安全`,仅在 `compatibilityMode`(无原生沙箱)时显示(`Settings.vue` `v-if="isolationStatus?.compatibilityMode"`)。后端硬性拒绝在有原生沙箱的平台上开启(`SettingsController.applyUnsandboxedPlugins`)。

### Windows 用户体验
1. 装插件 → 首次工具调用 → worker 起不来(或先在设置里看到"无沙箱运行插件"开关)。
2. 在 `Settings → 运行时与安全`(兼容模式下显示)打开开关(需确认对话框)。
3. worker 正常运行。

## 10. 插件 UI(iframe 配置面板)

iframe UI 做**配置面板**(不做浏览器画面预览,浏览器本身是独立窗口):

- **浏览器路径输入**:用户可指定自定义 Chromium/Chrome/Edge 路径(对应 §4 档 1)。空 = 用自动下载的。
- **下载状态显示**:Chromium 是否已就绪 / 版本 / 正在下载(进度)。
- **清除 profile 按钮**:一键删除 `<插件 data 目录>/profile/`(清登录态/Cookie)。

UI 通过 `@infinia/plugin-sdk` 的 `postMessage` 桥与 worker 通信(读取/写入配置、触发下载、清除 profile)。配置持久化到插件 data 目录的 JSON 文件。

## 11. 平台支持矩阵

| 平台 | 有头 | 沙箱 | 状态 |
|---|---|---|---|
| macOS | ✅ | ✅ sandbox-exec | 完整支持 |
| Linux | ✅ | ✅ bwrap | 完整支持 |
| Windows | ✅ | ⚠️ 需 `unsandboxedPlugins` 开关 | 支持,降级隔离 + 文档标注 |

## 12. 测试策略

- **handler 单元测试**:`BrowserHandlers` 各方法用 mock `BrowserSession` 验证入参解析、Playwright 调用、返回结构、错误兜底。参考现有 `CommandExecuteToolTest` / `ChatToolApprovalGateTest`。
- **ChromiumResolver 单元测试**:三档优先级、平台可执行文件名、路径存在性判断(用临时目录模拟)。
- **集成测试**:启动真实 Chromium 跑 `navigate → get_text → screenshot → close` 冒烟流(标记为需要网络的集成测试,CI 上可跳过)。
- **manifest 校验**:用 `toolchain/spec/manifest.schema.json` 校验 `manifest.json` 合法性。
- **Windows 覆盖缺口**:现有 `ProcessSandboxTest` / `PluginProcessManagerTest` 均无 Windows-path fixture;本插件测试在非 Windows 环境跑,Windows 行为靠设计保证 + 手测。

## 13. 风险与 follow-up

| 项 | 说明 | 处理 |
|---|---|---|
| Windows 强杀残留 Chromium 进程 | `descendants()` 不可靠 | MVP 已知限制;follow-up 在宿主加 `taskkill /T` |
| 首次下载 ~150MB | 用户首次使用需联网下载 Chromium | UI 显示进度;用户可自行指定路径绕过 |
| `browser_eval_js` 安全 | AI 可执行任意 JS | 已在浏览器进程沙箱内;effect=external 走审批 |
| token 消耗 | 细粒度工具 round-trip 多 | `get_text`/`query` 截断 + 取样缓解;后续可加 `browser_perform` 批处理 |

## 14. 实现顺序(概要,详 见后续 plan)

1. Maven 模块骨架(`OfficialPlugins/plugin-browser/pom.xml` + 包结构),引 playwright 依赖。
2. `manifest.json` + `plugin.json` + 空 UI。
3. `BrowserSession`(Playwright 生命周期 + 懒加载 + close)。
4. `ChromiumResolver`(三档解析 + CLI install)。
5. `BrowserHandlers`(9 个 handler,逐个实现 + 单测)。
6. `BrowserWorkerMain`(仿 `MarkdownWorkerMain` 串起来)。
7. iframe 配置面板。
8. 打包成 `.fyp`,本地冒烟验证。
9. 更新文档(`docs/en`、`docs/zh` 官方插件列表)。
