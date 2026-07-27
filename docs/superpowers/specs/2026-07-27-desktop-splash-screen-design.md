# Desktop 启动提示界面（Splash Screen）设计

- **日期**: 2026-07-27
- **范围**: `desktop/electron/`（Electron shell）
- **状态**: 设计待审

## 1. 背景与动机

桌面应用（Electron shell）当前在 `app.ready` 后到主窗口可见之间存在一段**显著的无反馈等待期**：

1. **后端 spawn + 健康检查**（`main.ts:144-164`）—— JVM 冷启动 + Spring Boot 上下文初始化，可能持续数秒到数十秒。
2. **主窗口加载 + 首次绘制**（`create-window.ts:115-117`）—— 主窗口 `show:false` 创建，仅在 Vue `ready-to-show` 后才 `show()`。

这段时间用户看到的是**完全无窗口的空白**（操作系统层面没有任何 UI）。当前唯一的"加载感"是 `BrowserWindow.backgroundColor: '#0d0d0d'`，但它只在 `show()` 之后才可见，对等待期毫无帮助。

**目标**：在 `bootstrap()` 起始处立即显示一个轻量启动提示界面（splash），覆盖整个等待期，随真实启动阶段更新进度文案，主窗口 `ready-to-show` 时自动消失。

## 2. 需求（已与用户确认）

| 维度 | 决策 |
|---|---|
| 进度形式 | **分阶段进度文本**（spawning → port-ready → health-ready → loading-ui） |
| 窗口样式 | **无边框透明**（Frameless + transparent） |
| 文案语言 | **双语，跟随系统 locale**（`app.getLocale()`），非中文 fallback 英文 |
| 启动失败时 | **关闭 splash + 现有 `dialog.showErrorBox`**（不引入重试 UI） |
| 出现时机 | **打包版与开发版都显示**（dev 模式阶段快速闪过属预期） |

## 3. 方案选型

评估了三种实现路径，选定 **方案 A**：

- **方案 A（选定）：独立纯 HTML splash 窗口** —— 新建一个 `BrowserWindow`，加载自包含的 `splash.html`（内联 CSS/JS/SVG），通过单向 `webContents.send` 推送进度。秒级出图，与主窗口彻底解耦，崩溃互不影响。
- **方案 B（否决）：主窗口内嵌 loading 覆盖层** —— 在 `frontend/index.html` 加覆盖 div。**根本问题**：主窗口加载发生在后端就绪之后，覆盖层无法覆盖最长的等待期（后端启动），等于没做。
- **方案 C（否决）：Vue SPA 路由级 loading 视图** —— 与方案 B 同样的根本问题，且增加前端路由耦合。

## 4. 架构设计

### 4.1 启动时序

```
app.whenReady()
  └─ bootstrap()
       ├─ initLogger()                               (现有)
       ├─ registerDialogIpc()                        (现有)
       ├─ ★ createSplashWindow()  ← 新增：立即创建并显示 splash
       │      └─ loadFile('resources/splash.html', { query: { lang } })
       │         ready-to-show → splash.show()
       ├─ resolveLayout(...)                         (现有)
       ├─ startBackend({ onProgress })  ← 改造：进度回调注入
       │      ├─ 'spawning'    → 初始
       │      ├─ 'port-ready'  → readPort() 解析到端口
       │      └─ 'health-ready'→ pollHealth() 首次 200
       ├─ createMainWindow({ onMainReady })          (现有，新增回调)
       │      └─ ready-to-show → mainWindow.show() + ★ splash.destroy()
       └─ createTray()                               (现有)

  失败路径（任一 try/catch 内）：
       → ★ destroySplash(splash)
       → dialog.showErrorBox(...)  (现有)
       → app.quit()
```

### 4.2 模块职责与边界

