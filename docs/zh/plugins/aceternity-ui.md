---
title: Aceternity UI 组件
description: "用 fengyu add 把 Aceternity UI 组件拉取进插件——直连官方 registry、带来源戳、遵守 Aceternity 自身的许可。"
lang: zh-CN
---

# Aceternity UI 组件

Infinia 套件（`@infinia/plugin-ui`）只随包发布 FengYu 自有的部分——外壳、
顶栏/状态条、页面、状态组件、选择器、Select/Combobox、步骤向导与引导钩子。
**Aceternity UI 组件有意不包含在包里**：
[Aceternity 许可证](https://ui.aceternity.com/licence) 允许在成品中使用其组件，
但禁止再分发其源文件——而一个公开发布的组件库恰好就是那种再分发。

正确做法是按项目拉取你需要的组件，直连官方 registry：

```bash
fengyu add sidebar          # 或：fengyu add aceternity/sidebar
```

## 命令做了什么

- 在**你的机器上、命令执行时**请求
  `https://ui.aceternity.com/registry/<name>.json`——CLI 刻意不内置、不镜像、
  不缓存任何文件，也没有离线回退。拉取失败时会打印 registry URL，你可以从
  [ui.aceternity.com](https://ui.aceternity.com) 手动拷贝组件。
- 把组件文件写入 `ui-src/src/aceternity/`（`--ui-only` 脚手架为根级
  `src/aceternity/`），多文件组件保留其内部目录结构。
- 每个写入文件都盖上来源戳：源 URL、© Aceternity UI、拉取日期与许可提醒。
- 为 FengYu 脚手架适配 shadcn 风格的导入——`@/lib/utils` 改写为
  `@infinia/plugin-ui`（它导出同一个 `cn`），`@/components/*` 改写为
  `src/aceternity/` 内的相对路径。传 `--raw-imports` 可保留上游源码原样。
- 用 `npm install --save` 在 UI 工程内安装 registry 载荷声明的依赖（如
  `motion`、`@tabler/icons-react`），已存在的依赖会跳过；`--no-install`
  只打印命令不执行。
- `registryDependencies` 依同样方式、从同一上游 registry 递归拉取。

每个项目首次运行会要求确认 Aceternity 许可证（`--yes` 用于非交互确认；
确认记录保存在 `ui-src/.fengyu-aceternity-ack`）。已有文件不会被覆盖，
除非传 `--force`。

## 许可边界

规则很短：

- ✅ **在插件内自由使用和修改这些组件**——插件是成品，成品可以被分发
  （包括通过 Infinia 商店发布）。
- ❌ **永远不要再分发其源文件。**具体来说：不要把 `src/aceternity/` 下拉取
  的文件提交到**公开**仓库，不要把它们再发布成组件库的一部分，也不要把它们
  拷进别人的项目。私有仓库和构建产物（`.fyp`）没有问题。
- Pro（付费）组件不在公开 registry 上——请在 ui.aceternity.com 购买后自行
  放入 `src/aceternity/`；仍建议按上面的来源戳惯例标注。

FengYu 自家的第一方插件遵循同一模式：组件放在私有的商店仓库里，只以构建
产物形式发布。
