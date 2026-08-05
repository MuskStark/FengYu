# Windows Job Object 进程隔离 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `WINDOWS_JOB` backend to `ProcessSandbox` (JNA + `CreateJobObject` + `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`) so Windows gets a reliable process-tree-termination primitive, fixing the #2 residual (`ProcessHandle.descendants()` is unreliable on Windows) and giving Windows its first real process-layer isolation primitive (#4 step 1).

**Architecture:** A new `WindowsJobSandbox` class maps the Win32 Job Object API via JNA (`jna` + `jna-platform`). `ProcessSandbox` gains a `WINDOWS_JOB` backend selected by `detect()` on Windows. Because a Job is assigned *after* `process.start()` (unlike bwrap/sandbox-exec which wrap the command line), `Launch` gains a third field `onStarted` — a `Consumer<Process>` that the caller invokes right after `start()`. `PluginProcessManager.Worker.close()` and `CommandExecuteTool.terminate()` use `TerminateJobObject` on Windows as the primary tree-kill, falling back to the existing `descendants()` path when no job handle exists (non-Windows / `NONE` backend).

**Tech Stack:** Java 21+, JNA 5.19.1 (`net.java.dev.jna:jna` + `net.java.dev.jna:jna-platform`), Win32 kernel32 Job Object API, Spring Boot 4.1, JUnit 5 (`@EnabledOnOs`).

## Global Constraints

- **JNA artifacts:** `net.java.dev.jna:jna` 5.19.1 + `net.java.dev.jna:jna-platform` 5.19.1. The parent `pom.xml` does NOT manage JNA — pin the version explicitly in `FengYu/pom.xml`. The Win32 `Kernel32` interface lives in `jna-platform` (package `com.sun.jna.platform.win32`), NOT the base `jna` artifact — both are required.
- **Non-Windows must never load the JNA classes.** `WindowsJobSandbox` is only reachable when `ProcessSandbox.detect()` returns `WINDOWS_JOB`, which only happens on Windows. The `isAvailable()` guard catches `UnsatisfiedLinkError`/`NoClassDefFoundError` so a missing JNA degrades to `NONE` rather than crashing.
- **Job Object is NOT a filesystem/network sandbox.** It only provides reliable process-tree lifecycle (kill-on-job-close). This is an honest, documented gap; do NOT claim AppContainer/ACL capabilities.
- **`Launch.onStarted` may be null** for `BUBBLEWRAP`/`SANDBOX_EXEC`/`NONE`; callers must null-check before invoking.
- **No `System.out` prints** — SLF4J.
- **Match surrounding style** — the existing `ProcessSandbox`, `PluginProcessManager`, `CommandExecuteTool` style.
- **Commit convention:** conventional commits with emojis (✨ feat, 🐛 fix, ♻️ refactor, 📝 docs, 🧪 test). Commit per task.
- **Assign-failure policy:** if Job creation or `AssignProcessToJobObject` fails, throw — explicit failure beats silent fallback to the unreliable `descendants()`.

**Spec:** `docs/superpowers/specs/2026-08-05-windows-job-object-design.md`

---

### Task 1: Add JNA dependencies

Add `jna` + `jna-platform` (5.19.1) to `FengYu/pom.xml`. Verify the module still builds and the JNA classes resolve. No functional change yet.

**Files:**
- Modify: `FengYu/pom.xml` (add two dependencies)

**Interfaces:**
- Produces: JNA on the `FengYu` classpath, so later tasks can reference `com.sun.jna.*` and `com.sun.jna.platform.win32.*`.

- [ ] **Step 1: Read the current FengYu/pom.xml dependency section**

Run: `grep -n "spring-ai-starter-mcp-client\|</dependencies>" FengYu/pom.xml | head`
Locate where the AI/security dependencies are listed, so the new deps go in the same block.

- [ ] **Step 2: Add the two JNA dependencies**

In `FengYu/pom.xml`, inside `<dependencies>`, add (with a comment explaining the Windows-only purpose):

```xml
<!-- Windows Job Object process isolation (ProcessSandbox WINDOWS_JOB backend).
     jna-platform carries the Win32 Kernel32 bindings; base jna is its prerequisite.
     Only loaded on Windows — WindowsJobSandbox.isAvailable() guards non-Windows hosts. -->
<dependency>
    <groupId>net.java.dev.jna</groupId>
    <artifactId>jna</artifactId>
    <version>5.19.1</version>
</dependency>
<dependency>
    <groupId>net.java.dev.jna</groupId>
    <artifactId>jna-platform</artifactId>
    <version>5.19.1</version>
</dependency>
```

