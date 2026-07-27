# Desktop 启动提示界面（Splash Screen）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在桌面应用启动期间显示一个无边框透明的 splash 窗口，随真实启动阶段更新双语进度文案，主窗口就绪时自动消失。

**Architecture:** 在 `bootstrap()` 起始处创建一个独立的极简 `BrowserWindow`，加载自包含的 `splash.html`（内联 CSS/JS/SVG）。主进程通过单向 `webContents.send('splash:progress')` 推送 4 个阶段的进度；主窗口 `ready-to-show` 时销毁 splash。所有改动隔离在 `desktop/electron/`，前端和后端零改动。

**Tech Stack:** Electron 43（BrowserWindow / contextBridge / ipcRenderer）、TypeScript（strict）、Vitest、electron-builder。

## Global Constraints

- **版本约束**：不触碰 app 版本（`package.json` `version: "4.0.0-alpha.3"` 保持不变）；不触碰 plugin toolchain 版本。
- **代码风格**：遵循现有 Electron 源码约定 —— `strict: true`、CommonJS（`module: "commonjs"`）、2 空格缩进、单引号、无分号（见现有 `src/**/*.ts`）。
- **主题色**：splash 必须匹配主窗口暗色主题 `#0d0d0d`（`md3Dark.colors.background`，`create-window.ts:80`）。文本 `#ededed`、暗文本 `#a0a0a0`、边框 `#2a2a2a`。
- **CSP**：splash HTML 的 CSP 必须为 `default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:`（不加载任何外部资源；`script-src 'unsafe-inline'` 必需，因为进度订阅以内联 `<script>` 存在，splash 完全自包含）。
- **平台支持**：macOS / Windows 用 `transparent: true`；Linux 降级为 `transparent: false` + `backgroundColor: '#0d0d0d'`。
- **i18n**：双语（中/英），`app.getLocale()` 取 BCP-47 主标签，非 `zh*` 一律 fallback 英文。
- **向后兼容**：`onProgress` 参数全部可选，默认 `undefined` 时现有行为必须完全不变（现有 vitest 测试不回归）。
- **验证命令**：`cd desktop/electron && npm run build:ts` / `npm test` / `scripts/e2e-smoke.sh`。

**参考文档**：`docs/superpowers/specs/2026-07-27-desktop-splash-screen-design.md`

---

## File Structure

| 文件 | 类型 | 职责 |
|---|---|---|
| `desktop/electron/src/window/splash-i18n.ts` | 新增 | 阶段文案词表（源）+ `pickLocale()` + `SplashStage` 类型导出 |
| `desktop/electron/src/window/splash-preload.ts` | 新增 | 3 行 contextBridge，向 splash renderer 暴露 `onProgress` |
| `desktop/electron/src/window/create-splash.ts` | 新增 | `createSplashWindow()`、`sendProgress()`、`destroySplash()`、`resolveSplashHtml()` |
| `desktop/electron/resources/splash.html` | 新增 | 自包含 UI（内联 CSS/JS/SVG）+ 词表镜像 |
| `desktop/electron/src/util/health.ts` | 改动 | `pollHealth` 加可选 `onProgress`，首次 200 时调用 |
| `desktop/electron/src/backend/spawn.ts` | 改动 | `spawnBackend`/`readPort` 加可选 `onProgress`，解析到端口时调用 |
| `desktop/electron/src/backend/orchestrator.ts` | 改动 | `startBackend` 加可选 `onProgress`，转发到 spawn/health |
| `desktop/electron/src/window/create-window.ts` | 改动 | `CreateWindowOptions` 加 `onMainReady?`，`ready-to-show` 时调用 |
| `desktop/electron/src/main.ts` | 改动 | 创建/销毁 splash、注入 `onProgress`、传 `onMainReady`、4 处错误退出加 `destroySplash` |
| `desktop/electron/electron-builder.yml` | 改动 | `files` 加 `resources/splash.html` |
| `desktop/electron/test/splash-i18n.test.ts` | 新增 | `pickLocale` 单元测试 |
| `desktop/electron/test/create-splash.test.ts` | 新增 | `sendProgress`/`destroySplash` null-safe 单元测试 |
| `desktop/electron/test/health.test.ts` | 改动 | 增加 `onProgress` 调用断言 |
| `desktop/electron/test/spawn.test.ts` | 改动 | 增加 `onProgress` 调用断言 |

**依赖顺序**：Task 1（类型+词表）→ Task 2（preload）→ Task 3（health/spawn/orchestrator 加回调，可独立测试）→ Task 4（splash.html）→ Task 5（create-splash.ts 工厂）→ Task 6（create-window onMainReady）→ Task 7（main.ts 接线）→ Task 8（electron-builder.yml）→ Task 9（端到端验证）。

---

### Task 1: splash-i18n — 类型 + 词表 + locale 选择

**Files:**
- Create: `desktop/electron/src/window/splash-i18n.ts`
- Test: `desktop/electron/test/splash-i18n.test.ts`

**Interfaces:**
- Produces: `SplashStage` 类型（`'spawning' | 'port-ready' | 'health-ready' | 'loading-ui'`）、`SplashLocale` 类型（`'zh' | 'en'`）、`pickLocale(raw: string): SplashLocale`。后续所有任务的 `onProgress` 回调签名 `(stage: SplashStage) => void` 依赖此类型。

- [ ] **Step 1: Write the failing test**

