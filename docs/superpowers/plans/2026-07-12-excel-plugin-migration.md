# Excel Plugin Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the 3.2.0 built-in Excel splitter into a 4.0.0 official plugin `plugin-excel` under a new `OfficialPlugins/` aggregator, establishing a reusable web+desktop file I/O standard.

**Architecture:** Backend engine (`ExcelSplitter`) is migrated near-verbatim and only ever sees absolute `Path`s. A host-level `PluginFileController` handles web upload/zip-download into a session workspace; a `PluginContext.desktop` facade exposes native Tauri dialogs. The Vue micro-frontend branches on `ctx.desktop` but ships one bundle. AI tools become Spring AI `@Tool` beans auto-aggregated by the existing `AiToolDiscoveryConfig`.

**Tech Stack:** Java 21 + Spring Boot 4.1 (embedded Tomcat, loopback), Fesod 2.0.1-incubating, Apache POI 5.4.1, Vue 3.5.39 + Vuetify 3 (ESM micro-frontend, `vue` external), Tauri 2.0 (`@tauri-apps/plugin-dialog`), JUnit 5.

## Global Constraints

- Reactor build order: `FengYu-Api` → `OfficialPlugins` (plugin-markdown, plugin-excel) → `FengYu`. API must be installed first.
- All plugin modules declare `FengYu-Api` and `spring-context` as `provided` scope (app provides at runtime via fat JAR).
- Plugin id `fan.summer.excel`; OFFICIAL source requires `fan.summer.` id prefix or it's downgraded to THIRD_PARTY.
- Micro-frontend bundles mark `vue` external, inline CSS, single `index.js`, share host Vuetify via `app.use(ctx.vuetify)`.
- Backend binds `127.0.0.1` only; every non-health/non-setup request carries `X-FengYu-Token`.
- Plugin `invoke` only receives absolute paths — never branches on web/desktop.
- Tool-return JSON contract: success `{success:true, summary, ...}`; error `{success:false, error}`.
- Workspace root: `${java.io.tmpdir}/fengyu/plugin-workspace/{pluginId}/{session}/{in,out}/`. TTL 24h + JVM shutdown-hook sweep.
- Upload limits: `.xlsx`/`.xls` only, single-file cap 100MB. `session` must be a valid UUID; resolved paths must stay under the workspace root (reject `..`).
- Fesod/POI versions come from root `pom.xml` `dependencyManagement` — do not re-declare versions in plugin poms.

---

## Phase A — Aggregator + move plugin-markdown

### Task 1: Create `OfficialPlugins/` aggregator and move plugin-markdown into it

**Files:**
- Create: `OfficialPlugins/pom.xml`
- Move: `plugin-markdown/` → `OfficialPlugins/plugin-markdown/` (via `git mv`)
- Modify: `pom.xml` (root `<modules>`), `OfficialPlugins/plugin-markdown/pom.xml` (`<relativePath>`)

**Interfaces:**
- Produces: reactor module `OfficialPlugins` (packaging `pom`) with child `plugin-markdown`; root reactor references `OfficialPlugins` instead of `plugin-markdown`.

- [ ] **Step 1: Move the markdown module**

```bash
cd /Users/phoebej/Develop/Java/FengYu
mkdir -p OfficialPlugins
git mv plugin-markdown OfficialPlugins/plugin-markdown
```

- [ ] **Step 2: Create the aggregator pom**

Create `OfficialPlugins/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>fan.summer.fengyu</groupId>
    <artifactId>FengYu-parent</artifactId>
    <version>4.0.0-SNAPSHOT</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>OfficialPlugins</artifactId>
  <packaging>pom</packaging>
  <name>FengYu Official Plugins (aggregator)</name>

  <modules>
    <module>plugin-markdown</module>
    <module>plugin-excel</module>
  </modules>
</project>
```

- [ ] **Step 3: Fix markdown's parent relativePath**

In `OfficialPlugins/plugin-markdown/pom.xml`, change `<relativePath>../pom.xml</relativePath>` to `<relativePath>../../pom.xml</relativePath>` (parent is still `FengYu-parent`, now two levels up).

- [ ] **Step 4: Point the root reactor at the aggregator**

In root `pom.xml` `<modules>`, replace `<module>plugin-markdown</module>` with `<module>OfficialPlugins</module>`. Keep order: `FengYu-Api`, `OfficialPlugins`, `FengYu`.

- [ ] **Step 5: Build the reactor to verify the move**

