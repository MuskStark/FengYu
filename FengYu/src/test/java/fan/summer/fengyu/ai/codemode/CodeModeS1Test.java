package fan.summer.fengyu.ai.codemode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 pure surface: the pragma matrix (codex description_override_tests semantics), the
 * schema→TypeScript renderer snapshots (json_schema_types_tests semantics), and the
 * description templates — including the no-codex-terms check.
 */
class CodeModeS1Test {

    // ── pragma ──────────────────────────────────────────────────────────────────────────

    @Test
    void sourceWithoutPragmaPassesThroughUntouched() {
        ExecPragma.ExecSource parsed = ExecPragma.parse("text('hi')");
        assertNull(parsed.pragma());
        assertEquals("text('hi')", parsed.source());
    }

    @Test
    void pragmaOnFirstLineSplitsOff() {
        ExecPragma.ExecSource parsed =
                ExecPragma.parse("// @exec: {\"yield_time_ms\": 10}\ntext('hi')");
        assertEquals(10L, parsed.pragma().yieldTimeMs());
        assertNull(parsed.pragma().maxOutputTokens());
        assertEquals("text('hi')", parsed.source());
    }

    @Test
    void malformedPragmasCarryTheCodexShapedErrors() {
        // pragma without following source
        assertThrows(IllegalArgumentException.class,
                () -> ExecPragma.parse("// @exec: {\"yield_time_ms\": 10}"));
        assertThrows(IllegalArgumentException.class,
                () -> ExecPragma.parse("// @exec: {\"yield_time_ms\": 10}\n"));
        // unknown field
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> ExecPragma.parse("// @exec: {\"yield_time_ms\": 1, \"evil\": true}\nx()"));
        assertTrue(unknown.getMessage().contains("supported fields"), unknown.getMessage());
        // not an object
        assertThrows(IllegalArgumentException.class,
                () -> ExecPragma.parse("// @exec: [1,2]\nx()"));
        // invalid JSON
        assertThrows(IllegalArgumentException.class,
                () -> ExecPragma.parse("// @exec: {nope}\nx()"));
        // negative / fractional / unsafe integers
        assertThrows(IllegalArgumentException.class,
                () -> ExecPragma.parse("// @exec: {\"yield_time_ms\": -1}\nx()"));
        assertThrows(IllegalArgumentException.class,
                () -> ExecPragma.parse("// @exec: {\"yield_time_ms\": 1.5}\nx()"));
        assertThrows(IllegalArgumentException.class,
                () -> ExecPragma.parse(
                        "// @exec: {\"yield_time_ms\": 9007199254740993}\nx()"));
    }

    // ── schema → TypeScript ─────────────────────────────────────────────────────────────

    @Test
    void primitiveAndCompositeSnapshots() {
        assertEquals("string", JsonSchemaToTs.render("{\"type\":\"string\"}"));
        assertEquals("number", JsonSchemaToTs.render("{\"type\":\"integer\"}"));
        assertEquals("boolean", JsonSchemaToTs.render("{\"type\":\"boolean\"}"));
        assertEquals("\"red\" | \"green\"", JsonSchemaToTs.render(
                "{\"type\":\"string\",\"enum\":[\"red\",\"green\"]}"));
        assertEquals("string | number", JsonSchemaToTs.render(
                "{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}"));
        assertEquals("string[]", JsonSchemaToTs.render(
                "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"));
        assertEquals("(string | number)[]", JsonSchemaToTs.render(
                "{\"type\":\"array\",\"items\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}}"));
        assertEquals("Record<string, unknown>", JsonSchemaToTs.render("{\"type\":\"object\"}"));
        assertEquals("unknown", JsonSchemaToTs.render("{}"));
        assertEquals("unknown", JsonSchemaToTs.render("not json"));
    }

    @Test
    void objectPropertiesCarryOptionalityFromRequired() {
        String rendered = JsonSchemaToTs.render("""
                {"type":"object","properties":{
                  "command":{"type":"string"},
                  "cwd":{"type":"string"},
                  "timeoutSeconds":{"type":"integer"}},
                 "required":["command"]}""");
        assertTrue(rendered.startsWith("{\n"), rendered);
        assertTrue(rendered.contains("  command: string;"), rendered);
        assertTrue(rendered.contains("  cwd?: string;"), rendered);
        assertTrue(rendered.contains("  timeoutSeconds?: number;"), rendered);
    }

    // ── descriptions ────────────────────────────────────────────────────────────────────

    @Test
    void execDescriptionCarriesTheNestedToolDeclaration() {
        String description = CodeModeToolDescription.execDescription(List.of(
                new CodeModeToolDescription.NestedTool("workspace_exec",
                        "Run a shell command inside the workspace",
                        "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}},"
                                + "\"required\":[\"command\"]}")), 10_000);

        assertTrue(description.contains("await tools.workspace_exec("));
        assertTrue(description.contains("command: string;"), description);
        assertTrue(description.contains("Defaults to 10000 ms"));
        assertTrue(description.contains("`store(key: string, value: any)`"));
        // No codex-specific vocabulary may survive the adaptation.
        assertTrue(!description.toLowerCase().contains("codex")
                && !description.toLowerCase().contains("v8 isolate")
                && !description.toLowerCase().contains("ologs"),
                "template must reference FengYu surfaces only");
    }

    @Test
    void toolNamesNormalizeToJsIdentifiers() {
        assertEquals("workspace_exec", CodeModeToolDescription.identifier("workspace_exec"));
        assertEquals("mcp__ologs__get_profile",
                CodeModeToolDescription.identifier("mcp__ologs__get_profile"));
        assertEquals("a_b_c", CodeModeToolDescription.identifier("a-b.c"));
        assertEquals("_1step", CodeModeToolDescription.identifier("1step"));
    }

    @Test
    void waitDescriptionMatchesTheContract() {
        String wait = CodeModeToolDescription.waitDescription();
        assertTrue(wait.contains("`terminate: true` stops the running cell"));
        assertTrue(wait.contains("only the new output since the last yield"));
    }
}
