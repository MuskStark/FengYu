# Excel Four-Step Wizard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the 3.2.0 four-step Excel splitting workflow in the isolated `.fyp` plugin and eliminate JSON-RPC 500 errors caused by worker stdout contamination.

**Architecture:** Keep the Excel UI as dependency-free HTML/CSS/ES modules inside its sandboxed iframe. Put deterministic wizard validation and payload construction in a small pure JavaScript module, keep DOM/host bridge orchestration in `app.js`, and retain the existing Java split engine. Reserve child-process stdout for newline-delimited JSON-RPC in the SDK, while making the host parser defensive against third-party worker noise.

**Tech Stack:** Java 21, Spring Boot 4.1, Gson/Jackson, JUnit 5, Vue host bridge, native browser ES modules, Node.js built-in test runner, Maven.

---

## File Structure

| File | Responsibility |
|---|---|
| `FengYu-Plugin-Sdk/src/main/java/fan/summer/fengyu/sdk/JsonRpcWorker.java` | Own stdout for protocol output and redirect incidental process output to stderr. |
| `FengYu-Plugin-Sdk/src/test/java/fan/summer/fengyu/sdk/JsonRpcWorkerTest.java` | Prove handler stdout cannot corrupt protocol responses. |
| `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java` | Read until a valid response with the current ID; log stderr and protocol noise. |
| `FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginProcessManagerTest.java` | Exercise noisy stdout, mismatched IDs, RPC error and EOF. |
| `FengYu/src/main/java/fan/summer/fengyu/web/GlobalExceptionHandler.java` | Return stable JSON for plugin runtime failures. |
| `FengYu/src/test/java/fan/summer/fengyu/web/GlobalExceptionHandlerTest.java` | Verify safe 500 response body. |
| `OfficialPlugins/plugin-excel/src/main/java/fan/summer/fengyu/plugin/excel/ExcelPlugin.java` | Enforce mode-specific server-side configuration validation. |
| `OfficialPlugins/plugin-excel/src/test/java/fan/summer/fengyu/plugin/excel/ExcelPluginTest.java` | Cover BY_SHEET/BY_COLUMN/COMPLEX validation and payloads. |
| `OfficialPlugins/plugin-excel/src/test/java/fan/summer/fengyu/plugin/excel/ExcelWorkerMainTest.java` | Run the real worker protocol around analyze/configure/query. |
| `OfficialPlugins/packages/excel/ui/wizard-state.js` | Pure wizard state, validation, summaries and RPC payload construction. |
| `OfficialPlugins/packages/excel/ui/index.html` | Four distinct wizard panels and accessible controls. |
| `OfficialPlugins/packages/excel/ui/app.js` | File capabilities, bridge calls, navigation, rendering and export. |
| `OfficialPlugins/packages/excel/ui/style.css` | Responsive four-step visual states in both host themes. |
| `OfficialPlugins/packages/excel/ui/wizard-state.test.mjs` | Node tests for navigation, validation and complex rules. |
| `OfficialPlugins/build-packages.sh` | Run UI tests before emitting `.fyp` archives. |

## Task 1: Reserve Worker stdout for JSON-RPC

**Files:**
- Modify: `FengYu-Plugin-Sdk/src/main/java/fan/summer/fengyu/sdk/JsonRpcWorker.java`
- Modify: `FengYu-Plugin-Sdk/src/test/java/fan/summer/fengyu/sdk/JsonRpcWorkerTest.java`

- [ ] **Step 1: Write the failing stdout-isolation test**

Add a second test that invokes the process-level `run()` path with temporary `System.in/out/err` streams:

