# Migrating to the 3.2.0 Plugin Interface

**Every SwissKitJ plugin — new or existing — must target the 3.2.0 contract.** The center of
that contract is the injected `PluginHost` facade
([source](https://github.com/MuskStark/SwissKitJ/blob/main/SwissKitJ-Api/src/main/java/fan/summer/api/host/PluginHost.java)):
the host calls `init(PluginHost)` once per plugin and every host capability — logging,
settings, background tasks, i18n, theme, notifications — is reached through that one object.

The host still *accepts* the pre-3.2.0 static entry points so old JARs keep loading, but they
are **retained only for backward-compatibility and are forbidden in 3.2.0 code.** When you
author a new plugin or touch an existing one, migrate it fully — don't leave a mix.

## Why the facade replaced the statics

The old static paths (`I18n.registerPluginBundle(baseName, getClass().getClassLoader())`,
`LoggerFactory.getLogger(...)`, `Themes.applyTo(scene)`, raw `javafx.concurrent.Task` +
hand-managed threads) each made the plugin author responsible for something the host can do
correctly and invisibly:

- **ClassLoader plumbing** — `registerPluginBundle` needed you to pass the *right* ClassLoader,
  and getting it wrong silently produced raw i18n keys. `host.i18n().registerBundle("i18n.messages")`
  resolves the plugin's own ClassLoader automatically — there is no way to get it wrong.
- **Thread-context ClassLoader on background work** — a hand-rolled `Thread`/`Task` sheds the
  plugin's TCCL, so bundled H2/MyBatis/ServiceLoader lookups break off the FX thread.
  `host.tasks().submit(...)` runs with the correct TCCL and marshals callbacks back to the FX
  thread for you.
- **Background keep-alive** — you had to override `hasRunningTasks()` truthfully or a back-click
  evicted a running job. Tasks submitted through `host.tasks()` keep the plugin alive
  automatically (the host ORs `tasks().runningCount()` with `hasRunningTasks()`).
- **Settings** — there was no host-provided store, so plugins bundled their own H2 just to
  remember a last-used directory. `host.settings()` is a namespaced, H2-backed KV store with
  read-your-writes semantics.

## The one mapping table (old → 3.2.0)

| Pre-3.2.0 (forbidden in new code) | 3.2.0 replacement | Notes |
|---|---|---|
| `LoggerFactory.getLogger(X.class)` | `host.logger(X.class)` | Same backbone; obtain in `init()` or lazily. |
| `I18n.registerPluginBundle("i18n.messages", getClass().getClassLoader())` | `host.i18n().registerBundle("i18n.messages")` | No ClassLoader arg — resolved for you. |
| `I18n.get(key)` / `I18n.get(key, args...)` | `host.i18n().get(key, args...)` | |
| `I18n.bind(prop, key)` | `host.i18n().bind(prop, key)` | |
| `I18n.addListener(runnable)` | `host.i18n().addListener(runnable)` | |
| `Themes.applyTo(scene)` | `host.theme().applyTo(scene)` | Only for plugin-owned Stages. |
| `ThemeService.onChange(listener)` | `host.theme().onChange(listener)` | |
| raw `new Task<>(){…}` + `new Thread(...).start()` | `host.tasks().submit(name, work, onSuccess, onError)` | Callbacks land on the FX thread; TCCL correct. |
| overriding `hasRunningTasks()` to guard a `host.tasks()` job | *(delete it)* | `host.tasks()` keeps the plugin alive on its own. |
| bundling H2 just to persist small settings | `host.settings()` | Bundle H2 only for real plugin *data*. |
| `GlassNotification.toast/notify/confirm(...)` | `host.notifications().toast/notify/confirm(...)` | **`GlassNotification` is `@Deprecated(forRemoval=true)`.** |
| `.glass-*` CSS classes | `.sk-*` classes | e.g. `.glass-dialog` → `.sk-dialog`. Hard rename — old classes don't exist on a 3.2.0 host. |

> **Two severities.** `GlassNotification` / `.glass-*` are truly `@Deprecated(forRemoval=true)`
> and a pre-3.2.0 JAR renders unstyled on a 3.2.0 host — these *will* break. The statics
> (`I18n.*`, `LoggerFactory.*`, `Themes.*`, raw `Task`) still compile and run, but are
> **migration-only** — this skill forbids them in any code you write or edit. Fix them even
> when they still "work."

## Migration procedure (existing plugin → 3.2.0)

Do these in order; each step is independently verifiable.

1. **Add the facade.** Add a `private PluginHost host;` field and implement:
   ```java
   @Override public void init(PluginHost host) { this.host = host; }
   ```
   `init()` runs once, on the FX thread, before `createView()` and before `aiTools()`
   registration — so the field is set before you need it.

2. **Replace the logger.** Drop `private static final PluginLogger log = LoggerFactory.getLogger(...)`.
   Obtain the logger from the host instead — since `init()` precedes all real work, assign it
   there: `this.log = host.logger(getClass());`.

3. **Replace i18n.** In `createView()`, swap
   `I18n.registerPluginBundle("i18n.messages", getClass().getClassLoader())` for
   `host.i18n().registerBundle("i18n.messages")`, then every `I18n.get`/`I18n.bind` for
   `host.i18n().get`/`host.i18n().bind`. Metadata getters (`getName()`, `getDescription()`) also
   go through `host.i18n().get(...)` — they're called after `init()`.

4. **Replace background work.** Delete hand-rolled `Task`/`Thread` code and any
   `hasRunningTasks()` override that existed only to guard it. Use
   `host.tasks().submit(name, work, onSuccess, onError)` — see
   [advanced.md §Background tasks](advanced.md#background-tasks-hosttasks).

5. **Replace persistence-of-settings.** If the plugin bundled H2/MyBatis solely to remember
   preferences, move those to `host.settings()`. Keep bundled H2 only for genuine datasets.

6. **Replace notifications.** `GlassNotification.*` → `host.notifications().*`. Delete the
   `import fan.summer.zhiflow.api.component.GlassNotification;`.

7. **Replace standalone-Stage theming.** `Themes.applyTo(scene)` → `host.theme().applyTo(scene)`
   for any plugin-owned window.

8. **Rename CSS.** Grep the plugin for `glass-` and rename every class to its `sk-` equivalent
   (`.glass-field` → `.sk-field`, `.glass-btn-primary` → `.sk-btn-primary`, etc.). See the
   rename table in the repo `CHANGELOG.md` `[3.2.0]`.

9. **Bump the API dependency** to `3.2.0` in `pom.xml`
   (`<swisskit.api.version>3.2.0</swisskit.api.version>`).

10. **Verify.** Grep the whole module for the forbidden symbols — the migration is done only
    when this prints nothing:
    ```bash
    grep -rn -e 'GlassNotification' -e 'glass-' \
             -e 'I18n\.' -e 'LoggerFactory\.getLogger' -e 'Themes\.applyTo' \
             -e 'new Task<' src/main/
    ```
    Then run the plugin in the preview shell (`mvn javafx:run -Pdev`, which injects a
    `PluginHost` via `PluginPreviewWindow`) and confirm i18n keys resolve, the theme applies,
    and background jobs survive a back-click.

## Dev-mode note: where the host comes from

In production the host injects `init(PluginHost)`. In standalone dev you must use
**`PluginPreviewWindow`**, which injects a `PreviewPluginHost` exactly like the real host
(`PluginContext.runWith(p, () -> p.init(host))`). A hand-rolled `Scene` with no preview window
leaves `host == null` — so the 3.2.0 template's `DevApp` uses `PluginPreviewWindow`. Don't fall
back to the raw-`Scene` path unless you also construct and inject a host yourself. See
[scaffold.md §Dev launcher](scaffold.md).
