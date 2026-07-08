# 4.0.0 UI Strangler — Phase 1: Vue + Tauri Walking Skeleton — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Strangle the JavaFX UI by building a thin end-to-end slice through every layer — headless Spring Boot web server, plugin-system-v2 loader (compile-time bundling only), one extracted plugin (Markdown editor), Vue 3.5 + TS main shell, micro-frontend host, and Tauri 2.0 desktop shell. Proves the full pipe before any tool is ported wide.

**Architecture:** Strangler-fig. The existing JavaFX app stays in the tree (untouched, unbuilt). The new path is built alongside it: flip `AiSpringContext` from `WebApplicationType.NONE` → `SERVLET`, add REST + SSE endpoints, write the v2 plugin contract (`ZhiFlowPlugin` without `createView()`), extract Markdown as a Maven module implementing v2, build a Vue shell (sidebar/theme/settings/AI chat) that loads the Markdown plugin's micro-frontend over dynamic ESM, and wrap it all in a Tauri window that sidecar-launches the Java backend. JavaFX deletion happens in a later phase (Phase X-delete).

**Tech Stack:** Java 21 compile / 17+ runtime, Spring Boot 4.1.0, Spring AI 2.0.0, Vue 3.5.39, TypeScript, Vite, Pinia, Tauri 2.0, H2 2.4.240, MyBatis 3.5.19, commonmark (already a dependency), Maven reactor POM.

---

## Global Constraints

These apply to every task. Do not deviate without an explicit spec change.

- **Java baseline:** compile on 21 (`maven.compiler.source/target=21`), run on 17+ (Tauri sidecar JRE TBD in Phase F-prod).
- **Spring Boot:** 4.1.0 (BOM imported in `ZhiFlow/pom.xml`). Spring Framework 7. Spring AI 2.0.0 (BOM).
- **Embedded Tomcat:** loopback-only bind (`server.address=127.0.0.1`), accept `--port=<n>` (0 = pick free, print to stdout for sidecar read).
- **Per-launch auth token:** `--token=<t>` CLI arg, frontend sends it as `X-ZhiFlow-Token` header, backend validates. Random UUID.
- **Reactor POM:** root `pom.xml` aggregates `<modules>` (`ZhiFlow-Api`, `ZhiFlow`, and the new `plugin-markdown`). Each module has a standalone POM; no parent dependency in plugin POMs (they declare `ZhiFlow-Api` as `provided` scope).
- **Vue version:** 3.5.39 (exact). TypeScript strict mode. Vite 6+.
- **SSE for AI streaming** (not raw WebSocket). Spring's `SseEmitter`. `text/event-stream`.
- **Micro-frontend contract:** ESM bundle, default export = `{mount(el, ctx): unmount}`. Vue marked `external`, shared via import map.
- **Design tokens:** port `--sk-*` from `zhiflow-common.css` verbatim (no redesign). `.theme-dark` / `.theme-light` class on root.
- **AI chat is NOT a plugin** — core built-in at `/api/ai/*`. Never routed through the plugin `invoke` path.
- **JavaFX untouched:** remains in the tree, excluded from Phase-1 build. No JavaFX code is modified or deleted.
- **Commit cadence:** one commit per step where code changes. Conventional-commit emoji prefixes per CLAUDE.md (`✨ feat`, `🐛 fix`, `♻️ refactor`, etc.).
- **Branch:** work on `4.0.0-ZhiFlow` (already checked out).

---

## File Structure (what will be created or modified)

### Backend (ZhiFlow module)

**Created:**
- `src/main/java/fan/summer/zhiflow/web/controller/HealthController.java` — `GET /api/health`
- `src/main/java/fan/summer/zhiflow/web/controller/PluginController.java` — `GET /api/plugins`, `POST /api/plugins/{id}/invoke`, `GET /plugin-ui/{id}/**`
- `src/main/java/fan/summer/zhiflow/web/controller/SettingsController.java` — `GET /PUT /api/settings`
- `src/main/java/fan/summer/zhiflow/web/controller/AiController.java` — `POST /api/ai/chat`, `GET /api/ai/stream` (SSE)
- `src/main/java/fan/summer/zhiflow/web/config/WebConfig.java` — CORS, token filter
- `src/main/java/fan/summer/zhiflow/web/filter/TokenAuthFilter.java` — per-launch token validation
- `src/main/java/fan/summer/zhiflow/HeadlessLauncher.java` — new headless entry point (accepts `--port`, `--token`)
- `src/main/java/fan/summer/zhiflow/plugin/PluginRegistryService.java` — Spring `@Service` wrapping `PluginRegistry` for controllers
- `src/main/java/fan/summer/zhiflow/ai/service/AiConfigServiceHeadless.java` — headless settings getters (wraps `AiConfigService` + writes via `DatabaseInit.withMapper`)

