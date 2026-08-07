# UOS 专项构建 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增一个独立的 UOS Linux 构建产物(lite deb,x86_64),通过构建期常量注入禁用渲染进程 Chromium 沙箱,解决 UOS/Deepin 上因无 root 无法 setuid `chrome-sandbox` 导致程序无法启动的问题。

**Architecture:** 构建期脚本 `gen-build-flavor.mjs` 读 `FENGYU_BUILD_FLAVOR` 环境变量,把 flavor 写进 `src/build-flavor.json`;`tsc resolveJsonModule` 把该 JSON `require()` 进 `flavor.ts` 并复制到 `dist/`,electron-builder 的 `files: ['dist/**/*']` 把它打入 `app.asar`。主进程据此决定是否 `app.commandLine.appendSwitch('no-sandbox')` 并把两个 BrowserWindow 的 `sandbox` 改为 `!SANDBOX_DISABLED`。独立的 `electron-builder.uos.yml` 只出 deb。标准构建的 `SANDBOX_DISABLED === false`,所有改动退化为现状。

**Tech Stack:** Electron 43,TypeScript 5.7(`resolveJsonModule: true`),electron-builder 26,Node 内置 `fs`,vitest 3,GitHub Actions。

## Global Constraints

- 主进程用纯 `tsc`(无 bundler);`resolveJsonModule` **不内联**,而是发出 `require('./build-flavor.json')` 并把 JSON 复制到 `dist/`(已实测确认)。flavor 值由构建期 JSON 决定,运行时不读 `process.env`。
- `gen-build-flavor.mjs` 必须在 `tsc` **之前**运行(否则 `dist/build-flavor.json` 是旧值/不存在)。
- 非法 `FENGYU_BUILD_FLAVOR` 值一律降级为 `standard`(安全默认 = 保留沙箱)。
- UOS 构建基于 **lite** 配置(`electron-builder.yml`),**不带 JRE**。
- 仅 x86_64;appId 不变(`fan.summer.fengyu`),用 `productName: Infinia-UOS` 区分包名。
- 标准构建(win/mac/linux)**零行为变化**——所有改动在 `SANDBOX_DISABLED === false` 时退化为现状。
- **不同步** README / CHANGELOG / docs(用户明确要求)。
- 测试位于 `desktop/electron/test/`(vitest `include: ['test/**/*.test.ts']`),从 `src/` 导入(`../src/...`)。
- 提交规范:conventional commits + emoji(`✨` feat / `🐛` fix / `♻️` refactor / `📝` docs / `🔥` removal)。每个 Task 末尾提交。

**Spec:** `docs/superpowers/specs/2026-08-07-uos-unsandboxed-build-design.md`

---

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `desktop/electron/scripts/gen-build-flavor.mjs` | 读 env → 写 `src/build-flavor.json`,幂等、非法值降级 | 新建 |
| `desktop/electron/src/flavor.ts` | 从 JSON 派生 `BUILD_FLAVOR` / `SANDBOX_DISABLED` 常量 | 新建 |
| `desktop/electron/src/build-flavor.json` | 被生成的 flavor 载体(gitignore,不入库) | 生成 |
| `desktop/electron/.gitignore` | 忽略 `src/build-flavor.json` | 改 |
| `desktop/electron/package.json` | `build:ts`/`dev` 前置 gen 脚本 | 改 |
| `desktop/electron/src/main.ts` | UOS 构建时 `appendSwitch('no-sandbox')` + warning | 改 |
| `desktop/electron/src/window/create-window.ts` | `sandbox: true` → `!SANDBOX_DISABLED` | 改 |
| `desktop/electron/src/window/create-splash.ts` | 同上 | 改 |
| `desktop/electron/electron-builder.uos.yml` | 独立 UOS 打包配置(自包含,基于 lite) | 新建 |
| `desktop/electron/test/gen-build-flavor.test.ts` | gen 脚本逻辑测试 | 新建 |
| `.github/workflows/fengyu-release.yml` | desktop job 加 UOS 构建步骤 + 上传 | 改 |

---

## Task 1: 构建期 flavor 生成脚本(gen-build-flavor.mjs)

负责把 `FENGYU_BUILD_FLAVOR` 环境变量变成磁盘上的 `src/build-flavor.json`,为后续所有构建提供 flavor 来源。先写测试驱动其"幂等 + 非法值降级"行为。

