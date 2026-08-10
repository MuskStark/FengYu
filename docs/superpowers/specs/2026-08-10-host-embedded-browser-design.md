# 宿主内嵌浏览器能力设计

- **日期**: 2026-08-10
- **状态**: 设计已确认,待实现
- **范围**: 将浏览器操作能力从 `plugin-browser`(Playwright Java worker)迁移为宿主内置能力,由后端委托 Electron 原生驱动,复用 Electron 已捆绑的 Chromium;移除 `plugin-browser` 插件。
- **前置设计**: `2026-08-05-plugin-browser-design.md`(被本设计取代)

## 1. 背景与动机

现状:浏览器操作能力**完全住在** `plugin-browser` 这个官方插件 worker 里——一个独立的 Playwright Java 进程,自带 ~570MB Chromium 驱动包(打包时按平台 strip),通过 JSON-RPC 2.0 over stdio 与宿主通信。宿主(Spring Boot)本身没有任何浏览器自动化能力。

本设计的驱动力:

1. **会话/状态深度集成** —— 浏览器 profile/cookies/登录态应跟随宿主(Electron userData)生命周期,而非住在插件 dataDir。
2. **架构简化** —— 去掉浏览器这一块的 JSON-RPC worker + 沙箱 + 进程管理层。
3. **复用 Electron 的 Chromium** —— 桌面形态下 Electron 本身就是 Chromium,不应再重复集成一个浏览器引擎(Playwright 自带的 Chromium)。

核心架构后果(必须明确):Playwright Java 会自己拉起独立 Chromium 进程,**无法驱动 Electron 的 webContents**。因此"内嵌"的真实含义是**实现层的替换**——从"plugin-browser 的 Playwright worker"换到"Electron 原生 webContents + CDP,后端通过 HTTP 桥指挥"。不是简单搬家。

## 2. 已确认的关键决策

| 决策点 | 选定 |
|---|---|
| 能力形态 | 宿主内嵌(净新增,非插件) |
| 引擎位置 | 后端委托 Electron;桌面态优先 |
| 引擎实现 | Electron 原生 webContents + `webContents.debugger`(CDP),**不用 Playwright** |
| 并发模型 | 单页(对齐现状),全局一个 BrowserWindow |
| 窗口形态 | 独立可见窗口(show:true,不嵌入主界面) |
| 旧插件 | **移除** `plugin-browser`(含 Playwright/Chromium 依赖) |
| 调用入口 | AI 工具(不做专门前端面板/REST 端点) |
| 传输层 | Electron 主进程开 loopback HTTP listener(token 校验,随机端口) |
| 注册开关 | `fengyu.desktop` 系统属性(由 spawn.ts 注入,仅桌面态) |
| 审批门 | `implements ApprovalRequiredTool`(与 `execute_command` 同等) |
| Web 态行为 | 无 Electron → 无 `fengyu.desktop` → BrowserTool 不注册 → `browser_*` 不出现在 AI 目录 |

## 3. 非目标

- **不做前端面板/REST 端点** —— 浏览器操作仅通过 AI 工具触发。
- **不做多标签页** —— 单页,后续可演进(返回值可预留 tabId)。
- **不做 headless 模式** —— 固定独立可见窗口。
- **不保证 a11yTree 字节级等价** —— 语义等价即可(见 §6.4)。
- **不为 Web 态提供浏览器能力** —— 桌面优先,Web 态用户失去该能力(被接受)。

## 4. 架构

### 4.1 运行时拓扑

