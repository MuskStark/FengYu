---
title: 插件市场
description: 插件市场通过 /api/plugin-packages 提供本地 .fyp 生命周期——安装（.fyp 上传、本地路径）、预检（inspect）、启用/禁用以及卸载插件。目录浏览与按 id 安装/更新位于统一插件商店（/api/plugin-store）之下，它还聚合了 Claude Code、OpenAI Codex 与 Grok Build 市场。
lang: zh-CN
---

# 插件市场

插件市场是宿主的插件注册中心。自 4.0.0-rc.1 起，本地 `.fyp` 生命周期由 `/api/plugin-packages` 提供——安装（upload）、预检（inspect）、启用、禁用与卸载，每一个插件（官方与第三方一视同仁）都在此管理；`POST /upload` 是构建好的 `.fyp` 的安装路径（市场 UI 的上传按钮走的是这条）。目录浏览与按 id 安装/更新移到了统一插件商店 `/api/plugin-store` 之下。已弃用的 `/api/plugin-market` 兼容层仍将生命周期端点 1:1 转发（附带 `Deprecation` 响应头）；其旧的目录端点一律返回 `410 Gone`，并在响应中指明对应的 `/api/plugin-store` 替代端点。

## 统一插件商店（Claude / Codex / Grok / FengYu）

> 自 4.0.0-alpha.7 起。除上述 FengYu 市场外，**Stores** 标签页还订阅第三方 **Claude Code**、**OpenAI Codex** 与 **Grok Build** 市场目录，并把它们合并成一个可浏览、带来源徽标的网格。