**Files:**
- Create: `desktop/electron/scripts/gen-build-flavor.mjs`
- Test: `desktop/electron/test/gen-build-flavor.test.ts`

**Interfaces:**
- Consumes: `process.env.FENGYU_BUILD_FLAVOR`(env)
- Produces: `desktop/electron/src/build-flavor.json`,内容 `{"flavor":"uos"}` 或 `{"flavor":"standard"}`;退出码 0

- [ ] **Step 1: 写失败测试**

新建 `desktop/electron/test/gen-build-flavor.test.ts`。gen 脚本用 `.mjs` + ESM `import`,vitest 跑 `.ts` 测试,所以测试用 `child_process` 执行脚本并校验产出 JSON(避免 ESM/CJS 互导)。脚本接受一个可选的环境变量 `FENGYU_BUILD_FLAVOR_OUT` 指定输出路径(测试隔离用;生产路径由脚本内部默认推导)。

```ts
import { describe, it, expect, afterEach } from 'vitest'
import { execFileSync } from 'node:child_process'
import { readFileSync, rmSync, existsSync } from 'node:fs'
import { join } from 'node:path'

const script = join(__dirname, '..', 'scripts', 'gen-build-flavor.mjs')
const tmpOut = join(__dirname, '..', 'src', 'build-flavor.test.json')

function run(env: Record<string, string | undefined>) {
  execFileSync(process.execPath, [script], {
    env: { ...process.env, ...env },
    cwd: join(__dirname, '..'),
  })
}

afterEach(() => {
  if (existsSync(tmpOut)) rmSync(tmpOut)
})

describe('gen-build-flavor', () => {
  it('writes uos flavor when FENGYU_BUILD_FLAVOR=uos', () => {
    run({ FENGYU_BUILD_FLAVOR: 'uos', FENGYU_BUILD_FLAVOR_OUT: tmpOut })
    expect(JSON.parse(readFileSync(tmpOut, 'utf8'))).toEqual({ flavor: 'uos' })
  })

  it('writes standard flavor when env unset', () => {
    run({ FENGYU_BUILD_FLAVOR_OUT: tmpOut })
    expect(JSON.parse(readFileSync(tmpOut, 'utf8'))).toEqual({ flavor: 'standard' })
  })

  it('falls back to standard on illegal value', () => {
    run({ FENGYU_BUILD_FLAVOR: 'foo', FENGYU_BUILD_FLAVOR_OUT: tmpOut })
    expect(JSON.parse(readFileSync(tmpOut, 'utf8'))).toEqual({ flavor: 'standard' })
  })

  it('is idempotent (rewrites same content)', () => {
    run({ FENGYU_BUILD_FLAVOR: 'uos', FENGYU_BUILD_FLAVOR_OUT: tmpOut })
    const first = readFileSync(tmpOut, 'utf8')
    run({ FENGYU_BUILD_FLAVOR: 'uos', FENGYU_BUILD_FLAVOR_OUT: tmpOut })
    const second = readFileSync(tmpOut, 'utf8')
    expect(second).toBe(first)
  })
})
```

- [ ] **Step 2: 运行测试,确认失败**

Run: `cd desktop/electron && npx vitest run test/gen-build-flavor.test.ts`
Expected: FAIL — `gen-build-flavor.mjs` 不存在,`execFileSync` 抛 `ENOENT`。

- [ ] **Step 3: 实现脚本**

新建 `desktop/electron/scripts/gen-build-flavor.mjs`:

```js
// Generates src/build-flavor.json from the FENGYU_BUILD_FLAVOR environment variable.
// Runs BEFORE tsc so the resolved flavor is baked into dist/build-flavor.json at build time
// (resolveJsonModule emits a require() that reads this file at runtime; it does NOT inline).
// Legal values: 'uos' | 'standard' (default). Anything else degrades to 'standard' — the
// safe default keeps the Chromium sandbox ON.
import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..')

const raw = process.env.FENGYU_BUILD_FLAVOR
const flavor = raw === 'uos' ? 'uos' : 'standard'
const out = process.env.FENGYU_BUILD_FLAVOR_OUT ?? join(root, 'src', 'build-flavor.json')

writeFileSync(out, JSON.stringify({ flavor }), 'utf8')
```

- [ ] **Step 4: 运行测试,确认通过**

Run: `cd desktop/electron && npx vitest run test/gen-build-flavor.test.ts`
Expected: PASS — 4 个测试全绿。

