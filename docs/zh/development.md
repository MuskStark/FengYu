# 开发指南

## 前置要求

- **JDK 21+**
- **Maven 3.8+**
- **IntelliJ IDEA**（推荐）
- **Git**

## 设置

```bash
git clone https://github.com/MuskStark/SwissKitJ.git
cd SwissKitJ

mvn install -f SwissKitJ-Api/pom.xml -DskipTests
mvn clean package -f SwissKit/pom.xml -DskipTests
```

## 项目结构

```
SwissKitJ/
├── SwissKitJ-Api/                        # 共享 API 模块
│   └── src/main/java/fan/summer/api/
│       ├── SwissKitJPlugin.java          # 插件接口
│       ├── PluginContext.java            # 插件隔离的 TCCL 切换
│       ├── ToolCategory.java             # 分类枚举
│       ├── IconStyle.java                # 图标样式枚举
│       ├── ToolType.java                 # 类型枚举（BUILTIN / PLUGIN）
│       ├── component/
│       │   └── StepWizard.java           # 多步骤向导
│       ├── log/
│       │   ├── LoggerFactory.java
│       │   └── PluginLogger.java
│       └── theme/
│           └── Themes.java
├── SwissKit/                             # 主 JavaFX 应用
│   └── src/main/java/fan/summer/
│       ├── Launcher.java                 # 入口点
│       ├── app/SwissKitJApp.java         # JavaFX Application
│       ├── buildintool/                  # 内置工具
│       ├── plugin/                       # 插件加载
│       └── ui/                           # 应用壳 UI
├── backup/                               # 遗留 Swing 代码（排除在构建外）
├── docs/                                 # 文档
└── pom.xml                               # 根聚合器
```

### 模块依赖

| 模块 | 依赖 | 作用域 |
|------|------|--------|
| `SwissKitJ-Api` | JavaFX | compile |
| `SwissKit` | `SwissKitJ-Api` | compile |
| 外部插件 | `SwissKitJ-Api` | provided |

## 插件开发

### 创建外部插件

**1. Maven 配置**

```xml
<dependencies>
    <dependency>
        <groupId>fan.summer.api</groupId>
        <artifactId>SwissKitJ-Api</artifactId>
        <version>3.0.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

**2. 实现接口**

```java
package plugin.example.mytool;

