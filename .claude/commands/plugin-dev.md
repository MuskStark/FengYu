You are guiding the user through creating a new SwissKitJ plugin. Ask them for:

1. **Plugin name** (e.g. `StarReport`) — used as artifact ID and project name
2. **Plugin ID** (e.g. `plugin.swisskit.star`) — unique identifier for the host
3. **Base package** (e.g. `fan.swisskitj.plugin.star`) — Java package root
4. **Short description** — shown in the host UI
5. **Needs database?** — if yes, include H2 + MyBatis layer
6. **Needs Excel I/O?** — if yes, include fesod-sheet dependencies and listener pattern
7. **Needs file upload?** — if yes, include background worker pattern
8. **Needs AI integration?** — if yes, include AiService integration pattern

Then scaffold the project following the templates below. Create all files, do not leave placeholders.

---

## Project Structure

```
<plugin-name>/
├── pom.xml
├── src/main/java/<package-path>/
│   ├── <Name>Plugin.java          # SPI entry point
│   ├── DevLauncher.java           # Module-system bypass launcher with PluginPreviewWindow
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
│   ├── i18n/
│   │   ├── messages.properties        # Default (English) i18n translations
│   │   └── messages_zh.properties     # Chinese translations (add locales as needed)
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
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <source>21</source>
                    <target>21</target>
                    <encoding>UTF-8</encoding>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>1.18.42</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
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

import fan.summer.api.IconStyle;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import fan.summer.api.ToolType;
import fan.summer.api.i18n.I18n;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import javafx.scene.Node;
import {{base-package}}.ui.{{Name}}PluginUi;

public class {{Name}}Plugin implements SwissKitJPlugin {

    private static final PluginLogger log = LoggerFactory.getLogger({{Name}}Plugin.class);

    private {{Name}}PluginUi ui;

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
    public ToolCategory getCategory() {
        return ToolCategory.OTHER;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getMdiIcon() {
        return "{{mdi-icon-name}}";  // e.g. "file-excel" — see https://pictogrammers.com/library/mdi/
    }

    @Override
    public IconStyle getIconStyle() {
        return IconStyle.BLUE;  // BLUE | PURPLE | TEAL | AMBER | RED | PINK | GRAY
    }

    @Override
    public ToolType getType() {
        return ToolType.PLUGIN;
    }

    @Override
    public Node createView() {
        I18n.registerPluginBundle("i18n.messages", getClass().getClassLoader());
        ui = new {{Name}}PluginUi();
        return ui.getView();
    }

    @Override
    public boolean hasRunningTasks() {
        // Return true when this plugin has background tasks that should
        // continue running after the user navigates away.
        // Example: return worker != null && worker.isRunning();
        return false;
    }

    @Override
    public void onBackground() {
        // Called when the user navigates away while hasRunningTasks() is true.
        // Use to disable UI polling, reduce update frequency, etc.
    }

    @Override
    public void onForeground() {
        // Called when the user returns to this plugin from a background state.
        // Use to refresh UI elements that need manual updates after scene detach.
    }

    @Override
    public void onUnload() {
        // Called once when the plugin is being unloaded or the app is shutting down.
        // Release threads, file handles, etc.
    }
}
```

### 4. Dev Launcher (JavaFX Module-System Bypass)

**DevLauncher** — This class has ZERO JavaFX imports except `Platform`. Uses `PluginPreviewWindow` to launch the plugin in a SwissKitJ-like shell for development. The JVM module system only checks module dependencies starting from the class containing `main()`. By keeping JavaFX references minimal in this class, no `--module-path` or `--add-modules` flags are needed.

```java
package {{base-package}};

import fan.summer.api.preview.PluginPreviewWindow;
import javafx.application.Platform;

public class DevLauncher {
    public static void main(String[] args) {
        Platform.startup(() -> {
            PluginPreviewWindow.configure().withPlugin(new {{Name}}Plugin()).launch();
        });
    }
}
```

Call chain: `java DevLauncher` → `Platform.startup()` → `PluginPreviewWindow.configure().withPlugin(...).launch()` → opens a SwissKitJ-like preview shell with the plugin embedded.

