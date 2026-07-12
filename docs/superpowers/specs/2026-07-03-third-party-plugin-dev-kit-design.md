# 第三方插件开发套件(fengyuj-plugin-kit)设计

- 日期:2026-07-03
- 状态:已通过设计评审,待写实现计划
- 相关:主项目 `docs/plugins/`、`CLAUDE.md`、插件仓库 `SwissKit-Plugin`

## 1. 目标与背景

让**完全独立仓库**里的第三方开发者能用一个 Claude Code skill 引导开发 SwissKitJ
插件,并**严格遵循主项目设计标准**。

现状问题:

1. **交付缺失** —— 现有 `plugin-dev` 是本机个人 skill,外部开发者拿不到。
2. **漂移** —— 现有 skill 是 37KB 硬模板巨物(创建于 2025-06-09),内嵌
   `fengyu.api.version=3.0.0`,而主项目已到 v3.2.0(PluginHost、`.glass-*`→`.sk-*`
   改名、ThemeService、AiTool JSON 契约)。文档本身也漂移:`docs/plugins/entry-point.md`
   里 `getCategory()` 返回 `String`,而 `CLAUDE.md` 里是 `ToolCategory` 枚举。
3. **无强制** —— 插件仓库 `SwissKit-Plugin` 无 `CLAUDE.md`、无 `.claude/`,开发者
   零标准约束。

## 2. 核心决策

| 维度 | 决策 |
|---|---|
| 交付 | 可发布到 marketplace 的 Claude Code plugin,面向只依赖 `SwissKitJ-Api` 的独立空仓库 |
| 执行力 | 指导 + 可复用的自动合规检查(非硬性构建门禁) |
| 新鲜度 | 主项目 `docs/plugins/` 为**单一真相源**,随 API 版本快照进插件;版本号从依赖读取 |

采用**方案 A:一体化 "Plugin-in-a-box"**。否决方案 B(纯在线文档,无确定性校验,
与"自动合规检查"取向不符)与方案 C(Maven enforcer 硬门禁,过重,与"指导+检查"
取向不符)。

## 3. 交付物结构

产物 `fengyuj-plugin-kit` 存放在**主仓库**(与 API 同源演进):

```
SwissKitJ/
└── .claude-plugin/plugin/
    ├── plugin.json                 # 插件清单(name/version/description)
    ├── skills/
    │   └── fengyuj-plugin-dev/
    │       └── SKILL.md            # 精简引导脚手架(§5)
    ├── agents/
    │   └── fengyuj-plugin-reviewer.md   # 语义合规审查 agent(§6b)
    ├── standards/                  # 从 docs/plugins 快照的标准(单一真相源镜像)
    │   ├── VERSION                 # 快照对应的 API 版本戳
    │   ├── entry-point.md  spi.md  pitfalls.md  plugin-host.md
    │   ├── ui.md  i18n.md  database.md  ai-tools.md
    │   └── checklist.md            # 机械+语义规则规范化清单(脚本/agent 共用)
    └── scripts/
        └── validate.sh            # 确定性机械校验脚本(§6a)
```

**发布方式**:该目录发布到一个 marketplace 仓库(或作为 `SwissKit-Plugin` 仓库的
`.claude-plugin/marketplace.json` 条目)。第三方:
`/plugin marketplace add <repo>` + `/plugin install fengyuj-plugin-kit`。

## 4. 组件边界

| 组件 | 做什么 | 依赖 | 不做什么 |
|---|---|---|---|
| `fengyuj-plugin-dev` skill | 问需求 → 拼装项目 → 落地 CLAUDE.md → 触发校验 | `standards/` | 不裁定标准正确性 |
| `standards/` | 设计标准的权威镜像 + 版本戳 | 由 sync 脚本从 `docs/plugins/` 生成 | 不手改(生成物) |
| `validate.sh` | 确定性机械规则校验,退出码非 0 即失败 | `standards/checklist.md` | 不做语义判断,不改码 |
| `fengyuj-plugin-reviewer` agent | 语义规则审查,产出违规清单+建议 | `standards/checklist.md` | 不直接改码 |
| CLAUDE.md 模板 | 落到开发者新仓库,持续约束其 Claude 会话 | —— | —— |

## 5. 脚手架 skill 行为(`fengyuj-plugin-dev`)

替换现有 37KB 硬模板 skill。流程:

1. 问需求:名称 / 插件 ID / 基础包名 / 描述 / 分类 / 图标,以及是否需要
   DB / Excel / AI / 后台任务。
2. **先读 `standards/VERSION` 与 `standards/checklist.md`** 载入当前标准,再生成。
3. 按需拼装:基础骨架必出;DB(H2+MyBatis)/ Excel(fesod-sheet)/ AI(AiTool)/
   后台任务为可选模块。生成的 `pom.xml` 中 `fengyu.api.version` 从
   `standards/VERSION` 填,**不写死**。
