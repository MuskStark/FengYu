# Unified Host and Plugin Log Center Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a secure, file-backed log center that records host and isolated-plugin logs, supports filtered history/export and authenticated SSE, and exposes the result in the Vue shell.

**Architecture:** Keep the readable `fengyu.log`, add a structured rolling `fengyu-events.jsonl`, and derive both from the same redacted Logback event. Isolated plugin stderr is attributed with MDC, a bounded in-process bus handles live delivery, and file scanning remains the source of truth for history and reconnect recovery.

**Tech Stack:** Java 21, Spring Boot 4.1, SLF4J 2.0, Logback 1.5, Jackson, JUnit 5, Spring MockMvc, Vue 3.5, TypeScript, Pinia, Axios, native EventSource, Vitest, Node test runner.

## Global Constraints

- Preserve `.fengyu/logs/fengyu.log` as readable UTF-8 text.
- Add `.fengyu/logs/fengyu-events.jsonl` as the canonical query source.
- Keep at most 7 days and 200 MB across the log directory; configure each rolling family with a 100 MB cap and 25 MB segment size.
- Redact before text/JSONL persistence, SSE publication, query results, and exports; never retain an unredacted fallback.
- Plugin stdout remains newline-delimited JSON-RPC only; diagnostic logging uses stderr.
- Default plugin stderr level is INFO; `[TRACE]`, `[DEBUG]`, `[INFO]`, `[WARN]`, and `[ERROR]` prefixes override it.
- Bound one plugin stderr logical line to 64 KiB and mark truncated events.
- History defaults to 500 records, caps at 2,000 records, and accepts at most a 7-day range.
- Do not add log-level mutation or disk-log deletion APIs.
- Log collection, querying, and streaming failures must not fail host requests or plugin RPC calls.
- Follow TDD for every backend behavior and pure frontend state behavior.
- Use emoji conventional commits from `AGENTS.md`.

---

## File and Component Map

| Path | Responsibility |
|---|---|
| `FengYu/src/main/java/fan/summer/fengyu/log/model/LogSource.java` | HOST/PLUGIN enum. |
| `FengYu/src/main/java/fan/summer/fengyu/log/model/LogEvent.java` | Stable structured event shared by persistence, query, export, and SSE. |
| `FengYu/src/main/java/fan/summer/fengyu/log/runtime/LogRedactionRuntime.java` | Spring-independent static redaction registry used during early Logback startup. |
| `FengYu/src/main/java/fan/summer/fengyu/log/LogRedactionService.java` | Spring facade for per-plugin dynamic secret registration. |
| `FengYu/src/main/java/fan/summer/fengyu/log/runtime/PluginLogContext.java` | Scoped MDC source/plugin/truncated metadata. |
| `FengYu/src/main/java/fan/summer/fengyu/log/runtime/LogEventMapper.java` | Convert `ILoggingEvent` to one redacted `LogEvent` with stable per-event sequence. |
| `FengYu/src/main/java/fan/summer/fengyu/log/encoder/RedactingTextEncoder.java` | Human-readable redacted Logback encoder. |
| `FengYu/src/main/java/fan/summer/fengyu/log/encoder/StructuredLogEncoder.java` | One-redacted-JSON-object-per-line encoder. |
| `FengYu/src/main/java/fan/summer/fengyu/log/stream/LogEventBridge.java` | Static no-buffer bridge safe before Spring starts. |
| `FengYu/src/main/java/fan/summer/fengyu/log/stream/LogEventBusAppender.java` | Logback appender publishing mapped events to the bridge. |
| `FengYu/src/main/java/fan/summer/fengyu/log/stream/LogEventBus.java` | Bounded dispatcher and per-client subscriptions. |
| `FengYu/src/main/java/fan/summer/fengyu/log/query/LogQuery.java` | Validated query parameters. |
| `FengYu/src/main/java/fan/summer/fengyu/log/query/LogPage.java` | History page response. |
| `FengYu/src/main/java/fan/summer/fengyu/log/query/LogCursorCodec.java` | Opaque cursor encode/decode with basename validation. |
| `FengYu/src/main/java/fan/summer/fengyu/log/query/LogFileCatalog.java` | Discover current and archived JSONL files newest-first. |
| `FengYu/src/main/java/fan/summer/fengyu/log/query/LogQueryService.java` | Reverse scan, filter, and paginate JSONL/gzip files. |
| `FengYu/src/main/java/fan/summer/fengyu/log/LogQuotaService.java` | Enforce 7-day/200 MB directory policy without deleting active files. |
| `FengYu/src/main/java/fan/summer/fengyu/log/LogExportService.java` | Stream filtered text/JSONL and archive ZIP downloads. |
| `FengYu/src/main/java/fan/summer/fengyu/web/controller/LogController.java` | History, source metadata, and export endpoints. |
| `FengYu/src/main/java/fan/summer/fengyu/web/controller/LogStreamController.java` | Authenticated SSE, heartbeat, overflow, cleanup. |
| `frontend/src/stores/logs.ts` | History/realtime state, filters, pause/resume, reconnect recovery. |
| `frontend/src/api/logSse.ts` | EventSource wrapper for `/api/logs/stream`. |
| `frontend/src/views/Logs.vue` | Log center page composition. |
| `frontend/src/components/logs/LogFilters.vue` | Filter toolbar and export actions. |
| `frontend/src/components/logs/LogList.vue` | Fixed-row virtualized log list. |
| `frontend/src/components/logs/LogDetailPanel.vue` | Full event/exception detail and copy action. |

---

