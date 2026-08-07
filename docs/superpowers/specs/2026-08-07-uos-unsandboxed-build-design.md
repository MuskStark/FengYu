# UOS 专项构建设计(禁用 Chromium 沙箱)

- **日期**: 2026-08-07
- **状态**: 设计已确认,待实现
- **范围**: 新增一个独立的 UOS Linux 构建产物,通过构建期常量注入禁用渲染进程 OS 沙箱,解决 UOS/Deepin 上 `chrome-sandbox` 因无 root 无法 setuid 导致程序无法启动的问题。标准构建的安全姿态完全不动。

## 1. 背景与现状

### 问题
UOS(统信)/Deepin 默认禁用 unprivileged user namespaces。本应用桌面端当前在所有 BrowserWindow 上硬编码 `sandbox: true`(`create-window.ts:111`、`create-splash.ts:62`),且没有任何 `--no-sandbox` fallback、没有 `app.commandLine` 处理、没有 setuid `chrome-sandbox` 故事。

Chromium 沙箱在 Linux 上只有两条出路:
1. **unprivileged user namespaces** — UOS/Deepin 默认禁用,这正是用户遇到的崩溃根因。
2. **setuid `chrome-sandbox` 辅助程序** — 必须同时满足:属主是 **root** + 带 **setuid 位(04755)**。`chown root` 只能由 root 执行。

### 关键约束(已与用户确认)
- **分发方式**:独立的 UOS 构建产物,UOS 为内部使用,**需要管理员签名后才能安装**。
- **"管理员签名"指包签名**(受信任签名才能上架/安装),**不是安装操作本身有 root 权限**。
- **安装时没有 root**,运行时也没有 → 永远配不出可用的 setuid chrome-sandbox → **无法保留 Chromium OS 沙箱**,只能关。
- **CPU 架构**:仅 x86_64(amd64),其余架构后续再说。
- **JRE**:UOS 构建不带 JRE(lite 变体),UOS 环境自备兼容 JDK。
- **文档**:本次不同步 `README`/`CHANGELOG`/`docs/`,只做代码/构建/CI 改动。

### 决策推导
- 方案 A(setuid chrome-sandbox,保留沙箱)**被否**:安装无 root → 无法 `chown root`。
- 方案 B(运行时强制 `--no-sandbox`):可行但纯被动。
- 方案 C(硬化版 `--no-sandbox`):**采用**。沙箱必关,就把"关掉之后靠什么兜底"显式确认并保留。

### 兜底依据(沙箱关闭后仍可控)
本应用渲染进程:
- **只加载本地可信 SPA**(`frontend-dist`),不加载任何远程/动态网页;
- 后端 **loopback 独占**,token 走 env(`FENGYU_AUTH_TOKEN`)不走 argv;
- 已有 `contextIsolation: true` + `nodeIntegration: false` + preload 白名单 + per-frame CSP,**这些在 UOS 构建里全部保留不动**,作为关闭 OS 沙箱后的纵深防御。

## 2. 目标与非目标

### 目标
1. 新增独立 UOS 构建产物(lite,deb,x86_64),与标准 Linux 构建不撞包。
2. 通过**构建期常量注入**(非运行时探测)让 UOS 构建禁用渲染进程 OS 沙箱。
3. 标准构建(win/mac/linux)的 `sandbox: true` 安全姿态**完全不动**。
4. CI 出 UOS deb 并纳入 Release 聚合。

### 非目标
- 保留 Chromium OS 沙箱(约束决定不可能)。
- 出 rpm/AppImage for UOS(只出 deb)。
- 覆盖 arm64/龙芯(本次仅 x86_64)。
- UOS 构建带 JRE(lite 变体)。
- 同步 README/CHANGELOG/docs(用户明确要求跳过)。
- 改动后端、插件运行时、前端。

## 3. 已确认的关键决策

