# Codex 风格 Vuetify 插件 UI 设计

**日期：** 2026-07-14
**状态：** 已确认，待实施计划

## 1. 背景与目标

FengYu 4.0 插件 UI 运行在受 CSP 保护的沙箱 iframe 中。当前 `fengyu plugin create`
生成原生 HTML、JavaScript 和一份复制到项目内的 SDK 文件。宿主的 `codex.css` 无法跨
iframe 继承，新插件也没有统一的 Vue 项目结构或官方 UI 组件。

本功能提供基于 Vue 3 与 Vuetify 3 的官方 Codex 风格 UI 能力，并将其作为 CLI 创建
插件时的默认选择。新项目创建后已经注册主题、SDK 和组件，不需要开发者再次“激活”
UI 套件。

目标：

- 发布独立 npm 包 `@infinia/plugin-ui`。
- 基于 Vuetify 组件和无障碍行为实现 Codex 风格主题，不重复实现基础控件。
- 提供 FengYu 场景专用的复合组件，并直接集成 `@infinia/plugin-sdk`。
- 让 `fengyu plugin create` 默认生成可运行、可构建的 Vue/Vite 插件工作台。
- 保持现有原生 HTML 插件的开发、校验和构建兼容性。

非目标：

- 不把宿主 CSS 或宿主 Vuetify 实例注入插件 iframe。
- 不要求插件采用固定路由、Pinia 或业务数据模型。
- 不在首版提供非 Vue 框架适配层。
- 不改变插件 manifest schema 或后端 worker 协议。

## 2. 方案选择

采用“标准 Vue 组件库 + Vite 插件模板”：

- `@infinia/plugin-ui` 以 npm 包独立版本化。
- Vue、Vuetify 和 `@infinia/plugin-sdk` 作为 peer dependencies，避免组件库内部出现多份
  Vue 运行时；插件的 Vite 生产构建将实际依赖打进 iframe 静态资源。
- CLI 默认生成 Vue 3、Vite、Vuetify、插件 UI 包和 SDK 的完整工程。

未选择的方案：

- 单体插件框架会把路由、状态管理和 UI 强绑定，升级影响面过大。
- CLI 复制组件源码会导致每个插件形成分叉，无法统一下发主题、无障碍和缺陷修复。
- 宿主运行时注入会增加 iframe、CSP 和宿主/插件版本协商的复杂度。

## 3. 包结构与公共 API

在仓库中新增 `plugin-ui/vue/`，其 npm 包名为 `@infinia/plugin-ui`。包分为三层。

### 3.1 主题层

导出 `createFengYuVuetify(options?)`，负责：

- 创建并返回配置完成的 Vuetify 实例。
- 注册 `fengyuCodexLight` 和 `fengyuCodexDark` 主题。
- 注册 MDI 图标配置、组件 defaults、紧凑密度、圆角、排版和焦点状态。
- 使用边框和表面层级替代大面积阴影。
- 允许测试或高级插件传入自定义 SDK client、初始 locale 和有限的主题覆盖。

主题的已确认视觉基线为：扁平表面、细边框、紧凑密度、克制圆角；暗色与亮色模式
保持相同的信息层级。视觉稿保存在本次设计会话的 `.superpowers/brainstorm/` 目录，
不作为生产依赖。

### 3.2 Vuetify 基础层

插件作者直接使用 Vuetify 的 `v-btn`、`v-icon`、`v-text-field`、`v-textarea`、
`v-select`、`v-card`、`v-chip`、`v-alert`、`v-tabs`、`v-dialog`、`v-data-table`、
`v-pagination` 和其他基础组件。`@infinia/plugin-ui` 通过 theme 和 defaults 统一其 Codex
外观，不制作同名透传包装组件。

### 3.3 FengYu 复合层

首版公开以下组件：

