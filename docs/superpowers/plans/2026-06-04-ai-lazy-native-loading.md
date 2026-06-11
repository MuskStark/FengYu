# AI Lazy Native Loading + Backend Toggle — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Defer native library loading until the AI tool is opened, add a Java/Native toggle in settings, and ensure native failures never crash the main app.

**Architecture:** Remove eager native loading from startup. `SwissKitJApp.initializeAiBackend()` becomes a no-op for local mode. A new `SwissKitJSettingUi.ensureLocalBackend()` method handles lazy initialization when the AI chat plugin activates. A segmented toggle in settings lets users choose between Java and Native backends.

**Tech Stack:** Java, JavaFX, H2/MyBatis for settings persistence, i18n properties files.

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `SwissKit/src/main/resources/i18n/messages.properties` | Modify | Add i18n keys (English) |
| `SwissKit/src/main/resources/i18n/messages_zh.properties` | Modify | Add i18n keys (Chinese) |
| `SwissKit/src/main/java/fan/summer/ai/nativejni/LlamaContext.java` | Modify | Remove static native-loading block |
| `SwissKit/src/main/java/fan/summer/ai/service/AiServiceImpl.java` | Modify | Constructor takes `boolean useNative`, lazy runner creation |
| `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java` | Modify | Add `ensureLocalBackend()`, `createLocalBackend()`, `getAiLocalBackend()`, toggle UI |
| `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java` | Modify | Skip local AI init at startup |
| `SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java` | Modify | Call `ensureLocalBackend()` in `onActivate()` |

---

### Task 1: Add i18n keys

**Files:**
- Modify: `SwissKit/src/main/resources/i18n/messages.properties`
- Modify: `SwissKit/src/main/resources/i18n/messages_zh.properties`

- [ ] **Step 1: Add English i18n keys**

In `messages.properties`, add after the existing `setting.ai.autoLoadFailed` line (around line 103):

```properties
setting.ai.backend=Inference Engine
setting.ai.backend.java=Java
setting.ai.backend.native=Native
```

- [ ] **Step 2: Add Chinese i18n keys**

In `messages_zh.properties`, add after the existing `setting.ai.autoLoadFailed` line (around line 103):

```properties
setting.ai.backend=推理引擎
setting.ai.backend.java=Java
setting.ai.backend.native=Native
```

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/resources/i18n/messages.properties SwissKit/src/main/resources/i18n/messages_zh.properties
git commit -m "📝 docs: add i18n keys for AI backend toggle"
```

---

### Task 2: Remove `LlamaContext` static native-loading block

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/nativejni/LlamaContext.java`

- [ ] **Step 1: Remove the static initializer block**

Delete lines 28–30 (`static { NativeLoader.load(); }`):

```java
// DELETE THIS:
    static {
        NativeLoader.load();
    }
```

The class should now go directly from the field declaration to the constructor:

```java
    private volatile long nativePtr; // LlamaWrapper* in C++

    public LlamaContext(ModelParams params) {
```

The constructor already checks `NativeLoader.isLoaded()` at line 33 — that safety check stays.

- [ ] **Step 2: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/nativejni/LlamaContext.java
git commit -m "♻️ refactor: remove static native loader from LlamaContext"
```

---

### Task 3: Refactor `AiServiceImpl` constructor for lazy initialization

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/service/AiServiceImpl.java`

- [ ] **Step 1: Change constructor to accept backend choice parameter**

Replace the existing constructor (lines 50–61):

```java
    public AiServiceImpl() {
        NativeLoader.load();
        if (NativeLoader.isLoaded()) {
            backend = Backend.NATIVE;
            workerClient = new NativeWorkerClient();
            log.info("AI backend: native (llama.cpp JNI, out-of-process)");
        } else {
            backend = Backend.JAVA;
            javaRunner = new LlamaRunner();
            log.info("AI backend: pure Java (fallback)");
        }
    }
```

With:

```java
    /**
     * Creates a new AI service with the specified backend.
     *
     * @param useNative if true, use llama.cpp JNI for inference;
     *                  if false, use the pure Java inference engine.
     *                  The caller must call {@link NativeLoader#load()} beforehand
     *                  and verify {@link NativeLoader#isLoaded()} when useNative is true.
     */
    public AiServiceImpl(boolean useNative) {
        if (useNative) {
            backend = Backend.NATIVE;
            log.info("AI backend: native (llama.cpp JNI, out-of-process)");
        } else {
            backend = Backend.JAVA;
            log.info("AI backend: pure Java");
        }
    }
```