```
┌─────────────────────────────────────────────────────────────────┐
│  Electron 主进程 (desktop/electron/src/)                        │
│                                                                  │
│  ┌──────────────────┐    ┌──────────────────────────────────┐  │
│  │ BrowserBridge     │    │ BrowserWindow (独立可见,单页)     │  │
│  │ HTTP listener     │    │  webContents                     │  │
│  │ 127.0.0.1:随机端口 │◄──►│  · loadURL / executeJavaScript   │  │
│  │ + token 校验       │    │  · capturePage()                │  │
│  │                   │    │  · webContents.debugger (CDP)    │  │
│  └─────────┬─────────┘    └──────────────────────────────────┘  │
│            │ 持久 profile: <userData>/fengyu-browser/profile      │
└────────────┼─────────────────────────────────────────────────────┘
             │ HTTP (loopback, token)
             │ 端口/token 通过 spawn 握手传入后端
┌────────────┼─────────────────────────────────────────────────────┐
│  Spring Boot 后端 (FengYu/)     │                                 │
│  ┌──────────▼───────────┐                                        │
│  │ BrowserTool          │ 9 个 @Tool 方法 (implements            │
│  │ (FengYuTool)         │   ApprovalRequiredTool)                │
│  │  browser_navigate …  │ → 每个 call() 发 HTTP 给 BrowserBridge  │
│  └──────────┬───────────┘                                        │
│             │ 仅当 fengyu.desktop=true 时注册                     │
│  ┌──────────▼───────────┐                                        │
│  │ AiToolRegistry       │ 自动扫描 FengYuTool bean               │
│  └──────────────────────┘                                        │
└──────────────────────────────────────────────────────────────────┘
```

### 4.2 职责划分

- **后端** 只负责"AI 工具契约"——9 个 `@Tool` 方法,签名/返回包络对齐 `plugin-browser`,把请求转成 HTTP 调用。**不碰浏览器**。
- **Electron** 只负责"执行浏览器操作"——HTTP 服务 + 独立 `BrowserWindow` + webContents/CDP。**不知道 AI**。
- **传输** 是一条 loopback HTTP(token 校验),端口和 token 在现有 spawn 握手框架里传递。

### 4.3 与现有 plugin-browser 的关系

`BrowserTool` 的 9 个工具名与 plugin 完全相同 → 不能共存(`AiToolRegistry` 会重名)。本设计包含移除 `plugin-browser`,从根上消除冲突,并对用户机器上残留的已安装记录做软抑制(见 §8.1)。

## 5. 后端实现

### 5.1 注册条件

```
spawn.ts 注入:  -Dfengyu.desktop=true  (或 env FENGYU_DESKTOP=true)
后端:           @ConditionalOnProperty("fengyu.desktop") 修饰 BrowserTool
                → 仅桌面态被 Spring 扫描为 FengYuTool bean
                → Web 态下 bean 根本不存在,AI 工具目录里没有 browser_*
```

参照现有 `fengyu.update.portable` 的注入模式(它也是 spawn.ts 按形态注入)。**后端不需要新的配置文件**。

### 5.2 BrowserTool 类骨架

```java
@Component
@ConditionalOnProperty("fengyu.desktop")
public class BrowserTool implements ApprovalRequiredTool, FengYuTool {

    // 9 个 @Tool 方法,名称与 plugin 逐字一致
    @Tool(name = "browser_navigate",  description = "...") public String navigate(...)
    @Tool(name = "browser_click",     description = "...") public String click(...)
    @Tool(name = "browser_type",      description = "...") public String type(...)
    @Tool(name = "browser_get_text",  description = "...") public String getText(...)
    @Tool(name = "browser_query",     description = "...") public String query(...)
    @Tool(name = "browser_screenshot",description = "...") public String screenshot(...)
    @Tool(name = "browser_wait_for",  description = "...") public String waitFor(...)
    @Tool(name = "browser_eval_js",   description = "...") public String evalJs(...)
    @Tool(name = "browser_close",     description = "...") public String close(...)
}
```

- `implements ApprovalRequiredTool` —— 浏览器操作有真实副作用,走和 `execute_command` 一样的审批门(`CommandExecuteTool` 模式)。
- `@ToolParam` 提供输入 schema(后端工具无 manifest,靠反射注解)。
- 返回值是 JSON 字符串(Spring AI 工具结果约定)。
- 文件:`FengYu/src/main/java/fan/summer/fengyu/ai/tools/BrowserTool.java`(新建)。

### 5.3 返回包络(逐字对齐 plugin-browser)

