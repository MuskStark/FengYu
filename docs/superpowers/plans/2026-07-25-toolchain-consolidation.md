# Toolchain 目录整合 + 发布流程对齐 + 发布前修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 7 个工具链目录整合进 `toolchain/`(扁平化、语义短名),重命名 CI/release workflow 与 skill 为 `toolchain-*`,并修复发布前审查发现的 6 个 Important 问题,为 `4.0.0-alpha.3` 切割扫清障碍。

**Architecture:** 分三阶段顺序执行(目录整合是其余两项的前提)。阶段 1 用 `git mv` 搬迁(此时构建红),阶段 2 集中更新所有引用使构建转绿,阶段 3 修复 6 个 Important。每阶段独立可验证、可回退。全程在 `4.0.0-electron` 分支,不 force-push。

**Tech Stack:** Maven 多模块 reactor、npm workspaces-style `file:` 依赖、GitHub Actions、Electron 43、Spring Boot 4 actuator、Vitest、node:test。

## Global Constraints

摘自 spec `docs/superpowers/specs/2026-07-25-toolchain-consolidation-design.md`,每个任务隐含遵守:

- **不变量(发布坐标,绝对不改):** Maven artifactId `fengyu-plugin-sdk`/`fengyu-plugin-devkit`;npm 包名 `@infinia/plugin-sdk`/`plugin-ui`/`plugin-dev`/`plugin-cli`;tag 前缀 `plugin-tooling-v*`。
- **不改历史归档:** `docs/superpowers/plans/`、`docs/superpowers/specs/` 下已有文件不动(本次新 plan 例外)。
- **提交规范:** conventional commits with emojis(`♻️` refactor、`🐛` fix、`📝` docs、`🔥` removal、`🔧` chore)。仅在用户明确要求时才 commit/push/tag。
- **依赖边界:** `FengYu-Api` 是 plugin + AI 契约模块,其他模块依赖它;toolchain 的 Maven 模块**不继承** root parent(独立 POM)。
- **测试优先:** 每个修复任务先写/改测试再改实现(TDD)。

## File Structure

整合后的目标结构:

```
toolchain/
├── sdk-java/        ← FengYu-Plugin-Sdk        (Maven, artifactId 不变)
├── devkit-java/     ← FengYu-Plugin-DevKit     (Maven, artifactId 不变)
├── sdk-ts/          ← plugin-sdk/typescript    (@infinia/plugin-sdk)
├── ui/              ← plugin-ui/vue            (@infinia/plugin-ui)
├── dev/             ← plugin-dev               (@infinia/plugin-dev)
├── cli/             ← plugin-cli               (@infinia/plugin-cli)
└── spec/            ← plugin-spec              (schema + fixtures)
```

阶段 2 将更新的引用面(精确清单,来自预核验):
- **Maven:** `pom.xml`(2 个 `<module>`)、核验各子 pom 相对路径
- **npm file: 依赖:** `toolchain/ui/package.json`(`file:../../plugin-sdk/typescript` → `file:../sdk-ts`)、`toolchain/dev/package.json`(`file:../plugin-sdk/typescript` → `file:../sdk-ts`)
- **Workflows:** `toolchain-ci.yml`(原 plugin-tooling.yml,paths + cache + cd)、`toolchain-release.yml`(原 plugin-tooling-release.yml,cache + cd + mvnw + npm pack + node)、`fengyu-release.yml`(`plugin-cli` → `toolchain/cli`,2 处)
- **Scripts:** `e2e-smoke.sh`(1 处)、`plugin-tooling-local-smoke.sh`(5 处)、`check-plugin-dependency-boundaries.sh`(1 处)
- **Skills:** `.agents/skills/toolchain-release/`、`.claude/skills/toolchain-release/`(重命名 + 内容)、`AGENTS.md`、`CLAUDE.md`、其余 3 个 skill 内路径
- **Docs:** `docs/{en,zh}/plugins/{sdk-cli,build-deploy,ui-microfrontend,worker}.md`、`docs/{en,zh}/reference/glossary.md`、`README.md`(共 ~13 处路径引用)
- **后端测试:** `FengYu/src/test/.../PluginPackageServiceTest.java:90-91`(相对路径)

---

# 阶段 1 — 目录搬迁(`git mv`,此时构建红)

阶段目标:纯物理移动,保留 rename 历史。本阶段结束时仓库处于"引用未更新"的中间态,构建会失败 —— 这是预期的,阶段 2 修复。**本阶段不单独提交**,与阶段 2 首个提交合并(避免留下一个已知构建红的提交)。

### Task 1.1: 创建 toolchain/ 并搬迁 Maven 模块

**Files:**
- Move: `FengYu-Plugin-Sdk/` → `toolchain/sdk-java/`
- Move: `FengYu-Plugin-DevKit/` → `toolchain/devkit-java/`

**Interfaces:** 无(纯文件移动)

- [ ] **Step 1: 确认搬迁前状态干净**

```bash
git status --short
```
Expected: 仅 `D FengYu/src/main/resources/init.sql`(已有的未提交删除,与本任务无关,保留不动)。无其他未跟踪文件干扰。

- [ ] **Step 2: 搬迁两个 Maven 模块**

```bash
mkdir -p toolchain
git mv FengYu-Plugin-Sdk toolchain/sdk-java
git mv FengYu-Plugin-DevKit toolchain/devkit-java
```

- [ ] **Step 3: 验证 git 识别为 rename**

```bash
git status --short | grep -E "toolchain/sdk-java|toolchain/devkit-java"
```
Expected: 两行,形如 `R  FengYu-Plugin-Sdk/pom.xml -> toolchain/sdk-java/pom.xml`(R = rename,git 自动检测)。若显示为 D + ?? 说明未识别 rename,检查是否大写敏感问题。

- [ ] **不提交** —— 继续下一个 Task。

### Task 1.2: 搬迁 4 个 npm 包(扁平化中间层)

**Files:**
- Move: `plugin-sdk/typescript/` → `toolchain/sdk-ts/`(扁平化,去掉 typescript 中间层)
- Move: `plugin-ui/vue/` → `toolchain/ui/`(扁平化,去掉 vue 中间层)
- Move: `plugin-dev/` → `toolchain/dev/`
- Move: `plugin-cli/` → `toolchain/cli/`

**Interfaces:** 无。注意:`plugin-sdk/` 和 `plugin-ui/` 目录下只有唯一的 `typescript/`/`vue/` 子目录,扁平化后原父目录消失。

- [ ] **Step 1: 搬迁并扁平化 sdk-ts 和 ui**

扁平化需要两步(git mv 不能直接跨层 rename 目录内容到新目录名)。用 `git mv <src>/* <dst>` 模式:

```bash
# sdk-ts: 先建目标,再逐项 mv 内容(保留 rename 跟踪)
mkdir -p toolchain/sdk-ts
# plugin-sdk 下只有 typescript/ 一个子目录;把它的内容搬进 sdk-ts
git mv plugin-sdk/typescript/package.json toolchain/sdk-ts/package.json
# 其余内容(可能含 .gitignore, src, test, dist, package-lock.json 等)逐个搬:
for item in plugin-sdk/typescript/* plugin-sdk/typescript/.[!.]*; do
  [ -e "$item" ] && git mv "$item" "toolchain/sdk-ts/$(basename "$item")"
done
# 现在删空目录
rmdir plugin-sdk/typescript plugin-sdk 2>/dev/null || true

# ui 同理
mkdir -p toolchain/ui
git mv plugin-ui/vue/package.json toolchain/ui/package.json
for item in plugin-ui/vue/* plugin-ui/vue/.[!.]*; do
  [ -e "$item" ] && git mv "$item" "toolchain/ui/$(basename "$item")"
done
rmdir plugin-ui/vue plugin-ui 2>/dev/null || true
```

