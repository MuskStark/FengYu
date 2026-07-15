# 统一日志中心（宿主 + 插件）设计

**Date:** 2026-07-15
**Branch:** 4.0.0-FengYu
**Status:** Design approved; pending implementation plan

---

## 1. 背景

FengYu 4.0.0 已有两条日志路径，但缺少开发者可直接使用的日志查询能力：

1. 宿主通过 SLF4J + Logback 输出到控制台和 `.fengyu/logs/fengyu.log`。文本文件包含 DEBUG 及以上日志，按天滚动，保留 7 天，总大小上限 200 MB。
2. 插件后端运行在独立进程中。`PluginProcessManager` 将 stdout 用作换行分隔的 JSON-RPC 2.0 通道，将 stderr 读入宿主日志。目前 stderr 仅以 DEBUG 日志转记，单行缩略到 240 字符，无法在产品中按插件查询、实时查看或导出。

现有文本日志适合人工打开，但不适合作为稳定的查询数据源：异常堆栈是多行文本，字段没有明确边界，日志格式调整会破坏解析，插件来源也不是独立字段。

## 2. 目标

交付一个统一日志中心，用于开发者排查宿主与插件问题：

- 记录全部宿主日志与插件进程日志。
- 保留现有可人工阅读的 `fengyu.log`。
- 增加结构化滚动日志，支持可靠的历史查询、分页、筛选和导出。
- 在 Vue 应用中增加独立“日志中心”页面。
- 支持 SSE 实时日志、暂停显示、断线补查和自动滚动。
- 支持按时间、级别、来源、插件和关键词筛选。
- 在落盘和推送前统一脱敏常见凭据及插件运行时敏感环境变量。
- 两类日志合计保留 7 天，磁盘总量不超过 200 MB。
- 日志子系统故障不得影响宿主启动、插件调用或 JSON-RPC 通道。

## 3. 非目标

- 不在界面中动态调整宿主或插件日志级别。
- 不将日志写入业务数据库，也不依赖数据库查询日志。
- 不实现远程日志采集、云端上传或多设备聚合。
- 不提供正则查询、全文索引或日志分析报表。
- 不允许插件 stdout 承载普通日志；stdout 继续专用于 JSON-RPC。
- 不承诺恢复脱敏前的原始敏感内容。

## 4. 方案选择

采用“双日志文件”方案：

- `.fengyu/logs/fengyu.log`：现有的人类可读文本日志。
- `.fengyu/logs/fengyu-events.jsonl`：日志中心使用的结构化 JSON Lines 日志。

两者都使用按时间和大小滚动的策略，单个活动文件达到 25 MB 时提前滚动，归档仍按日期组织并保留 7 天。为落实“合计不超过 200 MB”，两个滚动策略分别设置 `totalSizeCap=100MB`。当前活动文件也计入日志目录治理；启动时及滚动后执行一次目录配额检查，必要时优先删除最旧归档，确保整个日志目录不持续超过 200 MB。

没有选择直接解析 `fengyu.log`，因为多行异常和未来格式变化会使查询不稳定；没有只保留 JSONL，因为这会破坏开发者直接打开文本日志排查的现有习惯。

## 5. 总体架构

```text
宿主 SLF4J 日志 ───────────────┐
                              │
插件 stderr ─ PluginProcessManager ─ SLF4J + pluginId/source 标记
                              │
                              v
                    Logback logging event
                              │
                    LogRedactionService
                    （消息 + 异常脱敏）
                              │
             ┌────────────────┼─────────────────┐
             v                v                 v
       fengyu.log       fengyu-events.jsonl   LogEventBus
       人类可读文本       结构化滚动文件         有界实时总线
                                                │
                                                v
                                         /api/logs/stream

fengyu-events.jsonl + 滚动归档
             │
             v
       LogQueryService
             │
       ┌─────┴────────┐
       v              v
  /api/logs      /api/logs/export
```

结构化文件是历史查询的权威来源；实时总线只负责低延迟推送，不承担持久化。文本日志是人工排查和结构化日志故障时的兜底。

## 6. 日志事件模型

结构化文件每行是一个完整 JSON 对象：

```json
{
  "timestamp": "2026-07-15T16:25:31.482+08:00",
  "sequence": 18421,
  "level": "ERROR",
  "source": "PLUGIN",
  "pluginId": "com.example.excel",
  "logger": "plugin.com.example.excel.stderr",
  "thread": "plugin-com.example.excel-stderr",
  "message": "Workbook processing failed",
  "exception": "java.lang.IllegalStateException: ...\n\tat ...",
  "truncated": false
}
```