| 单元 | 职责 | 依赖 |
|---|---|---|
| `create-splash.ts` | 创建/显示/销毁 splash 窗口；暴露 `sendProgress(splash, stage)`、`destroySplash(splash)`；null-safe | Electron `BrowserWindow`、`app`、`splash-i18n.ts` |
| `splash-i18n.ts` | 极小翻译表（4 阶段 × 中英）+ `pickLocale()` 从 `app.getLocale()` 解析 BCP-47 | 无 |
| `splash.html` | 自包含 UI（内联 CSS + JS + SVG logo）；监听 `splash:progress` 事件切换文案 | splash-preload.ts（暴露 `onProgress`） |
| `splash-preload.ts` | 3 行 preload：`contextBridge.exposeInMainWorld('splash', { onProgress })` | Electron `contextBridge`、`ipcRenderer` |
| `main.ts` | 在 `bootstrap()` 起始创建 splash；在 4 个错误退出点之前 `destroySplash`；向 `startBackend`/`pollHealth` 传 `onProgress`；向 `createMainWindow` 传 `onMainReady` | 上述所有 |
| `orchestrator.ts` / `spawn.ts` / `health.ts` | 各接受可选 `onProgress?: (stage: SplashStage) => void`；在真实节点调用；**默认 undefined 时行为完全不变** | `splash-i18n.ts`（仅类型 `SplashStage`） |
| `create-window.ts` | `ready-to-show` 回调（`create-window.ts:115`）增加 `opts.onMainReady?.()` 调用 | 无新依赖 |

**隔离性**：splash 是独立 `BrowserWindow` + 独立 `webContents`，与主窗口无共享状态。进度通道单向 `webContents.send`，无需 `ipcMain.handle`。前端、后端零改动。

## 5. 详细设计

### 5.1 BrowserWindow 选项（`create-splash.ts`）

```ts
const isLinux = process.platform === 'linux'
const splash = new BrowserWindow({
  width: 480, height: 320,
  frame: false,
  transparent: !isLinux,          // Linux 降级：不透明，避免合成器渲染异常
  resizable: false,
  maximizable: false,
  minimizable: false,
  fullscreenable: false,
  skipTaskbar: true,
  show: false,
  center: true,
  focusable: false,               // 纯展示，不抢焦点
  webPreferences: {
    preload: join(__dirname, 'splash-preload.js'),
    contextIsolation: true,
    nodeIntegration: false,
    sandbox: true,
  },
})
```

**设计决策**：
- `skipTaskbar: true` + `focusable: false` —— splash 不占任务栏、不可交互；主窗口出现后焦点直接落在主窗口。
- `transparent: !isLinux` —— macOS/Windows 原生支持透明圆角；Linux 合成器支持参差，降级为不透明暗色背景（CSS `border-radius` 失效但功能正常）。
- `preload: splash-preload.js` —— 仅暴露 `onProgress`，让 HTML 内 JS 能监听 `splash:progress` 事件实现文案平滑过渡。

### 5.2 进度推送通道

主进程侧（`create-splash.ts`）：
```ts
export type SplashStage = 'spawning' | 'port-ready' | 'health-ready' | 'loading-ui'

export function sendProgress(splash: BrowserWindow | null, stage: SplashStage): void {
  if (splash && !splash.isDestroyed()) {
    splash.webContents.send('splash:progress', { stage, ts: Date.now() })
  }
}
```

splash-preload（`splash-preload.ts`，约 3 行）：
```ts
import { contextBridge, ipcRenderer } from 'electron'
contextBridge.exposeInMainWorld('splash', {
  onProgress: (cb: (p: { stage: string; ts: number }) => void) =>
    ipcRenderer.on('splash:progress', (_e, p) => cb(p)),
})
```

### 5.3 splash.html 结构（自包含）