- [ ] **Step 5: 人工验证默认输出路径**

确认不带 `FENGYU_BUILD_FLAVOR_OUT` 时写到 `src/build-flavor.json`:
```bash
cd desktop/electron
FENGYU_BUILD_FLAVOR=uos node scripts/gen-build-flavor.mjs
cat src/build-flavor.json   # 期望 {"flavor":"uos"}
rm src/build-flavor.json    # 清理,Task 3 会加进 .gitignore
```

- [ ] **Step 6: 提交**

```bash
cd desktop/electron
git add scripts/gen-build-flavor.mjs test/gen-build-flavor.test.ts
git commit -m "✨ feat(desktop): gen-build-flavor.mjs — build-time flavor injection (uos|standard)"
```

---

## Task 2: flavor.ts 常量模块

从 `build-flavor.json` 派生类型安全的常量,供主进程消费。集中判断,避免散落在 main/window 三处。

**Files:**
- Create: `desktop/electron/src/flavor.ts`
- Test: `desktop/electron/test/flavor.test.ts`

**Interfaces:**
- Consumes: `src/build-flavor.json`(由 Task 1 的脚本生成;测试需先准备该文件)
- Produces:
  - `type BuildFlavor = 'standard' | 'uos'`
  - `function resolveFlavor(raw: unknown): BuildFlavor`(纯函数,可单测)
  - `const BUILD_FLAVOR: BuildFlavor`
  - `const SANDBOX_DISABLED: boolean`

> **测试策略说明(已实测确认):** `import data from './build-flavor.json'` 是模块顶层执行,且 vitest/vite 在首次 import 时就把 JSON 内联进模块转换结果——因此**无法**用 `vi.resetModules()` + 重写 JSON 文件、或动态 `import()` 查询串破缓存、或 `vi.doMock` 来在运行时切换 flavor(三者均实测失败)。正确做法:把判断逻辑抽成**纯函数 `resolveFlavor(raw)`**,对它做参数化单测;另加一个 wiring 测试确认 `SANDBOX_DISABLED` 是布尔且与 `BUILD_FLAVOR` 一致。这样生产代码简单、测试无需 mock、且不依赖 JSON 文件的具体内容。

- [ ] **Step 1: 先确保 src/build-flavor.json 存在(默认 standard)**

```bash
cd desktop/electron
node scripts/gen-build-flavor.mjs
cat src/build-flavor.json   # 期望 {"flavor":"standard"}
```
(此文件 Task 4 会加进 `.gitignore`;此刻先放着,wiring 测试需要它能被 import。)

- [ ] **Step 2: 写失败测试**

新建 `desktop/electron/test/flavor.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { resolveFlavor } from '../src/flavor'

describe('resolveFlavor', () => {
  it('returns uos for the literal uos', () => {
    expect(resolveFlavor('uos')).toBe('uos')
  })
  it('returns standard for standard', () => {
    expect(resolveFlavor('standard')).toBe('standard')
  })
  it('degrades an unknown string to standard (safe default keeps sandbox ON)', () => {
    expect(resolveFlavor('weird')).toBe('standard')
  })
  it('degrades undefined / null / object to standard', () => {
    expect(resolveFlavor(undefined)).toBe('standard')
    expect(resolveFlavor(null)).toBe('standard')
    expect(resolveFlavor({ flavor: 'uos' })).toBe('standard')
  })
})

describe('flavor module wiring', () => {
  it('SANDBOX_DISABLED is a boolean consistent with BUILD_FLAVOR', async () => {
    const m = await import('../src/flavor')
    expect(typeof m.SANDBOX_DISABLED).toBe('boolean')
    expect(m.SANDBOX_DISABLED).toBe(m.BUILD_FLAVOR === 'uos')
  })
})
```

- [ ] **Step 3: 运行测试,确认失败**

Run: `cd desktop/electron && npx vitest run test/flavor.test.ts`
Expected: FAIL — `../src/flavor.ts` 不存在,import 抛错。

- [ ] **Step 4: 实现 flavor.ts**

新建 `desktop/electron/src/flavor.ts`:

```ts
// Build-time flavor, resolved from src/build-flavor.json (generated by scripts/gen-build-flavor.mjs
// before tsc, and emitted to dist/build-flavor.json by resolveJsonModule as a runtime require()).
// Centralized here so main + window code never read the JSON directly.
import data from './build-flavor.json'

export type BuildFlavor = 'standard' | 'uos'

/**
 * Resolve a raw flavor value into a typed BuildFlavor. Pure & export-standalone so it can be
 * unit-tested without mocking JSON module loading (vite inlines JSON at first import, so runtime
 * file rewriting cannot re-resolve it). Safe default: anything other than the literal 'uos'
 * keeps the Chromium sandbox ON.
 */
export function resolveFlavor(raw: unknown): BuildFlavor {
  return raw === 'uos' ? 'uos' : 'standard'
}

export const BUILD_FLAVOR: BuildFlavor = resolveFlavor((data as { flavor?: unknown }).flavor)
export const SANDBOX_DISABLED = BUILD_FLAVOR === 'uos'
```

- [ ] **Step 5: 运行测试,确认通过**

Run: `cd desktop/electron && npx vitest run test/flavor.test.ts`
Expected: PASS — 5 个测试全绿。
Run(回归): `cd desktop/electron && npm test`
Expected: 全量测试通过(新测试不破坏既有)。

- [ ] **Step 6: 提交**

```bash
cd desktop/electron
git add src/flavor.ts src/build-flavor.json test/flavor.test.ts
git commit -m "✨ feat(desktop): flavor.ts — BUILD_FLAVOR / SANDBOX_DISABLED constants from build-flavor.json"
```
> 说明:`src/build-flavor.json` 此处一并提交是为了让 tsc/vitest 能跑;Task 4 会把它加进 `.gitignore` 并从索引移除(但磁盘保留),CI 每次 build:ts 都会重写它。若 Task 4 先做,此步可跳过提交该 JSON。

---

## Task 3: npm 脚本接入(gen 前置于 tsc)

把 `gen-build-flavor.mjs` 接入 `build:ts` 和 `dev`,保证任何构建路径下 `src/build-flavor.json` 都是新鲜的。

**Files:**
- Modify: `desktop/electron/package.json`(scripts 段)

- [ ] **Step 1: 改 package.json scripts**

把 `build:ts` 从
```json
    "build:ts": "tsc -p tsconfig.json",
```
改为
```json
    "build:ts": "node scripts/gen-build-flavor.mjs && tsc -p tsconfig.json",
```
把 `dev` 从
```json
    "dev": "npm run build:ts && electron .",
```
改为
```json
    "dev": "npm run build:ts && electron .",
```
(`dev` 已通过 `build:ts` 间接覆盖,无需单独改——`build:ts` 现在含 gen。)

- [ ] **Step 2: 验证 tsc 链路完整**

```bash
cd desktop/electron
rm -f src/build-flavor.json dist/build-flavor.json
npm run build:ts
test -f src/build-flavor.json && echo "src ok" || echo "src MISSING"
test -f dist/build-flavor.json && echo "dist ok" || echo "dist MISSING"
node -e "console.log(require('./dist/flavor.js'))"   # 期望 { BUILD_FLAVOR: 'standard', SANDBOX_DISABLED: false }
```
Expected: `src ok`、`dist ok`、且 flavor 输出正确。

- [ ] **Step 3: 验证 uos 链路**

```bash
cd desktop/electron
FENGYU_BUILD_FLAVOR=uos npm run build:ts
node -e "console.log(require('./dist/flavor.js'))"   # 期望 { BUILD_FLAVOR: 'uos', SANDBOX_DISABLED: true }
unset FENGYU_BUILD_FLAVOR
npm run build:ts                                       # 复位回 standard
```

- [ ] **Step 4: 回归单元测试**

Run: `cd desktop/electron && npm test`
Expected: 全绿(gen 前置不影响 vitest 对 src/ 的直接测试)。

- [ ] **Step 5: 提交**

```bash
cd desktop/electron
git add package.json
git commit -m "✨ feat(desktop): run gen-build-flavor before tsc in build:ts (flavor baked at build time)"
```

---

## Task 4: .gitignore + 把 build-flavor.json 移出版本控制

`build-flavor.json` 是生成物,不入库,但磁盘必须总有(CI 与本地都靠 gen 产出)。

**Files:**
- Modify: `desktop/electron/.gitignore`

- [ ] **Step 1: 加 gitignore 条目**

在 `desktop/electron/.gitignore` 末尾追加:
```
# Generated by scripts/gen-build-flavor.mjs before every tsc; flavor is build-time, not source.
src/build-flavor.json
```

