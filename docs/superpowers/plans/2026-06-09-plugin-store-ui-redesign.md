# 线上插件商店 UI 重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把线上插件商店从纯文字单列列表重构为带搜索、分类筛选、图标色块和三态安装按钮(安装/已安装/更新)的卡片网格。

**Architecture:** UI 主体在 `OnlineStorePane`(JavaFX `VBox`)重构为「标题 + 工具栏(搜索框 + 分类下拉 + 刷新)+ ScrollPane(FlowPane 卡片网格)+ 状态栏」。把版本比较、安装态判定、过滤匹配等纯逻辑抽到 `StorePluginLogic` 静态工具类并加 JUnit 单测;`StorePlugin` 由内部类抽为顶层数据类。安装态通过把已安装插件 `id→version` Map 从 `MainWindow`(持有 `registry`)经 `PluginStoreUi.build(...)` 传入计算。下载/原子移动逻辑保持不变。

**Tech Stack:** Java 21, JavaFX 21.0.2, JUnit 5(新增,test scope), MyBatis/H2(不涉及), Maven(经 IntelliJ 构建)。

> **构建/测试约束(来自 CLAUDE.md):** 本机没有系统 Maven,**禁止在普通 shell 跑 `mvn`**。所有编译/打包经 IntelliJ 内置 Maven(Maven 工具窗口)或 IDEA MCP 工具(`mcp__idea__build_project`)。单测用 IntelliJ 的 JUnit 运行器(测试类左侧绿色箭头)直接运行,或在 Maven 工具窗口执行 SwissKit 的 `test` goal。

---

## 文件结构

| 文件 | 责任 | 动作 |
|---|---|---|
| `SwissKit/pom.xml` | 构建配置 | 修改:加 JUnit 5 依赖 + surefire |
| `SwissKit/src/main/java/fan/summer/ui/store/StorePlugin.java` | 商店插件数据模型 | 新建(从 `OnlineStorePane` 内部类抽出) |
| `SwissKit/src/main/java/fan/summer/ui/store/StorePluginLogic.java` | 纯逻辑:版本比较 / 安装态 / 过滤 | 新建 |
| `SwissKit/src/test/java/fan/summer/ui/store/StorePluginLogicTest.java` | `StorePluginLogic` 单测 | 新建 |
| `SwissKit/src/main/resources/i18n/messages.properties` | 英文文案(默认) | 修改:新增 key |
| `SwissKit/src/main/resources/i18n/messages_zh.properties` | 中文文案 | 修改:新增 key |
| `SwissKit/src/main/resources/css/shell.css` | app-shell 样式 | 修改:新增 `.store-*` 类 |
| `SwissKit/src/main/java/fan/summer/ui/store/OnlineStorePane.java` | 商店主体 UI | 重构:工具栏 + 网格 + 卡片 + 过滤 + 三态按钮 + `installedVersions` 构造参数 |
| `SwissKit/src/main/java/fan/summer/ui/store/PluginStoreUi.java` | 商店容器 | 修改:`build(installed)` 透传已安装信息 |
| `SwissKit/src/main/java/fan/summer/ui/MainWindow.java` | 主窗口 | 修改:调用处传 `registry.getPlugins()` |

实现顺序:先打地基(pom → 数据模型 → 纯逻辑+测试 → i18n → CSS),再做 UI 主体重构,最后接线 + 验证。这样 UI 重构时所有依赖项已就绪。

---

## Task 1: 添加 JUnit 5 测试依赖

**Files:**
- Modify: `SwissKit/pom.xml`

- [ ] **Step 1: 在 `<dependencies>` 末尾(`logback-classic` 之后、`</dependencies>` 之前)加入 JUnit 5**

在 `SwissKit/pom.xml` 中,找到:
```xml
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <version>${logback.version}</version>
        </dependency>
    </dependencies>
```
改为(在 `</dependencies>` 前插入 JUnit):
```xml
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <version>${logback.version}</version>
        </dependency>

        <!-- JUnit 5 (test scope) — pure-logic unit tests only -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
```

- [ ] **Step 2: 在 `<build><plugins>` 中加入 surefire 插件**

在 `SwissKit/pom.xml` 的 `maven-compiler-plugin` `</plugin>` 之后(仍在 `<plugins>` 内)加入:
```xml
            <!-- Surefire: run JUnit 5 tests -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
```

- [ ] **Step 3: 让 IntelliJ 重新加载 Maven 项目**

在 IntelliJ 的 Maven 工具窗口点击 "Reload All Maven Projects"(或用 `mcp__idea__build_project` 触发一次构建),确认无依赖解析错误。
Expected: 项目同步成功,`org.junit.jupiter` 出现在 External Libraries。

- [ ] **Step 4: Commit**

```bash
git add SwissKit/pom.xml
git commit -m "✨ feat: add JUnit 5 test dependency to SwissKit"
```

---

## Task 2: 把 `StorePlugin` 抽取为顶层数据类

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ui/store/StorePlugin.java`
- Modify: `SwissKit/src/main/java/fan/summer/ui/store/OnlineStorePane.java`(删除内部类 + 修正引用)

- [ ] **Step 1: 新建顶层 `StorePlugin` 类**

写入 `SwissKit/src/main/java/fan/summer/ui/store/StorePlugin.java`:
```java
package fan.summer.zhiflow.ui.store;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ToolCategory;