字段语义：

| 字段 | 规则 |
|---|---|
| `timestamp` | 带时区的 ISO-8601 时间，使用日志事件产生时间，不使用查询时间 |
| `sequence` | 当前宿主进程内单调递增的序号，用于同毫秒排序和 SSE 去重；重启后可重新开始 |
| `level` | `TRACE`、`DEBUG`、`INFO`、`WARN`、`ERROR` |
| `source` | `HOST` 或 `PLUGIN` |
| `pluginId` | 插件日志必填；宿主日志为 `null` |
| `logger` | 原始 logger 名称 |
| `thread` | 产生日志的线程名 |
| `message` | 参数展开后的最终消息，已经脱敏 |
| `exception` | 完整异常类型、消息和堆栈，已经脱敏；无异常时为 `null` |
| `truncated` | 插件输出因单条上限被截断时为 `true` |

结构化编码必须正确转义换行和控制字符，保证“一行一个事件”。

## 7. 来源归属

### 7.1 宿主日志

默认事件标记为：

```text
source=HOST
pluginId=null
```

现有宿主 logger 不需要逐个修改。

### 7.2 独立插件进程

`PluginProcessManager` 启动 stderr 读取线程时，为每行构造插件日志事件：

```text
logger=plugin.<pluginId>.stderr
source=PLUGIN
pluginId=<manifest id>
```

插件 stderr 默认映射为 `INFO`，避免正常插件诊断内容只能在 DEBUG 模式下看到。若插件采用标准前缀：

```text
[TRACE] message
[DEBUG] message
[INFO] message
[WARN] message
[ERROR] message
```

宿主解析并映射到对应级别，同时从最终消息中移除前缀。没有前缀时使用 INFO。该约定向后兼容现有插件，且不要求引入语言相关 SDK。

单条 stderr 原始输入最多接受 64 KiB UTF-8 内容；超过上限时保留前 64 KiB，设置 `truncated=true`。读取器不得无限等待没有换行的输出：达到上限即形成一条事件并继续消费后续内容。

### 7.3 非 JSON stdout

stdout 继续专用于 JSON-RPC。无法解析为 JSON 的行、响应 ID 不匹配及协议错误记录为：

```text
source=PLUGIN
pluginId=<manifest id>
logger=plugin.<pluginId>.protocol
level=WARN
```

非 JSON stdout 不作为普通 INFO 日志接受，避免开发者误以为可以安全混用 stdout 与 RPC。

### 7.4 旧版 PluginLogger 兼容

4.0.0 运行时插件以独立进程为主。仍通过 `FengYu-Api` `PluginLogger` 进入宿主 SLF4J 的日志，只有 logger 名称符合 `plugin.<pluginId>.*` 时才能可靠归属为插件；其余记录为 HOST。设计不使用包名前缀猜测插件 ID，避免错误归属。

## 8. 脱敏

### 8.1 单一脱敏入口

新增 `LogRedactionService`，被文本编码、结构化编码和实时事件转换共同使用。任何文件或 SSE 客户端都不得收到脱敏前的消息或异常文本。

Logback 会早于 Spring Bean 完整初始化，因此底层脱敏注册表必须是可由 Logback 直接构造、线程安全且无 Spring 依赖的运行时组件；`LogRedactionService` 是 Spring 侧的管理门面。文本 encoder、JSONL encoder 和实时 appender 共享同一个底层注册表，而不是各自维护规则副本。

脱敏范围包括：

- `password`、`passwd`、`pwd`
- `token`、`access_token`、`refresh_token`
- `apiKey`、`api_key`
- `secret`、`client_secret`
- `Authorization: Bearer ...`
- JDBC/URI 中的用户名密码片段
- 插件运行时实际注入的敏感环境变量值

替换文本统一为 `***REDACTED***`。键名保留，以便开发者知道哪个配置参与了日志。

### 8.2 动态敏感值

`PluginRuntimeEnvironmentService` 在为插件构造环境变量时，将敏感值按插件 ID 注册到脱敏服务。`PluginProcessManager` 停止插件、替换 Worker 或宿主关闭时注销相应值。

现有 `SensitiveValueRedactor` 的职责合并到统一脱敏组件，避免插件 stderr 先使用一套 `<redacted>` 规则、其他日志再使用另一套 `***REDACTED***` 规则。迁移完成后删除该专用类。

注册表只保存在内存中，不写入日志或数据库。动态值匹配忽略长度小于 4 的内容，避免短字符串导致大面积误脱敏。动态值使用引用计数或按插件集合管理，防止两个插件共享同一值时提前移除。