### Task 1: Structured event, scoped attribution, and unified redaction

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/model/LogSource.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/model/LogEvent.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/runtime/LogRedactionRuntime.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/LogRedactionService.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/runtime/PluginLogContext.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/runtime/LogEventMapper.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/log/LogRedactionRuntimeTest.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/log/LogEventMapperTest.java`

**Interfaces:**
- Produces: `LogEvent`, `LogSource`, `LogRedactionRuntime.register(owner, values)`, `unregister(owner)`, `redact(text)`, `PluginLogContext.open(pluginId, truncated)`, `LogEventMapper.map(ILoggingEvent)`.
- Consumes: Logback `ILoggingEvent`, SLF4J MDC, Jackson-compatible Java records.

- [ ] **Step 1: Write redaction tests**

Cover static key/value patterns, Bearer tokens, JDBC URLs, exception text, dynamic values, shared values owned by two plugins, values shorter than four characters, null input, and the exact replacement `***REDACTED***`.

```java
@Test void redactsStaticAndDynamicSecrets() {
    LogRedactionRuntime.register("plugin:a", List.of("do-not-log-me"));
    String value = LogRedactionRuntime.redact(
        "password=hunter2 Authorization: Bearer abc.def apiKey=xyz value=do-not-log-me");
    assertFalse(value.contains("hunter2"));
    assertFalse(value.contains("abc.def"));
    assertFalse(value.contains("xyz"));
    assertFalse(value.contains("do-not-log-me"));
    assertTrue(value.contains("***REDACTED***"));
}

@Test void keepsSharedSecretUntilEveryOwnerUnregisters() {
    LogRedactionRuntime.register("plugin:a", List.of("shared-secret"));
    LogRedactionRuntime.register("plugin:b", List.of("shared-secret"));
    LogRedactionRuntime.unregister("plugin:a");
    assertEquals("***REDACTED***", LogRedactionRuntime.redact("shared-secret"));
    LogRedactionRuntime.unregister("plugin:b");
    assertEquals("shared-secret", LogRedactionRuntime.redact("shared-secret"));
}
```

- [ ] **Step 2: Run the redaction test and verify failure**

Run:

```bash
mvn -f FengYu/pom.xml -Dtest=LogRedactionRuntimeTest test
```

Expected: compilation fails because `LogRedactionRuntime` does not exist.

- [ ] **Step 3: Implement the event and redaction types**

Use these exact public shapes:

```java
public enum LogSource { HOST, PLUGIN }

public record LogEvent(
    OffsetDateTime timestamp,
    long sequence,
    String level,
    LogSource source,
    String pluginId,
    String logger,
    String thread,
    String message,
    String exception,
    boolean truncated
) {}
```

`LogRedactionRuntime` must be `final`, Spring-independent, thread-safe, and own:

```java
public static final String REDACTED = "***REDACTED***";
public static void register(String owner, Collection<String> values);
public static void unregister(String owner);
public static String redact(String text);
static void clearForTest();
```

Store owner-to-values and rebuild a longest-first immutable value list after registration changes. Ignore null, blank, and length `< 4` dynamic values. Apply compiled case-insensitive patterns for password/token/apiKey/secret assignments, Bearer headers, and URI/JDBC credentials, then replace registered literal values. Catch internal runtime failures and return `[LOG REDACTION FAILED]` without logging the input.

`LogRedactionService` delegates to the runtime:

```java
@Service
public final class LogRedactionService {
    public void registerPluginEnvironment(String pluginId, Map<String, String> environment);
    public void unregisterPlugin(String pluginId);
}
```

Only environment keys ending in `_PASSWORD`, `_SECRET`, `_TOKEN`, or `_API_KEY` are registered.

- [ ] **Step 4: Write mapper and MDC tests**

Create a Logback `LoggingEvent`, open `PluginLogContext`, map it twice, and verify source/plugin/truncated metadata, exception redaction, and identical sequence for the same `ILoggingEvent` instance.

```java
@Test void mapsPluginContextAndKeepsStableSequence() {
    LoggingEvent raw = event(Level.ERROR, "failed password=hunter2", new IllegalStateException("token=abc123"));
    try (var ignored = PluginLogContext.open("com.example.worker", true)) {
        raw.setMDCPropertyMap(MDC.getCopyOfContextMap());
    }
    LogEvent first = LogEventMapper.map(raw);
    LogEvent second = LogEventMapper.map(raw);
    assertEquals(LogSource.PLUGIN, first.source());
    assertEquals("com.example.worker", first.pluginId());
    assertTrue(first.truncated());
    assertEquals(first.sequence(), second.sequence());
    assertFalse(first.message().contains("hunter2"));
    assertFalse(first.exception().contains("abc123"));
}
```

- [ ] **Step 5: Implement scoped MDC and mapping**

Use MDC keys:

```java
public static final String SOURCE_KEY = "fengyu.source";
public static final String PLUGIN_ID_KEY = "fengyu.pluginId";
public static final String TRUNCATED_KEY = "fengyu.truncated";
```

`PluginLogContext.open` returns `AutoCloseable`, snapshots previous values, writes `PLUGIN`, plugin ID, and truncated flag, then restores the exact previous values in `close()`.

`LogEventMapper.map` must:

- use event timestamp with the system zone;
- assign a process-local monotonic sequence cached by event identity in a synchronized `WeakHashMap<ILoggingEvent, Long>`;
- default to HOST when MDC does not contain `PLUGIN`;
- render `IThrowableProxy` through Logback's throwable converter;
- redact formatted message and exception independently;
- never mutate the original logging event.

- [ ] **Step 6: Run focused and module tests**

```bash
mvn -f FengYu/pom.xml -Dtest=LogRedactionRuntimeTest,LogEventMapperTest test
```

Expected: all new tests pass.

- [ ] **Step 7: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/log FengYu/src/test/java/fan/summer/fengyu/log
git commit -m "✨ feat(logs): add structured events and unified redaction"
```

---

