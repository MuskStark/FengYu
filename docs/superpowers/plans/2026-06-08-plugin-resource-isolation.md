# 插件资源隔离（Child-First 资源查找）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让插件 ClassLoader 的资源查找（`getResource`/`getResourceAsStream`/`getResources`）优先命中插件 JAR 自身，根治插件与宿主同名资源（`mybatis-config.xml`、`init.sql`、`mapper/`、`i18n/*`）的父优先委派冲突。

**Architecture:** 新增 `ChildFirstResourceClassLoader extends URLClassLoader`，**只**重写三个资源方法为 child-first，**不**重写 `loadClass`（类加载保持父优先，保证 `SwissKitJPlugin` 等共享类型仍是宿主同一个 `Class` 对象）。`PluginLoader.loadJar()` 用它替换原生 `URLClassLoader`，下游无需改动。

**Tech Stack:** Java 21，`java.net.URLClassLoader`。

> **构建与验证约束（来自 CLAUDE.md）：** 本仓库**无系统 Maven**，编译一律走 IntelliJ 内置 Maven（Maven 工具窗口）或 IDEA MCP 工具（`mcp__idea__build_project`）。**切勿在普通 shell 跑 `mvn`，会失败。** 但 `javac` / `java`（JDK 21，`/usr/bin`）可直接使用。本项目当前**没有任何单元测试，也未配置测试运行器，且各处构建均 `-DskipTests`**——因此本计划**不引入 JUnit 依赖**，改用一个**仅依赖 JDK 的一次性验证程序**（`javac`+`java` 运行）来取得真实的行为证据；这是符合 YAGNI 且在本环境可实际执行的方式。

---

## File Structure

| 文件 | 责任 | 改动 |
|---|---|---|
| `SwissKit/src/main/java/fan/summer/plugin/ChildFirstResourceClassLoader.java` | child-first 资源查找的 ClassLoader | 新增 |
| `SwissKit/src/main/java/fan/summer/plugin/PluginLoader.java` | 插件 JAR 加载；第 287-290 行构造器替换 | 修改 |
| `/tmp/cfrcl-verify/`（一次性，不提交） | JDK-only 行为验证程序 | 临时 |

---

## Task 1: 实现 `ChildFirstResourceClassLoader`（含 JDK-only 行为验证）

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/plugin/ChildFirstResourceClassLoader.java`
- Temp (不提交): `/tmp/cfrcl-verify/CfrclVerify.java`

- [ ] **Step 1: 先写一次性验证程序（应先失败：loader 尚不存在）**

创建 `/tmp/cfrcl-verify/CfrclVerify.java`（**不提交**，仅用于在本环境取得真实证据）：

```java
import fan.summer.plugin.ChildFirstResourceClassLoader;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * JDK-only behavioral verification for ChildFirstResourceClassLoader.
 * Creates a fake "host" dir and a fake "plugin" dir that share resource names,
 * then asserts child-first resolution and parent fallback. Prints PASS/FAIL.
 * Run with plain javac/java — no JUnit, no Maven.
 */
public class CfrclVerify {
    static int failures = 0;

    static void check(String label, Object actual, Object expected) {
        boolean ok = (expected == null) ? actual == null : expected.equals(actual);
        System.out.println((ok ? "PASS" : "FAIL") + " : " + label
                + " (expected=" + expected + ", actual=" + actual + ")");
        if (!ok) failures++;
    }

    static ChildFirstResourceClassLoader newLoader(Path pluginDir, Path hostDir) throws Exception {
        // parent loads from the "host" dir; null grandparent avoids leaking the real classpath
        URLClassLoader parent = new URLClassLoader(new URL[]{hostDir.toUri().toURL()}, null);
        return new ChildFirstResourceClassLoader(new URL[]{pluginDir.toUri().toURL()}, parent);
    }