### 8.3 异常与失败

脱敏器自身不得使用常规日志记录待脱敏的输入。若某条规则执行失败，输出保守替换为 `[LOG REDACTION FAILED]`，而不是回退到原文。

## 9. 写入、轮转和实时总线

### 9.1 文本文件

保留文件名和基本格式：

```text
2026-07-15 16:25:31.482 ERROR [thread] logger - message
```

插件日志在消息前增加可见来源：

```text
[plugin:com.example.excel] Workbook processing failed
```

异常堆栈紧随事件写入。文本编码器同样调用脱敏服务。

### 9.2 结构化文件

使用自定义 JSONL encoder 或 appender 写入 `fengyu-events.jsonl`，归档名为：

```text
fengyu-events.2026-07-15.jsonl.gz
```

结构化文件滚动失败时不得抛回业务线程。错误通过 Logback status/控制台做限频报告；文本文件继续工作。

### 9.3 有界实时总线

新增进程内 `LogEventBus`：

- Appender 向一个有界队列发布已经脱敏的 `LogEvent`。
- 发布操作不得阻塞业务线程。
- 队列满时按 TRACE/DEBUG、INFO、WARN/ERROR 的顺序淘汰低级别事件。
- 无法保留事件时累计丢弃计数；恢复后产生一条合成 WARN 事件，例如 `Log stream dropped 318 low-priority events`。
- 合成告警不得递归进入同一失败路径。
- 每个 SSE 客户端拥有独立、较小的发送缓冲。慢客户端缓冲耗尽时关闭该连接，由前端通过历史查询补齐。

Logback appender 同样可能早于 Spring 上下文初始化。它通过一个静态、无阻塞的 `LogEventBridge` 发布；Spring 启动后由 `LogEventBus` 注册接收器，启动前没有接收器时只落盘、不缓存实时事件。这样不会因日志初始化顺序形成循环依赖，也不会为启动早期日志建立无界内存缓存；这些早期记录仍可通过历史查询读取。

文件写入不依赖实时总线是否健康。即使所有 SSE 客户端都断开，日志仍正常落盘。

## 10. 历史查询

新增 `LogQueryService`，只读取 `fengyu-events.jsonl` 及其 `.gz` 归档。查询从最新文件向最旧文件扫描，在满足数量上限后停止。

支持条件：

- `from` / `to`：时间范围
- `levels`：一个或多个级别
- `source`：`HOST` 或 `PLUGIN`
- `pluginId`
- `query`：对 `logger`、`message` 和 `exception` 做不区分大小写的普通子串匹配
- `cursor`：继续读取更旧记录的不透明游标
- `limit`：默认 500，最小 1，最大 2,000

结果按 `timestamp DESC, sequence DESC` 返回。游标包含足以恢复扫描位置的信息，但 API 将其视为不透明字符串；游标因日志轮转失效时返回明确的 `cursorExpired=true`，前端重新从当前筛选条件第一页加载。

查询约束：

- 默认时间范围是最近 24 小时。
- 最长查询范围为 7 天。
- 空关键词不触发文本匹配。
- 损坏 JSON 行被跳过并计数，查询继续处理其他记录。
- 单次请求设置读取字节上限，达到上限时返回已有结果和下一页游标，避免大查询长期占用请求线程。

本阶段不建立持久化索引。200 MB 上限与 7 天窗口使逆序文件扫描可接受；如实际数据证明查询延迟不可接受，再独立设计索引。

## 11. REST API

全部接口位于现有 Token 鉴权之后，只允许已认证的本机客户端调用。

### 11.1 `GET /api/logs`

示例：

```text
GET /api/logs?source=PLUGIN&pluginId=com.example.excel&levels=WARN,ERROR&query=workbook&limit=500
```

响应：

```json
{
  "items": [],
  "nextCursor": "opaque-or-null",
  "cursorExpired": false,
  "skippedCorruptRecords": 0
}
```

### 11.2 `GET /api/logs/stream`

SSE 事件名为 `log`，data 为单个 `LogEvent` JSON。连接建立后只发送新事件，不重放历史。前端携带最后收到的 `timestamp` 和 `sequence` 调用历史接口补查断线窗口。浏览器 `EventSource` 不能设置请求头，因此沿用现有 AI SSE 约定，通过 `?token=` 传递启动 Token；`TokenAuthFilter` 只对 `/api/logs/stream` 增加该 query-token 例外，其他日志 API 继续要求 `X-FengYu-Token` 请求头。

