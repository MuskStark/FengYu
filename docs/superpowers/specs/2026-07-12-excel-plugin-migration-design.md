# Excel 拆分工具迁移设计：3.2.0 内置工具 → 4.0.0 官方插件

- **日期**：2026-07-12
- **状态**：设计已批准，待生成实现计划
- **作者**：Claude Code + 人类搭档

## 1. 背景与目标

3.2.0（JavaFX）里 Excel 拆分是**内置工具**（`SwissKit/.../buildintool/excelsplitter/`），
含 `ExcelSplitter` 引擎 + `StepWizard` UI + 6 个 AI 工具，COMPLEX 模式用 H2/MyBatis 落库。

4.0.0 是 **headless web + 桌面（Tauri）** 应用：Java 是 loopback Spring Boot 服务，UI 是
Vue 3.5 + Vuetify 微前端。本设计把 Excel 拆分迁移为 **官方插件 `plugin-excel`**，遵循 4.0.0
的 `FengYuPlugin` v2 契约 + 微前端 + Spring AI `@Tool` 模型。

**同时**：把本迁移中定义的文件 I/O 方式固化为 **FengYu 插件文件 I/O 标准 v1**，供以后所有
文件类插件遵循。

### 目标
1. Excel 拆分以官方插件形式落地，存放于新建的 `OfficialPlugins/` 聚合父目录。
2. 保留三种拆分模式：BY_SHEET / BY_COLUMN / COMPLEX。
3. web 与桌面双端体验：web 走上传 + zip 下载；桌面走原生对话框直读写本地目录。
4. 6 个 AI 工具重写为 Spring AI `@Tool` bean。
5. 沉淀可复用的插件文件 I/O 标准。

### 非目标
- 不保留 JavaFX UI 细节（`StepWizard` 等）。
- COMPLEX 配置不再落库（改内存会话）。
- 不做 Tauri 签名打包 / 内嵌 JRE（属 Phase F-prod）。

## 2. 目录与构建结构

```
OfficialPlugins/                       # 新聚合父目录 (packaging=pom, parent=FengYu-parent)
  pom.xml                              # <modules>: plugin-markdown, plugin-excel
  plugin-markdown/                     # 由现有顶层位置 git mv 迁入（决策 3）
  plugin-excel/
    pom.xml                            # parent=OfficialPlugins-parent
    ui-src/                            # Vue 3.5 + Vuetify 微前端源码（照搬 plugin-markdown 脚手架）
    src/main/java/fan/summer/fengyu/plugin/excel/
      ExcelPlugin.java                 # @Component implements FengYuPlugin
      ExcelSplitter.java               # 核心引擎（v3.2.0 迁移）
      SplitConfig.java / ExcelUtil.java / FileNameUtil.java / NoModelDataListener.java
      ExcelSessionStore.java           # session -> SplitConfig（含 COMPLEX 内存多配置）
      ai/ ExcelAnalyzeTool ... (6× @Tool bean, 重写)
    src/main/resources/ui/excel/index.js   # 构建产物
```

- root `pom.xml` `<modules>` 增加 `OfficialPlugins`，移除顶层 `plugin-markdown`（迁入后）。
- `OfficialPlugins/pom.xml` 为 `packaging=pom`，父指向 `FengYu-parent`，子模块 markdown + excel。
- `FengYu/pom.xml` 保留对 `plugin-markdown` 的依赖，新增对 `plugin-excel` 的 compile 依赖
  （groupId `fan.summer.fengyu.plugin`）；两者 `@Component` 被现有 `fan.summer.fengyu` 组件扫描拾取。
- `plugin-excel` 依赖：`FengYu-Api`(provided)、`spring-context`(provided)、`fesod-sheet`、
  `poi-ooxml`、`commons-compress`（zip 打包，或用 JDK `java.util.zip`）。
- 依赖版本 `fesod 2.0.1-incubating` / `poi 5.4.1` 已在 root pom `dependencyManagement`。

## 3. FengYu 插件文件 I/O 标准 v1

**统一原则：插件后端 `invoke` 永远只接收绝对路径，不感知 web/desktop。** 差异全部由前端与
host 层吸收。

### 3.1 Web 路径 — host 通用文件端点（新增 `PluginFileController`）

| 端点 | 说明 |
|---|---|
| `POST /api/plugins/{id}/files` (multipart) | 存到 host 会话工作区 `${tmp}/fengyu/plugin-workspace/{id}/{session}/in/`，返回 `{session, files:[{name, path}]}`（path 为服务端绝对路径）。首次调用时 session 由后端生成并返回。 |
| `GET /api/plugins/{id}/files/archive?session=&dir=out` | 把会话 `out/` 目录打成 `results.zip` 流式返回，浏览器 save-as。 |
| `DELETE /api/plugins/{id}/files?session=` | 主动清理会话工作区。 |

