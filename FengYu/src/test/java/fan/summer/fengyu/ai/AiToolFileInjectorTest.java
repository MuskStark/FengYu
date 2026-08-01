package fan.summer.fengyu.ai;

import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void doesNotInjectForWriteDirParam() {
        String schema = "{\"type\":\"object\",\"properties\":{\"outputDir\":{\"type\":\"object\",\"description\":\"A writable FengYu DirectoryRef\"}}}";
        Map<String, Object> modelParams = new java.util.LinkedHashMap<>(Map.of("outputDir", "model-value"));

        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            modelParams, "fan.summer.excel", schema, List.of(excelFileRef()));

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
}
