# Project Scaffold

完整插件项目结构，所有文件均已包含，无占位符。

```
<plugin-name>/
├── pom.xml
├── src/main/java/<package-path>/
│   ├── <Name>Plugin.java          # SPI 入口
│   ├── <Name>DevApp.java          # JavaFX 独立运行 Application
│   ├── DevLauncher.java          # 模块系统绕过启动器
│   ├── database/
│   │   ├── DatabaseInit.java      # H2 + MyBatis 初始化
│   │   ├── entity/               # MyBatis 实体类
│   │   └── mapper/               # MyBatis Mapper 接口
│   ├── excel/
│   │   ├── dto/                   # FesodSheet 读取 DTO
│   │   └── listener/             # FesodSheet 读取监听器
│   ├── service/                   # 业务逻辑
│   ├── ui/
│   │   └── <Name>PluginUi.java   # JavaFX UI
│   ├── util/                      # 工具类
│   └── worker/                    # 后台任务
├── src/main/resources/
│   ├── META-INF/services/fan.summer.fengyu.api.SwissKitJPlugin
│   ├── i18n/
│   │   ├── messages.properties        # 默认语言（英文）
│   │   └── messages_zh.properties     # 中文翻译
│   ├── init.sql                   # DDL（需要数据库时）
│   ├── mybatis-config.xml         # MyBatis 配置（需要数据库时）
│   └── mapper/                    # MyBatis XML Mapper（需要数据库时）
```

---

## 1. pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>{{base-package}}</groupId>
    <artifactId>{{plugin-name}}</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <javafx.version>21.0.2</javafx.version>
        <fengyu.api.version>3.0.0</fengyu.api.version>
    </properties>

    <dependencies>
        <!-- FengYu API — 运行时由宿主提供 -->
        <dependency>
            <groupId>fan.summer.fengyu.api</groupId>
            <artifactId>FengYu-Api</artifactId>
            <version>${fengyu.api.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- JavaFX — 运行时由宿主提供 -->
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-graphics</artifactId>
            <version>${javafx.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-controls</artifactId>
            <version>${javafx.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- 根据需要添加以下依赖 -->

        <!-- Excel 读写（可选） -->
        <dependency>
            <groupId>org.apache.fesod</groupId>
            <artifactId>fesod-sheet</artifactId>
            <version>2.0.1-incubating</version>
        </dependency>

        <!-- 嵌入式数据库（可选） -->
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <version>2.4.240</version>
        </dependency>

        <!-- MyBatis（可选，配合 H2 使用） -->
        <dependency>
            <groupId>org.mybatis</groupId>
            <artifactId>mybatis</artifactId>
            <version>3.5.19</version>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>1.18.42</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>${project.artifactId}-${project.version}</finalName>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>

            <!-- Shade 插件：生成包含合并 SPI 服务的 fat JAR -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.3</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

    <profiles>
        <profile>
            <id>dev</id>
            <dependencies>
                <dependency>
                    <groupId>org.openjfx</groupId>
                    <artifactId>javafx-graphics</artifactId>
                    <version>${javafx.version}</version>
                </dependency>
                <dependency>
                    <groupId>org.openjfx</groupId>
                    <artifactId>javafx-controls</artifactId>
                    <version>${javafx.version}</version>
                </dependency>
            </dependencies>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.openjfx</groupId>
                        <artifactId>javafx-maven-plugin</artifactId>
                        <version>0.0.8</version>
                        <configuration>
                            <mainClass>{{base-package}}.DevLauncher</mainClass>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
</project>
```

---

## 2. SPI 注册文件

路径：`src/main/resources/META-INF/services/fan.summer.fengyu.api.SwissKitJPlugin`

内容（仅一行）：
```
{{base-package}}.{{Name}}Plugin
```

> **CRITICAL**：必须是 `META-INF/services/`，不是 `services/`。Java `ServiceLoader` 只查找 `META-INF/services/`。

---

## 3. DevLauncher（绕过模块系统）

```java
package {{base-package}};

public class DevLauncher {
    public static void main(String[] args) {
        {{Name}}DevApp.main(args);
    }
}
```

此类的 **零 JavaFX 导入**。JVM 模块系统只从包含 `main()` 的类开始检查模块依赖。保持 JavaFX 代码在此类之外，就无需 `--module-path` 或 `--add-modules` 参数。

---

## 4. {{Name}}DevApp（独立运行入口）

```java
package {{base-package}};

import {{base-package}}.ui.{{Name}}PluginUi;
import javafx.application.Application;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class {{Name}}DevApp extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        Scene scene = new Scene(new Group(new {{Name}}PluginUi().getView()), 800, 600);
        stage.setTitle("{{plugin-name}} Dev");
        stage.setScene(scene);
        stage.show();
    }
}
```

调用链：`java DevLauncher` → `{{Name}}DevApp.main()` → `launch()` → `start(Stage)`

---

## 5. 文件夹说明

| 目录 | 说明 |
|------|------|
| `database/` | 仅在需要数据库时创建 |
| `excel/` | 仅在需要 Excel I/O 时创建 |
| `service/` | 可选，业务逻辑放这里 |
| `worker/` | 仅在需要后台任务时创建 |
| `util/` | 可选，工具类放这里 |
| `i18n/` | 必须，包含 `messages.properties` 和 `messages_zh.properties` |