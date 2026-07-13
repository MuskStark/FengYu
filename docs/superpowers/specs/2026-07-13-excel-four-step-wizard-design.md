# Excel 四步拆分向导与 Worker 协议修复设计

- **日期**：2026-07-13
- **状态**：已批准
- **目标版本**：4.0.0

## 1. 背景

4.0.0 的 Excel 官方插件已经可以安装并显示 iframe UI，但当前界面把所有控件堆在单页，
没有恢复 3.2.0 `StepWizard` 的分步体验。同时调用
`POST /api/plugin-runtime/fan.summer.excel/invoke` 时可能返回无具体信息的 HTTP 500。

服务端日志证明 500 发生在 `PluginProcessManager.Worker.invoke()`：宿主从 worker stdout
读取一行后直接按 JSON 解析，而该行包含非协议文本，触发
`JsonParseException: Unexpected character ('-')`。当前 newline-delimited JSON-RPC 契约没有
在 SDK 侧独占 stdout，也没有在宿主侧隔离第三方 worker 的非协议输出。

## 2. 范围

### 2.1 目标

1. 用 iframe 内原生 HTML/CSS/JavaScript 恢复 3.2.0 的四步 Excel 拆分向导。
2. 完整支持 `BY_SHEET`、`BY_COLUMN`、`COMPLEX` 三种模式。
3. Web 使用临时输出目录并下载 ZIP；Tauri 桌面使用原生目录选择器。
4. 复杂模式支持多条规则、删除/清空规则和显式“整表复制”开关。
5. 保证 worker stdout 只承载 JSON-RPC，并让宿主防御性处理非协议输出。
6. API 向前端返回可操作的具体错误信息，不再只表现为无说明的 500。

### 2.2 非目标

- 不把 Vue/Vuetify 运行时打入 Excel `.fyp`。
- 不把 Excel 专属步骤抽象成宿主通用向导组件。
- 不恢复 3.2.0 的 JavaFX 控件、动画或数据库持久化规则。
- 不改变三种拆分模式的核心 Excel 引擎算法。

## 3. 方案选择

采用原生 iframe 四步向导。它直接重构 `OfficialPlugins/packages/excel/ui/` 下的静态资源，
不增加插件运行时依赖，符合当前 `.fyp` 进程与 UI 双隔离架构。

未采用以下方案：

- 插件内 Vue/Vuetify：需要重复打包运行时，增加包体和构建复杂度。
- 宿主通用向导：当前只有 Excel 一个明确消费者，会把领域逻辑提前耦合进宿主。

## 4. 四步交互

### 4.1 第一步：选择文件与分析

- 支持 `.xlsx`、`.xls`、`.xlsm`。
- 桌面端调用 `files.open`，Web 端使用上传端点，最终都得到宿主授权的 `FileRef`。
- 选择文件后立即调用 `analyze(session, sourceFile)`。
- 分析期间禁用下一步并展示加载状态。
- 成功后展示文件名、工作表数量和工作表名称；失败时保留在本步骤并允许重新选择。
- 只有分析成功才能进入第二步。

### 4.2 第二步：拆分设置

使用三个互斥模式卡片，并根据选择显示专属表单。

#### BY_SHEET

- 默认选中所有分析出的工作表。
- 支持逐项选择、全选和清空。
- 至少选择一个工作表才能继续。
- 提交 `configure` 参数：`mode=BY_SHEET`、`selectedSheets`。

#### BY_COLUMN

- 先选择工作表，再从分析结果中选择表头列。
- 切换工作表时清空旧列选择。
- 提交 `mode=BY_COLUMN`、`splitSheet`、`splitColumn`、`splitColumnIndex`。
- 工作表和列均已选择才能继续。

#### COMPLEX

- 每条规则包含工作表、表头行号、拆分列号和规则类型。
- 普通拆分规则要求表头行号、拆分列号均为正整数。
- “整表复制”通过显式开关设置；开启后隐藏数字输入，并写入
  `headerIndex=-1`、`columnIndex=-1`。
- 支持添加、删除单条和清空全部；页面持续显示规则数量和规则摘要。
- 至少存在一条规则才能继续。
- 提交 `mode=COMPLEX`、`complexEntries`，每条包含
  `fieldName/sheetName/headerIndex/columnIndex`。

第二步中的选择先保存在前端草稿；点击下一步时一次性调用 `configure`，避免每次控件变化
都触发 worker RPC。

### 4.3 第三步：确认与输出

- 展示源文件、工作表数量、拆分模式及模式专属配置摘要。
- BY_SHEET 展示选中工作表与预计文件数。
- BY_COLUMN 展示目标工作表、列名、列位置。
- COMPLEX 展示全部普通规则和整表复制规则。
- 桌面端调用 `files.outputDirectory` 打开原生目录选择器，并显示授权目录。
- Web 端调用同一能力，由宿主创建临时输出目录；UI 明确提示完成后下载 ZIP。
- 获得有效 `DirectoryRef` 后才能进入第四步。

### 4.4 第四步：执行与结果

