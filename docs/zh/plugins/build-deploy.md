# Build & Deploy

## 开发模式

```bash
# 编译
mvn clean compile -Pdev

# 运行（启动 JavaFX 独立窗口）
mvn javafx:run -Pdev
```

## 生产打包

```bash
mvn clean package
```

输出：`target/<plugin-name>-1.0-SNAPSHOT.jar`

## 部署

将生成的 JAR 复制到宿主应用的 `plugins/` 目录：

```bash
cp target/my-plugin-1.0-SNAPSHOT.jar /path/to/ZhiFlow/plugins/
```

宿主启动时会自动扫描并加载插件。支持热加载——插件目录有变化时会自动重载。

## 验证 fat JAR

检查打包结果是否包含正确的 SPI 文件和合并的服务：

```bash
unzip -l target/my-plugin-1.0-SNAPSHOT.jar | grep -E "META-INF/services|ServicesResourceTransformer"
```

## Shade 插件关键配置

```xml
<configuration>
    <transformers>
        <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
    </transformers>
    <createDependencyReducedPom>false</createDependencyReducedPom>
</configuration>
```

`ServicesResourceTransformer` 确保依赖 JAR 中的 SPI 文件被合并进 fat JAR。没有它，SPI 文件会被覆盖，插件无法被发现。

## 依赖 scope 说明

| Scope | 含义 |
|-------|------|
| `provided` | ZhiFlow-Api 和 JavaFX 由宿主在运行时提供，插件不打包 |
| 无（默认） | 插件自己的依赖（如 H2、FesodSheet、MyBatis）会打包进 fat JAR |