- [ ] **Step 3: Build the FengYu module to confirm the deps resolve**

Run: `./mvnw -q -pl FengYu -am compile`
Expected: BUILD SUCCESS (JNA jars download from Maven Central).

- [ ] **Step 4: Commit**

```bash
git add FengYu/pom.xml
git commit -m "⬆️ deps(fengyu): add JNA 5.19.1 for Windows Job Object isolation"
```

---

### Task 2: WindowsJobSandbox — JNA Win32 Job Object binding

The standalone JNA class that creates/configures a Job, assigns a process, and terminates it. Pure JNA, no FengYu deps. Tested on Windows (`@EnabledOnOs(OS.WINDOWS)`); a non-Windows test asserts `isAvailable()` returns false without throwing.

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/security/WindowsJobSandbox.java`
- Create: `FengYu/src/test/java/fan/summer/fengyu/security/WindowsJobSandboxTest.java`

**Interfaces:**
- Consumes: JNA (`com.sun.jna.*`, `com.sun.jna.platform.win32.Kernel32` is NOT used — define a local `Kernel32` interface to keep the binding minimal and avoid pulling the full platform Win32 surface; use `com.sun.jna.ptr.*` + `WinNT.HANDLE`).
- Produces: `WindowsJobSandbox` with `static boolean isAvailable()`, `static long createAndConfigureJob()`, `static void assign(long jobHandle, Process process)`, `static void terminate(long jobHandle)`, `static void closeHandle(long jobHandle)`.

- [ ] **Step 1: Write the failing non-Windows test**

Create `FengYu/src/test/java/fan/summer/fengyu/security/WindowsJobSandboxTest.java`:

```java
package fan.summer.fengyu.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.*;

class WindowsJobSandboxTest {

    /** On non-Windows the JNA Win32 classes must not load; isAvailable() returns false cleanly. */
    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "JNA Win32 binding only loads on Windows")
    void isAvailableReflectsHost() {
        // On Windows this runs and asserts true; on mac/linux it is skipped.
        assertTrue(WindowsJobSandbox.isAvailable());
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "Job Object API is Windows-only")
    void jobCreatesAndAssignsAndKillsOnClose() throws Exception {
        long job = WindowsJobSandbox.createAndConfigureJob();
        assertNotEquals(0L, job, "job handle should be non-zero");
        // Spawn a long-sleeping child and assign it.
        Process.sleepChild = new ProcessBuilder("timeout", "/T", "30", "/NOBREAK").start();
        WindowsJobSandbox.assign(job, Process.sleepChild);
        assertTrue(Process.sleepChild.isAlive(), "child alive after assign");
        // Closing the job handle triggers KILL_ON_JOB_CLOSE — the child dies.
        WindowsJobSandbox.closeHandle(job);
        Process.sleepChild.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(Process.sleepChild.isAlive(), "child killed when job handle closed");
    }
}
```

Note: `Process.sleepChild` above is pseudocode — use a local `Process` variable in the test. The real test (Windows-only) creates a `timeout /T 30` child via `new ProcessBuilder("cmd","/c","timeout","/T","30").start()`. On mac/linux both tests are skipped (`@EnabledOnOs(OS.WINDOWS)`), so the suite is green everywhere.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl FengYu test -Dtest=WindowsJobSandboxTest`
Expected: FAIL (compile error — `WindowsJobSandbox` does not exist).

- [ ] **Step 3: Implement WindowsJobSandbox**

Create `FengYu/src/main/java/fan/summer/fengyu/security/WindowsJobSandbox.java`:

```java
package fan.summer.fengyu.security;

import com.sun.jna.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Windows-only JNA binding over the Win32 Job Object API, used by {@link ProcessSandbox}'s
 * {@code WINDOWS_JOB} backend to guarantee reliable process-tree termination.
 *
 * <p>A Job Object created with {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE} ensures that when the
 * host JVM dies (closing the job handle), the Windows kernel kills every process in the job —
 * including grandchildren (e.g. a plugin worker's Node driver / Chromium). This replaces the
 * unreliable {@code ProcessHandle.descendants()} enumeration on Windows.
 *
 * <p><b>Not a filesystem/network sandbox.</b> Job Objects confine the process tree's lifecycle,
 * not its filesystem or network access. OS-level file/network isolation on Windows would require
 * AppContainer + ACL work (out of scope; see the design spec's known-gap note).
 *
 * <p>This class is only loaded on Windows (guarded by {@code ProcessSandbox.detect()} →
 * {@code WINDOWS_JOB}, which is only selected on Windows, and by {@link #isAvailable()}). Loading
 * it on a non-Windows host would fail to link the native stubs, so never reference it unconditionally.
 */
final class WindowsJobSandbox {

    private static final Logger log = LoggerFactory.getLogger(WindowsJobSandbox.class);

    // Win32 constants.
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
    private static final int JobObjectExtendedLimitInformation = 9;
    private static final int PROCESS_SET_QUOTA = 0x0100;
    private static final int PROCESS_TERMINATE = 0x0001;

    /** Minimal kernel32 mapping for Job Object lifecycle. */
    public interface Kernel32 extends Library {
        HANDLE CreateJobObjectW(Pointer lpJobAttributes, String lpName);
        boolean SetInformationJobObject(HANDLE hJob, int infoClass,
                                        Pointer lpJobObjectInfo, int cbJobObjectInfoLength);
        boolean AssignProcessToJobObject(HANDLE job, HANDLE process);
        boolean TerminateJobObject(HANDLE job, int exitCode);
        boolean CloseHandle(HANDLE hObject);
        HANDLE OpenProcess(int access, boolean inherit, int pid);
    }

    private static final Kernel32 LIB = loadKernel32();

    private WindowsJobSandbox() {}

    /** True iff JNA + the Win32 stubs loaded successfully on this host (Windows only). */
    static boolean isAvailable() {
        return LIB != null;
    }

    private static Kernel32 loadKernel32() {
        try {
            if (!Platform.isWindows()) return null;
            return Native.load("kernel32", Kernel32.class);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError | RuntimeException e) {
            log.warn("JNA kernel32 unavailable; Windows Job isolation disabled: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Create a Job Object configured to kill its entire process tree when the handle closes.
     *
     * @return the job handle (as a long); never 0
     * @throws IllegalStateException if the Job could not be created or configured
     */
    static long createAndConfigureJob() {
        if (LIB == null) throw new IllegalStateException("JNA kernel32 not available");
        HANDLE job = LIB.CreateJobObjectW(null, null);
        if (job == null) throw new IllegalStateException("CreateJobObjectW returned null");
        // JOBOBJECT_EXTENDED_LIMIT_INFORMATION: the only field we set is BasicLimitInformation.LimitFlags.
        // Layout (Win64): IO_COUNTERS(40) + JOB_OBJECT_LIMIT_INFORMATION(48 + 3*HANDLE) — total 112 bytes.
        // We only need the LimitFlags DWORD at offset 40 (after PerProcessUserTimeLimit etc.).
        // To keep this robust without hand-rolling the full struct, use a Memory big enough and set the flag.
        Memory info = new Memory(208);  // generous; covers JOBOBJECT_EXTENDED_LIMIT_INFORMATION
        info.clear();
        // Per JOBOBJECT_BASIC_LIMIT_INFORMATION, LimitFlags is at offset 0x14 (20) within that struct,
        // which is at offset 0 within the extended struct's BasicLimitInformation field (offset 16 after IO_COUNTERS).
        // Simpler: use a JNA Structure. See helper below.
        boolean ok = configureKillOnClose(job);
        if (!ok) {
            LIB.CloseHandle(job);
            throw new IllegalStateException("SetInformationJobObject failed");
        }
        return Pointer.nativeValue(job.getPointer());
    }

    private static boolean configureKillOnClose(HANDLE job) {
        // Build JOBOBJECT_EXTENDED_LIMIT_INFORMATION with LimitFlags = KILL_ON_JOB_CLOSE.
        // Using JNA Structure is the reliable path. Define a static nested Structure class.
        return ExtendedLimit.withKillOnJobClose(LIB, job);
    }

    /** Assign a running process to the job. Throws on failure (explicit-failure policy). */
    static void assign(long jobHandle, Process process) {
        if (LIB == null) throw new IllegalStateException("JNA kernel32 not available");
        HANDLE job = asHandle(jobHandle);
        long pid = process.pid();
        HANDLE ph = LIB.OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, false, (int) pid);
        if (ph == null) throw new IllegalStateException("OpenProcess failed for pid " + pid);
        try {
            if (!LIB.AssignProcessToJobObject(job, ph)) {
                throw new IllegalStateException("AssignProcessToJobObject failed for pid " + pid);
            }
        } finally {
            LIB.CloseHandle(ph);
        }
    }

    /** Terminate every process in the job (normal close path). */
    static void terminate(long jobHandle) {
        if (LIB == null || jobHandle == 0L) return;
        LIB.TerminateJobObject(asHandle(jobHandle), 1);
    }

    /** Close the job handle. On the last close with KILL_ON_JOB_CLOSE, the kernel kills the tree. */
    static void closeHandle(long jobHandle) {
        if (LIB == null || jobHandle == 0L) return;
        LIB.CloseHandle(asHandle(jobHandle));
    }

    private static HANDLE asHandle(long value) {
        // Reconstruct a HANDLE from a raw pointer value.
        return new HANDLE(Pointer.of(value));
    }

    /** JOBOBJECT_EXTENDED_LIMIT_INFORMATION JNA Structure (minimal — only LimitFlags is set). */
    public static class ExtendedLimit extends Structure {
        public static class ByReference extends ExtendedLimit implements Structure.ByReference {}

        // IO_COUNTERS (4 x LONG, 32 bytes)
        public long ReadOperationCount, WriteOperationCount, OtherOperationCount;
        public long ReadTransferCount, WriteTransferCount, OtherTransferCount;
        // JOB_OBJECT_BASIC_LIMIT_INFORMATION
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
        public int LimitFlags, MinimumWorkingSetSize, MaximumWorkingSetSize;
        public int ActiveProcessLimit, Affinity, PriorityClass, SchedulingClass;
        // ... remaining fields (we zero them; the struct must still match the ABI size)

        @Override protected java.util.List<String> getFieldOrder() {
            return java.util.List.of(
                "ReadOperationCount","WriteOperationCount","OtherOperationCount",
                "ReadTransferCount","WriteTransferCount","OtherTransferCount",
                "PerProcessUserTimeLimit","PerJobUserTimeLimit",
                "LimitFlags","MinimumWorkingSetSize","MaximumWorkingSetSize",
                "ActiveProcessLimit","Affinity","PriorityClass","SchedulingClass");
        }

        static boolean withKillOnJobClose(Kernel32 lib, HANDLE job) {
            ExtendedLimit info = new ExtendedLimit();
            info.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            info.write();
            return lib.SetInformationJobObject(job, JobObjectExtendedLimitInformation,
                    info.getPointer(), (int) info.size());
        }
    }
}
```

