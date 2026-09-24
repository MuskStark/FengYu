---
title: 设计系统
description: Infinia 宿主外壳使用 Zai 设计系统（zai.css 令牌 + Tailwind 4）；插件微前端保留自己的 Vuetify 3 / MD3 实例，通过宿主桥梁同步。
lang: zh-CN
---

# 设计系统

Infinia 的宿主外壳使用 **Zai 设计系统**渲染——令牌调色板定义在 `frontend/src/styles/zai.css`，通过 **Tailwind CSS 4** 工具类消费。插件微前端在其沙箱化 iframe 内继续使用 **Vuetify 3 + Material Design 3** 渲染；宿主桥梁让它们的语言与深色/浅色主题和外壳保持一致。桌面产品中没有 JavaFX 层；Electron 外壳所承载的，正是与浏览器中相同的 React UI。

## Zai 令牌（`zai.css`）

单个 `frontend/src/styles/zai.css` 里有两层：

- `@theme` 块：`text-ui-*` 字阶（UI 字号由 `--ui-font-size` 统一掌管）与 Tailwind 工具类解析所依据的语义化 `--color-*` 默认值。
- `.theme-zai-light` / `.theme-zai-dark` 调色板块，同时把相同的值桥接进遗留的 `--v-theme-*` 与 `--cx-*` 令牌空间，让旧样式表无需改动即可解析为 Zai 颜色。

## 主题类

主题状态以 `<html>` 上的类表达：`dark`（Tailwind 变体）、`theme-zai-dark` / `theme-zai-light`，以及遗留的 `v-theme--*` 类。`index.html` 中的内联防闪烁脚本会在首帧绘制前应用持久化主题；之后由 `stores/settings.ts` 应用并持久化变更。

## 插件微前端使用 UI kit

插件运行在独立的 iframe JavaScript 运行域中，因此无法复用宿主的 React 树或令牌。它安装 `@infinia/plugin-ui`，在插件内创建带有 FengYu 组件、默认配置和主题的 Vuetify 实例：

```ts
const vuetify = createFengYuVuetify()
app.use(vuetify)
await bindFengYuEnvironment(vuetify, fengyu)
```

`bindFengYuEnvironment` 在 `host.ready` 时取得初始语言与主题，再应用后续 `environment` 事件。这样既保留隔离边界，也让可见的深色/浅色模式保持同步。

## 小结

| 层 | 是什么 |
| --- | --- |
| 外壳设计语言 | Zai（源自 ZCode 的令牌体系） |
| 外壳样式 | Tailwind CSS 4 + `frontend/src/styles/zai.css` |
| 主题类 | `<html>` 上的 `dark` + `theme-zai-*` + 遗留 `v-theme--*` |
| 主题运行时 | `stores/settings.ts` + `index.html` 防闪烁脚本 |
| 插件主题 | 插件本地的 `@infinia/plugin-ui`（Vuetify 3 / MD3）实例绑定 SDK 环境事件 |
| 主题 | 浅色与深色 |

## 下一步

- [功能特性](/zh/features)——Zai UI 渲染了什么。
- [快速开始](/zh/quickstart)——运行前端开发服务器以实时查看效果。
