# Plugin Project Scaffold

A SwissKitJ plugin is a standalone Maven project that produces a fat-JAR. The fastest start is
to copy `assets/plugin-template/` and substitute the placeholders, then read this file for the
why behind each file.

**Placeholders** used throughout:
- `{{base-package}}` — reverse-domain base package, e.g. `com.example.csvsorter`
- `{{base-package-path}}` — same with `/`, e.g. `com/example/csvsorter`
- `{{Name}}` — PascalCase tool name, e.g. `CsvSorter`
- `{{slug}}` — kebab-case id slug, e.g. `csv-sorter`
- `{{plugin-name}}` — Maven artifactId, e.g. `csv-sorter-plugin`

## Project layout

```
{{plugin-name}}/
├── pom.xml
└── src/main/
    ├── java/{{base-package-path}}/
    │   ├── {{Name}}Plugin.java        ← implements SwissKitJPlugin (the SPI entry, single class)
    │   ├── {{Name}}DevApp.java        ← standalone JavaFX Application for `mvn javafx:run -Pdev`
    │   └── DevLauncher.java           ← zero-JavaFX-imports main class (module workaround)
    └── resources/
        ├── META-INF/services/fan.summer.api.SwissKitJPlugin   ← one line: {{base-package}}.{{Name}}Plugin
        └── i18n/
            ├── messages.properties        ← default/English, keys prefixed plugin.{{slug}}.
            └── messages_zh.properties     ← Chinese, same keys
```

Optional packages (only if needed): `database/` (H2+MyBatis), `excel/` (FesodSheet),
`worker/` (background tasks), `service/`, `util/`.

## pom.xml — the essentials