/**
 * Lightweight data class representing a plugin available in the online store.
 * Instances are created by parsing the store's JSON catalog. Fields are public
 * and mutable to mirror the JSON shape directly during parsing.
 *
 * @since 1.0
 */
public class StorePlugin {
    public String id;
    public String name;
    public String description;
    public String version;
    public String jarUrl;
    public IconStyle iconStyle;
    public ToolCategory category;
}
```

- [ ] **Step 2: 从 `OnlineStorePane` 删除内部 `StorePlugin` 类**

在 `OnlineStorePane.java` 末尾,删除整段(第 439-455 行附近):
```java
    // ── Data model ────────────────────────────────────────────────

    /**
     * Lightweight data class representing a plugin available in the online store.
     * Instances are created by parsing the store's JSON catalog.
     *
     * @since 1.0
     */
    public static class StorePlugin {
        public String id;
        public String name;
        public String description;
        public String version;
        public String jarUrl;
        public IconStyle iconStyle;
        public ToolCategory category;
    }
```
保留最后的类结束 `}`。`OnlineStorePane` 内对 `StorePlugin` 的引用因同包顶层类而无需改 import。

- [ ] **Step 3: 编译确认**

在 IntelliJ 构建 SwissKit 模块(Maven 工具窗口 `compile` goal 或 `mcp__idea__build_project`)。
Expected: 编译通过,无 "cannot find symbol StorePlugin"。

- [ ] **Step 4: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ui/store/StorePlugin.java \
        SwissKit/src/main/java/fan/summer/ui/store/OnlineStorePane.java
git commit -m "♻️ refactor: extract StorePlugin to top-level class"
```

---

## Task 3: 纯逻辑工具类 `StorePluginLogic`(TDD)

**Files:**
- Create: `SwissKit/src/test/java/fan/summer/ui/store/StorePluginLogicTest.java`
- Create: `SwissKit/src/main/java/fan/summer/ui/store/StorePluginLogic.java`

- [ ] **Step 1: 先写失败测试**

写入 `SwissKit/src/test/java/fan/summer/ui/store/StorePluginLogicTest.java`:
```java
package fan.summer.zhiflow.ui.store;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ToolCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static fan.summer.zhiflow.ui.store.StorePluginLogic.InstallState.*;
import static org.junit.jupiter.api.Assertions.*;

class StorePluginLogicTest {

    // ── compareVersion ───────────────────────────────────────────
    @Test
    void compareVersion_equal() {
        assertEquals(0, StorePluginLogic.compareVersion("1.2.3", "1.2.3"));
    }

    @Test
    void compareVersion_higherMinor() {
        assertTrue(StorePluginLogic.compareVersion("1.3.0", "1.2.9") > 0);
    }

    @Test
    void compareVersion_lowerMajor() {
        assertTrue(StorePluginLogic.compareVersion("1.9.9", "2.0.0") < 0);
    }

    @Test
    void compareVersion_differentSegmentCount() {
        // "1.2" treated as "1.2.0"
        assertEquals(0, StorePluginLogic.compareVersion("1.2", "1.2.0"));
        assertTrue(StorePluginLogic.compareVersion("1.2.1", "1.2") > 0);
    }

    @Test
    void compareVersion_nonNumericFallsBackToStringCompare() {
        // 3.0.0-rc.3 vs 3.0.0-rc.2 → rc.3 > rc.2 on the trailing segment
        assertTrue(StorePluginLogic.compareVersion("3.0.0-rc.3", "3.0.0-rc.2") > 0);
    }

    @Test
    void compareVersion_nullSafe() {
        assertEquals(0, StorePluginLogic.compareVersion(null, null));
        assertTrue(StorePluginLogic.compareVersion("1.0.0", null) > 0);
        assertTrue(StorePluginLogic.compareVersion(null, "1.0.0") < 0);
    }

    // ── installState ─────────────────────────────────────────────
    @Test
    void installState_notInstalled() {
        assertEquals(NOT_INSTALLED,
            StorePluginLogic.installState("com.x.a", "1.0.0", Map.of("com.x.b", "1.0.0")));
    }

    @Test
    void installState_installedSameVersion() {
        assertEquals(INSTALLED,
            StorePluginLogic.installState("com.x.a", "1.0.0", Map.of("com.x.a", "1.0.0")));
    }

    @Test
    void installState_storeNewer_updatable() {
        assertEquals(UPDATABLE,
            StorePluginLogic.installState("com.x.a", "1.1.0", Map.of("com.x.a", "1.0.0")));
    }

    @Test
    void installState_installedNewerThanStore_treatedAsInstalled() {
        assertEquals(INSTALLED,
            StorePluginLogic.installState("com.x.a", "1.0.0", Map.of("com.x.a", "1.2.0")));
    }

    @Test
    void installState_nullMap_notInstalled() {
        assertEquals(NOT_INSTALLED,
            StorePluginLogic.installState("com.x.a", "1.0.0", null));
    }

    // ── matches ──────────────────────────────────────────────────
    private StorePlugin sample() {
        StorePlugin p = new StorePlugin();
        p.id = "com.example.excel-splitter";
        p.name = "Excel Splitter";
        p.description = "Split large Excel files by sheet or column.";
        p.version = "2.1.0";
        p.iconStyle = IconStyle.BLUE;
        p.category = ToolCategory.DEV;
        return p;
    }

    @Test
    void matches_emptyQueryAllCategory_true() {
        assertTrue(StorePluginLogic.matches(sample(), "", null));
        assertTrue(StorePluginLogic.matches(sample(), null, null));
    }

    @Test
    void matches_nameSubstringCaseInsensitive() {
        assertTrue(StorePluginLogic.matches(sample(), "excel", null));
        assertTrue(StorePluginLogic.matches(sample(), "SPLIT", null));
    }

    @Test
    void matches_descriptionAndId() {
        assertTrue(StorePluginLogic.matches(sample(), "column", null));
        assertTrue(StorePluginLogic.matches(sample(), "example.excel", null));
    }

    @Test
    void matches_noHit_false() {
        assertFalse(StorePluginLogic.matches(sample(), "zzz", null));
    }

    @Test
    void matches_categoryFilter() {
        assertTrue(StorePluginLogic.matches(sample(), "", ToolCategory.DEV));
        assertFalse(StorePluginLogic.matches(sample(), "", ToolCategory.IMAGE));
    }

    @Test
    void matches_categoryAndQueryCombined() {
        assertTrue(StorePluginLogic.matches(sample(), "excel", ToolCategory.DEV));
        assertFalse(StorePluginLogic.matches(sample(), "excel", ToolCategory.NET));
    }
}
```