```java
@Test
void reservesStdoutForProtocolWhenHandlerPrintsDiagnostics() throws Exception {
    InputStream oldIn = System.in;
    PrintStream oldOut = System.out;
    PrintStream oldErr = System.err;
    var protocol = new ByteArrayOutputStream();
    var diagnostics = new ByteArrayOutputStream();
    try {
        System.setIn(new ByteArrayInputStream(
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"noisy\",\"params\":{}}\n"
                .getBytes(StandardCharsets.UTF_8)));
        System.setOut(new PrintStream(protocol, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(diagnostics, true, StandardCharsets.UTF_8));
        new JsonRpcWorker().on("noisy", params -> {
            System.out.println("library-noise");
            return Map.of("ok", true);
        }).run();
    } finally {
        System.setIn(oldIn);
        System.setOut(oldOut);
        System.setErr(oldErr);
    }
    assertEquals(1, protocol.toString(StandardCharsets.UTF_8).lines().count());
    assertTrue(protocol.toString(StandardCharsets.UTF_8).contains("\"ok\":true"));
    assertTrue(diagnostics.toString(StandardCharsets.UTF_8).contains("library-noise"));
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -pl FengYu-Plugin-Sdk -Dtest=JsonRpcWorkerTest test
```

Expected: FAIL because `library-noise` is currently written to the protocol stream.

- [ ] **Step 3: Implement stdout isolation**

Replace the no-argument `run()` with:

```java
public void run() throws Exception {
    InputStream protocolInput = System.in;
    OutputStream protocolOutput = System.out;
    System.setOut(System.err);
    run(protocolInput, protocolOutput);
}
```

Do not redirect inside `run(InputStream, OutputStream)`; tests and embedded callers depend on explicit stream injection.

- [ ] **Step 4: Run the SDK tests and verify GREEN**

```bash
mvn -pl FengYu-Plugin-Sdk -Dtest=JsonRpcWorkerTest test
```

Expected: both SDK tests pass.

- [ ] **Step 5: Commit**

```bash
git add FengYu-Plugin-Sdk/src/main/java/fan/summer/fengyu/sdk/JsonRpcWorker.java \
        FengYu-Plugin-Sdk/src/test/java/fan/summer/fengyu/sdk/JsonRpcWorkerTest.java
git commit -m "🐛 fix(plugin-sdk): isolate JSON-RPC stdout"
```

## Task 2: Make the Host Worker Parser Defensive

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java`
- Modify: `FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginProcessManagerTest.java`

- [ ] **Step 1: Extend the test worker with noisy protocol scenarios**

Change `EchoWorker` so the requested method controls its output:

```java
String method = line.contains("\"method\":\"error\"") ? "error"
    : line.contains("\"method\":\"eof\"") ? "eof" : "echo";
if ("eof".equals(method)) return;
System.out.println("third-party diagnostic line");
System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"other\",\"result\":{}}");
if ("error".equals(method)) {
    System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
        + "\",\"error\":{\"code\":-32000,\"message\":\"bad workbook\"}}");
} else {
    System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
        + "\",\"result\":{\"value\":\"ok\"}}");
}
System.out.flush();
```

Add tests:

```java
@Test void ignoresNoiseAndMismatchedIds() throws Exception {
    try (PluginProcessManager manager = manager()) {
        @SuppressWarnings("unchecked") Map<String, Object> result =
            (Map<String, Object>) manager.invoke("com.example.worker", "echo", Map.of());
        assertEquals("ok", result.get("value"));
    }
}
@Test void preservesRpcErrorMessage() throws Exception {
    try (PluginProcessManager manager = manager()) {
        var error = assertThrows(IllegalArgumentException.class,
            () -> manager.invoke("com.example.worker", "error", Map.of()));
        assertTrue(error.getMessage().contains("bad workbook"));
    }
}
@Test void reportsWorkerEof() throws Exception {
    try (PluginProcessManager manager = manager()) {
        var error = assertThrows(IllegalStateException.class,
            () -> manager.invoke("com.example.worker", "eof", Map.of()));
        assertTrue(error.getMessage().contains("stopped unexpectedly"));
    }
}
```

Factor the existing package setup into a `manager()` helper so each test starts an isolated worker.

- [ ] **Step 2: Run and verify RED**

```bash
mvn -pl FengYu -am -Dtest=PluginProcessManagerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: noisy output causes Jackson parsing failure.