- `session` 为不透明 UUID，前端透传。`dir` 仅允许白名单值（`in`/`out`），拒绝其它。
- 所有端点受现有 `TokenAuthFilter`（`X-FengYu-Token`）保护。
- **不改动 `FengYuPlugin` 契约**：文件经独立通用端点进出，`invoke` 只收绝对路径。

### 3.2 桌面路径 — host 注入的桌面门面

`PluginContext` 增加可选字段：

```ts
interface PluginContext {
  // ...现有字段
  desktop?: {                                    // 仅 Tauri 下存在；浏览器为 undefined
    pickFile(filters?: { name: string; extensions: string[] }[]): Promise<string | null>
    pickDirectory(): Promise<string | null>
  }
}
```

- `@tauri-apps/plugin-dialog` 依赖与 capability 只在 host（`frontend/` + `desktop/src-tauri`）。
- 插件 bundle 零 Tauri 依赖，`vue` external 保持不变，web/desktop 单份产物通吃。

### 3.3 统一后端契约

`invoke("split", {session, sourceFile, outputDir, ...})` 收绝对路径：
- **Web**：`sourceFile` 来自上传返回；`outputDir` = 会话 `out/`（前端按标准约定填充）。
- **桌面**：`sourceFile` / `outputDir` 均来自原生对话框选定的本地绝对路径。

插件逻辑不分叉——只从入参 `Path` 读写。

### 3.4 会话 / 工作区模型

host 管理 `${tmp}/fengyu/plugin-workspace/{pluginId}/{session}/{in,out}/`。
**TTL = 24 小时 + 进程退出清扫**（决策 2）：后台定时扫描删除超时会话；JVM shutdown hook 清目录。

### 3.5 信任模型（须实现校验）

- 后端仅绑 `127.0.0.1` + token 鉴权 + 本地优先；绝对路径读写与桌面文件访问同一信任级别
  （用户本机），沿用 v3.2.0 行为。
- web 上传/archive 严格限定在 per-plugin 会话工作区内：`session` 校验为合法 UUID；解析后的
  路径必须落在工作区根下（防 `..` 穿越）。
- 桌面 `split` 有意写用户经原生对话框选定的目录（用户显式授权）。
- 上传限制：单文件大小上限（如 100MB）、扩展名白名单（`.xlsx` / `.xls`）。

## 4. plugin-excel 后端

### 4.1 引擎迁移（近乎零改写）

`ExcelSplitter` / `ExcelUtil` / `FileNameUtil` / `NoModelDataListener` / `SplitConfig` 从
v3.2.0 原样迁移，仅：
- 改包名到 `fan.summer.fengyu.plugin.excel`。
- `ExcelSplitter.complexSplit()` 去掉 `DatabaseInit` + MyBatis mapper 依赖，改从入参
  （session 内的 COMPLEX 配置 `List<ComplexSplitEntry>` POJO）读取。
- 删除遗留死代码：`FengYu/.../database/entity/excel/ComplexSplitConfigEntity.java` +
  `.../repository/excel/ComplexSplitConfigRepository.java`（4.0.0 里无 mapper，是死代码）。

### 4.2 ExcelSessionStore

`ConcurrentHashMap<String session, SplitConfig>`。COMPLEX 的多配置作为 `SplitConfig` 的一个
字段（`List<ComplexSplitEntry>`，纯 POJO，含 `fieldName/sheetName/headerIndex/columnIndex`；
`headerIndex==-1 && columnIndex==-1` 表示整表复制）。

### 4.3 ExcelPlugin.invoke actions

| action | args | 返回 |
|---|---|---|
| `analyze` | `{session, sourceFile}` | `{success, summary, sheets:{name:{colIndex:header}}}` |
| `configure` | `{session, mode, ...}` | `{success, summary}`（写 session 的 SplitConfig；COMPLEX 传多配置数组） |
| `split` | `{session, sourceFile, outputDir}` | `{success, summary, fileCount, files:[...]}` |

`descriptor()`：id `fan.summer.excel`，name「Excel 拆分」，category **FILE**（决策 1，见 §6），
icon `file-excel`，iconStyle GREEN/TEAL，version `4.0.0`，uiEntry `/plugin-ui/excel/index.js`，
supportsAi `true`，source OFFICIAL。

## 5. plugin-excel 前端（Vue 微前端）