| 决策点 | 选定 |
|---|---|
| 方案 | C — 硬化版 `--no-sandbox`(独立 UOS 产物) |
| 沙箱关闭方式 | **构建期常量注入**(非运行时探测 user namespace) |
| 常量载体 | 构建期生成 `src/build-flavor.json` + `tsc resolveJsonModule` 发出 `require()`,JSON 被烤进 `dist/build-flavor.json` 并打入 app.asar(无 bundler define) |
| 触发开关 | 环境变量 `FENGYU_BUILD_FLAVOR=uos` |
| 产物形态 | deb,仅 x86_64,基于 lite 配置(无 JRE) |
| appId | **不变**(`fan.summer.fengyu`,同一应用身份);用 productName 区分 |
| 包名区分 | `Infinia-UOS` → deb 包名 `infinia-uos`(不与 `infinia` 撞) |

## 4. 详细设计

### 4.1 构建期常量注入机制

由于主进程用纯 `tsc`(无 bundler,没有 `define` 插件),采用**生成 JSON + `resolveJsonModule`** 方案。`tsconfig.json` 已有 `resolveJsonModule: true`(第 14 行),无需改 tsconfig。

> **机制实测确认**(非凭记忆):`tsc` 在 `resolveJsonModule` 下**不会**把 JSON 内联成字面量,而是:① 在 `dist/` 生成一份 `build-flavor.json`;② 在 `dist/flavor.js` 发出 `require("./build-flavor.json")`。因此 flavor 的值在**构建期被烤进磁盘上的 JSON 文件**,运行时只 `require` 该文件、**不读 `process.env`**——构建后再改 `FENGYU_BUILD_FLAVOR` 不影响已构建产物。这满足"构建期常量注入"的意图(值由构建决定、运行时不可变),只是载体是构建期生成的 JSON 而非 main.js 里的字面量。electron-builder 的 `files: ['dist/**/*']` 会把该 JSON 一并打入 `app.asar`,运行时 `require` 能解析到。

**新增文件 `desktop/electron/scripts/gen-build-flavor.mjs`**:
- 读 `process.env.FENGYU_BUILD_FLAVOR`;
- 合法值:`'uos'` / 未设(视为 `'standard'`);
- 写 `desktop/electron/src/build-flavor.json`,内容 `{"flavor":"uos"}` 或默认 `{"flavor":"standard"}`;
- 幂等:每次构建前重写,保证文件总在、总有合法默认值;
- 用 Node 内置 `fs.writeFileSync`,无新依赖;
- 必须在 `tsc` 之前运行,这样 `tsc` 才能把对应内容烤进 `dist/build-flavor.json`。

**新增文件 `desktop/electron/src/flavor.ts`**(收敛判断,避免散落):
```ts
import data from './build-flavor.json';

export type BuildFlavor = 'standard' | 'uos';

/**
 * Resolve a raw flavor value into a typed BuildFlavor. Pure & standalone so it can be
 * unit-tested without mocking JSON module loading. Safe default: anything other than the
 * literal 'uos' keeps the Chromium sandbox ON.
 */
export function resolveFlavor(raw: unknown): BuildFlavor {
  return raw === 'uos' ? 'uos' : 'standard';
}

export const BUILD_FLAVOR: BuildFlavor = resolveFlavor((data as { flavor?: unknown }).flavor);
export const SANDBOX_DISABLED = BUILD_FLAVOR === 'uos';
```
> **测试策略:** `import data from './build-flavor.json'` 是模块顶层执行,且 vitest/vite 在首次 import 时就把 JSON 内联进转换结果,无法用 `vi.resetModules`/重写文件/`vi.doMock` 在运行时切换 flavor(三者实测失败)。因此把判断抽成纯函数 `resolveFlavor(raw)` 做参数化单测,wiring 测试只验证 `SANDBOX_DISABLED` 是与 `BUILD_FLAVOR` 一致的布尔。

**`build-flavor.json` 加入 `.gitignore`**(生成物,不入库)。