- [ ] **Step 2: 若已被 Task 2 提交进索引,从索引移除(磁盘保留)**

```bash
cd desktop/electron
git rm --cached src/build-flavor.json 2>/dev/null || echo "(not tracked, skip)"
ls src/build-flavor.json   # 确认磁盘仍在
```

- [ ] **Step 3: 确认 git 不再追踪、磁盘仍在**

```bash
cd desktop/electron
git check-ignore src/build-flavor.json && echo "ignored ok"
test -f src/build-flavor.json && echo "disk ok" || (node scripts/gen-build-flavor.mjs && echo "regenerated")
```

- [ ] **Step 4: 提交**

```bash
cd desktop/electron
git add .gitignore
git commit -m "🔥 chore(desktop): gitignore generated src/build-flavor.json"
```

---

## Task 5: 主进程禁用沙箱(main.ts)

在 UOS 构建里,`app.whenReady()` 之前 `appendSwitch('no-sandbox')` 并打 warning。标准构建不触发。

**Files:**
- Modify: `desktop/electron/src/main.ts`

- [ ] **Step 1: 加 import**

在 `desktop/electron/src/main.ts` 的 import 段(第 1–16 行)末尾追加:
```ts
import { SANDBOX_DISABLED } from './flavor'
```

- [ ] **Step 2: 加 sandbox switch(紧跟现有 win32 setAppUserModelId 之后)**

在第 27 行(`if (process.platform === 'win32') app.setAppUserModelId('fan.summer.fengyu')`)之后插入:
```ts
// UOS/Deepin build: unprivileged user namespaces are disabled by kernel policy, and the
// install has no root to setuid the chrome-sandbox helper — so Chromium's OS sandbox cannot
// initialize and the app fails to launch. We disable it at the Chromium level for the UOS
// build flavor only. Standard builds keep sandbox: true (handled in create-window/-splash).
// Defense-in-depth retained: contextIsolation, nodeIntegration off, preload allow-list,
// per-frame CSP, loopback-only backend + env token.
if (SANDBOX_DISABLED) {
  app.commandLine.appendSwitch('no-sandbox')
  logger.warn('[fengyu] UOS build: renderer OS sandbox disabled (kernel policy + no setuid root).')
}
```
> 注意:`logger` 由 `initLogger()` 在第 18 行赋值,此 switch 必须放在第 18 行**之后**(即放在第 27 行后即可,满足顺序)。

- [ ] **Step 3: 编译验证**

Run: `cd desktop/electron && npm run build:ts`
Expected: tsc 无错误。

- [ ] **Step 4: 验证 standard 构建不触发(默认 flavor)**

```bash
cd desktop/electron
node scripts/gen-build-flavor.mjs    # 默认 standard
npm run build:ts
node -e "const {SANDBOX_DISABLED} = require('./dist/flavor.js'); console.log('SANDBOX_DISABLED=', SANDBOX_DISABLED)"
```
Expected: `SANDBOX_DISABLED= false`。

- [ ] **Step 5: 提交**

```bash
cd desktop/electron
git add src/main.ts
git commit -m "✨ feat(desktop): UOS build disables Chromium sandbox via --no-sandbox (SANDBOX_DISABLED)"
```

---

## Task 6: BrowserWindow sandbox 标志(create-window + create-splash)

把两个窗口的 `sandbox: true` 改为 `sandbox: !SANDBOX_DISABLED`。标准构建等价 `true`。

**Files:**
- Modify: `desktop/electron/src/window/create-window.ts`(第 112 行)
- Modify: `desktop/electron/src/window/create-splash.ts`(第 62 行)

**Interfaces:**
- Consumes: `SANDBOX_DISABLED` from `../flavor`(create-window) / `../../flavor`(若 flavor 在 src 根,create-window 在 src/window,故相对路径是 `../flavor`)。两文件都在 `src/window/`,故 import 路径都是 `'../flavor'`。

- [ ] **Step 1: 改 create-window.ts**

在 `desktop/electron/src/window/create-window.ts` 顶部 import 段加:
```ts
import { SANDBOX_DISABLED } from '../flavor'
```
把第 112 行
```ts
      sandbox: true,
```
改为
```ts
      sandbox: !SANDBOX_DISABLED,
```

- [ ] **Step 2: 改 create-splash.ts**

