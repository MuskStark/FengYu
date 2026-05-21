You are guiding the user through creating a new SwissKitJ plugin. Ask them for:

1. **Plugin name** (e.g. `StarReport`) — used as artifact ID and project name
2. **Plugin ID** (e.g. `plugin.swisskit.star`) — unique identifier for the host
3. **Base package** (e.g. `fan.swisskitj.plugin.star`) — Java package root
4. **Short description** — shown in the host UI
5. **Needs database?** — if yes, include H2 + MyBatis layer
6. **Needs Excel I/O?** — if yes, include fesod-sheet dependencies and listener pattern
7. **Needs file upload?** — if yes, include background worker pattern

Then scaffold the project following the templates below. Create all files, do not leave placeholders.

---

## Project Structure

```
<plugin-name>/
├── pom.xml
├── src/main/java/<package-path>/
│   ├── <Name>Plugin.java          # SPI entry point
│   ├── <Name>DevApp.java          # JavaFX Application for dev mode
│   ├── DevLauncher.java           # Module-system bypass launcher
│   ├── database/
│   │   ├── DatabaseInit.java      # H2 + MyBatis bootstrap
│   │   ├── entity/                # MyBatis entity classes
│   │   └── mapper/                # MyBatis mapper interfaces
│   ├── excel/
│   │   ├── dto/                   # FesodSheet read DTOs
│   │   └── listener/              # FesodSheet read listeners
│   ├── service/                   # Business logic
│   ├── ui/
│   │   └── <Name>PluginUi.java    # JavaFX UI
│   ├── util/                      # Utilities
│   └── worker/                    # Background tasks
├── src/main/resources/
│   ├── META-INF/services/fan.summer.api.SwissKitJPlugin
│   ├── init.sql                   # DDL (if database needed)
│   ├── mybatis-config.xml         # MyBatis config (if database needed)
│   ├── mapper/                    # MyBatis XML mappers
│   └── template/                  # Excel templates (if Excel output needed)
```

---

## File Templates

