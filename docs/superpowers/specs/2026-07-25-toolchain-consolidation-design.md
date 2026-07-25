# Toolchain 目录整合 + 发布流程对齐 + 发布前修复

**日期:** 2026-07-25
**分支:** `4.0.0-electron`
**状态:** 待实现

## 背景与动机

FengYu 4.0.0 的插件工具链以**独立的版本线**发布(tag 前缀 `plugin-tooling-v*`),但它的 7 个组成部分目前散落在仓库根目录:

| 当前目录 | 类型 | 包名 |
|---|---|---|
| `FengYu-Plugin-Sdk` | Maven | `fan.summer.fengyu.sdk:fengyu-plugin-sdk` |
| `FengYu-Plugin-DevKit` | Maven | `fan.summer.fengyu.sdk:fengyu-plugin-devkit` |
| `plugin-sdk/typescript` | npm | `@infinia/plugin-sdk` |
| `plugin-ui/vue` | npm | `@infinia/plugin-ui` |
| `plugin-dev` | npm | `@infinia/plugin-dev` |
| `plugin-cli` | npm | `@infinia/plugin-cli` |
| `plugin-spec` | JSON schema(无构建) | — |

散落带来三个问题:(1) 根目录噪音大,工具链边界模糊;(2) `plugin-sdk`/`plugin-ui` 各套了一层无意义的中间目录(`typescript/`、`vue/`);(3) 命名风格混乱(`FengYu-Plugin-Sdk` vs `plugin-sdk` vs `plugin-cli`)。

本次重构把 7 个目录整合到单一 `toolchain/` 下、扁平化中间层、统一为语义短名,并把发布 workflow 和 skill 重命名对齐。同时顺带修复发布前代码审查发现的 6 个 Important 问题(后端 2 + 桌面壳 4),为 `4.0.0-alpha.3` 切割扫清障碍。

## 目标

1. **整合目录** — 7 个工具链目录迁入 `toolchain/`,子目录重命名为语义短名,扁平化中间层。
2. **对齐发布流程** — 重命名 CI/release workflow 和 skill 为 `toolchain-*`,更新所有内部路径引用。
3. **修复审查发现** — 6 个 Important(后端 2 + 桌面壳 4)。
4. **保持不变量** — 发布坐标(Maven artifactId、npm 包名、tag 前缀)、公开 API、用户工作流零变化。

## 非目标

- 不改变 Maven artifactId(`fengyu-plugin-sdk`、`fengyu-plugin-devkit`)—— GitHub Packages 已发布版本依赖此坐标。
- 不改变 npm 包名(`@infinia/*`)。
- 不改变 tag 触发前缀 `plugin-tooling-v*`(由 `on.push.tags` 驱动,与文件名/目录名无关)。
- 不改动 `docs/superpowers/plans/` 和 `docs/superpowers/specs/` 下的**历史归档**文档(它们记录当时的设计,改了反而失真)。仅本次新 spec 例外。
- 不做 Minor 修复(前端 CI 测试脚本、多处 stale 注释、macOS dock activate、build-jre 模块列表)—— 留作后续。

## 目录映射

```
toolchain/
├── sdk-java/        ← FengYu-Plugin-Sdk        (Maven, artifactId=fengyu-plugin-sdk)
├── devkit-java/     ← FengYu-Plugin-DevKit     (Maven, artifactId=fengyu-plugin-devkit)
├── sdk-ts/          ← plugin-sdk/typescript    (@infinia/plugin-sdk)
├── ui/              ← plugin-ui/vue            (@infinia/plugin-ui)
├── dev/             ← plugin-dev               (@infinia/plugin-dev)
├── cli/             ← plugin-cli               (@infinia/plugin-cli)
└── spec/            ← plugin-spec              (manifest.schema.json + fixtures,单一事实源)
```

**搬迁手段:** `git mv`(保留 rename 历史)。两层中间目录(`plugin-sdk/typescript`、`plugin-ui/vue`)扁平化为单层 `sdk-ts`/`ui`。

### 不变量(发布坐标,绝对不改)

