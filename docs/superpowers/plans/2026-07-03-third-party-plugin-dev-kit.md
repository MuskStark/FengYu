# 第三方插件开发套件(swisskitj-plugin-kit)实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让独立仓库的第三方开发者通过一个可分发的 Claude Code 插件(skill + 标准快照 + 校验脚本 + 审查 agent)开发出严格符合 SwissKitJ 设计标准的插件。

**Architecture:** 在主仓库 `SwissKitJ/.claude-plugin/plugin/` 内构建一个 Claude Code 插件;设计标准以主项目 `docs/plugins/` 为单一真相源,经 `/sync-plugin-standards` 命令快照进插件 `standards/` 目录并盖 API 版本戳;合规靠 `validate.sh`(确定性机械规则)+ `swisskitj-plugin-reviewer` agent(语义规则)双层保证;脚手架 skill 生成项目时读标准、落地 CLAUDE.md、拷入 validate.sh 并触发校验。

**Tech Stack:** Markdown(skill/agent/docs)、POSIX Bash(validate.sh + sync 脚本)、JSON(plugin.json / marketplace.json)、参照 Java/Maven 插件规范(不在本计划内写 Java 代码,只写模板文本)。

## Global Constraints

- 单一真相源 = `docs/plugins/*.md`;`standards/` 为生成物,**禁止手改**。
- 生成的插件 `pom.xml` 中 `swisskit.api.version` 必须从 `standards/VERSION` 取值,**不得写死**。
- 真实 API 事实(以 `SwissKitJ-Api` 源码为准):`getCategory()` 返回枚举 `ToolCategory`(值:`DEV/TEXT/IMAGE/NET/OTHER`);`getType()` 返回 `ToolType`;`getIconStyle()` 返回 `IconStyle`(`BLUE/PURPLE/TEAL/AMBER/RED/PINK/GRAY`)。当前 API 版本 = `3.2.0`。
- CSS token 前缀为 `-sk-*`;工具类为 `.sk-*`(旧 `.glass-*` 在 v3.2.0 已废弃)。
- `validate.sh` 必须是可移植 POSIX bash,只用 `grep`/`unzip`/`find` 等通用工具,退出码非 0 表示不合规;不得依赖主仓库存在。
- 参考(已知合规)插件:`SwissKit-Plugin/SwissKitJ-Plugin-KeepAwake`(pom、DevLauncher、SPI、entry class 均为当前标准范例)。
- 提交遵循 emoji conventional commits(`✨`/`🐛`/`♻️`/`📝`/`⬆️`)。

---

## File Structure

**主仓库内新增/修改:**

- Modify: `docs/plugins/entry-point.md` — 修正 `getCategory()` 等漂移
- Create: `.claude-plugin/plugin/plugin.json` — 插件清单
- Create: `.claude-plugin/plugin/skills/swisskitj-plugin-dev/SKILL.md` — 脚手架 skill
- Create: `.claude-plugin/plugin/agents/swisskitj-plugin-reviewer.md` — 语义审查 agent
- Create: `.claude-plugin/plugin/scripts/validate.sh` — 机械校验脚本(权威副本)
- Create: `.claude-plugin/plugin/standards/` — 生成物(VERSION + 快照 md + checklist.md)
- Create: `.claude-plugin/plugin/templates/CLAUDE.md.tmpl` — 落到开发者仓库的 CLAUDE.md 模板
- Create: `scripts/sync-plugin-standards.sh` — 同步脚本(sync 命令的实现)
- Create: `.claude/commands/sync-plugin-standards.md` — `/sync-plugin-standards` 命令入口
- Create: `.claude-plugin/marketplace.json`(或复用 SwissKit-Plugin 的)— 分发条目
- Create: `test/plugin-kit/fixtures/good-plugin/` 与 `test/plugin-kit/fixtures/bad-plugin/` — validate.sh 测试夹具
- Create: `test/plugin-kit/run-validate-tests.sh` — validate.sh 的测试运行器
- Modify: `/release` 命令文件(路径待定位)— 挂 sync 步骤
- Delete(收尾):`.claude/commands/plugin-dev.md` — 被新 skill 取代

---

## Task 1: 对齐单一真相源(修 docs/plugins 漂移)

