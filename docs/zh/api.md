# API 参考

SwissKitJ 核心接口、枚举、组件和内部 API 的详细参考。

## SwissKitJPlugin 接口

**位置**：`SwissKitJ-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java`

| 方法 | 返回类型 | 描述 |
|------|----------|------|
| `getId()` | `String` | 全局唯一标识符（反向域名） |
| `getName()` | `String` | 显示名称 |
| `getDescription()` | `String` | 一行描述 |
| `getCategory()` | `ToolCategory` | 侧边栏分类 |
| `getVersion()` | `String` | 版本字符串 |
| `getIconText()` | `String` | 表情符号或单个字符 |
| `getIconStyle()` | `IconStyle` | CSS 类 + 颜色（默认：BLUE） |
| `getType()` | `ToolType` | BUILTIN 或 PLUGIN（默认） |
| `createView()` | `Node` | 主 JavaFX UI 节点（调用一次，被缓存） |
| `onActivate()` | `void` | 工具进入前台 |
| `onDeactivate()` | `void` | 工具移至后台 |
| `onUnload()` | `void` | 插件正在卸载 |

## 枚举

### ToolCategory

**位置**：`SwissKitJ-Api/.../ToolCategory.java`

| 常量 | `getId()` | 描述 |
|------|-----------|------|
| `DEV` | `"dev"` | 开发者工具 |
| `TEXT` | `"text"` | 文本处理 |
| `IMAGE` | `"image"` | 图片处理 |
| `NET` | `"net"` | 网络工具 |
| `OTHER` | `"other"` | 杂项 |

### ToolType

**位置**：`SwissKitJ-Api/.../ToolType.java`

| 常量 | `getId()` | `isBuiltin()` |
|------|-----------|---------------|
| `BUILTIN` | `"builtin"` | `true` |
| `PLUGIN` | `"plugin"` | `false` |

### IconStyle

**位置**：`SwissKitJ-Api/.../IconStyle.java`

| 常量 | CSS 类 | 颜色 (RGB) |
|------|--------|-------------|
| `BLUE` | `"ic-blue"` | `rgb(99, 130, 255)` |
| `PURPLE` | `"ic-purple"` | `rgb(160, 110, 255)` |
| `TEAL` | `"ic-teal"` | `rgb(40, 210, 140)` |
| `AMBER` | `"ic-amber"` | `rgb(255, 185, 50)` |
| `RED` | `"ic-red"` | `rgb(255, 100, 100)` |
| `PINK` | `"ic-pink"` | `rgb(245, 100, 160)` |
| `GRAY` | `"ic-gray"` | `rgb(200, 200, 210)` |

## StepWizard

**位置**：`SwissKitJ-Api/.../component/StepWizard.java`

| 方法 | 返回类型 | 描述 |
|------|----------|------|
| `addStep(String title, Node content, BooleanSupplier canProceed)` | `void` | 添加步骤 |
| `build()` | `void` | 所有 `addStep()` 调用后必须调用 |
| `setOnStepChanged(StepChangeListener)` | `void` | 步骤更改监听器 |
| `goTo(int index)` | `void` | 导航到步骤（从 0 开始） |
| `getCurrentStep()` | `int` | 当前步骤索引 |
| `isLastStep()` | `boolean` | 当前步骤是否最后一步 |

### StepChangeListener

```java
void onStepChanged(int from, int to, int total);
```

## 插件日志

### LoggerFactory

**位置**：`SwissKitJ-Api/.../log/LoggerFactory.java`

```java
PluginLogger getLogger(Class<?> clazz)
PluginLogger getLogger(String name)
```

### PluginLogger

**位置**：`SwissKitJ-Api/.../log/PluginLogger.java`

SLF4J 风格的 `{}` 占位符 API，带级别：`trace`、`debug`、`info`、`warn`、`error`。

每个级别有重载方法：`(String)`、`(String, Object)`、`(String, Object, Object)`、`(String, Object...)`、`(String, Throwable)`。

## 主题

**位置**：`SwissKitJ-Api/.../theme/Themes.java`

```java
static final String COMMON_CSS = "/css/swisskit-common.css"
static String commonStylesheetUrl()
static void applyTo(Scene scene)
```

## 数据库层

### DatabaseInit

**位置**：`SwissKit/.../database/DatabaseInit.java`

H2 数据库位于 `.swisskit/swisskit.db`，Schema 来自 `init.sql`，MyBatis XML mapper 在 `resources/mapper/`。

```java
static void init()
static SqlSession getSqlSession()
static SqlSessionFactory getSqlSessionFactory()
```

### Mapper 接口

| Mapper | 关键方法 |
|--------|----------|
| `EmailAddressBookMapper` | `insert`、`selectAll` |
| `EmailTagMapper` | `insert`、`update`、`selectAll` |
| `EmailSentLogMapper` | `insert`、`selectAll` |
| `ComplexSplitConfigMapper` | `insert`、`selectByTaskId`、`deleteByTaskId` |
| `AppSettingMapper` | `selectByKey`、`upsert` |
| `PluginManagerMapper` | `selectAll` |
| `MenuOrderMapper` | `selectAll`、`updateOrder` |

## 实体类

### EmailAddressBookEntity

| 字段 | 类型 | 描述 |
|------|------|------|
| `id` | `Integer` | 主键 |
| `emailAddress` | `String` | 电子邮件地址 |
| `nickname` | `String` | 显示名称 |
| `tags` | `String` | 标签 ID 的 JSON 数组 |

### EmailTagEntity

| 字段 | 类型 | 描述 |
|------|------|------|
| `id` | `Long` | 主键 |
| `tag` | `String` | 标签名称 |

### EmailSentLogEntity

| 字段 | 类型 | 描述 |
|------|------|------|
| `id` | `Long` | 主键 |
| `to` / `cc` / `bcc` | `String` | 收件人 |
| `subject` | `String` | 邮件主题 |
| `content` | `String` | 邮件正文 |
| `attachment` | `String` | 附件信息 |
| `sendTime` | `Date` | 时间戳 |
| `isSuccess` | `boolean` | 投递状态 |

### ComplexSplitConfigEntity

| 字段 | 类型 | 描述 |
|------|------|------|
| `id` | `Long` | 主键 |
| `taskId` | `String` | 任务标识符 |
| `fieldName` | `String` | 原始文件名 |
| `sheetName` | `String` | 工作表名称 |
| `headerIndex` | `Integer` | 1-based 表头行（-1 = 复制全部） |
| `columnIndex` | `Integer` | 1-based 拆分列（-1 = 复制全部） |

## 回调接口

### ExcelAnalysisCallback

```java
void onSuccess(Map<String, Map<Integer, String>> result);
void onFailure(Exception e);
```

### QueryAllEmailInfoCallBack

```java
void onSuccess(List<EmailAddressBookEntity> addresses);
void onFailure(Exception e);
```