- [ ] **Step 3: Implement response scanning**

Inside `Worker.invoke`, replace the single `readLine()` parse with:

```java
for (String line; (line = reader.readLine()) != null;) {
    JsonNode response;
    try {
        response = json.readTree(line);
    } catch (IOException invalidJson) {
        log.warn("Plugin {} emitted non-JSON stdout: {}", pluginId, abbreviate(line));
        continue;
    }
    if (!id.equals(response.path("id").asText())) {
        log.warn("Plugin {} returned response for unexpected id={}", pluginId,
            response.path("id").asText("<missing>"));
        continue;
    }
    if (response.hasNonNull("error")) {
        throw new PluginRpcException(pluginId, method,
            response.path("error").path("message").asText("Plugin call failed"));
    }
    return json.treeToValue(response.get("result"), Object.class);
}
throw new IllegalStateException("Plugin backend stopped unexpectedly: " + pluginId);
```

Pass `pluginId` into `Worker` and `method` into the read path. Add an `abbreviate` helper capped at 240 characters. Do not log request params or file contents.

Change the stderr drain to log bounded lines:

```java
while ((line = errors.readLine()) != null) {
    log.debug("Plugin {} stderr: {}", id, abbreviate(line));
}
```

Add a package-private `PluginRpcException extends RuntimeException` carrying plugin ID and method.

- [ ] **Step 4: Run and verify GREEN**

```bash
mvn -pl FengYu -am -Dtest=PluginProcessManagerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all noisy worker tests pass.

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java \
        FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginProcessManagerTest.java
git commit -m "🐛 fix(plugin): tolerate noisy worker output"
```

## Task 3: Return Stable Runtime API Errors

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/web/GlobalExceptionHandler.java`
- Create: `FengYu/src/test/java/fan/summer/fengyu/web/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Write the failing exception mapping test**

```java
@Test
void mapsPluginRuntimeFailuresToSafeJson() {
    var response = new GlobalExceptionHandler().handlePluginFailure(
        new IllegalStateException("Plugin RPC failed: bad workbook"));
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals(false, response.getBody().get("success"));
    assertEquals("Plugin RPC failed: bad workbook", response.getBody().get("error"));
}
```

- [ ] **Step 2: Run and verify RED**

```bash
mvn -pl FengYu -am -Dtest=GlobalExceptionHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compile failure because `handlePluginFailure` does not exist.

- [ ] **Step 3: Add the 500 JSON handler**

```java
@ExceptionHandler(IllegalStateException.class)
public ResponseEntity<Map<String, Object>> handlePluginFailure(IllegalStateException e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) message = "Plugin runtime failed";
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("success", false, "error", message));
}
```

The handler must not include stack traces or nested exception objects.

- [ ] **Step 4: Run and verify GREEN**

```bash
mvn -pl FengYu -am -Dtest=GlobalExceptionHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/web/GlobalExceptionHandler.java \
        FengYu/src/test/java/fan/summer/fengyu/web/GlobalExceptionHandlerTest.java