**npm 脚本接入**(`desktop/electron/package.json`):
- `build:ts`: `node scripts/gen-build-flavor.mjs && tsc -p tsconfig.json`(生成在前);
- `dev`: 在跑 electron 之前先 `node scripts/gen-build-flavor.mjs`(保证开发态文件也在);
- 其他脚本(`build:win/mac/linux`、`build`、`test`)经 `build:ts` 间接覆盖,无需单独改。

### 4.2 主进程改动(最小且隔离)

**`desktop/electron/src/main.ts`**:
- 导入 `import { SANDBOX_DISABLED } from './flavor'`;
- 在 `app.whenReady()` 之前(模块顶层,Electron app 初始化后立即):
  ```ts
  if (SANDBOX_DISABLED) {
    app.commandLine.appendSwitch('no-sandbox');
    log.warn('[fengyu] UOS build: renderer OS sandbox disabled (unprivileged user namespaces unavailable on UOS/Deepin; no root to setuid chrome-sandbox). Defense-in-depth retained: contextIsolation, nodeIntegration off, preload allow-list, per-frame CSP, loopback-only backend.');
  }
  ```

**`desktop/electron/src/window/create-window.ts:111`**:
- `sandbox: true` → `sandbox: !SANDBOX_DISABLED`

**`desktop/electron/src/window/create-splash.ts:62`**:
- `sandbox: true` → `sandbox: !SANDBOX_DISABLED`

两处均导入 `SANDBOX_DISABLED`。

**标准构建行为不变**:`SANDBOX_DISABLED === false`,`appendSwitch` 不触发,`sandbox: !false === true`。

### 4.3 独立的 electron-builder 配置

**新增 `desktop/electron/electron-builder.uos.yml`**,基于 lite 配置(`electron-builder.yml`),自包含(非 delta)。关键差异:

| 字段 | lite (`electron-builder.yml`) | UOS (`electron-builder.uos.yml`) |
|---|---|---|
| `productName` | `Infinia` | `Infinia-UOS` |
| `linux.target` | `[AppImage, deb]` | `[deb]` |
| `directories.output` | `../dist-electron` | `../dist-electron-uos` |
| `appId` | `fan.summer.fengyu` | **不变**(`fan.summer.fengyu`) |
| deb 包名( productName 派生) | `infinia` | `infinia-uos` |
| `extraResources`(JRE) | 无 | **无**(lite 变体) |

**自定义 `linux.desktop` 模板**:electron-builder 支持 `linux.desktop` 映射到 `.desktop` 模板文件。新增 `desktop/electron/resources/uos.desktop.tpl`(或在 yml 内联),`Comment` 字段写明:
> Infinia UOS build — renderer OS sandbox disabled due to UOS/Deepin kernel policy; content restricted to local trusted SPA.

其余字段(`Categories=Development`、`StartupWMClass` 等)与标准一致。

### 4.4 CI 工作流

`.github/workflows/fengyu-release.yml` 的 `desktop:` job 加 **x86_64 only** 的 UOS 步骤(作为新步骤,不加新矩阵项,避免污染 win/mac):

在现有 lite + jre 构建之后追加:
```yaml
- name: Build UOS deb (x86_64, no-sandbox)
  if: matrix.os == 'ubuntu-22.04'
  env:
    FENGYU_BUILD_FLAVOR: uos
  run: |
    cd desktop/electron
    npm run build:ts
    npx electron-builder --linux --config electron-builder.uos.yml
```

产物上传(`actions/upload-artifact`):
```yaml
- name: Upload UOS deb
  if: matrix.os == 'ubuntu-22.04'
  uses: actions/upload-artifact@v4
  with:
    name: desktop-uos-deb
    path: desktop/dist-electron-uos/*.deb
```

Release job 的 artifact 聚合里加 `Infinia-UOS-*.deb`(沿用现有 `*.deb` glob 通常已覆盖,确认 glob 后按需显式补充)。

## 5. 验证