- 进入本步骤后自动调用 `split(session, sourceFile, outputDir)`，防止重复点击。
- 展示准备、执行、成功、失败四种状态；当前协议没有进度事件，因此不伪造百分比。
- 成功时展示文件数量与文件名列表。
- Web 端自动调用 `files.export(outputDir)` 下载 ZIP，并保留手动重新下载按钮。
- 桌面端展示输出目录和结果列表，不额外下载。
- 失败时保留 session 和配置，允许直接重试或返回前一步修改。

### 4.5 导航与重置

- 所有步骤提供上一步；前三步提供下一步。
- 重新选择源文件时生成新 session，并清除分析结果、模式草稿、复杂规则和输出目录。
- 从第四步返回不会自动再次执行；只有显式“重试”才重新调用 split。

## 5. JSON-RPC 修复

### 5.1 Worker SDK stdout 隔离

`JsonRpcWorker.run()` 在启动时保存原始 stdout 作为协议输出流，然后把全局 `System.out`
重定向到 stderr。之后 handler 和第三方库即使调用 `System.out`，也不会污染 JSON-RPC；
协议响应仍写入保存的原始流。

`run(InputStream, OutputStream)` 保持可注入、可测试，不修改调用方传入的输出流。

### 5.2 宿主防御性读取

`PluginProcessManager.Worker.invoke()` 不再假定读取到的第一行就是响应：

1. 按行读取 stdout。
2. 非 JSON 行记为 worker 诊断并继续读取。
3. JSON-RPC ID 不匹配的行记为协议异常并继续寻找当前请求 ID。
4. EOF、超时或有效 error response 转换为包含 plugin ID、method 和具体消息的异常。

外层已有 60 秒调用超时，防御性循环沿用该上限，不另建无限等待。

worker stderr 不再静默丢弃；宿主以 plugin ID 为上下文记录到 DEBUG/WARN 日志，但不得把
用户文件内容写入日志。

### 5.3 HTTP 错误响应

插件 RPC 异常由全局异常处理器转换为稳定 JSON：

```json
{ "success": false, "error": "<可读错误>" }
```

前端桥接保留该消息并显示在当前步骤。参数错误使用 400；worker 启动、协议或执行错误使用
500，但必须携带安全的具体错误文本。

## 6. 文件与状态数据流

```text
File picker/upload
  -> FileRef
  -> analyze(session, sourceFile)
  -> sheets/headers
  -> configure(session, mode-specific draft)
  -> DirectoryRef (native directory or web temp directory)
  -> split(session, sourceFile, outputDir)
  -> result files
  -> web ZIP export | desktop directory result
```

`PluginProcessManager.resolveRefs()` 继续在进入 worker 前把 `FileRef`/`DirectoryRef` 转换为已授权
绝对路径，Excel worker 不感知 Web/Tauri 差异。

## 7. 错误与校验

- 分析、配置、输出授权和拆分分别保存错误状态，错误发生在哪一步就显示在哪一步。
- BY_SHEET 空选择、BY_COLUMN 缺工作表或列、COMPLEX 空规则均在前端阻止继续；worker 后端
  仍执行同等校验，不能信任 iframe 输入。
- 数字字段拒绝零、负数和非整数；整表复制是唯一允许 `-1/-1` 的路径。
- worker error response 保留业务消息，宿主不得把其误判为协议损坏。
- 重试不会重复添加复杂规则，也不会静默切换输出目录。

## 8. 测试策略

### 8.1 Worker SDK

- handler 写入 `System.out` 时，协议输出仍只有合法 JSON-RPC。
- handler 正常结果、业务异常和未知方法保持现有契约。

### 8.2 宿主运行时

- stdout 在有效响应前包含非 JSON 行时仍能取得匹配响应。
- ID 不匹配、EOF、error response 和调用超时返回明确错误。
- stderr 日志被消费且不会阻塞 worker。

### 8.3 Excel 后端

- 三种模式的 configure 参数和校验。
- COMPLEX 普通规则及整表复制 `-1/-1`。
- analyze 和 split 的 worker 级 JSON-RPC 往返测试，覆盖曾触发 500 的真实调用路径。

### 8.4 前端与浏览器闭环

- 静态生命周期测试覆盖四步结构、模式字段和错误/重试状态。
- 浏览器验证 BY_SHEET、BY_COLUMN、COMPLEX 的导航和校验。
- Web 完整闭环：上传 → 分析 → 配置 → 临时目录 → 拆分 → ZIP 导出。
- 桌面文件授权路径由 host API/worker 集成测试覆盖，并在 Tauri 环境执行手工冒烟测试。

## 9. 完成标准

- Excel 页面在任意时刻只显示当前步骤内容。
- 三种模式均可完成配置，复杂规则支持显式整表复制。
- Web 可下载结果 ZIP，桌面可写入用户选择目录。
- 原问题中的 analyze/split RPC 不再产生 stdout JSON 解析 500。
- 所有新增回归测试、Maven reactor、前端构建和浏览器闭环验证通过。