git commit -m "🐛 fix(web): surface plugin runtime errors"
```

## Task 4: Validate Excel Mode Configuration Server-Side

**Files:**
- Modify: `OfficialPlugins/plugin-excel/src/main/java/fan/summer/fengyu/plugin/excel/ExcelPlugin.java`
- Modify: `OfficialPlugins/plugin-excel/src/test/java/fan/summer/fengyu/plugin/excel/ExcelPluginTest.java`

- [ ] **Step 1: Add failing validation tests**

Add tests after analyzing the fixture workbook:

```java
@Test void bySheetRejectsEmptySelection() throws Exception {
    plugin.invoke("analyze", Map.of("session", "sheet", "sourceFile", src.toString()));
    assertThrows(IllegalArgumentException.class, () -> plugin.invoke("configure",
        Map.of("session", "sheet", "mode", "BY_SHEET", "selectedSheets", List.of())));
}
@Test void byColumnRequiresKnownSheetAndColumn() throws Exception {
    plugin.invoke("analyze", Map.of("session", "column", "sourceFile", src.toString()));
    assertThrows(IllegalArgumentException.class, () -> plugin.invoke("configure",
        Map.of("session", "column", "mode", "BY_COLUMN", "splitSheet", "Missing",
            "splitColumn", "region", "splitColumnIndex", 0)));
}
@Test void complexRequiresAtLeastOneValidEntry() throws Exception {
    plugin.invoke("analyze", Map.of("session", "complex-empty", "sourceFile", src.toString()));
    assertThrows(IllegalArgumentException.class, () -> plugin.invoke("configure",
        Map.of("session", "complex-empty", "mode", "COMPLEX", "complexEntries", List.of())));
}
@Test void complexAcceptsWholeSheetCopySentinel() {
    plugin.invoke("configure", Map.of(
        "session", "complex", "mode", "COMPLEX",
        "complexEntries", List.of(Map.of(
            "fieldName", "in.xlsx", "sheetName", "Alpha",
            "headerIndex", -1, "columnIndex", -1))));
}
@Test void complexRejectsMixedNegativeIndexes() throws Exception {
    plugin.invoke("analyze", Map.of("session", "mixed", "sourceFile", src.toString()));
    for (List<Integer> indexes : List.of(List.of(-1, 2), List.of(2, -1))) {
        var entry = Map.of("fieldName", "in.xlsx", "sheetName", "Alpha",
            "headerIndex", indexes.get(0), "columnIndex", indexes.get(1));
        assertThrows(IllegalArgumentException.class, () -> plugin.invoke("configure",
            Map.of("session", "mixed", "mode", "COMPLEX", "complexEntries", List.of(entry))));
    }
}
```

- [ ] **Step 2: Run and verify RED**

```bash
mvn -pl OfficialPlugins/plugin-excel -am -Dtest=ExcelPluginTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: invalid configurations are currently accepted.

- [ ] **Step 3: Implement mode-specific validators**

After applying incoming fields, call:

```java
switch (cfg.mode) {
    case BY_SHEET -> validateSelectedSheets(cfg);
    case BY_COLUMN -> validateColumn(cfg);
    case COMPLEX -> validateComplex(cfg);
}
```

Rules:

```java
// BY_SHEET
if (cfg.selectedSheets == null || cfg.selectedSheets.isEmpty())
    throw new IllegalArgumentException("Select at least one sheet");
if (!cfg.analysisResult.keySet().containsAll(cfg.selectedSheets))
    throw new IllegalArgumentException("Selected sheet does not exist");

// BY_COLUMN
Map<Integer, String> headers = cfg.analysisResult.get(cfg.splitSheet);
if (headers == null) throw new IllegalArgumentException("Select a valid sheet");
if (cfg.splitColumnIndex < 0 || !headers.containsKey(cfg.splitColumnIndex))
    throw new IllegalArgumentException("Select a valid split column");

// COMPLEX
if (cfg.complexEntries == null || cfg.complexEntries.isEmpty())
    throw new IllegalArgumentException("Add at least one complex rule");
for (ComplexSplitEntry entry : cfg.complexEntries) {
    if (!cfg.analysisResult.containsKey(entry.sheetName()))
        throw new IllegalArgumentException("Complex rule sheet does not exist: " + entry.sheetName());
    boolean copyAll = entry.headerIndex() == -1 && entry.columnIndex() == -1;
    if (!copyAll && (entry.headerIndex() < 1 || entry.columnIndex() < 1))
        throw new IllegalArgumentException("Header row and split column must be positive integers");
}
```