### Task 2: Redacted text/JSONL persistence and live bridge

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/encoder/RedactingTextEncoder.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/encoder/StructuredLogEncoder.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/stream/LogEventBridge.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/stream/LogEventBusAppender.java`
- Modify: `FengYu/src/main/resources/logback.xml`
- Test: `FengYu/src/test/java/fan/summer/fengyu/log/LogEncoderTest.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/log/stream/LogEventBridgeTest.java`

**Interfaces:**
- Consumes: Task 1 `LogEventMapper`, `LogRedactionRuntime`.
- Produces: redacted text encoder, JSONL encoder, `LogEventBridge.install(Consumer<LogEvent>)`, `clear(Consumer<LogEvent>)`, `publish(LogEvent)`.

- [ ] **Step 1: Write encoder tests**

Encode one host event and one plugin event containing a secret and exception. Assert UTF-8, one JSON object per physical line, correct plugin fields, valid Jackson parsing, text prefix `[plugin:<id>]`, and no secret in either output.

```java
assertEquals("PLUGIN", json.readTree(jsonl).path("source").asText());
assertEquals("com.example.worker", json.readTree(jsonl).path("pluginId").asText());
assertTrue(text.contains("[plugin:com.example.worker]"));
assertFalse(text.contains("do-not-log-me"));
assertEquals(1, jsonl.lines().count());
```

- [ ] **Step 2: Run tests and verify failure**

```bash
mvn -f FengYu/pom.xml -Dtest=LogEncoderTest,LogEventBridgeTest test
```

Expected: compilation fails because the encoders and bridge do not exist.

- [ ] **Step 3: Implement the encoders and bridge**

`RedactingTextEncoder extends EncoderBase<ILoggingEvent>` with configurable `datePattern` and `loggerLength`. On `start()`, initialize a `DateTimeFormatter` and Logback throwable converter. On `encode`, assemble `timestamp`, padded level, thread, abbreviated/full logger, optional `[plugin:<id>] ` prefix, formatted message, newline, and exception text; run the complete string through `LogRedactionRuntime.redact` and return UTF-8 bytes. This explicit assembly makes the plugin prefix deterministic without mutating the source event.

`StructuredLogEncoder extends EncoderBase<ILoggingEvent>` maps the event once and serializes it with `JsonMapper.builder().findAndAddModules().build()`, appending exactly one `\n`.

`LogEventBridge` owns `AtomicReference<Consumer<LogEvent>>`. `publish` is a no-op before installation and catches consumer runtime failures without logging recursively.

`LogEventBusAppender extends UnsynchronizedAppenderBase<ILoggingEvent>` performs:

```java
@Override protected void append(ILoggingEvent event) {
    LogEventBridge.publish(LogEventMapper.map(event));
}
```

- [ ] **Step 4: Replace Logback configuration with redacted dual rolling files**

Configure these appenders in `logback.xml`:

```xml
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
  <encoder class="fan.summer.fengyu.log.encoder.RedactingTextEncoder">
    <datePattern>HH:mm:ss.SSS</datePattern><loggerLength>36</loggerLength>
  </encoder>
  <filter class="ch.qos.logback.classic.filter.ThresholdFilter"><level>INFO</level></filter>
</appender>

<appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
  <file>${LOG_DIR}/fengyu.log</file>
  <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
    <fileNamePattern>${LOG_DIR}/fengyu.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
    <maxFileSize>25MB</maxFileSize><maxHistory>7</maxHistory><totalSizeCap>100MB</totalSizeCap>
  </rollingPolicy>
  <encoder class="fan.summer.fengyu.log.encoder.RedactingTextEncoder">
    <datePattern>yyyy-MM-dd HH:mm:ss.SSS</datePattern><loggerLength>0</loggerLength>
  </encoder>
</appender>

<appender name="STRUCTURED" class="ch.qos.logback.core.rolling.RollingFileAppender">
  <file>${LOG_DIR}/fengyu-events.jsonl</file>
  <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
    <fileNamePattern>${LOG_DIR}/fengyu-events.%d{yyyy-MM-dd}.%i.jsonl.gz</fileNamePattern>
    <maxFileSize>25MB</maxFileSize><maxHistory>7</maxHistory><totalSizeCap>100MB</totalSizeCap>
  </rollingPolicy>
  <encoder class="fan.summer.fengyu.log.encoder.StructuredLogEncoder"/>
</appender>

<appender name="LIVE" class="fan.summer.fengyu.log.stream.LogEventBusAppender"/>
```

Attach `CONSOLE`, `FILE`, `STRUCTURED`, and `LIVE` to root. Preserve existing framework logger levels. Do not reference host encoder classes from worker JAR configuration: isolated workers may not have host implementation classes on their classpath, and their stderr is captured and redacted by the host in Task 3.

- [ ] **Step 5: Run tests and package resource check**

```bash
mvn -f FengYu/pom.xml -Dtest=LogEncoderTest,LogEventBridgeTest test
mvn -f FengYu/pom.xml -DskipTests package
jar tf FengYu/target/FengYu-4.0.0-SNAPSHOT.jar | grep -E 'logback.xml|StructuredLogEncoder|LogEventBusAppender'
```

Expected: tests pass and all three entries appear in the shaded JAR.

- [ ] **Step 6: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/log/encoder FengYu/src/main/java/fan/summer/fengyu/log/stream FengYu/src/main/resources/logback.xml FengYu/src/test/java/fan/summer/fengyu/log
git commit -m "✨ feat(logs): persist redacted text and structured events"
```

---

