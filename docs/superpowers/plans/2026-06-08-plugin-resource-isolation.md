# 插件资源隔离（Child-First 资源查找）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让插件 ClassLoader 的资源查找（`getResource`/`getResourceAsStream`/`getResources`）优先命中插件 JAR 自身，根治插件与宿主同名资源（`mybatis-config.xml`、`init.sql`、`mapper/`、`i18n/*`）的父优先委派冲突。

**Architecture:** 新增 `ChildFirstResourceClassLoader extends URLClassLoader`，**只**重写三个资源方法为 child-first，**不**重写 `loadClass`（类加载保持父优先，保证 `SwissKitJPlugin` 等共享类型仍是宿主同一个 `Class` 对象）。`PluginLoader.loadJar()` 用它替换原生 `URLClassLoader`，下游无需改动。

**Tech Stack:** Java 21, `java.net.URLClassLoader`, JUnit 5（新增 test 作用域依赖，供 IntelliJ 运行单测）。

> **构建提示（来自 CLAUDE.md）：** 本仓库无系统 Maven。编译与测试一律走 IntelliJ 内置 Maven（Maven 工具窗口）或 IDEA MCP 工具（`mcp__idea__build_project`、`mcp__idea__execute_run_configuration`）。**切勿在普通 shell 里跑 `mvn`，会失败。** 单元测试在 IntelliJ 中以 JUnit 运行配置直接运行。

---

## File Structure

| 文件 | 责任 | 改动 |
|---|---|---|
| `SwissKit/src/main/java/fan/summer/plugin/ChildFirstResourceClassLoader.java` | child-first 资源查找的 ClassLoader | 新增 |
| `SwissKit/src/main/java/fan/summer/plugin/PluginLoader.java` | 插件 JAR 加载；第 287 行构造器替换 | 修改 |
| `SwissKit/pom.xml` | 增加 JUnit 5 test 依赖 | 修改 |
| `SwissKit/src/test/java/fan/summer/plugin/ChildFirstResourceClassLoaderTest.java` | 验证子优先 / 父回退行为 | 新增（测试）|

---

## Task 1: 增加 JUnit 5 test 依赖

**Files:**
- Modify: `SwissKit/pom.xml:33`（`<dependencies>` 开标签之后）

- [ ] **Step 1: 在 `<dependencies>` 之后插入 JUnit 依赖**

打开 `SwissKit/pom.xml`，找到第 33 行的 `<dependencies>`，在它后面紧接着插入：

```xml
        <!-- JUnit 5: test scope only; run via IntelliJ JUnit run config -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>
```

插入后该区域应为：

```xml
    <dependencies>

        <!-- JUnit 5: test scope only; run via IntelliJ JUnit run config -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>

        <!-- SwissKitJ-Api: bundled in fat-JAR at runtime so plugins can load against it -->
        <dependency>
            <groupId>fan.summer.api</groupId>
            <artifactId>SwissKitJ-Api</artifactId>
            <version>${swisskit.api.version}</version>
        </dependency>
```

- [ ] **Step 2: 让 IntelliJ 重新导入 Maven，确认 junit-jupiter 进入测试类路径**

在 IDEA 中触发 Maven reload（或 `mcp__idea__build_project`）。
Expected: 无报错；`org.junit.jupiter.api.*` 可在 `src/test` 下被解析（下个 Task 写测试时验证）。

- [ ] **Step 3: Commit**

```bash
git add SwissKit/pom.xml
git commit -m "⬆️ chore: add JUnit 5 test dependency to SwissKit"
```

---