- `FyPluginShell`：响应式侧栏、顶栏和内容区。
- `FyPageHeader`、`FyToolbar`：统一页面标题、说明和操作区。
- `FyStepWizard`：步骤状态、前进/后退校验和窄屏布局。
- `FyFilePicker`、`FyDirectoryPicker`：宿主授权文件与目录选择。
- `FyEmptyState`、`FyLoadingState`、`FyErrorState`：标准页面状态。
- `FyNotificationCenter`：宿主通知与开发环境回退呈现。
- `FyConfirmDialog`：破坏性操作确认与焦点管理。
- `FyTaskTable`：任务状态、分页、加载、错误和空状态组合。
- `FyPermissionNotice`：权限需求及拒绝后的恢复指引。

组件支持按需导入。包不得要求插件安装 Vue Router 或 Pinia。

## 4. SDK 与环境同步

`@infinia/plugin-ui` 直接集成 `@infinia/plugin-sdk`，同时允许应用安装时注入替代 client，
以便单元测试和开发模拟器使用。

初始化数据流：

1. 插件调用 `fengyu.ready()` 获取宿主环境。
2. UI 适配器根据 `theme` 激活 `fengyuCodexLight` 或 `fengyuCodexDark`。
3. UI 适配器根据 `locale` 设置 Vuetify locale。
4. UI 适配器订阅 SDK 的 `environment` 事件，并实时同步后续主题与语言变化。
5. 插件卸载时取消订阅并释放 SDK client。

业务组件行为：

- `FyFilePicker` 根据模式调用 `files.open`、`files.inputDirectory` 或
  `files.outputDirectory`。
- `FyNotificationCenter` 优先调用宿主 `notify`；模拟器不可用时显示本地通知。
- 权限不足显示 `FyPermissionNotice`；SDK 超时显示可重试错误；用户取消文件选择返回
  正常的空结果，不显示错误。
- 异步组件具备禁用、加载、重试和取消状态。
- 破坏性操作必须通过 `FyConfirmDialog`，并提供键盘操作与正确的焦点恢复。

## 5. CLI 默认模板

`fengyu plugin create ./my-plugin --id com.example.my-plugin` 默认生成：

```text
my-plugin/
├── manifest.json
├── package.json
├── vite.config.ts
├── tsconfig.json
├── src/
│   ├── main.ts
│   └── App.vue
└── ui/                 # Vite 构建产物，不手工编辑
```

生成项目的 `package.json` 包含 Vue 3、Vuetify 3、Vite、TypeScript、
`@infinia/plugin-ui` 和 `@infinia/plugin-sdk`。`main.ts` 已完成 SDK ready、Vuetify 和 UI
包注册。`App.vue` 提供可直接改造的插件工作台，包含侧栏、工具栏、表单、文件选择、
任务表格、通知以及空状态示例。

创建命令默认安装依赖。`--no-install` 跳过安装，用于离线环境或开发者自行选择包管理器
的场景。依赖安装失败时保留已生成项目，并显示失败命令与重试指引，避免删除用户可用的
脚手架结果。

不再为新项目复制 `ui/sdk.js`；SDK 由 Vite 打包。现有原生 HTML 项目仍可继续使用复制
的 SDK 文件。

## 6. 开发与构建流程

### 6.1 开发

`fengyu plugin dev` 检测项目类型：

- Vue/Vite 项目：启动 Vite 开发服务和 FengYu iframe 模拟宿主，提供热更新。
- 原生 HTML 项目：保留现有静态服务器与刷新机制。

模拟宿主支持主题切换、语言切换、RPC 检查、通知、文件选择和授权失败模拟。它必须使用
与生产宿主相同的 SDK 消息形状，防止组件只在开发环境可用。

### 6.2 构建

`fengyu plugin build` 检测到生成模板时按以下顺序执行：

1. 执行项目声明的前端生产构建，将产物输出到 `ui/`。
2. 校验 manifest 和 `ui.entry`。
3. 将插件文件打包为 `.fyp`。

Vite 或 npm 命令失败时，CLI 保留原始退出码和错误输出，不生成部分 `.fyp`。原生 HTML
项目跳过前端构建，沿用现有校验和打包逻辑。