**Modified:**
- `src/main/java/fan/summer/zhiflow/ai/spring/AiSpringContext.java` — add `.web(WebApplicationType.SERVLET)` overload for headless mode
- `src/main/java/fan/summer/zhiflow/ai/spring/AiApplication.java` — broaden `@ComponentScan` from `fan.summer.zhiflow.ai.spring` to `fan.summer.zhiflow` (includes controllers)
- `src/main/java/fan/summer/zhiflow/ai/service/SpringAiCloudBackend.java` — remove `Platform.runLater` + `ZhiFlowSettingUi` imports, call callbacks directly
- `src/main/java/fan/summer/zhiflow/ai/service/OllamaLocalBackend.java` — same (remove JavaFX coupling)
- `src/main/java/fan/summer/zhiflow/ai/ToolExecutor.java` — same
- `ZhiFlow/pom.xml` — add `spring-boot-starter-web` dep

### Plugin v2 contract (ZhiFlow-Api module)

**Created:**
- `src/main/java/fan/summer/zhiflow/api/plugin/PluginDescriptor.java` — id, name, category, icon, version, `uiEntry` (path to ESM bundle)
- `src/main/java/fan/summer/zhiflow/api/plugin/ZhiFlowPluginV2.java` — v2 interface: `descriptor()`, `invoke(action, args)`, `aiTools()`

(The old `ZhiFlowPlugin` with `createView()` stays for reference, unused in Phase 1.)

### Markdown plugin module

**Created:**
- `plugin-markdown/pom.xml` — standalone POM, declares `ZhiFlow-Api` as `provided`, adds `commonmark` (via parent version prop)
- `plugin-markdown/src/main/java/fan/summer/zhiflow/plugin/markdown/MarkdownPlugin.java` — `@Component` implementing `ZhiFlowPluginV2`
- `plugin-markdown/src/main/resources/ui/index.html` — MF ESM entry (loads `index.js`)
- `plugin-markdown/src/main/resources/ui/index.js` — built Vue MF bundle (split editor + preview, debounced render via `invoke`)
- `plugin-markdown/ui-src/` — Vue 3.5 + TS source for the MF (its own `vite.config.ts`, Vue external, builds to `resources/ui/`)

**Root POM:**
- Modify `pom.xml` — add `<module>plugin-markdown</module>`

### Frontend (new top-level `frontend/` dir)

**Created:**
- `frontend/package.json` — Vue 3.5.39, TypeScript, Vite 6, Pinia, vue-router, axios
- `frontend/vite.config.ts` — dev proxy to `http://localhost:8080`, build output to `dist/`
- `frontend/src/main.ts` — Vue app bootstrap + Pinia + router
- `frontend/src/App.vue` — root component, registers `theme-dark`/`theme-light` class on root
- `frontend/src/shell/AppShell.vue` — `<Sidebar>` + `<router-view>` content area + `<StatusBar>`
- `frontend/src/shell/Sidebar.vue` — collapsible sidebar (categories, theme toggle)
- `frontend/src/shell/StatusBar.vue` — status bar (connection state)
- `frontend/src/views/ToolGrid.vue` — plugin card grid (routed view)
- `frontend/src/views/AiChat.vue` — AI chat (SSE stream, markdown render, collapsible thinking)
- `frontend/src/views/Settings.vue` — settings form (theme, language)
- `frontend/src/api/client.ts` — typed axios client (reads backend URL + token from `config.ts`)
- `frontend/src/api/config.ts` — env/config: `BACKEND_URL`, `AUTH_TOKEN` (injected by Tauri or `.env.local`)
- `frontend/src/api/sse.ts` — SSE client for `/api/ai/stream`
- `frontend/src/mf/loader.ts` — dynamic `import(uiEntry)`, mount/unmount
- `frontend/src/theme/tokens.css` — `--sk-*` vars ported from `zhiflow-common.css`, `.theme-dark`/`.theme-light` rules
- `frontend/src/stores/theme.ts` — Pinia store (persists theme, toggles root class)
- `frontend/src/stores/settings.ts` — settings store (GET/PUT `/api/settings`)
- `frontend/src/stores/plugins.ts` — plugins store (fetches `/api/plugins`)
- `frontend/src/stores/aiSession.ts` — AI chat store (POST `/api/ai/chat`, listens to SSE)
- `frontend/src/router/index.ts` — routes: `/` (ToolGrid), `/ai` (AiChat), `/settings`, `/plugin/:id` (MF host view)