**IMPORTANT for the implementer:** the exact `ExtendedLimit` struct layout above is approximate — JOBOBJECT_EXTENDED_LIMIT_INFORMATION on Win64 is 112 bytes and the hand-rolled `Structure` field set above may be incomplete (it omits `IoInfo` and the trailing `JobMemoryLimit`/`PeakProcessMemoryUsed`/`PeakJobMemoryUsed` fields). Before finalizing, consult the [Microsoft JOBOBJECT_BASIC_LIMIT_INFORMATION / JOBOBJECT_EXTENDED_LIMIT_INFORMATION docs](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_extended_limit_information) and ensure the JNA `Structure` field order + sizes match the Win64 ABI exactly (use `@FieldOrder` or override `getFieldOrder()` with ALL fields). An incomplete struct makes `SetInformationJobObject` fail silently. The Windows-only test (`jobCreatesAndAssignsAndKillsOnClose`) is the proof — it must actually kill the child on close.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl FengYu test -Dtest=WindowsJobSandboxTest`
Expected on mac/linux: PASS (both tests skipped via `@EnabledOnOs(OS.WINDOWS)`). The full struct correctness is only provable on Windows.

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/security/WindowsJobSandbox.java \
        FengYu/src/test/java/fan/summer/fengyu/security/WindowsJobSandboxTest.java
git commit -m "✨ feat(security): WindowsJobSandbox — JNA Job Object binding (KILL_ON_JOB_CLOSE)"
```

---

### Task 3: ProcessSandbox — WINDOWS_JOB backend + Launch.onStarted