## 7. 视觉与交互规范

- 颜色只来自 Vuetify theme tokens；业务组件不得写死页面背景和正文颜色。
- 默认密度比标准 Material Design 3 更紧凑，但点击目标和键盘焦点必须满足无障碍要求。
- 常规容器使用细边框表达层级，阴影只用于菜单、浮层和模态对话框。
- 明暗主题使用一致的语义 token，不以简单颜色反转实现。
- 状态不能只依赖颜色表达，必须同时提供文本或图标。
- 窄屏下侧栏收起为抽屉，表单由双列降为单列，数据表允许受控横向滚动。
- 主题切换不执行大范围动画；普通交互反馈保持短促、克制。

## 8. 错误处理

- SDK 未 ready：依赖 SDK 的组件显示初始化状态，不发送请求。
- SDK 版本不兼容：显示不可重试的兼容性错误和宿主/SDK 版本。
- 请求超时：显示可重试错误，重试必须创建新请求并清理旧状态。
- 用户取消：解析为正常取消结果，不触发错误通知。
- 权限拒绝：说明所需权限和恢复方式，不自动重复弹出授权请求。
- 主题或 locale 值未知：回退至暗色主题和英文，并在开发环境输出警告。
- 生产构建失败：CLI 不继续打包，并原样传播构建命令失败信息。

## 9. 测试与验收

### 9.1 UI 包测试

- `createFengYuVuetify()` 注册两个主题、defaults 和图标配置。
- SDK ready 与 `environment` 事件能够切换主题和 locale。
- 文件、目录、通知、权限、超时、取消和重试路径均有组件测试。
- 对话框具备焦点锁定、Esc 关闭与关闭后的焦点恢复。
- 所有复合组件覆盖 loading、empty、error、success 和 disabled 状态。

### 9.2 视觉与无障碍测试

- 默认工作台在亮色、暗色、桌面宽度和窄屏下进行截图回归。
- 键盘可到达所有交互控件，焦点样式清晰。
- 表单字段具备程序化 label，状态变化具备可读文本。
- 状态信息不只依赖颜色，文本和背景对比度达到 WCAG AA。

### 9.3 CLI 测试

- 默认创建项目包含预期依赖、配置和工作台文件。
- `--no-install` 不执行包管理器，同时仍生成完整项目。
- 安装失败保留项目并给出可执行的恢复指引。
- Vue 项目的 dev/build 路径和原生 HTML 兼容路径均被覆盖。
- 前端构建早于 manifest 校验和 `.fyp` 打包。
- 构建失败不产出 `.fyp`；成功包包含 `ui/index.html` 及其静态资源。

验收标准：开发者只需运行默认 create 命令并完成依赖安装，即可启动一个具有已确认
Codex 视觉、明暗主题同步、完整 Vuetify 基础组件和 FengYu SDK 复合组件的插件项目；
该项目可通过 `dev` 热更新，并通过一次 `build` 生成可安装的 `.fyp`。

## 10. 文档与迁移

同步更新中英文文档：

- 插件入门：新的默认 Vue/Vite 目录与首次运行流程。
- SDK/CLI：create、dev、build 的新行为和 `--no-install`。
- UI 微前端：iframe 内的 Vuetify、主题同步和 CSP 边界。
- UI 组件参考：主题初始化、Vuetify defaults、复合组件 API 与示例。
- 迁移指南：现有原生 HTML 插件无需迁移；希望采用新 UI 的插件可逐步引入 Vite、
  `@infinia/plugin-ui` 和 SDK 包。

## 11. 实施边界

该功能可在一个实施计划中完成，但应拆成可独立验证的阶段：UI 包基础设施与主题、复合
组件、CLI 模板与流程、模拟宿主、文档与端到端验证。各阶段必须保持原生 HTML 插件兼容，
避免直到最后才发现 CLI 或打包格式回归。