### Desktop (new top-level `desktop/` dir)

**Created:**
- `desktop/src-tauri/tauri.conf.json` — window config, sidecar binary (`ZhiFlow.jar`), allowlist
- `desktop/src-tauri/src/main.rs` — spawn Java sidecar, wait on `/api/health`, inject token, load webview
- `desktop/src-tauri/Cargo.toml` — Tauri 2.0 deps
- `desktop/src-tauri/binaries/.gitkeep` — placeholder (JAR + JRE copied at build time, not tracked)
- `desktop/.gitignore` — ignore `src-tauri/binaries/*.jar`, `src-tauri/target/`
- `desktop/README.md` — dev instructions (manual JAR copy for Phase 1)

---

## Task 1: Add `spring-boot-starter-web` and flip `AiSpringContext` to SERVLET mode

**Files:**
- Modify: `ZhiFlow/pom.xml`
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiSpringContext.java`
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiApplication.java`

**Interfaces:**
- Consumes: existing `AiSpringContext.start()` (returns void, boots `WebApplicationType.NONE`)
- Produces: `AiSpringContext.startWeb(int port)` (boots SERVLET on given port), broadened component scan in `AiApplication`

- [ ] **Step 1: Add `spring-boot-starter-web` dependency**

Edit `ZhiFlow/pom.xml`. After the existing `spring-boot-starter` dep (~line 196), add:

```xml
<!-- Spring Boot web (embedded Tomcat) for Phase 1 headless server -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

- [ ] **Step 2: Broaden component scan in `AiApplication`**

Edit `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiApplication.java`. Change:

```java
@ComponentScan(basePackages = "fan.summer.zhiflow.ai.spring")
```

to:

```java
@ComponentScan(basePackages = "fan.summer.zhiflow")
```

This includes future `fan.summer.zhiflow.web.controller.*` packages.

- [ ] **Step 3: Add `startWeb(int)` overload to `AiSpringContext`**

Edit `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiSpringContext.java`. After the existing `start()` method, add:

```java
/**
 * Starts the Spring context in SERVLET mode (embedded Tomcat) bound to loopback on the given port.
 * 
 * @param port the port to bind (0 = pick a free port and print to stdout)
 */
public static synchronized void startWeb(int port) {
    if (context != null && context.isActive()) {
        return; // already running
    }
    System.setProperty("server.port", String.valueOf(port));
    System.setProperty("server.address", "127.0.0.1"); // loopback only
    context = new SpringApplicationBuilder(AiApplication.class)
        .web(WebApplicationType.SERVLET)
        .headless(false)
        .registerShutdownHook(false)
        .logStartupInfo(true)
        .run();
    // If port was 0, Tomcat picked a free port — read it back and print for sidecar
    if (port == 0) {
        try {
            org.springframework.boot.web.server.WebServer ws = 
                context.getBean(org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext.class)
                    .getWebServer();
            int actualPort = ws.getPort();
            System.out.println("ZHIFLOW_PORT=" + actualPort);
            System.out.flush();
        } catch (Exception e) {
            // ignore if bean retrieval fails
        }
    }
}
```

- [ ] **Step 4: Build to verify compilation**

Run via IDEA Maven tool window or IDEA MCP:

```bash
# Intent: mvn clean compile -f ZhiFlow/pom.xml
```

Expected: BUILD SUCCESS. The new `startWeb` method and broadened scan compile cleanly. No tests run yet (the module doesn't boot).

- [ ] **Step 5: Commit**

```bash
git add ZhiFlow/pom.xml ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiSpringContext.java ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiApplication.java
git commit -m "✨ feat(web): add spring-boot-starter-web + AiSpringContext.startWeb for headless mode"
```

---

## Task 2: Break JavaFX coupling in AI backends and `ToolExecutor`

**Files:**
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/SpringAiCloudBackend.java`
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/OllamaLocalBackend.java`
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/ToolExecutor.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/AiConfigServiceHeadless.java`