- [ ] **Step 2: 运行测试,确认编译失败/红**

在 IntelliJ 打开 `StorePluginLogicTest`,点击类名旁绿色箭头运行(或 Maven `test` goal)。
Expected: 编译失败 —— `StorePluginLogic` 不存在(cannot find symbol)。

- [ ] **Step 3: 实现 `StorePluginLogic`**

写入 `SwissKit/src/main/java/fan/summer/ui/store/StorePluginLogic.java`:
```java
package fan.summer.zhiflow.ui.store;

import fan.summer.zhiflow.api.ToolCategory;

import java.util.Map;

/**
 * Pure, side-effect-free helpers for the online plugin store: version comparison,
 * install-state classification, and search/category filtering. Kept separate from
 * {@link OnlineStorePane} so the logic is unit-testable without a JavaFX runtime.
 *
 * @since 1.0
 */
public final class StorePluginLogic {

    private StorePluginLogic() {}

    /** Install status of a store plugin relative to the locally installed set. */
    public enum InstallState { NOT_INSTALLED, INSTALLED, UPDATABLE }

    /**
     * Compares two dotted version strings segment by segment. Numeric segments are
     * compared as integers; if either segment is non-numeric, that segment pair is
     * compared lexicographically. Missing trailing segments are treated as "0".
     *
     * @return &gt;0 if {@code a} is newer, 0 if equal, &lt;0 if {@code a} is older
     */
    public static int compareVersion(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        String[] as = a.split("\\.");
        String[] bs = b.split("\\.");
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            String sa = i < as.length ? as[i] : "0";
            String sb = i < bs.length ? bs[i] : "0";
            if (sa.equals(sb)) continue;
            Integer ia = tryParse(sa);
            Integer ib = tryParse(sb);
            int cmp;
            if (ia != null && ib != null) {
                cmp = Integer.compare(ia, ib);
            } else {
                cmp = sa.compareTo(sb);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private static Integer tryParse(String s) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return null; }
    }

    /**
     * Classifies a store plugin's install state against the installed id→version map.
     *
     * @param id           the store plugin id
     * @param storeVersion the version offered by the store
     * @param installed    map of installed plugin id → version; may be null/empty
     */
    public static InstallState installState(String id, String storeVersion,
                                            Map<String, String> installed) {
        if (installed == null || !installed.containsKey(id)) {
            return InstallState.NOT_INSTALLED;
        }
        String localVersion = installed.get(id);
        return compareVersion(storeVersion, localVersion) > 0
                ? InstallState.UPDATABLE
                : InstallState.INSTALLED;
    }

    /**
     * Tests whether a plugin passes the current search query and category filter.
     *
     * @param p      the plugin
     * @param query  case-insensitive substring matched against name/description/id;
     *               null or blank matches everything
     * @param filter required category, or null to mean "all categories"
     */
    public static boolean matches(StorePlugin p, String query, ToolCategory filter) {
        if (filter != null && p.category != filter) return false;
        if (query == null || query.isBlank()) return true;
        String q = query.trim().toLowerCase();
        return contains(p.name, q) || contains(p.description, q) || contains(p.id, q);
    }

    private static boolean contains(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase().contains(lowerNeedle);
    }
}
```

- [ ] **Step 4: 运行测试,确认全绿**

在 IntelliJ 重新运行 `StorePluginLogicTest`。
Expected: 全部测试 PASS(共 ~18 个)。

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ui/store/StorePluginLogic.java \
        SwissKit/src/test/java/fan/summer/ui/store/StorePluginLogicTest.java