- Maven artifactId:`fengyu-plugin-sdk`、`fengyu-plugin-devkit`
- npm 包名:`@infinia/plugin-sdk`、`@infinia/plugin-ui`、`@infinia/plugin-dev`、`@infinia/plugin-cli`
- tag 前缀:`plugin-tooling-v*`

### 特殊处理:`plugin-spec`(单一事实源)

`plugin-spec/manifest.schema.json` 是 schema 的事实源,被两处引用:

1. **`plugin-cli/src/manifest.mjs:9`** → `../spec/manifest.schema.json`。这是 CLI **内部副本** `plugin-cli/spec/`(由 `sync-spec` 脚本维护)。CLI 整体搬到 `toolchain/cli/` 后,内部相对路径 `../spec/` 仍成立,**无需改**。
2. **`FengYu/src/test/java/.../PluginPackageServiceTest.java:90-91`** → 从 `FengYu/` 出发读 `../plugin-spec/fixtures`。FengYu 与 toolchain 的相对位置改变后失效,需更新为 `../../toolchain/spec/fixtures`。

## 引用面清单(全部受影响位置)

### ① Maven(2 处)
- `pom.xml` 根 reactor:`<module>FengYu-Plugin-Sdk</module>` → `toolchain/sdk-java`,`<module>FengYu-Plugin-DevKit</module>` → `toolchain/devkit-java`。
- 其余 pom 的 `<dependency>` 用 artifactId 引用(不变)。需核验 `FengYu-Plugin-DevKit/pom.xml`、`OfficialPlugins/*/pom.xml`、`FengYu/pom.xml` 内是否有相对路径(`${project.basedir}/../FengYu-Plugin-Sdk` 之类),有则更新。

### ② npm `file:` 依赖路径(扁平化后重新计算)
原层级(每个 npm 包都套了一层):
```
plugin-sdk/typescript/   plugin-ui/vue/   plugin-dev/   plugin-cli/
```
原 `file:` 路径示例:`file:../plugin-sdk/typescript`(从 plugin-ui 出发)。
新层级(扁平化,4 个包平级在 `toolchain/` 下):
```
toolchain/sdk-ts/   toolchain/ui/   toolchain/dev/   toolchain/cli/
```
新 `file:` 路径示例:`file:../sdk-ts`(从 ui 出发)。

4 个 package.json 的 `dependencies`/`devDependencies` 互指路径全部更新。需逐个核验:
- `sdk-ts/package.json`(可能无 file: 依赖,是叶子)
- `ui/package.json`(peer `@infinia/plugin-sdk`)
- `dev/package.json`(当前 `file:../plugin-sdk/typescript`)
- `cli/package.json`(可能依赖 sdk-ts?)

### ③ GitHub workflows(重命名 2 个 + 更新 1 个)
- **`plugin-tooling.yml` → `toolchain-ci.yml`**:
  - `on.push.paths` / `on.pull_request.paths`:从 `plugin-ui/**`/`plugin-cli/**`/`plugin-sdk/**`/`FengYu-Plugin-Sdk/**` 改为 `toolchain/**`(单一 glob 覆盖全部),`OfficialPlugins/**` 保留,`scripts/plugin-tooling-local-smoke.sh` 保留(脚本文件名不变)。
  - 自身引用:`.github/workflows/plugin-tooling.yml` → `.github/workflows/toolchain-ci.yml`。
  - 内部 `cd plugin-sdk/typescript` 等改为 `cd toolchain/sdk-ts` 等。
- **`plugin-tooling-release.yml` → `toolchain-release.yml`**:
  - 内部所有 `cd`/`./mvnw -f`/`cache-dependency-path`/`npm pack`/`node plugin-cli/...` 路径更新。
  - `on.push.tags: plugin-tooling-v*` **不变**(tag 前缀不动)。
- **`fengyu-release.yml`**:核验是否引用 toolchain 路径(如 `./mvnw -pl FengYu-Plugin-Sdk`),有则更新。

### ④ Shell 脚本(6 个,逐个核验)
`scripts/e2e-smoke.sh`、`scripts/plugin-tooling-local-smoke.sh`、`scripts/check-plugin-dependency-boundaries.sh`、`scripts/test-web-release.sh`、`scripts/package-web-release.sh`、`scripts/offlinepython-e2e-smoke.sh`。凡 `cd`/路径引用 toolchain 目录的都改。