The `PluginPreviewWindow` supports additional configuration:
```java
PluginPreviewWindow.configure()
    .withPlugin(new MyPlugin())          // or .withJar(Path.of("build/libs/my-plugin.jar"))
    .title("My Plugin — Preview")
    .windowSize(960, 620)                // default 960 × 620
    .showSidebar(true)                   // default true
    .showSearchBar(true)                 // default true
    .showStatusBar(true)                 // default true
    .showDetailPanel(true)               // default true
    .launch();
```

### 5. Plugin UI

```java
package {{base-package}}.ui;

import fan.summer.api.component.GlassNotification;
import fan.summer.api.component.UiUtils;
import fan.summer.api.i18n.I18n;
import fan.summer.api.theme.Themes;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

public class {{Name}}PluginUi {

    private GridPane rootPanel;
    private Label exampleLabel = new Label();
    private Button exampleButton = UiUtils.glassBtn("Example", true);

    public {{Name}}PluginUi() {
        initComponents();
    }

    private void initComponents() {
        rootPanel = new GridPane();
        rootPanel.setHgap(10);
        rootPanel.setVgap(5);
        rootPanel.setPadding(new Insets(0));

        ColumnConstraints col0 = new ColumnConstraints();
        col0.setHgrow(Priority.NEVER);
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setHgrow(Priority.ALWAYS);
        rootPanel.getColumnConstraints().addAll(col0, col1);

        // Example: add components
        rootPanel.add(exampleLabel, 0, 0);
        rootPanel.add(exampleButton, 1, 0);

        // Bind i18n keys to properties — auto-updates on locale change
        String p = "plugin.{{slug}}.";
        I18n.bind(exampleLabel.textProperty(), p + "exampleLabel");
    }

    public Node getView() {
        return rootPanel;
    }

    // Notification helper — glassmorphism-styled, matches the host app theme
    private void showSuccess(String message) {
        GlassNotification.toast(rootPanel, GlassNotification.Type.SUCCESS, message);
    }

    private void showError(String message) {
        GlassNotification.toast(rootPanel, GlassNotification.Type.ERROR, message);
    }

    private void showWarning(String title, String message) {
        GlassNotification.notify(rootPanel, GlassNotification.Type.WARNING, title, message);
    }

    private boolean confirm(String title, String message) {
        return GlassNotification.confirm(rootPanel, title, message);
    }
}
```

**Reusable UI components from `fan.summer.api.component`:**

| Component | Use case | Example |
|-----------|----------|---------|
| `UiUtils.glassBtn(text, primary)` | Consistent styled buttons | `UiUtils.glassBtn("Save", true)` |
| `UiUtils.hSpacer()` | Horizontal spacer in HBox | `HBox hBox = new HBox(label, UiUtils.hSpacer(), btn)` |
| `UiUtils.subLabel(text)` | Muted form field label | `UiUtils.subLabel("Output path")` |
| `UiUtils.sectionTitle(text)` | Section heading label | `UiUtils.sectionTitle("Settings")` |
| `UiUtils.fieldStyle()` | CSS string for text inputs | `textField.setStyle(UiUtils.fieldStyle())` |
| `UiUtils.comboStyle()` | CSS string for combo boxes | `comboBox.setStyle(UiUtils.comboStyle())` |
| `GlassNotification.toast(context, type, msg)` | Auto-dismiss toast notification | `GlassNotification.toast(node, Type.SUCCESS, "Done")` |
| `GlassNotification.notify(context, type, title, msg)` | Modal notification with OK | `GlassNotification.notify(node, Type.ERROR, "Error", details)` |
| `GlassNotification.confirm(context, title, msg)` | Modal confirm with OK/Cancel | `GlassNotification.confirm(node, "Delete?", "Are you sure?")` |
| `StepWizard` | Multi-step wizard container | See CLAUDE.md "StepWizard" section |

**GlassNotification types:** `INFO`, `SUCCESS`, `WARNING`, `ERROR`

**i18n usage patterns:**