git commit -m "✨ feat: add StorePluginLogic (version compare, install state, filter) with tests"
```

---

## Task 4: 新增 i18n 文案

**Files:**
- Modify: `SwissKit/src/main/resources/i18n/messages.properties`(英文)
- Modify: `SwissKit/src/main/resources/i18n/messages_zh.properties`(中文)

- [ ] **Step 1: 英文 key**

在 `messages.properties` 中,找到 `store.online.installFailed=Install failed: {0}` 这一行,在其后追加:
```properties
store.online.search=Search plugins…
store.online.category.all=All
store.online.category.dev=Dev
store.online.category.text=Text
store.online.category.image=Image
store.online.category.net=Network
store.online.category.other=Other
store.online.btn.install=Install
store.online.btn.installed=✓ Installed
store.online.btn.update=↑ Update
store.online.noMatch=No matching plugins
store.online.countWithInstalled={0} plugins · {1} installed
```

- [ ] **Step 2: 中文 key**

在 `messages_zh.properties` 中,找到 `store.online.installFailed=安装失败：{0}` 这一行,在其后追加:
```properties
store.online.search=搜索插件…
store.online.category.all=全部
store.online.category.dev=开发
store.online.category.text=文本
store.online.category.image=图像
store.online.category.net=网络
store.online.category.other=其他
store.online.btn.install=安装
store.online.btn.installed=✓ 已安装
store.online.btn.update=↑ 更新
store.online.noMatch=未找到匹配的插件
store.online.countWithInstalled=共 {0} 个插件 · 已安装 {1}
```

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/resources/i18n/messages.properties \
        SwissKit/src/main/resources/i18n/messages_zh.properties
git commit -m "✨ feat: add i18n keys for redesigned plugin store"
```

---

## Task 5: 新增 `.store-*` CSS 类

**Files:**
- Modify: `SwissKit/src/main/resources/css/shell.css`

- [ ] **Step 1: 在 `shell.css` 末尾追加商店样式**

把以下内容追加到 `SwissKit/src/main/resources/css/shell.css` 文件末尾:
```css
/* ============================================================
   Plugin Store (online) — redesigned card grid
   ============================================================ */

.store-search {
    -fx-background-color: rgba(255,255,255,0.05);
    -fx-border-color: rgba(255,255,255,0.10);
    -fx-border-width: 1;
    -fx-border-radius: 9;
    -fx-background-radius: 9;
    -fx-text-fill: rgba(255,255,255,0.85);
    -fx-prompt-text-fill: rgba(255,255,255,0.35);
    -fx-font-size: 12.5px;
    -fx-padding: 8 12 8 12;
}
.store-search:focused {
    -fx-border-color: rgba(91,140,247,0.55);
}

.store-card {
    -fx-background-color: rgba(255,255,255,0.035);
    -fx-border-color: rgba(255,255,255,0.09);
    -fx-border-width: 1;
    -fx-border-radius: 12;
    -fx-background-radius: 12;
}
.store-card:hover {
    -fx-border-color: rgba(91,140,247,0.45);
    -fx-background-color: rgba(255,255,255,0.055);
}

.store-card-name {
    -fx-text-fill: rgba(255,255,255,0.92);
    -fx-font-size: 13.5px;
    -fx-font-weight: bold;
}

.store-badge {
    -fx-text-fill: rgba(255,255,255,0.55);
    -fx-font-size: 10px;
    -fx-background-color: rgba(255,255,255,0.08);
    -fx-background-radius: 5;
    -fx-padding: 2 7 2 7;
}

.store-card-desc {
    -fx-text-fill: rgba(255,255,255,0.50);
    -fx-font-size: 11.5px;
}

.store-install-btn {
    -fx-background-color: #5b8cf7;
    -fx-text-fill: white;
    -fx-font-size: 12px;
    -fx-font-weight: bold;
    -fx-background-radius: 8;
    -fx-border-width: 0;
    -fx-padding: 7 0 7 0;
    -fx-cursor: hand;
}
.store-install-btn.installed {
    -fx-background-color: rgba(76,217,123,0.15);
    -fx-text-fill: #4cd97b;
    -fx-border-color: rgba(76,217,123,0.30);
    -fx-border-width: 1;
    -fx-border-radius: 8;
    -fx-cursor: default;
}
.store-install-btn.update {
    -fx-background-color: rgba(240,169,58,0.15);
    -fx-text-fill: #f0a93a;
    -fx-border-color: rgba(240,169,58,0.30);
    -fx-border-width: 1;
    -fx-border-radius: 8;
}
```

- [ ] **Step 2: Commit**

```bash
git add SwissKit/src/main/resources/css/shell.css
git commit -m "💄 style: add .store-* CSS classes for redesigned plugin store"
```

---

## Task 6: 重构 `OnlineStorePane` 主体

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/store/OnlineStorePane.java`(整体重写)

> 本任务整体替换文件内容。下载/原子移动逻辑保持原样,仅在成功回调里新增「刷新该卡片状态」。新增构造参数 `Map<String,String> installedVersions`;旧的单参构造保留并委托(传 null)以兼容其它调用方。

- [ ] **Step 1: 整体重写 `OnlineStorePane.java`**

用以下完整内容替换 `SwissKit/src/main/java/fan/summer/ui/store/OnlineStorePane.java`:
```java
package fan.summer.zhiflow.ui.store;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ToolCategory;
import fan.summer.zhiflow.api.component.GlassNotification;
import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.plugin.PluginLoader;
import fan.summer.zhiflow.ui.store.StorePluginLogic.InstallState;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fan.summer.zhiflow.ai.util.JsonHelper;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Online plugin store pane: fetches the plugin catalog from a remote JSON API and
 * displays each plugin as a card in a responsive grid. A search box and category
 * dropdown filter the visible cards in memory; each card shows a tri-state install
 * button (install / installed / update) computed against the locally installed set.
 * <p>
 * The catalog is fetched automatically on construction. Downloads are written to a
 * {@code .part} temp file and atomically moved into the {@code plugins/} directory.
 *
 * @see PluginLoader#resolvePluginsDir()
 * @see StorePluginLogic
 * @since 1.0
 */