```html
<!DOCTYPE html>
<html lang data-platform="">
<head>
  <meta charset="UTF-8" />
  <meta http-equiv="Content-Security-Policy"
        content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:;" />
  <style>
    :root {
      --bg: #0d0d0d; --surface: #161616; --text: #ededed;
      --text-dim: #a0a0a0; --accent: #ededed; --radius: 16px;
    }
    html, body { margin: 0; height: 100%; background: transparent; }
    body {
      display: grid; place-items: center;
      font-family: -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
    }
    .card {
      width: 440px; padding: 36px 32px; border-radius: var(--radius);
      background: var(--bg); color: var(--text);
      box-shadow: inset 0 0 0 1px #2a2a2a;  /* 内嵌边框，跨平台一致 */
      text-align: center;
    }
    .logo { width: 72px; height: 72px; margin: 0 auto 20px; }
    .logo svg { width: 100%; height: 100%; }
    .brand { font-size: 18px; font-weight: 600; letter-spacing: 0.5px; }
    .brand .sub { display: block; font-size: 12px; font-weight: 400; color: var(--text-dim); margin-top: 2px; }
    .progress { margin-top: 28px; display: flex; align-items: center; justify-content: center; gap: 10px; min-height: 20px; }
    .spinner { width: 14px; height: 14px; border: 2px solid #333; border-top-color: var(--accent); border-radius: 50%; animation: spin 0.8s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
    .msg { font-size: 13px; color: var(--text-dim); transition: opacity 0.2s ease; }
    .msg.fade { opacity: 0; }
  </style>
</head>
<body>
  <div class="card">
    <div class="logo"><!-- 内联 infinia-app-icon.svg path --></div>
    <div class="brand"><span id="brand-name">…</span><span class="sub" id="brand-sub"></span></div>
    <div class="progress">
      <span class="spinner"></span>
      <span class="msg" id="msg">…</span>
    </div>
  </div>
  <script>
    // ~15 行：读 query 选语言 → 渲染初始文案 → 监听 onProgress 切文案（带 opacity 渐变）
  </script>
</body>
</html>
```

**关键点**：
- **CSP**：`default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:` —— splash 不加载任何外部资源，logo 内联为 SVG。`script-src 'unsafe-inline'` 是必须的：进度订阅逻辑以内联 `<script>` 形式存在，splash 是完全自包含的（无外部脚本源），内联脚本即整个可信面。
- **圆角阴影用 `inset box-shadow`** —— 透明窗口下外部 `box-shadow` 在 Windows/Linux 常被裁剪；`inset` 边框是三平台最稳的"卡片浮层"做法。
- **logo 内联 SVG** —— 复用 `assets/branding/infinia-app-icon.svg`（512×512 master，与 `docs/public/logo.svg` 字节一致）的 path 数据。
- **字体栈**包含 `PingFang SC` / `Microsoft YaHei` —— 保证中文渲染清晰。

### 5.4 i18n 翻译表（`splash-i18n.ts`）

| stage key | 触发点 | 中文 | English |
|---|---|---|---|
| `spawning` | `createSplashWindow` 后立即 | 正在启动蜂语… | Starting FengYu… |
| `port-ready` | `spawn.ts` `readPort()` 解析到 `FENGYU_PORT` | 正在初始化服务… | Initializing service… |
| `health-ready` | `health.ts` `pollHealth()` 首次 200 | 正在加载工作区… | Loading workspace… |
| `loading-ui` | `createMainWindow` 调用前 | 即将就绪 | Almost ready |

品牌名：中文 locale 主名"蜂语"、副标"Infinia · FengYu"；英文 locale 反过来。

```ts
export function pickLocale(raw: string): SplashLocale {
  const tag = (raw || '').toLowerCase().split('-')[0]
  return tag === 'zh' ? 'zh' : 'en'  // 非 zh* 一律 fallback 英文
}
```

locale 注入：主进程 `pickLocale(app.getLocale())` → `splash.loadFile(html, { query: { lang } })`；HTML 内 JS 读 `location.search` 解析 `lang`，查内嵌 `MESSAGES` 表渲染初始文案，后续阶段切换由 `onProgress` 驱动。

**单一事实源**：阶段文案词表只在 `splash-i18n.ts` 中定义（TS 是源）。由于 splash HTML 无法 import TS 模块（sandbox + 自包含约束），HTML 内 JS 必须硬编码一份**内容相同**的词表。为防止两者漂移，实现时：
1. 先写 `splash-i18n.ts` 的 `MESSAGES`/`BRAND` 常量（源）。
2. 将同样的字面量复制到 `splash.html` 的 `<script>` 内（同步镜像）。
3. 在 `splash-i18n.ts` 顶部加注释 `// NOTE: 阶段文案需与 resources/splash.html 内 MESSAGES 保持一致`，提示未来修改者同步两边。