- [ ] **Step 2: Add lazy creation in `loadModel()` for Java backend**

In the `loadModel()` method, the Java backend path (line 106) currently calls `javaRunner.load(...)` directly, which would NPE if `javaRunner` is null. Add lazy creation:

Replace:

```java
            } else {
                javaRunner.load(modelPath.toString());
            }
```

With:

```java
            } else {
                if (javaRunner == null) javaRunner = new LlamaRunner();
                javaRunner.load(modelPath.toString());
            }
```

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/service/AiServiceImpl.java
git commit -m "♻️ refactor: AiServiceImpl accepts backend param, lazy runner creation"
```

---

### Task 4: Add backend accessor, `ensureLocalBackend()`, and `createLocalBackend()` to settings

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java`

- [ ] **Step 1: Add constant and accessor**

After the existing `AI_ANTHROPIC_MODEL_KEY` constant (around line 337), add:

```java
    private static final String AI_LOCAL_BACKEND_KEY = "ai.local.backend";
```

After the existing `getAiAnthropicModel()` method (around line 866), add:

```java
    /**
     * Returns the saved local AI backend choice, or "native" if not set.
     *
     * @return "java" or "native"
     */
    public static String getAiLocalBackend() {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity entity = mapper.selectByKey(AI_LOCAL_BACKEND_KEY);
            if (entity != null && entity.getSettingValue() != null) return entity.getSettingValue();
        } catch (Exception ignored) {}
        return "native";
    }
```

- [ ] **Step 2: Replace `initializeAiService` local branch with `createLocalBackend`**

Replace the existing `initializeAiService` method (lines 479–501):

```java
    static void initializeAiService(String mode) {
        switch (mode) {
            case "openai" -> {
                fan.summer.ai.service.OpenAiService svc = new fan.summer.ai.service.OpenAiService();
                svc.configure(getAiOpenAiEndpoint(), getAiOpenAiApiKey(), getAiOpenAiModel());
                AiServiceProvider.switchMode(mode, svc);
            }
            case "anthropic" -> {
                fan.summer.ai.service.AnthropicService svc = new fan.summer.ai.service.AnthropicService();
                svc.configure(getAiAnthropicEndpoint(), getAiAnthropicApiKey(), getAiAnthropicModel());
                AiServiceProvider.switchMode(mode, svc);
            }
            default -> createLocalBackend(false);
        }
    }
```

Add the `createLocalBackend` method right after `initializeAiService`:

```java
    /**
     * Creates and registers a local AI backend (AiServiceImpl).
     *
     * @param autoLoadModel if true, auto-load the last saved model path from DB
     */
    private static void createLocalBackend(boolean autoLoadModel) {
        String backendSetting = getAiLocalBackend();
        boolean useNative = "native".equals(backendSetting);

        if (useNative) {
            fan.summer.ai.nativejni.NativeLoader.load();
            if (!fan.summer.ai.nativejni.NativeLoader.isLoaded()) {
                log.warn("Native library not available, falling back to Java engine");
                useNative = false;
            }
        }

        fan.summer.ai.service.AiServiceImpl aiService =
            new fan.summer.ai.service.AiServiceImpl(useNative);
        AiServiceProvider.switchMode("local", aiService);

        if (autoLoadModel) {
            autoLoadModel(aiService);
        }
    }
```

- [ ] **Step 3: Add `ensureLocalBackend()` and `autoLoadModel()` helper**

Add these two methods right after `createLocalBackend`:

```java
    /**
     * Ensures the local AI backend is initialized. Called lazily when the AI tool is opened.
     * No-op if already initialized. On first call, attempts native loading and auto-loads
     * the last saved model.
     */
    public static synchronized void ensureLocalBackend() {
        var svc = AiServiceProvider.getService();
        if (svc.isPresent() && svc.get() instanceof fan.summer.ai.service.AiServiceImpl) {
            return; // already initialized
        }
        log.info("Initializing local AI backend (lazy)");
        createLocalBackend(true);
    }

    private static void autoLoadModel(fan.summer.ai.service.AiServiceImpl aiService) {
        String modelPath = null;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity entity = mapper.selectByKey(AI_MODEL_PATH_KEY);
            if (entity != null && entity.getSettingValue() != null && !entity.getSettingValue().isBlank()) {
                modelPath = entity.getSettingValue();
            }
        } catch (Exception e) {
            log.debug("Could not read AI model path", e);
        }

        if (modelPath != null && java.nio.file.Files.exists(java.nio.file.Path.of(modelPath))) {
            log.info("Auto-loading local AI model: {}", modelPath);
            final String finalPath = modelPath;
            Thread.ofVirtual().start(() -> {
                try {
                    aiService.loadModel(java.nio.file.Path.of(finalPath));
                    AiServiceProvider.notifyStateChanged();
                    log.info("Local AI model auto-loaded successfully");
                } catch (Exception e) {
                    log.warn("Auto-load failed: {}", e.getMessage());
                }
            });
        }
    }
```

- [ ] **Step 4: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java
git commit -m "✨ feat: add ensureLocalBackend() with lazy init and auto-load"
```

---

### Task 5: Update `SwissKitJApp` to skip local AI initialization at startup

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java`

- [ ] **Step 1: Replace the local mode branch in `initializeAiBackend()`**

Replace the `default` branch in `initializeAiBackend()` (lines 191–227) — the entire `default -> { ... }` block:

```java
            default -> {
                try {
                    fan.summer.ai.service.AiServiceImpl aiService = new fan.summer.ai.service.AiServiceImpl();
                    AiServiceProvider.switchMode(mode, aiService);

                    String modelPath = null;
                    try (SqlSession session = DatabaseInit.getSqlSession()) {
                        AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
                        AppSettingEntity entity = mapper.selectByKey("ai.model.path");
                        if (entity != null && entity.getSettingValue() != null && !entity.getSettingValue().isBlank()) {
                            modelPath = entity.getSettingValue();
                        }
                    } catch (Exception e) {
                        log.debug("Could not read AI model path", e);
                    }

                    if (modelPath != null && java.nio.file.Files.exists(java.nio.file.Path.of(modelPath))) {
                        log.info("Auto-loading local AI model: {}", modelPath);
                        final String finalPath = modelPath;
                        Thread.ofVirtual().start(() -> {
                            try {
                                aiService.loadModel(java.nio.file.Path.of(finalPath));
                                AiServiceProvider.notifyStateChanged();
                                log.info("Local AI model auto-loaded successfully");
                            } catch (Exception e) {
                                log.warn("Auto-load failed: {}", e.getMessage());
                            }
                        });
                    }
                } catch (Exception e) {
                    // Guard: if native library loading fails catastrophically (e.g. UOS signature
                    // verification blocks unsigned .so), the app should still start without AI.
                    log.error("Failed to initialize local AI backend, AI features will be unavailable: {}", e.getMessage());
                    AiServiceProvider.switchMode("disabled", null);
                }
            }
```

With:

```java
            default -> {
                // Local mode: defer initialization until AI tool is opened.
                // See SwissKitJSettingUi.ensureLocalBackend() for the lazy init logic.
                log.info("AI backend: local (deferred, will initialize when AI tool opens)");
            }
```

- [ ] **Step 2: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java
git commit -m "♻️ refactor: defer local AI backend init until AI tool is opened"
```

---

### Task 6: Add backend toggle UI in settings

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java`

- [ ] **Step 1: Add the `buildBackendToggle()` method**

Add this private method in the AI Model Tab section (after `buildLocalModelPanel`, around line 599):