### 1. pom.xml

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
        <swisskit.api.version>3.0.0</swisskit.api.version>
    </properties>

    <dependencies>
        <!-- SwissKitJ API — provided by host at runtime -->
        <dependency>
            <groupId>fan.summer.api</groupId>
            <artifactId>SwissKitJ-Api</artifactId>
            <version>${swisskit.api.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- JavaFX — provided by host at runtime -->
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

        <!-- Include ONLY the dependencies the plugin needs: -->

        <!-- Excel reading/writing (optional) -->
        <dependency>
            <groupId>org.apache.fesod</groupId>
            <artifactId>fesod-sheet</artifactId>
            <version>2.0.1-incubating</version>
        </dependency>

        <!-- Embedded database (optional) -->
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <version>2.4.240</version>
        </dependency>

        <!-- Database access (optional, needed with H2) -->
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

            <!-- Shade plugin: creates fat JAR with merged SPI services -->
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

### 2. SPI Registration

File: `src/main/resources/META-INF/services/fan.summer.api.SwissKitJPlugin`

```
{{base-package}}.{{Name}}Plugin
```

**CRITICAL**: This file MUST be under `META-INF/services/`, NOT `services/`. Java's `ServiceLoader` only looks in `META-INF/services/`. The shade plugin's `ServicesResourceTransformer` will merge SPI files from dependencies.

### 3. Plugin Entry Point

```java
package {{base-package}};

import fan.summer.api.SwissKitJPlugin;
import javafx.scene.Node;
import {{base-package}}.ui.{{Name}}PluginUi;

public class {{Name}}Plugin implements SwissKitJPlugin {

    @Override
    public String getId() {
        return "{{plugin-id}}";
    }

    @Override
    public String getName() {
        return "{{display-name}}";
    }

    @Override
    public String getDescription() {
        return "{{description}}";
    }

    @Override
    public String getCategory() {
        return "OTHER";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getMdiIcon() {
        return "{{icon-name}}";
    }

    @Override
    public String getIconStyle() {
        return "BLUE";
    }

    @Override
    public Node createView() {
        return new {{Name}}PluginUi().getView();
    }
}
```

### 4. Dev Launcher (JavaFX Module-System Bypass)

**DevLauncher** — This class has ZERO JavaFX imports. The JVM module system only checks module dependencies starting from the class containing `main()`. By keeping JavaFX references out of this class, no `--module-path` or `--add-modules` flags are needed.

```java
package {{base-package}};

public class DevLauncher {
    public static void main(String[] args) {
        {{Name}}DevApp.main(args);
    }
}
```

**{{Name}}DevApp** — The actual JavaFX Application. Wraps the plugin UI in a standalone window for development.

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

Call chain: `java DevLauncher` (no JavaFX imports) → `{{Name}}DevApp.main()` → `launch()` → `start(Stage)`.

### 5. Plugin UI

```java
package {{base-package}}.ui;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

public class {{Name}}PluginUi {

    private GridPane rootPanel;

    public {{Name}}PluginUi() {
        initComponents();
    }

    private void initComponents() {
        rootPanel = new GridPane();
        rootPanel.setHgap(10);
        rootPanel.setVgap(5);
        rootPanel.setPadding(new Insets(0));

        // Add UI components here
        // Use rootPanel.add(node, col, row) for grid placement

        ColumnConstraints col0 = new ColumnConstraints();
        col0.setHgrow(Priority.NEVER);
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setHgrow(Priority.ALWAYS);
        rootPanel.getColumnConstraints().addAll(col0, col1);
    }

    public Node getView() {
        return rootPanel;
    }
}
```

### 6. Database Layer (if database needed)

**DatabaseInit** — Bootstraps H2 and MyBatis. Database files stored at `~/.swisskit/plugins/database/pl_{{slug}}`.

Key details:
- H2 URL uses `AUTO_SERVER=TRUE` for concurrent access
- `INIT=CREATE SCHEMA IF NOT EXISTS PUBLIC` ensures schema exists
- `mybatis-config.xml` uses `${db.url}` placeholder injected via `Properties`
- `init()` must be called before any database operations

```java
package {{base-package}}.database;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

public class DatabaseInit {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseInit.class);

    private static final String DB_URL;

    static {
        String dbPath = Path.of(System.getProperty("user.dir"))
                .resolve(".swisskit")
                .resolve("plugins")
                .resolve("database")
                .resolve("pl_{{slug}}")
                .toAbsolutePath()
                .toString()
                .replace("\\", "/");
        DB_URL = "jdbc:h2:file:" + dbPath
                + ";AUTO_SERVER=TRUE"
                + ";INIT=CREATE SCHEMA IF NOT EXISTS PUBLIC\\;SET SCHEMA PUBLIC";
    }

    private static SqlSessionFactory sqlSessionFactory;

    public static void init() {
        try {
            Path dbDir = Path.of(System.getProperty("user.dir"))
                    .resolve(".swisskit").resolve("plugins").resolve("database");
            if (!Files.exists(dbDir)) {
                Files.createDirectories(dbDir);
            }
            createTables();
            initMyBatis();
        } catch (Exception e) {
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    private static void createTables() {
        try (InputStream sql = DatabaseInit.class.getClassLoader().getResourceAsStream("init.sql")) {
            if (sql == null) throw new RuntimeException("Cannot find init.sql");
            String content = new String(sql.readAllBytes());
            try (Connection conn = DriverManager.getConnection(DB_URL);
                 Statement stmt = conn.createStatement()) {
                stmt.execute(content);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tables", e);
        }
    }

    private static void initMyBatis() {
        try (InputStream config = DatabaseInit.class.getClassLoader()
                .getResourceAsStream("mybatis-config.xml")) {
            if (config == null) throw new RuntimeException("Cannot find mybatis-config.xml");
            Properties props = new Properties();
            props.setProperty("db.url", DB_URL);
            sqlSessionFactory = new SqlSessionFactoryBuilder().build(config, props);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize MyBatis", e);
        }
    }

    public static SqlSession getSqlSession() {
        if (sqlSessionFactory == null) {
            throw new IllegalStateException("Database not initialized. Call init() first.");
        }
        return sqlSessionFactory.openSession();
    }
}
```

**mybatis-config.xml**:

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE configuration PUBLIC "-//mybatis.org//DTD Config 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-config.dtd">
<configuration>
    <settings>
        <setting name="mapUnderscoreToCamelCase" value="true"/>
        <setting name="localCacheScope" value="STATEMENT"/>
        <setting name="cacheEnabled" value="false"/>
        <setting name="jdbcTypeForNull" value="NULL"/>
    </settings>
    <environments default="default">
        <environment id="default">
            <transactionManager type="JDBC"/>
            <dataSource type="UNPOOLED">
                <property name="driver" value="org.h2.Driver"/>
                <property name="url" value="${db.url}"/>
            </dataSource>
        </environment>
    </environments>
    <mappers>
        <!-- Add mapper XML references here -->
    </mappers>
</configuration>
```

**Mapper pattern** — Each mapper has a Java interface and XML file:

Java interface (`{{Name}}Mapper.java`):
```java
package {{base-package}}.database.mapper;

import {{base-package}}.database.entity.{{Name}}Entity;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface {{Name}}Mapper {
    void batchInsert(List<{{Name}}Entity> data);
    List<{{Name}}Entity> selectAllByDate(@Param("recordDate") String recordDate);
    void deleteByDate(@Param("recordDate") String date);
}
```

XML mapper (`src/main/resources/mapper/{{Name}}Mapper.xml`):
```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="{{base-package}}.database.mapper.{{Name}}Mapper">

    <insert id="batchInsert" parameterType="list">
        INSERT INTO TABLE_NAME (id, field1, field2)
        VALUES
        <foreach collection="list" item="record" separator=",">
            (#{record.id}, #{record.field1}, #{record.field2})
        </foreach>
    </insert>

    <select id="selectAllByDate" resultType="{{base-package}}.database.entity.{{Name}}Entity">
        SELECT * FROM TABLE_NAME WHERE record_date = #{recordDate}
    </select>

    <delete id="deleteByDate">
        DELETE FROM TABLE_NAME WHERE record_date = #{recordDate}
    </delete>
</mapper>
```

**IMPORTANT**: The XML `namespace` MUST match the Java interface's fully qualified name exactly. A mismatch causes `org.apache.ibatis.binding.BindingException`.

### 7. Excel Reading (if Excel input needed)

**DTO** — Maps Excel columns by index using `@ExcelProperty`:

```java
package {{base-package}}.excel.dto;

import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelProperty;

@Data
public class {{Name}}Dto {
    @ExcelProperty(index = 0)
    private String field1;
    @ExcelProperty(index = 1)
    private String field2;
}
```

**Listener** — Implements `ReadListener`, batches records, persists via MyBatis:

```java
package {{base-package}}.excel.listener;

import {{base-package}}.database.DatabaseInit;
import {{base-package}}.database.entity.{{Name}}Entity;
import {{base-package}}.database.mapper.{{Name}}Mapper;
import {{base-package}}.excel.dto.{{Name}}Dto;
import org.apache.fesod.common.util.ListUtils;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.read.listener.ReadListener;
import org.apache.ibatis.session.SqlSession;

import java.util.ArrayList;
import java.util.List;

public class {{Name}}Listener implements ReadListener<{{Name}}Dto> {

    private static final int BATCH_COUNT = 1000;
    private List<{{Name}}Dto> batch = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);

    @Override
    public void invoke({{Name}}Dto data, AnalysisContext context) {
        batch.add(data);
        if (batch.size() >= BATCH_COUNT) {
            saveData();
            batch = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        saveData();
    }

    private void saveData() {
        if (batch.isEmpty()) return;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            {{Name}}Mapper mapper = session.getMapper({{Name}}Mapper.class);
            // Convert DTOs to entities and insert
            List<{{Name}}Entity> entities = new ArrayList<>();
            batch.forEach(dto -> {
                {{Name}}Entity entity = new {{Name}}Entity();
                // Map dto fields to entity
                entities.add(entity);
            });
            mapper.batchInsert(entities);
            session.commit();
        }
    }
}
```

**Reading a file**:
```java
// With charset (for GBK-encoded files)
FesodSheet.read(filePath, {{Name}}Dto.class, new {{Name}}Listener())
    .charset(Charset.forName("GBK"))
    .sheet().doRead();

// Default charset (UTF-8)
FesodSheet.read(filePath, {{Name}}Dto.class, new {{Name}}Listener())
    .sheet().doRead();

// From InputStream (e.g. extracted from ZIP)
FesodSheet.read(inputStream, {{Name}}Dto.class, new {{Name}}Listener())
    .charset(Charset.forName("GBK"))
    .sheet().doRead();
```

### 8. Background Worker (if file upload needed)

Extends JavaFX `Task<Void>` for async execution with success/failure callbacks:

```java
package {{base-package}}.worker;

import {{base-package}}.database.DatabaseInit;
import {{base-package}}.excel.dto.{{Name}}Dto;
import {{base-package}}.excel.listener.{{Name}}Listener;
import javafx.concurrent.Task;
import org.apache.fesod.sheet.FesodSheet;

public class {{Name}}UploadWorker extends Task<Void> {

    private final String filePath;

    public {{Name}}UploadWorker(String filePath) {
        this.filePath = filePath;
    }

    @Override
    protected Void call() throws Exception {
        DatabaseInit.init();
        FesodSheet.read(filePath, {{Name}}Dto.class, new {{Name}}Listener())
                .sheet().doRead();
        return null;
    }
}
```

Usage in UI:
```java
{{Name}}UploadWorker worker = new {{Name}}UploadWorker(filePath);
worker.setOnSucceeded(ev -> { /* show success alert */ });
worker.setOnFailed(ev -> { /* show error with worker.getException() */ });
new Thread(worker).start();
```

### 9. File Chooser Utility

```java
package {{base-package}}.util;

import javafx.scene.Node;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import java.io.File;

public abstract class FileChoiceUtil {
    public static String choiceFile(Node node, String title, String description, String... extensions) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(title);
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(description, extensions));
        Window window = node.getScene().getWindow();
        File file = fileChooser.showOpenDialog(window);
        return file != null ? file.getAbsolutePath() : null;
    }
}
```

---

## Common Pitfalls

1. **SPI file location**: Must be `META-INF/services/fan.summer.api.SwissKitJPlugin`, NOT `services/`. `ServiceLoader` will not find it otherwise.

2. **Mapper XML namespace**: Must exactly match the Java mapper interface's fully qualified class name. Package renames require updating both Java files AND XML namespaces.

3. **SPI file content**: Must contain the fully qualified class name of the plugin class (e.g. `fan.swisskitj.plugin.star.StarPlugin`), not a partial or old name.

4. **JavaFX module system**: `DevLauncher` must have zero JavaFX imports. All JavaFX code lives in `{{Name}}DevApp` which is called indirectly.

5. **JavaFX `Scene` requires `Parent`**: `StarPluginUi.getView()` returns `Node`, but `Scene` constructor takes `Parent`. Wrap in `new Group(node)` to bridge the type mismatch.

6. **Shade plugin**: The `ServicesResourceTransformer` is required to merge SPI files from dependencies into the fat JAR. Without it, SPI files get overwritten.

7. **H2 database path**: Uses `user.dir` (current working directory), not `user.home`. In production, the host app sets `user.dir` appropriately. The database directory path must use forward slashes even on Windows.

8. **Dev profile mainClass**: Must match the actual `DevLauncher` package path, not an old or placeholder name.

---

## Build & Deploy

```bash
# Development (runs JavaFX app locally, bypasses module system)
mvn clean compile -Pdev
mvn javafx:run -Pdev

# Production (creates fat JAR)
mvn clean package
# Output: target/<plugin-name>-1.0-SNAPSHOT.jar
# Deploy to host by placing the JAR in the host's plugins directory
```