| Pattern | Use case | Example |
|---------|----------|---------|
| `I18n.bind(property, key)` | Static labels, buttons — auto-updates on locale change | `I18n.bind(label.textProperty(), "plugin.xxx.title")` |
| `I18n.bind(property, key, args...)` | Formatted labels — re-evaluates on locale change | `I18n.bind(status.textProperty(), "plugin.xxx.count", count)` |
| `I18n.get(key)` | Dynamic text (status, formatted messages) | `statusLabel.setText(I18n.get("plugin.xxx.idle"))` |
| `I18n.get(key, args...)` | Dynamic formatted text | `I18n.get("plugin.xxx.loaded", fileCount)` |
| `I18n.addListener(runnable)` | Custom refresh when locale changes | `I18n.addListener(this::refreshStatus)` |

**Theme rules:**
- Nodes embedded in the host Scene inherit `swisskit-common.css` automatically — no action needed.
- Only independent windows (custom Stage) need `Themes.applyTo(scene)` to match the host theme.
- `GlassNotification` applies the theme automatically — no manual `Themes.applyTo()` needed.

### 6. i18n Resource Files

Properties files live under `src/main/resources/i18n/`. The base name `i18n.messages` is what gets passed to `I18n.registerPluginBundle()`.

**Key convention**: Prefix all keys with `plugin.<slug>.` to avoid collisions with the host or other plugins.

File: `src/main/resources/i18n/messages.properties` (default / English)
```properties
# {{Name}} Plugin i18n
plugin.{{slug}}.exampleLabel=Example Label
plugin.{{slug}}.exampleButton=Example Button
plugin.{{slug}}.idle=Idle
plugin.{{slug}}.running=Running...
plugin.{{slug}}.error=Error
```

File: `src/main/resources/i18n/messages_zh.properties` (Chinese)
```properties
# {{Name}} Plugin i18n Chinese
plugin.{{slug}}.exampleLabel=示例标签
plugin.{{slug}}.exampleButton=示例按钮
plugin.{{slug}}.idle=空闲
plugin.{{slug}}.running=运行中...
plugin.{{slug}}.error=错误
```

**IMPORTANT**:
- The base name `i18n.messages` must match the path — if files are in `i18n/` and named `messages.properties`, the base name is `i18n.messages`.
- Both files must use the **exact same keys**. Missing keys in a locale file fall back to the default `messages.properties`.
- Key prefix `plugin.<slug>.` is a convention, not enforced — but it prevents collisions.

### 7. Database Layer (if database needed)

**DatabaseInit** — Bootstraps H2 and MyBatis. Database files stored at `~/.swisskit/plugins/database/pl_{{slug}}`.

Key details:
- H2 URL uses `AUTO_SERVER=TRUE` for concurrent access
- `INIT=CREATE SCHEMA IF NOT EXISTS PUBLIC` ensures schema exists
- `mybatis-config.xml` uses `${db.url}` placeholder injected via `Properties`
- `init()` must be called before any database operations

```java
package {{base-package}}.database;

import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

public class DatabaseInit {

    private static final PluginLogger log = LoggerFactory.getLogger(DatabaseInit.class);

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

### 8. Excel Reading (if Excel input needed)

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

### 9. Background Worker (if file upload needed)

Extends JavaFX `Task<Void>` for async execution with success/failure callbacks:

```java
package {{base-package}}.worker;

import {{base-package}}.database.DatabaseInit;
import {{base-package}}.excel.dto.{{Name}}Dto;
import {{base-package}}.excel.listener.{{Name}}Listener;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import javafx.concurrent.Task;
import org.apache.fesod.sheet.FesodSheet;

public class {{Name}}UploadWorker extends Task<Void> {