每个方法返回 `LinkedHashMap` 序列化的 JSON,键与语义与 `BrowserHandlers.java` 完全一致:

| 工具 | 成功返回键 |
|---|---|
| `navigate` | `success, summary, url, title` |
| `click` | `success, summary, clicked:true` |
| `type` | `success, summary, filled:true` |
| `get_text` | `success, summary, text, length` |
| `query` | `success, summary, count, samples`(≤5) |
| `screenshot` | `success, summary, imagePath, width, height, a11yTree` |
| `wait_for` | `success, summary, ok:true` |
| `eval_js` | `success, summary, value` |
| `close` | `success, summary, closed:true` |

失败统一 `{success:false, summary:<单行消息>}`。

**常量也对齐:** `TEXT_CAP = 64000`(截断追加 `…[truncated]`)、`SAMPLE_LIMIT = 5`。这些截断在后端做(与插件在 worker 里做不同,但对 AI 结果一致)。

### 5.4 转发协议(后端 → Electron)

`BrowserTool` 内部持有一个 `BrowserBridgeClient`,每个 `call()`:

1. 组请求 `{ "method": "browser_navigate", "params": {...} }`
2. `POST http://127.0.0.1:<bridgePort>/invoke`(Header `X-Browser-Token: <token>`)
3. 超时:每工具沿用 plugin 的 `timeoutSeconds`(navigate 60 / click 30 / type 30 / get_text 30 / query 30 / screenshot 30 / wait_for 40 / eval_js 30 / close 15)
4. 解析 Electron 回的 `{success, summary, ...}` → 原样转成 AI 工具结果
5. 连接失败/超时 → `{success:false, summary:"browser bridge unavailable"}`

`bridgePort` 和 `token` 来自握手注入的 env(`FENGYU_BROWSER_BRIDGE_PORT` / `FENGYU_BROWSER_BRIDGE_TOKEN`)。HTTP 客户端复用后端已有依赖(不引新库)。

文件:`FengYu/src/main/java/fan/summer/fengyu/ai/tools/BrowserBridgeClient.java`(新建)。

## 6. Electron 实现

### 6.1 HTTP 桥(`src/browser/bridge.ts`,新建)

```
启动时机:  app.whenReady() 之后,spawn 后端 之前(否则后端拿不到端口)
监听:      http.createServer on 127.0.0.1, port=0 (系统分配随机端口)
鉴权:      每个请求校验 Header X-Browser-Token: <bridgeToken>
            bridgeToken = crypto.randomBytes(24).toString('hex') (与现有 genToken 同模式)
端点:      POST /invoke   body: { "method": "...", "params": {...} }
            → 返回 { success, summary, ... } (与后端契约一致)
传递给后端: spawn.ts 启动 JVM 时注入
              env FENGYU_BROWSER_BRIDGE_PORT=<port>
              env FENGYU_BROWSER_BRIDGE_TOKEN=<bridgeToken>
            (复用现有 env 注入机制,spawn.ts 已经在注入 FENGYU_AUTH_TOKEN 等)
```

用裸 `http` 而非 Express:Electron 单进程,请求量极低,现有 `src/` 也不依赖 Express。

**生命周期:** 桥随 Electron 主进程生死。后端进程若崩了,下次握手重连即可(后端每次启动都从 env 读端口/token)。

### 6.2 独立可见窗口(`src/browser/window.ts`,新建)

```
一个 BrowserWindow,惰性创建:
  - 第一次 browser_navigate 时才创建(对齐插件 BrowserSession 的 lazy 模式)
  - 普通窗口尺寸 1280x900 (对齐插件 --window-size=1280,900)
  - 持久 profile: <userData>/fengyu-browser/profile
      → BrowserWindow({ webPreferences: { partition: 'persist:fengyu-browser' } })
      (partition: 'persist:...' 让 cookies/localStorage/登录态跨重启保留)
  - 独立窗口: show:true,不绑主窗口,用户能实时看到 AI 在操作
  - contextIsolation/sandbox 维持 Electron 默认安全姿态
```

