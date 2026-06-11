# 插件资源隔离（Child-First 资源查找）— 设计文档

- 日期：2026-06-08
- 模块：`SwissKit`（宿主程序）
- 范围：核心修复，不改动 `SwissKitJ-Api`，不改动 `I18n`

## 1. 背景与现象

StarReport 插件在宿主中运行时，MyBatis 的 6 个 Mapper 全部 `Mapper NOT registered`，
插件自身的数据表（`STAR_VISIT_RAW_DATA` 等）也未创建；但用 `mvn javafx:run -Pdev`
独立运行时一切正常。

## 2. 根本原因（已定位并复现）

`PluginLoader.loadJar()`（`SwissKit/src/main/java/fan/summer/plugin/PluginLoader.java:287`）
使用父优先（parent-first）的标准 `URLClassLoader`：

```java
URLClassLoader cl = new URLClassLoader(
    new URL[]{jar.toUri().toURL()},
    getClass().getClassLoader());   // parent = 宿主 App ClassLoader
```

宿主 `SwissKit/target/classes` 中存在与插件**同名的根级类路径资源**：
`mybatis-config.xml`、`init.sql`、`mapper/` 目录。父优先委派导致插件调用
`getResourceAsStream("mybatis-config.xml")` 时返回的是**宿主**的同名文件，而非插件
JAR 内的文件。于是插件用宿主 config 构建 `SqlSessionFactory`（注册的是宿主 mapper），
用宿主 `init.sql` 建表，插件自身的 mapper 与表全部缺失。

> 关键点：这是**资源名冲突**，与类加载、TCCL 设置无关。仅按类名打破父委派的
> `IsolatedPluginClassLoader` 也无法解决，因为它不改变 `getResource*` 的委派行为。

## 3. 方案（需求文档方案 A）

为插件 ClassLoader 提供 **child-first 的资源**查找：`getResource` /
`getResourceAsStream` / `getResources` 优先从插件 JAR 自身查找，找不到再委派父级。
**类加载保持父优先不变。**

### 为什么只让资源 child-first，类仍父优先

`SwissKitJPlugin`、JavaFX API 等共享类型必须解析为宿主已加载的**同一个** `Class`
对象，否则 `ServiceLoader`、强制类型转换、`instanceof` 全部失效。本 Bug 纯粹是资源名
冲突，因此**只有资源需要 child-first**，不重写 `loadClass`。

## 4. 组件

### 4.1 新增类 `fan.summer.plugin.ChildFirstResourceClassLoader`

位于 `SwissKit` 模块，与 `PluginLoader` 同包。`extends URLClassLoader`，只重写资源方法：

- `getResource(String name)`：先 `findResource(name)`（仅查本 JAR 的 URL）；为 null
  时再 `super.getResource(name)`（父级 + 自身）。
- `getResourceAsStream(String name)`：基于 child-first 的 `getResource(name)` 打开流；
  `getResource` 返回 null（插件与宿主都没有）时返回 null；打开流抛 `IOException`（如插件
  JAR 条目损坏）时记一条 `log.warn` 并返回 null。**此处刻意不回退到宿主的同名副本**——
  否则插件资源损坏时会悄悄改用宿主的 `mybatis-config.xml` 等，正好重新引入本特性要根治的
  跨污染。让插件「响亮地失败」并留下告警，比静默回退更安全，也与 JDK
  `ClassLoader.getResourceAsStream` 在 `IOException` 时返回 null 的契约一致。
- `getResources(String name)`：先枚举 `findResources(name)`（本 JAR），再追加父级
  `getParent().getResources(name)`，合并为一个 `Enumeration<URL>`；本 JAR 的资源排在前面。
- 不重写 `loadClass` / `findClass`：类加载行为与今天完全一致（父优先）。

### 4.2 修改 `PluginLoader.loadJar()`

将：

```java
URLClassLoader cl = new URLClassLoader(urls, getClass().getClassLoader());
```

替换为：

```java
URLClassLoader cl = new ChildFirstResourceClassLoader(urls, getClass().getClassLoader());
```

下游（`openLoaders` 的 `Map<Path, URLClassLoader>`、`ServiceLoader.load`、`cl.close()`、
`I18n.registerPluginBundle`、`PluginContext.register`）均按 `URLClassLoader` / `ClassLoader`
操作，因此**无其它调用点需要改动**。

## 5. 错误处理与边界

- 插件 JAR **不含**该资源时，行为与今天一致（回退父级）——对不自带
  `mybatis-config.xml` 等资源的插件完全向后兼容。
- 常见的单结果查找（`getResourceAsStream`）中，插件资源会遮蔽宿主同名资源，正是所需。
- `getResources` 合并枚举时保证本 JAR 资源排在父级之前；不做去重（不同 URL 即不同来源）。

## 6. 不改动 I18n 的原因

`I18n.loadBundleFromUrlClassLoader`（`SwissKitJ-Api/.../i18n/I18n.java:311`）已用
`findResource` 规避父委派，本身已正确。新 loader 下其 `getResource` 路径同样安全，但
保留现有代码可把改动面降到最小、零回归风险。需求文档第 4 节列出的 i18n 风险，
在本方案下也由 child-first 资源查找一并根治（即便 I18n 未来移除 workaround 也安全）。

## 7. 测试

本仓库**无系统 Maven、无测试运行器，且各处构建均 `-DskipTests`**，但 `javac`/`java`（JDK 21）
可直接使用。为符合 YAGNI 且在本环境真正可执行，**不引入 JUnit 依赖**，验证手段为：

1. **JDK-only 一次性验证程序**（`javac`+`java` 运行，不入库）：构造两个临时目录，二者含同名资源
   （`mybatis-config.xml`/`init.sql`/`dup.txt`），父 loader 指向「宿主」目录、子 loader 指向
   「插件」目录，断言 `getResource`/`getResourceAsStream`/`getResources` 返回**子（插件）**副本，
   且仅存在于宿主的资源仍能父级回退。打印 `ALL PASS`。
2. IDEA `build_project` 确认实现类与接入点在项目内编译通过。

## 8. 影响面小结

| 文件 | 改动 |
|---|---|
| `SwissKit/.../plugin/ChildFirstResourceClassLoader.java` | 新增 |
| `SwissKit/.../plugin/PluginLoader.java` | 第 287-290 行替换构造器 |
| `/tmp/cfrcl-verify/CfrclVerify.java` | 一次性 JDK-only 验证（不入库）|