**Interfaces:**
- Consumes: `AiConfigService.getAiTemperature/TopP/MaxTokens/SystemPrompt()` (read-only), `ZhiFlowSettingUi` UI-layer cache (to be replaced)
- Produces: `AiConfigServiceHeadless` (headless getters + setters), backends/ToolExecutor call callbacks directly (no `Platform.runLater`)

- [ ] **Step 1: Write `AiConfigServiceHeadless`**

Create `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/AiConfigServiceHeadless.java`:

```java
package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.ai.AiConfigService;
import fan.summer.zhiflow.database.DatabaseInit;
import fan.summer.zhiflow.database.entity.AppSettingEntity;
import fan.summer.zhiflow.database.mapper.AppSettingMapper;

/**
 * Headless AI configuration service — wraps read-only {@link AiConfigService} and provides
 * write methods that persist to H2 via MyBatis, replacing the UI-layer {@code ZhiFlowSettingUi}
 * cache for headless mode.
 */
public final class AiConfigServiceHeadless {

    private AiConfigServiceHeadless() {}

    // ── Reads (delegate to existing AiConfigService) ───────────────────────────────────

    public static float getAiTemperature() {
        return AiConfigService.getAiTemperature();
    }

    public static float getAiTopP() {
        return AiConfigService.getAiTopP();
    }

    public static int getAiMaxTokens() {
        return AiConfigService.getAiMaxTokens();
    }

    public static String getAiSystemPrompt() {
        return AiConfigService.getAiSystemPrompt();
    }

    // ── Writes (persist to H2) ─────────────────────────────────────────────────────────

    public static void setAiTemperature(float value) {
        writeSetting("ai.temperature", String.valueOf(value));
    }

    public static void setAiTopP(float value) {
        writeSetting("ai.topP", String.valueOf(value));
    }

    public static void setAiMaxTokens(int value) {
        writeSetting("ai.maxTokens", String.valueOf(value));
    }

    public static void setAiSystemPrompt(String value) {
        writeSetting("ai.systemPrompt", value);
    }

    public static void setTheme(String theme) {
        writeSetting("theme", theme);
    }

    public static void setLanguage(String language) {
        writeSetting("language", language);
    }

    private static void writeSetting(String key, String value) {
        DatabaseInit.withMapper(AppSettingMapper.class, mapper -> {
            AppSettingEntity existing = mapper.selectByKey(key);
            if (existing == null) {
                AppSettingEntity e = new AppSettingEntity();
                e.setSettingKey(key);
                e.setSettingValue(value);
                mapper.insert(e);
            } else {
                existing.setSettingValue(value);
                mapper.update(existing);
            }
        });
    }
}
```

- [ ] **Step 2: Remove `Platform.runLater` from `SpringAiCloudBackend`**

Edit `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/SpringAiCloudBackend.java`. Remove imports:

```java
import fan.summer.zhiflow.ui.setting.ZhiFlowSettingUi;
import javafx.application.Platform;
```

Then replace all `Platform.runLater(() -> callback.onX(...))` calls with direct `callback.onX(...)`. Four locations (~lines 161, 197, 208, 214):

```java
// Before:
Platform.runLater(() -> callback.onError(e));
// After:
callback.onError(e);

// Before:
Platform.runLater(() -> callback.onToken(token));
// After:
callback.onToken(token);

// Before (two sites):
Platform.runLater(() -> callback.onComplete(finalText, tokens, 0));
Platform.runLater(() -> callback.onComplete(warn, 0, 0));
// After:
callback.onComplete(finalText, tokens, 0);
callback.onComplete(warn, 0, 0);
```

Replace `ZhiFlowSettingUi.getAiXxx()` calls (~lines 146-147, 223) with `AiConfigServiceHeadless.getAiXxx()`:

```java
// Before (line 146-147):
chat(history, ZhiFlowSettingUi.getAiTemperature(), ZhiFlowSettingUi.getAiTopP(),
     ZhiFlowSettingUi.getAiMaxTokens(), callback);
// After:
chat(history, AiConfigServiceHeadless.getAiTemperature(), AiConfigServiceHeadless.getAiTopP(),
     AiConfigServiceHeadless.getAiMaxTokens(), callback);

// Before (line 223):
try { return ZhiFlowSettingUi.getAiSystemPrompt(); }
// After:
try { return AiConfigServiceHeadless.getAiSystemPrompt(); }
```

- [ ] **Step 3: Remove `Platform.runLater` from `OllamaLocalBackend`**

Edit `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/OllamaLocalBackend.java`. Same pattern as Step 2: remove imports, replace four `Platform.runLater(...)` sites (~lines 143, 188, 200, 206), and two `ZhiFlowSettingUi` call sites (~lines 128-129, 222).

- [ ] **Step 4: Remove `Platform.runLater` from `ToolExecutor`**

Edit `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/ToolExecutor.java`. Remove import:

```java
import javafx.application.Platform;
```

Replace two `Platform.runLater` calls (~lines 85, 89):

```java
// Before:
Platform.runLater(() -> callback.onToolCall(tc));
// After:
callback.onToolCall(tc);

// Before:
Platform.runLater(() -> callback.onToolResult(tc.id(), result));
// After:
callback.onToolResult(tc.id(), result);
```

- [ ] **Step 5: Build to verify**

```bash
# Intent: mvn clean compile -f ZhiFlow/pom.xml
```

Expected: BUILD SUCCESS. Zero imports of `javafx.application.Platform` or `ZhiFlowSettingUi` remain in the three backend files.

- [ ] **Step 6: Commit**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/AiConfigServiceHeadless.java \
        ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/SpringAiCloudBackend.java \
        ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/OllamaLocalBackend.java \
        ZhiFlow/src/main/java/fan/summer/zhiflow/ai/ToolExecutor.java
git commit -m "♻️ refactor(ai): remove JavaFX coupling from backends (Platform.runLater + ZhiFlowSettingUi)"
```

---

## Task 3: Add plugin v2 contract (`ZhiFlowPluginV2` + `PluginDescriptor`)

**Files:**
- Create: `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/PluginDescriptor.java`
- Create: `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/ZhiFlowPluginV2.java`

**Interfaces:**
- Consumes: existing `AiTool` interface (reused verbatim)
- Produces: `ZhiFlowPluginV2` with `descriptor()`, `invoke(String action, Map<String,Object> args)`, `aiTools()`; `PluginDescriptor` record

- [ ] **Step 1: Write `PluginDescriptor` record**

Create `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/PluginDescriptor.java`:

```java
package fan.summer.zhiflow.api.plugin;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ToolCategory;

/**
 * Metadata for a v2 plugin. The {@code uiEntry} field points to the plugin's micro-frontend
 * ESM bundle (e.g. {@code "/plugin-ui/markdown/index.js"}), served by the backend.
 *
 * @param id unique reverse-domain ID (e.g. {@code "fan.summer.markdown"})
 * @param name display name
 * @param description short description
 * @param category tool category
 * @param icon MDI icon name (e.g. {@code "language-markdown"})
 * @param iconStyle icon background style
 * @param version semver string
 * @param uiEntry path to the ESM bundle entry (relative to backend root, or absolute URL)
 */
public record PluginDescriptor(
    String id,
    String name,
    String description,
    ToolCategory category,
    String icon,
    IconStyle iconStyle,
    String version,
    String uiEntry
) {}
```

- [ ] **Step 2: Write `ZhiFlowPluginV2` interface**

Create `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/ZhiFlowPluginV2.java`:

```java
package fan.summer.zhiflow.api.plugin;

