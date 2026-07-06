# Migrating to 3.2

This guide covers everything a plugin author needs to change (or can newly use)
when upgrading a plugin from SwissKitJ 3.1.x to 3.2.0.

## Breaking: `.glass-*` CSS classes renamed to `.sk-*`

All shared utility CSS classes in `zhiflow-common.css` were renamed from the
`glass-*` prefix to `sk-*` (e.g. `.glass-dialog` → `.sk-dialog`,
`.glass-field` → `.sk-field`, `.glass-btn-primary` → `.sk-btn-primary`).
See the full rename table in the `[3.2.0]` section of `CHANGELOG.md`.

**Action required:** search your plugin source for `glass-` and replace each
class with its `sk-` counterpart. Plugins still referencing `.glass-*` will
render unstyled.

## Deprecated: `GlassNotification` → `SkNotification`

`fan.summer.zhiflow.api.component.GlassNotification` is deprecated and now a thin alias
of the new `SkNotification` (same `Type` enum, same `toast` / `notify` /
`confirm` methods). Existing code keeps compiling; migrate at your convenience:

```java
// before
GlassNotification.toast(view, GlassNotification.Type.SUCCESS, "Saved");
// after
SkNotification.toast(view, SkNotification.Type.SUCCESS, "Saved");
```

The alias will be removed in 4.0.

## New: main window uses native OS chrome

The main window switched to `StageStyle.DECORATED`. If your plugin opens its own
transparent/undecorated `Stage`, nothing changes for you — but remember to call
`fan.summer.zhiflow.api.theme.Themes.applyTo(scene)` so `-sk-*` tokens resolve.

## New: `I18n.registerFallbackBundle(...)`

Modules that ship their own message bundle (including the API module itself) can
register a *fallback* bundle. Lookup order is: host bundle → plugin bundles →
fallback bundles. Plugins should keep using `I18n.registerPluginBundle(...)`
inside `createView()`; `registerFallbackBundle` is for library-level defaults.

## New: `PluginPreviewWindow` (developer tool)

`fan.summer.zhiflow.api.preview.PluginPreviewWindow` launches your plugin inside a
standalone shell-like window with theme and language toggles — no full SwissKitJ
install needed during development.

> **Stability note:** the preview API is a development-time tool. Its window
> layout intentionally mirrors (but is not shared with) the real app shell;
> always do a final check inside the real application before release.

## New: PluginHost / PluginSettings / TaskRunner

Plugins can now override `init(PluginHost host)` to receive a per-plugin host
facade: namespaced persistent settings (`host.settings()`), TCCL-safe
background tasks with automatic background keepalive (`host.tasks()`),
ClassLoader-free i18n bundle registration (`host.i18n().registerBundle(...)`),
plus theme and notification access. Existing plugins need no change — the
static entry points keep working. See `plugins/plugin-host.md` for the full
guide.

The preview window (`PluginPreviewWindow`) now loads plugins with the exact
same semantics as the real host: child-first resource ClassLoader, TCCL
registration, and `init(PluginHost)` injection (settings persist under
`~/.swisskit/preview-settings/`).

## Checklist

- [ ] Replace every `.glass-*` style class with `.sk-*`
- [ ] (Optional) switch `GlassNotification` calls to `SkNotification`
- [ ] Rebuild against `SwissKitJ-Api` 3.2.0 (`provided` scope, as before)
- [ ] Verify your UI in **both** dark and light themes
- [ ] (Optional) adopt `init(PluginHost)` for settings/tasks/i18n instead of static entry points