先让标准文档与真实 API 自洽,否则快照会把错误标准分发给第三方。

**Files:**
- Modify: `docs/plugins/entry-point.md`
- Reference(只读,判定真相):`SwissKitJ-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java`、`ToolCategory.java`、`ToolType.java`、`IconStyle.java`

**Interfaces:**
- Produces:自洽的 `docs/plugins/*.md`,供 Task 3 的 sync 快照。

- [ ] **Step 1: 写失败断言(grep 探针)**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ
grep -n "public String getCategory" docs/plugins/entry-point.md
```
Expected: 命中 —— 证明存在 `String getCategory()` 漂移(应为 `ToolCategory`)。

- [ ] **Step 2: 全量核对 API 事实**

对照真实接口逐条核查 `entry-point.md` 的方法签名表与代码块,列出所有与源码不符处。至少包含:
- `getCategory()` 返回 `String "OTHER"` → 应为 `ToolCategory getCategory()` 返回 `ToolCategory.OTHER`(需 `import fan.summer.api.ToolCategory;`)。
- 方法说明表里 `getCategory()` 的“分类:text/image/...”应改为枚举值 `DEV/TEXT/IMAGE/NET/OTHER`。
- `getType()` 说明“返回 `"builtin"`”应改为返回 `ToolType`(值 `PLUGIN/BUILTIN`,默认 `PLUGIN`)。
- 补充 `init(PluginHost)`(v3.2.0+)与推荐用 `host.i18n().registerBundle(...)` 的说明(与 `plugin-host.md` 一致)。

- [ ] **Step 3: 修正 entry-point.md**

把代码块改成与 `KeepAwakePlugin.java` 一致的真实写法:
```java
import fan.summer.api.IconStyle;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import fan.summer.api.i18n.I18n;
import javafx.scene.Node;