Create `desktop/electron/test/splash-i18n.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { pickLocale } from '../src/window/splash-i18n'

describe('pickLocale', () => {
  it('returns zh for BCP-47 zh variants', () => {
    expect(pickLocale('zh-CN')).toBe('zh')
    expect(pickLocale('zh-TW')).toBe('zh')
    expect(pickLocale('zh')).toBe('zh')
  })

  it('returns en for English locales', () => {
    expect(pickLocale('en-US')).toBe('en')
    expect(pickLocale('en-GB')).toBe('en')
    expect(pickLocale('en')).toBe('en')
  })

  it('falls back to en for any non-zh locale', () => {
    expect(pickLocale('ja-JP')).toBe('en')
    expect(pickLocale('de-DE')).toBe('en')
    expect(pickLocale('fr')).toBe('en')
  })

  it('falls back to en for empty or malformed input', () => {
    expect(pickLocale('')).toBe('en')
    expect(pickLocale('   ')).toBe('en')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd desktop/electron && npx vitest run test/splash-i18n.test.ts`
Expected: FAIL — `Cannot find module '../src/window/splash-i18n'`

- [ ] **Step 3: Write minimal implementation**

Create `desktop/electron/src/window/splash-i18n.ts`:

```ts
// 阶段文案词表。注意：resources/splash.html 内 <script> 中有一份内容相同的
// MESSAGES/BRAND 镜像（splash HTML 无法 import TS）。修改本文件时必须同步那边。

export type SplashStage = 'spawning' | 'port-ready' | 'health-ready' | 'loading-ui'
export type SplashLocale = 'zh' | 'en'

export const MESSAGES: Record<SplashLocale, Record<SplashStage, string>> = {
  zh: {
    'spawning': '正在启动蜂语…',
    'port-ready': '正在初始化服务…',
    'health-ready': '正在加载工作区…',
    'loading-ui': '即将就绪',
  },
  en: {
    'spawning': 'Starting FengYu…',
    'port-ready': 'Initializing service…',
    'health-ready': 'Loading workspace…',
    'loading-ui': 'Almost ready',
  },
}

export const BRAND: Record<SplashLocale, { name: string; sub: string }> = {
  zh: { name: '蜂语', sub: 'Infinia · FengYu' },
  en: { name: 'Infinia', sub: 'FengYu · 蜂语' },
}

/**
 * Resolve a BCP-47 locale string (e.g. app.getLocale() → "zh-CN", "en-US") to a
 * splash locale. Any non-zh tag falls back to English.
 */
export function pickLocale(raw: string): SplashLocale {
  const tag = (raw || '').toLowerCase().split('-')[0]
  return tag === 'zh' ? 'zh' : 'en'
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd desktop/electron && npx vitest run test/splash-i18n.test.ts`
Expected: PASS — 4 tests passed.

- [ ] **Step 5: Verify TS compiles**

Run: `cd desktop/electron && npm run build:ts`
Expected: exit 0, `dist/window/splash-i18n.js` produced.

- [ ] **Step 6: Commit**

```bash
git add desktop/electron/src/window/splash-i18n.ts desktop/electron/test/splash-i18n.test.ts
git commit -m "✨ feat(desktop): add splash i18n word table and pickLocale"
```

---

### Task 2: splash-preload — contextBridge 暴露 onProgress

**Files:**
- Create: `desktop/electron/src/window/splash-preload.ts`

**Interfaces:**
- Produces: 编译产物 `dist/window/splash-preload.js`，被 splash BrowserWindow 的 `webPreferences.preload` 引用。在 renderer 侧暴露 `window.splash.onProgress(cb)`，`cb` 接收 `{ stage: string; ts: number }`。

**Note:** preload 脚本无法用 vitest 直接单测（依赖 Electron runtime），其行为通过 Task 5（create-splash）和 Task 9（端到端）间接验证。本任务只保证 TS 编译通过。

- [ ] **Step 1: Write the preload**

Create `desktop/electron/src/window/splash-preload.ts`:

```ts
import { contextBridge, ipcRenderer } from 'electron'

// Minimal bridge: lets splash.html subscribe to main-process progress updates
// without exposing any privileged surface. The payload shape mirrors what
// create-splash.ts sends via webContents.send('splash:progress', ...).
contextBridge.exposeInMainWorld('splash', {
  onProgress: (cb: (p: { stage: string; ts: number }) => void): void => {
    ipcRenderer.on('splash:progress', (_event, payload) => cb(payload))
  },
})
```

- [ ] **Step 2: Verify TS compiles**

Run: `cd desktop/electron && npm run build:ts`
Expected: exit 0, `dist/window/splash-preload.js` produced.

- [ ] **Step 3: Commit**

```bash
git add desktop/electron/src/window/splash-preload.ts
git commit -m "✨ feat(desktop): add splash preload exposing onProgress bridge"
```

---

### Task 3: 在 health / spawn / orchestrator 注入可选 onProgress 回调

**Files:**
- Modify: `desktop/electron/src/util/health.ts` (interface `PollHealthOptions` line 8-21, function `pollHealth` line 26-64)
- Modify: `desktop/electron/src/backend/spawn.ts` (interface `SpawnOptions` line 8-16, function `spawnBackend` line 59-104, function `readPort` line 106-155)
- Modify: `desktop/electron/src/backend/orchestrator.ts` (interface `StartBackendOptions` line 13-20, function `startBackend` line 26-54)
- Test: `desktop/electron/test/health.test.ts`
- Test: `desktop/electron/test/spawn.test.ts`

**Interfaces:**
- Consumes: `SplashStage` from `../window/splash-i18n`（Task 1）
- Produces: `PollHealthOptions.onProgress?: (stage: SplashStage) => void`、`SpawnOptions.onProgress?`、`StartBackendOptions.onProgress?`。Task 7 的 main.ts 会传入 `(stage) => sendProgress(splash, stage)`。

- [ ] **Step 1: Add failing test for health onProgress**

Append to `desktop/electron/test/health.test.ts` (inside the existing `describe('pollHealth', ...)` block):

```ts
  it('invokes onProgress with health-ready on first 200', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    const onProgress = vi.fn()
    await pollHealth({
      port: 24056,
      token: 't',
      fetchImpl: fetchImpl as unknown as typeof fetch,
      onProgress,
    })
    expect(onProgress).toHaveBeenCalledOnce()
    expect(onProgress).toHaveBeenCalledWith('health-ready')
  })
```