For BY_SHEET, preserve 3.2.0 behavior by defaulting to all analyzed sheets only when the
`selectedSheets` key is absent. An explicitly supplied empty list remains invalid.

- [ ] **Step 4: Run and verify GREEN**

```bash
mvn -pl OfficialPlugins/plugin-excel -am -Dtest=ExcelPluginTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/plugin-excel/src/main/java/fan/summer/fengyu/plugin/excel/ExcelPlugin.java \
        OfficialPlugins/plugin-excel/src/test/java/fan/summer/fengyu/plugin/excel/ExcelPluginTest.java
git commit -m "🐛 fix(plugin-excel): validate split configuration"
```

## Task 5: Add a Real Excel Worker Protocol Regression Test

**Files:**
- Create: `OfficialPlugins/plugin-excel/src/test/java/fan/summer/fengyu/plugin/excel/ExcelWorkerMainTest.java`

- [ ] **Step 1: Write the worker round-trip test**

Create a workbook under `@TempDir`, then invoke a `JsonRpcWorker` configured with the same handlers as `ExcelWorkerMain`. To avoid duplicating registration, first extract this package-private factory:

```java
static JsonRpcWorker worker() {
    ExcelSessionStore sessions = new ExcelSessionStore();
    ExcelPlugin plugin = new ExcelPlugin(sessions);
    return new JsonRpcWorker()
        .on("analyze", p -> plugin.invoke("analyze", p))
        .on("configure", p -> plugin.invoke("configure", p))
        .on("split", p -> plugin.invoke("split", p));
}
```

The test sends newline-delimited analyze and configure requests through byte streams and asserts:

```java
assertEquals(2, output.lines().count());
assertTrue(output.contains("\"id\":\"analyze-1\""));
assertTrue(output.contains("\"success\":true"));
assertFalse(output.contains("Unexpected character"));
```

- [ ] **Step 2: Run and verify RED**

```bash
mvn -pl OfficialPlugins/plugin-excel -am -Dtest=ExcelWorkerMainTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compile failure until the factory is extracted.

- [ ] **Step 3: Extract handler registration and use it from `main`**

```java
public static void main(String[] args) throws Exception { worker().run(); }
```

Keep all six `excel_*` AI methods registered in the factory in addition to UI methods.

- [ ] **Step 4: Run and verify GREEN**

Run the same Maven command; expect the worker round-trip test to pass.

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/plugin-excel/src/main/java/fan/summer/fengyu/plugin/excel/ExcelWorkerMain.java \
        OfficialPlugins/plugin-excel/src/test/java/fan/summer/fengyu/plugin/excel/ExcelWorkerMainTest.java
git commit -m "✅ test(plugin-excel): cover worker protocol round trip"
```

## Task 6: Build the Pure Four-Step Wizard State Module

**Files:**
- Create: `OfficialPlugins/packages/excel/ui/wizard-state.js`
- Create: `OfficialPlugins/packages/excel/ui/wizard-state.test.mjs`

- [ ] **Step 1: Write failing state tests**

Cover these exported functions:

```javascript
import {
  initialState, canContinue, addComplexRule, removeComplexRule,
  configurePayload, confirmationRows,
} from './wizard-state.js'
```

Assertions:

```javascript
test('analysis gates step one', () => {
  assert.equal(canContinue({...initialState(), step:0}), false)
  assert.equal(canContinue({...initialState(), step:0, input:{id:'ref'}, sheets:{A:{0:'id'}}}), true)
})
test('by sheet requires one selected sheet', () => {
  const base = {...initialState(), step:1, input:{id:'ref'}, sheets:{A:{0:'id'}}}
  assert.equal(canContinue({...base, mode:'BY_SHEET', selectedSheets:[]}), false)
  assert.equal(canContinue({...base, mode:'BY_SHEET', selectedSheets:['A']}), true)
})
test('by column requires sheet and column', () => {
  const base = {...initialState(), step:1, input:{id:'ref'}, sheets:{A:{0:'id'}}, mode:'BY_COLUMN'}
  assert.equal(canContinue({...base, splitSheet:'A'}), false)
  assert.equal(canContinue({...base, splitSheet:'A', splitColumn:'id', splitColumnIndex:0}), true)
})
test('complex whole sheet stores -1 sentinels', () => {
  const next = addComplexRule(initialState(), {sheetName:'A', copyWholeSheet:true})
  assert.deepEqual(next.complexEntries[0], {
    fieldName:'', sheetName:'A', headerIndex:-1, columnIndex:-1,
  })
})
test('configure payload contains selectedSheets and complexEntries', () => {
  assert.deepEqual(configurePayload({...initialState(), session:'s', mode:'BY_SHEET', selectedSheets:['A']}),
    {session:'s', mode:'BY_SHEET', selectedSheets:['A']})
  const complexEntries = [{fieldName:'in.xlsx',sheetName:'A',headerIndex:-1,columnIndex:-1}]
  assert.deepEqual(configurePayload({...initialState(), session:'s', mode:'COMPLEX', complexEntries}),
    {session:'s', mode:'COMPLEX', complexEntries})
})
```

- [ ] **Step 2: Run and verify RED**

```bash
node --test OfficialPlugins/packages/excel/ui/wizard-state.test.mjs
```

Expected: module-not-found failure.

- [ ] **Step 3: Implement immutable state helpers**

Export:

```javascript
export function initialState() {
  return {
    step: 0, session: createLocalId(), input: null, sheets: null,
    mode: 'BY_SHEET', selectedSheets: [], splitSheet: '', splitColumn: '',
    splitColumnIndex: -1, complexEntries: [], output: null,
    phase: 'idle', error: '', result: null, exported: false,
  }
}
```

`canContinue` implements the design validation. `addComplexRule` normalizes whole-sheet rules to
`-1/-1` and rejects invalid normal indices. `configurePayload` emits only the fields required by the active mode. `confirmationRows` returns label/value pairs without HTML.

- [ ] **Step 4: Run and verify GREEN**

```bash
node --test OfficialPlugins/packages/excel/ui/wizard-state.test.mjs
```

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/packages/excel/ui/wizard-state.js \
        OfficialPlugins/packages/excel/ui/wizard-state.test.mjs
git commit -m "✨ feat(plugin-excel): add wizard state model"
```

## Task 7: Replace the Single-Page Excel UI with Four Panels

**Files:**
- Modify: `OfficialPlugins/packages/excel/ui/index.html`
- Modify: `OfficialPlugins/packages/excel/ui/app.js`
- Modify: `OfficialPlugins/packages/excel/ui/style.css`
- Modify: `OfficialPlugins/packages/excel/ui/wizard-state.test.mjs`

- [ ] **Step 1: Add failing structural assertions**

Read `index.html` in the Node test and assert exact IDs exist:

```javascript
for (const id of ['step-source','step-mode','step-confirm','step-run','back','next',
  'sheet-list','mode-by-sheet','mode-by-column','mode-complex','complex-list',
  'copy-whole-sheet','confirmation','result-files','retry','export-again']) {
  assert.match(html, new RegExp(`id=["']${id}["']`))
}
```

- [ ] **Step 2: Run and verify RED**

```bash
node --test OfficialPlugins/packages/excel/ui/wizard-state.test.mjs
```

- [ ] **Step 3: Write semantic four-panel markup**

Replace the body content with:

```html
<main>
  <header><h1>Excel Splitter</h1><p>Split a workbook in four guided steps.</p></header>
  <ol class="steps" aria-label="Progress">
    <li data-step="0" class="active">1 <span>Source</span></li>
    <li data-step="1">2 <span>Split settings</span></li>
    <li data-step="2">3 <span>Confirm &amp; output</span></li>
    <li data-step="3">4 <span>Run</span></li>
  </ol>
  <div id="message" role="status" aria-live="polite"></div>
  <section id="step-source" class="step-panel"></section>
  <section id="step-mode" class="step-panel" hidden></section>
  <section id="step-confirm" class="step-panel" hidden></section>
  <section id="step-run" class="step-panel" hidden></section>
  <footer><button id="back" type="button" hidden>Back</button><button id="next" type="button">Next</button></footer>
