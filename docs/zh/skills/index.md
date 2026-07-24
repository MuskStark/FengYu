---
title: 技能 (Skills)
description: 运行时技能是助手按需加载的领域指南（Codex 式渐进式披露）。像插件一样管理——打包为 .fys 包，具备完整的安装/卸载/市场生命周期。
lang: zh
---

# 技能 (Skills)

**技能**（Skill）是助手按需加载的领域指南单元。技能是 FengYu 的第三种扩展面，与
**插件**（隔离 `.fyp` 包中的可调用工具）和 **AI 工具**（进程内的 `@Tool` Bean）并列。三者
刻意保持独立：插件贡献*能力*，工具贡献*模型可调用的函数*，技能贡献*上下文指南*。

> **在哪里管理技能：****插件页**（`/plugins`）是两类扩展的统一管理入口。顶部的
> `插件 | 技能` tab 切换视图；已装项出现在顶部的快捷行，其余在下方卡片网格。没有独立的
> 技能页。单个 **上传** 按钮同时接受 `.fyp` 和 `.fys` 包，按后缀自动路由到对应安装器。
> 这与 Codex 把所有扩展类型归到同一个 Extensions 视图的做法一致。

## 工作原理（渐进式披露）

技能采用 **Codex 式渐进式披露**，因此大型指南文档不会撑爆 token 预算：

1. 每次对话请求时，主机向系统提示词追加一个精简的 **Available Skills** 目录——每个已启用
   的技能一行（仅 `id` + `description`）。
2. 当用户请求匹配某个技能时，模型调用内置的 **`skill` 工具**，传入该技能的 id。
3. 工具返回技能的**完整正文**（markdown 指南），模型据此执行。

N 个技能的每请求成本约为 N 行，与每个正文的长度无关。技能正文只有在真正被加载时才会
消耗 token。

## 像插件一样管理

技能与**插件具有相同的生命周期**：

- 打包为 **`.fys` 包**（zip：`manifest.json` + `SKILL.md`）。
- 安装到 `~/.fengyu/skills/<id>/`——与 `~/.fengyu/plugins/<id>/` 平级的文件系统目录。
- 通过 **`.disabled` 标记文件**启用/停用（状态在重装后保持，不进数据库）。
- 可从**远程目录**通过技能市场浏览和安装。
- 内置官方技能可由 `OfficialSkillSeeder` 在启动时**自动 seed**。

| 来源 | 位置 | 生命周期 |
| --- | --- | --- |
| **内置 (Built-in)** | `classpath:/skills/<id>/SKILL.md`（在 app JAR 内） | 随每次发布打包。**不可卸载或停用**（安装同名技能可覆盖定制）。 |
| **已安装 (Installed)** | `~/.fengyu/skills/<id>/`（来自 `.fys` 包） | 完整的安装/卸载/启用/停用，与插件完全一致。 |

与某个内置技能同名的已安装技能会**覆盖**它——无需 fork JAR 即可定制内置指南。已安装来源优先。

## `.fys` 包格式

`.fys` 包是 zip，根目录必需两个文件：

```
manifest.json   # SkillManifest — id, name, description, version, author, icon, homepage, official
SKILL.md        # frontmatter (name, description) + markdown 正文
assets/...      # 可选附附资源
```

`manifest.json` 是已安装技能的权威元数据；`SKILL.md` 提供指南正文。（内置 classpath 技能没有
manifest——元数据和正文都来自 `SKILL.md` 的 frontmatter。）

```json
{
  "schemaVersion": 1,
  "id": "my-team-conventions",
  "name": "团队规范",
  "description": "编码风格与评审清单。在本仓库写或评审代码时加载。",
  "version": "1.0.0",
  "author": "me",
  "icon": "book-outline",
  "homepage": "https://github.com/me/conventions",
  "official": false
}
```

**id** 必须匹配 `[a-z0-9]+(?:[.-][a-z0-9]+)+`（与插件 id 规则相同）。`official: true` 要求 id 以
`fan.summer.` 开头。版本为 semver，驱动市场的"有可用更新"比较。

## 启用 / 停用

启用状态是**文件系统标记**（`~/.fengyu/skills/<id>/.disabled`），与插件完全一致。没有该文件时，
已安装技能默认启用。可在 **技能** 页（`/skills`）切换，或通过 `PATCH /api/skills/{id}/enabled`。
内置技能没有安装目录，始终启用。

## REST API

技能生命周期镜像 `/api/plugin-market`，端点位于 `/api/skills`：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/api/skills` | 列出全部已发现技能（内置 + 已安装）——不含正文。 |
| `GET` | `/api/skills/{id}` | 单个技能完整详情，含 markdown 正文。 |
| `GET` | `/api/skills/market` | 市场合并视图（`MarketplaceSkill[]`）。 |
| `POST` | `/api/skills/upload` | 安装 `.fys` 包（multipart `file`）。→ 201 |
| `POST` | `/api/skills/upload-native` | 按绝对路径安装 `.fys`（桌面端）。→ 201 |
| `POST` | `/api/skills/{id}/install` | 从已配置目录安装。→ 201 |
| `POST` | `/api/skills/{id}/update` | 从目录更新（复用 install）。 |
| `PATCH` | `/api/skills/{id}/enabled` | 切换 `.disabled` 标记。请求体 `{"enabled": bool}`。 |
| `DELETE` | `/api/skills/{id}` | 卸载。→ 204（内置技能返回 409） |

所有端点都需要 `X-FengYu-Token` 请求头。内置技能在卸载或停用尝试时返回 **409 Conflict**。

## 配置

| 键 | 默认值 | 用途 |
| --- | --- | --- |
| `fengyu.skills.directory` | `${user.home}/.fengyu/skills` | `.fys` 包安装位置。 |
| `fengyu.skills.catalog-url` | `""`（无） | 远程技能市场目录 JSON。为空则仅本地已安装。 |
| `fengyu.skills.official-directory` | `${user.dir}/OfficialSkills/target/packages` | `OfficialSkillSeeder` 启动时扫描。 |

## 编写建议

- 把技能的**触发条件**写在 `description` 里——这是模型在决定加载正文前唯一能看到的一行。
  列出具体的标记词。
- 正文开头写"先加载权威输入再行动"以及"若与仓库冲突，以仓库为准"——技能是指南，不是权威。
- 一个技能只讲一件事。若超过几百行，考虑拆分。

## 权威来源

当本页与仓库冲突时，**以仓库为准**：

- 运行时契约：`FengYu/src/main/java/fan/summer/fengyu/ai/skill/`
- 内置技能：`FengYu/src/main/resources/skills/`
- REST 端点：`FengYu/src/main/java/fan/summer/fengyu/web/controller/SkillController.java`