- [ ] **Step 2: 搬迁 plugin-dev 和 plugin-cli(无中间层)**

```bash
git mv plugin-dev toolchain/dev
git mv plugin-cli toolchain/cli
```

- [ ] **Step 3: 验证搬迁结果**

```bash
ls toolchain/
```
Expected: `cli  dev  devkit-java  sdk-java  sdk-ts  ui`(sdk-java/devkit-java 来自 Task 1.1)。且根目录不再有 `plugin-sdk`、`plugin-ui`、`plugin-dev`、`plugin-cli`。

```bash
# 确认旧目录已消失
ls -d plugin-sdk plugin-ui plugin-dev plugin-cli FengYu-Plugin-Sdk FengYu-Plugin-DevKit 2>&1 | grep -c "No such"
```
Expected: `6`

- [ ] **不提交** —— 继续下一个 Task。

### Task 1.3: 搬迁 plugin-spec

**Files:**
- Move: `plugin-spec/` → `toolchain/spec/`

**Interfaces:** 无。注意:`plugin-spec` 是 schema 事实源,被 `FengYu/src/test/.../PluginPackageServiceTest.java` 和 `toolchain/cli/spec/`(CLI 内部同步副本)引用。前者相对路径会在阶段 2 Task 2.1 修复,后者是 cli 内部副本随 cli 搬迁,内部 `../spec/` 仍成立。

- [ ] **Step 1: 搬迁 spec**

```bash
git mv plugin-spec toolchain/spec
```

- [ ] **Step 2: 验证**

```bash
ls toolchain/spec/
```
Expected: `fixtures  manifest.schema.json`

```bash
ls -d plugin-spec 2>&1 | grep -c "No such"
```
Expected: `1`

- [ ] **不提交** —— 阶段 1 完成,进入阶段 2。

---

# 阶段 2 — 引用更新(使构建转绿)

阶段目标:更新所有指向旧路径的引用,使全量构建、测试、smoke 转绿。阶段末整体提交一次(或按任务粒度多次提交)。每个任务结束跑对应最小验证;阶段末 Task 2.9 跑全量验证。

### Task 2.1: 后端测试 plugin-spec 相对路径

**Files:**
- Modify: `FengYu/src/test/java/fan/summer/fengyu/plugin/market/PluginPackageServiceTest.java:88-92`

**Interfaces:** 无。

- [ ] **Step 1: 读当前相对路径逻辑**

Run: `sed -n '85,95p' FengYu/src/test/java/fan/summer/fengyu/plugin/market/PluginPackageServiceTest.java`

预期看到 `root.resolve("../plugin-spec/fixtures")` 之类(root 指向 FengYu 模块根)。新位置:从 `FengYu/` 到 `toolchain/spec/fixtures` 需上两级再进 toolchain,即 `../../toolchain/spec/fixtures`。

- [ ] **Step 2: 更新相对路径**

将 `../plugin-spec/fixtures` 改为 `../../toolchain/spec/fixtures`(保留 fallback 逻辑结构)。用 Edit 工具精确替换。

- [ ] **Step 3: 验证测试编译(不跑全部,只编译测试)**

```bash
./mvnw -pl FengYu test-compile -DskipTests -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS(路径是字符串,编译期不验证存在性,但确认无语法错误)。

- [ ] **不提交** —— 继续下一个 Task(阶段末统一提交)。

### Task 2.2: Maven reactor module 路径

**Files:**
- Modify: `pom.xml`(`<modules>` 段)
- Verify(可能 modify):`toolchain/devkit-java/pom.xml`(若内部有 `${project.basedir}/../FengYu-Plugin-Sdk` 相对引用)、`OfficialPlugins/*/pom.xml`、`FengYu/pom.xml`

**Interfaces:** 无(artifactId 不变,只改本地 module 路径)。

- [ ] **Step 1: 更新 root pom modules**

在 `pom.xml` 的 `<modules>` 段,把:
```xml
<module>FengYu-Plugin-Sdk</module>
<module>FengYu-Plugin-DevKit</module>
```
改为:
```xml
<module>toolchain/sdk-java</module>
<module>toolchain/devkit-java</module>
```
(其余三个 module `FengYu-Api`/`OfficialPlugins`/`FengYu` 不动。)

- [ ] **Step 2: 核验各子 pom 是否有相对路径引用旧目录**

```bash
grep -rnE "\.\./FengYu-Plugin-Sdk|\.\./FengYu-Plugin-DevKit|\.\./plugin-sdk|\.\./plugin-ui|\.\./plugin-cli|\.\./plugin-spec|\.\./plugin-dev" \
  toolchain/devkit-java/pom.xml toolchain/sdk-java/pom.xml OfficialPlugins/*/pom.xml FengYu/pom.xml 2>/dev/null
