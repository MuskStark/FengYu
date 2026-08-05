# Windows Job Object 进程隔离设计

- **日期**: 2026-08-05
- **状态**: 设计已确认,待实现
- **范围**: 给 `ProcessSandbox` 加 Windows Job Object 后端,解决 Windows 上的进程树终止可靠性(#2 残留)并提供进程层隔离原语(#4 的第一步)

## 1. 背景与现状(重新审计)

源自一次 Windows 兼容性审计的 4 个问题。重新核对 live 代码后的**当前实际状态**:

| # | 问题 | 原结论 | **当前实际状态** |
|---|---|---|---|
| 1 | 插件 worker 在 Windows 硬失败(`IllegalStateException`) | 🔴 高 | **已部分修** — `unsandboxedPlugins` 开关(`bf321c4f`)让 Windows 用户经 `unrestricted()` 跑插件;默认 fail-closed 是有意安全策略 |
| 2 | Windows 进程树回收不可靠,孤儿 worker 锁 DB 文件 | 🔴 高 | **已基本修** — SDK stdin-EOF watchdog(`7a5d0862`)+ Electron `taskkill /T` 是可靠主路径;**残留**:`PluginProcessManager.Worker.close()` 和 `CommandExecuteTool.terminate()` 仍用不可靠的 `ProcessHandle.descendants()` |
| 3 | 风险命令正则无 Windows 覆盖 | 🟡 中(安全) | **moot** — Windows 上 `commandPotentiallyUnsafe` 对每条命令 early-return `true`(`ChatToolApprovalGate.java:113`),正则永不执行;正则只在 macOS/Linux 跑,而 Unix 命令集在那里是正确的 |
| 4 | Windows 无原生沙箱,裸跑 | 🟡 中 | **仍然存在** — `detect()` 返回 `NONE`;无 Job Object / AppContainer / JNA 任何隔离原语 |

**本设计只处理 #4 + #2 残留。** #1 已有开关;#3 确认 moot。

## 2. 目标与非目标

### 目标
- **#4 第一步**:给 Windows 加一个真实的进程层隔离原语 —— Job Object(`JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`),让 host JVM 死亡时内核保证杀掉整个 worker 进程树。
- **#2 残留**:把 `PluginProcessManager.Worker.close()` 和 `CommandExecuteTool.terminate()` 在 Windows 上的主终止路径从不可靠的 `ProcessHandle.descendants()` 换成 `TerminateJobObject`(内核保证)。

### 非目标(MVP 不做)
- **文件系统/网络隔离** —— Job Object 不提供 OS 级文件系统限制(那需要 AppContainer + ACL,属方案 B 范围)。本设计的 Job 只解决进程树生命周期。这是诚实的已知差距,文档标注。
- AppContainer / 受限令牌 / ACL
- 改 `ChatToolApprovalGate` 正则(#3 moot)
- 改 SDK stdin-EOF watchdog(已可靠,Job 是补充层)
- 改 Electron `tree-kill`(已用 `taskkill /T`,已正确)

## 3. 已确认的关键决策

| 决策点 | 选定 |
|---|---|
| 方案 | A — Job Object 作为可靠的进程树终止原语(非完整沙箱) |
| 接入方式 | `ProcessSandbox` 加第三个后端 `WINDOWS_JOB`,与 `BUBBLEWRAP`/`SANDBOX_EXEC`/`NONE` 平级 |
| JNA 依赖 | `net.java.dev.jna:jna` 加在 `FengYu` 模块(父 pom 不管理,需显式版本);非 Windows 上 `WindowsJobSandbox` 类永不加载 |
| 进程模型 | Job 句柄由 host JVM 持有;host 死亡 → 句柄关闭 → 内核 `KILL_ON_JOB_CLOSE` 杀整树 |
| `Launch` 改造 | 加第三个字段 `onStarted`(Consumer<Process>);`WINDOWS_JOB` 的回调做 create+assign,其他后端为 null |
| assign 失败处理 | **抛错(显式失败)**,优于静默降级到不可靠的 `descendants()` |
| 与 `unsandboxedPlugins` 关系 | 开关 OFF = Job 隔离(进程层);开关 ON = `unrestricted()` 无任何隔离(最宽松,完全裸跑)。语义清晰化 |

## 4. 架构

### 4.1 运行时拓扑

```
host JVM (FengYu)
  ├── ProcessSandbox.detect() → Windows 上返回 WINDOWS_JOB
  ├── ProcessSandbox.plugin()/command()
  │     └── wrap() 对 WINDOWS_JOB 返回原始命令 + onStarted 回调
  ├── PluginProcessManager.start() / CommandExecuteTool.execute()
  │     ├── ProcessBuilder.start()
  │     ├── launch.onStarted().accept(process)  ← Job create + assign
  │     └── Worker/terminate 持有 job 句柄
  └── host 退出 / worker.close()
        └── WindowsJobSandbox.terminate(job) → TerminateJobObject(内核杀整树)
                                                 (host JVM 崩溃时:句柄关闭 → KILL_ON_JOB_CLOSE)
```

### 4.2 与现有沙箱层的关系

Job Object **不包装命令行**(不像 bwrap/sandbox-exec 在命令前加前缀)。它在 `process.start()` **之后**把句柄 assign 进去。因此 `wrap()` 对 `WINDOWS_JOB` 返回原始命令(`new Launch(raw, backend, onStarted)`),assign 交给调用方在启动后通过 `onStarted` 回调做。

## 5. JNA Win32 绑定(`WindowsJobSandbox`)

**文件:** `FengYu/src/main/java/fan/summer/fengyu/security/WindowsJobSandbox.java`

### 5.1 Kernel32 JNA 接口(内部)

```java
interface Kernel32 extends com.sun.jna.Library {
    HANDLE CreateJobObjectW(Pointer lpJobAttributes, String lpName);
    boolean SetInformationJobObject(HANDLE hJob, int infoClass,
            Pointer lpJobObjectInfo, int lpJobObjectInfoLength);
    boolean AssignProcessToJobObject(HANDLE hJob, HANDLE hProcess);
    boolean TerminateJobObject(HANDLE hJob, int uExitCode);
    boolean CloseHandle(HANDLE h);
    HANDLE OpenProcess(int access, boolean inherit, int pid);
}
```

### 5.2 关键常量

- `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000` — 核心:句柄关闭时杀整个 Job
- `JobObjectExtendedLimitInformation` info class = 9
- `PROCESS_SET_QUOTA = 0x0100`,`PROCESS_TERMINATE = 0x0001`
- 结构体:`IO_COUNTERS`(8字节×4字段)+ `JOB_OBJECT_LIMIT_INFORMATION`(含 IO_COUNTERS + 限制标志 + 进程/作业句柄上限)

### 5.3 公开方法

```java
/** JNA 是否可加载(非 Windows 返回 false 且不抛)。 */
static boolean isAvailable()
/** 创建 Job + 配置 KILL_ON_JOB_CLOSE,返回句柄。 */
long createAndConfigureJob()
/** 把 process assign 进 job。失败抛 IllegalStateException(显式失败)。 */
void assign(long jobHandle, Process process)
/** 主动终止整个 job(正常关闭路径)。 */
void terminate(long jobHandle)
/** 关闭句柄(触发 KILL_ON_JOB_CLOSE,如果还有进程)。 */
void close(long jobHandle)
```

**assign 的进程句柄获取:** `OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, false, pid)`,pid 从 `process.toHandle().pid()` 取。

**类加载守卫:** 整个类只在 Windows 由 `WINDOWS_JOB` 路径触达。`isAvailable()` 用 `try { Class.forName("com.sun.jna.Platform"); return Platform.isWindows(); } catch (UnsatisfiedLinkError | NoClassDefFoundError) { return false; }` 守卫,确保非 Windows 不触发 JNA 链接。

## 6. ProcessSandbox 改造

### 6.1 Backend 枚举

```java
enum Backend { BUBBLEWRAP, SANDBOX_EXEC, WINDOWS_JOB, NONE }
```

### 6.2 detect() Windows 分支

```java
if (os.contains("win")) {
    return WindowsJobSandbox.isAvailable() ? Backend.WINDOWS_JOB : Backend.NONE;
}
```
防御性:`isAvailable()` 为 false 时降级到 `NONE`(回到现状),不崩溃。

### 6.3 Launch 加 onStarted 字段

```java
public record Launch(List<String> command, Backend backend,
                     java.util.function.Consumer<Process> onStarted) {
    public Launch {
        command = List.copyOf(command);
        // onStarted 可为 null(非 WINDOWS_JOB 后端)
    }
}
```
现有调用点构造 `new Launch(command, backend)` 需改为 `new Launch(command, backend, null)`。

### 6.4 wrap() WINDOWS_JOB 分支

```java
if (backend == Backend.WINDOWS_JOB) {
    long[] jobHolder = {0};
    java.util.function.Consumer<Process> onStarted = process -> {
        jobHolder[0] = WindowsJobSandbox.createAndConfigureJob();
        WindowsJobSandbox.assign(jobHolder[0], process);
    };
    return new Launch(raw, backend, onStarted);
}
```
(具体 job 句柄的持有与回收由调用方 `Worker`/`CommandExecuteTool` 负责;`onStarted` 只做 create+assign,句柄返回方式见 §7 的调用方改造。)

## 7. 调用方改造(#2 残留修复核心)

### 7.1 PluginProcessManager.start() + Worker.close()

**start()(~206-215):**
```java
Process process = builder.start();
// NEW: 若 Launch 带 onStarted 回调,执行 Job create+assign
if (launch.onStarted() != null) {
    launch.onStarted().accept(process);
}
```
Worker 需持有 job 句柄(通过 `onStarted` 回调内回传,或 Worker 字段记录)。**`onStarted` 回调签名调整为** `Consumer<Process>` 返回 job 句柄 —— 设计为实现侧把句柄存入 Worker 字段(详见 plan,通过让 `onStarted` 写入一个 `long[]` 或由调用方传入句柄容器)。

**Worker.close()(~489-503)新顺序:**
1. **优先** `WindowsJobSandbox.terminate(job)`(Windows 内核杀整树,可靠)— 仅当 job 句柄非 0
2. **回退** 现有 `process.destroy()` + `killDescendants()`(job 句柄为 0 时,即非 Windows 或降级)
3. 关闭 job 句柄 `WindowsJobSandbox.close(job)`

`killDescendants()` 保留作为非 Windows / `NONE` 后端的回退路径,不删除。

### 7.2 CommandExecuteTool.execute() + terminate()

**execute()(~96):** 同样 `process = builder.start()` 后调 `launch.onStarted()`;工具持有 job 句柄。

**terminate()(~180-189):** Windows 上优先 `TerminateJobObject`,回退 `destroyForcibly` + `descendants()`。

### 7.3 onStarted 的句柄回传设计

`Consumer<Process>` 不返回值。句柄回传方案:`Worker` / `CommandExecuteTool` 持有一个 `long[] jobHolder = {0}`,`Launch` 的 `onStarted` 写入它。或更简洁:让 `ProcessSandbox` 提供 `Launch.onStarted()` 返回 `Consumer<Process>`,调用方在回调里把句柄存入自己的字段。具体实现细节(字段 vs 容器)在 plan 阶段定,不锁定。

## 8. 与 unsandboxedPlugins 开关的关系

| 平台 | 开关 OFF | 开关 ON |
|---|---|---|
| Windows | `detect()`=WINDOWS_JOB → worker 进 Job(进程层隔离,可靠终止);**无文件系统/网络隔离**(已知差距) | `fullAccess` → `unrestricted()` → NONE,**无 Job、无任何隔离**(完全裸跑) |
| macOS/Linux | 原生 sandbox-exec/bwrap(不变);开关 UI 不显示 | 开关后端拒绝(`SettingsController` 已硬性拒绝有沙箱平台) |

**开关语义清晰化:** OFF = Job 隔离(进程层);ON = 无隔离。

## 9. 测试策略

### 层 1:非 Windows 可跑(主战场)
- `ProcessSandboxTest`:验证 `detect()` 在非 Windows 不返回 `WINDOWS_JOB`;验证 `WINDOWS_JOB` 的 `wrap()` 返回原始命令 + 非空 `onStarted`;验证 `Launch.onStarted()` 为 null 时调用方安全跳过。
- `Launch` 回调契约测试。

### 层 2:仅 Windows 可跑(`@EnabledOnOs(OS.WINDOWS)`,CI 跳过)
- `WindowsJobSandboxTest`:创建 Job → assign 一个 `timeout 5` 进程 → `close()` 句柄 → 断言进程被杀。
- `isAvailable()` 在非 Windows 返回 false 且不抛。
- 文档标注 Windows CI 需跑这类测试。

### 隔离 seam
`WindowsJobSandbox` 的 JNA 调用通过内部 `Kernel32` 接口;非 Windows 用 `isAvailable()` 守卫,Windows 跑真实 kernel32。不引入 mock JNA(句柄/指针无法有效 mock)。

## 10. 降级路径

| 场景 | 行为 |
|---|---|
| 非 Windows | `detect()` 不返回 `WINDOWS_JOB`;`WindowsJobSandbox` 类从不加载 |
| Windows + JNA 缺失(理论上不会发生,编译期依赖) | `isAvailable()` catch `UnsatisfiedLinkError`/`NoClassDefFoundError` → `detect()` 降级 `NONE` → 回现状 |
| Windows + Job 创建/assign 失败 | `onStarted` 回调抛异常 → worker 启动失败(显式报错,优于静默降级到不可靠的 `descendants()`) |

## 11. 文档更新

- **`docs/en/plugins/` + `docs/zh/plugins/`** 进程隔离/Windows 兼容章节:平台支持矩阵更新,标注 Windows 现有 Job Object 进程隔离(进程层),文件系统/网络隔离为已知差距。
- **`Settings.vue` 的 `unsandboxedPlugins` 开关提示文案:** 语义从"无沙箱"调整为"无进程隔离"(因 OFF 现在有 Job)。i18n 双语同步(`frontend/src/i18n/en.json` + `zh.json`)。
- **CHANGELOG:** 记录 Windows Job Object 后端。

## 12. JNA 依赖

`net.java.dev.jna:jna` 加在 `FengYu/pom.xml`(父 pom 不管理,需显式版本)。JNA 是成熟库,~1.5MB。非 Windows 上类不加载(`isAvailable()` 守卫 + `detect()` 不返回 `WINDOWS_JOB`)。版本选取 JNA 最新稳定版(plan 阶段查 Maven Central)。

## 13. 平台支持矩阵(更新后)

| 平台 | 进程层隔离 | 文件系统/网络隔离 | 状态 |
|---|---|---|---|
| macOS | ✅ sandbox-exec(可靠) | ✅ sandbox-exec profile | 完整 |
| Linux | ✅ bwrap(可靠) | ✅ bwrap bind | 完整 |
| Windows | ✅ Job Object(可靠终止) | ❌ 已知差距(需 AppContainer,后续) | 进程层 OK;文件系统待方案 B |

## 14. 风险与 follow-up

| 项 | 说明 | 处理 |
|---|---|---|
| 文件系统/网络隔离缺失 | Job 只管进程树 | 已知差距;后续方案 B(AppContainer + ACL) |
| JNA 依赖体积 | ~1.5MB | 可接受;非 Windows 不加载 |
| Windows 测试覆盖 | Job API 只能 Windows 跑 | 层 1 非覆盖逻辑;层 2 标记 `@EnabledOnOs` |
| assign 失败 | worker 启动失败 | 显式抛错(优于静默降级) |

## 15. 实现顺序(概要,详见后续 plan)

1. 加 JNA 依赖到 `FengYu/pom.xml`。
2. `WindowsJobSandbox`(JNA 绑定 + 公开方法 + `isAvailable` 守卫)+ `@EnabledOnOs` 测试。
3. `ProcessSandbox`:`Backend.WINDOWS_JOB` + `Launch.onStarted` + `detect()`/`wrap()` 分支。
4. `PluginProcessManager`:`start()` 调 `onStarted`,`Worker.close()` 优先 `TerminateJobObject`。
5. `CommandExecuteTool`:`execute()` 调 `onStarted`,`terminate()` 优先 `TerminateJobObject`。
6. 非 Windows 的 `ProcessSandboxTest` + `Launch` 契约测试补全。
7. 文档(docs + Settings 文案 + CHANGELOG)。
