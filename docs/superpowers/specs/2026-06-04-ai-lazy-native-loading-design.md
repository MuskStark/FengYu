# AI Tool Lazy Native Loading + Backend Toggle

**Date:** 2026-06-04
**Status:** Approved

## Problem

The AI tool's external native library (`libllama_jni.*`) is loaded eagerly during app startup via `AiServiceImpl` constructor and `LlamaContext` static initializer. This can crash the main application on platforms where native loading fails (wrong architecture, missing library, UOS signature verification, etc.). There is also no user-facing setting to choose between the pure Java inference engine and the native llama.cpp engine.

## Solution

### 1. Backend Toggle Setting

**New DB key:** `ai.local.backend` with values `"java"` or `"native"` (default: `"native"`).

UI: A toggle switch in the AI Model → Local panel. Left side = "Java 引擎", right side = "Native 引擎". Toggling saves the setting and reinitializes the local backend.

New accessor in `SwissKitJSettingUi`:
- `getAiLocalBackend()` → reads from DB, defaults to `"native"`

New i18n keys:
- `setting.ai.backend.java` → "Java 引擎"
- `setting.ai.backend.native` → "Native 引擎"

### 2. Lazy Backend Initialization

**`SwissKitJApp.initializeAiBackend()` changes:**
- For `"local"` mode: no-op — do NOT create `AiServiceImpl`. Log: `"AI backend: local (deferred)"`.
- `"openai"` and `"anthropic"` modes unchanged (no native libs involved).

**New method `AiServiceProvider.ensureLocalBackend()`:**
- Called when AI tool opens (`AiChatPlugin.onActivate()`)
- If `AiServiceImpl` already exists → no-op
- Read `ai.local.backend` setting from DB
- If `"native"`: call `NativeLoader.load()` → if success, create `AiServiceImpl(NATIVE)` → if fail, log warning and fallback to Java
- If `"java"`: skip `NativeLoader.load()`, create `AiServiceImpl(JAVA)`
- Register via `AiServiceProvider.switchMode("local", aiService)`
- Auto-load saved model path from DB (moved from `SwissKitJApp`)

**`AiServiceImpl` constructor changes:**
- Remove `NativeLoader.load()` call
- Accept `Backend` as constructor parameter (no auto-detection)
- Don't create `LlamaRunner` or `NativeWorkerClient` eagerly — create in `loadModel()`

**`LlamaContext` changes:**
- Remove `static { NativeLoader.load(); }` block
- Callers must ensure `NativeLoader` is loaded before constructing `LlamaContext`

### 3. Toggle Switch UI

Added in `buildLocalModelPanel()` above the model path field:
- Two-state toggle: left label "Java 引擎" / right label "Native 引擎"
- Implemented as a styled `HBox` with two buttons or a custom toggle
- On toggle: save `ai.local.backend` setting, call `initializeAiService("local")` to reinitialize

### 4. AI Tool Integration

**Activation trigger:**
- `AiChatPlugin.onActivate()` calls `AiServiceProvider.ensureLocalBackend()`
- First call triggers full initialization; subsequent calls are no-ops
- `AiChatView.refreshServiceState()` already listens to `AiServiceProvider.addOnStateChangeListener()` — picks up the new service automatically

**Native-unavailable banner:**
- Reuse existing `nativeUnavailableBanner` in `AiChatView`
- Show when backend setting is `"native"` but `NativeLoader.isLoaded() == false` (fell back to Java)
- Text informs user native engine unavailable, using Java fallback

### 5. Crash Safety

- `NativeLoader.load()` called ONLY from `ensureLocalBackend()`, wrapped in `try-catch(Throwable)`
- `LlamaContext` static block removed — no class-loading side effects
- Main app startup never touches native libs for local mode
- Guarantee: regardless of native library failure (missing, wrong arch, UOS sig verification, SIGILL, etc.), main app starts cleanly. Only the AI tool shows a degraded banner or error.

## Files Changed

| File | Change |
|------|--------|
| `SwissKit/src/main/java/fan/summer/ai/nativejni/NativeLoader.java` | No change (already safe) |
| `SwissKit/src/main/java/fan/summer/ai/nativejni/LlamaContext.java` | Remove `static { NativeLoader.load(); }` |
| `SwissKit/src/main/java/fan/summer/ai/service/AiServiceImpl.java` | Constructor takes `Backend` param, no `NativeLoader.load()` call |
| `SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiServiceProvider.java` | Add `ensureLocalBackend()` method |
| `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java` | Local mode in `initializeAiBackend()` becomes no-op |
| `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java` | Add toggle UI, `getAiLocalBackend()` accessor |
| `SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java` | Call `ensureLocalBackend()` in `onActivate()` |
| `SwissKit/src/main/resources/i18n/messages.properties` | Add i18n keys for toggle labels |
| `SwissKit/src/main/resources/i18n/messages_en.properties` | Add i18n keys for toggle labels |