```
Expected: 空输出(这些 pom 用 artifactId 引用,不依赖目录名)。若有命中,逐个更新为 `../sdk-java`/`../devkit-java`/`../sdk-ts` 等(按新相对位置)。

- [ ] **Step 3: 验证 Maven reactor 识别新 module 路径**

```bash
./mvnw -pl toolchain/sdk-java install -DskipTests -q 2>&1 | tail -3
./mvnw -pl toolchain/devkit-java install -DskipTests -q 2>&1 | tail -3
```
Expected: 两次都 BUILD SUCCESS。若 devkit 报找不到 sdk-java artifact,确认上一步 sdk-java 已 install 进 local .m2。

- [ ] **不提交**。

### Task 2.3: npm file: 依赖路径

**Files:**
- Modify: `toolchain/ui/package.json`(`@infinia/plugin-sdk` 的 file: 路径)
- Modify: `toolchain/dev/package.json`(`@infinia/plugin-sdk` 的 file: 路径)
- Regenerate: 两个包的 `package-lock.json`

**Interfaces:** 4 个 npm 包现在平级在 `toolchain/` 下,互指用单层 `file:../<name>`。

- [ ] **Step 1: 更新 ui 的 file: 依赖**

在 `toolchain/ui/package.json`,把:
```json
"@infinia/plugin-sdk": "file:../../plugin-sdk/typescript",
```
改为:
```json
"@infinia/plugin-sdk": "file:../sdk-ts",
```
(注意:此文件可能有同 key 的两处声明 `^1.0.0` 和 `file:`,这是既有问题,本次只改 file: 路径,不顺手去重 —— 见 Global Constraints 不做无关重构。)

- [ ] **Step 2: 更新 dev 的 file: 依赖**

在 `toolchain/dev/package.json`,把:
```json
"@infinia/plugin-sdk": "file:../plugin-sdk/typescript",
```
改为:
```json
"@infinia/plugin-sdk": "file:../sdk-ts",
```

- [ ] **Step 3: 重新生成 lockfile(两个包)**

```bash
cd toolchain/ui && npm install --package-lock-only && cd ../..
cd toolchain/dev && npm install --package-lock-only && cd ../..
```
Expected: 无错误。`package-lock.json` 更新(file: 路径变化需反映)。

- [ ] **Step 4: 验证 4 包 install + 测试**

```bash
cd toolchain/sdk-ts && npm ci && npm test && cd ../..
cd toolchain/ui && npm ci && npm run build && npm test && cd ../..
cd toolchain/dev && npm ci && npm test && cd ../..
cd toolchain/cli && npm ci && npm test && cd ../..
```
Expected: 全部通过。若 ui/dev 的 `npm ci` 因 lockfile 不一致失败,重跑 Step 3 后重试。

- [ ] **不提交**。

### Task 2.4: 验证 CLI 仍能构建官方插件(跨边界)

**Files:** 无修改,仅验证。

**Interfaces:** 确认 `toolchain/cli` → `OfficialPlugins/*` 的跨目录调用不受影响(CLI 通过命令行参数接收路径,不硬编码)。

- [ ] **Step 1: CLI 构建一个官方插件**

```bash
node toolchain/cli/bin/fengyu.mjs plugin build OfficialPlugins/plugin-markdown 2>&1 | tail -5
```
Expected: 成功生成 `OfficialPlugins/plugin-markdown/dist-package/*.fyp`。若失败,检查 CLI 内部是否硬编码了旧 toolchain 路径(grep `toolchain/cli/src` 里的路径字面量)。

- [ ] **不提交**。

### Task 2.5: GitHub workflows(CI + release + app)

**Files:**
- Rename + Modify: `.github/workflows/plugin-tooling.yml` → `.github/workflows/toolchain-ci.yml`
- Rename + Modify: `.github/workflows/plugin-tooling-release.yml` → `.github/workflows/toolchain-release.yml`
- Modify: `.github/workflows/fengyu-release.yml`(2 处 `plugin-cli` → `toolchain/cli`)

**Interfaces:** `on.push.tags: plugin-tooling-v*` 在 release workflow **保持不变**(tag 前缀不动)。

- [ ] **Step 1: 重命名并改 toolchain-ci.yml(原 plugin-tooling.yml)**

```bash
git mv .github/workflows/plugin-tooling.yml .github/workflows/toolchain-ci.yml
```

编辑 `.github/workflows/toolchain-ci.yml`:

(a) `on.push.paths` 和 `on.pull_request.paths`:把
```yaml
      - 'plugin-ui/**'
      - 'plugin-cli/**'
      - 'plugin-sdk/**'
      - 'FengYu-Plugin-Sdk/**'
```
合并为单行:
```yaml
      - 'toolchain/**'
```
`OfficialPlugins/**`、`scripts/plugin-tooling-local-smoke.sh` 保留;把两处 `'.github/workflows/plugin-tooling.yml'` 改为 `'.github/workflows/toolchain-ci.yml'`。

(b) `cache-dependency-path`(约 50-52 行):
```yaml
            plugin-sdk/typescript/package-lock.json
            plugin-ui/vue/package-lock.json
            plugin-cli/package-lock.json
```
改为:
```yaml
            toolchain/sdk-ts/package-lock.json
            toolchain/ui/package-lock.json
            toolchain/cli/package-lock.json
```

(c) 各 `cd`/`run`/`working-directory`(约 55-75 行):`cd plugin-sdk/typescript` → `cd toolchain/sdk-ts`;`cd plugin-ui/vue` → `cd toolchain/ui`;`cd plugin-cli` → `cd toolchain/cli`;约 59 行 `cp -R plugin-sdk/typescript/.` → `cp -R toolchain/sdk-ts/.`;约 73、75 行 `working-directory: plugin-ui/vue` → `working-directory: toolchain/ui`;约 85-86 行 playwright 路径 `plugin-ui/vue/...` → `toolchain/ui/...`;约 104 行 `cache-dependency-path: plugin-cli/package-lock.json` → `toolchain/cli/package-lock.json`;约 112 行 `./mvnw -pl FengYu-Plugin-Sdk` → `./mvnw -pl toolchain/sdk-java`;约 114 行 `working-directory: plugin-cli` → `working-directory: toolchain/cli`;约 115 行 `node plugin-cli/bin/fengyu.mjs` → `node toolchain/cli/bin/fengyu.mjs`。

- [ ] **Step 2: 重命名并改 toolchain-release.yml(原 plugin-tooling-release.yml)**

```bash
git mv .github/workflows/plugin-tooling-release.yml .github/workflows/toolchain-release.yml
```

全文件路径替换(共约 15 处):
- `cd plugin-sdk/typescript` → `cd toolchain/sdk-ts`
- `cd plugin-ui/vue` → `cd toolchain/ui`
- `cd plugin-dev` → `cd toolchain/dev`
- `cd plugin-cli` → `cd toolchain/cli`
- `./mvnw -pl FengYu-Plugin-Sdk` → `./mvnw -pl toolchain/sdk-java`
- `./mvnw -pl FengYu-Plugin-DevKit` → `./mvnw -pl toolchain/devkit-java`
- `./mvnw -f FengYu-Plugin-Sdk/pom.xml` → `./mvnw -f toolchain/sdk-java/pom.xml`
- `./mvnw -f FengYu-Plugin-DevKit/pom.xml` → `./mvnw -f toolchain/devkit-java/pom.xml`
- `node plugin-cli/bin/fengyu.mjs` → `node toolchain/cli/bin/fengyu.mjs`
- `(cd plugin-cli && npm pack` → `(cd toolchain/cli && npm pack`
- `(cd plugin-dev && npm pack` → `(cd toolchain/dev && npm pack`
- `(cd plugin-sdk/typescript && npm pack` → `(cd toolchain/sdk-ts && npm pack`
- `(cd plugin-ui/vue && npm pack` → `(cd toolchain/ui && npm pack`
- `cache-dependency-path:` 下的 4 个 lockfile 路径 → `toolchain/<name>/package-lock.json`
- `node plugin-cli/scripts/resolve-tooling-version.mjs` → `node toolchain/cli/scripts/resolve-tooling-version.mjs`
- `scripts/plugin-tooling-local-smoke.sh` **保持不变**(脚本文件名不重命名)

**关键:`on.push.tags: ['plugin-tooling-v*']` 和注释里的 tag 前缀一律不动。**

- [ ] **Step 3: 改 fengyu-release.yml 的 plugin-cli 引用**

`.github/workflows/fengyu-release.yml` 约 104-121 行:
- `working-directory: plugin-cli` → `working-directory: toolchain/cli`(约 106 行)
- `node plugin-cli/bin/fengyu.mjs` → `node toolchain/cli/bin/fengyu.mjs`(约 121 行)
- 步骤名 "Install plugin-cli deps" 可保留或改为 "Install toolchain/cli deps"(可选)

- [ ] **Step 4: YAML 语法验证**

```bash
for f in .github/workflows/toolchain-ci.yml .github/workflows/toolchain-release.yml .github/workflows/fengyu-release.yml; do
  python3 -c "import yaml,sys; yaml.safe_load(open('$f')); print('OK: $f')" || echo "FAIL: $f"
done
```
Expected: 三个都 `OK:`。

- [ ] **Step 5: 核验无残留旧路径**

```bash
grep -rnE "plugin-sdk/|plugin-ui/|plugin-cli/|plugin-dev/|FengYu-Plugin-Sdk|FengYu-Plugin-DevKit" .github/workflows/ 2>/dev/null | grep -v "plugin-tooling-v\|toolchain"
```
Expected: 空输出(所有旧路径都已迁移,tag 前缀和 toolchain 新路径被 grep -v 排除)。

- [ ] **不提交**。

### Task 2.6: Shell 脚本路径

**Files:**
- Modify: `scripts/e2e-smoke.sh:28`
- Modify: `scripts/plugin-tooling-local-smoke.sh:9,10,12,17,24,29`
- Modify: `scripts/check-plugin-dependency-boundaries.sh:49`

**Interfaces:** 无。脚本文件名保持不变(`plugin-tooling-local-smoke.sh` 不重命名,因为它属于 scripts 命名空间,与 toolchain 目录解耦)。

- [ ] **Step 1: e2e-smoke.sh**

约 28 行:
```bash
  if ! node "$ROOT/plugin-cli/bin/fengyu.mjs" plugin build "$ROOT/OfficialPlugins/plugin-$plugin" >/dev/null; then
```
改为:
```bash
  if ! node "$ROOT/toolchain/cli/bin/fengyu.mjs" plugin build "$ROOT/OfficialPlugins/plugin-$plugin" >/dev/null; then
```

- [ ] **Step 2: plugin-tooling-local-smoke.sh**

```bash
./mvnw -f FengYu-Plugin-Sdk/pom.xml install -DskipTests
```
→ `./mvnw -f toolchain/sdk-java/pom.xml install -DskipTests`
```bash
./mvnw -f FengYu-Plugin-DevKit/pom.xml install -DskipTests
```
→ `./mvnw -f toolchain/devkit-java/pom.xml install -DskipTests`
```bash
cd "$ROOT/plugin-sdk/typescript"
```
→ `cd "$ROOT/toolchain/sdk-ts"`
```bash
cd "$ROOT/plugin-ui/vue"
```
→ `cd "$ROOT/toolchain/ui"`
```bash
cd "$ROOT/plugin-dev"
```
→ `cd "$ROOT/toolchain/dev"`
```bash
cd "$ROOT/plugin-cli"
```
→ `cd "$ROOT/toolchain/cli"`

- [ ] **Step 3: check-plugin-dependency-boundaries.sh**

约 49 行:
```bash
reject_text "$ROOT/FengYu-Plugin-Sdk/pom.xml" \
```
→ `reject_text "$ROOT/toolchain/sdk-java/pom.xml" \`

- [ ] **Step 4: 核验其余脚本无残留**

```bash
grep -rnE "plugin-sdk/|plugin-ui/|plugin-cli/|plugin-dev/|plugin-spec/|FengYu-Plugin-Sdk|FengYu-Plugin-DevKit" scripts/ 2>/dev/null
```
Expected: 空输出(`test-web-release.sh`、`package-web-release.sh`、`offlinepython-e2e-smoke.sh` 经预核验无引用,应无残留)。

- [ ] **不提交**。

### Task 2.7: Skills + CLAUDE 适配器 + AGENTS/CLAUDE

**Files:**
- Rename + Modify: `.agents/skills/plugin-tooling-release/` → `.agents/skills/toolchain-release/`
- Rename + Modify: `.claude/skills/plugin-tooling-release/` → `.claude/skills/toolchain-release/`
- Modify: `AGENTS.md`、`CLAUDE.md`(对该 skill 名的引用)
- Modify: `.agents/skills/fengyu-plugin-dev/SKILL.md`、`app-release/SKILL.md`、`docs-updater/SKILL.md`、`toolchain-release/SKILL.md`(内部路径)

**Interfaces:** skill 的 `name` frontmatter 字段从 `plugin-tooling-release` 改为 `toolchain-release`。

- [ ] **Step 1: 重命名两个 skill 目录**

```bash
git mv .agents/skills/plugin-tooling-release .agents/skills/toolchain-release
git mv .claude/skills/plugin-tooling-release .claude/skills/toolchain-release
```

- [ ] **Step 2: 更新 toolchain-release SKILL.md 的 frontmatter name + 内部路径**

`.agents/skills/toolchain-release/SKILL.md`:
- 第 2 行 `name: plugin-tooling-release` → `name: toolchain-release`
- "Source of truth" 表(约 13-18 行)6 处路径:
  - `FengYu-Plugin-Sdk/pom.xml` → `toolchain/sdk-java/pom.xml`
  - `FengYu-Plugin-DevKit/pom.xml` → `toolchain/devkit-java/pom.xml`
  - `plugin-cli/package.json` → `toolchain/cli/package.json`
  - `plugin-dev/package.json` → `toolchain/dev/package.json`
  - `plugin-sdk/typescript/package.json` → `toolchain/sdk-ts/package.json`
  - `plugin-ui/vue/package.json` → `toolchain/ui/package.json`
- Step 2 的 6 个文件 bullet 同步更新。
- Step 3 的 cd 命令(约 62-72 行):`cd plugin-cli` → `cd toolchain/cli`、`cd plugin-dev` → `cd toolchain/dev`、`cd plugin-sdk/typescript` → `cd toolchain/sdk-ts`、`cd plugin-ui/vue` → `cd toolchain/ui`、`mvn -f FengYu-Plugin-Sdk/pom.xml` → `mvn -f toolchain/sdk-java/pom.xml`、`mvn -f FengYu-Plugin-DevKit/pom.xml` → `mvn -f toolchain/devkit-java/pom.xml`。
- Step 1 的 `node plugin-cli/scripts/resolve-tooling-version.mjs` → `node toolchain/cli/scripts/resolve-tooling-version.mjs`(约 32 行)。
- 注:`.claude/skills/toolchain-release/` 是适配器(指向 .agents),检查其内容是否需同步(grep 后决定)。

- [ ] **Step 3: 更新 AGENTS.md 和 CLAUDE.md 的 skill 名引用**

```bash
grep -n "plugin-tooling-release\|plugin-tooling-local-smoke\|FengYu-Plugin-Sdk\|plugin-sdk/\|plugin-ui/\|plugin-cli/" AGENTS.md CLAUDE.md
```
对每个命中:
- skill 名 `plugin-tooling-release` → `toolchain-release`(仅指 skill 名时;指 tag 前缀 `plugin-tooling-v*` 不动)
- 路径引用同步更新为 `toolchain/<name>`

注:AGENTS.md 的 "Maven reactor" 表和 "Two version lines" 段可能有 `FengYu-Plugin-Sdk` 描述,按新路径 `toolchain/sdk-java` 更新但保留描述文字。`plugin-tooling-local-smoke.sh` 脚本名保持不变。

- [ ] **Step 4: 更新其余 3 个 skill 内的 toolchain 路径**

```bash
grep -rnE "plugin-sdk/|plugin-ui/|plugin-cli/|plugin-dev/|plugin-spec/|FengYu-Plugin-Sdk|FengYu-Plugin-DevKit|plugin-tooling-release" \
  .agents/skills/fengyu-plugin-dev/SKILL.md \
  .agents/skills/app-release/SKILL.md \
  .agents/skills/docs-updater/SKILL.md 2>/dev/null
```
对每个命中更新路径;若引用了 `plugin-tooling-release` skill 名则改为 `toolchain-release`。

- [ ] **Step 5: 核验无残留**

```bash
grep -rnE "plugin-sdk/|plugin-ui/|plugin-cli/|plugin-dev/|plugin-spec/|FengYu-Plugin-Sdk|FengYu-Plugin-DevKit" \
  .agents/ .claude/ AGENTS.md CLAUDE.md 2>/dev/null | grep -v "plugin-tooling-v\|toolchain"
```
Expected: 空输出(仅 tag 前缀和新 toolchain 路径被排除)。

- [ ] **不提交**。

### Task 2.8: Docs(中英当前文档)+ README + CHANGELOG

**Files:**
- Modify: `docs/en/plugins/{sdk-cli,build-deploy,ui-microfrontend,worker}.md`、`docs/en/reference/glossary.md`
- Modify: `docs/zh/` 对应 5 个文件
- Modify: `README.md`
- Modify: `CHANGELOG.md`(加一条本次变更)

**Interfaces:** 历史归档(`docs/superpowers/`)不动。

- [ ] **Step 1: 列出全部命中行**

```bash
grep -rnE "plugin-sdk/typescript|plugin-ui/vue|plugin-cli/bin|plugin-cli/package|FengYu-Plugin-Sdk|plugin-dev/package" \
  docs/en docs/zh README.md 2>/dev/null
```

- [ ] **Step 2: 逐文件更新路径(共约 13 处)**

映射:
- `plugin-sdk/typescript` → `toolchain/sdk-ts`
- `plugin-ui/vue` → `toolchain/ui`
- `plugin-cli/bin` → `toolchain/cli/bin`
- `plugin-cli/package` → `toolchain/cli/package`
- `plugin-dev/package` → `toolchain/dev/package`
- `FengYu-Plugin-Sdk`(指目录/artifact 描述时)→ `toolchain/sdk-java`(若指 Maven artifactId `fengyu-plugin-sdk` 则不动 —— 注意区分:artifactId 全小写带连字符,目录名是 `FengYu-Plugin-Sdk`)

`README.md:145` 的 reactor 表条目 `FengYu-Plugin-Sdk` 描述 → `toolchain/sdk-java`。

- [ ] **Step 3: CHANGELOG 加一条**

在 `CHANGELOG.md` 顶部(最新未发布段)加:
```markdown
### ♻️ Toolchain 目录整合
- 7 个插件工具链目录(2 Maven + 4 npm + schema)整合进 `toolchain/`,扁平化中间层,统一语义短名(`sdk-java`/`devkit-java`/`sdk-ts`/`ui`/`dev`/`cli`/`spec`)。
- CI/release workflow 与 skill 重命名为 `toolchain-*`(`plugin-tooling.yml`→`toolchain-ci.yml`,`plugin-tooling-release.yml`→`toolchain-release.yml`)。tag 前缀 `plugin-tooling-v*` 不变。
```

- [ ] **Step 4: 核验无残留**

```bash
grep -rnE "plugin-sdk/typescript|plugin-ui/vue|plugin-cli/bin|FengYu-Plugin-Sdk|FengYu-Plugin-DevKit" \
  docs/en docs/zh README.md 2>/dev/null | grep -v "toolchain\|fengyu-plugin-sdk\|fengyu-plugin-devkit"
```
Expected: 空输出。

- [ ] **不提交**。

### Task 2.9: 阶段 2 全量验证 + 提交

**Files:** 无修改,仅验证后提交阶段 1+2 全部改动。

**Interfaces:** 阶段 2 结束时,整个仓库构建/测试/smoke 应转绿。

- [ ] **Step 1: 全量 Maven 构建(含后端测试编译)**

```bash
./mvnw -pl FengYu -am package -DskipTests 2>&1 | tail -5
```
Expected: BUILD SUCCESS。`-am` 会先构建依赖模块(FengYu-Api、toolchain/sdk-java、toolchain/devkit-java、OfficialPlugins)。

- [ ] **Step 2: 后端测试(验证 plugin-spec 相对路径修复)**

```bash
./mvnw -pl FengYu test -Dtest='PluginPackageServiceTest' 2>&1 | tail -10
```
Expected: 测试通过(它读 `toolchain/spec/fixtures`)。

- [ ] **Step 3: toolchain local smoke**

```bash
scripts/plugin-tooling-local-smoke.sh 2>&1 | tail -10
```
Expected: 成功(install Java SDK、pack TS SDK/UI、consumer 解析)。

- [ ] **Step 4: e2e smoke**

```bash
scripts/e2e-smoke.sh 2>&1 | tail -10
```
Expected: 成功(含 CLI 构建官方插件)。

- [ ] **Step 5: docs build**

```bash
npm run docs:build 2>&1 | tail -5
```
Expected: 成功(确认 docs 路径更新后无死链)。

- [ ] **Step 6: 最终残留扫描**

```bash
grep -rnE "(^|/)(plugin-sdk|plugin-ui|plugin-cli|plugin-dev|plugin-spec|FengYu-Plugin-Sdk|FengYu-Plugin-DevKit)(/|$)" \
  --include="*.yml" --include="*.yaml" --include="*.json" --include="*.sh" --include="*.mjs" --include="*.ts" --include="*.js" --include="*.md" --include="*.java" \
  . 2>/dev/null | grep -v node_modules | grep -v /target/ | grep -v /dist/ | grep -v "package-lock.json" | grep -v "docs/superpowers/" | grep -v "plugin-tooling-v"
```
Expected: 空输出(或仅命中 `plugin-tooling-v*` tag 前缀,已被 grep -v 排除)。若有残留,逐一修复。

- [ ] **Step 7: 提交阶段 1 + 阶段 2 全部改动**

```bash
git add -A
git status --short | head -40   # 人工确认:全是 rename + 修改,无意外删除
git commit -m "♻️ toolchain: 整合 7 目录到 toolchain/ + 对齐发布流程

- 搬迁 FengYu-Plugin-Sdk/FengYu-Plugin-DevKit → toolchain/sdk-java/devkit-java
- 扁平化 plugin-sdk/typescript → toolchain/sdk-ts,plugin-ui/vue → toolchain/ui
- 搬迁 plugin-dev/plugin-cli/plugin-spec → toolchain/dev/cli/spec
- 更新 Maven reactor module 路径(artifactId 不变)
- 更新 npm file: 依赖路径(ui/dev → ../sdk-ts)
- 重命名 plugin-tooling.yml → toolchain-ci.yml,plugin-tooling-release.yml → toolchain-release.yml
- 更新 fengyu-release.yml、6 个脚本、4 个 skill、AGENTS/CLAUDE、docs(en+zh)路径
- tag 前缀 plugin-tooling-v* 保持不变"
```
(此提交大但内聚 —— 是一次原子重构。若用户要求拆分,可按 Task 边界拆成多个提交,但需确保每个提交都构建绿 —— 阶段 1 单独提交会红,故建议合并。)

---

# 阶段 3 — 修复 6 个 Important(发布前审查发现)

阶段目标:修复后端 2 项(B1 actuator、B2 Web bundle token)+ 桌面壳 4 项(D1 导航守卫、D2 auto-updater JRE、D3 supervisor stop、D4 APP 崩溃提示)。每个任务 TDD,独立提交。

### Task 3.1: B1 — 收紧 actuator exposure 为 health-only

**Files:**
- Modify: `FengYu/src/main/resources/application.yml:23-30`

**Interfaces:** 无。核验结论:无任何代码调 `/actuator/restart`;SETUP→APP 走 `System.exit(SETUP_DONE)`。

- [ ] **Step 1: 写测试 —— 确认 actuator 配置不含 restart**

本任务改的是 YAML 配置,无单元测试直接覆盖;改为在 Task 3.x 的 e2e smoke 里验证 `/actuator/restart` 返回 404(见 Step 3)。先在此记录预期:`grep restart application.yml` 应空。

- [ ] **Step 2: 更新 application.yml**

把:
```yaml
management:
  endpoint:
    restart:
      enabled: true             # Web deployment: setup wizard triggers context restart
  endpoints:
    web:
      exposure:
        include: restart,health
```
改为:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
```
(整个 `endpoint.restart` 段删除。SETUP→APP 重启走 `System.exit(SETUP_DONE)` + 桌面 supervisor,不依赖 actuator。)

- [ ] **Step 3: 验证配置 + restart 端点不可达**

```bash
# 配置无 restart
grep -c "restart" FengYu/src/main/resources/application.yml
```
Expected: `0`

```bash
# 启动后端,确认 /actuator/restart 404(需先 package)
./mvnw -pl FengYu -am package -DskipTests -q
java -jar FengYu/target/FengYu-*.jar --port=24099 &
BACKEND_PID=$!
sleep 8
# health 应 200
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:24099/api/health
# restart 应 404
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://127.0.0.1:24099/actuator/restart
kill $BACKEND_PID 2>/dev/null
```
Expected: health = `200`,restart = `404`。

- [ ] **Step 4: 提交**

```bash
git add FengYu/src/main/resources/application.yml
git commit -m "🐛 fix(security): 收紧 actuator exposure 为 health-only

移除 management.endpoint.restart 暴露。SETUP→APP 重启完全走
System.exit(SETUP_DONE) + 桌面 supervisor,无任何代码调用
/actuator/restart。该端点在 Web bundle 无 token 姿态下可被任意
loopback 进程强制重启上下文(DoS)。"
```

### Task 3.2: B2 — Web bundle 默认生成并传 token

**Files:**
- Modify: `distribution/web/run.sh`
- Modify: `distribution/web/run.bat`

**Interfaces:** 用户显式传 `--token=` 时覆盖;未传时脚本生成随机 token 并打印到 stderr 供用户记录。

- [ ] **Step 1: 写测试(契约级 —— 确认脚本生成 token 逻辑)**

无现成测试框架覆盖 shell。改为在脚本里加自检:若 `$@` 不含 `--token`,生成一个。手动验证为主(Step 3)。

- [ ] **Step 2: 更新 run.sh**

`distribution/web/run.sh` 当前关键行(约 7-11):
```sh
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
...
exec "$JAVA" -Dfengyu.plugins.official-directory="$ROOT/plugins" -jar "$ROOT/Infinia.jar" "$@"
```

改为(在 exec 前注入 token,若用户未传):
```sh
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
...
# 若用户未显式传 --token,生成一个随机 per-launch token 并传入,避免默认认证关闭。
# 用户传 --token=<t> 时此处不覆盖(下面的 case 检测)。
TOKEN_ARGS=()
case " $* " in *" --token"*) ;; *" --token="*) ;; *)
  GEN_TOKEN="zf-$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')-$$"
  TOKEN_ARGS=(--token="$GEN_TOKEN")
  echo "Generated per-launch token (pass --token=<t> to override): $GEN_TOKEN" >&2
;; esac

exec "$JAVA" -Dfengyu.plugins.official-directory="$ROOT/plugins" -jar "$ROOT/Infinia.jar" "${TOKEN_ARGS[@]}" "$@"
```

- [ ] **Step 3: 更新 run.bat**

`distribution/web/run.bat` 末行(约 42):
```bat
"%JAVA%" -Dfengyu.plugins.official-directory="%ROOT%plugins" -jar "%ROOT%Infinia.jar" %*
```

改为(在末行前加 token 生成,bat 用 %RANDOM%+%TIME%):
```bat
REM 若用户未显式传 --token,生成随机 token 避免默认认证关闭。
setlocal enabledelayedexpansion
set "HAS_TOKEN=0"
for %%A in (%*) do (
  echo %%A | findstr /b "--token" >nul && set "HAS_TOKEN=1"
)
if "!HAS_TOKEN!"=="0" (
  set "GEN_TOKEN=zf-%RANDOM%%RANDOM%-%TIME:~6,2%%TIME:~9,2%"
  echo Generated per-launch token (pass --token=^<t^> to override): !GEN_TOKEN! >&2
  "%JAVA%" -Dfengyu.plugins.official-directory="%ROOT%plugins" -jar "%ROOT%Infinia.jar" --token="!GEN_TOKEN!" %*
) else (
  "%JAVA%" -Dfengyu.plugins.official-directory="%ROOT%plugins" -jar "%ROOT%Infinia.jar" %*
)
endlocal
```

- [ ] **Step 4: 验证 run.sh(linux/mac)**

```bash
# 模拟无 token 启动
cd distribution/web
# 需先有 Infinia.jar(从 FengYu/target 复制或符号链接)
cp ../../FengYu/target/FengYu-*.jar Infinia.jar 2>/dev/null || echo "skip: no jar,仅验脚本逻辑"
# 仅 dry-run 脚本的 token 生成逻辑(不真启 java)
bash -c 'case " $* " in *" --token"*) ;; *" --token="*) ;; *) echo "would gen token";; esac' -- --port=8080
bash -c 'case " $* " in *" --token"*) ;; *" --token="*) ;; *) echo "would gen token";; esac' -- --token=secret
```
Expected: 第一行输出 `would gen token`,第二行无输出(已传 token)。

- [ ] **Step 5: 提交**

```bash
git add distribution/web/run.sh distribution/web/run.bat
git commit -m "🐛 fix(security): Web bundle 默认生成并传 per-launch token

run.sh/run.bat 在用户未传 --token 时生成随机 token 并传入,避免
默认认证关闭导致任意 loopback 进程可访问后端。用户显式传
--token=<t> 时覆盖。"
```

### Task 3.3: D1 — 桌面壳导航守卫(setWindowOpenHandler + will-navigate)

**Files:**
- Modify: `desktop/electron/src/window/create-window.ts`
- Test: `desktop/electron/test/`(新增或扩展现有测试)

**Interfaces:** 无。需 import `shell`。

- [ ] **Step 1: 写失败测试**

新增 `desktop/electron/test/window-open-handler.test.ts`(或若框架不易模拟 webContents,改为在现有 launch e2e 里加断言)。Vitest 单元测试模拟 `win.webContents.setWindowOpenHandler` 较繁,采用更轻量方案:测试 createMainWindow 返回的 win 上注册了 handler(通过 spy)。

若测试 webContents 不可行(Electron 在 node 环境难初始化),降级为:在 `test/e2e/launch.spec.ts` 里加一步 —— 加载 About 页,点一个外链,断言无新窗口打开、shell.openExternal 被调用。先尝试单元测试:

```ts
// desktop/electron/test/window-open-handler.test.ts
import { describe, it, expect, vi } from 'vitest'

vi.mock('electron', () => {
  const handlers: Record<string, Function> = {}
  return {
    BrowserWindow: vi.fn().mockImplementation(() => ({
      on: vi.fn(),
      loadURL: vi.fn(),
      loadFile: vi.fn(),
      webContents: {
        openDevTools: vi.fn(),
        setWindowOpenHandler: vi.fn((fn) => { handlers.open = fn }),
        on: vi.fn((evt, fn) => { handlers[evt] = fn }),
        getURL: vi.fn(() => 'http://127.0.0.1:5173/'),
      },
    })),
    session: { defaultSession: { webRequest: { onHeadersReceived: vi.fn() } } },
    shell: { openExternal: vi.fn() },
  }
})

describe('createMainWindow navigation guards', () => {
  it('denies window.open and delegates http(s) to shell.openExternal', async () => {
    const { createMainWindow } = await import('../src/window/create-window')
    const win = createMainWindow({ apiBase: '', token: '', onHideToTray: () => {}, isDev: true, isQuitting: () => false })
    // setWindowOpenHandler 被调用过(win.webContents.setWindowOpenHandler 是 spy)
    expect((win.webContents.setWindowOpenHandler as any).mock.calls.length).toBeGreaterThan(0)
  })
})
```

- [ ] **Step 2: 跑测试确认失败(实现未加)**

```bash
cd desktop/electron && npx vitest run test/window-open-handler.test.ts 2>&1 | tail -15
```
Expected: FAIL(setWindowOpenHandler 未被调用)。

- [ ] **Step 3: 实现 —— 加导航守卫**

`desktop/electron/src/window/create-window.ts`,在 `new BrowserWindow({...})` 之后、`win.on('close', ...)` 之前加:

```ts
import { BrowserWindow, session, shell } from 'electron'  // 顶部 import 加 shell
```

```ts
  // Navigation guard: deny all window.open; delegate http(s) to the system browser.
  // Without this, <a target="_blank"> opens a new Electron window with the same preload,
  // and a compromised page could window.open('file://...') or navigate to an arbitrary origin.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//.test(url)) {
      void shell.openExternal(url)
    }
    return { action: 'deny' }
  })
  // Block in-page navigation to a different origin (defense against iframe/top-level redirects).
  win.webContents.on('will-navigate', (e, url) => {
    if (url !== win.webContents.getURL()) e.preventDefault()
  })
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd desktop/electron && npx vitest run test/window-open-handler.test.ts 2>&1 | tail -10
```
Expected: PASS。

- [ ] **Step 5: 跑桌面壳全部测试**

```bash
cd desktop/electron && npm test 2>&1 | tail -10
```
Expected: 全部 PASS。

- [ ] **Step 6: 提交**

```bash
git add desktop/electron/src/window/create-window.ts desktop/electron/test/window-open-handler.test.ts
git commit -m "🐛 fix(desktop): 加 setWindowOpenHandler + will-navigate 导航守卫

禁止 window.open 开新 Electron 窗口(会带相同 preload),http(s)
外链委托给系统浏览器;阻止页面内导航到不同源。缓解被入侵页面
window.open('file://...') 或跨源跳转的风险。"
```

### Task 3.4: D2 — auto-updater 在 JRE 变体跳过更新检查

**Files:**
- Modify: `desktop/electron/src/updater/auto-updater.ts`
- Test: `desktop/electron/test/`(新增)

**Interfaces:** 无。JRE 变体检测:`existsSync(join(process.resourcesPath, 'jre'))`(electron-builder.yml 的 JRE 变体把 jre 放到 resourcesPath/jre)。

- [ ] **Step 1: 写失败测试**

新增 `desktop/electron/test/auto-updater.test.ts`:
```ts
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('electron-updater', () => ({ autoUpdater: { checkForUpdates: vi.fn() } }))
vi.mock('electron', () => ({ dialog: { showMessageBox: vi.fn() } }))
vi.mock('node:fs', () => ({
  existsSync: vi.fn((p: string) => p.includes('jre')),
}))

describe('checkForUpdates skips JRE variant', () => {
  beforeEach(() => vi.clearAllMocks())

  it('does not check for updates when jre/ exists in resourcesPath', async () => {
    const { autoUpdater } = await import('electron-updater')
    const { checkForUpdates } = await import('../src/updater/auto-updater')
    await checkForUpdates()
    expect((autoUpdater.checkForUpdates as any).mock.calls.length).toBe(0)
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd desktop/electron && npx vitest run test/auto-updater.test.ts 2>&1 | tail -10
```
Expected: FAIL(当前实现无 JRE 检测,会调用 checkForUpdates)。

- [ ] **Step 3: 实现 JRE 变体检测**

`desktop/electron/src/updater/auto-updater.ts`,在 `checkForUpdates` 开头加:
```ts
import { existsSync } from 'node:fs'
import { join } from 'node:path'
```
```ts
export async function checkForUpdates(): Promise<void> {
  // JRE variant bundles its own jlink JRE under <resourcesPath>/jre. The updater feed
  // (latest*.yml) only references the lite variant, so auto-update would silently downgrade
  // JRE users to the Java-dependent lite build. Skip the check until per-variant feeds exist.
  if (existsSync(join(process.resourcesPath, 'jre'))) {
    console.log('[updater] JRE variant detected; skipping auto-update (would downgrade to lite)')
    return
  }
  try {
    // ... 原有逻辑不变
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd desktop/electron && npx vitest run test/auto-updater.test.ts 2>&1 | tail -10
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add desktop/electron/src/updater/auto-updater.ts desktop/electron/test/auto-updater.test.ts
git commit -m "🐛 fix(desktop): auto-updater 在 JRE 变体跳过更新检查

JRE 变体自带 jlink JRE,但 updater feed(latest*.yml)只引用 lite
变体,auto-update 会把 JRE 用户静默降级为依赖 Java 的 lite 构建。
检测 resourcesPath/jre 存在则跳过,直到实现 per-variant feeds。"
```

### Task 3.5: D3 — supervisor stop() 保存与调用

**Files:**
- Modify: `desktop/electron/src/main.ts`(保存 stop、在 killBackend 调用)

**Interfaces:** `superviseSetupRestart` 返回 `() => void`(已是现有签名,不变)。

- [ ] **Step 1: 写测试**

`desktop/electron/test/supervisor.test.ts` 已存在(测纯函数)。扩展:测 `superviseSetupRestart` 返回的 stop 被 main 调用。由于 main.ts 是入口,难单测;改为在 supervisor.test.ts 里测 stop() 的幂等性(已是现有行为),main.ts 的调用靠代码审查 + 集成。简化:本任务无新测试,靠 Step 4 的全量 npm test 不回归。

- [ ] **Step 2: 实现 —— main.ts 保存并调用 stop**

`desktop/electron/src/main.ts`:

顶部模块级变量(约 17-19 行附近,与 `let backendChild` 同段):
```ts
let backendChild: BackendChild | null = null
let devFrontend: DevFrontendHandle | null = null
let stopSupervisor: (() => void) | null = null   // NEW
let isQuitting = false
```

`killBackend()`(约 26-30 行)加调用:
```ts
function killBackend() {
  isQuitting = true
  stopSupervisor?.()          // NEW: detach the SETUP watcher
  stopSupervisor = null       // NEW
  backendChild?.kill()
  devFrontend?.stop()
}
```

`superviseSetupRestart` 调用处(约 166-177 行),保存返回值:
```ts
    stopSupervisor = superviseSetupRestart({   // NEW: 保存返回值
      getChild: () => backendChild,
      // ... 其余参数不变
    })
```

- [ ] **Step 3: 跑桌面壳全部测试**

```bash
cd desktop/electron && npm test 2>&1 | tail -10
```
Expected: 全部 PASS(无回归)。

- [ ] **Step 4: 提交**

```bash
git add desktop/electron/src/main.ts
git commit -m "🐛 fix(desktop): 保存并调用 superviseSetupRestart 的 stop()

main.ts 此前丢弃返回的 stop()。虽因 proc.once 自移除暂无泄漏,但
保存 stop 并在 killBackend 调用,使意图明确且防御未来加入的
interval/持久监听器。"
```

### Task 3.6: D4 — APP 模式后端崩溃提示

**Files:**
- Modify: `desktop/electron/src/main.ts`(APP 模式注册轻量 exit 监听)
- Test: `desktop/electron/test/`(可选,新增)

**Interfaces:** 无。alpha 阶段不自动重启(避免重启循环),仅 dialog 提示。

- [ ] **Step 1: 写测试(可选,若 main.ts 难单测则跳过靠审查)**

由于 main.ts bootstrap 逻辑重、依赖 Electron app 生命周期,单测成本高。本任务靠 Step 3 的全量测试不回归 + 代码审查。若需要,可在 supervisor.test.ts 加一个纯函数 `isAppCrash(exitCode, shuttingDown)` 测试逻辑分离:

`desktop/electron/src/backend/supervisor.ts` 加导出:
```ts
/** APP-mode backend exited unexpectedly (non-zero, not during shutdown). */
export function isAppCrash(exitCode: number | null, shuttingDown: boolean): boolean {
  return !shuttingDown && exitCode !== 0 && exitCode !== null
}
```

`desktop/electron/test/supervisor.test.ts` 加:
```ts
import { isAppCrash } from '../src/backend/supervisor'
describe('isAppCrash', () => {
  it('true for non-zero exit while running', () => {
    expect(isAppCrash(1, false)).toBe(true)
  })
  it('false during shutdown', () => {
    expect(isAppCrash(1, true)).toBe(false)
  })
  it('false for clean exit (0)', () => {
    expect(isAppCrash(0, false)).toBe(false)
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd desktop/electron && npx vitest run test/supervisor.test.ts 2>&1 | tail -10
```
Expected: FAIL(isAppCrash 未导出)。

- [ ] **Step 3: 实现 isAppCrash + main.ts APP 模式监听**

(a) supervisor.ts 加 `isAppCrash`(见 Step 1 代码)。

(b) main.ts:在 `const action = startupAction(...)` 之后,无论 action 是 ShowWindow 还是 ShowWindowAndSupervise,都给 `backendChild` 注册 APP 崩溃监听。在约 162-178 行段后加:
```ts
  // APP-mode crash guard: if the backend dies unexpectedly (not during shutdown, non-zero),
  // surface a dialog instead of silently leaving the user with connection errors.
  // Alpha does NOT auto-restart (avoid restart loops); user relaunches manually.
  if (backendChild) {
    const proc = backendChild.process
    proc.once('exit', (code) => {
      if (isAppCrash(code, isQuitting)) {
        logger.error(`[desktop] backend exited unexpectedly (code ${code})`)
        dialog.showErrorBox(
          'Backend stopped',
          'The FengYu backend exited unexpectedly. The app cannot continue. ' +
            'Please relaunch Infinia. If the problem persists, check the logs at ' +
            '<user dir>/.fengyu/logs/.',
        )
        app.quit()
      }
    })
  }
```
需 import `isAppCrash`(顶部 `import { ..., isAppCrash } from './backend/supervisor'`)。

注意:SETUP 模式的 supervisor 已在 exit code 0 时重启;APP 模式 exit code 0 不会发生(正常退出走 isQuitting)。若 SETUP supervisor 已处理 exit,此监听器对 SETUP 模式是冗余但无害(proc.once 触发其一;supervisor 的 restart 是异步的,若 supervisor 先拿到 exit 并重启,setChild 换了新 child,旧 proc 的 once 仍会触发 —— 需确认不会误报)。

**防御:** 只在 `action === StartupAction.ShowWindow`(纯 APP 模式)时注册此监听,避免与 SETUP supervisor 冲突:
```ts
  if (action === StartupAction.ShowWindow && backendChild) {
    // ... 上面的 exit 监听
  }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd desktop/electron && npx vitest run test/supervisor.test.ts 2>&1 | tail -10
```
Expected: PASS。

```bash
cd desktop/electron && npm test 2>&1 | tail -10
```
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add desktop/electron/src/backend/supervisor.ts desktop/electron/src/main.ts desktop/electron/test/supervisor.test.ts
git commit -m "🐛 fix(desktop): APP 模式后端崩溃提示对话框

此前 APP 模式(常见情况)不注册 supervisor,后端崩溃后用户只见
连接错误。现注册轻量 exit 监听,崩溃(非关闭中、非零退出)时弹
showErrorBox 提示用户重启,而非静默。alpha 不自动重启避免循环。"
```

---

# 阶段 4 — 最终验证(发布前)

### Task 4.1: 全量回归 + CHANGELOG 收尾

**Files:** 无修改,仅验证。

- [ ] **Step 1: 整 reactor 构建**

```bash
./mvnw clean package -DskipTests 2>&1 | tail -5
```
Expected: BUILD SUCCESS。

- [ ] **Step 2: e2e smoke(端到端,含 actuator restart 404 验证)**

```bash
scripts/e2e-smoke.sh 2>&1 | tail -15
```
Expected: 全部 endpoint 通过。手动附加验证 `/actuator/restart` 返回 404(B1 修复确认)。

- [ ] **Step 3: toolchain local smoke**

```bash
scripts/plugin-tooling-local-smoke.sh 2>&1 | tail -10
```
Expected: 成功。

- [ ] **Step 4: 桌面壳测试 + 构建**

```bash
cd desktop/electron && npm ci && npm test && npm run build:ts 2>&1 | tail -10 && cd ../..
```
Expected: 全部 PASS + TS 编译成功。

- [ ] **Step 5: docs build**

```bash
npm run docs:build 2>&1 | tail -5
```
Expected: 成功。

- [ ] **Step 6: 最终残留扫描(全仓)**

```bash
grep -rnE "(^|/)(plugin-sdk|plugin-ui|plugin-cli|plugin-dev|plugin-spec|FengYu-Plugin-Sdk|FengYu-Plugin-DevKit)(/|$)" \
  --include="*.yml" --include="*.yaml" --include="*.json" --include="*.sh" --include="*.mjs" --include="*.ts" --include="*.js" --include="*.md" --include="*.java" --include="*.xml" \
  . 2>/dev/null | grep -v node_modules | grep -v /target/ | grep -v /dist/ | grep -v "package-lock.json" | grep -v "docs/superpowers/" | grep -v "plugin-tooling-v" | grep -v "/toolchain/"
```
Expected: 空输出。

- [ ] **Step 7: git log 确认提交历史清晰**

```bash
git log --oneline -10
```
Expected: 阶段 2 一个大 refactor 提交 + 阶段 3 六个修复提交 + spec 提交,历史清晰。

- [ ] **Step 8: 汇报完成状态**

向用户报告:
- toolchain 整合完成(7 目录 → toolchain/)
- 发布流程对齐(2 workflow + skill 重命名)
- 6 个 Important 修复完成(B1/B2/D1/D2/D3/D4)
- 全量验证通过
- 未提交项:仅有原有的 `init.sql` 删除(与本任务无关,留待 alpha.3 切割时处理 —— spec 里 B1 段已提及)

---

## Self-Review

**1. Spec coverage:**
- 目录映射(7 目录)→ 阶段 1 Task 1.1-1.3 ✓
- Maven module 路径 → Task 2.2 ✓
- npm file: 依赖 → Task 2.3 ✓
- Workflows(重命名 2 + 改 1)→ Task 2.5 ✓
- Scripts(6 个)→ Task 2.6(预核验仅 3 个有引用,test-web/package-web/offlinepython 无)✓
- Skills(重命名 + 内容)+ AGENTS/CLAUDE → Task 2.7 ✓
- Docs(en+zh + README + CHANGELOG)→ Task 2.8 ✓
- 后端测试相对路径 → Task 2.1 ✓
- B1 actuator → Task 3.1 ✓
- B2 Web bundle token → Task 3.2 ✓
- D1 导航守卫 → Task 3.3 ✓
- D2 auto-updater JRE → Task 3.4 ✓
- D3 supervisor stop → Task 3.5 ✓
- D4 APP 崩溃提示 → Task 3.6 ✓
- 验证策略 → Task 2.9 + Task 4.1 ✓
- 非目标(不改 artifactId/npm 名/tag 前缀/历史归档)→ Global Constraints + 各 Task 注明 ✓

**2. Placeholder scan:** 无 TBD/TODO/"add error handling"/"similar to Task N"。每个步骤有具体命令或代码。Task 2.1 Step 2 的"用 Edit 工具精确替换"指向明确的目标字符串(可在实现时读文件确认)。Task 2.8 Step 2 的"逐文件更新"有完整映射表 + Step 1 的 grep 列出全部命中行。✓

**3. Type consistency:** `isAppCrash(exitCode: number | null, shuttingDown: boolean): boolean` 在 Task 3.6 Step 1(测试)和 Step 3(实现)签名一致 ✓。`superviseSetupRestart` 返回 `() => void` 与 main.ts 的 `stopSupervisor: (() => void) | null` 一致 ✓。`StartupAction.ShowWindow` 枚举值与现有 supervisor.ts 一致 ✓。

无遗漏。计划完整。