### Task 3: Attribute and bound isolated-plugin output

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/BoundedLineInput.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginStderrParser.java`
- Modify: `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java`
- Delete: `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/SensitiveValueRedactor.java`
- Modify: `FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginProcessManagerTest.java`
- Create: `FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/BoundedLineInputTest.java`
- Create: `FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginStderrParserTest.java`

**Interfaces:**
- Consumes: Task 1 `LogRedactionService`, `PluginLogContext`.
- Produces: `BoundedLineInput.next()`, `PluginStderrParser.parse`, plugin stderr logger `plugin.<id>.stderr`, protocol logger `plugin.<id>.protocol`.

- [ ] **Step 1: Write parser and bounded-line tests**

Use exact records:

```java
record BoundedLine(String text, boolean truncated) {}
record ParsedPluginLog(Level level, String message) {}
```

Test CRLF removal, EOF without newline, UTF-8 decoding, 64 KiB truncation with discard-until-newline, `[WARN]` parsing, case-insensitive prefixes, and default INFO.

```java
assertEquals(Level.WARN, PluginStderrParser.parse("[WARN] workbook failed").level());
assertEquals("workbook failed", PluginStderrParser.parse("[WARN] workbook failed").message());
assertEquals(Level.INFO, PluginStderrParser.parse("plain diagnostic").level());
```

- [ ] **Step 2: Run tests and verify failure**

```bash
mvn -f FengYu/pom.xml -Dtest=BoundedLineInputTest,PluginStderrParserTest test
```

Expected: compilation fails because the new classes do not exist.

- [ ] **Step 3: Implement bounded reading and prefix parsing**

`BoundedLineInput` reads raw bytes from `InputStream`, keeps at most `65_536` bytes, discards the remainder of an overlong logical line until `\n`, strips one trailing `\r`, and decodes with UTF-8 replacement for an incomplete trailing code point. `next()` returns `null` only when EOF occurs before any byte.

`PluginStderrParser` recognizes only a prefix at character zero matching `[(TRACE|DEBUG|INFO|WARN|ERROR)]` case-insensitively; it trims one following space and leaves other text unchanged.

- [ ] **Step 4: Extend `PluginProcessManagerTest` first**

Modify the test worker to emit:

```java
System.err.println("[WARN] database password=" + System.getenv("FENGYU_DB_PASSWORD"));
System.err.println("plain info");
System.err.println("x".repeat(70_000));
```

Attach `ListAppender`s to `plugin.com.example.worker.stderr` and `plugin.com.example.worker.protocol`. Assert WARN/INFO levels, MDC `fengyu.pluginId`, `fengyu.truncated=true` on the long event, and protocol WARN for the existing non-JSON stdout line. Because `ListAppender` observes raw Logback events before encoders, pass captured events through `LogEventMapper.map(...)` before asserting unified `***REDACTED***` output.

- [ ] **Step 5: Modify `PluginProcessManager`**

Inject `LogRedactionService`:

```java
public PluginProcessManager(PluginPackageService packages,
        PluginFileGrantService files,
        PluginRuntimeEnvironmentService runtimeEnvironment,
        LogRedactionService redaction) { ... }
```

At worker start:

1. obtain the environment;
2. call `redaction.registerPluginEnvironment(id, environment)`;
3. if `builder.start()` fails, unregister immediately;
4. create per-plugin stderr and protocol SLF4J loggers;
5. drain `process.getErrorStream()` with `BoundedLineInput`;
6. for each line, open `PluginLogContext.open(id, line.truncated())` and call the logger method matching the parsed level;
7. in `Worker.close()`, unregister the plugin after process termination.

When `workers.compute` finds a non-null dead Worker, close it before starting the replacement so its dynamic secrets are unregistered before the new Worker registers its environment. Keep one live Worker per plugin ID.

Wrap non-JSON stdout and mismatched response-ID WARN calls with `PluginLogContext.open(pluginId, false)` and write through `protocolLog`. Remove `abbreviate` for stderr; keep a 4 KiB cap for protocol-noise messages so an invalid stdout line cannot dominate host logs.

Delete `SensitiveValueRedactor`; use `LogRedactionRuntime.redact` for RPC error messages before throwing them to callers.

- [ ] **Step 6: Run plugin runtime tests**

```bash
mvn -f FengYu/pom.xml -Dtest=PluginProcessManagerTest,BoundedLineInputTest,PluginStderrParserTest test
```

Expected: all tests pass; no output or exception contains `do-not-log-me`.

- [ ] **Step 7: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/plugin/runtime FengYu/src/test/java/fan/summer/fengyu/plugin/runtime
git commit -m "✨ feat(plugins): capture attributed bounded worker logs"
```

---

### Task 4: Bounded real-time event bus

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/stream/LogStreamSignal.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/stream/LogSubscription.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/stream/LogEventBus.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/log/stream/LogEventBusTest.java`

**Interfaces:**
- Consumes: Task 2 `LogEventBridge`, Task 1 `LogEvent`.
- Produces: `LogEventBus.subscribe()`, `publish(LogEvent)`, `LogSubscription.poll(Duration)`, `close()` and signal kinds `LOG`, `OVERFLOW`, `CLOSED`.

- [ ] **Step 1: Write bus tests**

Construct the bus with small capacities through a package-private constructor:

```java
LogEventBus bus = new LogEventBus(3, 2);
LogSubscription subscription = bus.subscribe();
```

Test publish/delivery order, low-level eviction before WARN/ERROR, aggregate overflow count, per-subscriber overflow, bridge installation/removal, and shutdown without leaked dispatcher threads.

- [ ] **Step 2: Run the test and verify failure**

```bash
mvn -f FengYu/pom.xml -Dtest=LogEventBusTest test
```

Expected: compilation fails because `LogEventBus` does not exist.

- [ ] **Step 3: Implement the bus**

Use these shapes:

```java
public record LogStreamSignal(Kind kind, LogEvent event, long dropped) {
    public enum Kind { LOG, OVERFLOW, CLOSED }
}

public interface LogSubscription extends AutoCloseable {
    LogStreamSignal poll(Duration timeout) throws InterruptedException;
    @Override void close();
}
```

`LogEventBus` is a Spring `@Component` with defaults `globalCapacity=8192` and `subscriberCapacity=512`. It owns one daemon virtual dispatcher thread. `publish` never blocks:

- offer normally;
- when full, remove the oldest TRACE/DEBUG event, then INFO;
- if no lower-priority event exists, reject the new event;
- count every rejected/evicted event;
- after capacity recovers, dispatch one `OVERFLOW` signal with the aggregate count before subsequent logs.

Each subscription owns an `ArrayBlockingQueue<LogStreamSignal>`. If its queue is full, mark it overflowed and stop delivering further logs; its next `poll` returns `OVERFLOW`, then `CLOSED`.

Store `private final Consumer<LogEvent> bridgeConsumer = this::publish;`. Install that field into `LogEventBridge` in `@PostConstruct`; clear the same object identity and close subscriptions in `@PreDestroy`. Do not call SLF4J inside bus overflow paths.

- [ ] **Step 4: Run tests**

```bash
mvn -f FengYu/pom.xml -Dtest=LogEventBusTest,LogEventBridgeTest test
```

Expected: all tests pass and the test JVM exits normally.

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/log/stream FengYu/src/test/java/fan/summer/fengyu/log/stream
git commit -m "✨ feat(logs): add bounded realtime event bus"
```

