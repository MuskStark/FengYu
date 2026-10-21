package fan.summer.fengyu.ai.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in one-click flow templates, served from classpath {@code workflow-templates/*.fyf}.
 *
 * <p>Each template is a graph-only {@code .fyf} document (the same interchange format as flow
 * export) plus template metadata keys ({@code templateId}, {@code icon}, {@code requiredTools}).
 * Localization is full-file: {@code <id>.fyf} is canonical English, {@code <id>.zh.fyf} is the
 * complete Chinese twin (names, descriptions, schema titles, node descriptions) — a partial
 * delta overlay would let the two variants drift structurally, so parity is enforced by test
 * instead. The builder applies a template document exactly like an imported graph-only
 * export: canvas mounts the graph and compiles the plan on save.</p>
 */
@Service
public class WorkflowTemplateService {

    private final Map<String, Map<String, Object>> canonical = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> chinese = new LinkedHashMap<>();
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    public WorkflowTemplateService() {
        try {
            loadTemplates();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not load built-in workflow templates", failure);
        }
    }

    private void loadTemplates() throws Exception {
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:workflow-templates/*.fyf")) {
            String filename = resource.getFilename();
            if (filename == null) continue;
            Map<String, Object> document = json.readValue(resource.getInputStream(),
                    new TypeReference<Map<String, Object>>() {});
            String templateId = String.valueOf(document.get("templateId"));
            if (templateId.isBlank()) {
                throw new IllegalStateException("Template resource without a templateId: " + filename);
            }
            Map<String, Map<String, Object>> into = filename.endsWith(".zh.fyf")
                    ? chinese : canonical;
            if (into.put(templateId, document) != null) {
                throw new IllegalStateException("Duplicate workflow template id: " + templateId);
            }
        }
        for (String id : chinese.keySet()) {
            if (!canonical.containsKey(id)) {
                throw new IllegalStateException("Chinese template has no canonical twin: " + id);
            }
        }
    }

    /** Template summaries for the gallery, in canonical file order. */
    public List<Map<String, Object>> list(String locale) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : canonical.entrySet()) {
            Map<String, Object> document = pick(entry.getKey(), locale);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", entry.getKey());
            summary.put("icon", document.get("icon"));
            summary.put("name", document.get("name"));
            summary.put("description", document.get("description"));
            summary.put("goal", document.get("goal"));
            summary.put("requiredTools", document.get("requiredTools") instanceof List<?> tools
                    ? tools : List.of());
            result.add(summary);
        }
        return List.copyOf(result);
    }

    /** The full `.fyf` template document in the requested locale. */
    public Map<String, Object> document(String templateId, String locale) {
        Map<String, Object> document = pick(templateId, locale);
        if (document == null) throw new IllegalArgumentException("Unknown workflow template: "
                + templateId);
        return document;
    }

    private Map<String, Object> pick(String templateId, String locale) {
        if (templateId == null || templateId.isBlank()) return null;
        Map<String, Object> localized = locale != null && locale.toLowerCase().startsWith("zh")
                ? chinese.get(templateId) : null;
        return localized != null ? localized : canonical.get(templateId);
    }
}