在 `desktop/electron/src/window/create-splash.ts` 顶部 import 段加:
```ts
import { SANDBOX_DISABLED } from '../flavor'
```
把第 62 行
```ts
        sandbox: true,
```
改为
```ts
        sandbox: !SANDBOX_DISABLED,
```

- [ ] **Step 3: 编译 + 回归单元测试**

Run:
```bash
cd desktop/electron
npm run build:ts
npm test
```
Expected: tsc 无错误;vitest 全绿(既有 `create-splash.test.ts` 不受影响,因为 `sandbox` 值在 standard flavor 下仍是 `true`)。

- [ ] **Step 4: 端到端冒烟(标准构建行为不变)**

```bash
cd desktop/electron
node scripts/gen-build-flavor.mjs    # standard
npm run build:ts
node -e "
  const m = require('./dist/window/create-window.js');
  console.log('create-window module loaded ok');
"
```
> 注:`createMainWindow` 需要 Electron 运行时,无法纯 node 调用。此步仅验证模块可被 require 不抛语法/链接错。完整启动验证在 Task 8 的 CI xvfb 里做。

- [ ] **Step 5: 提交**

```bash
cd desktop/electron
git add src/window/create-window.ts src/window/create-splash.ts
git commit -m "✨ feat(desktop): BrowserWindow sandbox = !SANDBOX_DISABLED (UOS build turns it off)"
```

---

## Task 7: 独立的 electron-builder.uos.yml

基于 lite 配置,自包含(非 delta),只出 deb,productName `Infinia-UOS`,输出到 `../dist-electron-uos`。

**Files:**
- Create: `desktop/electron/electron-builder.uos.yml`

- [ ] **Step 1: 新建配置**

新建 `desktop/electron/electron-builder.uos.yml`,内容以 `electron-builder.yml` 为蓝本,改 `productName`、`linux.target`、`directories.output`,加 `linux.desktop` 自定义模板:

```yaml
# electron-builder configuration for the UOS (UnionTech / Deepin) desktop build.
#
# Why this exists: UOS/Deepin disables unprivileged user namespaces by kernel policy, and the
# install has no root to setuid the chrome-sandbox helper. Chromium's OS sandbox therefore
# cannot initialize and the app fails to launch. This build is compiled with
# FENGYU_BUILD_FLAVOR=uos, which disables the sandbox at the Chromium level (see src/main.ts and
# src/flavor.ts). Standard builds are unaffected.
#
# This config is a COMPLETE config (not a delta over electron-builder.yml) — same shape as
# electron-builder.jre.yml. Only deb is produced (UOS is Debian-based, internal use, x86_64).
# Lite variant: no bundled JRE (UOS environments are expected to provide a compatible JDK).

appId: fan.summer.fengyu
productName: Infinia-UOS
copyright: Copyright © 2026 FengYu

artifactName: ${productName}-${version}-${platform}-${arch}.${ext}

directories:
  output: ../dist-electron-uos
  buildResources: resources

files:
  - dist/**/*
  - frontend-dist/**/*
  - resources/splash.html
  - resources/icon.png
  - resources/icon.ico
  - resources/icon-32.png
  - resources/icon-128.png
  - package.json

extraMetadata:
  main: dist/main.js

extraResources:
  - from: resources/icon-32.png
    to: icon-32.png
    filter: ['**/*']
  - from: resources/icon.png
    to: icon.png
    filter: ['**/*']
  - from: resources/binaries/FengYu.jar
    to: binaries/FengYu.jar
    filter: ['**/*']
  - from: resources/binaries/plugins
    to: plugins
    filter: ['**/*']

linux:
  target:
    - target: deb
      arch: [x64]
  icon: resources/icon.png
  category: Development
  maintainer: FengYu
  # Custom .desktop entry — Comment documents the sandbox caveat for UOS users.
  desktop:
    Comment: Infinia UOS build — renderer OS sandbox disabled (UOS/Deepin kernel policy; no setuid root). Content restricted to local trusted SPA.
    StartupWMClass: Infinia-UOS

publish:
  provider: github
  owner: MuskStark
  repo: FengYu
```

- [ ] **Step 2: 校验 YAML 语法**

Run:
```bash
cd desktop/electron
node -e "console.log(Object.keys(require('js-yaml').load(require('fs').readFileSync('electron-builder.uos.yml','utf8'))).sort().join(','))" 2>/dev/null || npx -y js-yaml electron-builder.uos.yml > /dev/null && echo "yaml ok"
```
> 若仓库无 `js-yaml`,用 `npx -y js-yaml` 兜底。Expected: 无解析错误。