---

### Task 5: File catalog, history query, cursor, and quota enforcement

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/query/LogQuery.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/query/LogPage.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/query/LogCursorCodec.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/query/LogFileCatalog.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/query/LogQueryService.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/LogQuotaService.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/log/query/LogQueryServiceTest.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/log/LogQuotaServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `LogEvent`.
- Produces: validated `LogQuery`, `LogPage`, newest-first file catalog, opaque cursor, scheduled quota enforcement.

- [ ] **Step 1: Write query tests with current and gzip files**

Create temporary files named exactly:

```text
fengyu-events.jsonl
fengyu-events.2026-07-14.0.jsonl.gz
fengyu-events.2026-07-13.0.jsonl.gz
```

Test newest-first order, time/level/source/plugin/substring filters, 500 default, 2,000 maximum, 7-day rejection, pagination without duplicates, corrupt-line skipping, cursor expiry after deleting its file, and a 32 MiB scanned-byte ceiling.

Use exact records:

```java
public record LogQuery(
    OffsetDateTime from, OffsetDateTime to, Set<String> levels,
    LogSource source, String pluginId, String query,
    String cursor, int limit
) {}

public record LogPage(
    List<LogEvent> items, String nextCursor,
    boolean cursorExpired, long skippedCorruptRecords
) {}
```

- [ ] **Step 2: Run query test and verify failure**

```bash
mvn -f FengYu/pom.xml -Dtest=LogQueryServiceTest test
```

Expected: compilation fails because query classes do not exist.

- [ ] **Step 3: Implement catalog and cursor**

`LogFileCatalog` accepts the log directory through constructor injection and returns current JSONL first, then matching gzip archives ordered by filename date/index descending. It returns basenames plus resolved paths and rejects symlinks escaping the log directory.

`LogCursorCodec` Base64-URL encodes Jackson JSON:

```json
{"file":"fengyu-events.2026-07-14.0.jsonl.gz","offsetFromNewest":418}
```

Decode validates the basename against `[A-Za-z0-9._-]+`, requires non-negative offset, and never resolves a caller-provided path directly.

- [ ] **Step 4: Implement reverse scanning and filtering**

For each selected file, read UTF-8 lines (wrap gzip archives in `GZIPInputStream`) into a per-file list, iterate that list backwards, and count bytes read. A 25 MB segment bounds per-file memory. Stop when the page is full or 32 MiB have been scanned; cursor position refers to the next older physical record, including records rejected by filters.

Matching rules:

```java
boolean matches(LogEvent e, LogQuery q) {
    return !e.timestamp().isBefore(q.from())
        && !e.timestamp().isAfter(q.to())
        && (q.levels().isEmpty() || q.levels().contains(e.level()))
        && (q.source() == null || q.source() == e.source())
        && (q.pluginId() == null || q.pluginId().equals(e.pluginId()))
        && containsIgnoreCase(e.logger(), e.message(), e.exception(), q.query());
}
```

Normalize query input in a static factory: `to=now`, `from=to-24h`, `limit=500`; reject limit outside `1..2000`, `from>to`, and ranges over 7 days.

- [ ] **Step 5: Write and implement quota tests**

Test deletion order with fake active files and archives. `LogQuotaService` must:

- run at application ready and hourly with `@Scheduled(fixedDelay=3_600_000)`;
- delete archives older than 7 days;
- when total directory size exceeds 200 MB, delete oldest `.gz` archives until under cap;
- never delete `fengyu.log` or `fengyu-events.jsonl`;
- ignore unrelated files;
- expose `StorageStatus(boolean writable, long bytes, long capBytes)`.

Make constructor `LogQuotaService(Path logDir, long capBytes, Clock clock)` package-private for tests; the Spring constructor reads `${fengyu.log.dir:${user.dir}/.fengyu/logs}`.

- [ ] **Step 6: Run query and quota tests**

```bash
mvn -f FengYu/pom.xml -Dtest=LogQueryServiceTest,LogQuotaServiceTest test
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/log/query FengYu/src/main/java/fan/summer/fengyu/log/LogQuotaService.java FengYu/src/test/java/fan/summer/fengyu/log
git commit -m "✨ feat(logs): query rolling files and enforce retention"
```

---

