# 插件 UI 与宿主一致性修复设计

**日期：** 2026-07-23  
**状态：** 已确认修复方向，技能更新范围待复核

## 1. 背景与目标

官方 CLI 模板使用 `mdi-*` 字符串作为 Vuetify 图标输入，但 `FyPluginShell` 和
`FyEmptyState` 当前以 `/^m/i` 判断 SVG path，导致 `mdi-*` 被错误传给 `<path d>`。
同时，`@infinia/plugin-ui` 声称复制宿主主题色值，实际色板已经与
`frontend/src/plugins/md3-themes.ts` 发生漂移。

本次修复保证：

- CLI 当前生成的 `mdi-*` 图标写法能够由官方 UI 包正确渲染。
- `@mdi/js` SVG path 继续通过 `FyIcon` 正确渲染。
- 插件与宿主使用相同的基础主题色值和语义令牌。
- 自动化测试直接覆盖 CLI 的默认写法，并阻止主题再次静默漂移。

## 2. 修复方案

### 2.1 图标分类

在 `plugin-ui` 内提供单一的图标分类函数，供 `FyPluginShell` 和 `FyEmptyState`
共同使用：

- 以 `mdi-` 开头的字符串是 Vuetify MDI 图标名，交给 `v-icon`。
- 以 SVG move 命令 `M` 或 `m` 开头、且不是 `mdi-` 的字符串是 SVG path，
  交给 `FyIcon`。
- 空值不渲染图标。

不修改 CLI 模板，也不要求插件改用 `@mdi/js`，因为 `mdi-*` 是官方 Vuetify
配置和官方 CLI 已公开支持的合法输入。

### 2.2 主题一致性

宿主 `frontend/src/plugins/md3-themes.ts` 是基础色板的权威来源。将
`plugin-ui/vue/src/theme.ts` 的共享色值调整为与宿主一致。

插件组件已经使用、而宿主当前缺少的语义令牌（`surface-container-low` 和 warning
系列）补入宿主主题；这只扩展宿主可用令牌，不改变现有宿主组件使用的颜色。两边的
light/dark `colors` 与 `variables` 对象最终保持值级一致。

本次不新建共享 npm 包或跨包运行时依赖。`plugin-ui` 仍可独立发布；仓库测试负责验证
有意复制的主题定义没有漂移。

## 3. 测试策略

采用测试优先：

1. 在 `plugin-ui` 组件测试中使用 CLI 的实际输入 `mdi-home-outline`，断言它渲染为
   Vuetify MDI 图标，而不是 `FyIcon` SVG path；该测试在修复前必须失败。
2. 保留并执行现有 `@mdi/js` path 测试，证明兼容路径未回归。
3. 为 `FyEmptyState` 增加相同的 `mdi-*` 契约测试。
4. 增加主题契约测试，比较宿主和插件的 light/dark `colors`、`variables`，该测试在
   色值对齐前必须失败。
5. 修复后运行 `plugin-ui` 完整单元测试、类型检查、构建及 `git diff --check`。

## 4. 技能更新

同步更新实际负责该领域的两个仓库技能：

- `.agents/skills/fengyu-plugin-dev/SKILL.md`：增加插件 UI 一致性契约。明确官方 CLI
  模板是受支持输入的契约样本；`plugin-sdk`/`plugin-ui` 必须正确渲染模板写法；插件
  应复用 `@infinia/plugin-ui`，不能通过改写插件业务代码规避工具链缺陷；修改主题、
  图标或组件时必须对照宿主并运行契约测试。
- `.agents/skills/plugin-tooling-release/SKILL.md`：把 CLI 模板到 UI 包的兼容性、宿主/
  插件主题一致性测试和 `plugin-ui` 视觉测试列为发布门禁，防止发布彼此不兼容的 CLI、
  SDK 与 UI 包。

现有技能在本次对话中未阻止把官方 CLI 的 `mdi-*` 写法误判为插件问题，这构成技能修改
前的实际失败样本。修改后的技能必须明确要求先检查模板、UI 渲染分支与宿主权威主题，
再决定修复位置。

## 5. 实施边界

- 不修改官方插件业务 UI。
- 不修改 SDK 消息协议、manifest 或 iframe 隔离模型。
- 不重构宿主 Vuetify 初始化流程。
- 不处理与本问题无关的组件视觉调整。
- 不改写工作区中现有的 `plugin-dev` 和文档变更。

## 6. 验收标准

- CLI 模板中的 `mdi-home-outline` 在 `FyPluginShell` 中通过 `v-icon` 渲染。
- `mdi-*` 不会出现在 `<path d>` 中。
- `@mdi/js` path 仍通过内联 SVG 渲染。
- 插件与宿主基础主题对象值级一致。
- 插件开发与工具链发布技能都包含可执行的 UI 一致性检查和发布门禁。
- 相关测试、类型检查和构建全部通过。
