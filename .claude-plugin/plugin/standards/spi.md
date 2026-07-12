# SPI Registration

## Java ServiceLoader 机制

FengYu 插件通过 Java SPI（Service Provider Interface）被发现和加载。宿主在启动时扫描所有 JAR 中的 `META-INF/services/fan.summer.fengyu.api.FengYuPlugin` 文件，用 `ServiceLoader` 加载其中列出的插件类。

## SPI 文件格式

文件路径：`src/main/resources/META-INF/services/fan.summer.fengyu.api.FengYuPlugin`

文件内容：插件入口类的**完全限定名**（每行一个）

```
fan.fengyuj.plugin.star.StarPlugin
```

## Shade 插件的 ServicesResourceTransformer

插件的 `pom.xml` 必须配置 shade 插件的 `ServicesResourceTransformer`：

```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
```

**作用**：合并所有依赖 JAR 中的 SPI 文件到最终的 fat JAR。没有此配置，SPI 文件会被覆盖，插件无法被发现。

## 常见错误

| 错误 | 原因 | 解决 |
|------|------|------|
| `ServiceLoader` 找不到插件 | SPI 文件在 `services/` 而非 `META-INF/services/` | 确认路径是 `META-INF/services/` |
| 部署后插件加载失败 | SPI 文件内容是旧的类名 | 确保内容是最新的完全限定类名 |
| `BindingException` | Mapper XML namespace 与 Java 接口不匹配 | 参考 [Database](./database.md) |

## 验证 SPI 配置

部署后，检查 fat JAR 中是否包含正确的 SPI 文件：

```bash
unzip -p target/plugin-name-1.0-SNAPSHOT.jar META-INF/services/fan.summer.fengyu.api.FengYuPlugin
```