SSE 还可发送：

- `overflow`：服务端或客户端缓冲发生丢弃，前端立即补查历史。
- `heartbeat`：保持连接并帮助检测失联。

### 11.3 `GET /api/logs/sources`

返回来源与当前已安装插件：

```json
{
  "sources": ["HOST", "PLUGIN"],
  "plugins": [
    { "id": "com.example.excel", "name": "Excel Splitter" }
  ]
}
```

已卸载插件的历史日志仍可通过手工 `pluginId` 参数查询；下拉列表仅保证显示当前安装插件。

### 11.4 `GET /api/logs/export`

接受与历史查询相同的筛选条件，并增加：

- `format=text|jsonl`，默认 `text`
- `mode=filtered|archive`，默认 `filtered`

`filtered` 流式导出匹配记录，不在内存中构造完整文件。`archive` 返回日志目录中现有文本和结构化滚动文件的 ZIP。导出应用与查询相同的 7 天范围、鉴权和脱敏保证。

## 12. 前端日志中心

新增路由 `/logs`，在侧边栏底部导航中增加“日志”入口。

### 12.1 页面结构

顶部工具栏：

- 时间范围：最近 15 分钟、1 小时、24 小时、7 天、自定义
- 级别多选
- 来源：全部、宿主、插件
- 插件选择器
- 关键词输入框
- 实时开关/暂停按钮
- 导出当前结果
- 下载全部日志

日志列表使用虚拟滚动，只渲染可见行。每行显示：

- 时间
- 级别色标
- 来源或插件名
- logger 简称
- 单行消息摘要

点击一行展开完整 logger、线程、原始时间、完整消息、异常堆栈、截断标记，并提供复制按钮。

### 12.2 实时行为

- 页面首次进入先加载历史，再建立 SSE。
- 位于列表顶部且未暂停时，新日志自动插入并保持自动滚动。
- 用户主动滚动查看旧日志时暂停自动滚动，但连接继续接收。
- 暂停期间事件缓存在浏览器有界队列中，并显示“有 N 条新日志”。
- 点击提示后批量合并并回到最新位置。
- 浏览器缓冲达到上限时丢弃最旧的界面缓存并触发一次历史补查提示，不影响服务端文件。
- SSE 重连成功后，用最后时间戳和序号补查缺口，再恢复实时流。

“清空”只清空当前浏览器列表，不删除磁盘日志。页面不提供删除日志文件的操作。

### 12.3 状态与国际化

日志筛选状态保存在日志页面 store 中；离开页面再返回时保留本次会话的筛选条件，但不写入全局设置。所有新增文案同时加入英文和中文资源。

## 13. 错误处理

| 场景 | 行为 |
|---|---|
| 结构化文件不可写 | 限频报告，文本日志继续；应用和插件调用不失败 |
| 文本文件不可写 | 控制台与结构化文件继续；应用不失败 |
| 两种文件均不可写 | 控制台继续，日志中心实时流尽力工作；状态栏/日志页显示存储不可用 |
| 归档中存在损坏 JSON 行 | 跳过该行，响应返回损坏计数 |
| SSE 客户端过慢 | 发送 `overflow` 后关闭连接，前端重连并补查 |
| 日志事件总线过载 | 优先丢弃低级别事件，恢复后生成聚合 WARN |
| 插件输出超长 stderr | 64 KiB 截断并设置 `truncated=true` |
| 插件 stderr 读取失败 | 记录插件归属 WARN；Worker 生命周期按现有规则处理 |
| 日志目录超过配额 | 删除最旧归档；不删除当前活动文件 |
| 查询游标对应文件已被轮转删除 | 返回 `cursorExpired=true`，前端刷新第一页 |
| 脱敏处理失败 | 用 `[LOG REDACTION FAILED]` 替代整条内容，不回退原文 |

## 14. 主要组件边界

后端建议新增或调整以下职责单一的组件：

| 组件 | 职责 |
|---|---|
| `LogEvent` | 稳定的结构化日志 DTO |
| `LogSource` | `HOST` / `PLUGIN` 枚举 |
| `LogRedactionService` | 静态规则和动态敏感值注册、脱敏 |
| `PluginLogContext` | 将插件 ID/source 放入 MDC，并确保作用域结束后清理 |
| `StructuredLogEncoder` | 将 Logback event 转成脱敏 JSONL |
| `RedactingTextEncoder` | 生成脱敏的人类可读文本 |
| `LogEventBusAppender` | 将脱敏事件非阻塞发布到实时总线 |
| `LogEventBus` | 有界广播、背压和丢弃统计 |
| `LogQueryService` | 逆序读取当前及归档 JSONL，筛选和分页 |
| `LogExportService` | 流式导出筛选结果或日志归档 ZIP |
| `LogStorageHealth` | 暴露文本/结构化文件可用状态及配额状态 |
| `LogController` | 历史、来源、导出接口 |
| `LogStreamController` | SSE 生命周期、心跳和慢客户端处理 |