### ⑤ Skills + CLAUDE 适配器(重命名 + 内容)
- **`.agents/skills/plugin-tooling-release/` → `.agents/skills/toolchain-release/`**(SKILL.md 内路径引用更新)
- **`.claude/skills/plugin-tooling-release/` → `.claude/skills/toolchain-release/`**
- `AGENTS.md`、`CLAUDE.md` 里对该 skill 名的引用更新
- `.agents/skills/fengyu-plugin-dev/SKILL.md`、`app-release/SKILL.md`、`docs-updater/SKILL.md` 内的 toolchain 路径引用更新

### ⑥ Docs(中英,结构性镜像;仅当前文档,不动历史归档)
- `docs/en/plugins/{sdk-cli,build-deploy,ui-microfrontend,worker}.md`、`docs/en/reference/glossary.md`
- `docs/zh/` 对应 5 个文件
- `README.md`、`CHANGELOG.md`(CHANGELOG 加一条本次变更)
- **不改** `docs/superpowers/plans/`、`docs/superpowers/specs/` 下已有文件

### ⑦ 后端测试相对路径(1 处)
`FengYu/src/test/java/fan/summer/fengyu/plugin/market/PluginPackageServiceTest.java:90-91` 的 `../plugin-spec/fixtures` → `../../toolchain/spec/fixtures`(并保留其 fallback 逻辑)。

## 修复:审查发现的 6 个 Important

### 后端(2)

**B1. Actuator `restart` 端点暴露且可达** — `application.yml:23-30`
- **现状:** `management.endpoints.web.exposure.include: restart,health`,`POST /actuator/restart` 是活的上下文重启端点。它不在 `TokenAuthFilter` 旁路名单(token 保护),但在 Web bundle 无 token 姿态下,任何 loopback 进程可 POST 强制重启。
- **核验结论:** **无任何代码调用 `/actuator/restart`**。SETUP→APP 重启完全走 `System.exit(SETUP_DONE)`(`SetupController.java:58`),由桌面 supervisor 监听 exit code 0 触发 respawn。注释 "Web deployment: setup wizard triggers context restart" 是过时的。
- **修复:** `application.yml` 改为 `exposure.include: health`,删除 `management.endpoint.restart.enabled`。SETUP wizard 不受影响(它本来就是进程退出+supervisor 重启,不是 Spring 上下文 restart)。

**B2. 便携 Web bundle 默认认证关闭** — `distribution/web/run.sh:11`、`run.bat:42`
- **现状:** `exec java -jar ... "$@"` 不传 `--token`,默认认证关闭。配合 B1 形成风险面。
- **修复:** `run.sh`/`run.bat` 在未传 `--token` 时生成随机 token 并传入(脚本级,不改 Java)。保持用户显式传 `--token=` 时覆盖。实现:shell 生成 `zf-$(head -c 16 /dev/urandom | xxd -p)-$$` 风格 token,bat 用 `%RANDOM%`+时间戳。

### 桌面壳(4)

**D1. 缺少 `setWindowOpenHandler` / `will-navigate`** — `desktop/electron/src/window/create-window.ts`
- **现状:** 未注册导航守卫。`About.vue` 的 `<a target="_blank">` 在 Electron 里会开带相同 preload 的新窗口;被入侵/妥协页面可 `window.open('file://...')` 或导航到任意源。
- **修复:** 在 `createMainWindow` 创建窗口后添加:
  ```ts
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//.test(url)) { shell.openExternal(url); return { action: 'deny' } }
    return { action: 'deny' }
  })
  win.webContents.on('will-navigate', (e, url) => {
    if (url !== win.webContents.getURL()) e.preventDefault()
  })
  ```
  需 import `shell`。

