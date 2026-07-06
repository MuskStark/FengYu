# AI 辅助插件开发

ZhiFlow 提供了一个 **Agent Skills** 包，把 AI 编程助手变成一位 ZhiFlow 插件专家。它熟悉
真实的 `SwissKitJPlugin` 契约（3.2.0，共 16 个方法）、SPI 加载机制、主题/组件体系，以及那些反复
出现的陷阱——因此它产出的插件**首次就能干净加载、主题渲染正确**，并且遵循
[UI 设计系统](/zh/ui-design/README.md)，而不是自己另起一套外观。

> **跨助手通用：** 该技能遵循开放的 [Agent Skills](https://agentskills.io/) 标准，所以**同一套文件**
> 在 **ZCode**、**Claude Code** 以及任何兼容的助手（如 GitHub Copilot agent 模式）里都能用。
> 你只需要选对安装目录。

## 它能做什么

当你让助手去构建/脚手架/调试一个插件时，该技能会激活，引导它：

- 用**单一类**直接实现 `SwissKitJPlugin`（11 个内置工具都用的单类模式），而非 `*PluginUi` 包装类。
- 用正确的签名和返回值，接好 7 个必需 + 9 个默认方法。
- 配置 `META-INF/services/fan.summer.zhiflow.api.SwissKitJPlugin` 和 `maven-shade-plugin` 的
  `ServicesResourceTransformer`，确保插件真的能被加载。
- 用 `-sk-*` token / `.sk-*` 类来上色（绝不内联十六进制），与
  [设计规范](/zh/ui-design/01-design-system.md) 一致——字号、间距、圆角、动效、无障碍。
- 暴露 AI 工具、跑后台任务、用共享组件（`GlassNotification`、`StepWizard`、`UiUtils`）。

它自带一份可直接复制的 Maven 脚手架（`pom.xml`、插件类、dev 启动器、SPI 文件、i18n bundle），位于
`assets/plugin-template/`。

## 安装

该技能是可移植、自包含的（全用绝对 URL + 内联关键事实，不含任何仓库相对路径），所以**同一套文件**
到处都能用。按你用的助手选对**目录**：

| 助手 | 项目级（提交进 repo，推荐） | 用户级（你的所有项目） |
|---|---|---|
| **ZCode** | `<项目>/.agents/skills/` | `~/.agents/skills/` |
| **Claude Code** | `<项目>/.claude/skills/` | `~/.claude/skills/` |

### 在插件仓库里（推荐用于官方插件仓库和任何插件项目）

把技能提交进仓库，所有贡献者自动获得。**Claude Code**：

```bash
# 在你的插件项目根目录
mkdir -p .claude/skills
svn export https://github.com/MuskStark/ZhiFlow/trunk/.agents/skills/zhiflow-plugin-dev \
  .claude/skills/zhiflow-plugin-dev
git add .claude/skills/zhiflow-plugin-dev
git commit -m "chore: add zhiflow-plugin-dev skill"
```

**ZCode** 同样做法，目录换成 `.agents/skills/`。如果插件仓库同时有两边贡献者，可以**两份都放**
（文件完全相同）——保持同步即可。

### 用户级（第三方开发者——所有项目通用）

装到主目录一次：

```bash
# Claude Code
mkdir -p ~/.claude/skills
svn export https://github.com/MuskStark/ZhiFlow/trunk/.agents/skills/zhiflow-plugin-dev \
  ~/.claude/skills/zhiflow-plugin-dev

# ZCode
mkdir -p ~/.agents/skills
svn export https://github.com/MuskStark/ZhiFlow/trunk/.agents/skills/zhiflow-plugin-dev \
  ~/.agents/skills/zhiflow-plugin-dev
```

完整安装细节（两种助手、发现优先级、同步与更新），见技能自带的
[`INSTALL.md`](https://github.com/MuskStark/ZhiFlow/blob/main/.agents/skills/zhiflow-plugin-dev/INSTALL.md)。

## 使用

装好后，直接在 AI 工具里描述你要什么——技能会按意图自动触发。例如：

- "帮我写一个 ZhiFlow 插件，把文本转成二维码"
- "给我的插件加一个格式化 JSON 的 AI 工具"
- "我的插件能加载，但 UI 显示的是原始 i18n key"
- "搭一个 CSV 排序插件的脚手架"

也可以用 `/zhiflow-plugin-dev <你的请求>` 强制加载。

## 它住在哪里

- **源码 / 权威副本：**
  [`MuskStark/ZhiFlow/.agents/skills/zhiflow-plugin-dev/`](https://github.com/MuskStark/ZhiFlow/tree/main/.agents/skills/zhiflow-plugin-dev)
- 目标版本为 **API 3.2.0** 和当前 `docs/ui-design/` 规范。当 API 或规范发布破坏性变更时，从主仓库
  重新同步即可。

该技能是这套人类可读插件文档的伴侣——它把同样的事实（外加当前 3.2.0 契约）编码成助手可以直接应用
的形式。若要查阅权威的 API 契约和设计规则，请始终以这些文档和
[UI 设计系统](/zh/ui-design/README.md) 为准做交叉核对。