    private static final PluginLogger log = LoggerFactory.getLogger({{Name}}UploadWorker.class);

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
worker.setOnSucceeded(ev -> {
    GlassNotification.toast(rootPanel, GlassNotification.Type.SUCCESS,
        I18n.get("plugin.{{slug}}.upload.success"));
});
worker.setOnFailed(ev -> {
    GlassNotification.notify(rootPanel, GlassNotification.Type.ERROR,
        I18n.get("plugin.{{slug}}.error"),
        worker.getException().getMessage());
});
new Thread(worker).start();
```

### 10. Background Execution

Plugins with long-running tasks (file processing, uploads, etc.) can opt into background execution. Override `hasRunningTasks()` to return `true` when tasks are active:

```java
private javafx.concurrent.Task<Void> activeWorker;

@Override
public boolean hasRunningTasks() {
    return activeWorker != null && activeWorker.isRunning();
}

@Override
public void onBackground() {
    // Optional: reduce UI update frequency while in background
}

@Override
public void onForeground() {
    // Optional: refresh UI elements that may need manual updates
}
```

**How it works in the host:**
- When the user navigates away and `hasRunningTasks()` returns `true`, the host calls `onBackground()` instead of `onDeactivate()` and keeps the plugin's view cached
- The plugin's view Node retains all JavaFX property bindings even when detached from the scene graph — progress bars, labels, and status text continue updating automatically
- When the user clicks the same ToolCard again, the cached view is re-attached and `onForeground()` is called
- A green pulse indicator appears on the ToolCard while the plugin is backgrounded
- Multiple plugins can run in the background simultaneously

**Key rules:**
1. `hasRunningTasks()` is polled at navigation time — return the current state, not a cached value
2. `onBackground()` / `onForeground()` are optional — most plugins only need `hasRunningTasks()`
3. Always call `super` or use the default no-op implementations — these are default methods on the interface

### 11. AI Integration (if AI integration needed)

Plugins can access the host's AI service via `AiServiceProvider` to integrate AI capabilities (chat, tool calling) without depending on any specific inference implementation.

**Getting the AI service:**
```java
import fan.summer.api.ai.AiService;
import fan.summer.api.ai.AiServiceProvider;
import java.util.Optional;

Optional<AiService> aiOpt = AiServiceProvider.getService();
if (aiOpt.isPresent() && aiOpt.get().isReady()) {
    AiService ai = aiOpt.get();
    // Use AI service
}
```

**Sending a chat message with streaming:**
```java
import fan.summer.api.ai.AiChatMessage;
import fan.summer.api.ai.AiStreamCallback;
import java.util.List;

List<AiChatMessage> messages = List.of(
    AiChatMessage.system("You are a helpful assistant."),
    AiChatMessage.user("Hello!")
);

ai.chat(messages, new AiStreamCallback() {
    @Override
    public void onToken(String fragment) {
        Platform.runLater(() -> responseArea.appendText(fragment));
    }

    @Override
    public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
        Platform.runLater(() -> statusLabel.setText(
            I18n.get("plugin.{{slug}}.done", tokensGenerated)));
    }

    @Override
    public void onError(Throwable error) {
        Platform.runLater(() -> GlassNotification.toast(view,
            GlassNotification.Type.ERROR, error.getMessage()));
    }
});
```

**Registering a custom AI tool:**
```java
import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolParam;
import fan.summer.api.ai.AiToolResult;
import java.util.List;
import java.util.Map;

AiTool myTool = new AiTool() {
    @Override public String getName()        { return "my_tool"; }
    @Override public String getDescription() { return "Does something useful"; }
    @Override public List<AiToolParam> getParameters() {
        return List.of(AiToolParam.of("input", "string", "The input to process"));
    }
    @Override public AiToolResult execute(Map<String, Object> arguments) {
        String input = (String) arguments.get("input");
        return AiToolResult.success("Processed: " + input);
    }
};

// Register globally via AiServiceProvider (visible to all AI backends)
AiServiceProvider.registerTool(myTool);

// Or register on a specific AiService instance
ai.registerTool(myTool);
```

**Listening for AI state changes:**
```java
AiServiceProvider.addOnStateChangeListener(() -> {
    // Called when mode switches, model loads/unloads, etc.
    boolean ready = AiServiceProvider.getService()
        .map(AiService::isReady).orElse(false);
    Platform.runLater(() -> updateUI(ready));
});
```

### 12. File Chooser Utility

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

### 13. Plugin Logging

Use `fan.summer.api.log.LoggerFactory` (in `SwissKitJ-Api`) — do NOT depend on SLF4J directly. The host routes plugin log calls into the same SLF4J + Logback backbone (console at INFO+, rolling file at DEBUG+ under `.swisskit/logs/swisskit.log`). If the host has not installed a binder (e.g. plugin unit tests), `LoggerFactory` returns a silent no-op logger.

```java
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