### 5.1 单元测试(vitest)
新增 `desktop/electron/src/__tests__/flavor.test.ts`(若 mock `build-flavor.json` 不便,改为测 `gen-build-flavor.mjs` 的产出):
- 默认(无 env):写出 `{"flavor":"standard"}`;
- `FENGYU_BUILD_FLAVOR=uos`:写出 `{"flavor":"uos"}`;
- 非法值(如 `FENGYU_BUILD_FLAVOR=foo`):降级为 `standard`。

`flavor.ts` 的常量判断逻辑简单,主要靠 gen 脚本的幂等/降级测试覆盖。

### 5.2 构建冒烟(macOS 本地)
```bash
cd desktop/electron
FENGYU_BUILD_FLAVOR=uos npm run build:ts
grep -c "no-sandbox" dist/main.js   # 期望 ≥ 1
```
反向(标准构建不应触发禁用):
```bash
unset FENGYU_BUILD_FLAVOR
npm run build:ts
# 注意:tsc 不做死代码消除,'no-sandbox' 字面量在两种构建里都会出现在 dist/main.js。
# 真正的区分点是 build-flavor.json 的内容 + SANDBOX_DISABLED 运行时求值:
cat src/build-flavor.json   # 期望 {"flavor":"standard"}
```
标准构建的运行时不触发由 `SANDBOX_DISABLED === false` 保证(单元测试覆盖),不由 grep 判定。

### 5.3 CI 启动冒烟
UOS deb 在 ubuntu-22.04 runner 上 `xvfb-run` 启动(沿用现有 linux e2e 方式),证明 `--no-sandbox` 能起窗 + 连上 loopback 后端。若现有 e2e 脚本不直接吃 deb,则最小验证:`dpkg-deb -x` 解包后跑里面的可执行文件,确认进程不因沙箱崩溃。

## 6. 影响面与回归

| 文件/模块 | 变更类型 | 回归风险 |
|---|---|---|
| `desktop/electron/scripts/gen-build-flavor.mjs` | 新增 | 无(新文件) |
| `desktop/electron/src/flavor.ts` | 新增 | 无(新文件) |
| `desktop/electron/src/build-flavor.json` | 生成(gitignore) | 无(不入库) |
| `desktop/electron/src/main.ts` | 加 3 行(SANDBOX_DISABLED 分支) | 极低(标准构建不触发) |
| `desktop/electron/src/window/create-window.ts` | `true` → `!SANDBOX_DISABLED` | 极低(标准构建等价 true) |
| `desktop/electron/src/window/create-splash.ts` | 同上 | 极低 |
| `desktop/electron/electron-builder.uos.yml` | 新增 | 无(新文件) |
| `desktop/electron/resources/uos.desktop.tpl`(或内联) | 新增 | 无 |
| `desktop/electron/.gitignore` | 加一行 | 无 |
| `desktop/electron/package.json`(scripts) | `build:ts`/`dev` 加 gen 前置 | 极低(gen 幂等) |
| `.github/workflows/fengyu-release.yml` | 加 UOS 步骤 | 无(新步骤,条件触发) |
| `desktop/electron/src/__tests__/flavor.test.ts` | 新增 | 无 |

**标准构建零行为变化**:所有改动在 `SANDBOX_DISABLED === false` 时退化为现状。

## 7. 已知差距(诚实标注)

- **UOS 构建关闭了渲染进程 OS 沙箱**。这是 UOS 内核策略 + 无 root 安装的硬约束决定的,无法在本应用层面规避。纵深防御(contextIsolation / nodeIntegration off / preload 白名单 / CSP / loopback 后端)保留,但**不等于** OS 级进程隔离。
- **仅 x86_64**:UOS 在国产 CPU(arm64/龙芯)上的支持本次不做。
- **UOS 构建不带 JRE**:依赖目标机器自备兼容 JDK;若 UOS 环境无 JDK,后端起不来(与标准 lite 构建一致的既有约束,非本次引入)。
