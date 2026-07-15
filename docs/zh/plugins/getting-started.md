---
title: 入门
description: 用 fengyu plugin create 脚手架生成一个 FengYu 插件，了解生成的 Vue/Vuetify 项目结构，并用 fengyu plugin dev 在本地运行它。
lang: zh-CN
---

# 入门

本页将带你从零开始创建一个新插件，讲解脚手架生成的目录结构，以及在本地运行它。`fengyu plugin` CLI 共有五个子命令——`create`、`dev`、`build`、`validate`、`install`——本页覆盖前两个。完整的命令表见 [SDK 与 CLI](/zh/plugins/sdk-cli) 页面。

## 脚手架生成插件

用 `fengyu plugin create` 创建一个新插件。你必须传入一个反向 DNS 的 `--id`：

```bash
fengyu plugin create ./my-plugin --id com.example.my-plugin
```

脚手架**默认会在新项目中执行 `npm install`**，使其立即可运行。传入 `--no-install` 可跳过安装（例如你想使用其他包管理器或本地 `file:` 依赖时）：

```bash
fengyu plugin create ./my-plugin --id com.example.my-plugin --no-install
```

脚手架会拒绝覆盖已存在的目录，并基于 `vue-codex` 模板写入一个 Vue 3 + Vuetify（Material Design 3）项目。人类可读的 `name` 由 `--id` 的最后一段派生（本例中为 `My Plugin`）。FengYu UI 组件从 [`@fengyu/plugin-ui`](/zh/plugins/ui-components) 导入；生成的 `src/main.ts` 已经绑定了宿主主题与 locale，你无需自行接线。

## 快速开始

从无到有打包出 `.fyp` 的完整循环只需四条命令：

```bash
fengyu plugin create ./my-plugin --id com.example.my-plugin
cd my-plugin
fengyu plugin dev .
fengyu plugin build .
```

- `create` 默认会安装依赖；`--no-install` 可跳过。
- `src/main.ts` 已经绑定主题/locale，并把 `FengYuClient` 注入整个应用——见 [UI 组件](/zh/plugins/ui-components)。
- 你用来组合的基础控件（`v-btn`、`v-card`、`v-list`……）就是普通的 Vuetify 控件，已由 `createFengYuVuetify` 全局注册。
- FengYu 组件（`FyFilePicker`、`FyStepWizard`……）从 `@fengyu/plugin-ui` 导入。
- 旧式静态插件（没有构建步骤的纯 `ui/index.html` + `ui/app.js`）依然被 `dev` 与 `build` 完整支持；迁移是**可选的**。

## 目录结构

脚手架生成后，项目结构如下：

```
my-plugin/
├── manifest.json     # 元数据、权限、aiTools——见 /zh/plugins/manifest
├── package.json      # npm 清单；依赖 @fengyu/plugin-sdk + @fengyu/plugin-ui
├── index.html        # Vite 入口 HTML
├── vite.config.ts    # 构建到 ./ui（从而 manifest.ui.entry 能解析）
├── tsconfig.json
└── src/
    ├── main.ts       # 挂载应用、绑定主题/locale、注入 FengYuClient
    └── App.vue       # 你的 UI：FyPluginShell + FyPageHeader + FyFilePicker + ……
```

::: tip 静态插件
旧式静态插件保留原有结构——`ui/index.html` + `ui/app.js` + 复制的 `ui/sdk.js`，无 Vite。`dev` 与 `build` 会自动探测项目类型。参见 [UI 微前端](/zh/plugins/ui-microfrontend)。
:::

要组成一个连接后端的插件，还缺一件东西——worker，由你自己添加：

- `backend/worker.jar`——你的 JSON-RPC worker 可执行文件，使用 Java Worker SDK 构建（参见 [Worker](/zh/plugins/worker) 与 [构建与部署](/zh/plugins/build-deploy)）。在 `manifest.json` 的 `backend.command` 下声明其启动命令。开发模式下，模拟器会用 mock 回答 `rpc.invoke`，因此你可以在 worker 存在之前先把 UI 做好。

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
  "permissions": [],
  "official": false,
  "aiTools": []
}
```

注意 `ui.entry` 指向 `ui/index.html`——这是 `vite build` 的**输出**（由 `vite.config.ts` 的 `build.outDir: 'ui'` 配置）。完整的 schema——包括每一个合法的 `category` 与 `permissions` 取值——见 [清单](/zh/plugins/manifest)。

## 在本地运行

`fengyu plugin dev` 会探测出 Vue/Vite 项目，启动 Vite（带 HMR），并提供一个回环模拟器页面，把你的应用托管在沙箱化 iframe 中，并回答 SDK 的 `postMessage` 调用：

```bash
fengyu plugin dev .
```

- 开发宿主仅绑定 `127.0.0.1`。
- 打开打印出的 URL（`http://127.0.0.1:4173/__fengyu`）会加载 RPC 检查器外壳；它的 iframe 指向 Vite 开发服务器，所以编辑会热重载。
- 模拟器用当前主题/locale 回答 `host.ready`，用开发 mock `{success:true, devMock:true, method, params}` 回答 `rpc.invoke`，用示例文件回答 `files.open`，用成功回答 `notify`。
- 在检查器的控制按钮上切换**主题**（dark/light）与 **locale**（en/zh），以验证你的 UI 是否对 `bindFengYuEnvironment` 做出反应。
- 默认端口是 `4173`；传入 `--port` 可更改。

## 下一步

- [UI 组件](/zh/plugins/ui-components)——`@fengyu/plugin-ui` 套件：外壳、文件选择器、步骤向导等。
- [清单](/zh/plugins/manifest)——每个字段、类型与默认值。
- [Worker（JSON-RPC）](/zh/plugins/worker)——编写 `backend/worker.jar`。
- [UI 微前端](/zh/plugins/ui-microfrontend)——你的 UI 对话的 `FengYuClient` API。
- [构建与部署](/zh/plugins/build-deploy)——用 `fengyu plugin build` 生成 `.fyp`。