- [ ] **Step 2: Run health test to verify it fails**

Run: `cd desktop/electron && npx vitest run test/health.test.ts`
Expected: FAIL — `onProgress` not called (or type error: onProgress not in options).

- [ ] **Step 3: Add onProgress to health.ts**

In `desktop/electron/src/util/health.ts`:

(a) Add import at top (after existing content, line 1-5 region):
```ts
import type { SplashStage } from '../window/splash-i18n'
```

(b) Add field to `PollHealthOptions` interface (after `requestTimeoutMs?: number` at line 20):
```ts
  /** Called once when the backend first reports healthy (HTTP 200). Optional. */
  onProgress?: (stage: SplashStage) => void
```

(c) Destructure `onProgress` in `pollHealth` (add to the destructure block at lines 27-37, after `requestTimeoutMs = 2_000,`):
```ts
    onProgress,
```

(d) Invoke on success — change line 56 (`if (resp.status === 200) return`) to:
```ts
      if (resp.status === 200) {
        onProgress?.('health-ready')
        return
      }
```

- [ ] **Step 4: Run health test to verify it passes**

Run: `cd desktop/electron && npx vitest run test/health.test.ts`
Expected: PASS — all tests including the new one.

- [ ] **Step 5: Add failing test for spawn onProgress**

Open `desktop/electron/test/spawn.test.ts` and read its existing structure first. Append a new test (inside the existing top-level describe) that asserts `onProgress` is called with `'port-ready'` after the backend reports `FENGYU_PORT=<n>`. Use the same stdout-mocking pattern the existing tests use (read the file to copy the exact helper). Schematic:

```ts
  it('invokes onProgress with port-ready when FENGYU_PORT is parsed', async () => {
    // Reuse the existing spawn test harness in this file: mock ChildProcess stdout
    // to emit "FENGYU_PORT=24056\n", mock existsSync/resolveJava as the other tests do.
    const onProgress = vi.fn()
    const result = await spawnBackend({
      layout: <same mock layout used by neighboring tests>,
      token: 't',
      requestedPort: 24056,
      onProgress,
    })
    expect(result.port).toBe(24056)
    expect(onProgress).toHaveBeenCalledWith('port-ready')
  })
```

**Important:** Before writing this test, `Read` `test/spawn.test.ts` fully and copy the exact mock setup (the `layout` shape, the `spawn` mock, the stdout emission mechanism) the neighboring tests use. Do not invent a new mocking pattern.

- [ ] **Step 6: Run spawn test to verify it fails**

Run: `cd desktop/electron && npx vitest run test/spawn.test.ts`
Expected: FAIL — `onProgress` not called, or type error.

- [ ] **Step 7: Add onProgress to spawn.ts**

In `desktop/electron/src/backend/spawn.ts`:

(a) Add import (after line 5 `import { parseFengyuPort } from './handshake'`):
```ts
import type { SplashStage } from '../window/splash-i18n'
```

(b) Add field to `SpawnOptions` interface (after `pollIntervalMs?: number` at line 16):
```ts
  /** Called when the backend reports its bound port. Optional. */
  onProgress?: (stage: SplashStage) => void
```

(c) In `spawnBackend`, after `port = await readPort(...)` succeeds (line 97) and before the `return { child, port }` (line 103), call:
```ts
  opts.onProgress?.('port-ready')
```
Place it right after the `try { port = await readPort(...) } catch { ... }` block, so it fires only when a port was successfully read.

(d) Thread `onProgress` into `readPort` only if needed — **it is NOT needed**. The progress callback fires from `spawnBackend` after `readPort` resolves, not inside `readPort`. Do not modify `readPort`'s signature.

- [ ] **Step 8: Run spawn test to verify it passes**

Run: `cd desktop/electron && npx vitest run test/spawn.test.ts`
Expected: PASS — all tests including the new one.

- [ ] **Step 9: Add onProgress to orchestrator.ts**

In `desktop/electron/src/backend/orchestrator.ts`:

(a) Add import (after line 4 `import type { RuntimeLayout } from './runtime-layout'`):
```ts
import type { SplashStage } from '../window/splash-i18n'
```

(b) Add field to `StartBackendOptions` interface (after `onBackendLine?: (line: string) => void` at line 19):
```ts
  /** Forwarded to spawn (port-ready) and health (health-ready). Optional. */
  onProgress?: (stage: SplashStage) => void
```

(c) In `startBackend`, pass `onProgress` into both `spawnBackend` (lines 28-34) and `pollHealth` (line 37). Edit the `spawnBackend` call:
```ts
  const { child, port } = await spawnBackend({
    layout,
    token,
    requestedPort,
    shouldCancel: opts.shouldCancel,
    onLine: opts.onBackendLine,
    onProgress: opts.onProgress,
  })
```
Edit the `pollHealth` call (line 37):
```ts
    await pollHealth({ port, token, shouldCancel: opts.shouldCancel, fetchImpl: opts.fetchImpl, onProgress: opts.onProgress })
```

- [ ] **Step 10: Run full test suite + TS compile**

Run: `cd desktop/electron && npm test`
Expected: PASS — all existing + new tests, no regressions.

Run: `cd desktop/electron && npm run build:ts`
Expected: exit 0.

- [ ] **Step 11: Commit**

```bash
git add desktop/electron/src/util/health.ts desktop/electron/src/backend/spawn.ts desktop/electron/src/backend/orchestrator.ts desktop/electron/test/health.test.ts desktop/electron/test/spawn.test.ts
git commit -m "✨ feat(desktop): thread optional onProgress through health/spawn/orchestrator"
```

---

### Task 4: splash.html — 自包含 UI

**Files:**
- Create: `desktop/electron/resources/splash.html`

