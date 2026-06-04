# Plugin Uninstall Feature — Design Spec

**Date:** 2026-06-04
**Branch:** v3.0.0-rc.2

## Summary

Add the ability for users to uninstall external plugins via the DetailPanel. When a user clicks a tool card to view its details, an "Uninstall" button appears for external plugins (not built-in tools). Clicking it triggers a confirmation dialog, and on confirm the plugin is deactivated (if active), removed from the registry, its JAR file deleted from disk, and the UI returns to the tool grid.

## Requirements

1. **Uninstall button in DetailPanel** — only visible for external plugins (`ToolType.PLUGIN`)
2. **Confirmation dialog** — glass-styled, shows plugin name, Cancel / Confirm buttons
3. **Force deactivate if active** — deactivate, close view, navigate back to tool grid, remove cached view
4. **Clean removal** — unload JAR (close ClassLoader, remove from registry), delete JAR file
5. **i18n** — all user-facing strings in both `messages.properties` and `messages_zh.properties`

## Architecture

### Approach: Explicit unload + delete (Approach B)

Call `unloadJar()` explicitly for immediate feedback, then delete the JAR file. The existing WatchService detects the file deletion but the second `unloadJar` call is a harmless no-op because `jarPlugins.remove(jar)` returns `null`.

### Data flow

```
DetailPanel [Uninstall click]
  → show confirmation dialog
  → onUninstall callback (set by MainWindow)
    → MainWindow:
      1. if active: registry.deactivate() + contentArea.showToolGrid() + cachedViews.remove()
      2. loader.uninstallPlugin(plugin)
         → PluginLoader.findJarPath(plugin) → jar path
         → PluginLoader.unloadJar(jar) → registry.removePlugin() + ClassLoader.close()
         → Files.delete(jar)
    → detailPanel.hide()
```

## Component Changes

### 1. `PluginLoader` — new public methods

- `Path findJarPath(SwissKitJPlugin plugin)` — reverse-lookup through `jarPlugins` map
- `void uninstallPlugin(SwissKitJPlugin plugin)` — finds JAR, calls `unloadJar()`, deletes file

### 2. `DetailPanel` — Uninstall button + confirmation

- Add `Button uninstallBtn` below the Launch button
- Visibility: `uninstallBtn.setVisible(plugin.getType().isPlugin())` in `fillData()`
- `Consumer<SwissKitJPlugin> onUninstall` callback, set via `setOnUninstall()`
- Confirmation: glass-styled overlay within the DetailPanel itself (no separate Stage)
- On confirm: invoke `onUninstall`, then `hide()`

### 3. `MainWindow.wireEvents()` — uninstall orchestration

- Set `detailPanel.setOnUninstall(plugin -> { ... })` which:
  1. If plugin is active: `registry.deactivate()` + `contentArea.showToolGrid()` + `cachedViews.remove(plugin)`
  2. `loader.uninstallPlugin(plugin)`
  3. `detailPanel.hide()`

### 4. i18n keys

| Key | English | Chinese |
|-----|---------|---------|
| `detail.btn.uninstall` | Uninstall | 卸载 |
| `detail.uninstall.confirmTitle` | Uninstall Plugin | 卸载插件 |
| `detail.uninstall.confirmMsg` | Are you sure you want to uninstall "{0}"? | 确定要卸载 "{0}" 吗？ |
| `detail.uninstall.success` | ✓ Uninstalled: {0} | ✓ 已卸载：{0} |
| `detail.uninstall.failed` | ✖ Uninstall failed: {0} | ✖ 卸载失败：{0} |

## Files Changed

| File | Change |
|------|--------|
| `SwissKit/.../plugin/PluginLoader.java` | Add `findJarPath()` + `uninstallPlugin()` |
| `SwissKit/.../ui/content/DetailPanel.java` | Add Uninstall button, confirmation dialog, `onUninstall` callback |
| `SwissKit/.../ui/MainWindow.java` | Wire `onUninstall` callback |
| `SwissKit/.../i18n/messages.properties` | Add English i18n keys |
| `SwissKit/.../i18n/messages_zh.properties` | Add Chinese i18n keys |

## Edge Cases

- **Built-in tools:** Uninstall button hidden (checked via `getType().isPlugin()`)
- **Plugin currently active:** Force deactivate before unload; cached view removed
- **Plugin has running background tasks:** `registry.deactivate()` already sends to background; `removePlugin()` (called by `unloadJar`) calls `onDeactivate()` + `onUnload()` regardless
- **Watcher double-fire:** `unloadJar()` removes from `jarPlugins` map first; second call is a no-op
- **File delete fails:** Log error, UI still shows plugin (honest failure)