### Task 6: Export, REST endpoints, SSE, and authentication

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/log/LogExportService.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/web/controller/LogController.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/web/controller/LogStreamController.java`
- Modify: `FengYu/src/main/java/fan/summer/fengyu/web/filter/TokenAuthFilter.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/log/LogExportServiceTest.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/web/controller/LogControllerTest.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/web/controller/LogStreamControllerTest.java`
- Modify: `FengYu/src/test/java/fan/summer/fengyu/web/filter/TokenAuthFilterTest.java`

**Interfaces:**
- Consumes: Task 4 bus, Task 5 query/catalog/quota, `PluginPackageService.installed()`.
- Produces: `/api/logs`, `/api/logs/sources`, `/api/logs/export`, `/api/logs/stream`.

- [ ] **Step 1: Write export tests**

Test filtered text, filtered JSONL, archive ZIP names, UTF-8, no unbounded byte array, and rejection of unsupported `mode`/`format`. The service API is:

```java
public void exportFiltered(LogQuery query, ExportFormat format, OutputStream output);
public void exportArchive(OutputStream output);
public enum ExportFormat { TEXT, JSONL }
```

Text lines use:

```text
2026-07-15 16:25:31.482 ERROR [PLUGIN:com.example.worker] logger - message
```

- [ ] **Step 2: Write controller and auth tests**

Test exact contracts:

```text
GET /api/logs
GET /api/logs/sources
GET /api/logs/export?mode=filtered&format=text
GET /api/logs/export?mode=archive
GET /api/logs/stream?token=<launch-token>
```

Extend `TokenAuthFilterTest` with:

```java
@Test void allowsLogStreamQueryTokenButNotOtherLogApis() { ... }
```

Assert `/api/logs/stream` accepts a matching query token, rejects a missing/wrong token, and `/api/logs` still requires the header.

- [ ] **Step 3: Run tests and verify failure**

```bash
mvn -f FengYu/pom.xml -Dtest=LogExportServiceTest,LogControllerTest,LogStreamControllerTest,TokenAuthFilterTest test
```

Expected: new controller/service tests fail and the new auth case fails.

- [ ] **Step 4: Implement export and REST controller**

`LogController` maps query strings to `LogQuery.normalized(...)`. Source response shape:

```java
public record LogSourcesResponse(
    List<LogSource> sources,
    List<PluginSourceItem> plugins,
    LogQuotaService.StorageStatus storage
) {}
public record PluginSourceItem(String id, String name) {}
```

For exports return `ResponseEntity<StreamingResponseBody>` with:

- filtered text filename `fengyu-logs-<timestamp>.log`;
- filtered JSONL filename `fengyu-logs-<timestamp>.jsonl`;
- archive filename `fengyu-logs-<timestamp>.zip`;
- `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff`.

`exportArchive` adds only `fengyu.log`, `fengyu-events.jsonl`, and matching `fengyu.*.gz`/`fengyu-events.*.gz` basenames; never follow symlinks.

`exportFiltered` repeatedly queries pages of at most 2,000 records until `nextCursor` is null and writes each page immediately to the response stream. It does not materialize the full seven-day export in memory.

- [ ] **Step 5: Implement SSE controller**

`LogStreamController.stream()` creates `SseEmitter(0L)`, sends an immediate comment heartbeat, subscribes to `LogEventBus`, and starts a virtual thread. The loop polls for 15 seconds:

- `LOG` -> named `log`, JSON data `LogEvent`;
- `OVERFLOW` -> named `overflow`, `{ "dropped": n }`, then complete;
- timeout -> named `heartbeat`, `{ "timestamp": "..." }`;
- `CLOSED` -> complete.

Register `onCompletion`, `onTimeout`, and `onError` callbacks that close the subscription and interrupt the sender thread. Do not log each disconnected client through the live appender.

- [ ] **Step 6: Update `TokenAuthFilter`**

Change only the EventSource fallback condition:

```java
if (provided == null && ("/api/ai/stream".equals(path) || "/api/logs/stream".equals(path))) {
    provided = request.getParameter("token");
}
```

Update Javadoc to name both SSE endpoints.

- [ ] **Step 7: Run focused and integration tests**

```bash
mvn -f FengYu/pom.xml -Dtest=LogExportServiceTest,LogControllerTest,LogStreamControllerTest,TokenAuthFilterTest test
mvn -f FengYu/pom.xml -Dtest=HeadlessIntegrationTest test
```

Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/log/LogExportService.java FengYu/src/main/java/fan/summer/fengyu/web/controller/LogController.java FengYu/src/main/java/fan/summer/fengyu/web/controller/LogStreamController.java FengYu/src/main/java/fan/summer/fengyu/web/filter/TokenAuthFilter.java FengYu/src/test/java/fan/summer/fengyu
git commit -m "✨ feat(logs): expose history export and realtime APIs"
```

---

### Task 7: Frontend log API, EventSource client, and Pinia state

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/client.ts`
- Create: `frontend/src/api/logSse.ts`
- Create: `frontend/src/stores/logs.ts`
- Create: `frontend/src/stores/logs.test.ts`

**Interfaces:**
- Consumes: Task 6 REST/SSE contracts, existing `backendUrl` and `getToken`.
- Produces: typed log API, `openLogStream`, `useLogsStore`.

- [ ] **Step 1: Add exact TypeScript contracts**

```ts
export type LogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'
export type LogSource = 'HOST' | 'PLUGIN'

export interface LogEvent {
  timestamp: string
  sequence: number
  level: LogLevel
  source: LogSource
  pluginId: string | null
  logger: string
  thread: string
  message: string
  exception: string | null
  truncated: boolean
}

export interface LogFilters {
  from: string
  to: string
  levels: LogLevel[]
  source: LogSource | null
  pluginId: string | null
  query: string
}

export interface LogPage {
  items: LogEvent[]
  nextCursor: string | null
  cursorExpired: boolean
  skippedCorruptRecords: number
}