**Interfaces:**
- Consumes: `?lang=<zh|en>` query param（由 `createSplashWindow` 通过 `loadFile(..., { query })` 注入，Task 5）；`window.splash.onProgress(cb)`（由 splash-preload.ts 暴露，Task 2）。
- Produces: 一个静态 HTML 文件，被 `createSplashWindow` 的 `loadFile` 加载。

**Note:** HTML 是静态资源，不参与 tsc 编译，无法用 vitest 单测。视觉行为通过 Task 9（端到端手动验证）覆盖。词表必须与 `splash-i18n.ts` 的 `MESSAGES`/`BRAND` 完全一致（已在该文件顶部加注释提示）。

- [ ] **Step 1: Create splash.html**

Create `desktop/electron/resources/splash.html`. The SVG `<path>` data below is copied verbatim from `assets/branding/infinia-app-icon.svg` (the canonical 512px master). The `MESSAGES`/`BRAND` objects mirror `src/window/splash-i18n.ts` exactly.

```html
<!DOCTYPE html>
<html lang data-platform="">
<head>
  <meta charset="UTF-8" />
  <!-- No external resources: inline styles, inline SVG, no network/font fetch. -->
  <meta http-equiv="Content-Security-Policy"
        content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:;" />
  <title>Infinia</title>
  <style>
    :root {
      --bg: #0d0d0d;
      --text: #ededed;
      --text-dim: #a0a0a0;
      --accent: #ededed;
      --radius: 16px;
    }
    html, body { margin: 0; height: 100%; background: transparent; }
    body {
      display: grid;
      place-items: center;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
                   "Microsoft YaHei", "Helvetica Neue", sans-serif;
      -webkit-font-smoothing: antialiased;
    }
    .card {
      width: 416px;
      padding: 36px 32px 32px;
      border-radius: var(--radius);
      background: var(--bg);
      color: var(--text);
      /* Inset border (not outer box-shadow): renders consistently across
         macOS/Windows transparent windows; Linux degrades to a dark rectangle. */
      box-shadow: inset 0 0 0 1px #2a2a2a;
      text-align: center;
    }
    .logo { width: 72px; height: 72px; margin: 0 auto 20px; }
    .logo svg { width: 100%; height: 100%; display: block; }
    .brand { font-size: 18px; font-weight: 600; letter-spacing: 0.5px; }
    .brand .sub {
      display: block;
      font-size: 12px;
      font-weight: 400;
      color: var(--text-dim);
      margin-top: 4px;
      letter-spacing: 0;
    }
    .progress {
      margin-top: 28px;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      min-height: 20px;
    }
    .spinner {
      width: 14px;
      height: 14px;
      border: 2px solid #333;
      border-top-color: var(--accent);
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
    .msg {
      font-size: 13px;
      color: var(--text-dim);
      transition: opacity 0.2s ease;
    }
    .msg.fade { opacity: 0; }
  </style>
</head>
<body>
  <div class="card">
    <div class="logo" aria-hidden="true">
      <!-- Verbatim from assets/branding/infinia-app-icon.svg (512px master). -->
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
        <defs>
          <linearGradient id="tile" x1="0" y1="0" x2="1" y2="1">
            <stop stop-color="#ffffff"/><stop offset="1" stop-color="#edf3ff"/>
          </linearGradient>
          <linearGradient id="glass" x1="0" y1="0" x2="1" y2="1">
            <stop stop-color="#e8f8ff" stop-opacity=".94"/><stop offset=".24" stop-color="#64d2ff" stop-opacity=".78"/>
            <stop offset=".5" stop-color="#0a84ff" stop-opacity=".84"/><stop offset=".76" stop-color="#5e5ce6" stop-opacity=".84"/>
            <stop offset="1" stop-color="#af52de" stop-opacity=".76"/>
          </linearGradient>
          <linearGradient id="front" x1="0" y1="1" x2="1" y2="0">
            <stop stop-color="#64d2ff"/><stop offset=".48" stop-color="#0a84ff"/><stop offset="1" stop-color="#5e5ce6"/>
          </linearGradient>
        </defs>
        <rect x="20" y="20" width="472" height="472" rx="108" fill="url(#tile)"/>
        <rect x="21" y="21" width="470" height="470" rx="107" fill="none" stroke="#fff" stroke-opacity=".86" stroke-width="3"/>
        <g transform="translate(0 2) scale(2)" fill="none" stroke-linecap="round">
          <path d="M128 128C100 79 43 70 34 119C25 169 84 193 128 128C172 63 231 87 222 137C213 186 156 177 128 128" stroke="url(#glass)" stroke-width="42"/>
          <path d="M128 120C100 71 43 62 34 111C25 161 84 185 128 120C172 55 231 79 222 129C213 178 156 169 128 120" stroke="#fff" stroke-opacity=".52" stroke-width="5"/>
          <path d="M89 174C105 163 117 145 128 128C141 108 151 90 169 80" stroke="url(#front)" stroke-opacity=".91" stroke-width="43"/>
          <path d="M95 164C110 152 119 138 128 125C141 105 151 90 165 83" stroke="#fff" stroke-opacity=".58" stroke-width="5"/>
        </g>
      </svg>
    </div>
    <div class="brand">
      <span id="brand-name">…</span>
      <span class="sub" id="brand-sub"></span>
    </div>
    <div class="progress">
      <span class="spinner" aria-hidden="true"></span>
      <span class="msg" id="msg">…</span>
    </div>
  </div>
  <script>
    // Mirror of src/window/splash-i18n.ts MESSAGES/BRAND. Keep in sync.
    var MESSAGES = {
      zh: {
        'spawning': '正在启动蜂语…',
        'port-ready': '正在初始化服务…',
        'health-ready': '正在加载工作区…',
        'loading-ui': '即将就绪'
      },
      en: {
        'spawning': 'Starting FengYu…',
        'port-ready': 'Initializing service…',
        'health-ready': 'Loading workspace…',
        'loading-ui': 'Almost ready'
      }
    };
    var BRAND = {
      zh: { name: '蜂语', sub: 'Infinia · FengYu' },
      en: { name: 'Infinia', sub: 'FengYu · 蜂语' }
    };

    function pickLang(query) {
      var m = /[?&]lang=(zh|en)/.exec(query);
      return m ? m[1] : 'en';
    }

    var lang = pickLang(location.search);
    document.documentElement.setAttribute('lang', lang === 'zh' ? 'zh-CN' : 'en');
    if (window.process && window.process.platform === 'linux') {
      document.documentElement.setAttribute('data-platform', 'linux');
    }

    var brand = BRAND[lang];
    document.getElementById('brand-name').textContent = brand.name;
    document.getElementById('brand-sub').textContent = brand.sub;

    var msgEl = document.getElementById('msg');
    function setStage(stage) {
      var text = (MESSAGES[lang] && MESSAGES[lang][stage]) || MESSAGES.en[stage];
      if (msgEl.textContent === text) return;
      msgEl.classList.add('fade');
      setTimeout(function () {
        msgEl.textContent = text;
        msgEl.classList.remove('fade');
      }, 180);
    }

    // Initial stage
    setStage('spawning');

    // Subscribe to main-process progress updates (splash-preload.ts).
    if (window.splash && typeof window.splash.onProgress === 'function') {
      window.splash.onProgress(function (p) { setStage(p.stage); });
    }
  </script>
</body>
</html>
```

