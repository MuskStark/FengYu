---
title: 设计系统
description: Infinia 4.0.0 基于 Vuetify 3 采用 Material Design 3，并通过宿主桥梁同步隔离的插件 UI。
lang: zh-CN
---

# 设计系统

Infinia 4.0.0 的整套 UI——无论是宿主外壳还是插件微前端——都基于 **Vuetify 3** 和 **Material Design 3** 基线渲染。4.0.0 的桌面产品中没有 JavaFX 层；Electron 外壳所承载的，正是与浏览器中相同的 Vue UI。

## Material Design 3 基线

调色板采用 Google 的 MD3 默认值（主色 `#6750A4`）。颜色、elevation、形状和排版都遵循 Material 3 token 系统，并通过 Vuetify 3 的主题引擎暴露出来。

- 开箱即用地支持**浅色与深色主题**。
- MD3 调色板定义在 `frontend/src/plugins/md3-themes.ts` 中。

## 主题 store

主题由一个单一的 Pinia store——`useThemeStore` 单例驱动。组件从这个 store 读取主题状态，而不是直接操作 Vuetify，因此深色/浅色模式与调色板切换能够一致地传播。

## 插件微前端使用 UI kit

插件运行在独立的 iframe JavaScript 运行域中，因此无法复用宿主的 Vue 或 Vuetify 实例。它安装 `@infinia/plugin-ui`，在插件内创建带有 FengYu 组件、默认配置和主题的 Vuetify 实例：

```ts
const vuetify = createFengYuVuetify()
app.use(vuetify)
await bindFengYuEnvironment(vuetify, fengyu)
```

`bindFengYuEnvironment` 在 `host.ready` 时取得初始语言与主题，再应用后续 `environment` 事件。这样既保留隔离边界，也让视觉主题保持同步。

## 小结

| 层 | 是什么 |
| --- | --- |
| 组件库 | Vuetify 3 |
| 设计语言 | Material Design 3（Google 默认调色板） |
| 调色板来源 | `frontend/src/plugins/md3-themes.ts` |
| 主题运行时 | Pinia `useThemeStore` 单例 |
| 插件主题 | 插件本地的 `@infinia/plugin-ui` 实例绑定 SDK 环境事件 |
| 主题 | 浅色与深色 |

## 下一步

- [功能特性](/zh/features)——MD3 UI 渲染了什么。
- [快速开始](/zh/quickstart)——运行前端开发服务器以实时查看效果。