Run: `mvn -q -o install -DskipTests -pl FengYu-Api,OfficialPlugins/plugin-markdown -am` (or full `mvn -q install -DskipTests`).
Expected: BUILD SUCCESS; `plugin-markdown` builds under its new path. The FengYu app dependency on `plugin-markdown` (groupId `fan.summer.fengyu.plugin`, `${revision}`) still resolves — coordinates are unchanged by the move.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "♻️ refactor(plugins): add OfficialPlugins/ aggregator; move plugin-markdown into it"
```

---

## Phase B — FILE category (API + host + i18n)

### Task 2: Add `FILE` ToolCategory end to end

**Files:**
- Modify: `FengYu-Api/src/main/java/fan/summer/fengyu/api/ToolCategory.java`
- Modify: `FengYu/src/main/java/fan/summer/fengyu/web/controller/PluginController.java:63-71` (`iconFor`)
- Modify: `frontend/src/i18n/en.json:3-4`, `frontend/src/i18n/zh.json:3-4`
- Test: `FengYu-Api/src/test/java/fan/summer/fengyu/api/ToolCategoryTest.java`

**Interfaces:**
- Produces: `ToolCategory.FILE` with id `"file"`, labelKey `"category.file"`; `iconFor("file")` returns a glyph; i18n `category.file` in both locales.

- [ ] **Step 1: Write the failing test**

Create `FengYu-Api/src/test/java/fan/summer/fengyu/api/ToolCategoryTest.java`:

```java
package fan.summer.fengyu.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolCategoryTest {
    @Test
    void fileCategoryExistsWithIdAndLabelKey() {
        ToolCategory file = ToolCategory.FILE;
        assertEquals("file", file.getId());
        assertEquals("category.file", file.getLabelKey());
    }

    @Test
    void fromIdResolvesFile() {
        assertEquals(ToolCategory.FILE, ToolCategory.fromId("file"));
        assertEquals(ToolCategory.FILE, ToolCategory.fromId("FILE"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -o -pl FengYu-Api test -Dtest=ToolCategoryTest`
Expected: FAIL — `FILE` symbol does not exist (compile error).

- [ ] **Step 3: Add the enum value**

In `ToolCategory.java`, add after the `NET(...)` constant (before `AI`), matching the existing Javadoc style:

```java
    /** File-processing tools such as document splitters, converters, and archivers. */
    FILE("file", "category.file"),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -o -pl FengYu-Api test -Dtest=ToolCategoryTest`
Expected: PASS.

- [ ] **Step 5: Wire host icon + i18n**

In `PluginController.iconFor`, add a case inside the switch:

```java
            case "file" -> "🗄";
```

In `frontend/src/i18n/en.json` line 3-4, add `"file": "File",` inside the `"category"` object (e.g. after `"net": "Network",`):

```json
  "category": { "dev": "Developer", "text": "Text", "image": "Image",
                "net": "Network", "file": "File", "ai": "AI", "other": "Other" },
```

In `frontend/src/i18n/zh.json` line 3-4:

```json
  "category": { "dev": "开发者", "text": "文本", "image": "图像",
                "net": "网络", "file": "文件", "ai": "AI", "other": "其他" },
```

- [ ] **Step 6: Commit**

```bash
git add FengYu-Api/src/main/java/fan/summer/fengyu/api/ToolCategory.java \
        FengYu-Api/src/test/java/fan/summer/fengyu/api/ToolCategoryTest.java \
        FengYu/src/main/java/fan/summer/fengyu/web/controller/PluginController.java \
        frontend/src/i18n/en.json frontend/src/i18n/zh.json
git commit -m "✨ feat(api): add FILE tool category (host icon + i18n)"
```

## Phase C — plugin-excel module scaffold + engine migration

### Task 3: Scaffold `plugin-excel` Maven module

**Files:**
- Create: `OfficialPlugins/plugin-excel/pom.xml`
- Create: `OfficialPlugins/plugin-excel/src/main/resources/ui/excel/.gitkeep`

**Interfaces:**
- Produces: Maven module `plugin-excel` (groupId `fan.summer.fengyu.plugin`) with engine deps; builds empty for now.

- [ ] **Step 1: Create the pom**

Create `OfficialPlugins/plugin-excel/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>fan.summer.fengyu</groupId>
    <artifactId>FengYu-parent</artifactId>
    <version>4.0.0-SNAPSHOT</version>
    <relativePath>../../pom.xml</relativePath>
  </parent>

  <groupId>fan.summer.fengyu.plugin</groupId>
  <artifactId>plugin-excel</artifactId>
  <name>FengYu Plugin — Excel Splitter</name>
  <description>Official Excel splitting plugin (v2, headless): BY_SHEET/BY_COLUMN/COMPLEX
    modes + micro-frontend wizard + Spring AI tools.</description>

  <dependencies>
    <dependency>
      <groupId>fan.summer.fengyu.api</groupId>
      <artifactId>FengYu-Api</artifactId>
      <version>${revision}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework</groupId>
      <artifactId>spring-context</artifactId>
      <version>7.0.8</version>
      <scope>provided</scope>
    </dependency>
    <!-- Spring AI @Tool annotation (provided by app at runtime) -->
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-core</artifactId>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.apache.fesod</groupId>
      <artifactId>fesod-sheet</artifactId>
    </dependency>
    <dependency>
      <groupId>org.apache.poi</groupId>
      <artifactId>poi-ooxml</artifactId>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

NOTE: verify the `spring-ai-core` artifactId + that it (and `poi-ooxml`) are in root `dependencyManagement`. If the AI-core coordinates differ, copy the exact groupId/artifactId used by `FengYu/pom.xml` for Spring AI. If `poi-ooxml` has no managed version, add `<version>${poi.version}</version>`.

- [ ] **Step 2: Keep the ui resources dir tracked**

```bash
mkdir -p OfficialPlugins/plugin-excel/src/main/resources/ui/excel
touch OfficialPlugins/plugin-excel/src/main/resources/ui/excel/.gitkeep
```

- [ ] **Step 3: Build the empty module**

Run: `mvn -q -o install -DskipTests -pl OfficialPlugins/plugin-excel -am`
Expected: BUILD SUCCESS (no sources yet).

- [ ] **Step 4: Commit**

```bash
git add OfficialPlugins/plugin-excel/pom.xml OfficialPlugins/plugin-excel/src/main/resources/ui/excel/.gitkeep
git commit -m "✨ feat(plugin-excel): scaffold Maven module"
```

### Task 4: Migrate engine support classes (verbatim, repackaged)

**Files:**
- Create: `OfficialPlugins/plugin-excel/src/main/java/fan/summer/fengyu/plugin/excel/FileNameUtil.java`
- Create: `.../plugin/excel/NoModelDataListener.java`
- Create: `.../plugin/excel/ExcelUtil.java`
- Test: `.../plugin/excel/FileNameUtilTest.java`

**Interfaces:**
- Produces:
  - `FileNameUtil.getFileName(String) -> String` (strips extension).
  - `NoModelDataListener extends AnalysisEventListener<Map<Integer,Object>>`; `getCachedDataList() -> List<Map<Integer,Object>>`; `clear()`.
  - `ExcelUtil.normalizeOrInvalid(Object) -> String`; `copyHeaderToWorkbook(Sheet, Workbook, String, int)`; `writeDataRowsToSheet(Sheet, Workbook, Row, int, List<Map<Integer,Object>>)`; `copySheetToWorkbook(Sheet, Workbook)`.

- [ ] **Step 1: Copy the three classes from v3.2.0, changing only the package**

For each, run `git show v3.2.0:SwissKit/src/main/java/fan/summer/buildintool/excelsplitter/<Name>.java`, then write to the new path with the package line changed to `package fan.summer.fengyu.plugin.excel;`. Remove the `@since 3.0.0` lines is optional; keep the rest identical.

```bash
cd /Users/phoebej/Develop/Java/FengYu
D=OfficialPlugins/plugin-excel/src/main/java/fan/summer/fengyu/plugin/excel
mkdir -p "$D"
for f in FileNameUtil NoModelDataListener ExcelUtil; do
  git show "v3.2.0:SwissKit/src/main/java/fan/summer/buildintool/excelsplitter/$f.java" \
    | sed '1s#^package .*#package fan.summer.fengyu.plugin.excel;#' > "$D/$f.java"
done
```

- [ ] **Step 2: Write the failing test**

Create `.../plugin/excel/FileNameUtilTest.java`:

```java
package fan.summer.fengyu.plugin.excel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FileNameUtilTest {
    @Test
    void stripsExtension() {
        assertEquals("report_2024_Q1", FileNameUtil.getFileName("report_2024_Q1.xlsx"));
        assertEquals("data", FileNameUtil.getFileName("data.csv"));
        assertEquals("archive", FileNameUtil.getFileName("archive"));
    }
}
```

- [ ] **Step 3: Run tests to verify compile + pass**

Run: `mvn -q -o -pl OfficialPlugins/plugin-excel test -Dtest=FileNameUtilTest`
Expected: PASS. This also confirms `ExcelUtil`/`NoModelDataListener` compile against Fesod/POI on the classpath.

- [ ] **Step 4: Commit**

```bash
git add OfficialPlugins/plugin-excel/src/main/java/fan/summer/fengyu/plugin/excel/
git commit -m "✨ feat(plugin-excel): migrate engine support classes (FileNameUtil, ExcelUtil, NoModelDataListener)"
```

### Task 5: Migrate `SplitConfig` + add COMPLEX in-memory entries

**Files:**
- Create: `.../plugin/excel/ComplexSplitEntry.java`
- Create: `.../plugin/excel/SplitConfig.java`

**Interfaces:**
- Produces:
  - `record ComplexSplitEntry(String fieldName, String sheetName, int headerIndex, int columnIndex)` — `headerIndex==-1 && columnIndex==-1` means copy-entire-sheet.
  - `SplitConfig` with public fields: `Path sourceFile`; `Map<String,Map<Integer,String>> analysisResult`; `SplitMode mode` (enum `BY_SHEET/BY_COLUMN/COMPLEX`, default BY_SHEET); `List<String> selectedSheets`; `String splitSheet`; `String splitColumn`; `int splitColumnIndex=-1`; `List<ComplexSplitEntry> complexEntries = new ArrayList<>()`; `Path outputDir`; `String filePrefix=""`.

- [ ] **Step 1: Create ComplexSplitEntry**

```java
package fan.summer.fengyu.plugin.excel;

/**
 * One COMPLEX-mode split rule, held in memory on the session's {@link SplitConfig}
 * (replaces the 3.2.0 DB-backed {@code ComplexSplitConfigEntity}).
 *
 * @param fieldName    original source filename (informational)
 * @param sheetName    sheet this rule applies to
 * @param headerIndex  1-based header row; {@code -1} with columnIndex {@code -1} = copy entire sheet
 * @param columnIndex  1-based column to split by; {@code -1} with headerIndex {@code -1} = copy entire sheet
 */
public record ComplexSplitEntry(String fieldName, String sheetName, int headerIndex, int columnIndex) {}
```

- [ ] **Step 2: Create SplitConfig**

Copy v3.2.0 `SplitConfig` (package changed), and replace the `complexTaskId` field with `public List<ComplexSplitEntry> complexEntries = new ArrayList<>();`. Update `toDebugString()` to print `complexEntries.size()` instead of `complexTaskId`. Keep `SplitMode` enum inline.

```java
package fan.summer.fengyu.plugin.excel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parameters for one split operation, populated progressively by the UI/AI tools. */
public class SplitConfig {
    public enum SplitMode { BY_SHEET, BY_COLUMN, COMPLEX }

    public Path sourceFile;
    public Map<String, Map<Integer, String>> analysisResult;
    public SplitMode mode = SplitMode.BY_SHEET;
    public List<String> selectedSheets = new ArrayList<>();
    public String splitSheet;
    public String splitColumn;
    public int splitColumnIndex = -1;
    public List<ComplexSplitEntry> complexEntries = new ArrayList<>();
    public Path outputDir;
    public String filePrefix = "";

    public String toDebugString() {
        return "SplitConfig{sourceFile=" + sourceFile + ", mode=" + mode
             + ", selectedSheets=" + selectedSheets.size() + ", splitSheet=" + splitSheet
             + ", splitColumn=" + splitColumn + ", splitColumnIndex=" + splitColumnIndex
             + ", complexEntries=" + complexEntries.size() + ", outputDir=" + outputDir
             + ", filePrefix='" + filePrefix + "'}";
    }
}
```

- [ ] **Step 3: Compile**

Run: `mvn -q -o -pl OfficialPlugins/plugin-excel test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add OfficialPlugins/plugin-excel/src/main/java/fan/summer/fengyu/plugin/excel/ComplexSplitEntry.java \
        OfficialPlugins/plugin-excel/src/main/java/fan/summer/fengyu/plugin/excel/SplitConfig.java
git commit -m "✨ feat(plugin-excel): migrate SplitConfig; COMPLEX config held in memory"
```

### Task 6: Migrate `ExcelSplitter` engine (DB → in-memory COMPLEX)

**Files:**
- Create: `.../plugin/excel/ExcelSplitter.java`
- Test: `.../plugin/excel/ExcelSplitterTest.java`
- Test resource: generated in-test via POI (no fixture file needed)

**Interfaces:**
- Consumes: `SplitConfig`, `ComplexSplitEntry`, `ExcelUtil`, `FileNameUtil`, `NoModelDataListener`.
- Produces:
  - `static Map<String,Map<Integer,String>> ExcelSplitter.analyze(Path) throws Exception`.
  - `new ExcelSplitter(SplitConfig, BiConsumer<Double,String> progress)`.
  - `SplitResult split() throws Exception` where `record SplitResult(int fileCount, List<Path> outputFiles)`.

- [ ] **Step 1: Copy the engine, repackaged, with COMPLEX reading from memory**

Start from `git show v3.2.0:SwissKit/src/main/java/fan/summer/buildintool/excelsplitter/ExcelSplitter.java`. Apply these edits:
1. `package fan.summer.fengyu.plugin.excel;`
2. Remove imports: `fan.summer.database.DatabaseInit`, `...ComplexSplitConfigEntity`, `...ComplexSplitConfigMapper`, `org.apache.ibatis.session.SqlSession`.
3. In `complexSplit()`, replace the DB fetch block:

```java
        List<ComplexSplitConfigEntity> splitConfigs;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            ComplexSplitConfigMapper mapper = session.getMapper(ComplexSplitConfigMapper.class);
            splitConfigs = mapper.selectAllByTaskId(config.complexTaskId);
        }
        if (splitConfigs == null || splitConfigs.isEmpty()) {
            throw new RuntimeException("No complex split config found for taskId: " + config.complexTaskId);
        }
```

   with:

```java
        List<ComplexSplitEntry> splitConfigs = config.complexEntries;
        if (splitConfigs == null || splitConfigs.isEmpty()) {
            throw new RuntimeException("No complex split entries configured");
        }
```

4. Change the two typed lists + loop to use `ComplexSplitEntry` and its accessors (`.headerIndex()`, `.columnIndex()`, `.sheetName()`) instead of `ComplexSplitConfigEntity` getters (`.getHeaderIndex()` etc.). Since accessors return `int` (not `Integer`), simplify the copy-all test to `cfg.headerIndex() == -1 && cfg.columnIndex() == -1`. Everywhere the old code called `cfg.getSheetName()` / `cfg.getHeaderIndex()` / `cfg.getColumnIndex()`, use `cfg.sheetName()` / `cfg.headerIndex()` / `cfg.columnIndex()`.
5. Keep `splitBySheet()`, `splitByColumn()`, `analyze()`, `buildHeaders()`, `buildRows()`, `outputFileName()` unchanged except package.

- [ ] **Step 2: Write the failing test**

Create `.../plugin/excel/ExcelSplitterTest.java`. It builds a 2-sheet workbook with POI in a `@TempDir`, then exercises analyze + BY_SHEET + BY_COLUMN:

```java
package fan.summer.fengyu.plugin.excel;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExcelSplitterTest {

    @TempDir Path tmp;
    Path src;

    @BeforeEach
    void setUp() throws Exception {
        src = tmp.resolve("in.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(src.toFile())) {
            Sheet s1 = wb.createSheet("Alpha");
            Row h1 = s1.createRow(0); h1.createCell(0).setCellValue("region"); h1.createCell(1).setCellValue("v");
            String[] regions = {"east", "west", "east"};
            for (int i = 0; i < regions.length; i++) {
                Row r = s1.createRow(i + 1); r.createCell(0).setCellValue(regions[i]); r.createCell(1).setCellValue(i);
            }
            Sheet s2 = wb.createSheet("Beta");
            s2.createRow(0).createCell(0).setCellValue("name");
            s2.createRow(1).createCell(0).setCellValue("x");
            wb.write(fos);
        }
    }

    @Test
    void analyzeReturnsSheetsAndHeaders() throws Exception {
        Map<String, Map<Integer, String>> r = ExcelSplitter.analyze(src);
        assertEquals(List.of("Alpha", "Beta"), new ArrayList<>(r.keySet()));
        assertEquals("region", r.get("Alpha").get(0));
        assertEquals("name", r.get("Beta").get(0));
    }

    @Test
    void bySheetProducesOneFilePerSheet() throws Exception {
        SplitConfig c = new SplitConfig();
        c.sourceFile = src;
        c.analysisResult = ExcelSplitter.analyze(src);
        c.mode = SplitConfig.SplitMode.BY_SHEET;
        c.outputDir = Files.createDirectories(tmp.resolve("out1"));
        var res = new ExcelSplitter(c, null).split();
        assertEquals(2, res.fileCount());
        assertTrue(Files.exists(c.outputDir.resolve("Alpha.xlsx")));
        assertTrue(Files.exists(c.outputDir.resolve("Beta.xlsx")));
    }

    @Test
    void byColumnGroupsByUniqueValue() throws Exception {
        SplitConfig c = new SplitConfig();
        c.sourceFile = src;
        c.analysisResult = ExcelSplitter.analyze(src);
        c.mode = SplitConfig.SplitMode.BY_COLUMN;
        c.splitSheet = "Alpha";
        c.splitColumnIndex = 0;
        c.outputDir = Files.createDirectories(tmp.resolve("out2"));
        var res = new ExcelSplitter(c, null).split();
        assertEquals(2, res.fileCount()); // east, west
    }
}
```

- [ ] **Step 3: Run tests to verify they pass**

Run: `mvn -q -o -pl OfficialPlugins/plugin-excel test -Dtest=ExcelSplitterTest`
Expected: PASS (3 tests). If BY_COLUMN filename uses the split key, files are `in_east.xlsx`/`in_west.xlsx` — assert on count, not exact names, as written.

- [ ] **Step 4: Commit**

```bash
git add OfficialPlugins/plugin-excel/src/main/java/fan/summer/fengyu/plugin/excel/ExcelSplitter.java \
        OfficialPlugins/plugin-excel/src/test/java/fan/summer/fengyu/plugin/excel/ExcelSplitterTest.java
git commit -m "✨ feat(plugin-excel): migrate ExcelSplitter engine; COMPLEX reads in-memory entries"
```

### Task 7: `ExcelSessionStore` (session → SplitConfig, with active-session tracking)

**Files:**
- Create: `.../plugin/excel/ExcelSessionStore.java`
- Test: `.../plugin/excel/ExcelSessionStoreTest.java`

**Interfaces:**
- Produces (Spring `@Component`):
  - `SplitConfig get(String session)` — creates + stores a fresh `SplitConfig` if absent; marks it active.
  - `Optional<SplitConfig> active()` — the most recently touched session's config (for AI tools).
  - `void markActive(String session)`; `void remove(String session)`.

- [ ] **Step 1: Write the failing test**

```java
package fan.summer.fengyu.plugin.excel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExcelSessionStoreTest {
    @Test
    void getCreatesAndReuses() {
        ExcelSessionStore s = new ExcelSessionStore();
        SplitConfig a = s.get("sess-1");
        assertSame(a, s.get("sess-1"));
    }

    @Test
    void activeTracksMostRecent() {
        ExcelSessionStore s = new ExcelSessionStore();
        s.get("sess-1");
        SplitConfig second = s.get("sess-2");
        assertTrue(s.active().isPresent());
        assertSame(second, s.active().get());
    }

    @Test
    void removeDropsSession() {
        ExcelSessionStore s = new ExcelSessionStore();
        s.get("sess-1");
        s.remove("sess-1");
        assertTrue(s.active().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -o -pl OfficialPlugins/plugin-excel test -Dtest=ExcelSessionStoreTest`
Expected: FAIL — `ExcelSessionStore` does not exist.

- [ ] **Step 3: Implement**

```java
package fan.summer.fengyu.plugin.excel;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory per-session {@link SplitConfig} store; also tracks the most-recently-touched
 *  session so stateless AI tools can operate on the "current" workflow. */
@Component
public class ExcelSessionStore {

    private final ConcurrentHashMap<String, SplitConfig> byId = new ConcurrentHashMap<>();
    private final AtomicReference<String> activeId = new AtomicReference<>();

    public SplitConfig get(String session) {
        SplitConfig c = byId.computeIfAbsent(session, k -> new SplitConfig());
        activeId.set(session);
        return c;
    }

    public void markActive(String session) { activeId.set(session); }

    public Optional<SplitConfig> active() {
        String id = activeId.get();
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public void remove(String session) {
        byId.remove(session);
        activeId.compareAndSet(session, null);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -o -pl OfficialPlugins/plugin-excel test -Dtest=ExcelSessionStoreTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/plugin-excel/src/main/java/fan/summer/fengyu/plugin/excel/ExcelSessionStore.java \
        OfficialPlugins/plugin-excel/src/test/java/fan/summer/fengyu/plugin/excel/ExcelSessionStoreTest.java
git commit -m "✨ feat(plugin-excel): ExcelSessionStore with active-session tracking"
```

### Task 8: `ExcelPlugin` (FengYuPlugin invoke: analyze/configure/split)

**Files:**
- Create: `.../plugin/excel/ExcelPlugin.java`
- Create: `OfficialPlugins/plugin-excel/src/main/resources/META-INF/services/...` (NOT needed — bean is `@Component`, picked up by scan; skip SPI)
- Test: `.../plugin/excel/ExcelPluginTest.java`

**Interfaces:**
- Consumes: `ExcelSessionStore`, `ExcelSplitter`, `SplitConfig`, `ComplexSplitEntry`.
- Produces (Spring `@Component implements FengYuPlugin`):
  - `descriptor()` → id `fan.summer.excel`, category `ToolCategory.FILE`, icon `"file-excel"`, iconStyle `IconStyle.TEAL`, version `"4.0.0"`, uiEntry `"/plugin-ui/excel/index.js"`, supportsAi `true`, source `PluginSource.OFFICIAL`.
  - `invoke("analyze", {session, sourceFile})` → `{success, summary, sheets}` where `sheets` is `Map<String,Map<String,String>>` (colIndex stringified for JSON).
  - `invoke("configure", {session, mode, selectedSheets?, splitSheet?, splitColumnIndex?, filePrefix?, complexEntries?})` → `{success, summary}`.
  - `invoke("split", {session, sourceFile, outputDir})` → `{success, summary, fileCount, files}`.

- [ ] **Step 1: Write the failing test**

```java
package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.api.ToolCategory;
import fan.summer.fengyu.api.plugin.PluginDescriptor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExcelPluginTest {
    @TempDir Path tmp;
    Path src;
    ExcelPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        plugin = new ExcelPlugin(new ExcelSessionStore());
        src = tmp.resolve("in.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(src.toFile())) {
            Sheet s = wb.createSheet("Alpha");
            s.createRow(0).createCell(0).setCellValue("region");
            s.createRow(1).createCell(0).setCellValue("east");
            wb.write(fos);
        }
    }

    @Test
    void descriptorIsOfficialFileCategory() {
        PluginDescriptor d = plugin.descriptor();
        assertEquals("fan.summer.excel", d.id());
        assertEquals(ToolCategory.FILE, d.category());
        assertEquals("/plugin-ui/excel/index.js", d.uiEntry());
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeThenSplitBySheet() throws Exception {
        String sess = "s1";
        Map<String, Object> a = (Map<String, Object>) plugin.invoke("analyze",
            Map.of("session", sess, "sourceFile", src.toString()));
        assertEquals(Boolean.TRUE, a.get("success"));

        plugin.invoke("configure", Map.of("session", sess, "mode", "BY_SHEET"));

        Path out = Files.createDirectories(tmp.resolve("out"));
        Map<String, Object> r = (Map<String, Object>) plugin.invoke("split",
            Map.of("session", sess, "sourceFile", src.toString(), "outputDir", out.toString()));
        assertEquals(Boolean.TRUE, r.get("success"));
        assertEquals(1, ((Number) r.get("fileCount")).intValue());
    }

    @Test
    void unknownActionThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> plugin.invoke("bogus", Map.of()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -o -pl OfficialPlugins/plugin-excel test -Dtest=ExcelPluginTest`
Expected: FAIL — `ExcelPlugin` does not exist.

- [ ] **Step 3: Implement ExcelPlugin**

```java
package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.api.IconStyle;
import fan.summer.fengyu.api.ToolCategory;
import fan.summer.fengyu.api.plugin.PluginDescriptor;
import fan.summer.fengyu.api.plugin.PluginSource;
import fan.summer.fengyu.api.plugin.FengYuPlugin;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Component
public class ExcelPlugin implements FengYuPlugin {

    private static final String ID = "fan.summer.excel";
    private final ExcelSessionStore sessions;

    public ExcelPlugin(ExcelSessionStore sessions) { this.sessions = sessions; }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(ID, "Excel Splitter",
            "Split Excel workbooks by sheet, by column value, or via multi-rule complex config",
            ToolCategory.FILE, "file-excel", IconStyle.TEAL, "4.0.0",
            "/plugin-ui/excel/index.js", true, PluginSource.OFFICIAL);
    }

    @Override
    public Object invoke(String action, Map<String, Object> args) {
        String session = str(args, "session");
        if (session == null || session.isBlank()) {
            throw new IllegalArgumentException("session is required");
        }
        return switch (action) {
            case "analyze"   -> analyze(session, args);
            case "configure" -> configure(session, args);
            case "split"     -> split(session, args);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private Map<String, Object> analyze(String session, Map<String, Object> args) {
        Path file = requirePath(args, "sourceFile");
        SplitConfig cfg = sessions.get(session);
        cfg.sourceFile = file;
        try {
            cfg.analysisResult = ExcelSplitter.analyze(file);
        } catch (Exception e) {
            throw new IllegalArgumentException("Analyze failed: " + e.getMessage(), e);
        }
        Map<String, Map<String, String>> sheets = new LinkedHashMap<>();
        cfg.analysisResult.forEach((name, cols) -> {
            Map<String, String> m = new LinkedHashMap<>();
            cols.forEach((idx, header) -> m.put(String.valueOf(idx), header));
            sheets.put(name, m);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("summary", "analyzed " + sheets.size() + " sheet(s)");
        out.put("sheets", sheets);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> configure(String session, Map<String, Object> args) {
        SplitConfig cfg = sessions.get(session);
        String mode = str(args, "mode");
        if (mode != null) cfg.mode = SplitConfig.SplitMode.valueOf(mode);
        Object sel = args.get("selectedSheets");
        if (sel instanceof List<?> l) cfg.selectedSheets = l.stream().map(String::valueOf).toList();
        if (args.get("splitSheet") != null) cfg.splitSheet = str(args, "splitSheet");
        if (args.get("splitColumn") != null) cfg.splitColumn = str(args, "splitColumn");
        if (args.get("splitColumnIndex") != null) cfg.splitColumnIndex = num(args, "splitColumnIndex");
        if (args.get("filePrefix") != null) cfg.filePrefix = str(args, "filePrefix");
        Object entries = args.get("complexEntries");
        if (entries instanceof List<?> l) {
            List<ComplexSplitEntry> parsed = new ArrayList<>();
            for (Object o : l) {
                Map<String, Object> m = (Map<String, Object>) o;
                parsed.add(new ComplexSplitEntry(
                    String.valueOf(m.getOrDefault("fieldName", "")),
                    String.valueOf(m.get("sheetName")),
                    ((Number) m.getOrDefault("headerIndex", -1)).intValue(),
                    ((Number) m.getOrDefault("columnIndex", -1)).intValue()));
            }
            cfg.complexEntries = parsed;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("summary", "configured mode=" + cfg.mode);
        return out;
    }

    private Map<String, Object> split(String session, Map<String, Object> args) {
        SplitConfig cfg = sessions.get(session);
        cfg.sourceFile = requirePath(args, "sourceFile");
        cfg.outputDir = requirePath(args, "outputDir");
        if (cfg.analysisResult == null) {
            try { cfg.analysisResult = ExcelSplitter.analyze(cfg.sourceFile); }
            catch (Exception e) { throw new IllegalArgumentException("Analyze failed: " + e.getMessage(), e); }
        }
        ExcelSplitter.SplitResult res;
        try {
            res = new ExcelSplitter(cfg, null).split();
        } catch (Exception e) {
            throw new IllegalArgumentException("Split failed: " + e.getMessage(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("summary", "wrote " + res.fileCount() + " file(s)");
        out.put("fileCount", res.fileCount());
        out.put("files", res.outputFiles().stream().map(p -> p.getFileName().toString()).toList());
        return out;
    }

    private static String str(Map<String, Object> args, String k) {
        Object v = args == null ? null : args.get(k);
        return v == null ? null : v.toString();
    }
    private static int num(Map<String, Object> args, String k) {
        return ((Number) args.get(k)).intValue();
    }
    private static Path requirePath(Map<String, Object> args, String k) {
        String v = str(args, k);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(k + " is required");
        return Paths.get(v);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -o -pl OfficialPlugins/plugin-excel test -Dtest=ExcelPluginTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/plugin-excel/src/main/java/fan/summer/fengyu/plugin/excel/ExcelPlugin.java \
        OfficialPlugins/plugin-excel/src/test/java/fan/summer/fengyu/plugin/excel/ExcelPluginTest.java
git commit -m "✨ feat(plugin-excel): ExcelPlugin invoke (analyze/configure/split)"
```

## Phase D — Host file I/O standard + app wiring

### Task 9: `PluginWorkspaceService` (session workspace + zip + TTL)

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/workspace/PluginWorkspaceService.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/plugin/workspace/PluginWorkspaceServiceTest.java`

**Interfaces:**
- Produces (Spring `@Service`):
  - `String newSession()` → random UUID string.
  - `Path inDir(String pluginId, String session)` / `Path outDir(String pluginId, String session)` — creates dirs; validates pluginId + session are safe tokens.
  - `Path store(String pluginId, String session, String filename, InputStream data) throws IOException` — writes into `in/`, returns absolute path; rejects filenames containing `/`, `\`, or `..`.
  - `void zipDir(Path dir, OutputStream out) throws IOException` — zips a directory's files (flat).
  - `void remove(String pluginId, String session)` — recursive delete of the session dir.
  - `void sweep(Duration ttl)` — delete session dirs older than ttl; also runs on shutdown.

- [ ] **Step 1: Write the failing test**

```java
package fan.summer.fengyu.plugin.workspace;

import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

class PluginWorkspaceServiceTest {

    PluginWorkspaceService svc;

    @BeforeEach
    void setUp() { svc = new PluginWorkspaceService(); }

    @Test
    void storeAndZip() throws Exception {
        String id = "fan.summer.excel";
        String sess = svc.newSession();
        Path stored = svc.store(id, sess, "a.txt",
            new ByteArrayInputStream("hello".getBytes()));
        assertTrue(Files.exists(stored));
        assertTrue(stored.startsWith(svc.inDir(id, sess)));

        Files.writeString(svc.outDir(id, sess).resolve("r.xlsx"), "data");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        svc.zipDir(svc.outDir(id, sess), bos);
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            ZipEntry e = zis.getNextEntry();
            assertNotNull(e);
            assertEquals("r.xlsx", e.getName());
        }
        svc.remove(id, sess);
        assertFalse(Files.exists(svc.inDir(id, sess).getParent()));
    }

    @Test
    void rejectsTraversalFilename() {
        String sess = svc.newSession();
        assertThrows(IllegalArgumentException.class,
            () -> svc.store("fan.summer.excel", sess, "../evil.txt",
                new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void rejectsBadSession() {
        assertThrows(IllegalArgumentException.class,
            () -> svc.inDir("fan.summer.excel", "../../etc"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -o -pl FengYu test -Dtest=PluginWorkspaceServiceTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

```java
package fan.summer.fengyu.plugin.workspace;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.*;

/** Manages per-plugin per-session file workspaces under the OS temp dir, plus zip packaging
 *  and TTL cleanup. Backs the web upload/download path of the plugin file I/O standard. */
@Service
public class PluginWorkspaceService {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private final Path root = Paths.get(System.getProperty("java.io.tmpdir"), "fengyu", "plugin-workspace");

    public String newSession() { return UUID.randomUUID().toString(); }

    private void checkToken(String s, String what) {
        if (s == null || !SAFE_ID.matcher(s).matches()) {
            throw new IllegalArgumentException("Invalid " + what + ": " + s);
        }
    }

    private Path sessionRoot(String pluginId, String session) {
        checkToken(pluginId, "pluginId");
        checkToken(session, "session");
        return root.resolve(pluginId).resolve(session);
    }

    public Path inDir(String pluginId, String session) {
        return ensure(sessionRoot(pluginId, session).resolve("in"));
    }

    public Path outDir(String pluginId, String session) {
        return ensure(sessionRoot(pluginId, session).resolve("out"));
    }

    private static Path ensure(Path p) {
        try { Files.createDirectories(p); } catch (IOException e) { throw new UncheckedIOException(e); }
        return p;
    }

    public Path store(String pluginId, String session, String filename, InputStream data) throws IOException {
        if (filename == null || filename.isBlank()
                || filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new IllegalArgumentException("Unsafe filename: " + filename);
        }
        Path target = inDir(pluginId, session).resolve(filename);
        try (data) { Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING); }
        return target;
    }

    public void zipDir(Path dir, OutputStream out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            if (!Files.isDirectory(dir)) return;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    if (!Files.isRegularFile(p)) continue;
                    zos.putNextEntry(new ZipEntry(p.getFileName().toString()));
                    Files.copy(p, zos);
                    zos.closeEntry();
                }
            }
        }
    }

    public void remove(String pluginId, String session) {
        deleteRecursive(sessionRoot(pluginId, session));
    }

    public void sweep(Duration ttl) {
        if (!Files.isDirectory(root)) return;
        Instant cutoff = Instant.now().minus(ttl);
        try (var pluginDirs = Files.newDirectoryStream(root)) {
            for (Path pd : pluginDirs) {
                try (var sessDirs = Files.newDirectoryStream(pd)) {
                    for (Path sd : sessDirs) {
                        BasicFileAttributes a = Files.readAttributes(sd, BasicFileAttributes.class);
                        if (a.lastModifiedTime().toInstant().isBefore(cutoff)) deleteRecursive(sd);
                    }
                }
            }
        } catch (IOException ignored) { }
    }

    @PreDestroy
    public void shutdown() { deleteRecursive(root); }

    private static void deleteRecursive(Path p) {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) {} });
        } catch (IOException ignored) { }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -o -pl FengYu test -Dtest=PluginWorkspaceServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/plugin/workspace/PluginWorkspaceService.java \
        FengYu/src/test/java/fan/summer/fengyu/plugin/workspace/PluginWorkspaceServiceTest.java
git commit -m "✨ feat(host): PluginWorkspaceService — session workspace, zip, TTL sweep"
```

### Task 10: `PluginFileController` (upload / archive / delete) + scheduled sweep

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/web/controller/PluginFileController.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/workspace/WorkspaceSweepJob.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/web/controller/PluginFileControllerTest.java` (MockMvc / standalone)

**Interfaces:**
- Consumes: `PluginWorkspaceService`, `PluginRegistryService` (to reject unknown plugin ids).
- Produces:
  - `POST /api/plugins/{id}/files` (multipart `file`, optional `session`) → `{session, files:[{name, path}]}`.
  - `GET /api/plugins/{id}/files/archive?session=&dir=out` → `application/zip` stream, `Content-Disposition: attachment; filename="results.zip"`.
  - `DELETE /api/plugins/{id}/files?session=` → `{success:true}`.

- [ ] **Step 1: Write the failing test (standalone MockMvc)**

```java
package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.PluginRegistryService;
import fan.summer.fengyu.plugin.workspace.PluginWorkspaceService;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PluginFileControllerTest {

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        PluginRegistryService registry = mock(PluginRegistryService.class);
        when(registry.find("fan.summer.excel")).thenReturn(java.util.Optional.of(mock(fan.summer.fengyu.api.plugin.FengYuPlugin.class)));
        mvc = MockMvcBuilders.standaloneSetup(
            new PluginFileController(new PluginWorkspaceService(), registry)).build();
    }

    @Test
    void uploadReturnsSessionAndPath() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "in.xlsx",
            "application/octet-stream", "x".getBytes());
        mvc.perform(multipart("/api/plugins/fan.summer.excel/files").file(f))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.session").isNotEmpty())
           .andExpect(jsonPath("$.files[0].name").value("in.xlsx"))
           .andExpect(jsonPath("$.files[0].path").isNotEmpty());
    }

    @Test
    void uploadUnknownPluginIs404() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "in.xlsx", null, "x".getBytes());
        mvc.perform(multipart("/api/plugins/does.not.exist/files").file(f))
           .andExpect(status().isNotFound());
    }

    @Test
    void rejectsBadExtension() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "in.exe", null, "x".getBytes());
        mvc.perform(multipart("/api/plugins/fan.summer.excel/files").file(f))
           .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -o -pl FengYu test -Dtest=PluginFileControllerTest`
Expected: FAIL — controller does not exist.

- [ ] **Step 3: Implement the controller**

```java
package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.PluginRegistryService;
import fan.summer.fengyu.plugin.workspace.PluginWorkspaceService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Generic plugin file I/O (FengYu Plugin File I/O Standard v1): multipart upload into a
 *  session workspace, zip download of results, and session cleanup. Plugin-agnostic. */
@RestController
public class PluginFileController {

    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of(".xlsx", ".xls");

    private final PluginWorkspaceService workspace;
    private final PluginRegistryService registry;

    public PluginFileController(PluginWorkspaceService workspace, PluginRegistryService registry) {
        this.workspace = workspace;
        this.registry = registry;
    }

    @PostMapping("/api/plugins/{id}/files")
    public ResponseEntity<Object> upload(@PathVariable String id,
                                         @RequestParam(value = "session", required = false) String session,
                                         @RequestParam("file") MultipartFile file) throws IOException {
        if (registry.find(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("success", false, "error", "Unknown plugin id: " + id));
        }
        String name = file.getOriginalFilename();
        if (name == null || !hasAllowedExt(name)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Only .xlsx/.xls allowed"));
        }
        if (file.getSize() > MAX_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "File exceeds 100MB"));
        }
        String sess = (session == null || session.isBlank()) ? workspace.newSession() : session;
        Path stored = workspace.store(id, sess, name, file.getInputStream());
        Map<String, Object> fileInfo = new LinkedHashMap<>();
        fileInfo.put("name", name);
        fileInfo.put("path", stored.toAbsolutePath().toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", sess);
        out.put("files", List.of(fileInfo));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/api/plugins/{id}/files/archive")
    public ResponseEntity<StreamingResponseBody> archive(@PathVariable String id,
                                                          @RequestParam String session,
                                                          @RequestParam(defaultValue = "out") String dir) {
        if (registry.find(id).isEmpty()) return ResponseEntity.notFound().build();
        if (!dir.equals("out") && !dir.equals("in")) return ResponseEntity.badRequest().build();
        Path target = dir.equals("out") ? workspace.outDir(id, session) : workspace.inDir(id, session);
        StreamingResponseBody body = os -> workspace.zipDir(target, os);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"results.zip\"")
            .body(body);
    }

    @DeleteMapping("/api/plugins/{id}/files")
    public ResponseEntity<Object> delete(@PathVariable String id, @RequestParam String session) {
        workspace.remove(id, session);
        return ResponseEntity.ok(Map.of("success", true));
    }

    private static boolean hasAllowedExt(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return ALLOWED_EXT.contains(name.substring(dot).toLowerCase(Locale.ROOT));
    }
}
```

- [ ] **Step 4: Add the scheduled sweep job**

```java
package fan.summer.fengyu.plugin.workspace;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Hourly TTL sweep of plugin workspaces (24h retention). Complements the shutdown-hook wipe. */
@Component
public class WorkspaceSweepJob {
    private final PluginWorkspaceService workspace;
    public WorkspaceSweepJob(PluginWorkspaceService workspace) { this.workspace = workspace; }

    @Scheduled(fixedRate = 3_600_000L)
    public void sweep() { workspace.sweep(Duration.ofHours(24)); }
}
```

Ensure `@EnableScheduling` is present — check `FengYuApplication`; if absent, add `@EnableScheduling` to it. (Verify before adding to avoid a duplicate.)

- [ ] **Step 5: Run tests to verify pass + build**

Run: `mvn -q -o -pl FengYu test -Dtest=PluginFileControllerTest`
Expected: PASS. If `StreamingResponseBody` import missing from web deps, it's part of `spring-webmvc` (already present).

- [ ] **Step 6: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/web/controller/PluginFileController.java \
        FengYu/src/main/java/fan/summer/fengyu/plugin/workspace/WorkspaceSweepJob.java \
        FengYu/src/test/java/fan/summer/fengyu/web/controller/PluginFileControllerTest.java \
        FengYu/src/main/java/fan/summer/fengyu/FengYuApplication.java
git commit -m "✨ feat(host): PluginFileController (upload/archive/delete) + workspace sweep job"
```

### Task 11: Wire plugin-excel into the FengYu app + full reactor build

**Files:**
- Modify: `FengYu/pom.xml` (add plugin-excel dependency next to plugin-markdown)

**Interfaces:**
- Produces: `ExcelPlugin` + `ExcelSessionStore` + AI tools on the app classpath; `ExcelPlugin` registered by `PluginRegistryService`; ui served at `/plugin-ui/excel/`.

- [ ] **Step 1: Add the dependency**

In `FengYu/pom.xml`, after the `plugin-markdown` dependency, add:

```xml
        <!-- Official Excel plugin (v2): compile-time bundled; ui served at /plugin-ui/excel/. -->
        <dependency>
            <groupId>fan.summer.fengyu.plugin</groupId>
            <artifactId>plugin-excel</artifactId>
            <version>${revision}</version>
        </dependency>
```

- [ ] **Step 2: Full reactor build**

Run: `mvn -q -o install -DskipTests`
Expected: BUILD SUCCESS across `FengYu-Api`, `OfficialPlugins` (markdown + excel), `FengYu`.

- [ ] **Step 3: Boot smoke — /api/plugins lists excel**

Run:
```bash
java -jar FengYu/target/FengYu-4.0.0-SNAPSHOT.jar --port=24056 --token=dev &
sleep 8
curl -s -H "X-FengYu-Token: dev" http://127.0.0.1:24056/api/plugins | grep -o 'fan.summer.excel'
kill %1
```
Expected: prints `fan.summer.excel`.

- [ ] **Step 4: Commit**

```bash
git add FengYu/pom.xml
git commit -m "✨ feat(app): bundle plugin-excel; register Excel plugin + AI tools"
```

## Phase E — Frontend: PluginContext extension + Vue wizard

### Task 12: Extend `PluginContext` with `apiBase`, `token`, optional `desktop`

**Files:**
- Modify: `frontend/src/mf/loader.ts` (PluginContext interface)
- Modify: `frontend/src/views/PluginView.vue:51-65` (ctx construction)
- Create: `frontend/src/mf/desktop.ts` (Tauri detection + dialog facade)

**Interfaces:**
- Produces on `PluginContext`:
  - `apiBase: string` — from `getApiBase()`.
  - `token: string` — from `getToken()`.
  - `desktop?: { pickFile(filters?): Promise<string|null>; pickDirectory(): Promise<string|null> }` — present only under Tauri.

- [ ] **Step 1: Add fields to the interface**

In `frontend/src/mf/loader.ts`, extend `PluginContext`:

```ts
  /** Backend base URL (empty string = same-origin). For raw fetch (multipart/download). */
  apiBase: string
  /** Auth token to send as X-FengYu-Token on raw fetch calls. */
  token: string
  /** Native desktop file dialogs — present ONLY under Tauri; undefined in the browser. */
  desktop?: {
    pickFile(filters?: { name: string; extensions: string[] }[]): Promise<string | null>
    pickDirectory(): Promise<string | null>
  }
```

- [ ] **Step 2: Create the desktop facade**

Create `frontend/src/mf/desktop.ts`:

```ts
import type { PluginContext } from './loader'

/** True when running inside the Tauri webview. */
export function isTauri(): boolean {
  return typeof window !== 'undefined' &&
    ('__TAURI_INTERNALS__' in window || '__TAURI__' in window)
}

/** Build the native-dialog facade, or undefined when not under Tauri. */
export function makeDesktop(): PluginContext['desktop'] {
  if (!isTauri()) return undefined
  return {
    async pickFile(filters) {
      const { open } = await import('@tauri-apps/plugin-dialog')
      const res = await open({ multiple: false, directory: false, filters })
      return typeof res === 'string' ? res : null
    },
    async pickDirectory() {
      const { open } = await import('@tauri-apps/plugin-dialog')
      const res = await open({ multiple: false, directory: true })
      return typeof res === 'string' ? res : null
    },
  }
}
```

- [ ] **Step 3: Inject into ctx in PluginView.vue**

Add imports at top of `<script setup>`:

```ts
import { getApiBase, getToken } from '@/api/config'
import { makeDesktop } from '@/mf/desktop'
```

In the `ctx` object (after `notify:` line, before `vuetify`), add:

```ts
      apiBase: getApiBase(),
      token: getToken(),
      desktop: makeDesktop(),
```

- [ ] **Step 4: Add the Tauri dialog dependency**

Run: `cd frontend && npm install @tauri-apps/plugin-dialog@^2`
Expected: adds to `frontend/package.json` dependencies.

- [ ] **Step 5: Typecheck**

Run: `cd frontend && npm run build`
Expected: build succeeds (dynamic import of the tauri plugin is fine — it's only reached under Tauri).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/mf/loader.ts frontend/src/mf/desktop.ts frontend/src/views/PluginView.vue \
        frontend/package.json frontend/package-lock.json
git commit -m "✨ feat(frontend): PluginContext apiBase/token + optional Tauri desktop dialog facade"
```

### Task 13: plugin-excel micro-frontend scaffold (ui-src)

**Files:**
- Create: `OfficialPlugins/plugin-excel/ui-src/{package.json,tsconfig.json,vite.config.ts}`
- Create: `OfficialPlugins/plugin-excel/ui-src/src/{main.ts,pluginUi.ts,shims-vue.d.ts}`
- Create: `OfficialPlugins/plugin-excel/ui-src/src/ExcelSplitter.vue` (placeholder mount)

**Interfaces:**
- Consumes: host-provided `PluginUiContext` (mirror of `frontend` PluginContext: `api.invoke`, `apiBase`, `token`, `desktop?`, `theme`, `t`, `vuetify`).
- Produces: `resources/ui/excel/index.js` default-exporting `{ mount(el, ctx): () => void }`.

- [ ] **Step 1: Copy the markdown ui-src scaffold, renamed for excel**

```bash
cd /Users/phoebej/Develop/Java/FengYu
SRC=OfficialPlugins/plugin-markdown/ui-src
DST=OfficialPlugins/plugin-excel/ui-src
mkdir -p "$DST/src"
cp "$SRC/tsconfig.json" "$SRC/shims-vue.d.ts" "$DST/" 2>/dev/null || true
cp "$SRC/src/shims-vue.d.ts" "$DST/src/" 2>/dev/null || true
```

Then create `OfficialPlugins/plugin-excel/ui-src/package.json` (copy markdown's, change `name` to `plugin-excel-ui`, add `@tauri-apps/plugin-dialog` is NOT needed here — desktop calls go through host ctx).

`vite.config.ts` — copy markdown's, change `outDir` to `../src/main/resources/ui/excel/`.

- [ ] **Step 2: pluginUi.ts — extend the context type**

Create `OfficialPlugins/plugin-excel/ui-src/src/pluginUi.ts` mirroring markdown's but with the extra fields:

```ts
import type { Plugin } from 'vue'

export type PluginTheme = 'dark' | 'light'

export interface PluginUiContext {
  api: { invoke(action: string, args: Record<string, unknown>): Promise<unknown> }
  apiBase: string
  token: string
  desktop?: {
    pickFile(filters?: { name: string; extensions: string[] }[]): Promise<string | null>
    pickDirectory(): Promise<string | null>
  }
  theme?: PluginTheme
  onThemeChange?: (cb: (t: PluginTheme) => void) => (() => void)
  t?: (key: string) => string
  notify?: (msg: string) => void
  vuetify?: Plugin
}

export interface PluginUiModule {
  mount(el: HTMLElement, ctx: PluginUiContext): () => void
}
```

- [ ] **Step 3: main.ts — mount contract (copy markdown's verbatim, swap component import)**

```ts
import { createApp } from 'vue'
import ExcelSplitter from './ExcelSplitter.vue'
import type { PluginUiContext, PluginUiModule } from './pluginUi'

const module: PluginUiModule = {
  mount(el: HTMLElement, ctx: PluginUiContext): () => void {
    const app = createApp(ExcelSplitter)
    app.provide('pluginCtx', ctx)
    if (ctx.vuetify) app.use(ctx.vuetify)
    app.mount(el)
    return () => app.unmount()
  }
}
export default module
```

- [ ] **Step 4: Minimal ExcelSplitter.vue placeholder**

```vue
<script setup lang="ts">
import { inject } from 'vue'
import type { PluginUiContext } from './pluginUi'
const ctx = inject<PluginUiContext>('pluginCtx')!
</script>
<template>
  <v-container><v-alert type="info">Excel Splitter — {{ ctx.desktop ? 'desktop' : 'web' }} mode</v-alert></v-container>
</template>
```

- [ ] **Step 5: Build the bundle**

Run: `cd OfficialPlugins/plugin-excel/ui-src && npm install && npm run build`
Expected: emits `OfficialPlugins/plugin-excel/src/main/resources/ui/excel/index.js`.

- [ ] **Step 6: Commit**

```bash
git add OfficialPlugins/plugin-excel/ui-src OfficialPlugins/plugin-excel/src/main/resources/ui/excel/index.js
git commit -m "✨ feat(plugin-excel): micro-frontend scaffold + placeholder mount"
```

### Task 14: Excel wizard UI — web upload + desktop dialogs, three modes, zip download

**Files:**
- Modify: `OfficialPlugins/plugin-excel/ui-src/src/ExcelSplitter.vue` (full wizard)
- Create: `OfficialPlugins/plugin-excel/ui-src/src/fileIo.ts` (upload/download helpers)

**Interfaces:**
- Consumes: `ctx.api.invoke`, `ctx.apiBase`, `ctx.token`, `ctx.desktop?`.
- Produces: full 4-step flow. `fileIo.ts` exports:
  - `uploadFile(ctx, pluginId, file, session?) -> Promise<{session, path}>` (multipart POST).
  - `downloadArchive(ctx, pluginId, session)` (triggers browser save of `results.zip`).

- [ ] **Step 1: Implement fileIo.ts**

```ts
import type { PluginUiContext } from './pluginUi'

const PLUGIN_ID = 'fan.summer.excel'

function headers(ctx: PluginUiContext): HeadersInit {
  return ctx.token ? { 'X-FengYu-Token': ctx.token } : {}
}

export async function uploadFile(ctx: PluginUiContext, file: File, session?: string):
    Promise<{ session: string; path: string }> {
  const form = new FormData()
  form.append('file', file)
  if (session) form.append('session', session)
  const res = await fetch(`${ctx.apiBase}/api/plugins/${PLUGIN_ID}/files`, {
    method: 'POST', headers: headers(ctx), body: form,
  })
  if (!res.ok) throw new Error(`Upload failed: ${res.status}`)
  const data = await res.json()
  return { session: data.session, path: data.files[0].path }
}

export async function downloadArchive(ctx: PluginUiContext, session: string): Promise<void> {
  const res = await fetch(
    `${ctx.apiBase}/api/plugins/${PLUGIN_ID}/files/archive?session=${encodeURIComponent(session)}&dir=out`,
    { headers: headers(ctx) })
  if (!res.ok) throw new Error(`Download failed: ${res.status}`)
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = 'results.zip'
  document.body.appendChild(a); a.click(); a.remove()
  URL.revokeObjectURL(url)
}
```

- [ ] **Step 2: Implement the wizard component**

Replace `ExcelSplitter.vue` with a Vuetify `v-stepper` flow. Behavior:
- **Step 1 (source):** if `ctx.desktop`, a "Choose file" button → `ctx.desktop.pickFile([{name:'Excel',extensions:['xlsx','xls']}])` → store absolute `sourceFile`. Else an `<input type="file">` → `uploadFile(ctx, file)` → store `{session, sourceFile:path}`. For web, generate/hold `session` from upload response; for desktop, generate a client UUID (`crypto.randomUUID()`) for the invoke `session` arg.
- After source chosen, call `ctx.api.invoke('analyze', { session, sourceFile })`; render returned `sheets` (names + headers).
- **Step 2 (mode):** radio BY_SHEET / BY_COLUMN / COMPLEX. BY_SHEET → multiselect sheets; BY_COLUMN → pick sheet + column (from analyzed headers); COMPLEX → editable table of `{sheetName, headerIndex, columnIndex}` rows (with a "copy entire sheet" toggle setting both to -1). Call `ctx.api.invoke('configure', {...})`.
- **Step 3 (output):** if `ctx.desktop`, "Choose output folder" → `ctx.desktop.pickDirectory()` → `outputDir`. Else fixed note "results will be zipped for download" and set no outputDir (backend uses session out).
- **Step 4 (run):** call `ctx.api.invoke('split', { session, sourceFile, outputDir })`. For web, `outputDir` must be the session out dir — the backend resolves it; pass the server-side out path returned... **BUT** invoke only gets what we send. Decision: for web, backend `split` should default `outputDir` to the session `out/` when omitted. Adjust ExcelPlugin.split: if `outputDir` arg missing AND a `session` workspace exists, the plugin cannot know the host workspace path. **Resolution:** the web upload response also returns the session; the frontend then calls `invoke('split', {session, sourceFile, outputDir: <out path>})` where `<out path>` = derive from uploaded `path` by replacing `/in/<name>` with `/out`. Implement `deriveOutDir(path)` in fileIo.ts: `path.replace(/[/\\]in[/\\][^/\\]+$/, '/out')`. On success (web) call `downloadArchive(ctx, session)`; (desktop) show the local output folder path.

Keep it Vuetify-based, follow host theme via `app.use(ctx.vuetify)`, no language switcher.

- [ ] **Step 3: Add `deriveOutDir` to fileIo.ts**

```ts
/** Given an uploaded source path .../in/<file>, return the sibling .../out directory. */
export function deriveOutDir(sourcePath: string): string {
  return sourcePath.replace(/[/\\]in[/\\][^/\\]+$/, (m) => m.replace(/[^/\\]+$/, '').replace(/in([/\\])$/, 'out$1')).replace(/[/\\]$/, '')
}
```

If the regex proves fragile, simpler: split on `/in/` or `\in\` and rejoin with `/out`. Prefer this explicit version:

```ts
export function deriveOutDir(sourcePath: string): string {
  const sep = sourcePath.includes('\\') ? '\\' : '/'
  const marker = `${sep}in${sep}`
  const i = sourcePath.lastIndexOf(marker)
  if (i < 0) return sourcePath
  return sourcePath.substring(0, i) + sep + 'out'
}
```

Use the second (explicit) version.

- [ ] **Step 4: Build**

Run: `cd OfficialPlugins/plugin-excel/ui-src && npm run build`
Expected: rebuilt `index.js`.

- [ ] **Step 5: Manual E2E (web)**

Run backend jar + `cd frontend && npm run dev`; open the Excel tool, upload a real `.xlsx`, pick BY_SHEET, run, confirm `results.zip` downloads with one file per sheet.

- [ ] **Step 6: Commit**

```bash
git add OfficialPlugins/plugin-excel/ui-src/src/ExcelSplitter.vue \
        OfficialPlugins/plugin-excel/ui-src/src/fileIo.ts \
        OfficialPlugins/plugin-excel/src/main/resources/ui/excel/index.js
git commit -m "✨ feat(plugin-excel): wizard UI — web upload/desktop dialogs, 3 modes, zip download"
```

## Phase F — AI tools (Spring AI @Tool beans)

All tools live in `OfficialPlugins/plugin-excel/src/main/java/fan/summer/fengyu/plugin/excel/ai/`,
are `@Component implements fan.summer.fengyu.ai.tools.FengYuTool` with `@Tool`-annotated methods,
and operate on `ExcelSessionStore.active()` (the current workflow) — mirroring the 3.2.0 single
shared-config semantics. Returns are JSON strings via a small Gson/Jackson helper following the
`{success, summary, ...}` contract.

NOTE on marker interface: `FengYuTool` lives in the app module (`fan.summer.fengyu.ai.tools`), which
is NOT on plugin-excel's compile classpath by default. Verify: plugin-excel must depend on whatever
module exposes `FengYuTool` + Spring AI `@Tool` as `provided`. If `FengYuTool` is only in `FengYu`
(the app), add a `provided` dependency on `FengYu` OR relocate the marker into `FengYu-Api`. **Preferred:
move `FengYuTool` into `FengYu-Api`** (`fan.summer.fengyu.api.ai.FengYuTool`) so plugins can implement
it without depending on the app. Do this as Step 1 below and update `AiToolDiscoveryConfig`'s import.

### Task 15: Relocate `FengYuTool` marker to FengYu-Api; add JSON helper

**Files:**
- Move: `FengYu/.../ai/tools/FengYuTool.java` → `FengYu-Api/.../api/ai/FengYuTool.java`
- Modify: `FengYu/.../ai/config/AiToolDiscoveryConfig.java` (import), `FengYu/.../ai/tools/JsonFormatTool.java` (import)
- Create: `OfficialPlugins/plugin-excel/.../excel/ai/ToolJson.java`

**Interfaces:**
- Produces: `fan.summer.fengyu.api.ai.FengYuTool` (empty marker); `ToolJson.ok(String summary, Map extra) -> String`, `ToolJson.err(String message) -> String`.

- [ ] **Step 1: Move the marker into the API module**

```bash
cd /Users/phoebej/Develop/Java/FengYu
mkdir -p FengYu-Api/src/main/java/fan/summer/fengyu/api/ai
git mv FengYu/src/main/java/fan/summer/fengyu/ai/tools/FengYuTool.java \
       FengYu-Api/src/main/java/fan/summer/fengyu/api/ai/FengYuTool.java
```

Change its package line to `package fan.summer.fengyu.api.ai;`. Keep it an empty marker interface.

- [ ] **Step 2: Fix references in the app**

In `AiToolDiscoveryConfig.java` and `JsonFormatTool.java`, replace `import fan.summer.fengyu.ai.tools.FengYuTool;` with `import fan.summer.fengyu.api.ai.FengYuTool;`. (Search the whole app for other `ai.tools.FengYuTool` importers and fix them too.)

Run: `mvn -q -o install -DskipTests -pl FengYu-Api,FengYu -am` — expect BUILD SUCCESS.

- [ ] **Step 3: Add plugin-excel `provided` dep for Spring AI marker/annotation**

Confirm `FengYu-Api` (now hosting `FengYuTool`) is already a `provided` dep of plugin-excel (Task 3 — yes). Ensure `spring-ai-core` (`@Tool`) is `provided` (Task 3). Build plugin-excel: `mvn -q -o test-compile -pl OfficialPlugins/plugin-excel`.

- [ ] **Step 4: Add ToolJson helper**

```java
package fan.summer.fengyu.plugin.excel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small helper producing the {success, summary, ...} tool-return JSON contract. */
final class ToolJson {
    private static final ObjectMapper M = new ObjectMapper();
    private ToolJson() {}

    static String ok(String summary, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("summary", summary);
        if (extra != null) m.putAll(extra);
        return write(m);
    }
    static String err(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("error", message);
        return write(m);
    }
    private static String write(Map<String, Object> m) {
        try { return M.writeValueAsString(m); }
        catch (Exception e) { return "{\"success\":false,\"error\":\"serialization failed\"}"; }
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "♻️ refactor(ai): move FengYuTool marker to FengYu-Api; add plugin-excel ToolJson helper"
```

### Task 16: `ExcelAnalyzeTool` + `ExcelQueryTool` + `ExcelCancelTool`

**Files:**
- Create: `.../excel/ai/ExcelAnalyzeTool.java`, `ExcelQueryTool.java`, `ExcelCancelTool.java`
- Test: `.../excel/ai/ExcelAiToolsTest.java`

**Interfaces:**
- Consumes: `ExcelSessionStore`, `ExcelSplitter.analyze`.
- Produces (each `@Component implements FengYuTool`):
  - `ExcelAnalyzeTool.analyze(String filePath) -> String` — `@Tool(name="excel_analyze")`; sets active session's `sourceFile` + `analysisResult`; needs a session — use a fixed AI session key `"ai"` via `sessions.get("ai")`.
  - `ExcelQueryTool.query() -> String` — `@Tool(name="excel_query")`; reports active config state.
  - `ExcelCancelTool.cancel() -> String` — `@Tool(name="excel_cancel")`; `sessions.remove("ai")`.

- [ ] **Step 1: Write the failing test**

```java
package fan.summer.fengyu.plugin.excel.ai;

import fan.summer.fengyu.plugin.excel.ExcelSessionStore;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExcelAiToolsTest {
    @TempDir Path tmp;
    Path src;
    ExcelSessionStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new ExcelSessionStore();
        src = tmp.resolve("in.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(src.toFile())) {
            Sheet s = wb.createSheet("Alpha");
            s.createRow(0).createCell(0).setCellValue("region");
            wb.write(fos);
        }
    }

    @Test
    void analyzeThenQueryThenCancel() {
        String a = new ExcelAnalyzeTool(store).analyze(src.toString());
        assertTrue(a.contains("\"success\":true"));
        String q = new ExcelQueryTool(store).query();
        assertTrue(q.contains(src.getFileName().toString()));
        String c = new ExcelCancelTool(store).cancel();
        assertTrue(c.contains("\"success\":true"));
        assertTrue(store.active().isEmpty());
    }

    @Test
    void analyzeMissingFileErrors() {
        String a = new ExcelAnalyzeTool(store).analyze("/no/such/file.xlsx");
        assertTrue(a.contains("\"success\":false"));
    }
}
```

- [ ] **Step 2: Run to verify fail**

Run: `mvn -q -o -pl OfficialPlugins/plugin-excel test -Dtest=ExcelAiToolsTest`
Expected: FAIL — tool classes missing.

- [ ] **Step 3: Implement the three tools**

`ExcelAnalyzeTool`:

```java
package fan.summer.fengyu.plugin.excel.ai;

import fan.summer.fengyu.api.ai.FengYuTool;
import fan.summer.fengyu.plugin.excel.ExcelSessionStore;
import fan.summer.fengyu.plugin.excel.ExcelSplitter;
import fan.summer.fengyu.plugin.excel.SplitConfig;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ExcelAnalyzeTool implements FengYuTool {
    static final String AI_SESSION = "ai";
    private final ExcelSessionStore sessions;
    public ExcelAnalyzeTool(ExcelSessionStore sessions) { this.sessions = sessions; }

    @Tool(name = "excel_analyze",
          description = "Analyze an Excel .xlsx/.xls file: returns sheet names and column headers. "
                      + "Arg: filePath (absolute path to the Excel file).")
    public String analyze(String filePath) {
        if (filePath == null || filePath.isBlank()) return ToolJson.err("filePath is required");
        Path p = Paths.get(filePath.trim());
        if (!Files.exists(p) || !Files.isReadable(p)) return ToolJson.err("File not found: " + filePath);
        SplitConfig cfg = sessions.get(AI_SESSION);
        cfg.sourceFile = p;
        try { cfg.analysisResult = ExcelSplitter.analyze(p); }
        catch (Exception e) { return ToolJson.err("Analyze failed: " + e.getMessage()); }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("sheets", cfg.analysisResult.keySet());
        return ToolJson.ok("analyzed " + cfg.analysisResult.size() + " sheet(s)", extra);
    }
}
```

`ExcelQueryTool`:

```java
package fan.summer.fengyu.plugin.excel.ai;

import fan.summer.fengyu.api.ai.FengYuTool;
import fan.summer.fengyu.plugin.excel.ExcelSessionStore;
import fan.summer.fengyu.plugin.excel.SplitConfig;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ExcelQueryTool implements FengYuTool {
    private final ExcelSessionStore sessions;
    public ExcelQueryTool(ExcelSessionStore sessions) { this.sessions = sessions; }

    @Tool(name = "excel_query", description = "Query the current Excel split configuration state.")
    public String query() {
        SplitConfig c = sessions.active().orElse(null);
        if (c == null) return ToolJson.err("No active Excel session; call excel_analyze first.");
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("sourceFile", c.sourceFile != null ? c.sourceFile.toString() : null);
        extra.put("mode", c.mode != null ? c.mode.name() : null);
        extra.put("selectedSheets", c.selectedSheets);
        extra.put("splitSheet", c.splitSheet);
        extra.put("splitColumnIndex", c.splitColumnIndex);
        extra.put("complexEntries", c.complexEntries.size());
        extra.put("outputDir", c.outputDir != null ? c.outputDir.toString() : null);
        return ToolJson.ok("mode=" + (c.mode != null ? c.mode.name() : "unset"), extra);
    }
}
```

`ExcelCancelTool`:

```java
package fan.summer.fengyu.plugin.excel.ai;

import fan.summer.fengyu.api.ai.FengYuTool;
import fan.summer.fengyu.plugin.excel.ExcelSessionStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class ExcelCancelTool implements FengYuTool {
    private final ExcelSessionStore sessions;
    public ExcelCancelTool(ExcelSessionStore sessions) { this.sessions = sessions; }

    @Tool(name = "excel_cancel", description = "Cancel/reset the current Excel split session.")
    public String cancel() {
        sessions.remove(ExcelAnalyzeTool.AI_SESSION);
        return ToolJson.ok("session reset", null);
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q -o -pl OfficialPlugins/plugin-excel test -Dtest=ExcelAiToolsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/plugin-excel/src/main/java/fan/summer/fengyu/plugin/excel/ai/ \
        OfficialPlugins/plugin-excel/src/test/java/fan/summer/fengyu/plugin/excel/ai/ExcelAiToolsTest.java
git commit -m "✨ feat(plugin-excel): AI tools excel_analyze/query/cancel"
```

### Task 17: `ExcelConfigureTool` + `ExcelComplexConfigTool` + `ExcelExecuteTool`

**Files:**
- Create: `.../excel/ai/ExcelConfigureTool.java`, `ExcelComplexConfigTool.java`, `ExcelExecuteTool.java`
- Test: `.../excel/ai/ExcelExecuteToolTest.java`

**Interfaces:**
- Consumes: `ExcelSessionStore`, `ExcelSplitter`, `ComplexSplitEntry`.
- Produces (each `@Component implements FengYuTool`):
  - `ExcelConfigureTool.configure(String mode, java.util.List<String> sheets, String splitSheet, String splitColumn) -> String` — `@Tool(name="excel_configure")`; sets mode + params on active config; resolves `splitColumn` header → index.
  - `ExcelComplexConfigTool.complexConfig(String action, String sheetName, int headerIndex, int columnIndex) -> String` — `@Tool(name="excel_complex_config")`; action add/list/clear over `active().complexEntries`.
  - `ExcelExecuteTool.execute(String outputDir, String filePrefix) -> String` — `@Tool(name="excel_execute")`; runs `ExcelSplitter`; returns `{success, summary, fileCount, files}`.

- [ ] **Step 1: Write the failing test (full AI flow)**

```java
package fan.summer.fengyu.plugin.excel.ai;

import fan.summer.fengyu.plugin.excel.ExcelSessionStore;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class ExcelExecuteToolTest {
    @TempDir Path tmp;
    Path src;
    ExcelSessionStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new ExcelSessionStore();
        src = tmp.resolve("in.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(src.toFile())) {
            Sheet s = wb.createSheet("Alpha");
            s.createRow(0).createCell(0).setCellValue("region");
            s.createRow(1).createCell(0).setCellValue("east");
            wb.write(fos);
        }
    }

    @Test
    void analyzeConfigureExecuteBySheet() throws Exception {
        new ExcelAnalyzeTool(store).analyze(src.toString());
        String cfg = new ExcelConfigureTool(store).configure("BY_SHEET", null, null, null);
        assertTrue(cfg.contains("\"success\":true"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        String r = new ExcelExecuteTool(store).execute(out.toString(), "");
        assertTrue(r.contains("\"success\":true"));
        assertTrue(Files.exists(out.resolve("Alpha.xlsx")));
    }

    @Test
    void executeWithoutConfigErrors() {
        String r = new ExcelExecuteTool(store).execute("/tmp/x", "");
        assertTrue(r.contains("\"success\":false"));
    }
}
```

- [ ] **Step 2: Run to verify fail**

Run: `mvn -q -o -pl OfficialPlugins/plugin-excel test -Dtest=ExcelExecuteToolTest`
Expected: FAIL — classes missing.

- [ ] **Step 3: Implement ExcelConfigureTool**

```java
package fan.summer.fengyu.plugin.excel.ai;

import fan.summer.fengyu.api.ai.FengYuTool;
import fan.summer.fengyu.plugin.excel.ExcelSessionStore;
import fan.summer.fengyu.plugin.excel.SplitConfig;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ExcelConfigureTool implements FengYuTool {
    private final ExcelSessionStore sessions;
    public ExcelConfigureTool(ExcelSessionStore sessions) { this.sessions = sessions; }

    @Tool(name = "excel_configure",
          description = "Configure the split mode. mode is one of BY_SHEET|BY_COLUMN|COMPLEX. "
                      + "BY_SHEET: optional sheets list (empty=all). BY_COLUMN: splitSheet + splitColumn (header name). "
                      + "COMPLEX: configure entries via excel_complex_config first.")
    public String configure(String mode, List<String> sheets, String splitSheet, String splitColumn) {
        SplitConfig c = sessions.active().orElse(null);
        if (c == null || c.analysisResult == null) return ToolJson.err("Call excel_analyze first.");
        SplitConfig.SplitMode m;
        try { m = SplitConfig.SplitMode.valueOf(mode); }
        catch (Exception e) { return ToolJson.err("Invalid mode: " + mode); }
        c.mode = m;
        switch (m) {
            case BY_SHEET -> c.selectedSheets = (sheets != null && !sheets.isEmpty())
                    ? new ArrayList<>(sheets) : new ArrayList<>(c.analysisResult.keySet());
            case BY_COLUMN -> {
                if (splitSheet == null || splitColumn == null)
                    return ToolJson.err("splitSheet and splitColumn required for BY_COLUMN");
                Map<Integer, String> headers = c.analysisResult.get(splitSheet);
                if (headers == null) return ToolJson.err("Unknown sheet: " + splitSheet);
                Integer idx = null;
                for (var e : headers.entrySet()) if (splitColumn.equals(e.getValue())) { idx = e.getKey(); break; }
                if (idx == null) return ToolJson.err("Unknown column: " + splitColumn);
                c.splitSheet = splitSheet; c.splitColumn = splitColumn; c.splitColumnIndex = idx;
            }
            case COMPLEX -> {
                if (c.complexEntries.isEmpty()) return ToolJson.err("Add entries via excel_complex_config first");
            }
        }
        return ToolJson.ok("configured mode=" + m, null);
    }
}
```

- [ ] **Step 4: Implement ExcelComplexConfigTool**

```java
package fan.summer.fengyu.plugin.excel.ai;

import fan.summer.fengyu.api.ai.FengYuTool;
import fan.summer.fengyu.plugin.excel.ComplexSplitEntry;
import fan.summer.fengyu.plugin.excel.ExcelSessionStore;
import fan.summer.fengyu.plugin.excel.SplitConfig;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ExcelComplexConfigTool implements FengYuTool {
    private final ExcelSessionStore sessions;
    public ExcelComplexConfigTool(ExcelSessionStore sessions) { this.sessions = sessions; }

    @Tool(name = "excel_complex_config",
          description = "Manage COMPLEX split entries. action is one of add|list|clear. "
                      + "For add: sheetName, headerIndex (1-based; -1 with columnIndex -1 = copy entire sheet), "
                      + "columnIndex (1-based column to split by).")
    public String complexConfig(String action, String sheetName, int headerIndex, int columnIndex) {
        SplitConfig c = sessions.active().orElse(null);
        if (c == null) return ToolJson.err("Call excel_analyze first.");
        String a = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        switch (a) {
            case "add" -> {
                if (sheetName == null || sheetName.isBlank()) return ToolJson.err("sheetName required for add");
                String field = c.sourceFile != null ? c.sourceFile.getFileName().toString() : "";
                c.complexEntries.add(new ComplexSplitEntry(field, sheetName, headerIndex, columnIndex));
                return ToolJson.ok("added entry; total=" + c.complexEntries.size(), null);
            }
            case "list" -> {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("entries", c.complexEntries.stream().map(e -> Map.of(
                        "sheetName", e.sheetName(), "headerIndex", e.headerIndex(), "columnIndex", e.columnIndex())).toList());
                return ToolJson.ok(c.complexEntries.size() + " entr(ies)", extra);
            }
            case "clear" -> { c.complexEntries.clear(); return ToolJson.ok("cleared", null); }
            default -> { return ToolJson.err("Invalid action: " + action); }
        }
    }
}
```

- [ ] **Step 5: Implement ExcelExecuteTool**

```java
package fan.summer.fengyu.plugin.excel.ai;

import fan.summer.fengyu.api.ai.FengYuTool;
import fan.summer.fengyu.plugin.excel.ExcelSessionStore;
import fan.summer.fengyu.plugin.excel.ExcelSplitter;
import fan.summer.fengyu.plugin.excel.SplitConfig;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ExcelExecuteTool implements FengYuTool {
    private final ExcelSessionStore sessions;
    public ExcelExecuteTool(ExcelSessionStore sessions) { this.sessions = sessions; }

    @Tool(name = "excel_execute",
          description = "Execute the configured Excel split. Args: outputDir (absolute path), "
                      + "filePrefix (optional). Run excel_analyze + excel_configure first.")
    public String execute(String outputDir, String filePrefix) {
        SplitConfig c = sessions.active().orElse(null);
        if (c == null || c.analysisResult == null) return ToolJson.err("Call excel_analyze first.");
        if (c.mode == null) return ToolJson.err("Call excel_configure first.");
        if (outputDir == null || outputDir.isBlank()) return ToolJson.err("outputDir is required");
        c.outputDir = Paths.get(outputDir.trim());
        c.filePrefix = filePrefix != null ? filePrefix.trim() : "";
        try { Files.createDirectories(c.outputDir); } catch (Exception ignored) {}
        ExcelSplitter.SplitResult res;
        try { res = new ExcelSplitter(c, null).split(); }
        catch (Exception e) { return ToolJson.err("Split failed: " + e.getMessage()); }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("fileCount", res.fileCount());
        extra.put("files", res.outputFiles().stream().map(p -> p.getFileName().toString()).toList());
        return ToolJson.ok("wrote " + res.fileCount() + " file(s)", extra);
    }
}
```

- [ ] **Step 6: Run tests + full plugin test suite**

Run: `mvn -q -o -pl OfficialPlugins/plugin-excel test`
Expected: PASS (all plugin-excel tests).

- [ ] **Step 7: Commit**

```bash
git add OfficialPlugins/plugin-excel/src/main/java/fan/summer/fengyu/plugin/excel/ai/ \
        OfficialPlugins/plugin-excel/src/test/java/fan/summer/fengyu/plugin/excel/ai/ExcelExecuteToolTest.java
git commit -m "✨ feat(plugin-excel): AI tools excel_configure/complex_config/execute"
```

## Phase G — Tauri desktop wiring, cleanup, docs, E2E

### Task 18: Enable the Tauri dialog plugin (desktop capability)

**Files:**
- Modify: `desktop/src-tauri/Cargo.toml` (add `tauri-plugin-dialog`)
- Modify: `desktop/src-tauri/src/main.rs` (register plugin)
- Create/Modify: `desktop/src-tauri/capabilities/default.json` (grant dialog permission)
- Modify: `desktop/src-tauri/tauri.conf.json` if capabilities need referencing

**Interfaces:**
- Produces: the JS `@tauri-apps/plugin-dialog` `open()` call (used by `frontend/src/mf/desktop.ts`) is authorized at runtime.

- [ ] **Step 1: Add the Rust crate**

In `desktop/src-tauri/Cargo.toml` `[dependencies]`, add:

```toml
tauri-plugin-dialog = "2"
```

- [ ] **Step 2: Register in main.rs**

In `desktop/src-tauri/src/main.rs`, in the `tauri::Builder` chain (before `.run(...)`), add:

```rust
        .plugin(tauri_plugin_dialog::init())
```

(Place alongside any existing `.plugin(...)` calls; if none, add it right after `tauri::Builder::default()` / the builder start.)

- [ ] **Step 3: Grant capability**

Read `desktop/src-tauri/capabilities/default.json` (create if missing, mirroring the Tauri 2 schema). Add `"dialog:allow-open"` to its `permissions` array. If no capabilities file exists, create `desktop/src-tauri/capabilities/default.json`:

```json
{
  "$schema": "../gen/schemas/desktop-schema.json",
  "identifier": "default",
  "description": "Default capability set for FengYu desktop",
  "windows": ["main"],
  "permissions": ["dialog:allow-open"]
}
```

Verify `tauri.conf.json` `app.security` / `app` references the capabilities dir (Tauri 2 auto-loads `capabilities/`). If a capabilities file already exists with other permissions, just append `"dialog:allow-open"`.

- [ ] **Step 4: Build the desktop shell (if Rust toolchain available)**

Run: `cd desktop && npm run tauri build -- --debug` OR at minimum `cd desktop/src-tauri && cargo check`.
Expected: compiles with the dialog plugin. If Rust toolchain is unavailable in this environment, note it and defer the build-verify to CI; the config changes are still correct.

- [ ] **Step 5: Commit**

```bash
git add desktop/src-tauri/Cargo.toml desktop/src-tauri/src/main.rs desktop/src-tauri/capabilities/
git commit -m "✨ feat(desktop): enable Tauri dialog plugin for native file/folder pickers"
```

### Task 19: Remove leftover 3.2.0-era Excel DB dead code

**Files:**
- Delete: `FengYu/src/main/java/fan/summer/fengyu/database/entity/excel/ComplexSplitConfigEntity.java`
- Delete: `FengYu/src/main/java/fan/summer/fengyu/database/repository/excel/ComplexSplitConfigRepository.java`

**Interfaces:**
- Produces: no consumers remain (COMPLEX now uses in-memory `ComplexSplitEntry`).

- [ ] **Step 1: Confirm no references**

Run: `grep -rn "ComplexSplitConfigEntity\|ComplexSplitConfigRepository" FengYu/src frontend/src OfficialPlugins`
Expected: no matches outside the two files themselves. If any consumer exists (e.g. a Spring `@Repository` injection), stop and report — do not delete blindly.

- [ ] **Step 2: Delete the files**

```bash
git rm FengYu/src/main/java/fan/summer/fengyu/database/entity/excel/ComplexSplitConfigEntity.java \
       FengYu/src/main/java/fan/summer/fengyu/database/repository/excel/ComplexSplitConfigRepository.java
rmdir FengYu/src/main/java/fan/summer/fengyu/database/entity/excel \
      FengYu/src/main/java/fan/summer/fengyu/database/repository/excel 2>/dev/null || true
```

- [ ] **Step 3: Build to confirm nothing broke**

Run: `mvn -q -o install -DskipTests -pl FengYu -am`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git commit -m "🔥 chore(excel): remove dead 3.2.0-era ComplexSplitConfig JPA entity + repository"
```

### Task 20: Docs + E2E smoke coverage + final full verification

**Files:**
- Modify: `CLAUDE.md` (module table + note Tauri dialog now wired, OfficialPlugins/ location)
- Modify: `scripts/e2e-smoke.sh` (probe excel upload → split → archive)
- Modify: `docs/plugins/excel.md` (optional: point to new plugin location) — keep minimal

**Interfaces:**
- Produces: docs reflect the new structure; e2e covers the Excel web path.

- [ ] **Step 1: Update CLAUDE.md**

In the Module Structure table, change `plugin-markdown` row to note it lives under `OfficialPlugins/`, and add a `plugin-excel` row: "Official Excel splitter plugin (v2): backend engine + Vue wizard micro-frontend + 6 Spring AI @Tool beans; web upload+zip / desktop native dialogs." Add a short "Plugin File I/O Standard v1" bullet near the 4.0.0 Headless Architecture section summarizing the upload/archive endpoints + `ctx.desktop` facade + workspace TTL.

- [ ] **Step 2: Extend e2e-smoke.sh (web path)**

After the markdown assertion, add an Excel block that: uploads a tiny generated `.xlsx` via `curl -F`, captures `session` + `path` (parse with the JSON tooling already used in the script or `python3 -c`), calls `/api/plugins/fan.summer.excel/invoke` with `action=analyze` then `action=split` (outputDir = derived `out`), then GETs `/api/plugins/fan.summer.excel/files/archive?session=...` and asserts a non-empty zip (`unzip -l`). Use the same `AUTH` header array. Follow the existing script's assert style/log format.

Concrete snippet to insert (adapt variable names to the script's conventions):

```bash
# --- Excel plugin (web upload → split → archive) ---
XLSX="$WORK/sample.xlsx"
python3 - "$XLSX" <<'PY'
import sys, zipfile
# minimal xlsx via openpyxl if present, else skip gracefully
try:
    from openpyxl import Workbook
    wb = Workbook(); ws = wb.active; ws.title = "Alpha"
    ws.append(["region"]); ws.append(["east"]); ws.append(["west"])
    wb.save(sys.argv[1])
except Exception as e:
    print("SKIP-EXCEL:", e)
PY
if [ -f "$XLSX" ]; then
  UP=$(curl -s "${AUTH[@]}" -F "file=@$XLSX" "$H/api/plugins/fan.summer.excel/files")
  SESS=$(printf '%s' "$UP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["session"])')
  SRCP=$(printf '%s' "$UP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["files"][0]["path"])')
  OUTP=$(printf '%s' "$SRCP" | sed 's#/in/[^/]*$#/out#')
  curl -s "${AUTH[@]}" -H 'Content-Type: application/json' \
    -d "{\"action\":\"analyze\",\"args\":{\"session\":\"$SESS\",\"sourceFile\":\"$SRCP\"}}" \
    "$H/api/plugins/fan.summer.excel/invoke" >/dev/null
  curl -s "${AUTH[@]}" -H 'Content-Type: application/json' \
    -d "{\"action\":\"split\",\"args\":{\"session\":\"$SESS\",\"sourceFile\":\"$SRCP\",\"outputDir\":\"$OUTP\"}}" \
    "$H/api/plugins/fan.summer.excel/invoke" | grep -q '"success":true' \
    && echo "PASS: excel split" || { echo "FAIL: excel split"; exit 1; }
  curl -s "${AUTH[@]}" "$H/api/plugins/fan.summer.excel/files/archive?session=$SESS&dir=out" -o "$WORK/r.zip"
  unzip -l "$WORK/r.zip" | grep -q ".xlsx" && echo "PASS: excel archive" || { echo "FAIL: excel archive"; exit 1; }
fi
```

- [ ] **Step 3: Full build + test + smoke**

Run:
```bash
mvn -q -o install -DskipTests
cd OfficialPlugins/plugin-excel/ui-src && npm run build && cd -
mvn -q -o test -pl FengYu-Api,OfficialPlugins/plugin-excel,FengYu
scripts/e2e-smoke.sh 24057 e2e-token
```
Expected: BUILD SUCCESS, all unit tests pass, smoke prints PASS for markdown + excel split + excel archive.

- [ ] **Step 4: Frontend typecheck/build**

Run: `cd frontend && npm run build`
Expected: succeeds.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md scripts/e2e-smoke.sh docs/plugins/excel.md
git commit -m "📝 docs+test: document Excel plugin + File I/O Standard; e2e covers Excel web path"
```

## Self-Review Notes

- **Spec coverage:** every spec section (§2–§10) maps to at least one task (see plan intro mapping).
- **Ordering dependency:** Task 15 (relocate `FengYuTool` to `FengYu-Api`) MUST precede Tasks 16–17 (AI tools implement it) and Task 11's full build. Task 2 (FILE category) MUST precede Task 8 (descriptor uses `ToolCategory.FILE`).
- **Verify-before-code flags:** Task 3 (Spring AI artifactId + poi-ooxml managed version), Task 10 Step 4 (`@EnableScheduling` presence), Task 18 (Tauri capabilities file location), Task 19 Step 1 (no live consumers of the JPA entity).
- **Known soft spot:** Task 14's wizard `.vue` is behavior-specified, not fully coded, due to size — implementer writes the Vuetify stepper against the pinned invoke/fileIo contract.

## Global Verification (run after all tasks)

```bash
mvn -q -o install -DskipTests            # full reactor
mvn -q -o test -pl FengYu-Api,OfficialPlugins/plugin-excel,FengYu
cd OfficialPlugins/plugin-excel/ui-src && npm run build && cd -
cd frontend && npm run build && cd -
scripts/e2e-smoke.sh 24057 e2e-token     # markdown + excel web path
```

Expected: BUILD SUCCESS, all unit tests green, smoke prints PASS for markdown + excel split + excel archive.









