# 数据库被移除后可重新配置(启动恢复 + 手动重置端点)

**Date:** 2026-07-10
**Branch:** 4.0.0-FengYu
**Status:** Design (pending implementation plan)
**Related:** `2026-07-08-multids-setup-wizard-design.md`(多数据源初始化向导)

---

## 1. 背景与目标

### 1.1 问题(根因已定位)

用户配置 SQLite(文件路径 `.fengyu/database/fengyu`)后,数据库**目录**被移除/删除,但 `config/datasource.properties` 仍在。重启时:

1. `HeadlessLauncher.isDatasourceConfigured()`(`HeadlessLauncher.java:66`)**只检查配置文件是否存在** —— 文件在 → 返回 `true`。
2. 应用以 **APP 模式**启动 → `DataSourceAutoConfig.dataSource()` 构造 HikariDataSource 指向已不存在的目录 → SQLite 抛 `path to '...': '...database' does not exist` → `entityManagerFactory` 创建失败 → 进程退出码 1。`startWithFallback` 的端口重试也无法救回(同一坏配置重试两次,均失败)。

**为什么卡死无法恢复:**
- APP 模式起不来(数据库缺失 → 崩溃)。
- 不回退 SETUP 模式(配置文件仍在 → `isDatasourceConfigured()` 返回 `true`)。
- **没有任何 reset/delete/重配置端点**(已确认:`DataSourceConfigService` 只有 `load`/`save`;`SetupController` 4 个端点无删除;`SettingsController` 只管 theme/language)。
- 唯一出路:手动去文件系统删 `datasource.properties`。

崩溃堆栈关键行(来自用户提供的日志):

```
java.sql.SQLException: path to '.../.fengyu/database/fengyu': '.../.fengyu/database' does not exist
  at org.sqlite.SQLiteConnection.open(SQLiteConnection.java:261)
...
Caused by: org.hibernate.HibernateException: Unable to determine Dialect without JDBC metadata
```

### 1.2 目标

本次交付两件事:

1. **启动时自动恢复**:APP 模式启动前,对已加载的配置做一次 DB 连通性探测(JDBC `SELECT 1`,短超时)。探测失败 → 备份 `datasource.properties` 为 `.bak` → 回退 SETUP 模式,向导重新出现,用户可重新配置。
2. **手动重置端点**:在运行的 APP 模式(`SettingsController`)与 SETUP 模式(`SetupController`)各加一个重置端点,用户可在设置页主动触发"重置数据库配置" → 备份配置 → 进程以 `SETUP_DONE` 退出 → 监护进程重启进 SETUP 模式 → 向导重新出现。

### 1.3 非目标(本次不做)

- APP 模式运行时检测 DB 断线并自动重连/切库(运行时韧性,后续 phase)
- 运行时"重配置数据库"的完整前端流程(本次只加后端端点 + 最小前端调用;完整 settings UI 入口可后续接入)
- 远程 DB 不可达时的重试/退避策略(本次探测失败即回退 SETUP,语义清晰)

---

## 2. 关键设计决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 探测时机 | Spring 启动**之前**,在 `HeadlessLauncher` 决策点 | 最早可干预点,避免 `entityManagerFactory` 崩溃、避免部分 Spring 上下文已构建、避免双重启动日志。复用既有决策位置 |
| 探测方式 | 复用 `DataSourceConfigService.testConnection(cfg)`(原始 JDBC `SELECT 1`) | 既有、已测、不引 HikariCP。失败语义即"DB 不可达" |
| 探测超时 | `DriverManager.setLoginTimeout(5)` 包裹,finally 恢复 | 远程宕机主机必须快速失败,不能阻塞启动 60s |
| 失败时配置处理 | 移动 `datasource.properties` → `datasource.properties.bak`(已存在则加时间戳) | 既让 SETUP 模式起来(配置消失),又保留旧连接信息(host/port/db)供用户参考,不丢数据 |
| 备份失败兜底 | try 移动 → 失败则 try 删除 → 再失败则仅记日志,仍以 SETUP 模式启动 | 配置已加载但损坏时,SETUP 模式不使用它,优先保证应用能起来 |
| 手动重置入口 | APP 模式 `SettingsController` + SETUP 模式 `SetupController` 各一端点 | APP 模式端点供"运行中主动重置";SETUP 模式端点供"已回退 SETUP 后清理" |
| 退出钩子可测性 | 两个控制器各注入 `Runnable exitAction` 构造器 seam(生产为 `System.exit` lambda,测试为 no-op) | 既有 `initialize` 的 `System.exit` 完全无测、无 seam;新端点照搬会杀死测试 JVM。seam 让新老两个退出路径都可测 |