```xml
<project ...>
  <modelVersion>4.0.0</modelVersion>
  <groupId>{{base-package}}</groupId>
  <artifactId>{{plugin-name}}</artifactId>
  <version>1.0.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <javafx.version>21.0.2</javafx.version>
    <swisskit.api.version>3.2.0</swisskit.api.version>
  </properties>

  <dependencies>
    <!-- SwissKitJ API + JavaFX are PROVIDED by the host at runtime -->
    <dependency>
      <groupId>fan.summer.api</groupId>
      <artifactId>SwissKitJ-Api</artifactId>
      <version>${swisskit.api.version}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.openjfx</groupId>
      <artifactId>javafx-controls</artifactId>
      <version>${javafx.version}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.openjfx</groupId>
      <artifactId>javafx-graphics</artifactId>
      <version>${javafx.version}</version>
      <scope>provided</scope>
    </dependency>

    <!-- Plugin-owned deps go at DEFAULT scope so they're shaded into the fat JAR.
         Examples (only add what you actually use):
    <dependency>
      <groupId>com.h2database</groupId> <artifactId>h2</artifactId> <version>2.4.240</version>
    </dependency>
    <dependency>
      <groupId>org.mybatis</groupId> <artifactId>mybatis</artifactId> <version>3.5.19</version>
    </dependency>
    <dependency>
      <groupId>org.apache.fesod</groupId> <artifactId>fesod-sheet</artifactId> <version>2.0.1-incubating</version>
    </dependency>
    -->
  </dependencies>

  <build>
    <finalName>{{plugin-name}}</finalName>
    <plugins>
      <!-- Fat JAR. ServicesResourceTransformer is MANDATORY: it merges SPI files
           from dependency JARs so META-INF/services/fan.summer.api.SwissKitJPlugin
           isn't overwritten during shading. Without it your plugin is invisible. -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.3</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <createDependencyReducedPom>false</createDependencyReducedPom>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>

  <!-- DEV profile: re-adds JavaFX at compile/runtime so `mvn javafx:run -Pdev`
       launches the plugin standalone via DevLauncher. -->
  <profiles>
    <profile>
      <id>dev</id>
      <dependencies>
        <dependency>
          <groupId>org.openjfx</groupId> <artifactId>javafx-controls</artifactId>
          <version>${javafx.version}</version>
        </dependency>
        <dependency>
          <groupId>org.openjfx</groupId> <artifactId>javafx-graphics</artifactId>
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

**Scope rules in one line:** `SwissKitJ-Api` + JavaFX = `provided` (host supplies them); your
own deps (H2, FesodSheet, MyBatis) = default `compile` (shaded into your JAR).

> Note: the older `docs/plugins/scaffold.md` cites `swisskit.api.version=3.0.0`, but the actual
> `SwissKitJ-Api/pom.xml` is `3.2.0` — use **3.2.0**.

## The SPI file (mandatory)

Path: `src/main/resources/META-INF/services/fan.summer.api.SwissKitJPlugin`
(note: `META-INF/services/`, not `services/` at the root).

Content: one line, the FQCN of your plugin class:
```
{{base-package}}.{{Name}}Plugin
```

Verify after build:
```bash
unzip -p target/{{plugin-name}}.jar META-INF/services/fan.summer.api.SwissKitJPlugin
```
This must print your FQCN. If it's empty or wrong, the plugin won't load — check the shade
plugin's `ServicesResourceTransformer`.

## Dev launcher chain (for `mvn javafx:run -Pdev`)

JavaFX + the module system interact awkwardly, so the dev run uses a 3-class chain:

1. **`DevLauncher`** — the `mainClass` for `javafx-maven-plugin`. It has **ZERO JavaFX
   imports** (importing `javafx.application.Application` here triggers
   `NoClassDefFoundError` under the module system). Its only job is to call:
   ```java
   {{Name}}DevApp.main(args);
   ```
2. **`{{Name}}DevApp`** — `extends Application`. Its `main` calls `launch(args)`; `start()`
   builds a `Scene`, applies the theme via `Themes.applyTo(scene)`, and shows your plugin's
   view for offline testing.
3. (Optionally, use `PluginPreviewWindow` instead of a hand-rolled `DevApp` — see below.)

## `PluginPreviewWindow` — the preview harness (recommended for dev)

`SwissKitJ-Api` ships a standalone preview shell that mimics the host (sidebar, search bar,
status bar, detail panel) so you can test your plugin UI without deploying. Use it from your
`DevApp.start()`:

```java
import fan.summer.api.preview.PluginPreviewWindow;

@Override
public void start(Stage stage) throws Exception {
    // Build the preview with your plugin instance (or a JAR via withJar(Path))
    PluginPreviewWindow.configure()
        .withPlugin(new {{Name}}Plugin())
        .title("{{Name}} — dev preview")
        .windowSize(960, 620)
        .showSidebar(true)
        .showSearchBar(true)
        .showStatusBar(true)
        .showDetailPanel(true)
        .launch();   // must be called on the JavaFX Application thread
}
```

Builder methods: `configure()` (entry), `withJar(Path)` / `withPlugin(SwissKitJPlugin)`,
`title(String)`, `windowSize(double, double)` (default 960×620), `showSidebar(boolean)` /
`showSearchBar` / `showStatusBar` / `showDetailPanel` (default true), `launch()`.

This loads `swisskit-common.css` and stamps the theme class via `Themes.applyTo(scene)`, so
what you see in the preview matches what the host renders.

## Build & deploy

```bash
# Dev: run standalone in the preview shell
mvn clean compile -Pdev
mvn javafx:run -Pdev

# Production: build the fat JAR
mvn clean package
# → target/{{plugin-name}}.jar

# Deploy: copy into the host's plugin dir (auto-scanned, hot-reloaded)
cp target/{{plugin-name}}.jar ~/.swisskit/plugin/
```

The host watches `.swisskit/plugin/` for `ENTRY_CREATE/DELETE/MODIFY` and hot-reloads. No
restart needed. To uninstall, delete the JAR (the host fires `onUnload` and unloads it).