## Task 2: 用 TDD 实现 `ChildFirstResourceClassLoader`

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/plugin/ChildFirstResourceClassLoader.java`
- Test: `SwissKit/src/test/java/fan/summer/plugin/ChildFirstResourceClassLoaderTest.java`

- [ ] **Step 1: 写失败测试**

创建 `SwissKit/src/test/java/fan/summer/plugin/ChildFirstResourceClassLoaderTest.java`：

```java
package fan.summer.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies child-first resource lookup: a resource present in BOTH the plugin
 * "JAR" (the loader's own URL) and the parent loader resolves to the plugin's copy,
 * while a resource present ONLY in the parent still falls back to the parent.
 */
class ChildFirstResourceClassLoaderTest {

    /** Builds a ChildFirstResourceClassLoader whose own classpath is {@code pluginDir}
     *  and whose parent is a plain URLClassLoader over {@code hostDir}. */
    private ChildFirstResourceClassLoader newLoader(Path pluginDir, Path hostDir) throws IOException {
        // Use the system loader's parent (bootstrap) to avoid leaking the real test classpath.
        URLClassLoader parent = new URLClassLoader(new URL[]{hostDir.toUri().toURL()}, null);
        return new ChildFirstResourceClassLoader(new URL[]{pluginDir.toUri().toURL()}, parent);
    }

    @Test
    void getResource_prefersPluginCopyOverHost(@TempDir Path host, @TempDir Path plugin) throws Exception {
        Files.writeString(host.resolve("mybatis-config.xml"), "HOST");
        Files.writeString(plugin.resolve("mybatis-config.xml"), "PLUGIN");

        try (ChildFirstResourceClassLoader cl = newLoader(plugin, host)) {
            URL url = cl.getResource("mybatis-config.xml");
            assertNotNull(url, "resource should be found");
            assertEquals("PLUGIN", Files.readString(Path.of(url.toURI())),
                    "child-first loader must return the plugin's copy");
        }
    }

    @Test
    void getResourceAsStream_prefersPluginCopyOverHost(@TempDir Path host, @TempDir Path plugin) throws Exception {
        Files.writeString(host.resolve("init.sql"), "HOST");
        Files.writeString(plugin.resolve("init.sql"), "PLUGIN");

        try (ChildFirstResourceClassLoader cl = newLoader(plugin, host)) {
            try (var is = cl.getResourceAsStream("init.sql")) {
                assertNotNull(is, "stream should be found");
                assertEquals("PLUGIN", new String(is.readAllBytes()),
                        "child-first loader must stream the plugin's copy");
            }
        }
    }

    @Test
    void getResource_fallsBackToHostWhenPluginLacksIt(@TempDir Path host, @TempDir Path plugin) throws Exception {
        Files.writeString(host.resolve("host-only.txt"), "HOST");
        // plugin dir intentionally does not contain host-only.txt

        try (ChildFirstResourceClassLoader cl = newLoader(plugin, host)) {
            URL url = cl.getResource("host-only.txt");
            assertNotNull(url, "must fall back to parent when plugin lacks the resource");
            assertEquals("HOST", Files.readString(Path.of(url.toURI())));
        }
    }

    @Test
    void getResources_listsPluginCopyFirst(@TempDir Path host, @TempDir Path plugin) throws Exception {
        Files.writeString(host.resolve("dup.txt"), "HOST");
        Files.writeString(plugin.resolve("dup.txt"), "PLUGIN");

        try (ChildFirstResourceClassLoader cl = newLoader(plugin, host)) {
            Enumeration<URL> e = cl.getResources("dup.txt");
            List<String> contents = new ArrayList<>();
            while (e.hasMoreElements()) {
                contents.add(Files.readString(Path.of(e.nextElement().toURI())));
            }
            assertTrue(contents.size() >= 2, "should enumerate both plugin and host copies");
            assertEquals("PLUGIN", contents.get(0), "plugin copy must come first");
            assertTrue(contents.contains("HOST"), "host copy must still be present");
        }
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败 / 测试失败**

在 IntelliJ 中右键 `ChildFirstResourceClassLoaderTest` → Run。
Expected: 编译失败，`ChildFirstResourceClassLoader` 不存在（cannot find symbol）。

- [ ] **Step 3: 写最小实现**

创建 `SwissKit/src/main/java/fan/summer/plugin/ChildFirstResourceClassLoader.java`：

```java
package fan.summer.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * A {@link URLClassLoader} that performs <strong>child-first resource</strong> lookup
 * while keeping <strong>parent-first class</strong> loading.
 *
 * <p>The host application ships root-level classpath resources (e.g.
 * {@code mybatis-config.xml}, {@code init.sql}, {@code mapper/}) that may collide by
 * name with resources a plugin bundles inside its own JAR. The standard parent-first
 * {@link URLClassLoader#getResource(String)} would return the <em>host's</em> copy,
 * causing the plugin to build its MyBatis {@code SqlSessionFactory} and run its schema
 * from the wrong files. This loader resolves resources from the plugin JAR first and
 * only delegates to the parent when the plugin does not provide them.</p>
 *
 * <p><strong>Class loading is intentionally left parent-first</strong> (no override of
 * {@code loadClass}/{@code findClass}). Shared API types such as
 * {@code fan.summer.api.SwissKitJPlugin} must resolve to the same {@code Class} objects
 * the host loaded, otherwise {@link java.util.ServiceLoader}, casts, and
 * {@code instanceof} would break. The reported bug is purely a resource-name conflict,
 * so only resource resolution is made child-first.</p>
 *
 * @see PluginLoader
 */
public class ChildFirstResourceClassLoader extends URLClassLoader {

    /**
     * Creates a child-first-resource loader over the given URLs.
     *
     * @param urls   the plugin JAR URL(s) searched first for resources
     * @param parent the parent class loader (the host application class loader)
     */
    public ChildFirstResourceClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    /**
     * Returns the plugin JAR's own copy of {@code name} if present, otherwise delegates
     * to the standard parent-first lookup.
     */
    @Override
    public URL getResource(String name) {
        URL own = findResource(name);   // searches only this loader's URLs (the plugin JAR)
        if (own != null) {
            return own;
        }
        return super.getResource(name); // parent-first fallback
    }

    /**
     * Opens a stream to the resource resolved by {@link #getResource(String)} (child-first).
     */
    @Override
    public InputStream getResourceAsStream(String name) {
        URL url = getResource(name);
        if (url == null) {
            return null;
        }
        try {
            return url.openStream();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Enumerates the plugin JAR's own copies of {@code name} first, then the parent's,
     * so a plugin-bundled resource shadows an identically-named host resource.
     */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> ordered = new ArrayList<>();
        // Plugin JAR first
        Enumeration<URL> own = findResources(name);
        while (own.hasMoreElements()) {
            ordered.add(own.nextElement());
        }
        // Parent next
        ClassLoader parent = getParent();
        if (parent != null) {
            Enumeration<URL> fromParent = parent.getResources(name);
            while (fromParent.hasMoreElements()) {
                ordered.add(fromParent.nextElement());
            }
        }
        return Collections.enumeration(ordered);
    }
}
```

- [ ] **Step 4: 运行测试，确认全部通过**

在 IntelliJ 中重新运行 `ChildFirstResourceClassLoaderTest`。
Expected: 4 个测试全部 PASS。

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/plugin/ChildFirstResourceClassLoader.java \
        SwissKit/src/test/java/fan/summer/plugin/ChildFirstResourceClassLoaderTest.java
git commit -m "✨ feat: add ChildFirstResourceClassLoader for plugin resource isolation"
```

---

## Task 3: 在 `PluginLoader.loadJar()` 接入新 loader

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/plugin/PluginLoader.java:287-290`

- [ ] **Step 1: 替换构造器**

打开 `SwissKit/src/main/java/fan/summer/plugin/PluginLoader.java`，将 `loadJar` 中的：

```java
            URLClassLoader cl = new URLClassLoader(
                new java.net.URL[]{jar.toUri().toURL()},
                getClass().getClassLoader()
            );
```

替换为：

```java
            // Child-first RESOURCE lookup (parent-first class loading) so a plugin's
            // own mybatis-config.xml / init.sql / mapper/** / i18n shadow the host's
            // identically-named root resources. See ChildFirstResourceClassLoader.
            URLClassLoader cl = new ChildFirstResourceClassLoader(
                new java.net.URL[]{jar.toUri().toURL()},
                getClass().getClassLoader()
            );
```

> 注：局部变量类型保持 `URLClassLoader`（`ChildFirstResourceClassLoader` 是其子类），
> 因此 `openLoaders`（`Map<Path, URLClassLoader>`）、`ServiceLoader.load`、`cl.close()`、
> `cl.getResource(...)`、`I18n.registerPluginBundle(...)`、`PluginContext.register(...)`
> 全部无需改动。

- [ ] **Step 2: 构建宿主，确认编译通过**

通过 IDEA `mcp__idea__build_project`（或 Maven 工具窗口）构建 `SwissKit`。
Expected: BUILD 成功，无编译错误。

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/plugin/PluginLoader.java
git commit -m "♻️ refactor: load plugins via ChildFirstResourceClassLoader"
```

---

## Task 4: 端到端确认（手动验证，可选但推荐）

**Files:** 无（仅运行验证）

- [ ] **Step 1: 用真实插件验证资源隔离**

将一个自带根级 `mybatis-config.xml` 的插件 JAR（如 StarReport 的早期版本，或临时构造一个含
`mybatis-config.xml` 的插件 JAR）放入 `.swisskit/plugin/`，启动宿主：

通过 IDEA 运行配置启动 `SwissKitJApp`（或 `mcp__idea__execute_run_configuration`）。
Expected（日志中）：插件的 mapper 正常注册、插件自有表创建成功；不再出现
`Mapper NOT registered`。若手头没有这样的插件，可跳过本 Task —— Task 2 的单测已覆盖
loader 的核心行为，Task 3 已确认接入点编译通过。

- [ ] **Step 2:（如执行了 Step 1）记录验证结果**

在 PR / commit 描述中注明实测插件 mapper 注册情况。

---

## Self-Review 结果

- **Spec coverage：** 方案 A（child-first 资源、类父优先）→ Task 2 实现 + Task 3 接入；
  「不改 I18n」→ 计划未触碰 I18n，符合 spec 第 6 节；测试章节 → Task 1+2。全部覆盖。
- **Placeholder scan：** 无 TBD/TODO；每个改代码的 step 均含完整代码。
- **Type consistency：** 全程类名 `ChildFirstResourceClassLoader`、方法
  `getResource`/`getResourceAsStream`/`getResources`/`findResource`/`findResources` 一致；
  局部变量类型 `URLClassLoader` 与 spec「下游无改动」一致。