export interface LogSources {
  sources: LogSource[]
  plugins: Array<{ id: string; name: string }>
  storage: { writable: boolean; bytes: number; capBytes: number }
}
```

Add `api.getLogs(filters, cursor?, limit?)`, `api.getLogSources()`, and `api.logExportUrl(filters, mode, format)`. `logExportUrl` must include the token only for an explicit browser navigation/download if the backend cannot receive Axios headers; prefer an Axios blob download method `downloadLogs(...)` so the token remains in the header and not in browser history.

- [ ] **Step 2: Write store tests before implementation**

Mock history and stream dependencies. Test:

- initial history is sorted newest-first;
- duplicate `(timestamp, sequence)` events are ignored;
- pause buffers events and increments `pendingCount`;
- resume merges pending events and clears the count;
- buffer cap drops oldest UI entries and sets `needsRecovery`;
- overflow invokes `recoverGap()`;
- reconnect backoff resets after open;
- filter changes clear cursors and reload.

Use dependency injection in a pure factory:

```ts
export function createLogsController(deps: {
  getLogs: typeof api.getLogs
  openStream: typeof openLogStream
  now: () => Date
}) { ... }
```

The Pinia store wraps this controller, making behavior testable without mounting Vue.

- [ ] **Step 3: Run test and verify failure**

```bash
npm --prefix frontend run test:unit -- src/stores/logs.test.ts
```

Expected: test fails because `logs.ts` does not exist.

- [ ] **Step 4: Implement EventSource wrapper**

`openLogStream` constructs `/api/logs/stream?token=...`, dispatches named `log`, `overflow`, and `heartbeat` events, exposes `onOpen/onDisconnect`, and returns `{ close() }`. Native `onerror` reports disconnect but does not permanently close; the store owns backoff/reconnect.

```ts
export interface LogStreamCallbacks {
  onOpen(): void
  onLog(event: LogEvent): void
  onOverflow(dropped: number): void
  onDisconnect(): void
}
```

- [ ] **Step 5: Implement controller and Pinia store**

State:

```ts
events: LogEvent[]              // newest first, max 10_000
pending: LogEvent[]             // max 2_000
filters: LogFilters
nextCursor: string | null
paused: boolean
connected: boolean
loading: boolean
pendingCount: number
selected: LogEvent | null
storageWritable: boolean
needsRecovery: boolean
```

Deduplicate with key `${timestamp}:${sequence}`. `recoverGap()` requests from the last displayed event timestamp minus one second through now, then merges/deduplicates. Reconnect delays are 1s, 2s, 5s, 10s, then 15s maximum. `disconnect()` clears timers and EventSource.

- [ ] **Step 6: Run unit test and typecheck**

```bash
npm --prefix frontend run test:unit -- src/stores/logs.test.ts
npm --prefix frontend run typecheck
```

Expected: both pass.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api frontend/src/stores/logs.ts frontend/src/stores/logs.test.ts
git commit -m "✨ feat(frontend): add log history and realtime state"
```

---

### Task 8: Log center route, virtualized UI, detail panel, and i18n

**Files:**
- Create: `frontend/src/views/Logs.vue`
- Create: `frontend/src/components/logs/LogFilters.vue`
- Create: `frontend/src/components/logs/LogList.vue`
- Create: `frontend/src/components/logs/LogDetailPanel.vue`
- Create: `frontend/src/components/logs/virtualRows.ts`
- Create: `frontend/src/components/logs/virtualRows.test.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/shell/Sidebar.vue`
- Modify: `frontend/src/i18n/en.json`
- Modify: `frontend/src/i18n/zh.json`
- Modify: `frontend/src/theme/codex.css`
- Create: `frontend/test/log-center-navigation.test.mjs`

**Interfaces:**
- Consumes: Task 7 `useLogsStore`.
- Produces: `/logs` page and sidebar entry.

- [ ] **Step 1: Write virtualization and navigation tests**

`virtualRows.test.ts` verifies start/end indexes, overscan, top/bottom spacers, empty lists, and clamping after list shrink.

`log-center-navigation.test.mjs` reads source and asserts:

```js
assert.match(router, /path:\s*['"]\/logs['"]/)
assert.match(sidebar, /labelKey:\s*['"]sidebar\.logs['"]/)
assert.match(en, /"logs"\s*:\s*"Logs"/)
assert.match(zh, /"logs"\s*:\s*"日志"/)
```

- [ ] **Step 2: Run tests and verify failure**

```bash
npm --prefix frontend run test:unit -- src/components/logs/virtualRows.test.ts
node --test frontend/test/log-center-navigation.test.mjs
```

Expected: tests fail because the files and route do not exist.

- [ ] **Step 3: Implement fixed-row virtualization**

Use `ROW_HEIGHT=34` and `OVERSCAN=12`:

```ts
export function virtualRows(total: number, scrollTop: number, viewport: number) {
  const visible = Math.ceil(viewport / ROW_HEIGHT)
  const start = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - OVERSCAN)
  const end = Math.min(total, start + visible + OVERSCAN * 2)
  return { start, end, top: start * ROW_HEIGHT, bottom: (total - end) * ROW_HEIGHT }
}
```

Keep row height fixed. Show full data in `LogDetailPanel` rather than expanding the row height, preserving correct virtualization.

- [ ] **Step 4: Implement `LogFilters.vue`**

Controls:

- presets 15 minutes, 1 hour, 24 hours, 7 days;
- level checkboxes or multi-select;
- source select;
- plugin select disabled unless source is PLUGIN or all;
- debounced 300 ms keyword input;
- live/pause button;
- clear-view button calling `store.clearView()` only;
- filtered text/JSONL export and full archive download.

Emit normalized partial filters and never expose log-level mutation or delete actions.

- [ ] **Step 5: Implement list and detail components**

`LogList.vue`:

- receives newest-first events;
- uses the fixed virtual window;
- formats timestamp to local `HH:mm:ss.SSS`;
- applies level classes `cx-log-level--trace/debug/info/warn/error`;
- displays plugin name/ID or `HOST`;
- emits `select` on click;
- detects user scrolling away from top and emits `auto-scroll-change`.

`LogDetailPanel.vue` displays all fields, preserves exception whitespace with `<pre>`, shows a truncated chip, and copies a deterministic plain-text representation with `navigator.clipboard.writeText`.

- [ ] **Step 6: Implement `Logs.vue` lifecycle**

On mount: load sources, load first page, connect stream. On unmount: disconnect. Compose filters, storage warning, pending banner, list, load-older button, and detail panel.

When `pendingCount > 0`, show localized “N new logs” button calling `resume()`. When storage is not writable, show an error alert while keeping realtime view usable.

- [ ] **Step 7: Add route, sidebar, translations, and styles**

Route:

```ts
{ path: '/logs', name: 'logs', component: () => import('@/views/Logs.vue') }
```

Sidebar bottom item:

```ts
{ key: 'logs', to: '/logs', labelKey: 'sidebar.logs', icon: 'mdi-text-box-search-outline' }
```

Add `sidebar.logs` and a complete `logs` namespace in both locale files for title, filters, levels, host/plugin labels, live/paused, new-count, load older, export, archive, copy, truncated, storage unavailable, empty, and corrupt-record warning.

Add scoped reusable log styles to `codex.css`: fixed 34px row, monospace message, level colors using theme/error/warn tokens, toolbar wrapping, detail panel, and narrow-width layout. Do not introduce hard-coded page backgrounds that break light mode.

