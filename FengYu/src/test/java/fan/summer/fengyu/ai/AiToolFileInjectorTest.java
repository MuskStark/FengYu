package fan.summer.fengyu.ai;

import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolFileInjectorTest {

    private static ActiveFileRef excelFileRef() {
        return new ActiveFileRef("fan.summer.excel",
            new FileRef("ref_3f2a", "report.xlsx", "file", "read", 123L));
    }

    // ── classification ───────────────────────────────────────────────

    @Test
    void classifyReadFileByDescription() {
        Map<String, Object> schema = Map.of("type", "object", "description", "A FengYu FileRef");
        assertEquals(AiToolFileInjector.FileParamClass.READ_FILE,
            AiToolFileInjector.classifyParam("filePath", schema));
    }

    @Test
    void classifyWriteDirByDescription() {
        Map<String, Object> schema = Map.of("type", "object", "description", "A writable FengYu DirectoryRef");
        assertEquals(AiToolFileInjector.FileParamClass.WRITE_DIR,
            AiToolFileInjector.classifyParam("outputDir", schema));
    }

    @Test
    void classifyReadDirByDescription() {
        Map<String, Object> schema = Map.of("type", "object", "description", "A FengYu DirectoryRef");
        assertEquals(AiToolFileInjector.FileParamClass.READ_DIR,
            AiToolFileInjector.classifyParam("projectDir", schema));
    }

    @Test
    void classifyFallsBackToParamNameWhenNoDescription() {
        // email plugin's inputDirectory/outputDirectory have NO description
        assertEquals(AiToolFileInjector.FileParamClass.READ_DIR,
            AiToolFileInjector.classifyParam("inputDirectory", Map.of("type", "object")));
        assertEquals(AiToolFileInjector.FileParamClass.WRITE_DIR,
            AiToolFileInjector.classifyParam("outputDirectory", Map.of("type", "object")));
    }

    @Test
    void classifyFallsBackForOlderExcelManifestDescriptions() {
        assertEquals(AiToolFileInjector.FileParamClass.READ_FILE,
            AiToolFileInjector.classifyParam("filePath", Map.of(
                "type", "object", "description", "Workbook selected from FengYu files")));
        assertEquals(AiToolFileInjector.FileParamClass.WRITE_DIR,
            AiToolFileInjector.classifyParam("outputDir", Map.of(
                "type", "object", "description", "Writable directory selected from FengYu files")));
    }

    @Test
    void classifyIgnoresNonFileParams() {
        Map<String, Object> schema = Map.of("type", "string", "description", "Optional path to a Python executable");
        assertEquals(AiToolFileInjector.FileParamClass.NONE,
            AiToolFileInjector.classifyParam("executable", schema));
    }

    // ── injection: B path ────────────────────────────────────────────

    @Test
    void injectsSingleReadFileParamWhenGrantMatches() {
        String schema = "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}},\"required\":[\"filePath\"]}";
        Map<String, Object> modelParams = new java.util.LinkedHashMap<>(Map.of("filePath", Map.of("id", "model-magic", "name", "ignored")));

        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            modelParams, "fan.summer.excel", schema, List.of(excelFileRef()));

        @SuppressWarnings("unchecked") Map<String, Object> injected = (Map<String, Object>) out.get("filePath");
        assertEquals("ref_3f2a", injected.get("id"));
        assertEquals("report.xlsx", injected.get("name"));
        assertEquals("file", injected.get("kind"));
        assertEquals("read", injected.get("access"));
        assertEquals(123L, injected.get("size"));
    }

    // ── injection: degrade to A ──────────────────────────────────────

    @Test
    void doesNotInjectWhenPluginIdMismatch() {
        String schema = "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}}}";
        Map<String, Object> modelParams = new java.util.LinkedHashMap<>(Map.of("filePath", "model-value"));

        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            modelParams, "fan.summer.excel", schema,
            List.of(new ActiveFileRef("fan.summer.offlinepython",
                new FileRef("ref_9b1c", "proj", "directory", "read", 0L))));

        assertEquals("model-value", out.get("filePath"));
    }

    @Test
    void doesNotInjectWhenKindMismatch() {
        // tool wants a read-dir, grant is a file
        String schema = "{\"type\":\"object\",\"properties\":{\"projectDir\":{\"type\":\"object\",\"description\":\"A FengYu DirectoryRef\"}}}";
        Map<String, Object> modelParams = new java.util.LinkedHashMap<>(Map.of("projectDir", "model-value"));

        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            modelParams, "fan.summer.offlinepython", schema,
            List.of(new ActiveFileRef("fan.summer.offlinepython",
                new FileRef("ref_1", "f.py", "file", "read", 1L))));

        assertEquals("model-value", out.get("projectDir"));
    }

    @Test
    void injectsSingleWriteDirParamWhenWritableGrantMatches() {
        String schema = "{\"type\":\"object\",\"properties\":{\"outputDir\":{\"type\":\"object\",\"description\":\"A writable FengYu DirectoryRef\"}}}";
        Map<String, Object> modelParams = new java.util.LinkedHashMap<>(Map.of("outputDir", "model-value"));
        ActiveFileRef output = new ActiveFileRef("fan.summer.excel",
            new FileRef("ref_out", "results", "directory", "read-write", 0L));

        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            modelParams, "fan.summer.excel", schema, List.of(excelFileRef(), output));

        @SuppressWarnings("unchecked") Map<String, Object> injected = (Map<String, Object>) out.get("outputDir");
        assertEquals("ref_out", injected.get("id"));
        assertEquals("directory", injected.get("kind"));
        assertEquals("read-write", injected.get("access"));
    }

    @Test
    void doesNotInjectReadOnlyDirectoryForWriteDirParam() {
        String schema = "{\"type\":\"object\",\"properties\":{\"outputDir\":{\"type\":\"object\",\"description\":\"A writable FengYu DirectoryRef\"}}}";
        ActiveFileRef output = new ActiveFileRef("fan.summer.excel",
            new FileRef("ref_out", "results", "directory", "read", 0L));
        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            Map.of("outputDir", "model-value"), "fan.summer.excel", schema, List.of(output));
        assertEquals("model-value", out.get("outputDir"));
    }

    @Test
    void doesNotInjectWhenMultipleFileParams() {
        // email_send_batch has inputDirectory + commonAttachments(array of object).
        // Give commonAttachments items a FileRef description so the array classifies
        // as FILE_LIST, making fileParamNames genuinely size 2 and exercising the
        // degrade-to-A branch (size() != 1).
        String schema = "{\"type\":\"object\",\"properties\":{\"inputDirectory\":{\"type\":\"object\"},\"commonAttachments\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}}}}";
        Map<String, Object> modelParams = new java.util.LinkedHashMap<>(Map.of("inputDirectory", "a", "commonAttachments", List.of()));

        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            modelParams, "fan.summer.email", schema, List.of());

        assertEquals("a", out.get("inputDirectory"));
    }

    @Test
    void doesNotInjectWhenNoMatchingGrant() {
        String schema = "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}}}";
        Map<String, Object> modelParams = new java.util.LinkedHashMap<>(Map.of("filePath", "model-value"));

        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            modelParams, "fan.summer.excel", schema, List.of());

        assertEquals("model-value", out.get("filePath"));
    }

    @Test
    void passesThroughWhenNoFileParams() {
        String schema = "{\"type\":\"object\",\"properties\":{\"mode\":{\"type\":\"string\"}}}";
        Map<String, Object> modelParams = new java.util.LinkedHashMap<>(Map.of("mode", "BY_SHEET"));

        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            modelParams, "fan.summer.excel", schema, List.of(excelFileRef()));

        assertEquals("BY_SHEET", out.get("mode"));
        assertNull(out.get("filePath"));
    }

    // ── blank write-dir default (canvas workflows) ──────────────────

    private static final String WRITE_DIR_SCHEMA =
        "{\"type\":\"object\",\"properties\":{"
        + "\"outputDir\":{\"type\":\"string\",\"description\":\"Resolved absolute path of a writable FengYu DirectoryRef; leave empty to write into the plugin default output folder.\"}"
        + "},\"required\":[\"outputDir\"]}";

    @Test
    void blankWriteDirParamDetectedAndFilled() {
        assertEquals("outputDir", AiToolFileInjector.blankWriteDirParam(Map.of(), WRITE_DIR_SCHEMA));
        assertEquals("outputDir",
            AiToolFileInjector.blankWriteDirParam(Map.of("outputDir", "  "), WRITE_DIR_SCHEMA));

        // A plain path is injected (not a FileRef): registering a grant here would restart
        // stateful plugin workers, so the default must stay grant-free.
        Map<String, Object> filled = AiToolFileInjector.fillDefaultOutputDir(
            new java.util.LinkedHashMap<>(Map.of("filePrefix", "p")),
            WRITE_DIR_SCHEMA, "/data/plugin-x/default-output");
        assertEquals("/data/plugin-x/default-output", filled.get("outputDir"));
        assertEquals("p", filled.get("filePrefix"));
        assertNull(AiToolFileInjector.fillDefaultOutputDir(
            new java.util.LinkedHashMap<>(Map.of()), WRITE_DIR_SCHEMA, null).get("outputDir"));
    }

    @Test
    void blankWriteDirParamNotFilledWhenValueOrRefPresent() {
        // A typed path stays — the sandbox may allow it, and the user asked for it.
        assertNull(AiToolFileInjector.blankWriteDirParam(
            Map.of("outputDir", "/tmp/out"), WRITE_DIR_SCHEMA));
        // An already-injected FileRef map stays.
        assertNull(AiToolFileInjector.blankWriteDirParam(
            Map.of("outputDir", Map.of("id", "ref_x")), WRITE_DIR_SCHEMA));
        // No file params at all → nothing to default.
        assertNull(AiToolFileInjector.blankWriteDirParam(Map.of(),
            "{\"type\":\"object\",\"properties\":{\"mode\":{\"type\":\"string\"}}}"));
    }

    // ── run file placeholder binding ────────────────────────────────

    @Test
    void bindsPlaceholderToCurrentPluginsFileRef() {
        ActiveFileRef excelRef = new ActiveFileRef("fan.summer.excel",
            new FileRef("ref_wb", "report.xlsx", "file", "read", 10L));
        ActiveFileRef emailRef = new ActiveFileRef("fan.summer.email",
            new FileRef("ref_wb2", "report.xlsx", "file", "read", 10L));
        Map<String, Object> params = new java.util.LinkedHashMap<>(
            Map.of("filePath", "@file:workbook", "mode", "COMPLEX"));

        Map<String, Object> bound = AiToolFileInjector.bindRunFilePlaceholders(
            params, "fan.summer.excel", Map.of("workbook", List.of(excelRef, emailRef)));

        @SuppressWarnings("unchecked") Map<String, Object> ref = (Map<String, Object>) bound.get("filePath");
        assertEquals("ref_wb", ref.get("id"));
        assertEquals("read", ref.get("access"));
        assertEquals("COMPLEX", bound.get("mode"));
    }

    @Test
    void bindsNestedPlaceholdersAndLeavesPlainArgsUntouched() {
        ActiveFileRef shared = new ActiveFileRef("fan.summer.email",
            new FileRef("ref_dir", "out", "directory", "read", 0L));
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("inputDirectory", "@file:outputDir");
        params.put("subject", "Monthly report");
        params.put("entries", List.of(Map.of("sheetName", "Sales", "columnName", "部门")));

        Map<String, Object> bound = AiToolFileInjector.bindRunFilePlaceholders(
            params, "fan.summer.email", Map.of("outputDir", List.of(shared)));

        @SuppressWarnings("unchecked") Map<String, Object> ref = (Map<String, Object>) bound.get("inputDirectory");
        assertEquals("ref_dir", ref.get("id"));
        assertEquals("Monthly report", bound.get("subject"));
        assertEquals(1, ((List<?>) bound.get("entries")).size());
    }

    @Test
    void unknownInputOrPluginFailsLoudly() {
        Map<String, Object> params = new java.util.LinkedHashMap<>(Map.of("filePath", "@file:workbook"));

        var missingInput = assertThrows(IllegalArgumentException.class,
            () -> AiToolFileInjector.bindRunFilePlaceholders(params, "fan.summer.excel", Map.of()));
        assertTrue(missingInput.getMessage().contains("workbook"));

        ActiveFileRef otherPlugin = new ActiveFileRef("fan.summer.excel",
            new FileRef("ref_wb", "report.xlsx", "file", "read", 10L));
        var wrongPlugin = assertThrows(IllegalArgumentException.class,
            () -> AiToolFileInjector.bindRunFilePlaceholders(params, "fan.summer.email",
                Map.of("workbook", List.of(otherPlugin))));
        assertTrue(wrongPlugin.getMessage().contains("fan.summer.email"));
    }

    @Test
    void nonPlaceholderStringsPassThroughUnchanged() {
        Map<String, Object> params = new java.util.LinkedHashMap<>(
            Map.of("path", "@file:with spaces/odd chars", "note", "@file:"));
        Map<String, Object> bound = AiToolFileInjector.bindRunFilePlaceholders(
            params, "fan.summer.excel", Map.of());
        assertEquals(params, bound);
    }
}
