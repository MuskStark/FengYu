# Getting Started

## 环境要求

- **JDK 21** — 项目使用 Java 21 的模块系统特性
- **Maven 3.9+** — 构建工具
- **JavaFX 21** — 由宿主在运行时提供（`provided` scope）

## 创建插件项目

最简单的方式是复制 [Project Scaffold](./scaffold.md) 中的完整结构，然后替换占位符。

### 必需信息

在开始之前，准备好以下信息：

| 信息 | 示例 | 说明 |
|------|------|------|
| Plugin name | `StarReport` | Maven artifactId 和项目名 |
| Plugin ID | `plugin.zhiflow.star` | 宿主的唯一标识符 |
| Base package | `fan.zhiflowj.plugin.star` | Java 包根路径 |
| Short description | `Star Report Generator` | 宿主 UI 中显示 |
| Icon | `&#9733;` | 单字符或 emoji |
| Category | `OTHER` | `text / image / net / dev / other` |

### 可选组件

根据实际需求选择性地添加：

| 组件 | 何时需要 |
|------|----------|
| H2 + MyBatis | 需要本地持久化存储 |
| FesodSheet | 需要读取或写入 Excel 文件 |
| Background Worker | 需要处理文件上传等异步任务 |

## 项目创建步骤

1. 创建 Maven 项目目录结构
2. 编写 `pom.xml`（参考 [Scaffold](./scaffold.md)）
3. 实现 `SwissKitJPlugin` 入口类（参考 [Entry Point](./entry-point.md)）
4. 编写 UI（参考 [UI](./ui.md)）
5. 配置 SPI 文件（参考 [SPI](./spi.md)）
6. 配置 i18n（参考 [i18n](./i18n.md)）
7. 如需数据库，添加 [Database](./database.md) 层
8. 如需 Excel I/O，添加 [Excel](./excel.md) + [Background Tasks](./background-tasks.md)
9. 开发测试（`mvn javafx:run -Pdev`）
10. 生产打包（`mvn clean package`）

## 下一步

- 完整项目结构 → [Project Scaffold](./scaffold.md)
- 插件核心接口 → [Entry Point](./entry-point.md)