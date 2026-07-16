---
title: 构建与部署
description: 产出一个 .fyp 包——fengyu.plugin.json 构建编排、分阶段生命周期（prepare → install → test → build → validate → package）、GitHub Packages 认证、离线优先的安装校验，以及 .fyp 布局。
lang: zh
---

# 构建与部署

一个 `.fyp` 是一个具有固定运行时布局的 zip 归档。所有插件——无论第三方还是官方——都有**一条**构建流程，由 `fengyu plugin build` 和 `fengyu.plugin.json` 声明驱动。旧的 `OfficialPlugins/build-packages.sh` 脚本已被移除；三个随产品发布的官方插件现在由同一套 CLI 构建。

## `.fyp` 布局

产出的 `.fyp` 恰好包含运行时文件——绝不含源码、构建工具、`node_modules` 或凭据：

```
my-plugin-1.0.0.fyp
├── manifest.json          # 运行时元数据、权限、aiTools
├── ui/                    # Vite 构建产物（ui-src/dist）
│   ├── index.html
│   └── assets/…
└── backend/
    └── worker.jar         # shaded 的 JSON-RPC worker 可执行文件
```

`manifest.json` 声明 `ui.entry`（`ui/index.html`）和 `backend.command`（`java -jar backend/worker.jar`）。纯 UI 插件可以完全省略 `backend`。

## `fengyu.plugin.json`——构建声明

`manifest.json` 保持**仅运行时**。构建编排（源码路径、命令、输出目录）存放在项目根目录下另一个独立的 `fengyu.plugin.json` 中，CLI 会把它解析为一个规范化的项目模型：

```json
{
  "schemaVersion": 1,
  "ui": {
    "root": "ui-src",
    "output": "dist",
    "prepare": [["npm", "--prefix", "../shared", "run", "build"]],
    "install": ["npm", "ci"],
    "test": ["npm", "test"],
    "build": ["npm", "run", "build"]
  },
  "worker": {
    "root": "worker",
    "test": ["maven", "test"],
    "build": ["maven", "package", "-DskipTests"],
    "artifact": "target/my-worker.jar",
    "mainClass": "com.example.MyWorkerMain"
  },
  "package": { "outputDirectory": "dist-package" }
}
```

- `ui.prepare` 是一个有序的命令数组列表，在插件自身的 `npm ci` **之前**运行（例如用来构建共享的 `file:` 依赖）。不需要时省略即可。
- 逻辑命令 `maven` 会被解析为项目的 **Maven Wrapper**（`mvnw` / `mvnw.cmd`）。**绝不**会静默回退到系统 `mvn`——如果找不到 wrapper，构建会以一条精确的错误信息失败。
- 每个配置的路径都在插件根目录内解析；绝对路径、`..` 转义和符号链接转义都会被拒绝，错误信息中会包含对应的 JSON 字段路径。

零配置项目（没有 `fengyu.plugin.json`）依然可以构建：会探测到 `vite.config.*` 并当作 Vue/Vite 项目处理（运行 `npm run build` 再打包），其他情况则当作静态 `ui/` 项目处理。

## 分阶段生命周期

`fengyu plugin build` 为一个声明式项目运行一条有序、原子的流水线：

1. **ui.prepare**——按顺序执行每一条 `ui.prepare` 命令。
2. **ui.install**——存在 `package-lock.json` 时运行 `npm ci`（或在全新脚手架上运行 `npm install` 来生成它）。当 `node_modules` 存在且其 lockfile 指纹未变时跳过。
3. **ui.test**、**worker.test**——除非传入 `--skip-tests`，否则都会运行。
4. **ui.build**——Vite 构建（包含 `vue-tsc --noEmit` 类型检查）。
5. **worker.build**——Maven Wrapper 构建，产出 shaded JAR。
6. **assemble staging**——仅把 `manifest.json`、UI 产物、`backend/worker.jar` 以及声明的资源复制到一个隔离的临时目录。
7. **validate staging**——清单对象规则、`ui.entry` 解析到真实文件、后端命令引用 `backend/worker.jar`、worker JAR 带有配置的 `Main-Class` 与类入口，以及运行时目录树中不含源码 / `node_modules` / 带凭据的文件 / 符号链接。
8. **package**——写入 `<output>.tmp-<pid>-<random>`，检查已完成的归档，再原子地重命名为最终的 `.fyp`。

`--skip-tests` 仅跳过测试——绝不跳过类型检查或打包。任何失败（UI 构建、worker 构建、校验、重命名）都不会留下任何 `.fyp`、任何 `.tmp-*`、任何 staging 目录。

### GitHub Packages 认证

Java Worker SDK（`fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.0.0`）发布到 GitHub Packages。外部消费者通过脚手架生成的 `.mvn/settings.xml` 来解析它，该文件只从环境读取凭据：

```bash
export FENGYU_GITHUB_TOKEN='<a GitHub token with read:packages>'
# 也接受 GITHUB_TOKEN；CLI 会把它映射为 FENGYU_GITHUB_TOKEN 传给子进程。
```

生成的文件绝不会包含 token。如果 wrapper 根的 `settings.xml` 引用了 `maven.pkg.github.com`，而两个 token 都未设置，CLI 会抛出：

```
GitHub Packages authentication is required. Set FENGYU_GITHUB_TOKEN or GITHUB_TOKEN with read:packages.
```

仓库内部的构建（官方插件）从本地 reactor install 解析 SDK，因此**不需要** token。

## 构建官方插件

三个随产品发布的插件由同一套 CLI 构建——没有单独的脚本：

```bash
node plugin-cli/bin/fengyu.mjs plugin build OfficialPlugins/plugin-markdown
node plugin-cli/bin/fengyu.mjs plugin build OfficialPlugins/plugin-excel
node plugin-cli/bin/fengyu.mjs plugin build OfficialPlugins/plugin-email
```

每条都会写出 `OfficialPlugins/plugin-<name>/dist-package/fan.summer.<name>-4.0.0.fyp`。CI 在 `.github/workflows/plugin-tooling.yml` 中以矩阵方式构建它们。

## 安装产物

`fengyu plugin install` 会**离线优先**地校验包——在任何网络访问之前，它会检查归档限制与路径、归档内清单和 UI 入口，并对声明的 `backend/worker.jar` 做结构校验——然后再上传到市场。`fengyu.plugin.json` 中的 `package.resources[].to` 必须是 POSIX 风格的归档内相对路径：

```bash
fengyu plugin install ./dist-package/com.example.my-plugin-1.0.0.fyp \
  --host http://127.0.0.1:24056 --token "$FENGYU_TOKEN"
```

不安全或非法的包会在零次 fetch 调用下被拒绝。安装/更新/启用/卸载的 endpoint 见 [插件市场](/zh/plugins/marketplace)。

## 下一步

- [SDK 与 CLI](/zh/plugins/sdk-cli)——完整命令参考，包括 `--ui-only`、`--no-install` 与 `--skip-tests`。
- [Worker（JSON-RPC）](/zh/plugins/worker)——`worker.jar` 里装了什么。
- [插件市场](/zh/plugins/marketplace)——安装你构建的 `.fyp`。