照搬 plugin-markdown 脚手架（`ui-src/` → `resources/ui/excel/index.js`，`vue` external，
CSS 内联，共享 host Vuetify）。用向导式多步 UI：选文件 → 分析 → 选模式/配置 → 执行 → 结果。

双路径分支（同一 bundle）：
- `if (ctx.desktop)`：`pickFile()` / `pickDirectory()` 拿本地绝对路径 → `invoke('analyze'|'split', {...绝对路径})`；结果已在本地目录（可选再触发 archive 下载）。
- `else`（浏览器）：`<input type=file>` → `POST .../files`（multipart）拿 session + 服务端路径 →
  `invoke('split', {session, sourceFile, outputDir: 会话out})` → `GET .../files/archive` 触发 zip 下载。

前端需要 `apiBase` + `token` 发原始 multipart/download `fetch`。**host 改动**：`PluginContext`
增加只读 `apiBase: string`、`token: string`（`frontend/src/mf/loader.ts` 类型 +
`PluginView.vue` 注入 + 插件侧 `pluginUi.ts` 类型对齐）。遵循 host locale/theme，不自带语言切换。

## 6. 新增 FILE 分类（决策 1）

`ToolCategory` 枚举新增 `FILE("file", "category.file")`。改动点：
- `FengYu-Api/.../ToolCategory.java`：加枚举值。
- `PluginController.iconFor()`：`case "file" -> "🗄"`（或合适符号）。
- 前端 i18n：`frontend/src/i18n/en.json` `"file": "File"`、`zh.json` `"file": "文件"`。
- 侧栏分类由后端 `/api/plugin-categories` 动态驱动，无需前端硬编码列表。

## 7. AI 工具重写（6 个）

旧 `AiTool`/`AiToolResult`/`AiToolParam` 契约在 4.0.0 已不存在。全部改写为
`@Component implements FengYuTool` + `@Tool` 方法，由现有 `AiToolDiscoveryConfig` 自动聚合
（零配置编辑）。操作 `ExcelSessionStore` 的「当前活动会话」（沿用旧插件单会话语义）；AI 场景下
文件按 `filePath` 绝对路径传入（用户给本地路径）。返回 `{success, summary, ...}` JSON 约定。

| 工具 | 名称 | 职责 |
|---|---|---|
| ExcelAnalyzeTool | `excel_analyze` | 读结构（sheet/表头） |
| ExcelConfigureTool | `excel_configure` | 配置拆分模式 |
| ExcelExecuteTool | `excel_execute` | 执行拆分 |
| ExcelQueryTool | `excel_query` | 查询会话状态 |
| ExcelCancelTool | `excel_cancel` | 取消/重置会话 |
| ExcelComplexConfigTool | `excel_complex_config` | 设置 COMPLEX 多配置 |

## 8. Tauri 接线（桌面原生对话框）

- `frontend/` 引入 `@tauri-apps/plugin-dialog`；仅在 Tauri 环境（`window.__FENGYU_TOKEN__` 或
  Tauri 探测）下为 `PluginContext.desktop` 填充实现，否则 `undefined`。
- `desktop/src-tauri`：加 dialog 插件 + capability 授权（`tauri.conf.json` / capabilities）。
- CLAUDE.md 记录 Tauri fs/dialog 目前未接入——本设计首次接入 dialog。

## 9. 验证

- 后端单测：三模式拆分、`analyze` 表头解析、`ExcelSessionStore`、路径穿越校验。
- 端点测试：multipart 上传、archive zip、`DELETE` 清理、session 校验。
- 构建：reactor 顺序 API → OfficialPlugins(markdown, excel) → FengYu；`plugin-excel/ui-src` npm build。
- E2E：浏览器上传→拆分→下载 zip 闭环；桌面原生目录直写闭环（Tauri webview 下 zip save-as 行为验证，若被拦截退路为 Tauri 保存对话框，属渐进增强）。

## 10. 已定取舍

1. `OfficialPlugins/` 聚合父目录；plugin-markdown **迁入**（决策 3）。
2. 双路径、同一引擎：web 上传+zip / 桌面原生目录直写。
3. 文件端点归 host 通用 `PluginFileController`，`FengYuPlugin` 契约零改动，沉淀为标准。
4. COMPLEX 配置存内存会话，删遗留 JPA entity/repo。
5. AI 工具重写为 Spring AI `@Tool` bean。
6. 引擎逻辑不变，仅改包名 + 去 DB 直连。
7. 新增 FILE 分类，Excel 插件归入（决策 1）。
8. 工作区 TTL = 24h + 进程退出清扫（决策 2）。
