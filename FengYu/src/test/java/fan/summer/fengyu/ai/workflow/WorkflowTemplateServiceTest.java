package fan.summer.fengyu.ai.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowTemplateServiceTest {

    private final WorkflowTemplateService service = new WorkflowTemplateService();

    @Test
    void listsBuiltInTemplatesWithRequiredTools() {
        List<Map<String, Object>> templates = service.list("en");
        assertTrue(templates.size() >= 4, "the four built-in templates ship");
        Map<String, Object> jsonTidy = templates.stream()
                .filter(template -> "json-tidy".equals(template.get("id")))
                .findFirst().orElseThrow();
        assertEquals(List.of("json_format"), jsonTidy.get("requiredTools"));
        assertNotNull(jsonTidy.get("name"));
    }

    @Test
    void documentCarriesAGraphOnlyFyfEnvelope() {
        Map<String, Object> document = service.document("excel-email", "en");
        assertEquals("fengyu-flow", document.get("format"));
        assertEquals(1, document.get("schemaVersion"));
        assertNotNull(document.get("graph"));
        assertFalse(document.containsKey("plan"),
                "templates never ship a derived plan — the builder compiles on apply");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>)
                ((Map<String, Object>) document.get("graph")).get("nodes");
        assertTrue(nodes.size() >= 4);
    }

    @Test
    void chineseVariantServesLocalizedNames() {
        Object english = service.document("json-tidy", "en").get("name");
        Object chinese = service.document("json-tidy", "zh-CN").get("name");
        assertFalse(english.equals(chinese), "zh variant must be a full localized twin");
    }

    @Test
    @SuppressWarnings("unchecked")
    void chineseAndCanonicalVariantsStayStructurallyAligned() {
        for (Map<String, Object> summary : service.list("en")) {
            String id = (String) summary.get("id");
            // A missing zh variant silently falls back to canonical English inside a
            // Chinese client — require one per template and require it to be localized.
            Map<String, Object> chinese = service.document(id, "zh");
            Map<String, Object> canonical = service.document(id, "en");
            assertTrue(!chinese.equals(canonical),
                    "template " + id + ": zh variant must exist and be localized, not a fallback");
            // Descriptions are deliberately localized; everything that executes or lays out
            // (ids, tools, arguments, positions, edges) must be identical between variants.
            assertEquals(skeleton(canonical), skeleton(chinese),
                    "template " + id + ": locale variants must not drift structurally");
            assertEquals(canonical.get("requiredTools"), chinese.get("requiredTools"));
        }
    }

    /** Graph with per-node descriptions stripped — the localization-invariant skeleton. */
    private static List<Map<String, Object>> skeleton(Map<String, Object> document) {
        Map<String, Object> graph = (Map<String, Object>) document.get("graph");
        return ((List<Map<String, Object>>) graph.get("nodes")).stream()
                .map(node -> {
                    Map<String, Object> data = new java.util.LinkedHashMap<>(
                            (Map<String, Object>) node.get("data"));
                    data.remove("description");
                    data.remove("title");
                    Map<String, Object> out = new java.util.LinkedHashMap<>(node);
                    out.put("data", data);
                    return out;
                })
                .toList();
    }

    @Test
    void unknownTemplateIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.document("ghost", "en"));
    }
}