- [ ] **Step 8: Run all frontend checks**

```bash
npm --prefix frontend run test:unit
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

Expected: all pass.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/views/Logs.vue frontend/src/components/logs frontend/src/router/index.ts frontend/src/shell/Sidebar.vue frontend/src/i18n frontend/src/theme/codex.css frontend/test/log-center-navigation.test.mjs
git commit -m "✨ feat(frontend): add unified log center"
```

---

### Task 9: End-to-end verification and documentation

**Files:**
- Modify: `scripts/e2e-smoke.sh`
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `AGENTS.md`
- Create: `docs/en/guide/log-center.md`
- Create: `docs/zh/guide/log-center.md`
- Modify: `docs/.vitepress/config.ts`
- Modify: `docs/en/plugins/worker.md`
- Modify: `docs/zh/plugins/worker.md`
- Modify: `docs/en/plugins/pitfalls.md`
- Modify: `docs/zh/plugins/pitfalls.md`
- Modify: `docs/en/reference/rest-api.md`
- Modify: `docs/zh/reference/rest-api.md`
- Modify: `docs/en/reference/sse-events.md`
- Modify: `docs/zh/reference/sse-events.md`
- Modify: `docs/en/reference/troubleshooting.md`
- Modify: `docs/zh/reference/troubleshooting.md`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: packaged verification, bilingual user/developer documentation, current agent guidance.

- [ ] **Step 1: Extend smoke test**

After the existing plugin calls, poll authenticated history until a host log is present:

```bash
LOGS="$(curl -s "${AUTH[@]}" "$H/api/logs?source=HOST&limit=20")"
echo "$LOGS" | grep -q '"items"' || fail "log history missing: $LOGS"
```

Verify unauthenticated `/api/logs` returns 401, `fengyu.log` and `fengyu-events.jsonl` exist under the temporary working directory, each JSONL line parses with Python, and neither file contains the seeded database password if the smoke setup includes one.

- [ ] **Step 2: Run backend reactor verification**

```bash
mvn -f pom.xml -DskipTests install
mvn -f FengYu/pom.xml test
mvn -f FengYu/pom.xml package -DskipTests
scripts/e2e-smoke.sh
```

Expected: reactor builds, FengYu tests pass, shaded JAR builds, and smoke script prints PASS including the new log checks.

- [ ] **Step 3: Run frontend verification**

```bash
npm --prefix frontend run test:unit
npm --prefix frontend test
npm --prefix frontend run build
```

Expected: all pass.

- [ ] **Step 4: Write bilingual log-center guide**

Document:

- opening `/logs` from the sidebar;
- filters, pause/resume, reconnect recovery, copy, filtered export, archive download;
- readable and JSONL file locations;
- 7-day/200 MB retention;
- automatic redaction and the rule that secrets should still never be intentionally logged;
- “clear view” does not delete disk files;
- no runtime log-level editing.

Add both pages to Guide sidebars in `docs/.vitepress/config.ts`.

- [ ] **Step 5: Update plugin worker and pitfalls docs**

State exactly:

- stdout is JSON-RPC only;
- stderr is collected by the host and visible in Log Center;
- optional level prefixes are `[TRACE]`, `[DEBUG]`, `[INFO]`, `[WARN]`, `[ERROR]`;
- unprefixed stderr is INFO;
- logical lines over 64 KiB are truncated;
- common secrets and injected sensitive environment values are redacted, but plugins remain responsible for not logging private data.

- [ ] **Step 6: Update REST, SSE, troubleshooting, README, changelog, and AGENTS**

REST docs list all four endpoints and parameters. SSE docs list `log`, `overflow`, and `heartbeat`, query-token authentication, and reconnect/history recovery. Troubleshooting covers unwritable `.fengyu/logs`, expired cursors, missing plugin logs, stdout protocol pollution, and storage quota cleanup.

Replace the stale AGENTS plugin-logging paragraph with the 4.0 isolated-process contract and dual-file behavior. Keep the JavaFX-era logger API marked as legacy compatibility only.

- [ ] **Step 7: Build documentation and scan for stale claims**

```bash
npm run docs:build
rg -n "plugin stderr.*DEBUG|only.*fengyu.log|只.*fengyu.log|stderr.*DEBUG" README.md AGENTS.md docs/en docs/zh
```

Expected: VitePress build succeeds with no dead links; remaining matches, if any, are historical release notes explicitly labeled as historical.

- [ ] **Step 8: Final full verification**

```bash
git diff --check
mvn -f FengYu/pom.xml test
npm --prefix frontend run test:unit
npm --prefix frontend test
npm --prefix frontend run build
npm run docs:build
```

Expected: every command exits 0.

- [ ] **Step 9: Commit**

```bash
git add scripts/e2e-smoke.sh README.md CHANGELOG.md AGENTS.md docs
git commit -m "📝 docs(logs): document log center and worker logging"
```

---

## Final Acceptance Checklist

- [ ] Host and plugin events appear in readable text, JSONL, history API, and SSE.
- [ ] Plugin stderr has exact plugin ID, parsed level, and truncation metadata.
- [ ] Plugin stdout remains JSON-RPC-only; noise becomes attributed protocol WARN.
- [ ] Static and dynamic secrets are absent from text, JSONL, SSE, query, exceptions, and export.
- [ ] History filters and cursor pagination work across current and gzip files.
- [ ] Query limits are 500 default, 2,000 maximum, and 7 days maximum.
- [ ] Both rolling families use 25 MB segments, 7-day retention, and 100 MB individual caps.
- [ ] Directory quota cleanup never removes active log files.
- [ ] Slow clients and bus overflow generate recovery signals without blocking business threads.
- [ ] `/api/logs/stream` accepts query-token auth; other log APIs require the normal header.
- [ ] Frontend pause/resume, pending count, recovery, virtualization, detail, copy, and export work in dark and light themes.
- [ ] No log-level mutation or disk deletion control exists.
- [ ] Backend, frontend, smoke, and docs verification commands all pass.