---

## 3. 启动时序(修改后)

```
HeadlessLauncher.main(args)
  ├─ primeLogDirectory()                              (现有,保留)
  ├─ LoggerBinder.bind(...)                           (现有,保留)
  ├─ 解析 --port= / --token=                          (现有,保留)
  │
  ├─ determineStartupMode()  ← 新(替换 isDatasourceConfigured)
  │     │
  │     ├─ cfg = configService.load()
  │     ├─ cfg == null                       → configured=false → SETUP 模式 (未变)
  │     ├─ cfg != null → probe(cfg):
  │     │     ├─ DriverManager.setLoginTimeout(5)
  │     │     ├─ testConnection(cfg).success()
  │     │     ├─ finally: 恢复原 loginTimeout
  │     │     ├─ true  → configured=true  → APP 模式    (新:探测通过)
  │     │     └─ false → configService.backupAndClear() (新:备份配置)
  │     │                 → configured=false → SETUP 模式 (新:回退)
  │     └─ 探测本身抛异常(非连接类)→ 记日志,保守按 configured=true 走 APP
  │        (避免误删可用配置;真不可用 APP 启动会再次失败,下次再回退)
  │
  └─ startWithFallback(port, configured)              (现有,保留)
```

### 3.1 探测语义细节

- **嵌入式 SQLite(本次崩溃场景)**:`testConnection` 打开文件;父目录缺失 → 驱动抛 "does not exist" → `success=false` → 备份 + 回退 SETUP。✅ 这正是当前崩溃的根因,现被捕获。
  - 边界:父目录**在**但文件**不在** → SQLite 探测会创建一个空文件,随后 Hibernate `ddl-auto=update` 建表。这是"首次运行"语义,可接受(不触发回退)。
- **嵌入式 H2**:类似,文件不存在但目录在 → H2 创建文件 → 探测成功。
- **远程 MySQL/PostgreSQL**:`DriverManager.getConnection` 带 login timeout;服务器宕机 → 超时/拒绝 → `success=false` → 备份 + 回退 SETUP。用户应启动其 DB 或重新配置。
- **探测超时**:`DriverManager.setLoginTimeout(5)`(秒)。远程宕机主机 5s 内失败,不阻塞启动。`finally` 恢复 `DriverManager` 原 loginTimeout 值(线程级全局状态,必须恢复)。

### 3.2 保守的"非连接异常"分支

若 `testConnection` 抛出**非连接类**异常(如 `ClassNotFoundException`——驱动不在 classpath、或配置损坏 `NullPointerException`),不删配置,保守按 `configured=true` 走 APP。理由:这类异常不代表"DB 被移除",误删可用配置损失更大;若真不可用,APP 启动会再次失败,下一次启动再回退。`testConnection` 内部已对 `ClassNotFoundException` 返回 `fail`(不抛),故此分支主要兜底其他 RuntimeException。

---

## 4. 组件改动

### 4.1 `DataSourceConfigService`(`setup/` 包,既有)

新增一个方法,被启动探测与两个重置端点共用:

```java
/**
 * 备份并清除 datasource.properties:移动到 datasource.properties.bak
 * (若 .bak 已存在,追加时间戳后缀避免覆盖)。移动失败则尝试直接删除;
 * 删除也失败则仅记日志。返回备份文件路径(失败/无文件返回 null)。
 */
public Path backupAndClear() {
    Path file = configFile();
    if (!Files.exists(file)) return null;
    Path bak = resolveBakPath(file);   // .bak 或 .bak.<timestamp>
    try {
        Files.move(file, bak);
        log.warn("Backed up stale datasource.properties to {}", bak);
        return bak;
    } catch (IOException moveErr) {
        log.warn("Move to .bak failed ({}); attempting direct delete", moveErr.getMessage());
        try {
            Files.deleteIfExists(file);
            log.warn("Deleted datasource.properties directly (backup unavailable)");
            return null;
        } catch (IOException delErr) {
            log.error("Could not backup or delete datasource.properties: {}", delErr.getMessage());
            return null;
        }
    }
}
```