- **来源（Sources）。** 在 `/api/plugin-store/sources` 下添加 / 删除 / 刷新市场来源。FengYu 来源默认内置；Claude 来源提供 `.claude-plugin/marketplace.json`，Codex 来源提供 `.agents/plugins/marketplace.json`，Grok 来源提供 `.grok-plugin/marketplace.json`（例如 [xAI 官方目录](https://raw.githubusercontent.com/xai-org/plugin-marketplace/main/.grok-plugin/marketplace.json)）。
- **安装。** Claude/Codex/Grok 插件通过克隆其 git 源（JGit）安装。Claude 与 Grok 的 `url`/子目录来源会校验固定 sha；Codex 与 Grok 的 `local` 来源会把解析出的 HEAD sha 记入安装记录，确保每次安装都带有可审计的指纹。
- **安全。** 目录中的 `name` 在触及文件系统前会被转成单段安全 segment；克隆 URL 仅限 `https`/`http`/`file`；skill 提取跳过 symlink；目录响应上限 16 MiB。第三方目录内容一律视为不可信。
- **签名 FengYu 包。** FengYu 目录可发布 `sha256`、Ed25519 `signature` 与 `keyId`。宿主只下载
  一次，校验这份精确字节，并依据内置及用户 trust root 检查发布者 namespace、package/key
  吊销，再安装同一个文件。用户根位于 `<runtime-root>/trusted-plugin-publishers.json`
  （默认 `<working-directory>/.fengyu/...`）；用
  `fengyu sign` 生成目录签名元数据。
- **Windows 非沙箱开关。** 在没有原生进程沙箱的平台上，设置页的一行（需二次确认，默认关闭）允许插件 Worker 走 `unrestricted()` 通道。详见 alpha.7 更新日志的安全加固。

## 官方插件

Infinia 自带一组官方插件 —— 智能体开箱即可编排的真实能力。每一个都有独立页面：

| 插件 | 作用 | 文档 |
| --- | --- | --- |
| **Excel 拆分** | 按工作表、列值或复杂规则拆分工作簿 —— 附带六个 AI 工具。 | [Excel 拆分 →](/zh/plugins/official-excel) |
| **邮件中心** | 多账户 SMTP/IMAP、通讯录管理、批量发送、归档 —— 九个需确认的 AI 工具。 | [邮件中心 →](/zh/plugins/email-center) |
| **Offline Python Builder** | 构建包含全部依赖的离线 Python 安装仓库（wheelhouse）—— 六个 AI 工具与异步构建。 | [Offline Python →](/zh/plugins/official-offlinepython) |
| **Markdown 编辑器** | 分栏编辑器，采用隔离的服务端渲染。 | [Markdown 编辑器 →](/zh/plugins/official-markdown) |

## 浏览目录

自 4.0.0-rc.1 起，目录浏览发生在 `/api/plugin-store` 之下——即 UI 中 **Stores** 标签页渲染的统一、带来源徽标的视图。FengYu 来源列出每个可安装插件及其清单、`source`（`OFFICIAL` 或 `THIRD_PARTY`）、`enabled` 标志以及 `supportsAi` 徽标，并与上文所述的 Claude/Codex/Grok 来源合并。已弃用的 `GET /api/plugin-market` 别名返回 `410 Gone`，并在响应中指明替代端点 `/api/plugin-store/catalog`。

## 安装插件

安装路径有三条——两条本地的位于 `/api/plugin-packages` 之下，一条来自商店目录，位于 `/api/plugin-store` 之下：

| 方法 + 路径 | Body | 适用场景 |
| --- | --- | --- |
| `POST /api/plugin-packages/upload` | multipart `.fyp` 文件 | 你已有一个构建好的 `.fyp` 归档（常规路径；市场 UI 的上传按钮走的是这条）。 |
| `POST /api/plugin-packages/upload-native` | JSON `{path}` | 仅桌面端——从一个已存在于本地文件系统路径上的 `.fyp` 安装。 |
| `POST /api/plugin-store/{uid}/install` | — | 按 uid 安装一个已在商店目录中列出的插件。 |

- `POST /api/plugin-packages/upload` 解析上传的 `.fyp`，抽取其 `manifest.json`，校验结构，并注册该插件。其 `source` 成为 `THIRD_PARTY`。当包的 id 与某个已安装插件相同时，上传会**替换它**——宿主先停止运行中的 worker（更新门控）并原子地交换插件包目录；启用状态保持不变。
- `POST /api/plugin-store/{uid}/install` 是一键安装，针对已在商店目录中存在但尚未本地安装的插件。已弃用的 `POST /api/plugin-market/{id}/install` 返回 `410 Gone`，并在响应中指明该替代端点。

在市场 UI 中，每个本地 `.fyp` 的选择都会先经过由下方 inspect 端点驱动的确认对话框：显示传入版本与已安装版本的对比（`1.0.0 → 1.1.0`），在降级或同版本重装时给出警告，确认后才执行上传。已安装插件的详情抽屉也提供**从本地更新**入口。

::: tip
用市场 UI 上传构建好的 `.fyp`，或直接 POST：
`curl -F file=@./my-plugin-1.0.0.fyp -H "Authorization: Bearer $FENGYU_TOKEN" http://<host>/api/plugin-packages/upload`。
:::

## 更新

```
POST /api/plugin-store/{uid}/update
```

拉取某个商店目录插件的最新版本并替换已安装的副本。无需 body——宿主从来源目录中解析“最新”。已弃用的 `POST /api/plugin-market/{id}/update` 返回 `410 Gone`，并在响应中指明该替代端点。

更新是事务性的：旧包会保留为 rollback snapshot，直到新 Worker 通过保留启动握手；spawn/握手
失败会恢复并 preflight 旧包，宿主启动时也会恢复中断事务。当新 manifest 增加权限时，只有在向
用户展示新增权限后才传 `?confirmPermissions=true`；否则宿主拒绝此次权限升级。

### 从本地包更新

对于不在任何目录中的插件（例如从本地构建的 `.fyp` 安装的插件），上面的目录更新无法解析下载 URL。改为上传新包——相同 id、新版本：

```
POST /api/plugin-packages/inspect       # multipart "file"；或 /inspect-native {"path": "..."}
POST /api/plugin-packages/upload        # 确认后替换已安装的副本
```

`/inspect` 在**不安装**的前提下读取传入包的 manifest，返回 `PackageInspection`——`{id, name, version, installed, installedVersion, comparison}`，其中 `comparison` 为 `upgrade`、`downgrade`、`same`，id 尚未安装时为 `null`——客户端可以在上传停止 worker 并交换插件包之前，先确认版本变化（并在回滚时给出警告）。

## 启用 / 禁用

```
PATCH /api/plugin-packages/{id}/enabled
{ "enabled": true }   // 或 false
```

切换插件的 enabled 标志。**禁用会立即停止 worker 进程**——宿主的 `PluginProcessManager` 会把该 OS 进程拆毁，任何进行中的 RPC 都会被拒绝。启用不会急于启动 worker；进程在首次调用时惰性启动。完整生命周期见 [插件概述](/zh/plugins/overview)。

## 卸载

```
DELETE /api/plugin-packages/{id}?deleteData=true|false
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
| `GET /api/plugin-store/catalog` | 浏览统一商店目录 → 带来源徽标的插件网格 |
| `POST /api/plugin-packages/upload` | 从上传的 `.fyp` 安装（同 id 已安装则更新） |
| `POST /api/plugin-packages/upload-native` | 从本地路径安装（桌面端） |
| `POST /api/plugin-packages/inspect` | 预览上传的 `.fyp` → 安装还是更新 + 版本变化 |
| `POST /api/plugin-packages/inspect-native` | 从本地路径预览（桌面端） |
| `POST /api/plugin-store/{uid}/install` | 按 uid 安装一个商店目录插件 |
| `POST /api/plugin-store/{uid}/update?confirmPermissions=<boolean>` | 健康门控更新；显式确认新增权限 |
| `PATCH /api/plugin-packages/{id}/enabled` | 启用/禁用（禁用会停止 worker） |
| `DELETE /api/plugin-packages/{id}?deleteData=<boolean>` | 使用显式的运行数据保留/删除策略卸载 |

已弃用的 `/api/plugin-market` 兼容层会把上表中生命周期各行 1:1 转发（附带 `Deprecation` 响应头）；其目录各行——`GET /api/plugin-market`、`POST /api/plugin-market/{id}/install`、`POST /api/plugin-market/{id}/update`——一律返回 `410 Gone`，并在响应中指明对应的 `/api/plugin-store` 替代端点。

## 下一步

- [插件概述](/zh/plugins/overview)——install → enable → invoke → disable → uninstall 生命周期。
- [构建与部署](/zh/plugins/build-deploy)——产出一个 `.fyp` 以供上传。
- [SDK 与 CLI](/zh/plugins/sdk-cli)——`create` 与 `build` 命令。