`PluginProcessManager` 只负责给插件输出附加上下文、解析可选级别前缀和执行单条长度限制，不承担文件写入或查询逻辑。

## 15. 测试策略

### 15.1 后端单元测试

- 常见键值、Bearer Token、连接串、异常堆栈脱敏。
- 动态敏感值注册、共享值引用和插件停止后的注销。
- HOST/PLUGIN 来源和 pluginId 归属。
- stderr 级别前缀解析、默认 INFO、64 KiB 截断。
- 非 JSON stdout 和响应 ID 不匹配归属为插件协议 WARN。
- JSONL 对换行、Unicode、异常堆栈的正确编码。
- 历史筛选、排序、游标分页、最大 limit、损坏记录跳过。
- 日志配额清理只删除最旧归档，不删除活动文件。
- 实时总线低级别优先丢弃、聚合告警和慢订阅者断开。

### 15.2 后端集成测试

- 使用临时日志目录加载测试 Logback 配置，验证同一事件写入文本和 JSONL。
- 验证文件与 SSE 中均不存在测试密码、Token 和插件环境变量值。
- MockMvc 验证历史、来源和导出接口需要 Token。
- SSE 客户端接收实时事件，断开后可通过历史接口补查。
- 结构化 appender 失败不会使 REST 请求或插件调用失败。

### 15.3 前端测试

- API 参数和响应类型。
- 筛选条件变化会重载历史并重建正确的实时视图。
- 暂停时累计新日志数量，恢复时合并。
- 用户滚动查看旧日志时停止自动滚动。
- SSE overflow/重连触发历史补查。
- 行展开、异常显示、复制和导出操作。
- 中文、英文文案及窄屏布局。

### 15.4 端到端测试

测试插件向 stderr 输出 INFO、WARN、ERROR、异常堆栈、超长文本和敏感字段；同时触发一条宿主日志。验证：

1. 文本文件、JSONL 和日志中心均能看到正确记录。
2. 宿主与插件归属正确，插件筛选只返回目标插件。
3. 文件、SSE、查询和导出中敏感值均已脱敏。
4. stdout 的非 JSON 文本显示为插件协议 WARN，而不破坏后续 RPC 响应。
5. 停止并重启插件后，日志继续归属到同一 pluginId。

## 16. 文档更新

实现时同步更新：

- `README.md`：日志中心入口、日志目录和保存策略。
- `docs/` 与 `docs/zh/`：开发者如何打印插件日志；stdout/stderr 约定；级别前缀；脱敏注意事项。
- `CHANGELOG.md`：统一日志中心、结构化日志与插件日志采集。
- `AGENTS.md`：将旧的“插件日志统一写入 fengyu.log”描述更新为 4.0 双文件和独立进程约定，并明确 JavaFX 时代说明不是当前运行架构。

## 17. 验收标准

- 日志中心可查看宿主和所有已运行插件的历史与实时日志。
- 可按时间、级别、来源、插件 ID 和普通关键词筛选。
- 历史查询默认 500 条，单次不超过 2,000 条，最长范围 7 天。
- 插件 stderr 不再只以宿主 DEBUG 文本存在，而是具有明确 pluginId 和级别。
- 插件 stdout 继续保持 JSON-RPC 专用，协议污染有明确 WARN。
- 文本、JSONL、SSE、查询和导出都使用相同脱敏结果。
- 两类日志均保留 7 天，日志目录持续受 200 MB 总配额约束。
- 可导出当前筛选结果，也可下载完整日志归档。
- 慢 SSE 客户端、损坏日志记录、结构化写入失败和日志队列过载均不影响主业务。
- 不提供日志级别修改和磁盘日志删除功能。

## 18. 实施顺序建议

1. 日志事件模型、来源上下文和脱敏服务。
2. 文本/JSONL 编码与 Logback 双文件配置。
3. 插件 stderr/protocol 归属和输出限制。
4. 实时事件总线与 SSE。
5. 历史查询、来源和导出 API。
6. 前端 API/store、日志中心页面和侧边栏入口。
7. 端到端测试、配额验证和文档更新。
