# API Reference

Detailed reference for SwissKitJ's core interfaces, enums, components, and internal APIs.

## SwissKitJPlugin Interface

**Location**: `SwissKitJ-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java`

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getId()` | `String` | Globally unique identifier (reverse-domain) |
| `getName()` | `String` | Display name |
| `getDescription()` | `String` | One-line description |
| `getCategory()` | `ToolCategory` | Sidebar category |
| `getVersion()` | `String` | Version string |
| `getMdiIcon()` | `String` | Material Design Icons name, e.g. `file-excel` |
| `getIconStyle()` | `IconStyle` | CSS class + color (default: BLUE) |
| `getType()` | `ToolType` | BUILTIN or PLUGIN (default) |
| `createView()` | `Node` | Main JavaFX UI node (called once, cached) |
| `onActivate()` | `void` | Tool brought to foreground |
| `onDeactivate()` | `void` | Tool moved to background |
| `onUnload()` | `void` | Plugin being unloaded |

## Enums

### ToolCategory

**Location**: `SwissKitJ-Api/.../ToolCategory.java`

| Constant | `getId()` | Description |
|----------|-----------|-------------|
| `DEV` | `"dev"` | Developer utilities |
| `TEXT` | `"text"` | Text processing |
| `IMAGE` | `"image"` | Image processing |
| `NET` | `"net"` | Network tools |
| `OTHER` | `"other"` | Miscellaneous |

### ToolType

**Location**: `SwissKitJ-Api/.../ToolType.java`

| Constant | `getId()` | `isBuiltin()` |
|----------|-----------|---------------|
| `BUILTIN` | `"builtin"` | `true` |
| `PLUGIN` | `"plugin"` | `false` |

### IconStyle

**Location**: `SwissKitJ-Api/.../IconStyle.java`

| Constant | CSS Class | Color (RGB) |
|----------|-----------|-------------|
| `BLUE` | `"ic-blue"` | `rgb(99, 130, 255)` |
| `PURPLE` | `"ic-purple"` | `rgb(160, 110, 255)` |
| `TEAL` | `"ic-teal"` | `rgb(40, 210, 140)` |
| `AMBER` | `"ic-amber"` | `rgb(255, 185, 50)` |
| `RED` | `"ic-red"` | `rgb(255, 100, 100)` |
| `PINK` | `"ic-pink"` | `rgb(245, 100, 160)` |
| `GRAY` | `"ic-gray"` | `rgb(200, 200, 210)` |

## StepWizard

**Location**: `SwissKitJ-Api/.../component/StepWizard.java`

| Method | Return Type | Description |
|--------|-------------|-------------|
| `addStep(String title, Node content, BooleanSupplier canProceed)` | `void` | Add a step |
| `build()` | `void` | Must call after all `addStep()` |
| `setOnStepChanged(StepChangeListener)` | `void` | Step change listener |
| `goTo(int index)` | `void` | Navigate to step (0-based) |
| `getCurrentStep()` | `int` | Current step index |
| `isLastStep()` | `boolean` | Is current step the last |

### StepChangeListener

```java
void onStepChanged(int from, int to, int total);
```

## Plugin Logging

### LoggerFactory

**Location**: `SwissKitJ-Api/.../log/LoggerFactory.java`

```java
PluginLogger getLogger(Class<?> clazz)
PluginLogger getLogger(String name)
```

### PluginLogger

**Location**: `SwissKitJ-Api/.../log/PluginLogger.java`

SLF4J-style `{}` placeholder API with levels: `trace`, `debug`, `info`, `warn`, `error`.

Each level has overloads: `(String)`, `(String, Object)`, `(String, Object, Object)`, `(String, Object...)`, `(String, Throwable)`.

## Themes

**Location**: `SwissKitJ-Api/.../theme/Themes.java`

```java
static final String COMMON_CSS = "/css/zhiflow-common.css"
static String commonStylesheetUrl()
static void applyTo(Scene scene)
```

## PluginContext

**Location**: `SwissKitJ-Api/.../PluginContext.java`

Associates external plugins with their dedicated `ClassLoader` and provides thread-context-classloader (TCCL) switching for safe plugin method invocation. The host wraps every call to a plugin method (`createView()`, `onActivate()`, etc.) with `runWith`/`callWith`, and wraps the plugin node's `EventDispatcher` via `wrapEvents` so that background threads spawned from event handlers inherit the correct TCCL.

```java
static void register(SwissKitJPlugin plugin, ClassLoader loader)
static void unregister(SwissKitJPlugin plugin)
static ClassLoader getClassLoader(SwissKitJPlugin plugin)
static void runWith(SwissKitJPlugin plugin, Runnable action)
static <T> T callWith(SwissKitJPlugin plugin, Callable<T> action) throws Exception
static void wrapEvents(SwissKitJPlugin plugin, Node node)
```

Plugin keys are held via `WeakReference` so stale entries are eligible for GC even if the host fails to call `unregister`.

## Database Layer

### DatabaseInit

**Location**: `SwissKit/.../database/DatabaseInit.java`

H2 at `.swisskit/swisskit.db`, schema from `init.sql`, MyBatis XML mappers in `resources/mapper/`.

```java
static void init()
static SqlSession getSqlSession()
static SqlSessionFactory getSqlSessionFactory()
```

### Mapper Interfaces

| Mapper | Key Methods |
|--------|-------------|
| `EmailAddressBookMapper` | `insert`, `selectAll` |
| `EmailTagMapper` | `insert`, `update`, `selectAll` |
| `EmailSentLogMapper` | `insert`, `selectAll` |
| `ComplexSplitConfigMapper` | `insert`, `selectByTaskId`, `deleteByTaskId` |
| `AppSettingMapper` | `selectByKey`, `upsert` |
| `PluginManagerMapper` | `selectAll` |
| `EmailArchiveMapper` | `insert`, `selectAll` |
| `MenuOrderMapper` | `selectAll`, `updateOrder` |

## Entity Classes

### EmailAddressBookEntity

| Field | Type | Description |
|-------|------|-------------|
| `id` | `Integer` | Primary key |
| `emailAddress` | `String` | Email address |
| `nickname` | `String` | Display name |
| `tags` | `String` | JSON array of tag IDs |

### EmailTagEntity

| Field | Type | Description |
|-------|------|-------------|
| `id` | `Long` | Primary key |
| `tag` | `String` | Tag name |

### EmailSentLogEntity

| Field | Type | Description |
|-------|------|-------------|
| `id` | `Long` | Primary key |
| `to` / `cc` / `bcc` | `String` | Recipients |
| `subject` | `String` | Email subject |
| `content` | `String` | Email body |
| `attachment` | `String` | Attachment info |
| `sendTime` | `Date` | Timestamp |
| `isSuccess` | `boolean` | Delivery status |

### ComplexSplitConfigEntity

| Field | Type | Description |
|-------|------|-------------|
| `id` | `Long` | Primary key |
| `taskId` | `String` | Task identifier |
| `fieldName` | `String` | Original filename |
| `sheetName` | `String` | Sheet name |
| `headerIndex` | `Integer` | 1-based header row (-1 = copy all) |
| `columnIndex` | `Integer` | 1-based split column (-1 = copy all) |

### EmailArchiveEntity

| Field | Type | Description |
|-------|------|-------------|
| `id` | `Long` | Primary key |
| `messageId` | `String` | Email message ID |
| `subject` | `String` | Email subject |
| `fromAddr` | `String` | Sender address |
| `toAddr` | `String` | Recipient addresses |
| `sentTime` | `Date` | Send timestamp |
| `folder` | `String` | Archive folder |

## Callback Interfaces

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