public class {{Name}}Plugin implements SwissKitJPlugin {
    @Override public String getId()            { return "{{plugin-id}}"; }
    @Override public String getName()          { return "{{display-name}}"; }
    @Override public String getDescription()   { return "{{description}}"; }
    @Override public ToolCategory getCategory(){ return ToolCategory.OTHER; }
    @Override public String getVersion()       { return "1.0.0"; }
    @Override public String getMdiIcon()       { return "{{icon-name}}"; }
    @Override public IconStyle getIconStyle()  { return IconStyle.BLUE; }
    @Override public Node createView() {
        I18n.registerPluginBundle("i18n.messages", getClass().getClassLoader());
        return new {{Name}}PluginUi().getView();
    }
}
```
并同步修正方法说明表。

- [ ] **Step 4: 验证漂移已消除**

Run:
```bash
grep -n "String getCategory" docs/plugins/entry-point.md || echo "OK: no String getCategory"
grep -n "ToolCategory getCategory" docs/plugins/entry-point.md
```
Expected: 第一条打印 `OK: no String getCategory`;第二条命中。

- [ ] **Step 5: Commit**

```bash
git add docs/plugins/entry-point.md
git commit -m "📝 docs(plugins): align entry-point with real API (ToolCategory/ToolType/init)"
```

---

## Task 2: 搭建插件骨架 + plugin.json

建立可被 Claude Code 识别的插件目录与清单。

**Files:**
- Create: `.claude-plugin/plugin/plugin.json`
- Create(占位空目录用 `.gitkeep`):`.claude-plugin/plugin/{skills,agents,scripts,standards,templates}/`

**Interfaces:**
- Produces:`plugin.json`(字段 `name: "swisskitj-plugin-kit"`),供 marketplace 引用与后续组件挂载。

- [ ] **Step 1: 写 plugin.json**

Create `.claude-plugin/plugin/plugin.json`:
```json
{
  "name": "swisskitj-plugin-kit",
  "version": "3.2.0",
  "description": "Scaffold and validate SwissKitJ plugins that strictly follow the host's design standards.",
  "author": { "name": "SwissKitJ" }
}
```
> version 初值与当前 API 一致;后续由 sync 脚本 bump。

- [ ] **Step 2: 建目录骨架**

```bash
cd /Users/phoebej/Develop/Java/SwissKitJ/.claude-plugin/plugin
for d in skills/swisskitj-plugin-dev agents scripts standards templates; do mkdir -p "$d"; done
find . -type d -empty -exec touch {}/.gitkeep \;
```

- [ ] **Step 3: 验证结构**

Run: `find .claude-plugin/plugin -type f | sort`
Expected: 至少含 `plugin.json` 和各 `.gitkeep`。

- [ ] **Step 4: Commit**

```bash
git add .claude-plugin/plugin
git commit -m "✨ feat(plugin-kit): scaffold swisskitj-plugin-kit skeleton + manifest"
```

---

## Task 3: sync 脚本 + 首次生成 standards/

把 `docs/plugins/` 快照进插件,盖版本戳,生成 checklist.md。

**Files:**
- Create: `scripts/sync-plugin-standards.sh`
- Create: `.claude/commands/sync-plugin-standards.md`
- Generates: `.claude-plugin/plugin/standards/{VERSION, *.md, checklist.md}`

**Interfaces:**
- Consumes:`docs/plugins/*.md`、`SwissKitJ-Api/pom.xml`(读 `<version>`)。
- Produces:`standards/VERSION`(纯版本号一行)、`standards/checklist.md`(校验规则规范化清单),供 Task 4/5/6 引用。

- [ ] **Step 1: 写 sync 脚本**

Create `scripts/sync-plugin-standards.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/docs/plugins"
DST="$ROOT/.claude-plugin/plugin/standards"
mkdir -p "$DST"

# 1) 快照标准文档
cp "$SRC"/entry-point.md "$SRC"/spi.md "$SRC"/pitfalls.md \
   "$SRC"/plugin-host.md "$SRC"/ui.md "$SRC"/i18n.md "$SRC"/database.md "$DST"/
# ai-tools 文档若存在则一并快照(AiTool 契约来自 CLAUDE.md,若无独立 md 则跳过)
[ -f "$SRC/ai-tools.md" ] && cp "$SRC/ai-tools.md" "$DST"/ || true

# 2) 版本戳:从 API pom 读 <version>
VER="$(grep -m1 -oE '<version>[^<]+</version>' "$ROOT/SwissKitJ-Api/pom.xml" | sed -E 's/<[^>]+>//g')"
printf '%s\n' "$VER" > "$DST/VERSION"

# 3) 更新 plugin.json version 与 pom 一致
PJ="$ROOT/.claude-plugin/plugin/plugin.json"
sed -i.bak -E "s/(\"version\"[[:space:]]*:[[:space:]]*\")[^\"]+(\")/\1$VER\2/" "$PJ" && rm -f "$PJ.bak"

echo "Synced standards @ API $VER"
```
> `checklist.md` 在 Step 3 手写一次并纳入版本控制(它是规则规范,不是 docs 的机械拷贝);sync 只快照 docs 与版本戳。

- [ ] **Step 2: 写 checklist.md(校验规则单一清单)**

Create `.claude-plugin/plugin/standards/checklist.md`,分两节。**机械规则(validate.sh 判定)**:
```
M1 SPI 文件存在:src/main/resources/META-INF/services/fan.summer.api.SwissKitJPlugin
M2 SPI 内容 = 入口类 FQN,且该 .java 存在
M3 SwissKitJ-Api 依赖 scope 为 provided
M4 无 .glass- CSS 引用(源码+资源)
M5 无 setPrefWidth(Double.MAX_VALUE)
M6 无 maxWidthProperty().bind(widthProperty() 循环绑定
M7 插件 getId() 为 reverse-domain(至少两段,点分)
M8 pom 配了 ServicesResourceTransformer
M9 i18n/messages.properties 存在
M10 createView 或 init 中注册了 i18n bundle(registerPluginBundle 或 host.i18n().registerBundle)
M11 DevLauncher.java 零 javafx import
M12 pom 中 swisskit.api.version 属性存在(值由使用者维护)
```
**语义规则(reviewer agent 判定)**:
```
S1 AiTool 返回 JSON 符合 {success, summary, ...}(error 用 {success:false,error})
S2 后台任务经 host.tasks() 提交,而非裸 new Thread
S3 自建 Alert/Stage 调用 Themes.applyTo(scene) 上主题
S4 H2 路径基于 user.dir 且用正斜杠
S5 createView() 只构建一次(结果缓存,勿每次 new)
S6 使用 -sk-* / .sk-* token,不硬编码颜色规避主题
```

- [ ] **Step 3: 写命令入口**

Create `.claude/commands/sync-plugin-standards.md`:
```markdown
Run `bash scripts/sync-plugin-standards.sh` to snapshot docs/plugins into the
plugin-kit standards/ and stamp the current SwissKitJ-Api version. After it
runs, review the diff under `.claude-plugin/plugin/standards/` and commit.
Note: `standards/checklist.md` is hand-maintained — update it only when
validation rules change, not on every sync.
```

- [ ] **Step 4: 运行首次同步**

Run:
```bash
chmod +x scripts/sync-plugin-standards.sh
bash scripts/sync-plugin-standards.sh
cat .claude-plugin/plugin/standards/VERSION
ls .claude-plugin/plugin/standards/
```
Expected: `VERSION` 内容为 `3.2.0`;目录含 entry-point.md、pitfalls.md、checklist.md 等;`plugin.json` version = 3.2.0。

- [ ] **Step 5: Commit**

```bash
git add scripts/sync-plugin-standards.sh .claude/commands/sync-plugin-standards.md .claude-plugin/plugin/standards .claude-plugin/plugin/plugin.json
git commit -m "✨ feat(plugin-kit): sync-plugin-standards script + first standards snapshot"
```

---

## Task 4: validate.sh 机械校验 + 测试夹具

先建“坏插件”夹具让脚本失败、“好插件”夹具让脚本通过,再实现脚本。

**Files:**
- Create: `.claude-plugin/plugin/scripts/validate.sh`
- Create: `test/plugin-kit/fixtures/good-plugin/…`(用 KeepAwake 精简副本)
- Create: `test/plugin-kit/fixtures/bad-plugin/…`(故意违反 M1/M3/M4/M5)
- Create: `test/plugin-kit/run-validate-tests.sh`

**Interfaces:**
- Consumes:`standards/checklist.md` 的 M1–M12。
- Produces:`validate.sh <plugin-dir>` → 退出码 0=合规,非 0=违规并逐条打印 `FAIL Mn: <说明>`。

- [ ] **Step 1: 造夹具**

good-plugin:从 `SwissKit-Plugin/SwissKitJ-Plugin-KeepAwake` 拷 `pom.xml`、`src/main/java/**`、`src/main/resources/**`(去掉 target)。
bad-plugin:在 good 副本基础上制造违规——删除 SPI 文件(M1)、把 API 依赖 scope 改为 `compile`(M3)、在某 UI java 里加一行 `label.getStyleClass().add("glass-card");`(M4)与 `node.setPrefWidth(Double.MAX_VALUE);`(M5)。

- [ ] **Step 2: 写测试运行器(先失败)**

Create `test/plugin-kit/run-validate-tests.sh`:
```bash
#!/usr/bin/env bash
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
V="$DIR/../../.claude-plugin/plugin/scripts/validate.sh"
fail=0
bash "$V" "$DIR/fixtures/good-plugin"; rc=$?
[ "$rc" -eq 0 ] || { echo "EXPECTED good=0 got $rc"; fail=1; }
bash "$V" "$DIR/fixtures/bad-plugin"; rc=$?
[ "$rc" -ne 0 ] || { echo "EXPECTED bad!=0 got 0"; fail=1; }
[ "$fail" -eq 0 ] && echo "ALL PASS" || { echo "TESTS FAILED"; exit 1; }
```

Run: `bash test/plugin-kit/run-validate-tests.sh`
Expected: FAIL(`validate.sh` 尚不存在)。

- [ ] **Step 3: 实现 validate.sh**

Create `.claude-plugin/plugin/scripts/validate.sh`,对入参目录逐条实现 M1–M12。骨架:
```bash
#!/usr/bin/env bash
set -uo pipefail
P="${1:?usage: validate.sh <plugin-dir>}"
SRC="$P/src/main/java"; RES="$P/src/main/resources"; POM="$P/pom.xml"
rc=0
fail(){ echo "FAIL $1: $2"; rc=1; }
ok(){ :; }

SPI="$RES/META-INF/services/fan.summer.api.SwissKitJPlugin"
[ -f "$SPI" ] || fail M1 "missing SPI file"
if [ -f "$SPI" ]; then
  fqn="$(grep -m1 -v '^\s*$' "$SPI" | tr -d '\r')"
  path="$SRC/$(echo "$fqn" | tr '.' '/').java"
  [ -f "$path" ] || fail M2 "SPI class not found: $fqn"
fi
grep -qsE '<artifactId>SwissKitJ-Api</artifactId>' "$POM" \
  && grep -qsE '<scope>provided</scope>' "$POM" || fail M3 "SwissKitJ-Api must be provided"
grep -rqs 'glass-' "$SRC" "$RES" 2>/dev/null && fail M4 "'.glass-*' found; use .sk-*"
grep -rqs 'setPrefWidth(Double.MAX_VALUE)' "$SRC" 2>/dev/null && fail M5 "setPrefWidth(MAX_VALUE) banned"
grep -rqsE 'maxWidthProperty\(\)\.bind\(\s*widthProperty\(\)' "$SRC" 2>/dev/null && fail M6 "self width bind banned"
if [ -f "$SPI" ]; then
  id_line="$(grep -rhoE 'getId\(\)\s*\{\s*return\s*"[^"]+"' "$SRC" | head -1)"
  echo "$id_line" | grep -qE '"[a-zA-Z0-9_-]+(\.[a-zA-Z0-9_-]+)+"' || fail M7 "getId not reverse-domain"
fi
grep -qs 'ServicesResourceTransformer' "$POM" || fail M8 "shade ServicesResourceTransformer missing"
[ -f "$RES/i18n/messages.properties" ] || fail M9 "i18n/messages.properties missing"
grep -rqsE 'registerPluginBundle|i18n\(\)\.registerBundle' "$SRC" 2>/dev/null || fail M10 "i18n bundle not registered"
dl="$(grep -rl 'class DevLauncher' "$SRC" 2>/dev/null | head -1)"
[ -n "$dl" ] && grep -qs 'import javafx' "$dl" && fail M11 "DevLauncher must have zero javafx imports"
grep -qs 'swisskit.api.version' "$POM" || fail M12 "swisskit.api.version property missing"

[ "$rc" -eq 0 ] && echo "VALIDATE OK: $P"
exit $rc
```

- [ ] **Step 4: 跑测试到通过**

Run: `chmod +x .claude-plugin/plugin/scripts/validate.sh && bash test/plugin-kit/run-validate-tests.sh`
Expected: `ALL PASS`(good=0,bad≠0 且打印对应 `FAIL M1/M3/M4/M5`)。

- [ ] **Step 5: Commit**

```bash
git add .claude-plugin/plugin/scripts/validate.sh test/plugin-kit
git commit -m "✨ feat(plugin-kit): validate.sh mechanical checks + good/bad fixtures"
```

---

## Task 5: reviewer agent(语义校验)

**Files:**
- Create: `.claude-plugin/plugin/agents/swisskitj-plugin-reviewer.md`

**Interfaces:**
- Consumes:`standards/checklist.md` 的 S1–S6 + 快照 md。
- Produces:一个 subagent,输入插件目录,输出违规清单(每条:规则号、文件:行、问题、修复建议),不改码。

- [ ] **Step 1: 写 agent frontmatter + 指令**

Create 文件,frontmatter:
```markdown
---
name: swisskitj-plugin-reviewer
description: Reviews a SwissKitJ plugin project against the host's semantic design standards (S1–S6). Use after scaffolding or before packaging a plugin.
tools: Read, Grep, Glob
---
```
正文要求 agent:(1)先读同插件内 `standards/checklist.md` 与相关快照 md;(2)逐条核查 S1–S6,只报有证据的违规;(3)以列表输出 `规则号 | 文件:行 | 问题 | 建议`,无违规则输出 `SEMANTIC OK`;(4)明确不修改任何文件。

- [ ] **Step 2: 冒烟验证(对 bad-plugin)**

以该 agent 审查 `test/plugin-kit/fixtures/bad-plugin`(可先在其中加一处裸 `new Thread(...).start()` 制造 S2 违规)。
Expected: 报告含 S2 违规且指向正确文件行。

- [ ] **Step 3: Commit**

```bash
git add .claude-plugin/plugin/agents/swisskitj-plugin-reviewer.md test/plugin-kit
git commit -m "✨ feat(plugin-kit): semantic reviewer agent (S1–S6)"
```

---

## Task 6: 重写脚手架 skill + CLAUDE.md 模板

把 1004 行的 `.claude/commands/plugin-dev.md` 提炼为精简 skill,标准判断外置到 `standards/`。

**Files:**
- Create: `.claude-plugin/plugin/skills/swisskitj-plugin-dev/SKILL.md`
- Create: `.claude-plugin/plugin/templates/CLAUDE.md.tmpl`
- Reference(模板素材,只读):`.claude/commands/plugin-dev.md`、`SwissKit-Plugin/SwissKitJ-Plugin-KeepAwake/**`

**Interfaces:**
- Consumes:`standards/VERSION`、`standards/checklist.md`、`scripts/validate.sh`、`swisskitj-plugin-reviewer` agent。
- Produces:一次脚手架会话,在开发者空仓库产出可构建插件 + `CLAUDE.md` + `validate.sh` 副本。

- [ ] **Step 1: 写 SKILL.md frontmatter**

```markdown
---
name: swisskitj-plugin-dev
description: Use when a developer wants to create a new SwissKitJ plugin. Scaffolds a standards-compliant plugin project in an independent repo and runs compliance checks.
---
```

- [ ] **Step 2: 写 skill 流程正文(精简、引用式)**

正文按 spec §5 固定五步:
1. 询问需求(名称/插件 ID/基础包名/描述/分类/图标 + 是否需要 DB/Excel/AI/后台任务),分类取值限定 `DEV/TEXT/IMAGE/NET/OTHER`。
2. **先读** 本插件内 `standards/VERSION` 与 `standards/checklist.md`。
3. 按需拼装项目;基础骨架文件与内容**照搬** KeepAwake 的真实形态(entry class、DevLauncher、SPI、pom shade+dev profile、i18n 两个 properties),pom 的 `swisskit.api.version` 用 `standards/VERSION` 的值。
4. 落地 `CLAUDE.md`(用 templates/CLAUDE.md.tmpl 填充)并把 `scripts/validate.sh` 拷入开发者仓库根。
5. 运行 `bash validate.sh .` 并调用 `swisskitj-plugin-reviewer` agent,把违规修到零再交付。

正文中**不重复**标准细节(布局陷阱、AiTool 契约等),而是写“遵循 `standards/` 内对应文档”。可选模块(DB/Excel/AI)只给最小骨架并指向对应快照 md。

- [ ] **Step 3: 写 CLAUDE.md 模板**

Create `.claude-plugin/plugin/templates/CLAUDE.md.tmpl`,含:项目一句话说明({{description}})、构建命令(`mvn -Pdev javafx:run` 预览 / `mvn package` 出 fat JAR)、**硬性约束摘要**(SPI 路径、API provided、`.sk-*` 不用 `.glass-*`、布局三陷阱、i18n 注册)、以及**明确指令**:“任何改动后必须运行 `bash validate.sh .`;打包前用 swisskitj-plugin-reviewer 审查。”

- [ ] **Step 4: 干跑验证(临时空目录)**

在 `/tmp/sk-scaffold-test` 手动按 skill 步骤走一遍(或让执行者按 skill 生成),然后:
```bash
cp .claude-plugin/plugin/scripts/validate.sh /tmp/sk-scaffold-test/validate.sh
bash /tmp/sk-scaffold-test/validate.sh /tmp/sk-scaffold-test
```
Expected: `VALIDATE OK`。

- [ ] **Step 5: Commit**

```bash
git add .claude-plugin/plugin/skills .claude-plugin/plugin/templates
git commit -m "✨ feat(plugin-kit): lean scaffolder skill + CLAUDE.md template"
```

---

## Task 7: 分发条目 + 接入 /release + 使用文档

**Files:**
- Create: `.claude-plugin/marketplace.json`
- Modify: `/release` 命令文件(先定位)
- Create: `docs/plugins/third-party-kit.md`(第三方安装/使用说明)
- Modify: `docs/plugins/_sidebar.md`、`docs/zh/_sidebar.md`(挂新页)

**Interfaces:**
- Consumes:`plugin.json`(name/version)。
- Produces:可 `/plugin marketplace add` 的入口 + release 时自动 sync。

- [ ] **Step 1: 写 marketplace.json**

```json
{
  "name": "swisskitj",
  "owner": { "name": "SwissKitJ" },
  "plugins": [
    { "name": "swisskitj-plugin-kit", "source": "./.claude-plugin/plugin", "description": "Scaffold + validate SwissKitJ plugins." }
  ]
}
```

- [ ] **Step 2: 定位并挂 /release**

Run: `ls .claude/commands/ && grep -rn "release" .claude/commands/ 2>/dev/null | head`
在 release 命令的“bump 版本”步骤后加一行:执行 `bash scripts/sync-plugin-standards.sh` 并把 `.claude-plugin/plugin/standards/` 与 `plugin.json` 纳入发布提交。若 `/release` 命令文件不存在,则在 `docs/plugins/build-deploy.md` 记录“发布前手动运行 sync”。

- [ ] **Step 3: 写第三方使用文档**

Create `docs/plugins/third-party-kit.md`:安装(`/plugin marketplace add <repo-url>` → `/plugin install swisskitj-plugin-kit`)、用法(`/swisskitj-plugin-dev` 触发脚手架)、校验(`bash validate.sh .` + reviewer agent)、CI 集成示例(GitHub Actions 跑 validate.sh)。并在两个 `_sidebar.md` 加链接。

- [ ] **Step 4: 验证 JSON 合法**

Run: `python3 -c "import json;json.load(open('.claude-plugin/marketplace.json'));json.load(open('.claude-plugin/plugin/plugin.json'));print('json ok')"`
Expected: `json ok`。

- [ ] **Step 5: Commit**

```bash
git add .claude-plugin/marketplace.json docs/plugins/third-party-kit.md docs/plugins/_sidebar.md docs/zh/_sidebar.md
git commit -m "✨ feat(plugin-kit): marketplace entry + third-party usage docs"
# 若改了 release 命令:
git add .claude/commands/ && git commit -m "♻️ chore(release): hook sync-plugin-standards into release"
```

---

## Task 8: 端到端验证 + 退役旧命令

**Files:**
- Delete: `.claude/commands/plugin-dev.md`

**Interfaces:**
- Consumes:全部前置产物。

- [ ] **Step 1: 全链路跑一遍**

在临时空目录用新 skill 生成一个插件(如分类 `OTHER`、需要后台任务),然后:
```bash
cp .claude-plugin/plugin/scripts/validate.sh <newplugin>/validate.sh
bash <newplugin>/validate.sh <newplugin>   # 期望 VALIDATE OK
```
再用 `swisskitj-plugin-reviewer` 审查该目录 → 期望 `SEMANTIC OK`。

- [ ] **Step 2: 用宿主验证可加载(可选但推荐)**

按 CLAUDE.md 的构建方式 `mvn clean package -f <newplugin>/pom.xml -DskipTests`(经 IDEA Maven),把产出 fat JAR 丢进 `.swisskit/plugin/` 启动宿主确认工具卡片出现。若环境不便,记录为待人工验证。

- [ ] **Step 3: 退役旧 monolith 命令**

确认新 skill 覆盖旧 `plugin-dev.md` 全部能力后:
```bash
git rm .claude/commands/plugin-dev.md
git commit -m "♻️ chore(plugin-kit): retire monolithic plugin-dev command in favor of skill"
```

- [ ] **Step 4: 跑一次回归**

Run: `bash test/plugin-kit/run-validate-tests.sh`
Expected: `ALL PASS`。

---

## Self-Review

- **Spec coverage:** §3 结构→T2/T3;§5 skill→T6;§6a validate→T4;§6b agent→T5;§7 落地物→T6(CLAUDE.md/validate 副本)+T7(CI 文档);§8 sync→T3+T7;§8 前置修复→T1;§9 分期全覆盖;§10 非目标未越界。✅
- **Placeholder scan:** 无 TBD/TODO;validate.sh、sync 脚本、checklist、JSON 均给出实际内容。`/release` 路径未定为唯一已知不确定项,已给定位步骤 + 回退方案。✅
- **Type consistency:** `getCategory()`→`ToolCategory`、`getIconStyle()`→`IconStyle`、SPI FQN、`swisskit.api.version`、规则号 M1–M12/S1–S6 在各 Task 间一致引用。✅
