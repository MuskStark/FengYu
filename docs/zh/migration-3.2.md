# 迁移到 3.2

本指南涵盖插件作者将插件从 FengYu 3.1.x 升级到 3.2.0 时需要修改
（或可以新使用）的全部内容。

## 破坏性变更：`.glass-*` CSS 类改名为 `.sk-*`

`fengyu-common.css` 中所有公共工具 CSS 类都从 `glass-*` 前缀改名为
`sk-*`（例如 `.glass-dialog` → `.sk-dialog`、`.glass-field` → `.sk-field`、
`.glass-btn-primary` → `.sk-btn-primary`）。
完整改名表见 `CHANGELOG.md` 的 `[3.2.0]` 段。

**需采取的操作：** 在插件源码中搜索 `glass-`，把每个类替换为对应的
`sk-` 类。仍引用 `.glass-*` 的插件将失去样式。

## 已废弃：`GlassNotification` → `SkNotification`

`fan.summer.fengyu.api.component.GlassNotification` 已废弃，现在是新类
`SkNotification` 的薄别名（同样的 `Type` 枚举、同样的 `toast` / `notify` /
`confirm` 方法）。现有代码仍可编译，可在方便时迁移：

```java
// 改之前
GlassNotification.toast(view, GlassNotification.Type.SUCCESS, "Saved");
// 改之后
SkNotification.toast(view, SkNotification.Type.SUCCESS, "Saved");
```

该别名将在 4.0 移除。

## 新增：主窗口使用原生 OS 边框

主窗口切换为 `StageStyle.DECORATED`。如果你的插件打开自己的透明/无边框
`Stage`，对你没有影响——但记得调用
`fan.summer.fengyu.api.theme.Themes.applyTo(scene)` 以便 `-sk-*` token 能解析。

## 新增：`I18n.registerFallbackBundle(...)`

自带消息 bundle 的模块（包括 API 模块自身）可以注册一个 *回退* bundle。
查找顺序是：宿主 bundle → 插件 bundle → 回退 bundle。插件应继续在
`createView()` 中使用 `I18n.registerPluginBundle(...)`；
`registerFallbackBundle` 是给库级默认值用的。

## 新增：`PluginPreviewWindow`（开发工具）

`fan.summer.fengyu.api.preview.PluginPreviewWindow` 在一个独立的类 shell 窗口中
启动你的插件，带主题和语言切换——开发期间无需完整安装 FengYu。

> **稳定性说明：** 预览 API 是开发期工具。它的窗口布局有意模仿（但不共享）
> 真实应用 shell；发布前务必在真实应用中做一次最终检查。

## 新增：PluginHost / PluginSettings / TaskRunner

插件现在可以重写 `init(PluginHost host)` 来接收一个每插件宿主门面：命名空间
隔离的持久化设置（`host.settings()`）、自动 TCCL 且自动后台保活的后台任务
（`host.tasks()`）、无需传 ClassLoader 的 i18n bundle 注册
（`host.i18n().registerBundle(...)`），以及主题和通知访问。现有插件无需修改
——静态入口继续可用。完整指南见 `plugins/plugin-host.md`。

预览窗口（`PluginPreviewWindow`）现在以与真实宿主完全相同的语义加载插件：
child-first 资源 ClassLoader、TCCL 注册、`init(PluginHost)` 注入（设置持久化
到 `~/.fengyu/preview-settings/`）。

## 检查清单

- [ ] 把每个 `.glass-*` 样式类替换为 `.sk-*`
- [ ] （可选）把 `GlassNotification` 调用切换为 `SkNotification`
- [ ] 重新编译以依赖 `FengYu-Api` 3.2.0（仍是 `provided` 作用域）
- [ ] 在**深色和浅色**两个主题下验证你的 UI
- [ ] （可选）改用 `init(PluginHost)` 获取 settings/tasks/i18n，替代静态入口