**单页语义:** 全局只有一个这样的窗口。`browser_close` 关窗并清空引用,下次 navigate 再创建——与插件 `BrowserSession` 的 idempotent close + lazy start 完全对称。

**profile 位置选 `app.getPath('userData')/fengyu-browser/profile`:** 这是 Electron 自己管的会话状态,跟随 Electron 数据目录——"与宿主同生命周期、深度集成"的体现。

### 6.3 九个操作的执行(`src/browser/handlers.ts`,新建)

| method | Electron 实现 |
|---|---|
| `browser_navigate` | `win.webContents.loadURL(url)`(校验 http/https);waitUntil 近似:`load`→didFinishLoad,`domcontentloaded`→dom-ready,`networkidle`→**降级为 didFinishLoad + 固定短延迟(500ms)** |
| `browser_click` | `executeJavaScript('document.querySelector(sel)?.click()')`,先等待选择器可见 |
| `browser_type` | `executeJavaScript`:清空(`clear`)后设 value + 派发 input/change 事件 |
| `browser_get_text` | `executeJavaScript('document.querySelector(sel)?.innerText ?? document.body.innerText')`(后端截断 64000) |
| `browser_query` | `executeJavaScript` 取 `querySelectorAll(sel)` 的 innerText,返回 ≤5 样本 + count |
| `browser_screenshot` | `win.webContents.capturePage(rect?)` → PNG buffer,写文件 `shot-<ts>.png`;`fullPage` 用 `executeJavaScript` 取 scrollHeight 重设大小后截 |
| `browser_wait_for` | 轮询 `executeJavaScript` 检查元素 state(visible/hidden/attached/detached),超时 30s |
| `browser_eval_js` | `executeJavaScript(script)`,`String.valueOf` 结果 |
| `browser_close` | `win.destroy()`,置引用为 null |

**截断/常量在后端做**(TEXT_CAP=64000、SAMPLE_LIMIT=5),Electron 只回原始结果——Electron 侧薄、后端统一管契约。

**截图路径:** Electron 写到 `<userData>/fengyu-browser/screenshots/shot-<ts>.png`,把绝对路径放 `imagePath` 回给后端。后端内置工具不走 `AiToolFileInjector`,路径是字符串。

**语义降级点(仅一处):** `browser_navigate` 的 `waitUntil: "networkidle"` 无精确 Electron 等价,降级为 `load` + 500ms 延迟。其余 8 个操作语义无损。

### 6.4 a11yTree —— 唯一需要 CDP 的部分

```
启用:  win.webContents.debugger.attach('1.3')
取树:  CDP 'Accessibility.getFullAXTree'
格式化: toYaml(tree) → 产出与 Playwright ariaSnapshot 相似结构的 YAML
        (role / name / children: [...] 的缩进 YAML)
卸载:  screenshot 完成后 debugger.detach()  (避免长期挂 CDP)
```

输出**语义等价**(role/name/层级),不保证与 Playwright YAML 字节相同。AI 读它判断"页面上有什么、能点什么"的用途完全成立。CDP 是 Electron 原生支持的(`webContents.debugger`),无需额外依赖。

### 6.5 错误与超时

- 后端发请求时带每工具的 timeout(navigate 60s / click 30s / ... / close 15s),HTTP 客户端侧超时。
- Electron 侧各操作内部也设硬超时(尤其 wait_for 的轮询、navigate 的等待),避免请求挂死。
- 窗口不存在时(navigate 前就 click):返回 `{success:false, summary:"no browser session"}`,对齐插件 session 语义。

## 7. plugin-browser 移除

```
删除目录:    OfficialPlugins/plugin-browser/   (整个树:manifest/pom/worker src/打包脚本)
更新聚合:    OfficialPlugins/pom.xml            (从 <modules> 去掉 plugin-browser)
CI/打包:     .github/workflows/ 与 scripts/ 里构建/打包 plugin-browser .fyp 的步骤移除
               (antrun 跨平台 strip、per-OS .fyp 产物)
文档:        docs/en docs/zh 里 plugin-browser 相关页移除或改为"内置浏览器能力"说明
```

