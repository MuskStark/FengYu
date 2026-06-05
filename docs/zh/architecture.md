# 架构说明

SwissKitJ 基于 JavaFX 21 构建，采用模块化、插件化的架构。

## 模块结构

| 模块 | 用途 |
|------|------|
| `SwissKitJ-Api` | 共享插件接口（`SwissKitJPlugin`）、可复用组件（`StepWizard`）、主题、日志 API |
| `SwissKit` | JavaFX 应用壳 — UI、插件加载、内置工具 |

官方插件位于[单独的仓库](https://github.com/MuskStark/SwissKiJ-Plugin)。它们独立构建，在运行时作为 JAR 放入 `plugins/` 目录。所有插件将 `SwissKitJ-Api` 声明为 `provided` 依赖。主应用通过胖 JAR 在运行时提供它。

## 启动序列

`fan.summer.Launcher`（胖 JAR 清单入口点）→ `fan.summer.app.SwissKitJApp`（JavaFX `Application`）。

在 `SwissKitJApp.start()` 中：

1. 解析 `plugins/` 目录（生产环境为 JAR 同级目录，开发环境为 `./plugins/`）
2. 创建 `PluginLoader` + `PluginRegistry`
3. 通过 `BuiltinToolRegistrar` 注册内置工具
4. 创建 `FavoriteService`（从数据库加载收藏）
5. 构建并显示 `MainWindow`
6. 如已配置则初始化远程 AI 后端（OpenAI/Anthropic）；本地后端延迟到首次打开 AI 工具时初始化
7. 挂载 `WindowResizeHelper` 实现边缘/角落拖拽缩放
8. 启动 `PluginLoader`（扫描 `plugins/` 目录并监听变化）

## UI 结构

| 组件 | 职责 |
|------|------|
| `MainWindow` | 根 `StackPane`；拥有 `TitleBar`、`Sidebar`、`ContentArea`、状态栏 |
| `Sidebar` | 基于分类的导航，带搜索栏；分类：全部/文本/图片/开发者/网络/其他/收藏 |
| `ContentArea` | 显示 `ToolCard` 网格或活动工具视图；管理 `DetailPanel` 和返回栏 |
| `DetailPanel` | 滑入面板，显示插件元数据，带启动、卸载（仅外部插件）和收藏切换按钮 |
| `TitleBar` | 自定义窗口装饰（窗口为 `StageStyle.TRANSPARENT`） |

### 导航流程

点击 `ToolCard` → `DetailPanel.show()` → 点击启动 → `registry.activate(plugin)` + `contentArea.showPage(plugin.createView(), title)`。

返回时返回栏调用 `registry.deactivate()`。

## 插件系统

### 接口

```java
public interface SwissKitJPlugin {
    String getId();          // 反向域名 ID
    String getName();
    String getDescription();
    ToolCategory getCategory();
    String getVersion();
    String getIconText();    // 表情符号或单个字符
    default IconStyle getIconStyle() { return IconStyle.BLUE; }
    default PluginType getType()    { return PluginType.PLUGIN; }

    Node createView();       // 调用一次；结果会被缓存
    default void onActivate()   {}
    default void onDeactivate() {}
    default void onUnload()     {}
}
```

### 注册方式

- **内置工具**：通过 `BuiltinToolRegistrar` 直接注册——无需 SPI。
- **外部插件**：实现 `SwissKitJPlugin`，在 `META-INF/services/fan.summer.api.SwissKitJPlugin` 中声明，将 JAR 放入 `plugins/`。通过文件监听器热重载。

### 插件日志

插件使用 `fan.summer.api.log.LoggerFactory`，在主机运行时路由到 SLF4J/Logback，在测试中返回静默的空操作日志器。

## CSS 主题

三层玻璃态深色主题：

| 文件 | 模块 | 作用域 |
|------|------|--------|
| `css/swisskit-common.css` | `SwissKitJ-Api` | 共享变量、滚动条、`.glass-*` 工具类 |
| `css/shell.css` | `SwissKit` | 应用外壳 — 标题栏、侧边栏、卡片、面板 |
| `css/builtin.css` | `SwissKit` | 内置工具样式 |

嵌入主 Scene 的插件自动继承所有样式表。有独立 `Stage`/`Scene` 的插件应调用 `Themes.applyTo(scene)`。

## 数据库

H2 文件数据库位于工作目录下的 `.swisskit/swisskit.db`。Schema 从 `init.sql` 初始化。通过 MyBatis 访问，XML mapper 位于 `src/main/resources/mapper/`。

## 构建

```bash
mvn install -f SwissKitJ-Api/pom.xml -DskipTests
mvn clean package -f SwissKit/pom.xml -DskipTests
java -jar SwissKit/target/SwissKitJ-3.0.0-rc.2.jar
```

胖 JAR 通过 `maven-shade-plugin` 构建，捆绑所有平台的 JavaFX 原生库（`.dll`、`.so`、`.dylib`）。