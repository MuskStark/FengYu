# The `ZhiFlowPlugin` Contract

The single interface every plugin implements:
`fan.summer.zhiflow.api.ZhiFlowPlugin`
([source](https://github.com/MuskStark/ZhiFlow/blob/main/ZhiFlow-Api/src/main/java/fan/summer/api/ZhiFlowPlugin.java)).

The host depends only on this interface. You declare `ZhiFlow-Api` as `provided` scope,
implement the interface, register via `META-INF/services`, and ship a fat-JAR. The host
discovers it with Java `ServiceLoader`.

> **3.2.0 mandate.** Although only 7 methods are technically abstract, a conformant 3.2.0
> plugin also implements `init(PluginHost)` (default no-op in the interface) and reaches every
> host capability through that facade. The legacy static entry points (`I18n.*`,
> `LoggerFactory.getLogger`, `Themes.applyTo`, raw `javafx.concurrent.Task`) and the
> `@Deprecated(forRemoval=true)` `GlassNotification` remain only for old-JAR compatibility and
> are forbidden in new code. See [migration.md](migration.md).

## Required methods (abstract — no defaults)

| Signature | Return | Purpose |
|---|---|---|
| `String getId()` | `String` | Globally-unique id. Reverse-domain recommended (`"com.example.csv-sorter"`). Builtins use `"builtin.<slug>"`. |
| `String getName()` | `String` | Display name. Use `host.i18n().get(...)`. |
| `String getDescription()` | `String` | One-line description. Use `host.i18n().get(...)`. |
| `ToolCategory getCategory()` | `ToolCategory` | Sidebar grouping: `DEV` / `TEXT` / `IMAGE` / `NET` / `OTHER`. |
| `String getVersion()` | `String` | Semantic version `"major.minor.patch"`. |
| `String getMdiIcon()` | `String` | MDI icon name **without** `mdi-` prefix (e.g. `"code-json"`). Unknown names fall back to `"star"`. |
| `Node createView()` | `javafx.scene.Node` | Main UI. Called **once**; the host caches and reuses the returned node. |

## Default methods (override as needed)

| Signature | Default | Override when |
|---|---|---|
| `void init(PluginHost host)` (`@since 3.2.0`) | no-op | **Always, in 3.2.0.** Save the host reference; it's your only sanctioned route to logging, settings, tasks, i18n, theme, and notifications. Fires once, on the FX thread, before `createView()` and `aiTools()`. |
| `IconStyle getIconStyle()` | `IconStyle.BLUE` | You want a different icon background tint: `BLUE`/`PURPLE`/`TEAL`/`AMBER`/`RED`/`PINK`/`GRAY`. The color is applied from Java via `IconStyle.getColor()` — the `.ic-*` CSS classes are empty rules. |
| `ToolType getType()` | `ToolType.PLUGIN` | External plugins leave this as `PLUGIN`. Only builtins override to `BUILTIN`. |
| `void onActivate()` | no-op | The tool entered the foreground (each time). Start/resume live behavior. |
| `void onDeactivate()` | no-op | The tool was pushed to the background (each time). Pause non-essential work. |
| `void onBackground()` | no-op | **Instead of** `onDeactivate` when `hasRunningTasks()` is true — the plugin stays cached so work continues. |
| `void onForeground()` | no-op | Fires **after** `onActivate` when returning from a backgrounded state. Recalc layout after being detached. |
| `void onUnload()` | no-op | Fires **once** when the JAR is removed or the app shuts down. Release threads, handles, scheduled tasks. |
| `boolean hasRunningTasks()` | `false` | Return `true` while background work is in flight — keeps the view cached on back-navigation instead of deactivating. |
| `List<AiTool> aiTools()` | `List.of()` | The plugin exposes AI tools. See [advanced.md §AiTool](advanced.md#aitool). |

## The `PluginHost` facade (3.2.0 — required)

`init(PluginHost)` hands you a per-plugin facade
([source](https://github.com/MuskStark/ZhiFlow/blob/main/ZhiFlow-Api/src/main/java/fan/summer/api/host/PluginHost.java)).
Save it in a field — it's valid for the plugin's whole lifetime and is the **only** sanctioned
way to reach host services in 3.2.0.

| Accessor | Returns | Replaces (forbidden legacy) | Use for |
|---|---|---|---|
| `pluginId()` | `String` | — | Same value as `getId()`. |
| `logger(Class<?>)` | `PluginLogger` | `LoggerFactory.getLogger(cls)` | SLF4J-style logging into the host backbone. |
| `settings()` | `PluginSettings` | bundling H2 for prefs | Namespaced KV store: `get(key)`/`get(key,default)`/`put(key,value)`/`remove(key)`. Read-your-writes; `put(k,null)` == remove. |
| `tasks()` | `TaskRunner` | raw `Task`/`Thread` + `hasRunningTasks()` | `submit(name, work)` or `submit(name, callable, onSuccess, onError)`; callbacks on FX thread, TCCL correct, auto background keep-alive. |
| `i18n()` | `I18nFacade` | `I18n.*` statics | `registerBundle(baseName)` (no ClassLoader arg), `get(key, args...)`, `bind(prop, key)`, `addListener(runnable)`. |
| `theme()` | `ThemeFacade` | `Themes.applyTo` / `ThemeService.onChange` | `current()`, `onChange(listener)`, `applyTo(scene)` for plugin-owned Stages. |
| `notifications()` | `NotificationFacade` | `GlassNotification.*` | `toast(ctx, type, msg)`, `notify(ctx, type, msg)`, `confirm(ctx, title, msg)`. Types via `SkNotification.Type`. |

`init()` firing contract: **exactly once per plugin, on the JavaFX Application Thread, before
the plugin is visible in the registry and before `aiTools()` registration**, with the plugin's
ClassLoader already on the TCCL. So `getName()`/`getDescription()`/`createView()`/`aiTools()`
can all assume the host field is set.

## Lifecycle firing rules

Driven by `PluginRegistry` (on the JavaFX thread) and `PluginLoader` (on a load-scheduler
thread). All callbacks are wrapped in try/catch so a misbehaving plugin can't crash the host.

```
                 ┌─────────────────────────────────────────────────────┐
                 │  JAR appears in .zhiflow/plugin/                    │
                 │   → ServiceLoader loads class                        │
                 │   → PluginContext.register(plugin, classloader)      │
                 │   → registry.addPlugins(...)  [FX thread]            │
                 └───────────────────────┬─────────────────────────────┘
                                         │ user clicks tool
                                         ▼
   onActivate()  ◀───────────────────────────────────  user navigates back
        │                                                  │
        │ hasRunningTasks()?                               │
        ├── false → onDeactivate()                         │
        └── true  → onBackground()  (view stays cached)    │
                  → onForeground() + onActivate() ─────────┘  (returning from background)

   JAR removed / app shutdown  →  onUnload()  (once)
```

Key facts:
- **`createView()` is called exactly once**, lazily, on first activation. Never rebuild on
  later activations — store UI state in fields.
- **`onDeactivate` vs `onBackground`** is decided by your `hasRunningTasks()` return at the
  moment the user navigates away. If you do async work, override it truthfully or the user's
  back-click will evict the running view.
- **`onUnload`** is fired by `PluginLoader.unloadJar`, not by the registry — it fires exactly
  once. Close threads, file handles, scheduled executors here.

## `PluginContext` — the ClassLoader bridge

`PluginContext`
([source](https://github.com/MuskStark/ZhiFlow/blob/main/ZhiFlow-Api/src/main/java/fan/summer/api/PluginContext.java))
is **not** a service locator. It
associates each plugin instance with the `ClassLoader` that loaded its JAR, so that
plugin-internal libraries (the plugin's own `ServiceLoader`, MyBatis, resource-bundle
lookups) resolve against the plugin's classes/resources. You don't call it directly — the host
calls it for you:

- On load: `PluginContext.register(plugin, classloader)`.
- Every lifecycle callback and event handler runs inside
  `PluginContext.runWith(plugin, () -> ...)`, which temporarily sets the plugin's ClassLoader
  as the thread-context ClassLoader (TCCL).
- `PluginContext.wrapEvents(plugin, node)` wraps the view node's `EventDispatcher` so that
  events (and threads spawned from handlers) inherit the plugin's TCCL.

**Why this matters to you:** if your plugin bundles its own libraries (H2, MyBatis, etc.),
those work transparently because the TCCL is switched. You don't need to do anything special —
just don't assume `getClass().getClassLoader()` is the host's loader (it isn't).

## SPI loading mechanism

The host uses `ServiceLoader.load(ZhiFlowPlugin.class, classloader)` with a custom
`ChildFirstResourceClassLoader`:

- **Child-first for *resources*** — your plugin's `mybatis-config.xml`, mapper XMLs, and
  `messages.properties` are found in your JAR first, shadowing the host's.
- **Parent-first for *classes*** — `ZhiFlowPlugin` and shared API classes resolve to the
  same `Class` object as the host (required for `instanceof`/casts/`ServiceLoader` to work).

So: bundle your own deps (H2, FesodSheet, MyBatis) at default scope; keep `ZhiFlow-Api`
and JavaFX at `provided` scope (the host provides them at runtime).

## Related enums

**`ToolCategory`** — `DEV("dev")` / `TEXT("text")` / `IMAGE("image")` / `NET("net")` /
`OTHER("other")`. Each has `getId()` / `getI18nKey()` / `fromId(String)`.

**`ToolType`** — `BUILTIN("builtin")` / `PLUGIN("plugin")`. External plugins are `PLUGIN`
(the default). `isBuiltin()` / `isPlugin()` predicates.

**`IconStyle`** — `BLUE` / `PURPLE` / `TEAL` / `AMBER` / `RED` / `PINK` / `GRAY`. Each has
`getCssClass()` (e.g. `"ic-blue"`) and `getColor()` (`javafx.scene.paint.Color`). The color is
applied from Java; the `.ic-*` CSS rules are empty. `fromCssClass(String)` (case-insensitive,
default `BLUE`).