### 5.5 main.ts 改动点

**新增 4 处 `destroySplash`**（对应现有 4 个错误退出路径）：

| 现有位置 | 错误场景 | 改动 |
|---|---|---|
| `main.ts:100-108` | external-backend 健康检查失败 | catch 内首行 `destroySplash(splash)` |
| `main.ts:112-120` | dev frontend 启动失败 | 同上 |
| `main.ts:151-164` | 打包版 startBackend 失败 | 同上 |
| `main.ts:219-227` | 打包版 dev frontend 启动失败 | 同上 |

**进度回调注入**：
- `main.ts:99` external-backend 分支：`pollHealth({ ..., onProgress: (s) => sendProgress(splash, s) })`
- `main.ts:144-150` 打包分支：`startBackend({ ..., onProgress: (s) => sendProgress(splash, s) })`
- 主窗口创建前：`sendProgress(splash, 'loading-ui')`

**主窗口回调**：`createMainWindow({ ..., onMainReady: () => destroySplash(splash) })`（两条分支，`main.ts:122` 与 `main.ts:230`）

### 5.6 create-window.ts 改动（`ready-to-show`）

```ts
// create-window.ts:115-117 改动
win.once('ready-to-show', () => {
  if (!opts.isQuitting()) win.show()
  opts.onMainReady?.()   // ← 新增：通知 main 销毁 splash
})
```

**为什么用回调而非在 `await createMainWindow` 后 destroy**：`createMainWindow` 返回时 Vue 尚未 `ready-to-show`；若此时销毁 splash 会出现"splash 消失 → 主窗口仍未 show → 短暂无窗口"的空窗期。必须等 `ready-to-show` 才销毁。

### 5.7 平台差异处理

| 平台 | 处理 |
|---|---|
| macOS | `transparent:true` 原生支持圆角；无需特殊处理 |
| Windows | `transparent:true` + `frame:false` 下透明区域点击穿透（可接受，splash `focusable:false` 不交互）；圆角靠 CSS |
| Linux | 降级：`transparent:false` + `backgroundColor:'#0d0d0d'`，CSS 卡片仍用 `inset box-shadow` 画边；body 也设暗色背景。HTML `<html data-platform="linux">` 触发 CSS 覆盖 |

### 5.8 资源解析（dev vs 打包）

```ts
// create-splash.ts
function resolveSplashHtml(): string {
  const devPath = join(process.cwd(), 'resources', 'splash.html')
  const prodPath = join(__dirname, '..', 'resources', 'splash.html')
  return existsSync(devPath) ? devPath : prodPath
}
```

注意：这与 `tray.ts` 解析 icon 的模式**不同**。`tray.ts` 用 `process.resourcesPath`（extraResources 扁平化根），而 splash.html 通过 `electron-builder.yml` 的 `files:` 进入 app.asar，由 `__dirname` 回溯解析：`create-splash.js` 编译到 `<asar>/dist/window/`，两个 `..` 回溯到 `<asar>/`，再进 `resources/splash.html`。

## 6. 边界情况

| 情况 | 处理 |
|---|---|
| splash webContents 加载失败 | `did-fail-load` 记日志，不阻断主流程；splash 是装饰性的 |
| `splash.loadFile` 同步异常 | `createSplashWindow` 内 try/catch 返回 `null`；`sendProgress(null,...)`/`destroySplash(null)` 均 null-safe（`if (s && !s.isDestroyed())`） |
| 用户启动中 quit | 现有 `before-quit`/`will-quit` 处理（`main.ts:259-260`）不受影响；splash 被 destroy 不影响 |
| `window-all-closed` 误触发 | **已确认无此风险**：现有 `window-all-closed` 是 no-op（`main.ts:267-269`，为保留 tray 不退出）；且 splash destroy 发生在主窗口已存在并 `ready-to-show` 期间，主窗口仍在，不会触发 `window-all-closed` |
| 后端 spawn 失败 / health 超时 | throw → catch 内 `destroySplash` → `showErrorBox` → `app.quit()`（§5.5 四处） |
| dev 模式 | external-backend 分支也注入 `onProgress`；阶段快速从 spawning→health-ready（无 port-ready），符合预期 |