</main>
```

Fill each section with the controls named by the structural test. Use native `button`, `input`, `select` and checkbox elements, associated `<label for>`, a `<ul>` for result files, and no inline event handlers.

- [ ] **Step 4: Implement DOM orchestration in `app.js`**

Import state helpers and the SDK:

```javascript
import { fengyu, createId } from './sdk.js'
import { initialState, canContinue, addComplexRule, removeComplexRule,
  configurePayload, confirmationRows } from './wizard-state.js'
```

Implement these named functions with the following exact state transitions:

- `chooseSource`: call `fengyu.files.open`, replace state with `initialState()`, assign a new
  `createId()` session and selected `FileRef`, set `phase='analyzing'`, invoke `analyze`, store
  `result.sheets`, default `selectedSheets` to all keys, then set `phase='idle'`; on rejection set
  `phase='error'` and `error=message`.
- `render`: set `hidden` on every `.step-panel` according to `state.step`; mark indicators below
  the current step `done` and the current one `active`; derive Back/Next visibility and disabled
  state from `busy` and `canContinue(state)`; update the live message, confirmation rows and result list.
- `renderModeDetail`: show only the active mode container; rebuild sheet checkboxes and select
  options from `state.sheets`; preserve selections that still exist.
- `addRuleFromForm`: read the sheet and whole-sheet toggle; emit `-1/-1` for whole-sheet rules or
  parse positive integer inputs; call `addComplexRule` and rerender the rule list.
- `advance`: at step 1 invoke `configurePayload(state)` before incrementing; at step 2 call
  `fengyu.files.outputDirectory`, store the returned `DirectoryRef`, increment to step 3 and call
  `runSplit`; at other steps increment once after validation.
- `runSplit`: return immediately while busy; set `phase='running'`; invoke `split`; store result and
  set `phase='success'`; when platform is Web call `fengyu.files.export(state.output)` once and set
  `exported=true`; on rejection set `phase='error'` without clearing configuration.
- `back`: decrement one step, clear only transient error text, and never invoke split.
- `retry`: clear result/error/exported, keep source/config/output, and call `runSplit`.

Use one `busy` flag to prevent duplicate analyze/configure/split requests. Derive platform from `await fengyu.ready()` and export automatically only when `env.platform === 'web'`.

- [ ] **Step 5: Implement responsive theme-aware CSS**

Keep the existing dark/light variables. Add:

```css
.step-panel[hidden]{display:none}
.steps{display:grid;grid-template-columns:repeat(4,minmax(0,1fr))}
.steps li.done,.steps li.active{color:var(--text)}
.mode-card{display:grid;grid-template-columns:auto 1fr;align-items:start}
.mode-card:has(input:checked){border-color:var(--accent)}
.rule-row,.summary-row{display:grid;grid-template-columns:minmax(110px,.4fr) 1fr;gap:12px}
footer{display:flex;justify-content:space-between;gap:10px;margin-top:20px}
@media(max-width:600px){.steps span{display:none}.rule-row,.summary-row{grid-template-columns:1fr}}
```

Use only theme variables for colors. Preserve visible keyboard focus and disabled states.

- [ ] **Step 6: Run UI tests and verify GREEN**

```bash
node --test OfficialPlugins/packages/excel/ui/wizard-state.test.mjs
```

- [ ] **Step 7: Commit**

```bash
git add OfficialPlugins/packages/excel/ui/index.html \
        OfficialPlugins/packages/excel/ui/app.js \
        OfficialPlugins/packages/excel/ui/style.css \
        OfficialPlugins/packages/excel/ui/wizard-state.test.mjs
