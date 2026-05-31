# AI → Excel Splitter 集成设计

## 概述

AI聊天作为前端界面，驱动Excel拆分引擎。AI通过5个独立工具逐步引导用户完成4步向导（文件选择→分析→模式选择→执行）。

## 核心组件

### 1. 共享状态

- `SplitConfig`单例由`ExcelSplitterPlugin`管理，生命周期与插件`onActivate()/onDeactivate()`一致
- AI工具通过`ExcelSplitterPlugin.getSharedSplitConfig()`访问同一实例

### 2. 五个AI工具

| 工具名 | 参数 | 返回 |
|--------|------|------|
| `excel_analyze` | `filePath: string` | `{sheets: [{name, rowCount, headers}]}` 或 error |
| `excel_configure` | `mode: BY_SHEET\|BY_COLUMN\|COMPLEX, sheets?: string[], splitSheet?: string, splitColumn?: string, taskId?: string` | `{configured: true, summary}` 或 error |
| `excel_execute` | `outputDir: string, filePrefix?: string` | `{outputFiles: [path], totalRows, totalSheets}` 或 error |
| `excel_query` | 无 | `{sourceFile, mode, sheets, configuredSheets, outputDir}` |
| `excel_cancel` | 无 | `{cancelled: true}` |

### 3. 三种拆分模式支持

#### BY_SHEET 模式

- `excel_configure(mode="BY_SHEET", sheets=["Sheet1", "Sheet3"])`
- `SplitConfig.selectedSheets = [sheets]`, `SplitConfig.mode = BY_SHEET`
- `excel_execute` → 遍历`selectedSheets`，每sheet输出一个文件

#### BY_COLUMN 模式

- `excel_configure(mode="BY_COLUMN", splitSheet="Sheet1", splitColumn="部门")`
- `SplitConfig.splitSheet = splitSheet`, `SplitConfig.splitColumn = displayName`, `SplitConfig.splitColumnIndex = index`
- `excel_execute` → 按`splitColumnIndex`列值分组，每组一个文件

#### COMPLEX 模式

- `excel_configure(mode="COMPLEX", taskId="uuid-from-db")`
- `SplitConfig.complexTaskId = taskId`
- `excel_execute` → 读取`ComplexSplitConfigEntity`，按每行配置分别执行
  - 普通行：`headerIndex`行作表头，按`columnIndex`列分组拆分
  - 全表复制行：`headerIndex==-1 && columnIndex==-1` → 复制整个sheet到所有输出文件

### 4. 执行流程

```
用户: "帮我拆分这个excel"
  → AI调用 excel_analyze
    → SplitConfig.sourceFile = path, 调用 ExcelSplitter.analyze(path)
    → 返回 sheets 列表

BY_SHEET示例:
用户: "按sheet拆分，只选Sheet1和Sheet3"
  → AI调用 excel_configure(mode="BY_SHEET", sheets=["Sheet1","Sheet3"])
    → SplitConfig.mode=BY_SHEET, selectedSheets=[...]

BY_COLUMN示例:
用户: "按第3列拆分"
  → AI调用 excel_configure(mode="BY_COLUMN", splitSheet="Sheet1", splitColumn="部门")
    → SplitConfig.mode=BY_COLUMN, splitColumnIndex=2

COMPLEX示例:
用户: "用 complex_task_001 配置来拆分"
  → AI调用 excel_configure(mode="COMPLEX", taskId="complex_task_001")
    → SplitConfig.mode=COMPLEX, complexTaskId="..."

用户: "输出到桌面"
  → AI调用 excel_execute(outputDir=desktop)
    → 根据当前mode调用ExcelSplitter.split(config)
    → 返回结果

用户可随时调用 excel_query 查看当前配置状态
     excel_cancel 取消任务
```

### 5. 静默后台执行

- 工具内部在后台线程执行POI操作，主线程不阻塞
- 工具结果通过`AiStreamCallback.onToolResult()`返回给AI
- 错误显示为红色工具卡片

### 6. 多任务支持

- 同一时刻仅一个Excel拆分任务
- `AtomicBoolean hasRunningTask` 保护临界区

## 工具注册

在`SwissKitJApp.start()`或`AiChatPlugin.onActivate()`中注册5个工具到`AiService`：
- `ExcelAnalyzeTool`
- `ExcelConfigureTool`
- `ExcelExecuteTool`
- `ExcelQueryTool`
- `ExcelCancelTool`

每个工具构造函数传入`ExcelSplitterPlugin`引用以访问共享`SplitConfig`。

## 错误处理

- 文件不存在/不可读 → 红色卡片 + 具体错误
- 拆分失败 → 红色卡片 + 异常信息
- 取消成功 → 绿色卡片 + `Task cancelled`