- [ ] **Step 2: Verify the file is valid HTML (quick sanity)**

Run: `cd desktop/electron && node -e "const fs=require('fs');const h=fs.readFileSync('resources/splash.html','utf8');console.log('bytes:',h.length);console.log('has CSP:',/Content-Security-Policy/.test(h));console.log('has onProgress:',/onProgress/.test(h));console.log('has zh+en:',h.includes('正在启动蜂语')&&h.includes('Starting FengYu'));"`
Expected: prints `bytes: <~6000>`, `has CSP: true`, `has onProgress: true`, `has zh+en: true`.

- [ ] **Step 3: Commit**

```bash
git add desktop/electron/resources/splash.html
git commit -m "✨ feat(desktop): add self-contained bilingual splash HTML"
```

---

### Task 5: create-splash.ts — 工厂 + sendProgress + destroySplash

**Files:**
- Create: `desktop/electron/src/window/create-splash.ts`
- Test: `desktop/electron/test/create-splash.test.ts`

**Interfaces:**
- Consumes: `SplashStage`、`pickLocale` from `./splash-i18n`（Task 1）；Electron `BrowserWindow`、`app`。
- Produces:
  - `createSplashWindow(opts: { logger?: { info: (m: string) => void } }): BrowserWindow | null` — 创建并返回 splash 窗口；失败返回 null。
  - `sendProgress(splash: BrowserWindow | null, stage: SplashStage): void` — null-safe 推送进度。
  - `destroySplash(splash: BrowserWindow | null): void` — null-safe 销毁。
- Task 7 的 main.ts 依赖全部三个函数。

- [ ] **Step 1: Write the failing test**

Create `desktop/electron/test/create-splash.test.ts`. Electron's `BrowserWindow` can't be instantiated under vitest without a full Electron runtime, so we test only the **null-safe helpers** (`sendProgress`/`destroySplash` with `null`) and the **sendProgress-with-destroyed-window** path using a minimal stub. The real `createSplashWindow` is covered by Task 9 (end-to-end).

```ts
import { describe, it, expect, vi } from 'vitest'
// Stub electron before importing the module under test.
vi.mock('electron', () => ({
  BrowserWindow: vi.fn(),
  app: { getLocale: () => 'en-US' },
}))

import { sendProgress, destroySplash } from '../src/window/create-splash'

describe('sendProgress', () => {
  it('is a no-op when splash is null', () => {
    expect(() => sendProgress(null, 'health-ready')).not.toThrow()
  })

  it('is a no-op when splash is destroyed', () => {
    const fakeWebContents = { send: vi.fn() }
    const splash = {
      isDestroyed: () => true,
      webContents: fakeWebContents,
    }
    expect(() => sendProgress(splash as any, 'health-ready')).not.toThrow()
    expect(fakeWebContents.send).not.toHaveBeenCalled()
  })

  it('sends splash:progress with stage + ts when alive', () => {
    const fakeWebContents = { send: vi.fn() }
    const splash = {
      isDestroyed: () => false,
      webContents: fakeWebContents,
    }
    sendProgress(splash as any, 'port-ready')
    expect(fakeWebContents.send).toHaveBeenCalledWith('splash:progress', {
      stage: 'port-ready',
      ts: expect.any(Number),
    })
  })
})

describe('destroySplash', () => {
  it('is a no-op when splash is null', () => {
    expect(() => destroySplash(null)).not.toThrow()
  })

  it('is a no-op when splash is already destroyed', () => {
    const splash = { isDestroyed: () => true, destroy: vi.fn() }
    destroySplash(splash as any)
    expect(splash.destroy).not.toHaveBeenCalled()
  })

  it('calls destroy when alive', () => {
    const splash = { isDestroyed: () => false, destroy: vi.fn() }
    destroySplash(splash as any)
    expect(splash.destroy).toHaveBeenCalledOnce()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd desktop/electron && npx vitest run test/create-splash.test.ts`
Expected: FAIL — `Cannot find module '../src/window/create-splash'`.

- [ ] **Step 3: Write minimal implementation**

Create `desktop/electron/src/window/create-splash.ts`:

```ts
import { BrowserWindow, app } from 'electron'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { pickLocale, type SplashStage } from './splash-i18n'

interface Logger {
  info: (message: string) => void
}

interface CreateSplashOptions {
  logger?: Logger
}

/**
 * Resolve splash.html on disk. Dev: <cwd>/resources/splash.html (cwd is
 * desktop/electron). Packaged: <dist>/../resources/splash.html (mirrors the
 * icon-resolution pattern in src/desktop/tray.ts).
 */
function resolveSplashHtml(): string {
  const devPath = join(process.cwd(), 'resources', 'splash.html')
  const prodPath = join(__dirname, '..', 'resources', 'splash.html')
  return existsSync(devPath) ? devPath : prodPath
}

/**
 * Create and show the splash window. Returns the window, or null if creation
 * failed (the main process must treat null as "no splash" and continue booting).
 *
 * Frameless + transparent on macOS/Windows; opaque dark rectangle on Linux
 * (some compositors render transparent windows incorrectly).
 */
export function createSplashWindow(opts: CreateSplashOptions = {}): BrowserWindow | null {
  try {
    const isLinux = process.platform === 'linux'
    const splash = new BrowserWindow({
      width: 480,
      height: 320,
      frame: false,
      transparent: !isLinux,
      resizable: false,
      maximizable: false,
      minimizable: false,
      fullscreenable: false,
      skipTaskbar: true,
      show: false,
      center: true,
      focusable: false,
      backgroundColor: isLinux ? '#0d0d0d' : undefined,
      webPreferences: {
        preload: join(__dirname, 'splash-preload.js'),
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
      },
    })

    const locale = pickLocale(app.getLocale())
    const splashFile = resolveSplashHtml()
    void splash.loadFile(splashFile, { query: { lang: locale } })

    splash.once('ready-to-show', () => {
      if (!splash.isDestroyed()) splash.show()
    })

    splash.webContents.on('did-fail-load', (_e, errorCode, errorDescription) => {
      // Splash is decorative: log and continue, never block the main boot.
      opts.logger?.info(`[desktop] splash load failed: ${errorCode} ${errorDescription}`)
    })

    return splash
  } catch (err) {
    opts.logger?.info(`[desktop] splash creation failed: ${err instanceof Error ? err.message : String(err)}`)
    return null
  }
}

/**
 * Push a progress update to the splash renderer. Null-safe and destroyed-safe.
 */
export function sendProgress(splash: BrowserWindow | null, stage: SplashStage): void {
  if (!splash || splash.isDestroyed()) return
  splash.webContents.send('splash:progress', { stage, ts: Date.now() })
}

/**
 * Destroy the splash window if it exists and is still alive. Null-safe.
 */
export function destroySplash(splash: BrowserWindow | null): void {
  if (!splash || splash.isDestroyed()) return
  splash.destroy()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd desktop/electron && npx vitest run test/create-splash.test.ts`
Expected: PASS — 6 tests.

- [ ] **Step 5: Verify TS compiles**

Run: `cd desktop/electron && npm run build:ts`
Expected: exit 0, `dist/window/create-splash.js` + `dist/window/splash-preload.js` produced.

- [ ] **Step 6: Commit**

```bash
git add desktop/electron/src/window/create-splash.ts desktop/electron/test/create-splash.test.ts
git commit -m "✨ feat(desktop): add createSplashWindow/sendProgress/destroySplash"
```

---

### Task 6: create-window — ready-to-show 回调 onMainReady

**Files:**
- Modify: `desktop/electron/src/window/create-window.ts` (interface `CreateWindowOptions` line 4-20, function `createMainWindow` ready-to-show handler line 115-117)
- Test: `desktop/electron/test/window-open-handler.test.ts`（先读，看是否覆盖 ready-to-show；若不覆盖则跳过新增测试，因为 ready-to-show 难以单测）

**Interfaces:**
- Consumes: 无新依赖。
- Produces: `CreateWindowOptions.onMainReady?: () => void`。Task 7 的 main.ts 传入 `() => destroySplash(splash)`。

- [ ] **Step 1: Add onMainReady to the options interface**

In `desktop/electron/src/window/create-window.ts`, add to `CreateWindowOptions` (after `isQuitting: () => boolean` at line 19):

```ts
  /**
   * Called once when the main window finishes its first paint (ready-to-show).
   * Used by main.ts to tear down the splash window at the exact moment the main
   * window becomes visible — closing it earlier would leave a window-less gap.
   */
  onMainReady?: () => void
```

- [ ] **Step 2: Invoke onMainReady in the ready-to-show handler**

Replace lines 115-117:
```ts
  win.once('ready-to-show', () => {
    if (!opts.isQuitting()) win.show()
  })
```
with:
```ts
  win.once('ready-to-show', () => {
    if (!opts.isQuitting()) win.show()
    opts.onMainReady?.()
  })
```

- [ ] **Step 3: Verify TS compiles + existing tests still pass**

Run: `cd desktop/electron && npm run build:ts`
Expected: exit 0.

