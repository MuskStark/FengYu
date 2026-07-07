# 第三方开发者指南：swisskitj-plugin-kit

`swisskitj-plugin-kit` 是一个 Claude Code 插件，为第三方开发者提供脚手架、校验与评审
能力，帮助你写出严格遵守 SwissKitJ 宿主设计标准的插件——无需手动翻阅本目录下的全部
文档，也能保证产出物符合 M1–M12（机械规则）与 S1–S6（语义规则）。

## 安装

在 Claude Code 中依次执行：

```
/plugin marketplace add <repo-url>
/plugin install swisskitj-plugin-kit
```

其中 `<repo-url>` 指向本仓库（包含 `.claude-plugin/marketplace.json` 的那个仓库）。
安装成功后，Claude Code 会加载插件内置的 skill、agent 与脚本，供后续开发全程使用。

## 用法：脚手架新插件

在 Claude Code 会话中触发 `swisskitj-plugin-dev` skill（例如直接说"帮我用
swisskitj-plugin-dev 创建一个新插件"），它会依次询问：

- 插件名称（Name token，如 `StarReport`）
- 插件 ID（reverse-domain，如 `plugin.swisskit.star`）
- 基础包名（Java 包根）
- 简短描述
- 分类（必须是 `DEV` / `TEXT` / `IMAGE` / `NET` / `OTHER` 之一）
- MDI 图标名（不带 `mdi-` 前缀）
- 可选模块：数据库（H2 + MyBatis）、Excel 读写、AI 工具集成、后台任务

skill 会先读取插件包内 `standards/VERSION` 与 `standards/checklist.md`，确保生成的
`pom.xml` 使用与宿主一致的 `swisskit.api.version`，再按需读取 `standards/database.md`、
`standards/ui.md`、`standards/i18n.md`、`standards/plugin-host.md` 等文档来组装项目。
生成的项目落在你自己独立的仓库中，而不是 SwissKitJ 宿主仓库内。

## 校验

### 机械校验（M1–M12）

脚手架完成后，在插件项目根目录运行：

```bash
bash validate.sh .
```

该脚本检查 SPI 文件是否存在、`SwissKitJ-Api` 依赖是否为 `provided`、是否残留
`.glass-*` CSS、是否有 `setPrefWidth(Double.MAX_VALUE)` 等布局反模式、`getId()` 是否
符合 reverse-domain 格式、`ServicesResourceTransformer` 是否配置、i18n 资源是否存在
并注册等共 12 项机械规则。任意一项 `FAIL` 都会让脚本以非零状态码退出。

### 语义评审（S1–S6）

机械校验通过后，调用 `swisskitj-plugin-reviewer` agent 做语义层面的评审：AI 工具的
JSON 返回契约、后台任务是否经 `host.tasks()` 提交、自建 `Alert`/`Stage` 是否套用了
主题、H2 路径是否基于 `user.dir` 且用正斜杠、`createView()` 是否被宿主正确缓存和
复用、是否使用了 `-sk-*` / `.sk-*` token 而非硬编码颜色。这部分规则依赖判断力，因此
由 agent 而非纯脚本执行。

## CI 集成

在你的插件仓库里加一个 GitHub Actions workflow，在 push / PR 时自动跑机械校验，
不合规的插件会直接让 CI 失败：

```yaml
name: Validate SwissKitJ Plugin

on:
  push:
  pull_request:

jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run mechanical compliance checks (M1-M12)
        run: bash validate.sh .
```

建议将语义评审（`swisskitj-plugin-reviewer` agent）作为发 PR 前的人工/交互步骤保留在
Claude Code 会话中，CI 只强制跑机械规则——语义规则需要读代码上下文做判断，不适合在
无人值守的 CI 环境里跑。

## 与宿主标准文档的关系

`swisskitj-plugin-kit` 内置的 `standards/` 目录是本 `docs/plugins/` 目录中若干篇文档
（入口点、SPI、常见陷阱、PluginHost、UI、i18n、数据库）的快照，随宿主每次发布通过
`scripts/sync-plugin-standards.sh` 重新生成并按 `SwissKitJ-Api` 版本号打上戳。如果你
发现插件内标准与本目录文档不一致，以最新发布版本对应的插件包为准；若怀疑快照过期，
可以直接对照本目录下的原始文档确认。