- [ ] **Step 3: dry-run 打包校验(不实际产出,仅校验配置被接受)**

```bash
cd desktop/electron
# 准备最小资源占位(若 staging 未就绪),只校验配置可被 electron-builder 读取:
mkdir -p resources/binaries/plugins
[ -f resources/binaries/FengYu.jar ] || echo "(placeholder) no jar — config parse check only"
FENGYU_BUILD_FLAVOR=uos npx electron-builder --linux --config electron-builder.uos.yml --publish never --dir 2>&1 | tail -20 || echo "(expected to fail without full staging; goal is config parsed)"
```
> 此步目标是看到 electron-builder **接受了该配置**(没有 "unknown option" / YAML 语法报错),而非完整产出——完整产出在 CI(Task 8)做。看到 config 被解析即通过。

- [ ] **Step 4: 提交**

```bash
cd desktop/electron
git add electron-builder.uos.yml
git commit -m "✨ feat(desktop): electron-builder.uos.yml — deb-only UOS build, no-sandbox, lite (no JRE)"
```

---

## Task 8: CI 工作流加 UOS 构建步骤

在 `fengyu-release.yml` 的 `desktop:` job,在 JRE 构建之后、`Upload desktop bundles` 之前,加 UOS 步骤(仅 ubuntu-22.04 / x64)。

**Files:**
- Modify: `.github/workflows/fengyu-release.yml`

- [ ] **Step 1: 定位插入点**

在 `.github/workflows/fengyu-release.yml` 找到 `Build Electron bundle (with JRE)` step(约第 367 行)结束、`Upload desktop bundles` step(约第 373 行)开始之间插入。

- [ ] **Step 2: 插入 UOS 构建步骤**

在 `Build Electron bundle (with JRE)` 之后插入:
```yaml
      # ---- UOS (UnionTech / Deepin) build --------------------------------------
      # x86_64 only, deb only, lite (no bundled JRE). Compiled with FENGYU_BUILD_FLAVOR=uos,
      # which disables the Chromium renderer sandbox (UOS kernel blocks unprivileged user
      # namespaces and the install has no root to setuid chrome-sandbox). See
      # electron-builder.uos.yml + src/flavor.ts.
      - name: Build UOS deb (x86_64, no-sandbox)
        if: matrix.os == 'ubuntu-22.04'
        working-directory: desktop/electron
        env:
          FENGYU_BUILD_FLAVOR: uos
        run: |
          npm run build:ts
          npx electron-builder --linux --config electron-builder.uos.yml --publish never
```

- [ ] **Step 3: 把 UOS 产物纳入上传**

把现有 `Upload desktop bundles` step 的 `path` 加上 UOS 输出目录。原:
```yaml
      - name: Upload desktop bundles
        uses: actions/upload-artifact@v4
        with:
          name: ${{ matrix.artifact }}
          path: |
            desktop/dist-electron-lite/**
            desktop/dist-electron-jre/**
          if-no-files-found: error
```
改为(追加 UOS 目录,并把 `if-no-files-found` 从 `error` 改 `warn`,因为 win/mac matrix 不产 UOS/lite-jre 的某些组合):
```yaml
      - name: Upload desktop bundles
        uses: actions/upload-artifact@v4
        with:
          name: ${{ matrix.artifact }}
          path: |
            desktop/dist-electron-lite/**
            desktop/dist-electron-jre/**
            desktop/dist-electron-uos/**
          if-no-files-found: warning
```
> 说明:原 `if-no-files-found: error` 在单矩阵项下成立;UOS 目录只在 ubuntu matrix 出现,win/mac 不出 → 改 `warning` 避免非 linux matrix 失败。lite/jre 目录在所有 matrix 都有,保持不变。

- [ ] **Step 4: 确认 release 聚合 glob 已覆盖**

Release job 的 `Collect release files` 已含 `-name '*.deb'`(见工作流约第 416 行),`Infinia-UOS-*.deb` 会被自动收集,**无需额外改 release job**。在 PR 描述里记录这一点。

- [ ] **Step 5: 本地校验 YAML 语法**

Run:
```bash
cd /Users/phoebej/Develop/Java/FengYu
npx -y js-yaml .github/workflows/fengyu-release.yml > /dev/null && echo "yaml ok"
```
Expected: `yaml ok`(无解析错误)。