import fan.summer.api.*;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public class MyToolPlugin implements SwissKitJPlugin {

    @Override public String getId()          { return "com.example.my-tool"; }
    @Override public String getName()        { return "My Tool"; }
    @Override public String getDescription() { return "Does something useful"; }
    @Override public ToolCategory getCategory() { return ToolCategory.DEV; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()     { return "wrench"; }
    @Override public IconStyle getIconStyle(){ return IconStyle.TEAL; }

    @Override
    public Node createView() {
        VBox root = new VBox(16);
        root.getChildren().add(new Label("My Tool"));
        return root;
    }
}
```

**3. 通过 SPI 注册**

创建 `META-INF/services/fan.summer.api.SwissKitJPlugin`：

```
plugin.example.mytool.MyToolPlugin
```

**4. 部署**

打包为胖 JAR 放入主应用的 `plugins/` 目录。支持热重载。

### 生命周期方法

| 方法 | 调用时机 | 典型用途 |
|------|----------|----------|
| `createView()` | 首次启动 | 构建并返回 UI 节点。调用一次，被缓存。 |
| `onActivate()` | 工具进入前台 | 恢复计时器、刷新数据 |
| `onDeactivate()` | 工具移至后台 | 暂停计时器、持久化状态 |
| `onUnload()` | 插件正在卸载 | 释放线程、文件句柄 |

### 导航流程

1. 用户点击 `ToolCard` → `DetailPanel` 滑入
2. 用户点击启动 → `registry.activate(plugin)` → `contentArea.showPage(plugin.createView())`
3. 返回栏 → `registry.deactivate()`

### 插件资源隔离

外部插件通过子优先 `ClassLoader` 加载并注册到 `PluginContext`。主机自动处理 TCCL 切换——插件作者无需了解任何 `ClassLoader` 知识。对于打开自己 `Stage`/`Scene` 的插件，只要代码在生命周期方法或插件视图节点的事件处理器中运行，通过 `ServiceLoader` 或资源包进行的资源查找就能正常工作。

## 内置工具

内置工具跳过 SPI。在 `BuiltinToolRegistrar` 中注册：

```java
List<SwissKitJPlugin> builtins = List.of(
    new AiChatPlugin(),
    new JsonFormatterPlugin(),
    // ...
    new MyBuiltinPlugin()   // 在此添加
);
```

设置 `getType()` 返回 `PluginType.BUILTIN`。

## UI 组件

### StepWizard

```java
StepWizard wizard = new StepWizard();
wizard.addStep("Select file",  fileSelectPane, () -> filePath != null);
wizard.addStep("Split mode",   modePane,       () -> modeSelected);
wizard.addStep("Output",       outputPane,     () -> outputDir != null);
wizard.build();

wizard.setOnStepChanged((from, to, total) -> {
    if (from == 0 && to == 1) startAnalysis();
});
```

- 带完成/活动/空闲状态的点导航
- 动画滑入过渡
- `canProceed` supplier 通过抖动动画阻止前进
- 最后一步将"下一步"按钮变为"完成"

### 布局

使用 JavaFX 布局：`VBox`、`HBox`、`GridPane`、`BorderPane`、`StackPane`、`ScrollPane`。

### 主题

三层 CSS：

| 文件 | 作用域 |
|------|--------|
| `swisskit-common.css` | 共享变量、`.glass-*` 工具类、`.section-title` |
| `shell.css` | 应用外壳 — 标题栏、侧边栏、卡片、面板 |
| `builtin.css` | 内置工具样式 |

主 Scene 中的插件自动继承所有样式。独立窗口需要：

```java
Themes.applyTo(scene);
```

可用类：`.glass-dialog`、`.glass-field`、`.glass-combo`、`.glass-table`、`.glass-checkbox`、`.glass-btn-primary`、`.glass-btn-secondary`、`.glass-tab-pane`、`.section-title`。

## 日志

```java
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

private static final PluginLogger log = LoggerFactory.getLogger(MyPlugin.class);

log.info("Processing file: {}", file);
log.error("Failed: {}", file, exception);
```

后端：SLF4J + Logback。控制台 INFO+ 级，滚动文件 DEBUG+ 级，位于 `.swisskit/logs/`。测试中安全使用空操作日志器。

## 后台处理

使用 JavaFX `Task` 进行长时运行操作：

```java
Task<Void> task = new Task<>() {
    @Override
    protected Void call() throws Exception {
        updateMessage("Processing...");
        updateProgress(current, total);
        return null;
    }
};
progressBar.progressProperty().bind(task.progressProperty());
new Thread(task).start();
```

始终使用 `Platform.runLater()` 从后台线程更新 UI。

## 贡献

### 分支命名

- `feature/` — 新功能
- `bugfix/` — 错误修复
- `docs/` — 文档
- `refactor/` — 重构

### 提交规范

使用带表情符号的约定式提交：

| 前缀 | 表情符号 | 用途 |
|------|----------|------|
| `✨ feat:` | `:sparkles:` | 新功能 |
| `🐛 fix:` | `:bug:` | 错误修复 |
| `♻️ refactor:` | `:recycle:` | 重构 |
| `📝 docs:` | `:memo:` | 文档 |
| `⬆️ deps:` | `:arrow_up:` | 依赖升级 |

### Pull Request

1. Fork 并创建功能分支
2. 构建：`mvn clean package -f SwissKit/pom.xml -DskipTests`
3. 用约定式提交格式提交
4. 推送到 PR 并针对 `main` 打开

### 报告问题

请包含：操作系统、Java 版本、SwissKitJ 版本、重现步骤、预期与实际行为。