`load`/`save`/`testConnection`/`buildFromWizard` **不变**。

### 4.2 `HeadlessLauncher`

- `isDatasourceConfigured()` → 重构为 `determineStartupMode()`(或保留方法名,内部增强)。提取包私有静态助手 `probeDatabase(DataSourceConfigService svc)` 便于单测:
  - 加载配置;为 null → 返回 `false`(SETUP)。
  - 探测 `testConnection`(+5s login timeout 包裹);成功 → `true`(APP)。
  - 失败 → `svc.backupAndClear()` → 返回 `false`(SETUP)。
- `main` 调用处不变(拿一个 boolean 传给 `startWithFallback`)。
- **不变**:`primeLogDirectory`、端口/token 解析、`startWithFallback`、`runSpring`。

### 4.3 `SetupController`(SETUP 模式,既有)

新增端点 + 退出 seam:

```java
public class SetupController {
    private final DataSourceConfigService configService;
    private final Runnable exitAction;   // 新 seam

    // 生产构造器:exitAction = 延迟 1s System.exit(SETUP_DONE)
    public SetupController(DataSourceConfigService configService) {
        this(configService, defaultExitAction());
    }

    // 测试构造器
    SetupController(DataSourceConfigService configService, Runnable exitAction) {
        this.configService = configService;
        this.exitAction = exitAction;
    }

    /** 备份并清除 datasource.properties,信号重启。SETUP 模式下重启仍进 SETUP(配置已没)。 */
    @DeleteMapping("/config")
    public Map<String, Object> clearConfig() {
        Path bak = configService.backupAndClear();
        log.info("Setup config cleared via DELETE /api/setup/config (bak={})", bak);
        exitAction.run();   // 替换原来的内联 daemon thread + System.exit
        return Map.of("success", true, "action", "restart");
    }
    // ... initialize 也改用 exitAction.run() seam(统一,顺便可测)
}
```

`defaultExitAction()` = 启 daemon 线程 sleep 1s 后 `System.exit(SETUP_DONE)`(与现 `initialize` 行为一致,只是抽成 seam)。

### 4.4 `SettingsController`(APP 模式,既有)

新增端点 + 注入 `DataSourceConfigService` + 退出 seam:

```java
public class SettingsController {
    private final AiConfigServiceHeadless config;
    private final DataSourceConfigService dataSourceConfigService;  // 新
    private final Runnable exitAction;                              // 新

    // APP 模式端点:重置数据库配置 → 备份 → SETUP_DONE 退出 → 监护进程重启进 SETUP
    @PostMapping("/database/reset")
    public Map<String, Object> resetDatabase() {
        Path bak = dataSourceConfigService.backupAndClear();
        log.info("Database config reset via APP settings (bak={})", bak);
        exitAction.run();
        return Map.of("success", true, "action", "restart");
    }
}
```

`DataSourceConfigService` 是 `@Service`,在 APP 模式组件扫描包内,可注入;它只依赖 `CryptoUtil` + 文件系统,无循环依赖风险。

### 4.5 `TokenAuthFilter`

`/api/setup/*` 已放行(SETUP 模式)。`/api/settings/database/reset` 在 APP 模式,需 token 校验(默认走现有 filter,无需改)。

---

## 5. REST 端点契约

### 5.1 `DELETE /api/setup/config`(SETUP 模式)

```json
成功: { "success": true, "action": "restart" }
```

无请求体。备份 `datasource.properties` → `.bak`,触发进程退出(`SETUP_DONE`)→ 监护进程重启。因配置已没,重启进 SETUP 模式(向导重新出现)。若本就无配置文件,`backupAndClear` 返回 null(无操作),仍返回 `success:true`(幂等)。