Run: `cd desktop/electron && npm test`
Expected: PASS — all existing tests (the new optional field doesn't break any caller since none pass it yet).

- [ ] **Step 4: Commit**

```bash
git add desktop/electron/src/window/create-window.ts
git commit -m "✨ feat(desktop): fire onMainReady on main window ready-to-show"
```

---

### Task 7: main.ts — 接线 splash 生命周期

**Files:**
- Modify: `desktop/electron/src/main.ts` (imports line 1-14, `bootstrap()` line 84-242)

**Interfaces:**
- Consumes: `createSplashWindow`、`sendProgress`、`destroySplash` from `./window/create-splash`（Task 5）；`onProgress` 参数 on `startBackend`/`pollHealth`（Task 3）；`onMainReady` on `createMainWindow`（Task 6）。

**Note:** main.ts 是 Electron 主进程入口，无法用 vitest 单测。正确性通过 Task 9（端到端手动 + e2e-smoke）验证。本任务需极其小心地保留所有现有错误路径。

- [ ] **Step 1: Add imports**

In `desktop/electron/src/main.ts`, after line 9 (`import { createMainWindow } from './window/create-window'`), add:

```ts
import { createSplashWindow, sendProgress, destroySplash } from './window/create-splash'
```

- [ ] **Step 2: Create splash at the top of bootstrap()**

In `bootstrap()` (line 84), change:
```ts
async function bootstrap(): Promise<void> {
  registerDialogIpc()
```
to:
```ts
async function bootstrap(): Promise<void> {
  registerDialogIpc()

  // Show the splash immediately — before any backend work — so the user sees
  // feedback during the JVM cold start + Spring context init (the longest gap).
  const splash = createSplashWindow({ logger })
```

- [ ] **Step 3: Wire onProgress + destroySplash into the external-backend (dev) path**

In the `if (externalBackend) { ... }` block:

(a) Before the `await pollHealth(...)` at line 99, add initial progress:
```ts
    sendProgress(splash, 'spawning')
```

(b) Change the `pollHealth` call (line 99) to pass `onProgress`:
```ts
      await pollHealth({ baseUrl: externalBackend, token, shouldCancel: () => isQuitting, onProgress: (s) => sendProgress(splash, s) })
```

(c) In BOTH catch blocks in this branch (lines 100-108 and 112-120), add `destroySplash(splash)` as the **first statement** inside the catch (before `dialog.showErrorBox`):
```ts
    } catch (err) {
      destroySplash(splash)
      dialog.showErrorBox(
```
Apply this to both the `pollHealth` catch (line 100) and the `ensureDevFrontend` catch (line 112).

(d) Before `createMainWindow` (line 122), send the final stage:
```ts
    sendProgress(splash, 'loading-ui')
```

(e) Change the `createMainWindow` call (lines 122-128) to pass `onMainReady`:
```ts
    const win = createMainWindow({
      apiBase: externalBackend,
      token,
      onHideToTray: () => logger.info('[desktop] window hidden to tray'),
      isDev: true,
      isQuitting: () => isQuitting,
      onMainReady: () => destroySplash(splash),
    })
```

- [ ] **Step 4: Wire onProgress + destroySplash into the packaged (jar) path**

(a) Before `startBackend` (line 144), add initial progress. Insert right after `process.env.FENGYU_API_BASE = ''` (line 140):
```ts
  sendProgress(splash, 'spawning')
```

(b) Change the `startBackend` call (lines 144-150) to pass `onProgress`:
```ts
    started = await startBackend({
      layout,
      token,
      requestedPort: 24056,
      onBackendLine: logger.backendLine,
      shouldCancel: () => isQuitting,
      onProgress: (s) => sendProgress(splash, s),
    })
```

(c) In the `startBackend` catch block (line 151), add `destroySplash(splash)` as the first statement:
```ts
  } catch (err) {
    destroySplash(splash)
    const msg = err instanceof Error ? err.message : String(err)
```

(d) In the `ensureDevFrontend` catch block (line 219, inside `if (!isPackaged)`), add `destroySplash(splash)` as the first statement:
```ts
    } catch (err) {
      destroySplash(splash)
      dialog.showErrorBox(
        'Frontend not reachable',
```

(e) Before `createMainWindow` (line 230), send the final stage:
```ts
  sendProgress(splash, 'loading-ui')
```

(f) Change the `createMainWindow` call (lines 230-236) to pass `onMainReady`:
```ts
  const win = createMainWindow({
    apiBase,
    token,
    onHideToTray: () => logger.info('[desktop] window hidden to tray'),
    isDev: !isPackaged,
    isQuitting: () => isQuitting,
    onMainReady: () => destroySplash(splash),
  })
```

- [ ] **Step 5: Verify TS compiles**

Run: `cd desktop/electron && npm run build:ts`
Expected: exit 0. If type errors appear (e.g. `onProgress` not assignable), re-check Task 3 edits landed in all three files.

- [ ] **Step 6: Verify existing tests still pass**

Run: `cd desktop/electron && npm test`
Expected: PASS — main.ts isn't unit-tested, but this confirms no imported module regressed.

- [ ] **Step 7: Commit**

```bash
git add desktop/electron/src/main.ts
git commit -m "✨ feat(desktop): wire splash lifecycle into bootstrap (create/progress/destroy)"
```

---

### Task 8: electron-builder.yml — 打包 splash.html

**Files:**
- Modify: `desktop/electron/electron-builder.yml` (`files:` section line 37-44)

**Interfaces:** 无。

- [ ] **Step 1: Add splash.html to the files list**

In `desktop/electron/electron-builder.yml`, in the `files:` list (lines 37-44), add `resources/splash.html`. Insert it right after `frontend-dist/**/*` (line 39):

```yaml
files:
  - dist/**/*
  - frontend-dist/**/*
  - resources/splash.html
  - resources/icon.png
  - resources/icon.ico
  - resources/icon-32.png
  - resources/icon-128.png
  - package.json
```

**Why only splash.html (not the whole resources/ dir):** The icons are already consumed by electron-builder as build-time `mac.icon`/`win.icon` and are ALSO listed in `files` for in-asar lookup (per the file's header comment, lines 15-19). Adding the whole dir would pull in `resources/binaries/` (the JAR) twice. splash.html is the only new asset that must ride inside app.asar for `loadFile` to find it via `__dirname/../resources/`.

- [ ] **Step 2: Verify splash.html is NOT in extraResources**

Read the `extraResources:` section (lines 54-68) — it should NOT contain `splash.html`. The splash is loaded via `__dirname/../resources/splash.html` which resolves inside app.asar (where `files:` places it), NOT via `process.resourcesPath` (which is the extraResources root). No change needed here; this step is a confirmation.

- [ ] **Step 3: Verify the build includes splash.html**

Run: `cd desktop/electron && npm run build:ts && npx electron-builder --dir --mac --config electron-builder.yml`
Expected: build succeeds (may take a few minutes). Then verify:

Run: `find ../dist-electron -name 'splash.html' -not -path '*/node_modules/*'`
Expected: at least one path like `../dist-electron/mac-arm64/Infinia.app/Contents/Resources/app.asar/resources/splash.html` OR unpacked under `app.asar.unpacked`. (electron-builder may keep it inside the asar — that's fine, `loadFile` reads from asar.)

**If the build is too slow or errors on code-signing:** as a lighter check, run `cd desktop/electron && npx electron-builder --dir --linux --config electron-builder.yml` (Linux dir build is fastest and skips signing), then `find ../dist-electron -name 'splash.html'`.

- [ ] **Step 4: Commit**

```bash
git add desktop/electron/electron-builder.yml
git commit -m "📝 docs(desktop): include resources/splash.html in packaged app.asar"
```

---

### Task 9: 端到端验证

**Files:** 无（仅验证）。

**Interfaces:** 无。

- [ ] **Step 1: Run the full desktop test suite**

Run: `cd desktop/electron && npm test`
Expected: PASS — all tests (health, spawn, splash-i18n, create-splash, and all pre-existing).

- [ ] **Step 2: Run TS build**

Run: `cd desktop/electron && npm run build:ts`
Expected: exit 0. Confirm `dist/window/splash-preload.js`, `dist/window/splash-i18n.js`, `dist/window/create-splash.js` exist.

Run: `ls desktop/electron/dist/window/`
Expected: includes `splash-preload.js`, `splash-i18n.js`, `create-splash.js`.

- [ ] **Step 3: Run the e2e smoke test (backend boot probe)**

Run: `cd /Users/phoebej/Develop/Java/FengYu && ./scripts/e2e-smoke.sh`
Expected: PASS — confirms the `onProgress` injection into orchestrator/spawn/health did not change backend behavior. (This script boots the JAR directly, not via Electron, so it can't verify the splash visually — but it proves the backend path still works.)

- [ ] **Step 4: Manual visual verification (dev mode — IDE backend)**

Prerequisite: start the backend in your IDE (or `mvn -pl FengYu spring-boot:run` without `--token`), so it listens on `127.0.0.1:24056` with auth disabled.

Run: `cd desktop/electron && npm run dev`
Expected behavior:
1. A 480×320 frameless transparent window appears immediately, centered, showing the Infinia logo + "蜂语" / "Infinia · FengYu" + a spinner + the localized "Starting FengYu…" (or Chinese equivalent based on your system locale).
2. The progress text transitions (with a brief fade) through the stages as the backend comes up. In dev mode (IDE backend), `port-ready` is skipped; you'll see `spawning` → `health-ready` → `loading-ui`.
3. When the main Vue window finishes first paint, the splash disappears and the main window is visible.

**Check failure paths:** Stop the IDE backend, then `npm run dev` again. Confirm: splash appears, then closes, then the existing "Backend not reachable" error dialog shows (no zombie splash left behind).

- [ ] **Step 5: Manual visual verification (packaged mode)**

Run: `cd desktop/electron && npm run build:mac` (or your platform's `build:win`/`build:linux`).
Then launch the built `.app` / `.exe` / AppImage.
Expected: same splash behavior as Step 4, but exercising the full JAR-spawn path (you should see the `port-ready` stage visibly — "Initializing service…" / "正在初始化服务…" — between spawn and health).

**If you can't build locally (e.g. no signing identity):** `npm run build:ts && npx electron .` with `FENGYU_JAR=<path-to-built-FengYu.jar>` runs the packaged-path logic against a real JAR without electron-builder packaging — this exercises the spawn + progress + onMainReady wiring.

- [ ] **Step 6: Final commit (if any verification surfaced fixes)**

If Steps 4-5 revealed issues and you fixed them, commit those fixes now with appropriate `🐛 fix(desktop): ...` messages. Otherwise, no commit needed — the feature is complete.

---

## Self-Review Notes

**Spec coverage check** (each spec section → task):
- §4.1 启动时序 → Task 7 (main.ts wiring) + Task 6 (onMainReady)
- §4.2 模块职责 → Task 1-6 (each module = one task)
- §5.1 BrowserWindow 选项 → Task 5 (create-splash.ts)
- §5.2 进度推送通道 → Task 2 (preload) + Task 5 (sendProgress)
- §5.3 splash.html 结构 → Task 4
- §5.4 i18n 翻译表 → Task 1 (TS source) + Task 4 (HTML mirror)
- §5.5 main.ts 改动点（4 处 destroySplash）→ Task 7 Steps 3c/3d/4c/4d
- §5.6 create-window ready-to-show → Task 6
- §5.7 平台差异（Linux 降级）→ Task 5 (create-splash.ts `isLinux`)
- §5.8 资源解析 → Task 5 (resolveSplashHtml)
- §6 边界情况（did-fail-load / null-safe / window-all-closed）→ Task 5 (did-fail-load handler + null-guards); window-all-closed confirmed no-op in spec §6 (no change needed)
- §7.1 electron-builder.yml → Task 8
- §7.2 TypeScript 构建 → Task 2/5 (build:ts verification)
- §8 验证策略 → Task 9

**Placeholder scan:** No TBD/TODO/vague steps. Every code step contains complete code. ✓

**Type consistency:**
- `SplashStage` defined in Task 1, imported in Task 3 (health/spawn/orchestrator) and Task 5 (create-splash). ✓
- `onProgress?: (stage: SplashStage) => void` consistent across `PollHealthOptions`/`SpawnOptions`/`StartBackendOptions`. ✓
- `sendProgress(splash: BrowserWindow | null, stage: SplashStage)` matches main.ts call sites `(s) => sendProgress(splash, s)`. ✓
- `destroySplash(splash: BrowserWindow | null)` matches `onMainReady: () => destroySplash(splash)`. ✓
- `onMainReady?: () => void` in `CreateWindowOptions` matches `opts.onMainReady?.()` call. ✓

**Scope check:** Single feature, single desktop module, no frontend/backend changes. Appropriate for one plan. ✓
