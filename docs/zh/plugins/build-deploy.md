---
title: 构建与部署
description: 将遵循约定的 FengYu 插件构建为原子写入的 .fyp 包及校验和。
lang: zh
---

# 构建与部署

Toolchain 2 从标准布局构建所有插件。旧版独立配置文件（已统一为 `manifest.json`）与任意构建命令数组已移除。

## 项目与包布局

源码插件包含 `manifest.json`、位于 `ui-src/package.json` 的 UI（或预构建 `ui/`），以及可选的
`worker/pom.xml` 或根 `pom.xml` Worker。打包后的 `.fyp` 只包含：

```text
manifest.json
ui/
  index.html
backend/
  worker.jar
```

UI 命令来自项目标准 scripts：`dev`、可选的 `test`、`build` —— 脚手架项目用 npm，仓库内官方插件用
Yarn 4（经 `packageManager` 锁定版本）。Worker 使用 Maven `test` 与
`package` 生命周期，并始终通过最近的 Maven Wrapper 执行。Worker 构建必须产出唯一的
`target/*-worker.jar`。

## 生命周期

```bash
fengyu check .
fengyu build .
```

`build` 会按需安装 UI 依赖，运行测试（除非指定 `--skip-tests`），构建 UI 与 Worker，暂存
纯运行时文件，校验 manifest、UI 与 JAR，最后原子写入：

```text
dist/<plugin-id>-<version>.fyp
dist/<plugin-id>-<version>.fyp.sha256
```

使用 `--out <file>` 选择其他归档路径。构建失败会清理临时 staging 与归档文件。

外部 Java 插件通过生成的 `.mvn/settings.xml` 解析独立版本的 Worker SDK。设置具有
`read:packages` 权限的 `FENGYU_GITHUB_TOKEN` 或 `GITHUB_TOKEN`；凭据只经环境传递，绝不写入项目。

## 官方插件

```bash
node toolchain/cli/bin/fengyu.mjs build OfficialPlugins/plugin-markdown
node toolchain/cli/bin/fengyu.mjs build OfficialPlugins/plugin-excel
node toolchain/cli/bin/fengyu.mjs build OfficialPlugins/plugin-email
node toolchain/cli/bin/fengyu.mjs build OfficialPlugins/plugin-offlinepython
```

通过宿主插件市场 UI 或 `POST /api/plugin-market/upload` 安装 `.fyp`。宿主会在注册前校验归档
限制、路径、manifest、UI 入口和 Worker 结构。
