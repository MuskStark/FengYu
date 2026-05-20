# Plugin Preview Window — Design Spec

## Purpose

Provide a self-contained preview window in `SwissKitJ-Api` so third-party plugin developers can launch their plugin JAR (or plugin instance) and see exactly how it looks and behaves inside a SwissKitJ-like shell, without needing the full main application.

## Package Layout

All new code lives under `fan.summer.api.preview` in `SwissKitJ-Api`:

```
SwissKitJ-Api/src/main/java/fan/summer/api/preview/
├── PluginPreviewWindow.java    // Builder + Stage creation, the only public entry point
├── PreviewShell.java           // Shell layout (sidebar + content + status bar + detail panel)
├── PreviewSidebar.java         // Simplified sidebar (static categories, visual context only)
├── PreviewDetailPanel.java     // Simplified detail panel (metadata + Launch button)
├── PreviewToolCard.java        // Simplified tool card
└── PreviewTitleBar.java        // Simplified title bar (title + close button)

SwissKitJ-Api/src/main/resources/css/
└── swisskit-preview.css        // Preview shell styles (subset of shell.css, adapted for preview)
```

Only `PluginPreviewWindow` is public. All other classes are package-private.

Dependencies: only `javafx-graphics` and `javafx-controls` (both `provided` scope, already declared). No dependency on the `SwissKit` module.

## Builder API

```java
// From a JAR file (ServiceLoader scans META-INF/services)
PluginPreviewWindow.configure()
    .withJar(Path.of("build/libs/my-plugin.jar"))
    .launch();

// From a plugin instance
PluginPreviewWindow.configure()
    .withPlugin(new MyPlugin())
    .launch();

// Full configuration
PluginPreviewWindow.configure()
    .withJar(jarPath)
    .withPlugin(fallbackInstance)  // takes precedence if both set
    .title("My Plugin — Preview")
    .windowSize(900, 600)
    .showSidebar(true)
    .showStatusBar(true)
    .showDetailPanel(true)
    .showSearchBar(false)
    .launch();
```

### Configuration defaults

| Option | Default | Notes |
|--------|---------|-------|
| `title` | `"Plugin Preview"` | |
| `windowSize` | `960 × 620` | Same as main app default |
| `showSidebar` | `true` | |
| `showStatusBar` | `true` | |
| `showDetailPanel` | `true` | |
| `showSearchBar` | `true` | |

### JAR loading

When `withJar(Path)` is used:
1. Create a `URLClassLoader` with the JAR URL, parent = current thread's context class loader
2. `ServiceLoader.load(SwissKitJPlugin.class, classLoader)` scans for implementations
3. If multiple implementations found → show all as tool cards, developer clicks to launch each
4. If one implementation found → show it as the sole card
5. If none found → show an error message in the content area
6. On window close → call `onDeactivate()` + `onUnload()` on all loaded plugins, then `classLoader.close()`

`withPlugin(SwissKitJPlugin)` takes precedence: if both JAR and instance are provided, the instance is used and JAR loading is skipped.

## Shell Layout

```
┌──────────────────────────────────────────┐
│ PreviewTitleBar      title       [close] │
├──────────────────────────────────────────┤
│ SearchBar       (hidden if showSearchBar=false) │
├────────┬─────────────────────────────────┤
│        │                                 │
│ Sidebar│  ContentArea                    │
│(hidden │  ├─ Default: ToolCard grid      │
│ if     │  └─ Activated: createView()     │
│ false) │                                 │
│        │  DetailPanel (slides in from    │
│        │  right when card clicked)       │
├────────┴─────────────────────────────────┤
│ StatusBar  (hidden if showStatusBar=false) │
└──────────────────────────────────────────┘
```

### PreviewTitleBar
- Height ~40px, glass background
- Title label (left), close button (right)
- Drag-to-move support via mouse press-drag on the bar

### PreviewSidebar
- Static category list matching `ToolCategory` values
- Purely visual — no functional filtering (there is only one plugin to preview)
- Highlights the "Plugins" category by default
- Hidden when `showSidebar(false)`

### ContentArea
- **Grid mode**: displays `PreviewToolCard` for each loaded plugin in a `FlowPane`
- **Active mode**: displays `plugin.createView()` inside a `ScrollPane`, with a back bar at top
- Back bar: `"← Back"` label → returns to grid mode, calls `plugin.onDeactivate()`

### PreviewDetailPanel
- Slides in from the right (same 260px width, same animation as `DetailPanel`)
- Shows: icon, name, version, type, category, description
- "Launch Tool" button → transitions ContentArea to active mode, calls `plugin.onActivate()`
- Close button → slides out

### PreviewToolCard
- ~152×130 card with icon, name, description
- Click → shows `PreviewDetailPanel`
- Hover → scale + shadow effect
- Same visual style as `ToolCard` in the main app

## Interaction Flow

```
launch()
  └→ Window opens, sidebar shows "Plugins" selected
  └→ ToolCard grid displayed (one card per loaded plugin)

Click ToolCard
  └→ PreviewDetailPanel slides in from right
  └→ Shows plugin metadata

Click "Launch Tool"
  └→ ContentArea switches to active mode (plugin.createView())
  └→ Back bar appears at top
  └→ plugin.onActivate() called

Click "← Back"
  └→ ContentArea returns to grid mode
  └→ plugin.onDeactivate() called

Close window
  └→ All active plugins: onDeactivate() + onUnload()
  └→ ClassLoader closed (if JAR was loaded)
```

## CSS Strategy

`swisskit-preview.css` contains a focused subset of styles from `shell.css`:
- `.titlebar`, `.sidebar`, `.search-bar` — shell chrome
- `.tool-card`, `.detail-panel` — card + detail panel
- `.statusbar`, `.status-text` — status bar
- `.glass-*` utility classes are already in `swisskit-common.css` (loaded alongside)

The preview Scene loads both:
```java
scene.getStylesheets().addAll(
    Themes.commonStylesheetUrl(),           // swisskit-common.css (existing)
    PreviewShell.class.getResource("/css/swisskit-preview.css").toExternalForm()
);
```

This keeps the preview visually consistent with the main app without creating a dependency on the `SwissKit` module.

## Lifecycle Contract

| Event | Action |
|-------|--------|
| Window opens | nothing (plugins not yet activated) |
| "Launch Tool" clicked | `plugin.onActivate()` |
| Back to grid | `plugin.onDeactivate()` |
| Window closed | `plugin.onDeactivate()` (if active) + `plugin.onUnload()` |
| JAR loaded | `ClassLoader` held open until window closes |

If `onActivate()`/`onDeactivate()`/`onUnload()` throw, the exception is logged but does not crash the preview window.

## Error Handling

- JAR not found → dialog with error message, window does not open
- No `SwissKitJPlugin` implementation in JAR → content area shows "No plugin implementation found in JAR"
- `createView()` throws → content area shows error message with stack trace
- Plugin lifecycle methods throw → logged to stderr, preview continues

## What This Is NOT

- Not a full SwissKitJ app — no database, no plugin registry, no settings, no store
- Not a replacement for integration testing with the real main app
- Does not support hot-reload (file watcher) — that's a main-app-only feature
