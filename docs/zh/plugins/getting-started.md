---
title: 入门
description: 用 fengyu plugin create 脚手架生成一个 FengYu 插件，了解生成的目录结构，并用 fengyu plugin dev 在本地运行它。
lang: zh-CN
---

# 入门

本页将带你从零开始创建一个新插件，讲解脚手架生成的目录结构，以及在本地运行它。`fengyu plugin` CLI 共有五个子命令——`create`、`dev`、`build`、`validate`、`install`——本页覆盖前两个。完整的命令表见 [SDK 与 CLI](/zh/plugins/sdk-cli) 页面。

## 脚手架生成插件

用 `fengyu plugin create` 创建一个新插件。你必须传入一个反向 DNS 的 `--id`：

```bash
fengyu plugin create ./my-plugin --id com.example.my-plugin
```

脚手架会拒绝覆盖已存在的目录，它会写入一份初始的 `manifest.json`、`package.json`、一份最小化的 `ui/index.html` + `ui/app.js`，并把 SDK bundle 复制到 `ui/sdk.js`。人类可读的 `name` 由 `--id` 的最后一段派生（本例中为 `My Plugin`）。

## 目录结构

脚手架生成后，项目结构如下：

```
my-plugin/
├── manifest.json     # 元数据、权限、aiTools——见 /zh/plugins/manifest
├── package.json      # npm 清单；依赖 @fengyu/plugin-sdk
└── ui/
    ├── index.html    # 入口 HTML，通过 /plugin-runtime/{id}/ui/index.html 提供
    ├── app.js        # 你的 UI 代码；import './sdk.js'
    └── sdk.js        # @fengyu/plugin-sdk bundle（由脚手架复制）
```

要组成一个可运行的包，还缺一件东西——worker——由你自己添加：

- `backend/worker.jar`——你的 JSON-RPC worker 可执行文件，使用 Java Worker SDK 构建（参见 [Worker](/zh/plugins/worker) 与 [构建与部署](/zh/plugins/build-deploy)）。在 `manifest.json` 的 `backend.command` 下声明其启动命令。

## 编辑清单

打开 `manifest.json`，调整脚手架无法猜测的字段。通常需要改动的最小集合是：

```json
{
  "schemaVersion": 1,
  "id": "com.example.my-plugin",
  "name": "My Plugin",
  "description": "What this plugin does",
  "version": "1.0.0",
  "author": "Your Name",
  "icon": "puzzle-outline",
  "category": "other",
  "ui": { "entry": "ui/index.html" },
  "backend": { "command": "java -jar backend/worker.jar", "protocol": "json-rpc-2.0" },
  "permissions": [],
  "official": false,
  "aiTools": []
}
```

完整的 schema——包括每一个合法的 `category` 与 `permissions` 取值——见 [清单](/zh/plugins/manifest)。

## 在本地运行

`fengyu plugin dev` 会启动一个微型的回环开发宿主，提供你的 `ui/` 并模拟宿主的 `postMessage` 桥，让你无需后端即可驱动 `FengYuClient`：

```bash
fengyu plugin dev --port 4173
```

- 开发宿主仅绑定 `127.0.0.1`。
- 打开打印出的 URL（`http://127.0.0.1:4173/__fengyu`）会加载一个 RPC 检查器外壳，它把你的 `ui/index.html` 托管在一个沙箱化的 iframe 中。
- 文件变更会通过 Server-Sent Events 触发热重载。
- `rpc.invoke` 调用会返回一个开发用的 mock `{success:true, devMock:true, method, params}`——当你需要真实行为时再连接真实的 worker。

默认端口是 `4173`；省略 `--port` 即使用它。

## 下一步

- [清单](/zh/plugins/manifest)——每个字段、类型与默认值。
- [Worker（JSON-RPC）](/zh/plugins/worker)——编写 `backend/worker.jar`。
- [UI 微前端](/zh/plugins/ui-microfrontend)——你的 `ui/app.js` 使用的 `FengYuClient` API。
- [构建与部署](/zh/plugins/build-deploy)——用 `fengyu plugin build` 生成 `.fyp`。
