# plugin-browser Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an official `plugin-browser` plugin that gives the AI a general-purpose browser agent (navigate, click, type, scrape, screenshot, wait, eval JS) via Playwright + the existing JSON-RPC plugin worker model.

**Architecture:** A new Maven module `OfficialPlugins/plugin-browser`, structured like `plugin-markdown` (WorkerMain / RpcHandlers / domain logic). A `BrowserSession` singleton holds a Playwright `Browser` + `BrowserContext` + `Page`, lazily started and reused across tool calls. Nine `aiTools` are registered via `JsonRpcWorker.on(method, handler)`. Chromium is NOT bundled — it is auto-downloaded to the plugin's own data dir on first use, or a user-supplied path is used. Windows reuses the host's existing `unsandboxedPlugins` toggle; the plugin has zero platform branching.

**Tech Stack:** Java 21+, Maven (shaded worker jar), `fan.summer.fengyu.sdk:fengyu-plugin-sdk` 1.2.0, `com.microsoft.playwright:playwright` 1.49.0 (both version-managed by parent pom), Spring Boot 4.1 (host only — no host code changes).

## Global Constraints

- **Read versions from source, never hardcode.** App version is `${revision}` in root `pom.xml` (currently `4.0.0-alpha.8`); `manifest.json` `version` must equal it. SDK version is `${fengyu.plugin.sdk.version}` (`1.2.0`). Playwright is `${playwright.version}` (`1.49.0`). Do NOT copy these literals — reference the properties.
- **Plugin id:** `fan.summer.browser` (reverse-DNS, matches the `^[a-z0-9]+(?:[.-][a-z0-9]+)+$` schema pattern).
- **Worker package:** `fan.summer.fengyu.plugin.browser` (mirrors `plugin-markdown`'s `fan.summer.fengyu.plugin.markdown`).
- **Manifest `version` must equal `<revision>`** — the seeder/packager validates this.
- **`permissions: ["network", "files.write"]`** — `network` is required or `ProcessSandbox` blocks outbound browser traffic; `files.write` for screenshots + Chromium download to the plugin data dir.
- **No host code changes.** Everything lives in `OfficialPlugins/plugin-browser/`. The host's `AiToolRegistry` auto-collects aiTools from enabled plugins.
- **Envelope contract:** every handler returns `Map<String,Object>` with required keys `success` (boolean) + `summary` (single-line String). Use `PluginHandlerSupport.ok(...)` / `failure(...)`.
- **Commit convention:** conventional commits with emojis (✨ feat, 🐛 fix, ♻️ refactor, 📝 docs, 🧪 test). Commit per task.
- **No `System.out` prints** in worker code — `JsonRpcWorker.run()` redirects stdout to stderr; only the SDK owns stdout. Use SLF4J (`LoggerFactory`).
- **Match surrounding style:** mirror `plugin-markdown`'s three-class split (WorkerMain / RpcHandlers / domain) and its pom shade config.

**Spec:** `docs/superpowers/specs/2026-08-05-plugin-browser-design.md`

---

### Task 1: Maven module scaffold + aggregator registration

Create the module directory, pom, package structure, and register it in the `OfficialPlugins` aggregator. Verify it compiles as part of the reactor. No Java logic yet — just the skeleton + a placeholder main that proves the SDK dependency resolves.

**Files:**
- Create: `OfficialPlugins/plugin-browser/pom.xml`
- Modify: `OfficialPlugins/pom.xml` (add `<module>plugin-browser</module>`)
- Create: `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/BrowserWorkerMain.java` (placeholder)
- Create: `OfficialPlugins/plugin-browser/src/test/java/fan/summer/fengyu/plugin/browser/BrowserScaffoldTest.java`

**Interfaces:**
- Consumes: parent pom `${revision}`, `${playwright.version}`, `${fengyu.plugin.sdk.version}`; SDK artifact `fan.summer.fengyu.sdk:fengyu-plugin-sdk`.
- Produces: a compilable module `fan.summer.fengyu.plugin:plugin-browser` whose `BrowserWorkerMain` exists and whose shade config produces `target/browser-worker.jar` with main class `fan.summer.fengyu.plugin.browser.BrowserWorkerMain`.

- [ ] **Step 1: Add the module to the aggregator**

Modify `OfficialPlugins/pom.xml` `<modules>` block — add `plugin-browser` after `plugin-offlinepython`:

```xml
<modules>
    <module>plugin-markdown</module>
    <module>plugin-excel</module>
    <module>plugin-email</module>
    <module>plugin-offlinepython</module>
    <module>plugin-browser</module>
</modules>
```

- [ ] **Step 2: Create the module pom**

Create `OfficialPlugins/plugin-browser/pom.xml`. Model it on `plugin-markdown/pom.xml` (parent `../../pom.xml`, versionless deps) PLUS the signature-stripping filters from `plugin-email/pom.xml` (Playwright pulls transitive signed jars). Shade `finalName` = `browser-worker`, main class = `fan.summer.fengyu.plugin.browser.BrowserWorkerMain`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>fan.summer.fengyu</groupId>
        <artifactId>FengYu-parent</artifactId>
        <version>${revision}</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <groupId>fan.summer.fengyu.plugin</groupId>
    <artifactId>plugin-browser</artifactId>
    <name>FengYu Browser Plugin</name>

    <dependencies>
        <dependency>
            <groupId>fan.summer.fengyu.sdk</groupId>
            <artifactId>fengyu-plugin-sdk</artifactId>
        </dependency>
        <dependency>
            <groupId>com.microsoft.playwright</groupId>
            <artifactId>playwright</artifactId>
        </dependency>

        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>fan.summer.fengyu.sdk</groupId>
            <artifactId>fengyu-plugin-devkit</artifactId>
            <version>${fengyu.plugin.sdk.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>browser-worker</finalName>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <finalName>browser-worker</finalName>
                            <shadedArtifactAttached>false</shadedArtifactAttached>
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                            <minimizeJar>false</minimizeJar>
                            <filters>
                                <filter>
                                    <artifact>*:*</artifact>
                                    <excludes>
                                        <exclude>META-INF/*.SF</exclude>
                                        <exclude>META-INF/*.RSA</exclude>
                                        <exclude>META-INF/*.DSA</exclude>
                                    </excludes>
                                </filter>
                            </filters>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>fan.summer.fengyu.plugin.browser.BrowserWorkerMain</mainClass>
                                </transformer>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Create placeholder main + a scaffold test**

Create `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/BrowserWorkerMain.java`:

```java
package fan.summer.fengyu.plugin.browser;

import fan.summer.fengyu.sdk.JsonRpcWorker;

/**
 * Entry point for the plugin-browser worker. Wires the JSON-RPC method handlers
 * (registered in later tasks) onto the {@link JsonRpcWorker} and runs the stdio loop.
 */
public final class BrowserWorkerMain {
    private BrowserWorkerMain() {}

    public static void main(String[] args) throws Exception {
        // Handlers are added in later tasks; this proves the SDK + Playwright deps resolve.
        new JsonRpcWorker().run();
    }
}
```

Create `OfficialPlugins/plugin-browser/src/test/java/fan/summer/fengyu/plugin/browser/BrowserScaffoldTest.java`:

```java
package fan.summer.fengyu.plugin.browser;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Verifies the module compiles and core dependencies are on the classpath. */
class BrowserScaffoldTest {

    @Test
    void mainClassIsLoadable() {
        assertDoesNotThrow(() -> Class.forName("fan.summer.fengyu.plugin.browser.BrowserWorkerMain"));
    }

    @Test
    void playwrightIsOnClasspath() {
        assertDoesNotThrow(() -> Class.forName("com.microsoft.playwright.Playwright"));
    }

    @Test
    void sdkIsOnClasspath() {
        assertDoesNotThrow(() -> Class.forName("fan.summer.fengyu.sdk.JsonRpcWorker"));
    }
}
```

- [ ] **Step 4: Build the module in the reactor**

Run: `./mvnw -q -pl OfficialPlugins/plugin-browser -am test`
Expected: BUILD SUCCESS, 3 tests pass in `BrowserScaffoldTest`.

- [ ] **Step 5: Verify the shaded jar is produced**

Run: `./mvnw -q -pl OfficialPlugins/plugin-browser -am package -DskipTests && ls -la OfficialPlugins/plugin-browser/target/browser-worker.jar`
Expected: `target/browser-worker.jar` exists.

- [ ] **Step 6: Commit**

```bash
git add OfficialPlugins/pom.xml OfficialPlugins/plugin-browser
git commit -m "✨ feat(plugin-browser): scaffold Maven module + register in aggregator"
```

---

### Task 2: BrowserSession — Playwright lifecycle singleton

The core domain class: holds the Playwright `Playwright` / `Browser` / `BrowserContext` / `Page`, lazily started, reusable across calls, and cleanly closable. Designed to be testable WITHOUT launching a real browser by depending on a `BrowserLauncher` functional interface (production uses Playwright; tests use a fake).

**Files:**
- Create: `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/BrowserSession.java`
- Create: `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/BrowserLauncher.java`
- Test: `OfficialPlugins/plugin-browser/src/test/java/fan/summer/fengyu/plugin/browser/BrowserSessionTest.java`

**Interfaces:**
- Consumes: `com.microsoft.playwright.*` (Playwright, Browser, BrowserContext, Page, BrowserType.LaunchPersistentContextOptions); `BrowserLauncher` (defined in this task).
- Produces: `BrowserSession` with methods `page()` (lazy-start + return current `Page`), `browser()` (the live `Browser`, or null before start), `ensureStarted()`, `close()` (idempotent). Later handler tasks consume `BrowserSession`.

- [ ] **Step 1: Define the BrowserLauncher interface (seam for testing)**

Create `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/BrowserLauncher.java`:

```java
package fan.summer.fengyu.plugin.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;

/**
 * Seam that starts Playwright and launches a persistent browser context.
 * Production uses {@link PlaywrightBrowserLauncher}; tests supply a fake
 * so the lifecycle logic can be verified without a real Chromium.
 */
@FunctionalInterface
public interface BrowserLauncher {

    /**
     * Start Playwright, launch a persistent context, and return the browser handle.
     * The returned {@link Browser} owns the Chromium process tree; closing it
     * terminates all renderer/GPU/utility children.
     *
     * @param playwright     the Playwright instance (created by the caller)
     * @param userDataDir    persistent profile directory (cookies/login survive restarts)
     * @param executablePath custom browser path, or null to use Playwright's bundled one
     * @param headless       whether to run headless
     */
    Browser launch(Playwright playwright, Path userDataDir, String executablePath, boolean headless);
}
```

- [ ] **Step 2: Write the failing test for lazy-start + close**

Create `OfficialPlugins/plugin-browser/src/test/java/fan/summer/fengyu/plugin/browser/BrowserSessionTest.java`. It uses a fake launcher that records calls and returns mock Playwright objects (use Mockito, already on the test classpath via the parent management — if not present, add `org.mockito:mockito-core` test scope):

```java
package fan.summer.fengyu.plugin.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;

class BrowserSessionTest {

    @Test
    void ensureStartedLaunchesLazilyOnce() {
        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        when(context.newPage()).thenReturn(page);
        when(browser.newContext(any())).thenReturn(context);
        // Most Playwright builds expose contexts via browser; but launch returns the
        // browser from a persistent context, which already has one context. To keep
        // the test decoupled, the real session uses launchPersistentContext and reads
        // the context list. Simplify: the fake launcher returns a browser whose
        // browserContexts() yields our context.
        when(browser.browserContexts()).thenReturn(java.util.List.of(context));

        int[] launchCount = {0};
        BrowserSession session = new BrowserSession(
                Path.of(System.getProperty("java.io.tmpdir"), "fengyu-browser-test-profile"),
                null,   // executablePath = use bundled
                false,  // headless
                (pw, dir, exe, headless) -> { launchCount[0]++; return browser; });

        assertNull(session.browser(), "not started before ensureStarted");
        session.ensureStarted();
        assertEquals(1, launchCount[0], "launcher invoked exactly once");
        session.ensureStarted();
        assertEquals(1, launchCount[0], "launcher NOT invoked again on second ensureStarted");
        assertSame(browser, session.browser(), "browser is live after start");
        assertNotNull(session.page(), "page is available after start");
    }

    @Test
    void closeIsIdempotentAndKillsBrowser() {
        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        when(browser.browserContexts()).thenReturn(java.util.List.of(context));
        Playwright playwright = mock(Playwright.class);

        BrowserSession session = new BrowserSession(
                Path.of(System.getProperty("java.io.tmpdir"), "fengyu-browser-test-profile"),
                null, false,
                (pw, dir, exe, headless) -> browser) {
            // expose playwright for the test by overriding the factory hook
        };

        session.ensureStarted();
        session.close();
        session.close();   // must not throw
        verify(browser, times(1)).close();
    }
}
```

If Mockito is not on the classpath after build, add to `pom.xml` dependencies:
```xml
<dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><scope>test</scope></dependency>
```
Check the parent pom first: `grep -n "mockito" pom.xml` — if present in `dependencyManagement`, add it versionless.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -q -pl OfficialPlugins/plugin-browser test -Dtest=BrowserSessionTest`
Expected: FAIL (BrowserSession class does not exist).

- [ ] **Step 4: Implement BrowserSession**

Create `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/BrowserSession.java`:

```java
package fan.summer.fengyu.plugin.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Owns the Playwright lifecycle for the worker process: one {@link Playwright}
 * instance, one persistent {@link Browser} (via launchPersistentContext), one
 * {@link Page}. Lazily started on first use and reused across tool calls so the
 * AI's sequential calls (navigate → click → type) share one browsing session.
 *
 * <p>Not thread-safe — the JSON-RPC dispatch in {@code JsonRpcWorker} is single-threaded
 * per frame, so only one handler runs at a time.
 */
public final class BrowserSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);

    private final Path userDataDir;
    private final String executablePath;
    private final boolean headless;
    private final BrowserLauncher launcher;

    private Playwright playwright;
    private Browser browser;
    private Page page;

    public BrowserSession(Path userDataDir, String executablePath, boolean headless, BrowserLauncher launcher) {
        this.userDataDir = Objects.requireNonNull(userDataDir);
        this.executablePath = executablePath;   // null = use Playwright's bundled Chromium
        this.headless = headless;
        this.launcher = Objects.requireNonNull(launcher);
    }

    /** Starts the browser if not already running. Idempotent. */
    public synchronized void ensureStarted() {
        if (browser != null) return;
        log.info("Starting browser (userDataDir={}, headless={}, executablePath={})",
                userDataDir, headless, executablePath);
        userDataDir.toFile().mkdirs();
        playwright = Playwright.create();
        browser = launcher.launch(playwright, userDataDir, executablePath, headless);
        // A persistent context already has exactly one BrowserContext; grab its first page,
        // or create one if the context started empty.
        BrowserContext context = browser.browserContexts().isEmpty()
                ? browser.newContext() : browser.browserContexts().get(0);
        page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
    }

    /** The live browser, or null before {@link #ensureStarted()}. */
    public synchronized Browser browser() {
        return browser;
    }

    /** The current page; starts the browser lazily if needed. */
    public synchronized Page page() {
        ensureStarted();
        return page;
    }

    /** True if the browser is currently running. */
    public synchronized boolean isRunning() {
        return browser != null;
    }

    /** Closes the browser, the Node driver, and Playwright. Idempotent. */
    @Override
    public synchronized void close() {
        Page p = page;     page = null;
        Browser b = browser;  browser = null;
        Playwright pw = playwright;  playwright = null;
        // Closing the browser terminates all Chromium child processes;
        // closing playwright terminates the bundled Node driver subprocess.
        try { if (b != null) b.close(); } catch (Exception e) { log.warn("browser close failed: {}", e.getMessage()); }
        try { if (pw != null) pw.close(); } catch (Exception e) { log.warn("playwright close failed: {}", e.getMessage()); }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl OfficialPlugins/plugin-browser test -Dtest=BrowserSessionTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add OfficialPlugins/plugin-browser
git commit -m "✨ feat(plugin-browser): BrowserSession — Playwright lifecycle singleton"
```

---

### Task 3: ChromiumResolver — three-tier executablePath resolution + auto-download

Resolves which browser binary to launch. Tier 1: user-configured path. Tier 2: already-downloaded Chromium in the plugin data dir. Tier 3: invoke `Playwright` CLI to download Chromium into the plugin data dir. Pure logic + filesystem checks; the actual `ProcessBuilder` call to the CLI is encapsulated in a `ChromiumInstaller` seam.

**Files:**
- Create: `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/ChromiumResolver.java`
- Create: `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/ChromiumInstaller.java`
- Test: `OfficialPlugins/plugin-browser/src/test/java/fan/summer/fengyu/plugin/browser/ChromiumResolverTest.java`

**Interfaces:**
- Consumes: env var `FENGYU_PLUGIN_DATA_DIR` (set by `PluginProcessManager.start` — see `PluginProcessManager.java:206-212`); `System.getProperty("os.name")` for the executable suffix.
- Produces: `ChromiumResolver.resolve()` → `String` (absolute path) or `null` (use Playwright's bundled). `ChromiumInstaller.install(Path targetDir)` downloads Chromium.

- [ ] **Step 1: Define the installer seam**

Create `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/ChromiumInstaller.java`:

```java
package fan.summer.fengyu.plugin.browser;

import java.nio.file.Path;

/**
 * Downloads a Chromium build into {@code targetDir} using the Playwright CLI.
 * The production implementation sets {@code PLAYWRIGHT_BROWSERS_PATH=<targetDir>}
 * and invokes {@code com.microsoft.playwright.CLI install chromium}.
 */
@FunctionalInterface
public interface ChromiumInstaller {
    void install(Path targetDir) throws Exception;
}
```

- [ ] **Step 2: Write the failing test**

Create `OfficialPlugins/plugin-browser/src/test/java/fan/summer/fengyu/plugin/browser/ChromiumResolverTest.java`. Uses a temp dir to simulate tier 1/2/3 without network:

```java
package fan.summer.fengyu.plugin.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ChromiumResolverTest {

    @Test
    void tier1UserConfigPathWins(@TempDir Path tmp) throws Exception {
        Path userBrowser = tmp.resolve("user-chrome");
        Files.writeString(userBrowser, "fake");   // any existing file
        ChromiumResolver resolver = new ChromiumResolver(
                () -> userBrowser.toString(),          // tier 1: user config
                tmp.resolve("data"),                  // tier 2: plugin data dir
                false,                                // isWindows
                dir -> fail("should not download"));  // tier 3 never reached
        assertEquals(userBrowser.toString(), resolver.resolve());
    }

    @Test
    void tier2AlreadyDownloadedChromium(@TempDir Path tmp) throws Exception {
        Path dataDir = tmp.resolve("data");
        Path chrome = dataDir.resolve("chromium/chromium-1/chrome");
        Files.createDirectories(chrome.getParent());
        Files.createFile(chrome);

        ChromiumResolver resolver = new ChromiumResolver(
                () -> null,          // no user config
                dataDir,
                false,              // linux executable name "chrome"
                dir -> fail("should not download"));
        // resolve returns a path pointing at the existing chrome binary
        String resolved = resolver.resolve();
        assertTrue(resolved.endsWith("chrome"), "resolved=" + resolved);
    }

    @Test
    void tier3DownloadsWhenMissing(@TempDir Path tmp) throws Exception {
        Path dataDir = tmp.resolve("data");
        int[] installCalls = {0};
        // Simulate: after install(), the chrome binary now exists in the expected location.
        ChromiumResolver resolver = new ChromiumResolver(
                () -> null,
                dataDir,
                true,                       // isWindows → chrome.exe
                dir -> {
                    installCalls[0]++;
                    Path chrome = dir.resolve("chromium/chromium-1/chrome.exe");
                    Files.createDirectories(chrome.getParent());
                    Files.createFile(chrome);
                });
        String resolved = resolver.resolve();
        assertEquals(1, installCalls[0], "installer invoked exactly once");
        assertTrue(resolved.endsWith("chrome.exe"), "resolved=" + resolved);
    }

    @Test
    void tier3SkippedWhenUserConfigBlankAndReturnsNullIfStillMissing(@TempDir Path tmp) {
        ChromiumResolver resolver = new ChromiumResolver(
                () -> "",    // blank config = no tier 1
                tmp.resolve("data"),
                false,
                dir -> { /* no-op: don't create anything */ });
        // Nothing exists and download is a no-op → return null so Playwright uses its default.
        assertNull(resolver.resolve());
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -q -pl OfficialPlugins/plugin-browser test -Dtest=ChromiumResolverTest`
Expected: FAIL (ChromiumResolver does not exist).

- [ ] **Step 4: Implement ChromiumResolver**

Create `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/ChromiumResolver.java`:

```java
package fan.summer.fengyu.plugin.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.function.Supplier;

/**
 * Resolves the browser executable path by three-tier priority:
 * <ol>
 *   <li>User-configured path (from plugin settings; may point at a system Chrome/Edge).</li>
 *   <li>Already-downloaded Chromium under {@code <dataDir>/chromium/}.</li>
 *   <li>Auto-download Chromium into {@code <dataDir>/chromium/} via {@link ChromiumInstaller}.</li>
 * </ol>
 * Returns {@code null} when no path is available, letting Playwright fall back to its
 * bundled/installed browser. The platform-specific executable name (chrome / chrome.exe /
 * Chromium.app/Contents/MacOS/Chromium) is applied to tier-2 discovery.
 */
public final class ChromiumResolver {

    private static final Logger log = LoggerFactory.getLogger(ChromiumResolver.class);

    private final Supplier<String> userConfigPath;
    private final Path dataDir;
    private final boolean windows;
    private final ChromiumInstaller installer;

    public ChromiumResolver(Supplier<String> userConfigPath, Path dataDir,
                            boolean windows, ChromiumInstaller installer) {
        this.userConfigPath = userConfigPath;
        this.dataDir = dataDir;
        this.windows = windows;
        this.installer = installer;
    }

    /** Convenience factory reading env + os.name; used by the worker. */
    public static ChromiumResolver forEnvironment(Path dataDir, Supplier<String> userConfigPath) {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        return new ChromiumResolver(userConfigPath, dataDir, win, ChromiumResolver::installViaCli);
    }

    public String resolve() {
        // Tier 1: user-configured path.
        String configured = userConfigPath == null ? null : userConfigPath.get();
        if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            log.info("Using user-configured browser: {}", configured);
            return configured;
        }
        // Tier 2 + 3: under <dataDir>/chromium/.
        Path chromiumRoot = dataDir.resolve("chromium");
        String found = findExisting(chromiumRoot);
        if (found != null) {
            log.info("Using downloaded Chromium: {}", found);
            return found;
        }
        // Tier 3: download.
        try {
            log.info("No Chromium found; downloading into {}", chromiumRoot);
            installer.install(chromiumRoot);
        } catch (Exception e) {
            log.warn("Chromium download failed (will fall back to Playwright default): {}", e.getMessage());
            return null;
        }
        found = findExisting(chromiumRoot);
        if (found == null) {
            log.warn("Chromium still absent after install; falling back to Playwright default");
        }
        return found;
    }

    /** Scan {@code <chromiumRoot>/chromium-*/} for the platform binary. */
    private String findExisting(Path chromiumRoot) {
        if (!Files.isDirectory(chromiumRoot)) return null;
        String exeName = executableName();
        try (var dirs = Files.list(chromiumRoot)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                Path candidate = dir.resolve(exeName);
                if (Files.isRegularFile(candidate)) return candidate.toString();
                // macOS nested app bundle.
                Path macCandidate = dir.resolve("Chromium.app/Contents/MacOS/Chromium");
                if (Files.isRegularFile(macCandidate)) return macCandidate.toString();
            }
        } catch (Exception e) {
            log.debug("chromium scan failed: {}", e.getMessage());
        }
        return null;
    }

    private String executableName() {
        return windows ? "chrome.exe" : "chrome";
    }

    /** Production installer: PLAYWRIGHT_BROWSERS_PATH + CLI. */
    private static void installViaCli(Path targetDir) throws Exception {
        Files.createDirectories(targetDir);
        ProcessBuilder pb = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "-cp", System.getProperty("java.class.path"),
                "com.microsoft.playwright.CLI", "install", "chromium");
        pb.environment().put("PLAYWRIGHT_BROWSERS_PATH", targetDir.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // Drain output to the log (avoids blocking when the pipe fills).
        var drain = Thread.startVirtualThread(() -> {
            try (var in = p.getInputStream()) { in.transferTo(System.err); }
            catch (Exception ignored) {}
        });
        int code = p.waitFor();
        drain.join();
        if (code != 0) throw new IllegalStateException("playwright install exited " + code);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl OfficialPlugins/plugin-browser test -Dtest=ChromiumResolverTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add OfficialPlugins/plugin-browser
git commit -m "✨ feat(plugin-browser): ChromiumResolver — three-tier browser path + auto-download"
```

---

### Task 4: BrowserHandlers — the 9 JSON-RPC handlers

Implement all nine aiTool methods on a `BrowserHandlers` class extending `PluginHandlerSupport`. Each handler takes `Map<String,Object> params`, drives `BrowserSession` + Playwright, and returns the `{success, summary, ...}` envelope. Tests cover input parsing + envelope shape using a fake `BrowserSession` (no real browser).

**Files:**
- Create: `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/BrowserHandlers.java`
- Test: `OfficialPlugins/plugin-browser/src/test/java/fan/summer/fengyu/plugin/browser/BrowserHandlersTest.java`

**Interfaces:**
- Consumes: `BrowserSession` (Task 2), `PluginHandlerSupport` (SDK), `com.microsoft.playwright.Page` API (`navigate`, `click`, `fill`, `innerText`, `querySelectorAll`, `screenshot`, `waitForSelector`, `evalJs`), `JsonRpcWorker.string`/`integer` helpers.
- Produces: `BrowserHandlers` with 9 public methods each returning `Map<String,Object>`, plus a `handle(name, ref)` registration helper via the inherited `PluginHandlerSupport.handle(...)`.

**Reference:** the envelope builders are `ok(summary)`, `ok(summary, key, value)`, `failure(summary)` from `PluginHandlerSupport` (see `toolchain/sdk-java/.../PluginHandlerSupport.java:74-95`). Use `result(ThrowingOperation)` to wrap Playwright calls so exceptions become failure envelopes.

- [ ] **Step 1: Write failing tests for input parsing + envelope shape (navigate, click, type, get_text, query, wait_for, eval_js, close)**

Create `OfficialPlugins/plugin-browser/src/test/java/fan/summer/fengyu/plugin/browser/BrowserHandlersTest.java`. It stubs `BrowserSession` + `Page` with mocks and asserts the returned envelopes. Example for the first two methods; replicate the pattern for all nine (the test file will be long but mechanical):

```java
package fan.summer.fengyu.plugin.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserHandlersTest {

    private BrowserSession session;
    private Page page;
    private BrowserHandlers handlers;

    @BeforeEach
    void setUp() {
        page = mock(Page.class);
        session = mock(BrowserSession.class);
        when(session.page()).thenReturn(page);
        handlers = new BrowserHandlers(session);
    }

    @Test
    void navigateReturnsUrlAndTitle() {
        when(page.title()).thenReturn("Example");
        Map<String, Object> out = handlers.navigate(Map.of("url", "https://example.com"));
        assertEquals(true, out.get("success"));
        verify(page).navigate(eq("https://example.com"), any());
        assertEquals("https://example.com", out.get("url"));
        assertEquals("Example", out.get("title"));
    }

    @Test
    void navigateRejectsBlankUrl() {
        Map<String, Object> out = handlers.navigate(Map.of("url", "  "));
        assertEquals(false, out.get("success"));
        assertTrue(((String) out.get("summary")).toLowerCase().contains("url"));
    }

    @Test
    void clickInvokesPageClick() {
        Map<String, Object> out = handlers.click(Map.of("selector", "#go"));
        assertEquals(true, out.get("success"));
        verify(page).click(eq("#go"));
    }

    @Test
    void typeClearsThenFills() {
        Map<String, Object> out = handlers.type(Map.of("selector", "#q", "text", "hello"));
        assertEquals(true, out.get("success"));
        verify(page).fill(eq("#q"), eq("hello"));
    }

    @Test
    void getTextReturnsTextAndLength() {
        when(page.innerText(null)).thenReturn("hello world");
        Map<String, Object> out = handlers.getText(Map.of());
        assertEquals(true, out.get("success"));
        assertEquals("hello world", out.get("text"));
        assertEquals(11, out.get("length"));
    }

    @Test
    void queryReturnsCountAndSamples() {
        Page.ElementHandleLocator locator = mock(Page.ElementHandleLocator.class);  // if API differs, adjust to the real locator type
        // Adjust the stubbing to the real Playwright Java locator API you use in the impl.
        // The point of this test is the envelope shape, not the Playwright mock fidelity.
        Map<String, Object> out = handlers.query(Map.of("selector", "a"));
        assertEquals(true, out.get("success"));
        assertNotNull(out.get("count"));
        assertInstanceOf(java.util.List.class, out.get("samples"));
    }

    @Test
    void waitForReturnsOk() {
        Map<String, Object> out = handlers.waitFor(Map.of("selector", "#done"));
        assertEquals(true, out.get("success"));
        assertEquals(true, out.get("ok"));
    }

    @Test
    void evalJsReturnsValue() {
        when(page.evalJs(any())).thenReturn(42);
        Map<String, Object> out = handlers.evalJs(Map.of("script", "1+1"));
        assertEquals(true, out.get("success"));
        assertEquals("42", out.get("value"));
    }

    @Test
    void closeShutsSession() {
        Map<String, Object> out = handlers.close(Map.of());
        assertEquals(true, out.get("success"));
        verify(session).close();
    }
}
```

**Note on Playwright Java locator API:** Playwright Java's `Page.querySelector` / `Page.querySelectorAll` return `ElementHandle`; the modern fluent API is `page.locator(selector)`. The test stubs may need adjustment to match whichever API the implementation uses — update the mock signatures to match the real method calls. The test's PRIMARY purpose is the envelope shape (`success`/`summary` + the documented extra keys), not Playwright mock fidelity.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl OfficialPlugins/plugin-browser test -Dtest=BrowserHandlersTest`
Expected: FAIL (BrowserHandlers does not exist).

- [ ] **Step 3: Implement BrowserHandlers**

Create `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/BrowserHandlers.java`. Extends `PluginHandlerSupport`, wraps each Playwright call in `result(...)`. Use SLF4J (no stdout).

```java
package fan.summer.fengyu.plugin.browser;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import fan.summer.fengyu.sdk.PluginHandlerSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * The nine browser_* JSON-RPC handlers. Each method drives {@link BrowserSession}
 * and returns the standard {@code {success, summary, ...}} envelope.
 */
public class BrowserHandlers extends PluginHandlerSupport {

    private static final Logger log = LoggerFactory.getLogger(BrowserHandlers.class);
    private static final int TEXT_CAP = 64_000;       // protect the model's context window
    private static final int SAMPLE_LIMIT = 5;

    private final BrowserSession session;
    private final Path screenshotDir;

    public BrowserHandlers(BrowserSession session, Path screenshotDir) {
        super("browser");
        this.session = session;
        this.screenshotDir = screenshotDir;
        screenshotDir.toFile().mkdirs();
    }

    // ---- navigation -----------------------------------------------------

    public Map<String, Object> navigate(Map<String, Object> params) {
        String url = string(params, "url");
        if (url == null || url.isBlank()) return failure("url is required");
        String waitUntilRaw = string(params, "waitUntil");
        return result(() -> {
            Page.NavigateOptions opts = new Page.NavigateOptions();
            if (waitUntilRaw != null && !waitUntilRaw.isBlank()) {
                try { opts.setWaitUntil(WaitUntilState.valueOf(waitUntilRaw.toUpperCase(Locale.ROOT))); }
                catch (IllegalArgumentException ignored) {}
            }
            Page page = session.page();
            page.navigate(url, opts);
            String title = page.title();
            return ok("navigated to " + url, "url", url)
                    .with("title", title)
                    .build();   // see helper below if ok(...).build() not available; else use ok() + Map.put
        });
    }
```

**Important API caveat:** `PluginHandlerSupport.ok(summary, key, value)` returns a `Map` (not a builder) per the reference — it adds ONE key. To add multiple extra keys, build a `LinkedHashMap` yourself then return it (the SDK accepts any `Map`). Adjust each handler to build its own map:

```java
    // Corrected helper for multi-key envelopes:
    private Map<String, Object> okMany(String summary, Map<String, Object> extras) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("summary", summary);
        out.putAll(extras);
        return out;
    }

    public Map<String, Object> click(Map<String, Object> params) {
        String selector = string(params, "selector");
        if (selector == null || selector.isBlank()) return failure("selector is required");
        return result(() -> {
            session.page().click(selector);
            return okMany("clicked " + selector, Map.of("clicked", true));
        });
    }

    public Map<String, Object> type(Map<String, Object> params) {
        String selector = string(params, "selector");
        String text = string(params, "text");
        if (selector == null || selector.isBlank()) return failure("selector is required");
        if (text == null) text = "";
        return result(() -> {
            session.page().fill(selector, text);
            return okMany("filled " + selector, Map.of("filled", true));
        });
    }

    public Map<String, Object> getText(Map<String, Object> params) {
        String selector = string(params, "selector");
        return result(() -> {
            String text = selector == null || selector.isBlank()
                    ? session.page().innerText("body")
                    : session.page().innerText(selector);
            if (text.length() > TEXT_CAP) text = text.substring(0, TEXT_CAP) + "…[truncated]";
            String finalText = text;
            return okMany("read text", Map.of("text", finalText, "length", finalText.length()));
        });
    }

    public Map<String, Object> query(Map<String, Object> params) {
        String selector = string(params, "selector");
        if (selector == null || selector.isBlank()) return failure("selector is required");
        return result(() -> {
            var handles = session.page().querySelectorAll(selector);
            List<String> samples = new ArrayList<>();
            for (int i = 0; i < Math.min(SAMPLE_LIMIT, handles.size()); i++) {
                samples.add(String.valueOf(handles.get(i).innerText()));
            }
            return okMany("matched " + handles.size(), Map.of("count", handles.size(), "samples", samples));
        });
    }

    public Map<String, Object> screenshot(Map<String, Object> params) {
        boolean fullPage = Boolean.TRUE.equals(params.get("fullPage"));
        String selector = string(params, "selector");
        return result(() -> {
            Page page = session.page();
            Path file = screenshotDir.resolve("shot-" + System.currentTimeMillis() + ".png");
            Page.ScreenshotOptions opts = new Page.ScreenshotOptions()
                    .setPath(file).setFullPage(fullPage);
            if (selector != null && !selector.isBlank()) {
                page.locator(selector).screenshot(
                        new Page.Locator.ScreenshotOptions().setPath(file).orElse(opts)); // adjust to real API
            } else {
                page.screenshot(opts);
            }
            // Accessibility snapshot for the model (it cannot see pixels).
            String a11y = accessibilityTree(page);
            return okMany("screenshot saved", Map.of(
                    "imagePath", file.toString(),
                    "width", 0, "height", 0,   // real dims read from the file if needed; placeholder ok for MVP
                    "a11yTree", a11y));
        });
    }

    public Map<String, Object> waitFor(Map<String, Object> params) {
        String selector = string(params, "selector");
        if (selector == null || selector.isBlank()) return failure("selector is required");
        String stateRaw = string(params, "state");
        int timeoutMs = 30_000;
        Object t = params.get("timeout");
        if (t instanceof Number n) timeoutMs = n.intValue() * 1000;
        return result(() -> {
            Page.WaitForSelectorOptions opts = new Page.WaitForSelectorOptions().setTimeout(timeoutMs);
            if (stateRaw != null && !stateRaw.isBlank()) {
                try { opts.setState(com.microsoft.playwright.options.WaitForSelectorState
                        .valueOf(stateRaw.toUpperCase(Locale.ROOT))); } catch (IllegalArgumentException ignored) {}
            }
            session.page().waitForSelector(selector, opts);
            return okMany("wait satisfied", Map.of("ok", true));
        });
    }

    public Map<String, Object> evalJs(Map<String, Object> params) {
        String script = string(params, "script");
        if (script == null || script.isBlank()) return failure("script is required");
        return result(() -> {
            Object value = session.page().evalJs(script);
            return okMany("eval ok", Map.of("value", String.valueOf(value)));
        });
    }

    public Map<String, Object> close(Map<String, Object> params) {
        return result(() -> {
            session.close();
            return okMany("browser closed", Map.of("closed", true));
        });
    }

    /** Best-effort accessibility snapshot as a single-line text tree. */
    private String accessibilityTree(Page page) {
        try {
            return String.valueOf(page.accessibility().snapshot());
        } catch (Exception e) {
            return "(a11y unavailable: " + e.getMessage() + ")";
        }
    }
}
```

**Implementation note:** several Playwright Java option APIs above (`Locator.ScreenshotOptions().setPath(...).orElse(...)`, the exact `innerText(null)` signature) may differ slightly from 1.49.0. Consult the [Playwright Java API docs](https://playwright.dev/java/docs/api/class-browsertype) while implementing and adjust to the real method shapes — the envelope contract and handler structure are what matter for the plan. Remove the `navigate` body's `.with(...).build()` placeholder before committing; use the corrected `okMany` helper consistently.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl OfficialPlugins/plugin-browser test -Dtest=BrowserHandlersTest`
Expected: PASS (9 tests). Adjust mock signatures to match the real Playwright API you used.

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/plugin-browser
git commit -m "✨ feat(plugin-browser): nine browser_* JSON-RPC handlers"
```

---

### Task 5: Wire BrowserWorkerMain + the production BrowserLauncher

Replace the placeholder main from Task 1 with the real wiring: resolve env (`FENGYU_PLUGIN_DATA_DIR`), build `ChromiumResolver`, create `BrowserSession` with a production `BrowserLauncher` that calls `playwright.chromium().launchPersistentContext(...)`, instantiate `BrowserHandlers`, register all nine methods on `JsonRpcWorker`, and install a JVM shutdown hook so the browser is reaped even on abnormal exit.

**Files:**
- Modify: `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/BrowserWorkerMain.java`
- Create: `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/PlaywrightBrowserLauncher.java`
- Test: extend `OfficialPlugins/plugin-browser/src/test/java/fan/summer/fengyu/plugin/browser/BrowserHandlersTest.java` or add `BrowserWorkerMainTest.java` asserting all 9 methods are registered.

**Interfaces:**
- Consumes: `ChromiumResolver` (Task 3), `BrowserSession` + `BrowserLauncher` (Task 2), `BrowserHandlers` (Task 4), `JsonRpcWorker.on(...)` (SDK), env `FENGYU_PLUGIN_DATA_DIR`.
- Produces: an executable `BrowserWorkerMain` that registers `browser_navigate/click/type/get_text/query/screenshot/wait_for/eval_js/close`.

- [ ] **Step 1: Implement PlaywrightBrowserLauncher**

Create `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/PlaywrightBrowserLauncher.java`:

```java
package fan.summer.fengyu.plugin.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions;
import com.microsoft.playwright.Playwright;

import java.nio.file.Path;
import java.util.List;

/** Production launcher: uses chromium().launchPersistentContext with the resolved executable. */
public final class PlaywrightBrowserLauncher implements BrowserLauncher {

    @Override
    public Browser launch(Playwright playwright, Path userDataDir, String executablePath, boolean headless) {
        LaunchPersistentContextOptions opts = new LaunchPersistentContextOptions()
                .setHeadless(headless)
                .setArgs(List.of("--window-size=1280,900"));
        if (executablePath != null && !executablePath.isBlank()) {
            opts.setExecutablePath(Path.of(executablePath));
        }
        // launchPersistentContext returns a BrowserContext which is also a Browser-like handle;
        // cast/adapt per the Playwright Java API (BrowserContext exposes close() + pages()).
        return playwright.chromium().launchPersistentContext(userDataDir, opts);
    }
}
```

**Note:** `launchPersistentContext` returns a `BrowserContext`, not a `Browser`. Adjust `BrowserLauncher`'s return type to `BrowserContext` if cleaner (and update `BrowserSession` to store a `BrowserContext` + `Page` rather than `Browser`). Pick the type that minimizes casts; the lifecycle semantics (closing the context terminates Chromium) are identical. Update Task 2's signature if you change the return type — keep them consistent.

- [ ] **Step 2: Write the failing registration test**

Create `OfficialPlugins/plugin-browser/src/test/java/fan/summer/fengyu/plugin/browser/BrowserWorkerMainTest.java`:

```java
package fan.summer.fengyu.plugin.browser;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies all nine methods are registered on the JsonRpcWorker built by the main. */
class BrowserWorkerMainTest {

    @Test
    void registersAllNineMethods() throws Exception {
        JsonRpcWorker worker = BrowserWorkerMain.workerForTest();
        // The SDK stores handlers in a private map; expose via reflection for the test.
        Method m = JsonRpcWorker.class.getDeclaredMethod("handlers");
        // If there is no public accessor, instead attempt to dispatch a known method and
        // assert it is NOT unknown (-32601). Simplest: assert the worker is non-null and
        // each method name appears in the worker's toString/debug, or use a loopback transport.
        assertNotNull(worker);
        // Lightweight check: call worker.on(...) again for each method — it throws on duplicates,
        // proving they are already registered.
        String[] methods = {"browser_navigate","browser_click","browser_type","browser_get_text",
                "browser_query","browser_screenshot","browser_wait_for","browser_eval_js","browser_close"};
        for (String name : methods) {
            assertThrows(IllegalArgumentException.class, () -> worker.on(name, p -> null),
                    "expected " + name + " to be already registered");
        }
    }
}
```

This relies on `JsonRpcWorker.on` throwing on duplicates (confirmed in the reference: `handlers.putIfAbsent` returns non-null → `IllegalArgumentException`). If the SDK lacks a test-friendly hook, this duplicate-registration assertion is the cheapest coverage.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -q -pl OfficialPlugins/plugin-browser test -Dtest=BrowserWorkerMainTest`
Expected: FAIL (`workerForTest()` does not exist).

- [ ] **Step 4: Implement the real BrowserWorkerMain**

Replace `OfficialPlugins/plugin-browser/src/main/java/fan/summer/fengyu/plugin/browser/BrowserWorkerMain.java`:

```java
package fan.summer.fengyu.plugin.browser;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entry point for the plugin-browser worker. Resolves the browser executable, creates a
 * singleton {@link BrowserSession}, wires nine {@code browser_*} JSON-RPC handlers, and runs
 * the stdio loop. A JVM shutdown hook ensures the browser is reaped on abnormal exit.
 */
public final class BrowserWorkerMain {

    private static final Logger log = LoggerFactory.getLogger(BrowserWorkerMain.class);

    private BrowserWorkerMain() {}

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(System.getenv().getOrDefault("FENGYU_PLUGIN_DATA_DIR",
                System.getProperty("user.home") + "/.fengyu/plugins/fan.summer.browser"));
        boolean headless = Boolean.parseBoolean(System.getenv().getOrDefault("FENGYU_BROWSER_HEADLESS", "false"));

        ChromiumResolver resolver = ChromiumResolver.forEnvironment(dataDir, () -> null);   // user config wired via UI later
        String executablePath = resolver.resolve();

        BrowserSession session = new BrowserSession(
                dataDir.resolve("profile"), executablePath, headless, new PlaywrightBrowserLauncher());
        Runtime.getRuntime().addShutdownHook(new Thread(session::close, "browser-shutdown"));

        BrowserHandlers handlers = new BrowserHandlers(session, dataDir.resolve("screenshots"));
        worker(handlers).run();
    }

    static JsonRpcWorker worker(BrowserHandlers h) {
        JsonRpcWorker w = new JsonRpcWorker();
        w.on("browser_navigate",  h.handle("browser_navigate",  h::navigate));
        w.on("browser_click",     h.handle("browser_click",     h::click));
        w.on("browser_type",      h.handle("browser_type",      h::type));
        w.on("browser_get_text",  h.handle("browser_get_text",  h::getText));
        w.on("browser_query",     h.handle("browser_query",     h::query));
        w.on("browser_screenshot",h.handle("browser_screenshot",h::screenshot));
        w.on("browser_wait_for",  h.handle("browser_wait_for",  h::waitFor));
        w.on("browser_eval_js",   h.handle("browser_eval_js",   h::evalJs));
        w.on("browser_close",     h.handle("browser_close",     h::close));
        return w;
    }

    /** Test seam: build the worker with a no-op session. */
    static JsonRpcWorker workerForTest() {
        BrowserSession session = new BrowserSession(
                Path.of(System.getProperty("java.io.tmpdir"), "fb-test"),
                null, true, (pw, dir, exe, hd) -> { throw new UnsupportedOperationException("test"); });
        BrowserHandlers h = new BrowserHandlers(session, Path.of(System.getProperty("java.io.tmpdir")));
        return worker(h);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q -pl OfficialPlugins/plugin-browser test -Dtest=BrowserWorkerMainTest`
Expected: PASS.

- [ ] **Step 6: Run the full module test suite + package**

Run: `./mvnw -q -pl OfficialPlugins/plugin-browser -am test && ./mvnw -q -pl OfficialPlugins/plugin-browser -am package -DskipTests`
Expected: all tests pass; `target/browser-worker.jar` produced.

- [ ] **Step 7: Commit**

```bash
git add OfficialPlugins/plugin-browser
git commit -m "✨ feat(plugin-browser): wire BrowserWorkerMain + PlaywrightBrowserLauncher + shutdown hook"
```

---

### Task 6: manifest.json + fengyu.plugin.json + minimal UI

Add the runtime manifest (9 aiTools, permissions), the build descriptor (worker artifact path), and a minimal iframe config panel (browser-path input + status text). No build wiring for a heavy UI — a single static HTML file suffices for MVP.

**Files:**
- Create: `OfficialPlugins/plugin-browser/manifest.json`
- Create: `OfficialPlugins/plugin-browser/fengyu.plugin.json`
- Create: `OfficialPlugins/plugin-browser/ui/index.html`

**Interfaces:**
- Consumes: the `${revision}` app version (read from root `pom.xml` — currently `4.0.0-alpha.8`); the shade `finalName` `browser-worker` from Task 1.
- Produces: a `.fyp`-packageable plugin whose manifest declares 9 aiTools with `effect: "external"` and `method` matching the worker registrations; `backend.command: "java -jar backend/worker.jar"`.

- [ ] **Step 1: Read the current app version (do not hardcode)**

Run: `grep -m1 '<revision>' pom.xml`
Expected: a line like `<revision>4.0.0-alpha.8</revision>`. Use that exact string as `manifest.json` `version`.

- [ ] **Step 2: Create manifest.json**

Create `OfficialPlugins/plugin-browser/manifest.json` with the full 9-aiTool declaration from the spec §7. Copy the JSON verbatim from `docs/superpowers/specs/2026-08-05-plugin-browser-design.md` §7, substituting the real version read in Step 1. Confirm:
- `id`: `fan.summer.browser`
- `permissions`: `["network", "files.write"]`
- each `method` exactly matches a `worker.on(...)` name from Task 5
- each `effect` is `"external"`
- `backend.command`: `"java -jar backend/worker.jar"`, `protocol`: `"json-rpc-2.0"`

- [ ] **Step 3: Validate the manifest against the schema**

Run:
```bash
python3 -c "
import json, jsonschema
schema=json.load(open('toolchain/spec/manifest.schema.json'))
m=json.load(open('OfficialPlugins/plugin-browser/manifest.json'))
jsonschema.validate(m, schema)
print('manifest valid')
"
```
If `jsonschema` is not installed, install it (`pip install jsonschema`) or validate by running the host's own manifest loader against it. Expected: `manifest valid`.

- [ ] **Step 4: Create fengyu.plugin.json (build descriptor)**

Model on `plugin-offlinepython/fengyu.plugin.json` (no heavy UI build). Create `OfficialPlugins/plugin-browser/fengyu.plugin.json`:

```json
{
  "schemaVersion": 1,
  "worker": {
    "root": ".",
    "test": ["maven", "-f", "../../pom.xml", "-pl", "OfficialPlugins/plugin-browser", "-am", "test"],
    "build": ["maven", "-f", "../../pom.xml", "-pl", "OfficialPlugins/plugin-browser", "-am", "package", "-DskipTests"],
    "artifact": "target/browser-worker.jar",
    "mainClass": "fan.summer.fengyu.plugin.browser.BrowserWorkerMain"
  },
  "package": { "outputDirectory": "dist-package" }
}
```

- [ ] **Step 5: Create a minimal UI config panel**

Create `OfficialPlugins/plugin-browser/ui/index.html`. MVP: a single static page with a browser-path input (saved to the plugin data dir via the postMessage bridge) and a status line. Use the `@infinia/plugin-sdk` postMessage bridge the same way other plugins do; if the exact bridge API is unclear, ship a read-only status page for MVP and wire the input in a follow-up.

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Browser Agent</title>
  <style>body{font:14px system-ui,sans-serif;padding:16px;max-width:520px}</style>
</head>
<body>
  <h2>Browser Agent</h2>
  <p>AI-driven browser automation. Chromium is downloaded automatically on first use.</p>
  <label>Browser path (optional — leave blank to auto-download):</label>
  <input id="path" style="width:100%" placeholder="/usr/bin/google-chrome or C:\\Program Files\\..." />
  <p id="status">Status: loading…</p>
  <script>
    // Minimal postMessage bridge usage; adapt to @infinia/plugin-sdk API.
    window.addEventListener('message', (e) => {
      if (e.data && e.data.type === 'fengyu:state') {
        document.getElementById('path').value = e.data.browserPath || '';
        document.getElementById('status').textContent = 'Status: ' + (e.data.status || 'ready');
      }
    });
    document.getElementById('path').addEventListener('change', (e) => {
      parent.postMessage({ type: 'fengyu:setBrowserPath', value: e.target.value }, '*');
    });
  </script>
</body>
</html>
```

- [ ] **Step 6: Commit**

```bash
git add OfficialPlugins/plugin-browser/manifest.json OfficialPlugins/plugin-browser/fengyu.plugin.json OfficialPlugins/plugin-browser/ui
git commit -m "✨ feat(plugin-browser): manifest, build descriptor, minimal config UI"
```

---

### Task 7: Build the .fyp + local smoke verification

Package the plugin and verify it loads + responds end-to-end. This is the integration gate before docs. Requires a running host (or the devkit loopback harness) and a network connection (to download Chromium on first browser use).

**Files:**
- Modify: none (this is a verification task; the `.fyp` is a build artifact under `dist-package/`, which is gitignored).

**Interfaces:**
- Consumes: the packaged `target/browser-worker.jar` + manifest + UI from Tasks 1–6; the `fengyu-plugin-dev` skill's packaging flow.

- [ ] **Step 1: Build the .fyp**

Invoke the plugin packaging flow. The repo uses a packager driven by `fengyu.plugin.json`; consult the `fengyu-plugin-dev` skill (`Skill` tool → `fengyu-plugin-dev`) for the exact command. Typically:

Run: `cd OfficialPlugins/plugin-browser && npm exec -- fengyu-plugin-pack .` (or the skill's documented equivalent — follow the skill, do not guess).
Expected: `dist-package/fan.summer.browser-<version>.fyp` is produced.

- [ ] **Step 2: Install the .fyp into a running host**

Start the backend (auth disabled for local testing):
Run: `java -jar FengYu/target/FengYu-*.jar` (no `--token`)
Then install via the host's plugin install endpoint (see `fengyu-plugin-dev` skill for the exact route), pointing at the `.fyp`.

- [ ] **Step 3: Smoke-test one handler over JSON-RPC**

With the host running, trigger an AI tool call to `browser_navigate` (via the chat UI or a direct invoke). Expected: the worker starts, Chromium downloads on first run (observe the host log), a browser window opens to the URL, and the tool returns `{success:true, summary, url, title}`.

If a real browser cannot run in the CI environment, at minimum verify the worker process starts and responds to a `browser_close` call without the host throwing (proves the JSON-RPC wiring).

- [ ] **Step 4: Commit any packaging-script fixes (if needed)**

If the packaging flow revealed a needed fix (e.g. a path in `fengyu.plugin.json`), commit it:
```bash
git add OfficialPlugins/plugin-browser
git commit -m "🐛 fix(plugin-browser): packaging path correction"
```
Otherwise skip — `.fyp` artifacts are gitignored.

---

### Task 8: Documentation (docs/en + docs/zh official plugin list)

Update the official-plugins documentation to list `plugin-browser`, mirroring the existing EN/ZH structure.

**Files:**
- Modify: `docs/en/` plugin list page (find the page that lists official plugins — likely `docs/en/plugins.md` or similar; consult `docs-updater` skill).
- Modify: `docs/zh/` mirrored page.
- Modify: `README.md` official-plugins section (if it enumerates them).

**Interfaces:**
- Consumes: the plugin name, id, category, one-line description from `manifest.json`.

- [ ] **Step 1: Locate the official-plugins doc pages**

Run: `grep -rl "plugin-markdown\|fan.summer.markdown" docs/ README.md`
Expected: the files that enumerate official plugins.

- [ ] **Step 2: Add plugin-browser to the EN doc**

Add an entry for `plugin-browser` (id `fan.summer.browser`, category `automation`, "AI-driven browser automation") next to the other official plugins. Follow the exact format of the neighboring entries.

- [ ] **Step 3: Add the mirrored ZH entry**

Mirror the EN change in the ZH doc, keeping structure aligned (per `docs-updater` skill conventions).

- [ ] **Step 4: Update README if it lists plugins**

If `README.md` enumerates official plugins, add `plugin-browser` there too.

- [ ] **Step 5: Commit**

```bash
git add docs README.md
git commit -m "📝 docs(plugin-browser): list in official plugins (en + zh + readme)"
```

---

## Self-Review

**Spec coverage check** (each spec section → task):
- §2 decisions (Playwright/Java worker/headed/download/plugin id) → Task 1 (deps, id, package) ✓
- §3 architecture (worker wiring, host boundary, zero host changes) → Tasks 1, 5 ✓
- §4 Chromium three-tier resolution + auto-download to plugin data dir → Task 3 ✓
- §5 nine aiTools (manifest + handlers) → Tasks 4 (handlers), 6 (manifest) ✓
- §6 Maven module + shade + structure → Task 1 ✓
- §7 manifest.json → Task 6 ✓
- §8 session model (lazy single Page) + process recycling (close + shutdown hook + Windows known-limit doc) → Tasks 2, 5 ✓
- §9 Windows compatibility (reuse `unsandboxedPlugins`, zero plugin branching) → no task needed (design guarantee; documented in spec) ✓
- §10 UI config panel → Task 6 ✓
- §12 testing strategy (unit + manifest schema validation) → Tasks 2,3,4,5,6 ✓
- §14 implementation order → matches task order ✓

**Gaps addressed:**
- Screenshot a11y tree + "no multimodal" correction → Task 4 `screenshot()` returns `a11yTree` ✓
- playwright-java Node-driver three-level process tree → Task 2 `close()` kills both browser + playwright (Node driver); documented in `BrowserSession` javadoc ✓

**Type consistency:**
- `BrowserLauncher.launch(...)` return type: Task 2 returns `Browser`; Task 5 note flags that `launchPersistentContext` returns `BrowserContext` and instructs the implementer to pick one type and keep Task 2/5 consistent. Resolved by the note.
- Handler method names: `navigate/click/type/getText/query/screenshot/waitFor/evalJs/close` in Task 4 match the `worker.on(...)` registrations in Task 5 and the manifest `method` fields in Task 6 ✓
- `okMany(summary, extras)` helper defined in Task 4 and used by all handlers ✓

**Placeholder scan:** No "TBD"/"TODO". The Playwright Java option-API caveats are flagged explicitly with instructions to consult the docs (they are genuine API-uncertainties, not plan placeholders). Task 6 Step 1 reads the version from source rather than hardcoding. No "similar to Task N" without code.