git commit -m "✨ feat(plugin-excel): restore four-step wizard"
```

## Task 8: Gate Package Builds on UI Tests

**Files:**
- Modify: `OfficialPlugins/build-packages.sh`

- [ ] **Step 1: Prove the script currently omits UI tests**

```bash
rg "wizard-state.test" OfficialPlugins/build-packages.sh
```

Expected: no match.

- [ ] **Step 2: Add the test before packaging**

Immediately after SDK build:

```bash
node --test "$ROOT/OfficialPlugins/packages/excel/ui/wizard-state.test.mjs"
```

- [ ] **Step 3: Build all worker jars and packages**

```bash
mvn -pl OfficialPlugins -am package
./OfficialPlugins/build-packages.sh
unzip -l OfficialPlugins/target/packages/fan.summer.excel-4.0.0.fyp | \
  rg 'ui/(index.html|app.js|style.css|wizard-state.js|sdk.js)|backend/worker.jar'
```

Expected: UI tests pass and all six runtime files are present in the archive.

- [ ] **Step 4: Commit**

```bash
git add OfficialPlugins/build-packages.sh
git commit -m "✅ test(plugin-excel): gate package build on UI tests"
```

## Task 9: Full Verification and Browser Closure

**Files:**
- No production file changes expected.

- [ ] **Step 1: Run all automated suites**

```bash
mvn test
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix plugin-sdk/typescript test
npm --prefix plugin-cli test
node --test OfficialPlugins/packages/excel/ui/wizard-state.test.mjs
```

Expected: every command exits 0.

- [ ] **Step 2: Install the newly built package into the running dev host**

```bash
curl -fsS -X POST \
  -F file=@OfficialPlugins/target/packages/fan.summer.excel-4.0.0.fyp \
  http://127.0.0.1:24056/api/plugin-market/upload
```

Expected: HTTP 201 with manifest ID `fan.summer.excel`.

- [ ] **Step 3: Execute the Web browser closure**

Start the frontend and verify in a real browser:

1. Open `/plugin/fan.summer.excel` and confirm only step 1 is visible.
2. Upload a workbook and confirm automatic analysis advances/enables step 2.
3. Exercise BY_SHEET selection validation.
4. Exercise BY_COLUMN sheet-to-column cascading selection.
5. Add and remove a COMPLEX normal rule, then add a whole-sheet rule and verify `-1/-1` in the configure request.
6. Confirm the summary in step 3 and create the Web output directory.
7. Execute split, verify file list, and verify ZIP download.
8. Confirm network calls return JSON bodies and no `/invoke` request returns the original Jackson parse 500.

- [ ] **Step 4: Verify protocol failure presentation**

Invoke the noisy worker fixture from `PluginProcessManagerTest` and confirm the intentional RPC failure returns exactly `{success:false,error:"bad workbook"}`.

- [ ] **Step 5: Review the final diff**

```bash
git status --short
git diff --check
git log --oneline -10
```

Expected: only intended files changed, no whitespace errors, and each completed task has its own commit.

## Plan Self-Review

- **Spec coverage:** Tasks 1–3 cover stdout isolation, defensive parsing, stderr and HTTP errors; Tasks 4–5 cover Excel backend validation and the reported worker path; Tasks 6–8 cover the approved four-step UI, all modes, explicit whole-sheet copy, platform-aware output and packaging; Task 9 covers Web closure and regression verification.
- **Type consistency:** UI payload names match `ExcelPlugin.configure`: `selectedSheets`, `splitSheet`, `splitColumn`, `splitColumnIndex`, `complexEntries`. File capability objects remain `FileRef`/`DirectoryRef` and are resolved by the host before worker invocation.
- **Scope:** The plan does not add Vue to the plugin, a host wizard abstraction, database persistence, fabricated progress percentages or split-engine refactoring.