### 5.2 `POST /api/settings/database/reset`(APP 模式)

```json
成功: { "success": true, "action": "restart" }
```

无请求体。备份配置 → `SETUP_DONE` 退出 → 监护进程重启进 SETUP 模式。token 校验照常(APP 模式)。

---

## 6. 测试策略(TDD)

遵循既有 `DataSourceConfigServiceTest` 风格(JUnit 5, `@TempDir`, 注入构造器)。

### 6.1 `DataSourceConfigServiceTest`(纯单测)

- `backupAndClear_movesConfigToBak` —— save 一个配置 → 调 `backupAndClear` → 断言原文件不在、`.bak` 存在且内容一致。
- `backupAndClear_whenBakExists_appendsTimestamp` —— 两次备份 → 断言两个 `.bak` 文件都在(第二次为 `.bak.<ts>`)。
- `backupAndClear_whenFileMissing_returnsNullNoThrow` —— 无配置 → 调用不抛、返回 null。

### 6.2 `HeadlessLauncher` 探测(纯单测)

提取 `probeDatabase(DataSourceConfigService svc)` 为包私有静态方法:
- 指向**不存在的 SQLite 路径**的真实 `DataSourceConfigService`(tempDir)→ 探测失败 → 断言配置被备份、返回 `false`。
- 指向**真实可连 H2 临时文件 DB**的配置 → 探测成功 → 返回 `true`、配置未被删。
- 无配置(load 返回 null)→ 返回 `false`,不调 backup。

### 6.3 控制器测试(MockMvc + exitAction no-op seam)

- `SetupController.clearConfig`:注入 no-op `Runnable exitAction`,save 配置,MockMvc `DELETE /api/setup/config` → 断言 200、`{action:"restart"}`、配置已备份。
- `SettingsController.resetDatabase`:注入 `DataSourceConfigService`(tempDir)+ no-op `exitAction`,MockMvc `POST /api/settings/database/reset` → 断言 200、`{action:"restart"}`、配置已备份。
- 顺带:`SetupController.initialize` 用 seam 后也可补一个 MockMvc 测试(可选,本次不强制,但 seam 已具备)。

### 6.4 不做

- 真实 Tauri 监护进程重启的端到端测试(超出单测范围)
- 远程 DB Testcontainers(探测逻辑复用已测的 `testConnection`,无需重复)

---

## 7. 实现边界汇总

| 项 | 本次做 | 后续 |
|---|---|---|
| `DataSourceConfigService.backupAndClear()` | ✅ | — |
| `HeadlessLauncher` 启动探测 + 回退 SETUP | ✅ | — |
| `DELETE /api/setup/config`(SETUP) | ✅ | — |
| `POST /api/settings/database/reset`(APP) | ✅ | — |
| 两个控制器的 `exitAction` seam | ✅ | — |
| 单测(backupAndClear ×3、探测 ×3、两个端点 MockMvc) | ✅ | — |
| 设置页"重置数据库配置"前端按钮 | 最小调用 | ✅ 完整 UI |
| 运行时 DB 断线检测/自动重连 | ❌ | ✅ |
| 运行时切库 | ❌ | ✅ |

---

## 8. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 探测拖慢每次启动(本地 DB 通常 <100ms,远程可能 5s) | 本地嵌入式探测极快;远程用 5s login timeout 上限;可接受 |
| 误判可用 DB 为不可达(网络抖动)→ 误备份可用配置 | `.bak` 保留原配置,用户可手动恢复;回退 SETUP 后重新填一遍即可,数据不丢 |
| `DriverManager.setLoginTimeout` 是全局状态 | `finally` 恢复原值;探测同步执行,无并发 |
| `System.exit` seam 改动既有 `initialize` | 仅把内联 daemon thread 抽成 `exitAction.run()`,生产行为完全一致(默认 action 就是原逻辑) |
| APP 模式注入 `DataSourceConfigService` 引入循环依赖 | 该 service 仅依赖 `CryptoUtil`+文件系统,无 JPA/Repository,无循环风险 |

---

**下一步:** 本 spec 经用户审阅通过后,转入 writing-plans skill 生成实现计划。
