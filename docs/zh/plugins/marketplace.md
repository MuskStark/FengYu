---
title: 插件市场
description: 插件市场提供 /api/plugin-market——浏览目录、以三种方式安装（.fyp 上传、本地路径、目录 id）、更新、启用/禁用以及卸载插件。统一插件商店（/api/plugin-store）还聚合了 Claude Code 与 OpenAI Codex 市场。
lang: zh-CN
---

# 插件市场

插件市场是宿主的插件注册中心。它通过 `/api/plugin-market` 暴露，用于浏览目录以及管理每一个插件（官方与第三方一视同仁）的安装生命周期。所有生命周期操作（安装、更新、启用、禁用、卸载）都经由这些 endpoint 进行；`POST /upload` 是构建好的 `.fyp` 的安装路径（市场 UI 的上传按钮走的是这条）。

## 统一插件商店（Claude / Codex / FengYu）

> 自 4.0.0-alpha.7 起。除上述 FengYu 市场外，**Stores** 标签页还订阅第三方 **Claude Code** 与 **OpenAI Codex** 市场目录，并把它们合并成一个可浏览、带来源徽标的网格。

- **来源（Sources）。** 在 `/api/plugin-store/sources` 下添加 / 删除 / 刷新市场来源。FengYu 来源默认内置；Claude 来源提供 `.claude-plugin/marketplace.json`，Codex 来源提供 `.agents/plugins/marketplace.json`。
- **安装。** Claude/Codex 插件通过克隆其 git 源（JGit）安装。Claude 的 `url`/`git-subdir` 来源会校验固定 sha；Codex 的 `local` 来源会把解析出的 HEAD sha 记入安装记录，确保每次安装都带有可审计的指纹。
- **安全。** 目录中的 `name` 在触及文件系统前会被转成单段安全 segment；克隆 URL 仅限 `https`/`http`/`file`；skill 提取跳过 symlink；目录响应上限 16 MiB。第三方目录内容一律视为不可信。
- **Windows 非沙箱开关。** 在没有原生进程沙箱的平台上，设置页的一行（需二次确认，默认关闭）允许插件 Worker 走 `unrestricted()` 通道。详见 alpha.7 更新日志的安全加固。

## 官方插件

Infinia 自带一组官方插件 —— 智能体开箱即可编排的真实能力。每一个都有独立页面：

| 插件 | 作用 | 文档 |
| --- | --- | --- |
| **Excel 拆分** | 按工作表、列值或复杂规则拆分工作簿 —— 附带六个 AI 工具。 | [Excel 拆分 →](/zh/plugins/official-excel) |
| **邮件中心** | 多账户 SMTP/IMAP、通讯录管理、批量发送、归档 —— 九个需确认的 AI 工具。 | [邮件中心 →](/zh/plugins/email-center) |
| **Offline Python Builder** | 构建包含全部依赖的离线 Python 安装仓库（wheelhouse）—— 六个 AI 工具与异步构建。 | [Offline Python →](/zh/plugins/official-offlinepython) |
| **Markdown 编辑器** | 分栏编辑器，采用隔离的服务端渲染。 | [Markdown 编辑器 →](/zh/plugins/official-markdown) |
| **浏览器代理** | 通过 Playwright 驱动真实 Chromium —— 导航、点击、输入、抓取、截图、执行 JS —— 九个 AI 工具。 | [浏览器代理 →](/zh/plugins/official-browser) |

## 浏览目录

`GET /api/plugin-market` 把完整目录以 `MarketplacePlugin[]` 形式返回——每一个已安装插件及其清单、`source`（`OFFICIAL` 或 `THIRD_PARTY`）、`enabled` 标志以及 `supportsAi` 徽标。市场 UI 渲染的就是这个列表。

## 安装插件

在 `/api/plugin-market` 下有三条安装路径：

| 方法 + 路径 | Body | 适用场景 |
| --- | --- | --- |
| `POST /upload` | multipart `.fyp` 文件 | 你已有一个构建好的 `.fyp` 归档（常规路径；市场 UI 的上传按钮走的是这条）。 |
| `POST /upload-native` | JSON `{path}` | 仅桌面端——从一个已存在于本地文件系统路径上的 `.fyp` 安装。 |
| `POST /{id}/install` | — | 通过 id 安装一个已在目录中列出的插件。 |

- `POST /upload` 解析上传的 `.fyp`，抽取其 `manifest.json`，校验结构，并注册该插件。其 `source` 成为 `THIRD_PARTY`。
- `POST /{id}/install` 是一键安装，针对已在目录索引中存在但尚未本地安装的插件。

::: tip
用市场 UI 上传构建好的 `.fyp`，或直接 POST：
`curl -F file=@./my-plugin-1.0.0.fyp -H "Authorization: Bearer $FENGYU_TOKEN" http://<host>/api/plugin-market/upload`。
:::

## 更新

```
POST /api/plugin-market/{id}/update
```

拉取某个目录插件的最新版本并替换已安装的副本。无需 body——宿主从目录中解析“最新”。

## 启用 / 禁用

```
PATCH /api/plugin-market/{id}/enabled
{ "enabled": true }   // 或 false
```

切换插件的 enabled 标志。**禁用会立即停止 worker 进程**——宿主的 `PluginProcessManager` 会把该 OS 进程拆毁，任何进行中的 RPC 都会被拒绝。启用不会急于启动 worker；进程在首次调用时惰性启动。完整生命周期见 [插件概述](/zh/plugins/overview)。

## 卸载

```
DELETE /api/plugin-market/{id}?deleteData=true|false
```

数据策略必须显式指定。市场 UI 会进行两次确认：先确认卸载，再确认是否永久删除运行数据。
`deleteData=false` 会停止 worker 并删除解包后的插件包，但保留 `plugin-data/<id>` 以及已 provision
的数据库命名空间/凭据，供以后重装继续使用。`deleteData=true` 还会删除这些资源；若文件删除失败，
endpoint 会返回错误，而不会假报成功。无法完成的数据库清理会以 `DELETE_PENDING` 状态保留并重试。

## 目录 URL 覆盖

市场所浏览的目录从一个可配置的 URL 拉取。用一个系统属性把宿主指向另一个目录（例如私有 registry）：

```bash
java -Dfengyu.marketplace.catalog-url=https://internal.example/fengyu-catalog.json -jar fengyu.jar
```

## Endpoint 汇总

| Endpoint | 动作 |
| --- | --- |
| `GET /api/plugin-market` | 浏览目录 → `MarketplacePlugin[]` |
| `POST /api/plugin-market/upload` | 从上传的 `.fyp` 安装 |
| `POST /api/plugin-market/upload-native` | 从本地路径安装（桌面端） |
| `POST /api/plugin-market/{id}/install` | 按 id 安装一个目录插件 |
| `POST /api/plugin-market/{id}/update` | 更新到最新 |
| `PATCH /api/plugin-market/{id}/enabled` | 启用/禁用（禁用会停止 worker） |
| `DELETE /api/plugin-market/{id}?deleteData=<boolean>` | 使用显式的运行数据保留/删除策略卸载 |

## 下一步

- [插件概述](/zh/plugins/overview)——install → enable → invoke → disable → uninstall 生命周期。
- [构建与部署](/zh/plugins/build-deploy)——产出一个 `.fyp` 以供上传。
- [SDK 与 CLI](/zh/plugins/sdk-cli)——`create` 与 `build` 命令。