```java
    private static HBox buildBackendToggle() {
        Label label = subLabel(I18n.get("setting.ai.backend"));

        Button javaBtn = new Button(I18n.get("setting.ai.backend.java"));
        Button nativeBtn = new Button(I18n.get("setting.ai.backend.native"));

        // Segmented control styling: left button has right-side rounded, right button has left-side rounded
        String baseStyle = "-fx-font-size: 12px; -fx-padding: 5 14 5 14; -fx-cursor: hand;";
        javaBtn.setStyle(baseStyle + "-fx-background-radius: 4 0 0 4; -fx-border-radius: 4 0 0 4;");
        nativeBtn.setStyle(baseStyle + "-fx-background-radius: 0 4 4 0; -fx-border-radius: 0 4 4 0;");

        Runnable updateStyle = () -> {
            String current = getAiLocalBackend();
            boolean isJava = "java".equals(current);
            javaBtn.getStyleClass().setAll(isJava ? "glass-btn-primary" : "glass-btn-secondary");
            nativeBtn.getStyleClass().setAll(isJava ? "glass-btn-secondary" : "glass-btn-primary");
            // Re-apply shape styles after class change (stylesheet may override)
            javaBtn.setStyle(baseStyle + "-fx-background-radius: 4 0 0 4; -fx-border-radius: 4 0 0 4;");
            nativeBtn.setStyle(baseStyle + "-fx-background-radius: 0 4 4 0; -fx-border-radius: 0 4 4 0;");
        };
        updateStyle.run();

        javaBtn.setOnAction(e -> {
            saveAiSetting(AI_LOCAL_BACKEND_KEY, "java");
            updateStyle.run();
            initializeAiService("local");
        });

        nativeBtn.setOnAction(e -> {
            saveAiSetting(AI_LOCAL_BACKEND_KEY, "native");
            updateStyle.run();
            initializeAiService("local");
        });

        HBox toggle = new HBox(javaBtn, nativeBtn);
        toggle.setAlignment(Pos.CENTER_LEFT);

        HBox row = new HBox(10, label, toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
```

- [ ] **Step 2: Add the toggle to `buildLocalModelPanel()`**

In `buildLocalModelPanel()`, add the toggle at the top of the panel. Find the line that builds the children list (around line 590):

```java
        panel.getChildren().addAll(
            modelStatusLabel, modelPathLabel,
            labeled(I18n.get("setting.ai.modelPath"), modelPathField),
            modelBtnRow,
            labeled(I18n.get("setting.ai.memoryUsage"), memRow)
        );
```

Insert `buildBackendToggle(),` at the beginning:

```java
        panel.getChildren().addAll(
            buildBackendToggle(),
            modelStatusLabel, modelPathLabel,
            labeled(I18n.get("setting.ai.modelPath"), modelPathField),
            modelBtnRow,
            labeled(I18n.get("setting.ai.memoryUsage"), memRow)
        );
```

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java
git commit -m "✨ feat: add Java/Native backend toggle in AI settings"
```

---

### Task 7: Wire `AiChatPlugin.onActivate()` to lazy-init backend

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java`

- [ ] **Step 1: Add `ensureLocalBackend()` call in `onActivate()`**

Replace the existing `onActivate()` method (lines 77–79):

```java
    @Override
    public void onActivate() {
        log.info("AI Chat plugin activated");
    }
```

With:

```java
    @Override
    public void onActivate() {
        log.info("AI Chat plugin activated");
        fan.summer.ui.setting.SwissKitJSettingUi.ensureLocalBackend();
    }
```

- [ ] **Step 2: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java
git commit -m "✨ feat: lazy-init AI backend when AI chat tool opens"
```

---

### Task 8: Build and verify

**Files:** None (verification only)

- [ ] **Step 1: Build the project via IntelliJ Maven**

Use `mcp__idea__build_project` or run via IntelliJ's Maven tool window:

Build `SwissKitJ-Api` first, then `SwissKit`.

- [ ] **Step 2: Run the application and verify**

Verify the following behaviors:

1. **App starts without touching native libs** — check logs for `"AI backend: local (deferred)"` and NO `"Loaded native library"` message at startup.

2. **Settings show the toggle** — go to Settings → AI Model → Local Model panel. A segmented "Java / Native" toggle should appear at the top of the local model panel.

3. **Toggle works** — click Java, then Native. Verify the setting is saved (check logs or re-open settings).

4. **AI tool triggers lazy init** — open the AI chat tool. Check logs for `"Initializing local AI backend (lazy)"` followed by either native loading or Java fallback.

5. **Native failure is graceful** — if on a platform without native libs, verify the app starts fine, and the AI chat shows the `"⚠ Native acceleration unavailable"` banner but still works with Java engine.

6. **Model loading works** — load a GGUF model through settings. Verify it loads and chat works.

- [ ] **Step 3: Final commit (if any fixes were needed)**

```bash
git add -A
git commit -m "🐛 fix: address verification findings for lazy native loading"
```