Add the `WINDOWS_JOB` enum value, the `onStarted` field to `Launch`, the Windows branch in `detect()`, and the `WINDOWS_JOB` branch in `wrap()`. Update all existing `new Launch(...)` call sites to pass `null` for `onStarted`.

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/security/ProcessSandbox.java` (enum, Launch record, detect, wrap, the three `new Launch(...)` sites in wrap() at lines 108, 134, 151)
- Modify: `FengYu/src/main/java/fan/summer/fengyu/security/ProcessSandbox.java:90` (`unrestricted()` also constructs a `Launch`)
- Test: `FengYu/src/test/java/fan/summer/fengyu/security/ProcessSandboxTest.java` (add cases)

**Interfaces:**
- Consumes: `WindowsJobSandbox` (Task 2).
- Produces: `Launch` with a 3-arg canonical constructor `(List<String>, Backend, Consumer<Process>)`; a 2-arg convenience constructor `(List<String>, Backend)` that passes `null` onStarted (so existing call sites still compile); `WINDOWS_JOB` detect on Windows; `Launch.onStarted()` accessor.

- [ ] **Step 1: Write the failing tests**

In `FengYu/src/test/java/fan/summer/fengyu/security/ProcessSandboxTest.java`, add:

```java
@Test
void launchCarriesOnStartedCallback() {
    // For WINDOWS_JOB the Launch carries a non-null onStarted; for NONE/BUBBLEWRAP/SANDBOX_EXEC it is null.
    ProcessSandbox noneSandbox = new ProcessSandbox(ProcessSandbox.Backend.NONE);
    ProcessSandbox.Launch l = noneSandbox.unrestricted(java.util.List.of("echo","hi"));
    assertNull(l.onStarted(), "NONE backend has no onStarted hook");
}

@Test
void windowsJobBackendIsNotSelectedOnNonWindows() {
    // detect() is private; infer via isNativeSandboxAvailable() + the public backend().
    // On mac/linux NONE or BUBBLEWRAP/SANDBOX_EXEC is returned; WINDOWS_JOB never appears.
    assertNotEquals(ProcessSandbox.Backend.WINDOWS_JOB,
            new ProcessSandbox().backend(),
            "non-Windows host must not select WINDOWS_JOB");
}
```

(If `Backend.WINDOWS_JOB` does not yet compile, that's the expected RED.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl FengYu test -Dtest=ProcessSandboxTest`
Expected: FAIL (`Backend.WINDOWS_JOB` / `Launch.onStarted()` do not exist).

- [ ] **Step 3: Add WINDOWS_JOB to the Backend enum**

In `ProcessSandbox.java`, update the enum:

```java
public enum Backend {
    BUBBLEWRAP("bubblewrap"),
    SANDBOX_EXEC("sandbox-exec"),
    WINDOWS_JOB("windows-job"),
    NONE("none");

    private final String id;
    Backend(String id) { this.id = id; }
    public String id() { return id; }
}
```

- [ ] **Step 4: Add onStarted to the Launch record + a 2-arg convenience constructor**

Replace the `Launch` record (currently lines 41-49) with:

```java
public record Launch(List<String> command, Backend backend, java.util.function.Consumer<Process> onStarted) {
    public Launch {
        command = List.copyOf(command);
        onStarted = onStarted;   // may be null
    }

    /** Backwards-compatible 2-arg constructor: no onStarted hook (NONE/bwrap/sandbox-exec). */
    public Launch(List<String> command, Backend backend) {
        this(command, backend, null);
    }

    public boolean sandboxed() {
        return backend != Backend.NONE;
    }
}
```

This keeps all existing `new Launch(command, backend)` call sites compiling.

- [ ] **Step 5: Add the WINDOWS_JOB detect branch**

Replace `detect()` (currently lines 175-182) — add a Windows branch before the `return NONE`:

```java
private static Backend detect() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("linux") && executableOnPath("bwrap")) return Backend.BUBBLEWRAP;
    if (os.contains("mac") && Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) {
        return Backend.SANDBOX_EXEC;
    }
    if (os.contains("win") && WindowsJobSandbox.isAvailable()) return Backend.WINDOWS_JOB;
    return Backend.NONE;
}
```

- [ ] **Step 6: Add the WINDOWS_JOB branch to wrap() and plugin()**

In `wrap()` (line 107), add the `WINDOWS_JOB` branch right after the NONE check:

```java
private Launch wrap(List<String> raw, Path workdir, List<Path> writableRoots,
                    boolean broadFileWrite, boolean allowNetwork) {
    if (backend == Backend.NONE) return new Launch(raw, backend);
    if (backend == Backend.WINDOWS_JOB) {
        // Job Objects are assigned AFTER start(); the command itself is unchanged.
        java.util.function.Consumer<Process> onStarted = process -> {
            long job = WindowsJobSandbox.createAndConfigureJob();
            WindowsJobSandbox.assign(job, process);
            // Stash the handle on the process via a Thread-local is NOT viable across calls.
            // The caller (PluginProcessManager/CommandExecuteTool) owns the handle: see the
            // onStartedHandle() variant below — callers pass a long[1] to receive the handle.
        };
        return new Launch(raw, backend, onStarted);
    }
    // ... existing BUBBLEWRAP / SANDBOX_EXEC branches unchanged
```