4. 落地 CLAUDE.md 模板(§7)到新仓库,并拷入 `validate.sh` 副本。
5. 生成后**自动跑 `validate.sh` 并调用 reviewer agent**,交付前修掉违规项。

代码模板仍内嵌在 skill(脚手架需具体代码),但**规范判断以 `standards/` 为准** ——
skill 负责"怎么拼",标准正确性交给单一真相源。

## 6. 双层合规校验

### 6a. `validate.sh` —— 确定性机械规则(可进 CI)

| 检查 | 规则 |
|---|---|
| SPI 文件 | `META-INF/services/fan.summer.fengyu.api.SwissKitJPlugin` 存在且内容 = 入口类 FQN |
| API scope | `SwissKitJ-Api` 依赖必须 `provided` |
| CSS 迁移 | 源码/资源中无 `.glass-*`(v3.2.0 已改名 `sk-*`) |
| 布局陷阱 | 无 `setPrefWidth(Double.MAX_VALUE)`;无 `maxWidthProperty().bind(widthProperty()…)` |
| 插件 ID | reverse-domain 格式 |
| shade | 配置了 `ServicesResourceTransformer` |
| i18n | `i18n/messages.properties` 存在;`createView`/`init` 中注册了 bundle |
| DevLauncher | 零 JavaFX import |

### 6b. `fengyuj-plugin-reviewer` agent —— 语义规则(脚本查不了)

- AiTool 返回 JSON 符合 `{success, summary, …}` 契约
- 后台任务走 `host.tasks()`(而非裸线程)
- 独立 Alert / Stage 调用 `Themes.applyTo(scene)` 上主题
- H2 DB 路径基于 `user.dir` 且正斜杠
- `createView()` 只构建一次(结果缓存)

产出违规清单 + 修复建议,不直接改码。两层都以 `standards/checklist.md` 为唯一评判依据。

## 7. 开发者新仓库落地物

脚手架在开发者空仓库生成:

- 完整可构建插件项目(骨架 + 所选模块)。
- **`CLAUDE.md`**:精简版设计标准 + "改动后必须跑 `./validate.sh` 与 reviewer agent"
  指令,持续约束开发者自己的 Claude 会话。
- **`validate.sh`** 副本(从插件 `scripts/` 拷入),使校验脱离插件也能在 CI 跑。
- 可选:`.github/workflows/` 挂 `validate.sh` 作 PR check。

"严格"落在三处:生成时(skill 读标准)、提交前(脚本+agent)、持续开发
(仓库内 CLAUDE.md + CI)。

## 8. 新鲜度 / 同步机制(闭环关键)

单一真相源 = 主项目 `docs/plugins/`。新增主仓库脚本 `/sync-plugin-standards`,挂进
`/release` 流程:

1. 拷 `docs/plugins/*.md` → `plugin/standards/`。
2. 从 `SwissKitJ-Api/pom.xml` 读版本 → `standards/VERSION`。
3. 重新生成 `checklist.md`(机械+语义规则规范化清单)。
4. bump `plugin.json` 版本。

**前置修复**(计划第一步):同步前先让 `docs/plugins/` 自身与真实 API 自洽,例如
`entry-point.md` 的 `getCategory()` String/enum 漂移,以及 v3.2.0 的 PluginHost /
`.sk-*` 改名是否已在各文档反映。

## 9. 分期(供实现计划展开)

1. **对齐真相源**:审校 `docs/plugins/*` 与实际 `SwissKitJ-Api` 一致(修漂移)。
2. **搭插件骨架**:`plugin.json` + 目录结构 + 空 `standards/`。
3. **写 sync 脚本**:`/sync-plugin-standards`,首次生成 `standards/` + `VERSION` + `checklist.md`。
4. **写 `validate.sh`**:实现 §6a 全部机械规则 + 自测样例(故意违规的 fixture)。
5. **写 reviewer agent**:§6b 语义规则。
6. **重写 skill**:精简引导 + CLAUDE.md 模板 + 落地 validate + 触发校验。
7. **接发布**:marketplace 条目 + `/release` 挂 sync;文档说明第三方安装步骤。
8. **端到端验证**:空目录跑一遍 skill → 生成插件 → validate 通过 → 装进宿主运行。

## 10. 非目标(YAGNI)

- 不做 Maven enforcer / checkstyle 硬构建门禁(方案 C)。
- 不做在线文档实时拉取(方案 B)。
- 不做从 API JAR 反射自动推导标准(过度工程)。
- 不迁移现有官方插件到本套件(可后续单独进行)。