    public static void main(String[] args) throws Exception {
        Path host = Files.createTempDirectory("cfrcl-host");
        Path plugin = Files.createTempDirectory("cfrcl-plugin");

        // Shared names present in BOTH host and plugin
        Files.writeString(host.resolve("mybatis-config.xml"), "HOST");
        Files.writeString(plugin.resolve("mybatis-config.xml"), "PLUGIN");
        Files.writeString(host.resolve("init.sql"), "HOST");
        Files.writeString(plugin.resolve("init.sql"), "PLUGIN");
        Files.writeString(host.resolve("dup.txt"), "HOST");
        Files.writeString(plugin.resolve("dup.txt"), "PLUGIN");
        // Present ONLY in host
        Files.writeString(host.resolve("host-only.txt"), "HOST");

        try (ChildFirstResourceClassLoader cl = newLoader(plugin, host)) {
            // 1) getResource prefers the plugin copy
            URL r = cl.getResource("mybatis-config.xml");
            check("getResource child-first", r == null ? null : Files.readString(Path.of(r.toURI())), "PLUGIN");

            // 2) getResourceAsStream prefers the plugin copy
            try (var is = cl.getResourceAsStream("init.sql")) {
                check("getResourceAsStream child-first", is == null ? null : new String(is.readAllBytes()), "PLUGIN");
            }

            // 3) falls back to host when plugin lacks the resource
            URL h = cl.getResource("host-only.txt");
            check("getResource parent fallback", h == null ? null : Files.readString(Path.of(h.toURI())), "HOST");

            // 4) getResources lists the plugin copy first, host still present
            Enumeration<URL> e = cl.getResources("dup.txt");
            List<String> contents = new ArrayList<>();
            while (e.hasMoreElements()) contents.add(Files.readString(Path.of(e.nextElement().toURI())));
            check("getResources plugin-first", contents.isEmpty() ? null : contents.get(0), "PLUGIN");
            check("getResources host present", contents.contains("HOST"), Boolean.TRUE);
        }

        System.out.println(failures == 0 ? "ALL PASS" : (failures + " FAILURE(S)"));
        if (failures > 0) System.exit(1);
    }
}
```

- [ ] **Step 2: 运行验证，确认此刻失败（loader 不存在 → 编译错误）**

```bash
cd /tmp/cfrcl-verify && javac -d out CfrclVerify.java
```
Expected: 编译失败，`package fan.summer.plugin does not exist` / `cannot find symbol ChildFirstResourceClassLoader`。（这是 TDD 的红灯。）

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
 * {@code fan.summer.zhiflow.api.SwissKitJPlugin} must resolve to the same {@code Class} objects
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

- [ ] **Step 4: 编译实现 + 验证程序，运行验证，确认全部 PASS**

```bash
# 1) 编译 loader（仅依赖 JDK）到临时输出目录
javac -d /tmp/cfrcl-verify/out \
  SwissKit/src/main/java/fan/summer/plugin/ChildFirstResourceClassLoader.java
# 2) 把验证程序编译进同一输出目录（此时能解析到 loader）
javac -cp /tmp/cfrcl-verify/out -d /tmp/cfrcl-verify/out /tmp/cfrcl-verify/CfrclVerify.java
# 3) 运行
java -cp /tmp/cfrcl-verify/out CfrclVerify
```
Expected（stdout）：5 行 `PASS : ...`，最后一行 `ALL PASS`，退出码 0。

- [ ] **Step 5: 用 IDEA 构建确认实现类编入项目（项目级编译验证）**

通过 IDEA MCP `mcp__idea__build_project` 构建（或 Maven 工具窗口）。
Expected: 新增类编译通过，无错误。

- [ ] **Step 6: Commit（仅提交实现类，验证程序在 /tmp 不入库）**

```bash
git add SwissKit/src/main/java/fan/summer/plugin/ChildFirstResourceClassLoader.java
git commit -m "✨ feat: add ChildFirstResourceClassLoader for plugin resource isolation"
```

---

## Task 2: 在 `PluginLoader.loadJar()` 接入新 loader

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
> 全部无需改动。两类同包（`fan.summer.plugin`），无需 import。

- [ ] **Step 2: 用 IDEA 构建宿主，确认编译通过**

通过 IDEA MCP `mcp__idea__build_project`（或 Maven 工具窗口）构建 `SwissKit`。
Expected: BUILD 成功，无编译错误。

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/plugin/PluginLoader.java
git commit -m "♻️ refactor: load plugins via ChildFirstResourceClassLoader"
```

---

## Task 3:（可选）真实插件端到端确认

**Files:** 无（仅运行验证）

- [ ] **Step 1: 用自带根级 `mybatis-config.xml` 的插件验证**

将一个自带根级 `mybatis-config.xml` 的插件 JAR 放入 `.swisskit/plugin/`，经 IDEA 运行配置启动
`SwissKitJApp`（`mcp__idea__execute_run_configuration`）。
Expected（日志）：插件 mapper 正常注册、插件自有表创建成功，不再出现 `Mapper NOT registered`。
若手头没有这样的插件，可跳过——Task 1 的 JDK-only 验证已覆盖 loader 核心行为，Task 2 已确认接入点编译通过。

---

## Self-Review 结果

- **Spec coverage：** 方案 A（child-first 资源、类父优先）→ Task 1 实现 + Task 2 接入；
  「不改 I18n」→ 计划未触碰 I18n（符合 spec 第 6 节）；测试章节 → Task 1 的 JDK-only 验证程序，
  覆盖 child-first（3 个资源方法）与父级回退。全部覆盖。
- **Placeholder scan：** 无 TBD/TODO；每个改代码的 step 均含完整代码与可执行命令。
- **Type consistency：** 全程类名 `ChildFirstResourceClassLoader`，方法
  `getResource`/`getResourceAsStream`/`getResources`/`findResource`/`findResources` 一致；
  局部变量类型 `URLClassLoader` 与 spec「下游无改动」一致。
- **测试可执行性：** 验证程序仅依赖 JDK（`javac`/`java`），不引入 JUnit、不触发 `mvn`，
  与本仓库「无系统 Maven、无测试运行器、构建 skipTests」的现状一致。