**D2. auto-updater 把 JRE 变体用户静默降级为 lite** — `desktop/electron/src/updater/auto-updater.ts`、`electron-builder.yml`
- **现状:** GitHub release 的 `latest*.yml` 只引用 lite 变体;JRE 变体(JRE extraResource override,productName `Infinia-JRE`)作为 release asset 上传但不进 updater feed。`checkForUpdates()` 会给 JRE 变体用户推 lite 下载,`quitAndInstall()` 移除其自带 JRE。
- **修复(alpha 阶段最小方案):** 在 `checkForUpdates()` 入口检测当前是否 JRE 变体(`existsSync(join(process.resourcesPath, 'jre'))`),若是则跳过更新检查并 `console.log` 说明。完整方案(每变体独立 feed)留作 beta。

**D3. `superviseSetupRestart` 返回的 `stop()` 未保存/调用** — `desktop/electron/src/main.ts:166-177`
- **现状:** 调用 `superviseSetupRestart({...})` 但丢弃返回值。目前无泄漏(`proc.once('exit')` 自移除),但若将来加 interval 会泄漏。
- **修复:** 保存返回值到模块级变量 `stopSupervisor: (() => void) | null`,在 `killBackend()` 里调用并置 null。防御性,意图明确。

**D4. APP 模式后端崩溃无监管** — `desktop/electron/src/main.ts`
- **现状:** `startupAction === ShowWindow`(APP 模式,常见情况)时不注册任何 supervisor。后端崩溃后用户只见连接错误,无日志/提示。
- **修复:** APP 模式下也注册一个轻量 `child.on('exit', ...)`:崩溃(非 isQuitting 且 exitCode !== 0)时 `dialog.showErrorBox` 提示用户"后端意外退出,请重启应用或查看日志",避免静默。不自动重启(alpha 阶段保持简单,避免重启循环)。

## 验证策略

### 工具链整合(逐层最小可证明检查)
```bash
# Maven(artifactId 不变,只验模块路径解析)
./mvnw -pl toolchain/sdk-java install -DskipTests
./mvnw -pl toolchain/devkit-java install -DskipTests

# npm 4 包(验 file: 依赖重新解析 + 测试)
cd toolchain/sdk-ts && npm ci && npm test
cd toolchain/ui      && npm ci && npm run build && npm test
cd toolchain/dev     && npm ci && npm test
cd toolchain/cli     && npm ci && npm test

# CLI 仍能驱动官方插件构建(跨 toolchain↔OfficialPlugins 边界)
node toolchain/cli/bin/fengyu.mjs plugin build OfficialPlugins/plugin-markdown

# 整 reactor + smoke(顺带验证后端测试的 plugin-spec 相对路径修复)
./mvnw -pl FengYu -am package -DskipTests
scripts/plugin-tooling-local-smoke.sh
scripts/e2e-smoke.sh
npm run docs:build
```

### workflow 验证
本地无 `act`,改用契约测试 `scripts/release-workflow.test.mjs`(断言 workflow 路径结构)+ YAML 语法检查;真实运行留给 tag 触发。

### 修复验证
- **B1:** `mvn test` 跑后端测试(确认无 actuator restart 依赖);手动 `curl -X POST http://127.0.0.1:24056/actuator/restart` 应 404。
- **B2:** 启动 `run.sh` 无参数,日志应显示生成的 token;`curl` 不带 header 应 401。
- **D1:** 在 About.vue 点外链,应开系统浏览器而非新 Electron 窗口。
- **D2:** JRE 变体下 `checkForUpdates()` 不应弹更新框(需打包 JRE 变体或模拟 `resourcesPath/jre` 存在)。
- **D3/D4:** vitest 单元测试 `desktop/electron/test/supervisor.test.ts` 补 stop() 调用断言和 APP 崩溃提示断言。

## 风险与回退

- **风险:** 引用面广(6 类),漏改导致构建/测试失败。
- **缓解:** 分阶段提交 —— (1) 纯 `git mv` 搬迁(此时构建红);(2) 引用更新(逐步转绿);(3) 修复 6 个 Important。每阶段跑对应验证。
- **回退:** 全程在 `4.0.0-electron` 分支,不 force-push;若中途发现重大问题,`git reset` 回搬迁前 commit 即可。历史 rename 由 git 跟踪,无数据丢失风险。
