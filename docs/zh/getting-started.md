# 快速上手

## 系统要求

- **JDK 21 或更高版本**
- **Maven 3.8 或更高版本**（从源码构建时需要）

SwissKitJ 将 JavaFX 捆绑在胖 JAR 中，支持所有平台——无需单独的 JavaFX SDK。

## 安装

### 方式一：下载预构建 JAR

从 [GitHub Releases](https://github.com/MuskStark/SwissKitJ/releases) 页面下载。

```bash
java -jar SwissKitJ-3.0.1.jar
```

胖 JAR 包含 macOS、Windows 和 Linux 的 JavaFX 原生库——无需额外设置。

### 方式二：从源码构建

```bash
git clone https://github.com/MuskStark/SwissKitJ.git
cd SwissKitJ

# 先安装 API 模块（必需）
mvn install -f SwissKitJ-Api/pom.xml -DskipTests

# 构建主应用
mvn clean package -f SwissKit/pom.xml -DskipTests

# 运行
java -jar SwissKit/target/SwissKitJ-3.0.1.jar
```

**构建顺序很重要**：SwissKitJ-Api 提供共享的插件接口和可复用的 UI 组件。它必须先安装到本地 Maven 仓库，主应用才能编译。

## 运行

### 胖 JAR

```bash
java -jar SwissKit/target/SwissKitJ-3.0.1.jar
```

### IDE（IntelliJ IDEA）

1. 打开项目
2. 在 `SwissKit/src/main/java/fan/summer/` 中找到 `Launcher.java`
3. 右键点击 → "Run 'Launcher.main()'"

`Launcher` 类是胖 JAR 清单的入口点。

## 初步使用

1. **主窗口** — 应用打开时使用透明窗口框架和自定义标题栏
2. **侧边栏** — 工具分类：全部、文本、图片、开发者、网络、其他
3. **工具卡片** — 点击工具卡片查看详情面板，然后点击**启动**
4. **插件商店** — 从在线目录浏览安装插件，或加载本地 JAR

## 故障排除

### 应用无法启动

- 确保已安装 JDK 21+
- 检查 `JAVA_HOME` 环境变量
- 确认运行的是胖 JAR（不是模块 JAR）

### UI 不渲染

- 从干净状态重新构建：`mvn clean package -f SwissKit/pom.xml -DskipTests`
- 确保运行的是捆绑了 JavaFX 的胖 JAR

### 找不到 API 模块

```bash
mvn install -f SwissKitJ-Api/pom.xml -DskipTests
```

## 下一步

- [探索功能](features.md)
- [架构说明](architecture.md)
- [开发指南](development.md)