public class OnlineStorePane extends VBox {

    private static final Logger log = LoggerFactory.getLogger(OnlineStorePane.class);

    private static final double CARD_WIDTH = 260;

    private final Runnable onInstallComplete;
    /** Installed plugin id → version; used to compute install state. Never null. */
    private final Map<String, String> installedVersions;

    private final TextField searchField;
    private final ComboBox<ToolCategory> categoryBox;
    private final FlowPane grid;
    private final ProgressBar fetchProgress;
    private final Label statusLabel;
    private final HBox loadingRow;

    /** Full catalog from the last successful fetch; filtered in memory. */
    private final List<StorePlugin> allPlugins = new ArrayList<>();

    /** Sentinel meaning "All categories" in the dropdown (modelled as null value). */
    private static final ToolCategory ALL = null;

    /**
     * Backwards-compatible constructor with no installed-version info: every plugin
     * shows the plain "Install" button.
     *
     * @param onInstallComplete callback invoked after each successful install; may be null
     */
    public OnlineStorePane(Runnable onInstallComplete) {
        this(onInstallComplete, null);
    }

    /**
     * Constructs the online store pane, auto-fetching the plugin list on creation.
     *
     * @param onInstallComplete callback invoked after each successful install; may be null
     * @param installedVersions installed plugin id → version map; may be null
     */
    public OnlineStorePane(Runnable onInstallComplete, Map<String, String> installedVersions) {
        this.onInstallComplete = onInstallComplete;
        this.installedVersions = installedVersions != null
                ? new HashMap<>(installedVersions) : new HashMap<>();

        setSpacing(16);
        setStyle("-fx-background-color: transparent;");
        setPadding(new Insets(24));

        // ── Title + description ──────────────────────────────
        Label title = new Label(I18n.get("store.online.title"));
        title.setStyle("-fx-text-fill: rgba(255,255,255,0.90); -fx-font-size: 18px; -fx-font-weight: 500;");
        Label desc = new Label(I18n.get("store.online.desc"));
        desc.setStyle("-fx-text-fill: rgba(255,255,255,0.45); -fx-font-size: 12px;");
        desc.setWrapText(true);
        desc.setMaxWidth(Double.MAX_VALUE);

        // ── Toolbar: search + category + refresh ─────────────
        searchField = new TextField();
        searchField.setPromptText(I18n.get("store.online.search"));
        searchField.getStyleClass().add("store-search");
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, o, n) -> applyFilters());

        categoryBox = new ComboBox<>();
        categoryBox.getItems().add(ALL); // "All"
        categoryBox.getItems().addAll(ToolCategory.values());
        categoryBox.getSelectionModel().select(ALL);
        categoryBox.setConverter(new StringConverter<>() {
            @Override public String toString(ToolCategory c) {
                if (c == ALL) return I18n.get("store.online.category.all");
                return I18n.get("store.online.category." + c.getId());
            }
            @Override public ToolCategory fromString(String s) { return null; }
        });
        categoryBox.getStyleClass().add("glass-combo");
        categoryBox.valueProperty().addListener((obs, o, n) -> applyFilters());

        Button refreshBtn = glassBtn(I18n.get("store.online.refresh"));
        refreshBtn.setOnAction(e -> fetchPluginList());

        HBox toolbar = new HBox(10, searchField, categoryBox, refreshBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // ── Grid in scroll area ──────────────────────────────
        grid = new FlowPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setStyle("-fx-background-color: transparent;");
        grid.setPadding(new Insets(4, 0, 4, 0));

        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.setMaxWidth(Double.MAX_VALUE);
        scrollPane.setMaxHeight(Double.MAX_VALUE);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // ── Loading row ──────────────────────────────────────
        Label spinner = new Label("⏳");
        spinner.setStyle("-fx-font-size: 16px;");
        Label loadingText = new Label(I18n.get("store.online.fetching"));
        loadingText.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");
        fetchProgress = new ProgressBar();
        fetchProgress.setPrefWidth(200);
        fetchProgress.setStyle("-fx-accent: #5b8cf7;");
        loadingRow = new HBox(10, spinner, loadingText, fetchProgress);
        loadingRow.setAlignment(Pos.CENTER_LEFT);
        loadingRow.setVisible(false);
        loadingRow.setManaged(false);
        loadingRow.setPadding(new Insets(8, 0, 0, 0));

        // ── Status label ─────────────────────────────────────
        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.50); -fx-font-size: 12px;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        getChildren().addAll(title, desc, toolbar, scrollPane, loadingRow, statusLabel);

        fetchPluginList();
    }

    // ── Fetch ────────────────────────────────────────────────────

    private void fetchPluginList() {
        String urlStr = fan.summer.zhiflow.ui.setting.SwissKitJSettingUi.getStoreUrl();
        showLoading(true);
        statusLabel.setText("");

        new Thread(() -> {
            try {
                List<StorePlugin> plugins = fetchPlugins(urlStr);
                Platform.runLater(() -> {
                    showLoading(false);
                    allPlugins.clear();
                    allPlugins.addAll(plugins);
                    applyFilters();
                });
            } catch (Exception e) {
                log.error("Failed to fetch plugin list from {}", urlStr, e);
                Platform.runLater(() -> {
                    showLoading(false);
                    allPlugins.clear();
                    showError(I18n.get("store.online.installFailed", e.getMessage()));
                    applyFilters();
                });
            }
        }).start();
    }

    private List<StorePlugin> fetchPlugins(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new RuntimeException("HTTP " + responseCode);
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return parsePluginJson(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private List<StorePlugin> parsePluginJson(String json) {
        List<StorePlugin> result = new ArrayList<>();
        try {
            List<Object> array = JsonHelper.parseList(json);
            if (array == null) return result;
            for (Object item : array) {
                if (!(item instanceof Map<?, ?> rawObj)) continue;
                Map<String, Object> obj = (Map<String, Object>) rawObj;

                StorePlugin p = new StorePlugin();
                p.id = (String) obj.getOrDefault("id", null);
                p.name = (String) obj.getOrDefault("name", null);
                p.description = (String) obj.getOrDefault("description", null);
                p.version = (String) obj.getOrDefault("version", null);
                p.jarUrl = (String) obj.getOrDefault("jarUrl", null);
                p.iconStyle = IconStyle.fromCssClass((String) obj.getOrDefault("iconStyle", "ic-blue"));
                p.category = ToolCategory.fromId((String) obj.getOrDefault("category", "other"));

                if (p.id != null && p.name != null && p.jarUrl != null) result.add(p);
            }
        } catch (Exception e) {
            log.warn("JSON parse error, showing partial results", e);
        }
        return result;
    }

    // ── Filtering + rendering ────────────────────────────────────

    /** Re-applies the current search + category filter and rebuilds the grid. */
    private void applyFilters() {
        String query = searchField.getText();
        ToolCategory filter = categoryBox.getValue();

        grid.getChildren().clear();
        List<StorePlugin> visible = new ArrayList<>();
        for (StorePlugin p : allPlugins) {
            if (StorePluginLogic.matches(p, query, filter)) visible.add(p);
        }

        if (allPlugins.isEmpty()) {
            statusLabel.setText(I18n.get("store.online.noPlugins"));
            statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.40); -fx-font-size: 12px;");
            return;
        }
        if (visible.isEmpty()) {
            statusLabel.setText(I18n.get("store.online.noMatch"));
            statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.40); -fx-font-size: 12px;");
            return;
        }

        for (StorePlugin p : visible) grid.getChildren().add(buildPluginCard(p));

        int installedCount = 0;
        for (StorePlugin p : allPlugins) {
            if (installedVersions.containsKey(p.id)) installedCount++;
        }
        statusLabel.setText(I18n.get("store.online.countWithInstalled", allPlugins.size(), installedCount));
        statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.45); -fx-font-size: 12px;");
    }

    private VBox buildPluginCard(StorePlugin plugin) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(14));
        card.setPrefWidth(CARD_WIDTH);
        card.setMinWidth(CARD_WIDTH);
        card.setMaxWidth(CARD_WIDTH);
        card.getStyleClass().add("store-card");

        // Header: icon tile + name + meta
        StackPane iconTile = buildIconTile(plugin);
        Label nameLabel = new Label(plugin.name);
        nameLabel.getStyleClass().add("store-card-name");
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        Label versionBadge = new Label("v" + (plugin.version != null ? plugin.version : "?"));
        versionBadge.getStyleClass().add("store-badge");
        Label categoryBadge = new Label(I18n.get("store.online.category." + plugin.category.getId()));
        categoryBadge.getStyleClass().add("store-badge");
        HBox meta = new HBox(6, versionBadge, categoryBadge);
        meta.setAlignment(Pos.CENTER_LEFT);

        VBox titleCol = new VBox(4, nameLabel, meta);
        titleCol.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleCol, Priority.ALWAYS);

        HBox header = new HBox(11, iconTile, titleCol);
        header.setAlignment(Pos.CENTER_LEFT);

        // Description
        Label descLabel = new Label(plugin.description != null ? plugin.description : "");
        descLabel.getStyleClass().add("store-card-desc");
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(Double.MAX_VALUE);
        descLabel.setMinHeight(32);
        descLabel.setMaxHeight(32);

        // Install button (tri-state)
        Button installBtn = new Button();
        installBtn.getStyleClass().add("store-install-btn");
        installBtn.setMaxWidth(Double.MAX_VALUE);
        applyInstallState(installBtn, plugin);

        card.getChildren().addAll(header, descLabel, installBtn);
        return card;
    }

    /** Sets the install button's text/style/handler based on current install state. */
    private void applyInstallState(Button installBtn, StorePlugin plugin) {
        installBtn.getStyleClass().removeAll("installed", "update");
        InstallState state = StorePluginLogic.installState(plugin.id, plugin.version, installedVersions);
        switch (state) {
            case INSTALLED -> {
                installBtn.setText(I18n.get("store.online.btn.installed"));
                installBtn.getStyleClass().add("installed");
                installBtn.setDisable(true);
                installBtn.setOnAction(null);
            }
            case UPDATABLE -> {
                installBtn.setText(I18n.get("store.online.btn.update"));
                installBtn.getStyleClass().add("update");
                installBtn.setDisable(false);
                installBtn.setOnAction(e -> installPlugin(plugin, installBtn));
            }
            case NOT_INSTALLED -> {
                installBtn.setText(I18n.get("store.online.btn.install"));
                installBtn.setDisable(false);
                installBtn.setOnAction(e -> installPlugin(plugin, installBtn));
            }
        }
    }

    private StackPane buildIconTile(StorePlugin plugin) {
        String glyph = (plugin.name != null && !plugin.name.isBlank())
                ? plugin.name.substring(0, 1).toUpperCase() : "?";
        Label letter = new Label(glyph);
        letter.setStyle("-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold;");
        StackPane tile = new StackPane(letter);
        tile.setMinSize(38, 38);
        tile.setPrefSize(38, 38);
        tile.setMaxSize(38, 38);
        Color c = plugin.iconStyle != null ? plugin.iconStyle.getColor() : IconStyle.BLUE.getColor();
        tile.setStyle(
            "-fx-background-color: " + toRgbCss(c) + ";" +
            "-fx-background-radius: 10;"
        );
        return tile;
    }

    private static String toRgbCss(Color c) {
        return String.format("rgb(%d,%d,%d)",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    // ── Install (download + atomic move; unchanged behaviour) ─────

    private void installPlugin(StorePlugin plugin, Button installBtn) {
        installBtn.setDisable(true);
        installBtn.setText(I18n.get("store.online.fetching"));

        new Thread(() -> {
            Path tempFile = null;
            try {
                Path pluginDir = PluginLoader.resolvePluginsDir();
                Files.createDirectories(pluginDir);

                HttpURLConnection conn = (HttpURLConnection) new URL(plugin.jarUrl).openConnection();
                try {
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);

                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) {
                        throw new RuntimeException("Download failed: HTTP " + responseCode);
                    }

                    final String jarFileName;
                    String extractedName = plugin.jarUrl.substring(plugin.jarUrl.lastIndexOf('/') + 1);
                    if (extractedName.toLowerCase().endsWith(".jar")) {
                        jarFileName = extractedName;
                    } else {
                        jarFileName = plugin.id.replace('.', '-') + ".jar";
                    }
                    Path target = pluginDir.resolve(jarFileName);

                    // Write to a temporary .part file first, then atomically move to target.
                    tempFile = pluginDir.resolve(jarFileName + ".part");
                    try (var in = conn.getInputStream();
                         var out = new FileOutputStream(tempFile.toFile())) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }

                    Files.move(tempFile, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    tempFile = null;

                    log.info("Plugin downloaded and installed: {}", target);

                    Platform.runLater(() -> {
                        // Mark installed at the store version and refresh this card's button.
                        installedVersions.put(plugin.id, plugin.version);
                        applyInstallState(installBtn, plugin);
                        statusLabel.setText(I18n.get("store.online.installed", plugin.name, jarFileName));
                        statusLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
                        GlassNotification.toast(OnlineStorePane.this, GlassNotification.Type.SUCCESS,
                                I18n.get("store.online.installed", plugin.name, jarFileName));
                        if (onInstallComplete != null) onInstallComplete.run();
                    });
                } finally {
                    conn.disconnect();
                }
            } catch (Exception ex) {
                if (tempFile != null) {
                    try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
                }
                log.error("Plugin install failed for {}", plugin.id, ex);
                Platform.runLater(() -> {
                    showError(I18n.get("store.online.installFailed", ex.getMessage()));
                    applyInstallState(installBtn, plugin); // restore install/update button
                });
            }
        }).start();
    }

    // ── Small helpers ─────────────────────────────────────────────

    private void showLoading(boolean show) {
        loadingRow.setVisible(show);
        loadingRow.setManaged(show);
        fetchProgress.setVisible(show);
        if (show) fetchProgress.setProgress(-1);
    }

    private void showError(String msg) {
        statusLabel.setText("❌ " + msg);
        statusLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
    }

    private static Button glassBtn(String text) {
        Button btn = new Button(text);
        btn.setStyle(
            "-fx-background-color: rgba(255,255,255,0.07);" +
            "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
            "-fx-text-fill: rgba(255,255,255,0.75); -fx-font-size: 13px;" +
            "-fx-background-radius: 8; -fx-border-radius: 8;" +
            "-fx-padding: 8 14 8 14; -fx-cursor: hand;"
        );
        return btn;
    }
}
```

- [ ] **Step 2: 编译确认**

在 IntelliJ 构建 SwissKit 模块。
Expected: 编译通过。若 `glass-combo` 类不存在也无妨(只是少了样式),但应确认无编译错误。

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ui/store/OnlineStorePane.java
git commit -m "✨ feat: redesign online store as searchable, filterable card grid"
```