## 7. 打包配置

### 7.1 electron-builder.yml 改动

`resources/` 当前是 `buildResources`（构建期资源，不自动进产物）。需在 `files` 段显式加入 splash：

```yaml
files:
  - dist/**/*
  - resources/splash.html   # ★ 新增（仅此一个文件，不打包所有 icon）
  - frontend-dist/**/*
  - package.json
  # ... 其余现有排除规则
```

**只加 `splash.html`**（约 3KB），不打包 `icon.png`/`icon.ico`（它们已被 electron-builder 作为构建期 icon 消费，进产物会多 ~200KB 无用体积）。

### 7.2 TypeScript 构建

`splash-preload.ts` 在 `src/window/` 下，若 `tsconfig.json` 的 `include` 覆盖 `src/**/*.ts`（通常是）则自动纳入 `npm run build:ts`，产物为 `dist/splash-preload.js`。`splash.html` 是静态资源不参与编译。

## 8. 验证策略

遵循 AGENTS.md "Focused verification"——只跑最小可证明改动的检查：

| 验证项 | 命令 | 证明什么 |
|---|---|---|
| TS 编译通过 | `cd desktop/electron && npm run build:ts` | 新增/改动文件无类型错误 |
| 现有测试不回归 | `cd desktop/electron && npm test` | 可选 `onProgress` 参数不破坏现有 vitest 测试 |
| splash 资源进产物 | `cd desktop/electron && npm run build:mac && find . -path '*/resources/splash.html' -not -path '*/node_modules/*'` | electron-builder.yml 配置正确 |
| e2e 启动冒烟 | `scripts/e2e-smoke.sh` | 后端启动逻辑（onProgress 注入）未改变后端行为（该脚本不经 Electron，对 splash 透明） |
| 手动视觉验证 | `cd desktop/electron && npm run dev` + 打包后运行 `.app` | splash 出现、文案切换、主窗口出现后 splash 消失 |

## 9. 风险与回退

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| splash.html 未打进包（打包后 loadFile 失败） | 中 | 中（打包版 splash 不显示，但不崩溃） | `did-fail-load` 容错 + §8 find 验证 |
| Linux 下透明窗口渲染异常 | 低 | 低（Linux 非主要平台） | §5.7 降级方案 |
| `onProgress` 破坏现有 orchestrator 测试 | 低 | 低 | 参数可选、默认 undefined 时行为不变；§8 `npm test` 验证 |

**回退**：所有改动集中在 `desktop/electron/` 的 3 个新增文件 + 6 个现有文件的可选参数注入。`git revert` 单 commit 即可，前端和后端零影响。

## 10. 文件清单

**新增（4）**：
- `desktop/electron/resources/splash.html` —— 自包含 UI
- `desktop/electron/src/window/create-splash.ts` —— splash 工厂 + 生命周期
- `desktop/electron/src/window/splash-i18n.ts` —— 翻译表 + `pickLocale`
- `desktop/electron/src/window/splash-preload.ts` —— 暴露 `onProgress` 的 contextBridge

**改动（6）**：
- `desktop/electron/src/main.ts` —— 创建 splash、4 处 `destroySplash`、注入 `onProgress`、传 `onMainReady`
- `desktop/electron/src/backend/orchestrator.ts` —— `startBackend` 接受可选 `onProgress`
- `desktop/electron/src/backend/spawn.ts` —— `readPort` 成功时调用 `onProgress('port-ready')`
- `desktop/electron/src/util/health.ts` —— `pollHealth` 首次 200 时调用 `onProgress('health-ready')`
- `desktop/electron/src/window/create-window.ts` —— `ready-to-show` 回调增加 `opts.onMainReady?.()`
- `desktop/electron/electron-builder.yml` —— `files` 加入 `resources/splash.html`

**前端/后端零改动。**