`OfficialPlugins` 剩下 `plugin-markdown / plugin-excel / plugin-email / plugin-offlinepython` 四个。

## 8. 冲突避免与零改动验证

### 8.1 工具名冲突防御

移除插件本身已消除冲突源,但用户机器上可能残留已安装 `fan.summer.browser` 记录:

```
后端启动时:  若 fengyu.desktop=true 且检测到已安装 fan.summer.browser 插件
              → 日志告警,且在 AiToolRegistry 中跳过该插件的工具注册
              (不自动卸载用户数据,只让它的工具不进 AI 目录,避免 browser_* 重名)
```

软抑制,不改用户的插件安装状态——对齐项目 "preserve user work" 原则。

### 8.2 零改动区域(明确不动)

| 区域 | 是否改动 | 说明 |
|---|---|---|
| `AiToolRegistry` / `AiToolDiscoveryConfig` | 不动 | FengYuTool bean 自动扫描 |
| `ApprovalRequiredTool` / 审批门 | 不动 | BrowserTool implements 它即自动生效 |
| `CommandExecuteTool` | 不动 | 参考模板 |
| 前端 Vue / 路由 / 状态 | 不动 | 仅 AI 工具,无新 UI |
| `i18n` 文件 | 不动 | 内置工具描述不本地化 |
| 后端 `pom.xml` | 不动 | 不引 Playwright,HTTP 客户端复用 |
| Electron `package.json` | 不动 | 用原生 webContents + CDP |
| Electron preload / 主窗口 | 不动 | 浏览器窗口是独立 BrowserWindow |

## 9. 新增文件清单

| 文件 | 角色 |
|---|---|
| `FengYu/src/main/java/fan/summer/fengyu/ai/tools/BrowserTool.java` | 9 个 @Tool 方法,implements ApprovalRequiredTool |
| `FengYu/src/main/java/fan/summer/fengyu/ai/tools/BrowserBridgeClient.java` | HTTP 客户端,转发请求到 Electron |
| `desktop/electron/src/browser/bridge.ts` | HTTP listener + token 校验 |
| `desktop/electron/src/browser/window.ts` | 独立可见 BrowserWindow 生命周期管理 |
| `desktop/electron/src/browser/handlers.ts` | 9 个操作的 webContents/CDP 执行 |

修改(非新建):`desktop/electron/src/main.ts`(启动桥)、`desktop/electron/src/backend/spawn.ts`(注入 env + `-Dfengyu.desktop`)、`OfficialPlugins/pom.xml`、CI 脚本、文档。

## 10. 测试策略

```
后端:
  - BrowserTool 单测:mock BrowserBridgeClient,验证 9 个方法的契约
    (包络键、TEXT_CAP 截断、SAMPLE_LIMIT、失败包络)
  - ConditionalOnProperty 单测:验证 Web 态不注册 bean

Electron:
  - bridge 单测:启 http listener,mock 请求,验证 token 校验与路由
  - handlers 单测:用 mock webContents 验证 executeJavaScript 调用参数
  - a11yTree 格式化单测:给 mock CDP 树,验 YAML 输出结构

端到端:
  - scripts/e2e-smoke.sh 框架里加:桌面态下
    browser_navigate → browser_screenshot → browser_close
    (验证后端→桥→窗口→截图文件→a11yTree 全链路)
```

## 11. 迁移影响(对用户)

- **桌面用户:** 升级后,浏览器能力从"插件"变"内置",开箱即用,无需操作。旧的 `fan.summer.browser` 已安装记录会被软抑制(不报错、不崩)。
- **Web 态用户:** 浏览器能力消失(plugin-browser 移除后,Web 态无替代)。这是"桌面优先 + 移除插件"决定的直接后果,已被接受。
- **AI 行为:** 9 个工具名/返回包络对齐,prompts/skills 不用改。可观察差异仅两处:`a11yTree` YAML 不与旧版字节相同(语义等价);`waitUntil: "networkidle"` 降级为 `load` + 500ms。