---

## Task 7: 接线已安装信息(`PluginStoreUi` + `MainWindow`)

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/store/PluginStoreUi.java`
- Modify: `SwissKit/src/main/java/fan/summer/ui/MainWindow.java:283`

- [ ] **Step 1: `PluginStoreUi.build` 接收已安装列表**

在 `PluginStoreUi.java` 顶部 import 区加入(若尚无):
```java
import fan.summer.zhiflow.api.SwissKitJPlugin;
import javafx.collections.ObservableList;
import java.util.HashMap;
import java.util.Map;
```

把方法签名:
```java
    public static Node build() {

        // ── Content pages ──────────────────────────────────
        Node onlinePage = new OnlineStorePane(null);
        Node localPage  = new LocalInstallPane(null);
```
改为:
```java
    public static Node build(ObservableList<SwissKitJPlugin> installed) {

        // ── Content pages ──────────────────────────────────
        Map<String, String> installedVersions = new HashMap<>();
        if (installed != null) {
            for (SwissKitJPlugin p : installed) {
                installedVersions.put(p.getId(), p.getVersion());
            }
        }
        Node onlinePage = new OnlineStorePane(null, installedVersions);
        Node localPage  = new LocalInstallPane(null);
```

- [ ] **Step 2: 更新 `MainWindow` 调用处**

在 `MainWindow.java` 第 283 行,把:
```java
                contentArea.showPage(fan.summer.zhiflow.ui.store.PluginStoreUi.build(), I18n.get("store.online.title"));
```
改为:
```java
                contentArea.showPage(fan.summer.zhiflow.ui.store.PluginStoreUi.build(registry.getPlugins()), I18n.get("store.online.title"));
```

> 确认 `MainWindow` 中存在字段 `registry`(`wireEvents()` 内已用 `registry.getPlugins()`,见第 276 行),无需新增。

- [ ] **Step 3: 编译确认**

在 IntelliJ 构建 SwissKit 模块。
Expected: 编译通过,无 `build()` 参数不匹配错误。若仓库内还有其它 `PluginStoreUi.build()` 调用(Task 准备时已确认仅 `MainWindow` 一处),也应一并更新为传参。

- [ ] **Step 4: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ui/store/PluginStoreUi.java \
        SwissKit/src/main/java/fan/summer/ui/MainWindow.java
git commit -m "✨ feat: pass installed plugin versions into online store"
```

---

## Task 8: 整体构建 + 手动验证

**Files:** 无(验证任务)

- [ ] **Step 1: 全量构建**

先构建 API 模块再构建主应用(经 IntelliJ Maven / IDEA MCP,不用系统 mvn):
- `mvn install -f SwissKitJ-Api/pom.xml -DskipTests`
- `mvn clean package -f SwissKit/pom.xml -DskipTests`

Expected: BUILD SUCCESS,生成 `SwissKit/target/SwissKitJ-3.0.0-rc.3.jar`。

- [ ] **Step 2: 运行并手动核对**

运行应用(`java -jar SwissKit/target/SwissKitJ-3.0.0-rc.3.jar`),打开侧栏「插件商店 / Plugin Store」→「线上商店」,逐项核对:
- [ ] 顶部出现搜索框 + 右侧分类下拉 + 刷新按钮(一行)。
- [ ] 插件以卡片网格呈现,窗口变宽时每行卡片数增加(2–3 列自适应)。
- [ ] 每张卡片左上有彩色色块,内含插件名首字母;名称、`v版本`、分类徽章齐全。
- [ ] 搜索框输入关键字即时过滤;清空后恢复全部。
- [ ] 分类下拉选某分类只显示该类;选「全部」恢复。
- [ ] 搜索 + 分类同时生效;无结果显示「未找到匹配的插件」。
- [ ] 已安装插件的按钮显示「✓ 已安装」(禁用/绿);商店版本更高显示「↑ 更新」(琥珀);未安装显示「安装」(蓝)。
- [ ] 点「安装」可正常下载安装,成功后该卡片按钮变为「✓ 已安装」,并弹出成功提示。
- [ ] 底部状态栏显示「共 N 个插件 · 已安装 M」。
- [ ] 切换中英文(若应用支持运行时切换则重进商店),分类名/按钮文案随语言变化。

- [ ] **Step 3: 回归检查**

- [ ] 「本地安装」标签页功能不变(拖拽/选择 JAR 安装仍可用)。
- [ ] 商店不可达时显示错误状态,不崩溃。

> 本任务无代码改动,不单独 commit。若手动验证暴露问题,回到对应 Task 修复并提交。

---

## Self-Review(规划者已核对)

- **Spec 覆盖:**
  - §2 整体布局 → Task 6(工具栏 + ScrollPane/FlowPane)。
  - §3 卡片设计 → Task 6(`buildPluginCard` / `buildIconTile`)。
  - §4 安装状态本地计算 → Task 3(`installState`/`compareVersion`)+ Task 6(`applyInstallState`)+ Task 7(传入已安装 Map)。
  - §5 搜索+筛选 → Task 3(`matches`)+ Task 6(`applyFilters` + 监听器)。
  - §6 CSS 归位 → Task 5(`.store-*`),图标渐变色在 Java 注入(Task 6 `buildIconTile`)。
  - §7 i18n → Task 4(messages + messages_zh)。
  - §8 受影响文件 → 全部任务覆盖;StorePlugin 抽取 = Task 2。
  - §9 非目标:计划未引入作者/下载量/截图/缓存/排序。
- **Placeholder 扫描:** 无 TBD/TODO;每个代码步骤含完整代码。
- **类型一致性:** `StorePluginLogic.InstallState`(NOT_INSTALLED/INSTALLED/UPDATABLE)、`compareVersion`、`installState`、`matches` 在 Task 3 定义,Task 6 按相同签名调用;`OnlineStorePane(Runnable, Map<String,String>)` 在 Task 6 定义,Task 7 按此调用;`PluginStoreUi.build(ObservableList<SwissKitJPlugin>)` 在 Task 7 定义并被 `MainWindow` 调用。
- **已知非阻塞项:** `categoryBox` 复用 `glass-combo` 样式类(在 `zhiflow-common.css` 中存在);若不存在仅影响外观,不影响编译/功能。