public class MyService {
    private static final PluginLogger log = LoggerFactory.getLogger(MyService.class);

    public void process(String taskId) {
        log.info("Processing task: {}", taskId);
        try {
            // ...
            log.debug("Task {} completed, records={}", taskId, count);
        } catch (Exception e) {
            log.error("Task {} failed", taskId, e);
        }
    }
}
```

Use SLF4J-style `{}` placeholders — formatting is deferred until the level is actually enabled.

---

## Common Pitfalls

1. **SPI file location**: Must be `META-INF/services/fan.summer.api.SwissKitJPlugin`, NOT `services/`. `ServiceLoader` will not find it otherwise.

2. **Mapper XML namespace**: Must exactly match the Java mapper interface's fully qualified class name. Package renames require updating both Java files AND XML namespaces.

3. **SPI file content**: Must contain the fully qualified class name of the plugin class (e.g. `fan.swisskitj.plugin.star.StarPlugin`), not a partial or old name.

4. **JavaFX module system**: `DevLauncher` uses `Platform.startup()` to bootstrap JavaFX, then launches `PluginPreviewWindow`. No separate `DevApp` class is needed.

5. **Shade plugin**: The `ServicesResourceTransformer` is required to merge SPI files from dependencies into the fat JAR. Without it, SPI files get overwritten.

6. **H2 database path**: Uses `user.dir` (current working directory), not `user.home`. In production, the host app sets `user.dir` appropriately. The database directory path must use forward slashes even on Windows.

7. **Dev profile mainClass**: Must match the actual `DevLauncher` package path, not an old or placeholder name.

8. **i18n bundle registration**: `I18n.registerPluginBundle("i18n.messages", getClass().getClassLoader())` MUST be called in `createView()` before the UI is constructed. Without this, `I18n.get()` and `I18n.bind()` return raw keys instead of translated text. The ClassLoader must be the plugin's own (`getClass().getClassLoader()`), not the system ClassLoader.

9. **Use GlassNotification instead of Alert**: `Alert` creates its own Scene with default JavaFX styling, which does not match the SwissKitJ glassmorphism theme. Use `GlassNotification.toast()`, `.notify()`, and `.confirm()` for consistent themed notifications. If you must use `Alert`, apply `Themes.applyTo(scene)` via a `sceneProperty` listener on `alert.getDialogPane()`.

10. **Plugin logging**: Use `fan.summer.api.log.LoggerFactory` + `PluginLogger`, NOT `org.slf4j.LoggerFactory` + `Logger`. The host installs a binder that routes plugin logs correctly; without it the no-op logger ensures safety in unit tests.

11. **Icon name format**: `getMdiIcon()` returns an MDI icon name WITHOUT the `mdi-` prefix (e.g. `"file-excel"`, NOT `"mdi-file-excel"`). Full icon list: https://pictogrammers.com/library/mdi/

12. **IconStyle is an enum**: `getIconStyle()` returns an `IconStyle` enum value (`BLUE`, `PURPLE`, `TEAL`, `AMBER`, `RED`, `PINK`, `GRAY`), NOT a CSS class string.

13. **ToolCategory is an enum**: `getCategory()` returns a `ToolCategory` enum value (`DEV`, `TEXT`, `IMAGE`, `NET`, `OTHER`), NOT a raw string.

14. **ToolType is an enum**: `getType()` returns a `ToolType` enum value (`PLUGIN` for external plugins, `BUILTIN` for built-in tools), NOT a raw string. External plugins should use the default `ToolType.PLUGIN`.

15. **JavaFX Control maxWidth defaults**: `Control` subclasses (ScrollPane, Button, ProgressBar) have `maxWidth = USE_COMPUTED_SIZE` — they stop growing at their pref size inside a StackPane. Use `setMaxWidth(Double.MAX_VALUE)` + `HBox.setHgrow(node, Priority.ALWAYS)` to fill. Never use `setPrefWidth(Double.MAX_VALUE)` — it poisons the parent chain.

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