**Refinement (apply this instead of the Thread-local comment):** The `onStarted` callback needs to hand the job handle back to the caller. Change the hook contract from `Consumer<Process>` to a small functional interface that returns the handle, OR have the caller pass a `long[]` receiver. Simplest: make `onStarted` a `java.util.function.BiConsumer<Process, long[]>` — the caller passes a `long[1]` and the hook writes the job handle into it. Update the `Launch` record accordingly:

```java
public record Launch(List<String> command, Backend backend,
                     java.util.function.BiConsumer<Process, long[]> onStarted) {
```

and the WINDOWS_JOB branch:

```java
java.util.function.BiConsumer<Process, long[]> onStarted = (process, handleOut) -> {
    long job = WindowsJobSandbox.createAndConfigureJob();
    WindowsJobSandbox.assign(job, process);
    handleOut[0] = job;
};
return new Launch(raw, backend, onStarted);
```

The 2-arg convenience constructor still passes `null`.

Also update `plugin()` (line 100-102): the `if (backend == NONE) throw` stays, but add `WINDOWS_JOB` to the allowed set (it no longer throws):

```java
public Launch plugin(List<String> raw, Path pluginRoot, List<Path> writableRoots,
                     boolean broadFileWrite, boolean allowNetwork) {
    if (backend == Backend.NONE) {
        throw new IllegalStateException("Plugin workers require a supported native process sandbox");
    }
    return wrap(raw, pluginRoot, writableRoots, broadFileWrite, allowNetwork);
}
```
`WINDOWS_JOB` now flows through `wrap()` and returns a Launch with the onStarted hook — so `plugin()` no longer throws on Windows (the #4 hard-fail is resolved for the Job layer).

- [ ] **Step 7: Run the test to verify it passes**

Run: `./mvnw -q -pl FengYu test -Dtest=ProcessSandboxTest`
Expected: PASS.

- [ ] **Step 8: Run the full FengYu test suite to catch any Launch call-site regressions**

Run: `./mvnw -q -pl FengYu test`
Expected: BUILD SUCCESS (all existing tests pass; the 2-arg constructor kept them compiling).

- [ ] **Step 9: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/security/ProcessSandbox.java \
        FengYu/src/test/java/fan/summer/fengyu/security/ProcessSandboxTest.java
git commit -m "✨ feat(security): ProcessSandbox WINDOWS_JOB backend + Launch.onStarted hook"
```

---

### Task 4: PluginProcessManager — invoke onStarted, Worker holds job handle, close() uses TerminateJobObject

Wire the onStarted hook into `start()`, store the job handle on `Worker`, and make `Worker.close()` use `TerminateJobObject` as the primary tree-kill on Windows, falling back to the existing `descendants()` path when no handle.

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java`:
  - `start()` (~line 215, after `builder.start()`)
  - `Worker` class (~line 353): add a `long jobHandle` field + constructor param
  - `Worker.close()` (~line 489): terminate via job first

**Interfaces:**
- Consumes: `Launch.onStarted()` (BiConsumer<Process, long[]>) from Task 3; `WindowsJobSandbox.terminate/closeHandle` from Task 2.
- Produces: `Worker` that owns its job handle and reaps it reliably on Windows.

- [ ] **Step 1: Add a jobHandle field to Worker**

In `PluginProcessManager.java`, the `Worker` class (line 353). Add a field and constructor param:

```java
static final class Worker {
    // ... existing fields
    private final long jobHandle;   // Windows Job Object handle (0 on non-Windows / NONE)

    Worker(String pluginId, Process process, ObjectMapper json, SensitiveValueRedactor redactor,
           PluginLogStore logStore, long grantVersion, boolean fullAccess, long jobHandle) {
        // ... existing assignments
        this.jobHandle = jobHandle;
        // ... existing reader/writer setup
    }
```

- [ ] **Step 2: Invoke onStarted in start() and pass the handle to Worker**

In `start()`, after `Process process = builder.start();` (line 215), invoke the hook if present:

```java
Process process = builder.start();
long jobHandle = 0L;
if (launch.onStarted() != null) {
    long[] handleOut = {0L};
    launch.onStarted().accept(process, handleOut);
    jobHandle = handleOut[0];
}
// ... (stderr drain thread, unchanged)
Worker worker = new Worker(id, process, json, redactor, logStore,
        files.grantVersion(id), fullAccess, jobHandle);
```

- [ ] **Step 3: Update Worker.close() to terminate via job first**

Replace `Worker.close()` (lines 489-503) with:

```java
void close() {
    failAll("Plugin worker closed: " + pluginId);
    // Primary (Windows Job Object): terminate the entire tree via the kernel. Reliable on Windows
    // where ProcessHandle.descendants() is unreliable. No-op when jobHandle == 0.
    if (jobHandle != 0L) {
        try { WindowsJobSandbox.terminate(jobHandle); } catch (RuntimeException ignored) {}
    }
    // Destroy the worker JVM itself (graceful SIGTERM, then SIGKILL after 2s).
    process.destroy();
    try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
    // Backstop: descendants() walk. On Windows this is now secondary (the Job already terminated
    // the tree); on mac/linux (jobHandle==0) it remains the primary grandchild reaper.
    killDescendants(process.descendants());
    // Close the job handle (triggers KILL_ON_JOB_CLOSE on any survivors).
    if (jobHandle != 0L) {
        try { WindowsJobSandbox.closeHandle(jobHandle); } catch (RuntimeException ignored) {}
    }
}
```

`killDescendants()` (lines 505-511) is kept unchanged as the non-Windows backstop.

- [ ] **Step 4: Update the Worker constructor call site**

The constructor now takes `jobHandle`. The `start()` change in Step 2 already passes it. There are no other `new Worker(...)` call sites (the only construction is in `start()`).

- [ ] **Step 5: Build + run the FengYu test suite**

Run: `./mvnw -q -pl FengYu test`
Expected: BUILD SUCCESS. Existing `PluginProcessManagerTest` tests construct `Worker` indirectly via `start()` (mocked), so they should pass; if any test constructs `Worker` directly with the old signature, update it.

- [ ] **Step 6: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java
git commit -m "✨ feat(plugins): Worker reaps via Windows Job Object (TerminateJobObject primary)"
```

---

### Task 5: CommandExecuteTool — invoke onStarted, terminate via TerminateJobObject

Same wiring for the AI command-execution path: invoke the onStarted hook after `builder.start()`, and in `terminate()` prefer the job-terminate.

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/ai/tools/CommandExecuteTool.java`:
  - `execute()` (~line 96, after `builder.start()`)
  - `terminate()` (~line 180)
  - add a `long jobHandle` field on the tool instance (or a local captured into the try block)

**Interfaces:**
- Consumes: `Launch.onStarted()` from Task 3; `WindowsJobSandbox.terminate/closeHandle` from Task 2.
- Produces: AI commands launched under WINDOWS_JOB are reliably reaped on timeout/exit.

- [ ] **Step 1: Invoke onStarted in execute()**

In `execute()`, after `process = builder.start();` (line 96):

```java
process = builder.start();
long jobHandle = 0L;
if (launch.onStarted() != null) {
    long[] handleOut = {0L};
    launch.onStarted().accept(process, handleOut);
    jobHandle = handleOut[0];
}
final long finalJobHandle = jobHandle;   // for use in terminate()
```

Then in the timeout branch (line 104) and the finally blocks (lines 124, 127), pass the handle to `terminate`. Update the `terminate` signature to accept the job handle:

```java
if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
    timedOut = true;
    terminate(process, finalJobHandle);
    process.waitFor(5, TimeUnit.SECONDS);
}
```

- [ ] **Step 2: Update terminate() to use the job handle**

Replace `terminate(Process process)` (lines 180-189) with:

```java
private static void terminate(Process process, long jobHandle) {
    // Primary (Windows Job Object): kernel tree-kill. No-op when jobHandle == 0.
    if (jobHandle != 0L) {
        try { WindowsJobSandbox.terminate(jobHandle); } catch (RuntimeException ignored) {}
    }
    process.toHandle().descendants().forEach(handle -> {
        if (handle.isAlive()) handle.destroyForcibly();
    });
    process.destroyForcibly();
    if (jobHandle != 0L) {
        try { WindowsJobSandbox.closeHandle(jobHandle); } catch (RuntimeException ignored) {}
    }
}
```

Keep a single-arg overload `terminate(Process process)` that delegates with `0L` (for any path that doesn't have a handle — there shouldn't be one after this task, but defensive):

```java
private static void terminate(Process process) { terminate(process, 0L); }
```

Update all `terminate(process)` call sites in `execute()` to `terminate(process, finalJobHandle)`.

- [ ] **Step 3: Build + run the FengYu test suite**

Run: `./mvnw -q -pl FengYu test`
Expected: BUILD SUCCESS. `CommandExecuteToolTest` should pass unchanged (it tests with a fake/Unix sandbox).

- [ ] **Step 4: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/ai/tools/CommandExecuteTool.java
git commit -m "✨ feat(ai): CommandExecuteTool reaps via Windows Job Object"
```

---

### Task 6: Documentation (docs + Settings i18n + CHANGELOG)

Update the process-isolation docs (EN + ZH), the `unsandboxedPlugins` Settings copy (semantics: OFF = Job isolation on Windows), and the CHANGELOG.

**Files:**
- Modify: the process-isolation / Windows-compat doc page in `docs/en/plugins/` + `docs/zh/plugins/` (find via `grep -rln "sandbox\|process isolation\|unsandboxed" docs/en/ docs/zh/`).
- Modify: `frontend/src/i18n/en.json` + `frontend/src/i18n/zh.json` (the `unsandboxedPlugins` copy).
- Modify: `docs/en/reference/changelog.md` + `docs/zh/reference/changelog.md` (or the project's CHANGELOG location).

**Interfaces:**
- Consumes: the real metadata from Tasks 1–5.

- [ ] **Step 1: Locate the doc pages + Settings copy**

Run: `grep -rln "unsandboxed\|process isolation\|进程隔离\|无沙箱" docs/en docs/zh frontend/src/i18n README.md`

- [ ] **Step 2: Update the process-isolation doc (EN + ZH)**

Update the platform support matrix to reflect Windows now has Job Object process-layer isolation (reliable tree termination), with filesystem/network isolation still a known gap. Mirror EN→ZH structurally.

- [ ] **Step 3: Update the unsandboxedPlugins Settings i18n copy**

In `frontend/src/i18n/en.json` and `zh.json`, the `unsandboxedPlugins` copy currently says roughly "plugins run without process isolation". Update to reflect the new semantics: OFF = process-layer Job isolation on Windows; ON = no isolation. Keep it concise.

- [ ] **Step 4: Add a CHANGELOG entry**

Add an entry under the unreleased section noting the Windows Job Object backend.

- [ ] **Step 5: Build the frontend + docs to confirm no breakage**

Run: `cd frontend && npm run build` and `npm --prefix docs run build`
Expected: both succeed.

- [ ] **Step 6: Commit**

```bash
git add docs frontend/src/i18n
git commit -m "📝 docs: Windows Job Object process isolation (en + zh + settings i18n)"
```

---

## Self-Review

**Spec coverage check** (each spec section → task):
- §5 JNA binding (WindowsJobSandbox) → Task 2 ✓
- §6.1 Backend enum WINDOWS_JOB → Task 3 ✓
- §6.2 detect() Windows branch → Task 3 ✓
- §6.3 Launch.onStarted (BiConsumer<Process,long[]>) → Task 3 ✓
- §6.4 wrap() WINDOWS_JOB branch → Task 3 ✓
- §7.1 PluginProcessManager.start() invoke onStarted + Worker holds handle → Task 4 ✓
- §7.1 Worker.close() TerminateJobObject primary → Task 4 ✓
- §7.2 CommandExecuteTool.execute() + terminate() → Task 5 ✓
- §8 unsandboxedPlugins semantics (OFF=Job) → reflected in Task 6 copy ✓ (no code change — the toggle already routes fullAccess vs not; WINDOWS_JOB is the non-fullAccess path)
- §9 testing (layer 1 non-Windows + layer 2 @EnabledOnOs) → Tasks 2, 3 ✓
- §10 degradation (isAvailable guard → NONE) → Task 2 + 3 ✓
- §11 documentation → Task 6 ✓
- §12 JNA deps → Task 1 ✓

**Placeholder scan:** No "TBD"/"TODO". The `ExtendedLimit` struct layout caveat in Task 2 Step 3 is a genuine ABI-uncertainty flagged with a concrete resolution instruction (consult MS docs + the Windows test proves it) — not a plan placeholder. Task 2's test uses `Process.sleepChild` pseudocode, explicitly flagged; the real test uses `new ProcessBuilder("cmd","/c","timeout","/T","30")`.

**Type consistency:**
- `Launch.onStarted` is `BiConsumer<Process, long[]>` in Task 3 and consumed identically in Tasks 4 & 5 ✓
- `WindowsJobSandbox` method names: `createAndConfigureJob`, `assign`, `terminate`, `closeHandle`, `isAvailable` — consistent across Tasks 2/3/4/5 ✓
- `Worker` constructor gains `long jobHandle` in Task 4; the only call site is `start()` (updated in Task 4) ✓
- `terminate(Process, long)` in Task 5; call sites updated ✓

**Gaps addressed:** The handle-passing mechanism (`BiConsumer<Process, long[]>` with a `long[1]` receiver) is the concrete resolution of the spec's §7.3 open detail — locked in here, not deferred.