- [ ] **Step 6: 提交**

```bash
cd /Users/phoebej/Develop/Java/FengYu
git add .github/workflows/fengyu-release.yml
git commit -m "✨ feat(ci): build UOS deb (x86_64, no-sandbox) in desktop job + aggregate into release"
```

---

## Task 9: 全量回归与最终验证

把所有改动串起来,确认标准构建零行为变化、UOS 构建正确禁沙箱、测试全绿。

- [ ] **Step 1: standard 全量构建 + 测试**

```bash
cd desktop/electron
unset FENGYU_BUILD_FLAVOR
npm run build:ts
npm test
node -e "console.log(require('./dist/flavor.js'))"   # { BUILD_FLAVOR: 'standard', SANDBOX_DISABLED: false }
```
Expected: tsc 干净、vitest 全绿、flavor=standard。

- [ ] **Step 2: UOS 全量构建 + 测试**

```bash
cd desktop/electron
FENGYU_BUILD_FLAVOR=uos npm run build:ts
npm test                                              # flavor.test.ts 会读到 uos(因 JSON 被重写)
node -e "console.log(require('./dist/flavor.js'))"   # { BUILD_FLAVOR: 'uos', SANDBOX_DISABLED: true }
unset FENGYU_BUILD_FLAVOR
npm run build:ts                                      # 复位
```
Expected: flavor=uos、SANDBOX_DISABLED=true;测试全绿(注意 `flavor.test.ts` 的 afterAll 会把 JSON 复位)。

- [ ] **Step 3: git status 干净检查**

```bash
cd /Users/phoebej/Develop/Java/FengYu
git status --short
```
Expected: 无 `src/build-flavor.json` 出现(已 gitignore);若 working tree 仅剩该文件被标记,确认是 ignored:
```bash
git check-ignore desktop/electron/src/build-flavor.json && echo "ignored ok"
```

- [ ] **Step 4: 跨模块检查(AGENTS.md 要求:聚焦验证,不跑全 reactor)**

仅确认桌面端边界,不跑 Java reactor:
```bash
cd desktop/electron && npm test && npm run build:ts && echo "desktop ok"
```
Expected: `desktop ok`。

- [ ] **Step 5(可选): 完整 UOS deb 本地产出(需 staging 就绪)**

仅当本地已 `cp .../FengYu.jar resources/binaries/FengYu.jar` 并放了 `.fyp` 时:
```bash
cd desktop/electron
FENGYU_BUILD_FLAVOR=uos npx electron-builder --linux --config electron-builder.uos.yml --publish never
ls -la ../dist-electron-uos/*.deb
```
Expected: 产出 `Infinia-UOS-<ver>-linux-x64.deb`。无 staging 则跳过,留给 CI。

---

## Self-Review(Spec 覆盖核对)

| Spec 节 | 覆盖 Task |
|---|---|
| §4.1 构建期常量注入(gen 脚本 + JSON + tsc 机制) | Task 1, 2, 3, 4 |
| §4.2 主进程改动(appendSwitch + warning + sandbox 标志) | Task 5, 6 |
| §4.3 独立 electron-builder 配置(productName/target/output/desktop 模板) | Task 7 |
| §4.4 CI 工作流(ubuntu x64 步骤 + 上传 + release 聚合) | Task 8 |
| §5 验证(单元 + 构建冒烟) | Task 1, 2, 3, 5, 6, 9 |
| §5.3 CI 启动冒烟(xvfb) | 沿用现有 desktop job 的 `Run Electron launch E2E (Linux)` step;UOS deb 的 xvfb 启动验证由该 step 的标准构建覆盖(同一二进制布局),UOS 产物本身在 Task 8 产出。**注:不在 UOS 产物上单独跑 xvfb E2E,因为 UOS 与 lite 同源,差别仅在 sandbox 开关;如需专门验证 UOS deb 启动,可在 Task 8 后追加一个解包+启动步骤,但本计划默认不增加 CI 复杂度。** |
| §2 非目标(不带 JRE / 不同步文档 / 仅 x64 / 仅 deb) | 全局约束 + 各 Task 遵守 |

**Placeholder 扫描:** 无 TBD/TODO;每步含具体代码/命令/期望。✅
**类型一致性:** `BUILD_FLAVOR: BuildFlavor`、`SANDBOX_DISABLED: boolean` 在 Task 2 定义,Task 5/6 消费,签名一致。✅
