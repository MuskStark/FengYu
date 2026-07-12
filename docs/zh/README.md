# FengYu 文档

![FengYu](https://img.shields.io/badge/FengYu-Desktop%20Toolbox-blue) ![Java](https://img.shields.io/badge/Java-21-orange) ![License](https://img.shields.io/badge/License-MIT-green)

**FengYu** 是一款基于 JavaFX 21 构建的模块化桌面工具箱，为生产力工具提供了一个简洁、可扩展的平台。

## 快速链接

- [快速开始](/zh/getting-started.md) — 安装与配置
- [功能特性](/zh/features.md) — 内置工具与能力
- [架构设计](/zh/architecture.md) — 插件系统与设计
- [开发指南](/zh/development.md) — 构建插件与贡献代码
- [更新日志](/zh/changelog.md) — 版本历史

## 什么是 FengYu？

一款模块化桌面工具箱，可以：

- 与本地 AI 模型对话（GGUF 格式）
- 处理和拆分 Excel 文件
- 发送和管理邮件
- 在不同格式间转换颜色
- 格式化和校验 JSON
- 编解码 Base64 与计算哈希
- 编辑 Markdown 并实时预览
- 通过自定义插件扩展功能

### 核心特性

- **JavaFX 21 界面** — 玻璃态深色主题与自定义窗口装饰
- **插件架构** — 通过 Java SPI 自动发现插件，支持热重载
- **AI 聊天** — 本地 LLM 推理，支持 GGUF 模型
- **跨平台** — Fat JAR 内置 Windows、macOS 与 Linux 的原生库
- **StepWizard** — 可复用的多步骤向导组件

## 系统要求

- **JDK 21 或更高版本**
- **Maven 3.8+**（如需从源码构建）

## 快速上手

```bash
# 从 GitHub Releases 下载后：
java -jar FengYu-3.1.0.jar
```

或从源码构建：

```bash
mvn install -f FengYu-Api/pom.xml -DskipTests
mvn clean package -f FengYu/pom.xml -DskipTests
java -jar FengYu/target/FengYu-3.1.0.jar
```

## 许可证

MIT License
