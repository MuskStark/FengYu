# Plan: String-to-Enum Migration for Tool Type/Category/IconStyle

## Context

Currently the `SwissKitJPlugin` interface and all consumers use raw `String` literals for three concepts:

- **Category**: `"dev"`, `"text"`, `"image"`, `"net"`, `"other"` — 7 builtin tools + the `OnlineStorePane` JSON parser
- **Type**: `"builtin"`, `"plugin"` — checked with string comparison in 4 UI files + 7 builtin overrides
- **IconStyle**: `"ic-blue"`...`"ic-gray"` — 7 CSS class strings, with duplicated `resolveColor()` switch (20 lines each) in both `ToolCard` and `DetailPanel`

There are zero constants, enums, or shared definitions — just bare literals scattered across ~15 files. This makes the API unsafe for third-party plugin developers and causes code duplication.

The v3.0.0 branch is pre-release, so breaking changes to the SPI are acceptable.

## Plan

### Step 1: Create three enum classes in `SwissKitJ-Api/src/main/java/fan/summer/api/`

**`ToolCategory.java`**
- Values: `DEV("dev", "developer.tools")`, `TEXT`, `IMAGE`, `NET`, `OTHER("other", "other.tools")`
- Fields: `String id` (legacy lowercase string), `String i18nKey` (resource bundle key)
- Methods: `getId()`, `getI18nKey()`, `static fromId(String)` — case-insensitive, defaults to `OTHER`

**`ToolType.java`**
- Values: `BUILTIN("builtin")`, `PLUGIN("plugin")`
- Fields: `String id`
- Methods: `getId()`, `isBuiltin()`, `isPlugin()`

**`IconStyle.java`**
- Values: `BLUE("ic-blue", Color.rgb(99,130,255))`, `PURPLE`, `TEAL`, `AMBER`, `RED`, `PINK`, `GRAY`
- Fields: `String cssClass`, `Color color`
- Methods: `getCssClass()`, `getColor()`, `static fromCssClass(String)` — case-insensitive, defaults to `BLUE`

### Step 2: Update `SwissKitJPlugin` interface

File: `SwissKitJ-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java`

- Line 38-39: `String getCategory()` → `ToolCategory getCategory()`
- Line 51-55: `default String getIconStyle() { return "ic-blue"; }` → `default IconStyle getIconStyle() { return IconStyle.BLUE; }`
- Line 57-61: `default String getType() { return "plugin"; }` → `default ToolType getType() { return ToolType.PLUGIN; }`
- Update corresponding Javadoc comments

### Step 3: Update 7 built-in tools

All in `SwissKit/src/main/java/fan/summer/buildintool/`. Pattern for each:

| File | getCategory() | getIconStyle() | getType() |
|------|--------------|----------------|-----------|
| `dev/JsonFormatterPlugin.java` | `ToolCategory.DEV` | `IconStyle.BLUE` | `ToolType.BUILTIN` |
| `dev/Base64Plugin.java` | `ToolCategory.DEV` | `IconStyle.TEAL` | `ToolType.BUILTIN` |
| `dev/HashCalculatorPlugin.java` | `ToolCategory.DEV` | `IconStyle.AMBER` | `ToolType.BUILTIN` |
| `text/MarkdownEditorPlugin.java` | `ToolCategory.TEXT` | `IconStyle.BLUE` | `ToolType.BUILTIN` |
| `image/ColorConverterPlugin.java` | `ToolCategory.IMAGE` | `IconStyle.PINK` | `ToolType.BUILTIN` |
| `email/EmailPlugin.java` | `ToolCategory.NET` | `IconStyle.BLUE` | `ToolType.BUILTIN` |
| `excelsplitter/ExcelSplitterPlugin.java` | `ToolCategory.OTHER` | `IconStyle.TEAL` | `ToolType.BUILTIN` |

### Step 4: Update UI consumers

**`ToolCard.java`** (`SwissKit/src/main/java/fan/summer/ui/content/ToolCard.java`)
- Line 33: `resolveColor(plugin.getIconStyle())` → `plugin.getIconStyle().getColor()`
- Line 49: `.addAll("tool-icon-wrap", plugin.getIconStyle())` → `.addAll("tool-icon-wrap", plugin.getIconStyle().getCssClass())`
- Lines 65-66: `!"builtin".equals(plugin.getType())` → `plugin.getType().isPlugin()`
- Lines 120-130: Delete `resolveColor()` method entirely

**`DetailPanel.java`** (`SwissKit/src/main/java/fan/summer/ui/content/DetailPanel.java`)
- Line 135: `resolveColor(p.getIconStyle())` → `p.getIconStyle().getColor()`
- Line 154: `p.getType()` → `p.getType().getId()`
- Line 158: `p.getType()` → `p.getType().getId()`
- Line 159: `categoryName(p.getCategory())` — pass enum, update method signature
- Lines 162-172: Delete `resolveColor()` method
- Lines 174-182: Rewrite `categoryName(ToolCategory)` as enum switch

**`ContentArea.java`** (`SwissKit/src/main/java/fan/summer/ui/content/ContentArea.java`)
- Line 270: `!"builtin".equals(p.getType())` → `p.getType().isPlugin()`
- Line 272: `p.getCategory().equalsIgnoreCase(currentCategory)` → `p.getCategory().getId().equalsIgnoreCase(currentCategory)`

**`MainWindow.java`** (`SwissKit/src/main/java/fan/summer/ui/MainWindow.java`)
- Line 64: `plugin.getType().equals("plugin")` → `plugin.getType().isPlugin()`
- Line 228: `!"builtin".equals(p.getType())` → `p.getType().isPlugin()`

**`OnlineStorePane.java`** (`SwissKit/src/main/java/fan/summer/ui/store/OnlineStorePane.java`)
- Lines 183-184: Parse string then convert: `IconStyle.fromCssClass(iconStyleStr)` / `ToolCategory.fromId(categoryStr)`
- Line 263: `plugin.category` → `plugin.category.getId()`
- Lines 419-427: `StorePlugin.iconStyle` → `IconStyle`, `StorePlugin.category` → `ToolCategory`

### Step 5: Sidebar — No changes needed

The sidebar navigation IDs (`"all"`, `"plugins"`, `"fav"`, `"store"`, `"settings"`) are UI concepts, not tool metadata. The five category IDs (`"dev"`, `"text"`, `"image"`, `"net"`, `"other"`) continue to work as strings — the bridge is in `ContentArea.matchesCategory()` which compares `p.getCategory().getId()` against the sidebar's string.

### Migration build order

```
Step 1-2: mvn install -f SwissKitJ-Api/pom.xml -DskipTests   (API module)
Step 3-4: mvn clean package -pl SwissKit -am -DskipTests       (main module)
Verify:   mvn clean package -DskipTests                        (full project)
```

### Verification

1. Run `mvn clean package -DskipTests` from repo root — must compile with zero errors
2. Use `mcp__idea__get_file_problems` on each changed file to check for remaining issues
3. Verify the `OnlineStorePane` category badge renders correctly by checking `ToolCategory.fromId()` gracefully handles unknown categories (defaults to `OTHER`)
4. Confirm no remaining `"builtin"`, `"plugin"`, `"dev"`, `"text"`, `"image"`, `"net"` string literals in Java files (except in enums, sidebar nav IDs, and `store.json`)