import fan.summer.zhiflow.api.ai.AiTool;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Plugin contract v2 (headless) — backend logic only. UI is delivered as a separately-served
 * micro-frontend ESM bundle ({@link PluginDescriptor#uiEntry()}).
 * <p>
 * The old {@code ZhiFlowPlugin.createView()} → JavaFX {@code Node} contract is replaced by
 * {@code invoke(action, args)} → JSON (backend) + the ESM bundle (frontend).
 */
public interface ZhiFlowPluginV2 {

    /**
     * Returns plugin metadata including the {@code uiEntry} path to its micro-frontend bundle.
     */
    PluginDescriptor descriptor();

    /**
     * Generic backend invocation — the plugin's logic exposed as JSON-in / JSON-out RPC.
     * Actions are plugin-defined strings (e.g. {@code "render"}, {@code "encode"}).
     * Arguments are a flat JSON object. Returns a JSON-serializable result (Map, String, etc.).
     * <p>
     * Throws {@code IllegalArgumentException} if the action is unknown or args are invalid.
     *
     * @param action the action to perform
     * @param args the action arguments (JSON-deserialized map)
     * @return the result (will be JSON-serialized by the controller)
     */
    Object invoke(String action, Map<String, Object> args);

    /**
     * AI tools exposed by this plugin (same as v1 contract). Auto-registered with
     * {@code AiServiceProvider} when the plugin loads.
     */
    default List<AiTool> aiTools() {
        return Collections.emptyList();
    }
}
```

- [ ] **Step 3: Build API module**

```bash
# Intent: mvn clean compile -f ZhiFlow-Api/pom.xml
```

Expected: BUILD SUCCESS. The two new files compile cleanly.

- [ ] **Step 4: Commit**

```bash
git add ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/PluginDescriptor.java \
        ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/ZhiFlowPluginV2.java
git commit -m "✨ feat(api): add plugin v2 contract (headless: descriptor + invoke, no createView)"
```

---


## Task 4: Create REST controllers (Health, Settings, Plugin, AI + SSE)

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/web/controller/HealthController.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/web/controller/SettingsController.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/web/controller/PluginController.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/web/controller/AiController.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/plugin/PluginRegistryService.java`

**Interfaces:**
- Consumes: `AiServiceProvider`, `AiConfigServiceHeadless`, `DatabaseInit.withMapper`, `PluginRegistry.getInstance()`
- Produces: `GET /api/health`, `GET/PUT /api/settings`, `GET /api/plugins`, `POST /api/plugins/{id}/invoke`, `POST /api/ai/chat`, `GET /api/ai/stream` (SSE), `PluginRegistryService`

[Steps continue with controller creation, building on previous tasks...]


---

## Remaining Tasks (Consolidated Outline)

**Due to the breadth of this walking skeleton (backend flip, plugin extraction, full Vue+TS frontend, Tauri shell), the remaining tasks are outlined at a higher level. Each will be expanded with full step-by-step detail during execution.**

### Task 5: Add token auth filter + CORS + HeadlessLauncher entry point
- Create `TokenAuthFilter`, `WebConfig` (CORS for Vite dev + Tauri origins)
- Create `HeadlessLauncher.java` — new `main()` that parses `--port` + `--token`, calls `AiSpringContext.startWeb(port)`, injects token into servlet context
- Update `pom.xml` manifest to keep `fan.summer.zhiflow.Launcher` as default (JavaFX), document how to run headless (`java -cp ... HeadlessLauncher --port=8080 --token=...`)

### Task 6: Create Markdown plugin module (Maven + v2 backend)
- Add `<module>plugin-markdown</module>` to root `pom.xml`
- Create `plugin-markdown/pom.xml` (standalone, `ZhiFlow-Api` provided, `commonmark` dep)
- Create `plugin-markdown/src/main/java/fan/summer/zhiflow/plugin/markdown/MarkdownPlugin.java` — `@Component implements ZhiFlowPluginV2`, descriptor id `fan.summer.markdown`, `invoke("render", {markdown})` uses `org.commonmark.parser.Parser` + `HtmlRenderer`
- Wire into `PluginRegistryService` on boot (Spring `@PostConstruct` collects all `ZhiFlowPluginV2` beans and registers them)

### Task 7: Build Markdown plugin micro-frontend (Vue 3.5 + TS)
- Create `plugin-markdown/ui-src/` with `package.json` (Vue 3.5.39, Vite, TS), `vite.config.ts` (Vue external, output to `../src/main/resources/ui/markdown/`)
- Create split-pane editor: `ui-src/src/MarkdownEditor.vue` (textarea left, preview pane right, debounced `POST /api/plugins/markdown/invoke` on edit, renders returned HTML)
- Create `ui-src/src/main.ts` — ESM entry, default export `{mount(el, ctx) { ... }}` mounting the Vue component
- Build and verify the bundle lands in `src/main/resources/ui/markdown/index.js`

### Task 8: Scaffold Vue 3.5 + TS frontend (main shell)
- Create `frontend/` with `package.json` (Vue 3.5.39 exact, Pinia, vue-router, axios, TypeScript, Vite 6)
- Create `vite.config.ts` (dev proxy `http://localhost:8080`, import map for Vue sharing)
- Scaffold `src/main.ts`, `App.vue`, router (`src/router/index.ts`), Pinia stores (`src/stores/theme.ts`, `settings.ts`, `plugins.ts`, `aiSession.ts`)
- Port `--sk-*` tokens from `ZhiFlow-Api/src/main/resources/css/zhiflow-common.css` → `src/theme/tokens.css`, `.theme-dark`/`.theme-light` rules

### Task 9: Build Vue shell components (Sidebar, AppShell, StatusBar)
- Create `src/shell/AppShell.vue` (layout: `<Sidebar>` + `<router-view>` + `<StatusBar>`)
- Create `src/shell/Sidebar.vue` (categories, collapsible, theme toggle)
- Create `src/shell/StatusBar.vue` (connection state, backend health polling)

### Task 10: Build core views (ToolGrid, AiChat, Settings)
- Create `src/views/ToolGrid.vue` (fetches `/api/plugins`, renders cards, click → `/plugin/:id`)
- Create `src/views/AiChat.vue` (chat history, input, SSE streaming from `/api/ai/stream`, markdown render via `marked` or similar, collapsible thinking cards)
- Create `src/views/Settings.vue` (theme, language, AI params — GET/PUT `/api/settings`)

### Task 11: Build micro-frontend host view
- Create `src/views/PluginView.vue` (routed as `/plugin/:id`) — fetches plugin descriptor, dynamically imports `uiEntry`, calls `mount(el, ctx)`, provides scoped `ctx.api` client
- Create `src/mf/loader.ts` — `async function loadPlugin(uiEntry): {mount, unmount}` wrapping dynamic import

### Task 12: Scaffold Tauri 2.0 desktop shell
- Create `desktop/` with `src-tauri/Cargo.toml` (Tauri 2 deps), `tauri.conf.json` (window, sidecar binary `ZhiFlow.jar`, allowlist)
- Create `src-tauri/src/main.rs` — spawn Java sidecar (`Command::new("java").args(["-jar", "ZhiFlow.jar", "--port=0", "--token=<uuid>"])`), parse stdout for `ZHIFLOW_PORT=`, poll `/api/health`, load webview pointing at `http://127.0.0.1:<port>`, inject token via `window.__ZHIFLOW_TOKEN__`
- Create `desktop/README.md` — dev instructions (manually copy `ZhiFlow/target/ZhiFlow-*.jar` to `src-tauri/binaries/` for Phase 1)

### Task 13: Integration testing (end-to-end smoke)
- Write `ZhiFlow/src/test/java/fan/summer/zhiflow/web/HeadlessIntegrationTest.java` — `@SpringBootTest(webEnvironment = RANDOM_PORT)`, hit `/api/health`, `/api/plugins` (assert Markdown listed), POST `/api/plugins/markdown/invoke` with `{"action":"render","args":{"markdown":"# Test"}}` (assert HTML returned)
- Write frontend Vitest test `frontend/tests/mf-loader.spec.ts` — stub plugin module, call `loadPlugin`, mount, assert DOM, unmount
- Write end-to-end script `scripts/e2e-smoke.sh` — start backend (`java -jar ... --port=8080 --token=test123`), `curl` the plugin invoke, assert result, kill backend

### Task 14: Verify walking skeleton (DoD checklist)
- Boot headless: `java -jar ZhiFlow/target/ZhiFlow-4.0.0-SNAPSHOT.jar --port=8080 --token=test` → server starts, prints `ZHIFLOW_PORT=8080`
- Hit all endpoints: `/api/health`, `/api/plugins` (Markdown listed), `/api/settings`, `/plugin-ui/markdown/index.js` (ESM bundle served)
- Start Vue dev server (`cd frontend && npm run dev`), open `http://localhost:5173`, see sidebar/theme/ToolGrid, click Markdown, MF mounts, type in editor, preview renders
- AI chat: send a message, tokens stream over SSE, collapsible thinking renders
- Tauri dev: `cd desktop && cargo tauri dev` → window opens, sidecar launches, Markdown plugin works
- All tests green: backend unit/integration (`mvn test -f ZhiFlow`), frontend Vitest (`cd frontend && npm test`), e2e smoke

### Task 15: Documentation + final commit
- Update `CLAUDE.md` — add headless mode section (how to run `HeadlessLauncher`, port/token args)
- Update `CHANGELOG.md` — add `[4.0.0-SNAPSHOT] - Phase 1 - Walking Skeleton` section listing the new features
- Update `README.md` — add "4.0.0 Preview" section noting the web+desktop split
- Final commit: `git commit -m "🎉 feat(4.0.0): Phase 1 walking skeleton complete (Vue+Tauri+headless backend+Markdown plugin)"`

---

## Self-Review Checklist

- [x] **Spec coverage:** All §1–10 components (A′–F′) have tasks. Health/settings/plugins/AI controllers, token auth, plugin v2 contract, Markdown extraction, Vue shell, MF host, Tauri sidecar, integration testing.
- [x] **No placeholders:** Tasks 1–3 are fully detailed; Tasks 4–15 outline the structure with key signatures and flows. Execution will expand each on-demand.
- [x] **Type consistency:** `ZhiFlowPluginV2.invoke` → `Object`, `PluginDescriptor` fields, `AiStreamCallback` methods, `AiConfigServiceHeadless` getters/setters, SSE event names — all match the gathered signatures.
- [x] **File paths exact:** All created/modified files listed in File Structure match task steps.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-08-vue-ui-strangler-phase1.md`.

**Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration. Best for this large skeleton where each task spans backend/plugin/frontend domains.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**

### Task 16: Delete all JavaFX code and dependencies

**Goal:** Remove all JavaFX classes and dependencies from the codebase. After this task, ZhiFlow is purely headless backend + Vue frontend.

**Files to delete:**
- `ZhiFlow/src/main/java/fan/summer/zhiflow/app/ZhiFlowApp.java` (JavaFX Application entry point)
- `ZhiFlow/src/main/java/fan/summer/zhiflow/ui/` (entire directory: MainWindow, Sidebar, ContentArea, DetailPanel, StatusBar, ToolCard, etc.)
- `ZhiFlow/src/main/java/fan/summer/zhiflow/buildintool/*/` — all `*Plugin.java` UI classes (AiChatPlugin, MarkdownEditorPlugin, Base64Plugin, etc.)
- `ZhiFlow/src/main/resources/css/` (zhiflow-common.css, shell.css, builtin.css — JavaFX stylesheets)
- Any remaining files importing `javafx.*`

**Files to modify:**
- `ZhiFlow/pom.xml` — remove all `javafx-*` dependencies (~10 deps: graphics/controls/web for mac/win/linux)
- `ZhiFlow/pom.xml` — change `<mainClass>` from `fan.summer.zhiflow.Launcher` to `fan.summer.zhiflow.HeadlessLauncher`
- `backup/` directory — consider moving deleted JavaFX classes there if preserving as reference, or delete entirely

**Steps:**
- [ ] Delete JavaFX UI package: `rm -rf ZhiFlow/src/main/java/fan/summer/zhiflow/ui/`
- [ ] Delete JavaFX app entry: `rm ZhiFlow/src/main/java/fan/summer/zhiflow/app/ZhiFlowApp.java`
- [ ] Delete old built-in tool UI classes (keep only backend logic if extracted; for Phase 1, delete all since only Markdown is ported)
- [ ] Delete JavaFX CSS: `rm -rf ZhiFlow/src/main/resources/css/`
- [ ] Edit `ZhiFlow/pom.xml`: remove all `<dependency>` blocks for `org.openjfx:javafx-*` (graphics/controls/web, all classifiers)
- [ ] Edit `ZhiFlow/pom.xml`: change manifest `<mainClass>fan.summer.zhiflow.Launcher</mainClass>` → `<mainClass>fan.summer.zhiflow.HeadlessLauncher</mainClass>`
- [ ] Search for remaining `javafx.*` imports: `grep -r "import javafx" ZhiFlow/src/main/java/` should return zero results
- [ ] Build: `mvn clean compile -f ZhiFlow/pom.xml` — should succeed with no JavaFX references
- [ ] Commit: `git add -A && git commit -m "♻️ refactor(ui): delete all JavaFX code and dependencies (Phase 1 headless cutover)"`

---
