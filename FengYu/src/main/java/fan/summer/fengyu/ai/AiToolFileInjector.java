package fan.summer.fengyu.ai;

import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure, Spring-free mapper that decides whether to transparently inject an active FileRef into a
 * plugin tool's arguments before dispatch. Two outcomes:
 * <ul>
 *   <li><b>B (transparent):</b> the tool has exactly one file-class parameter AND a matching
 *       grant exists → that param's value is replaced with the whole FileRef object so the host's
 *       {@code PluginProcessManager.resolveRefs} rewrites it to a real path for the worker.</li>
 *   <li><b>A (degrade):</b> multiple file params or no matching grant → params
 *       are returned unchanged and the model is expected to fill them from the FileRef list the
 *       backend appended to the system prompt.</li>
 * </ul>
 *
 * <p>This class has no ThreadLocal or Spring coupling on purpose — it is fully unit-testable.
 * {@code ToolCallback.call()} parses the model args, calls {@link #injectFileRefs}, then dispatches.
 */
public final class AiToolFileInjector {

    private AiToolFileInjector() {}

    /** How a single tool parameter is classified for injection purposes. */
    public enum FileParamClass { NONE, READ_FILE, READ_DIR, WRITE_DIR, FILE_LIST }

    /**
     * Classify one JSON-Schema property. Description wording is primary; the parameter name is a
     * fallback for manifests (e.g. the email plugin) whose file params carry no description.
     */
    public static FileParamClass classifyParam(String name, Map<String, Object> schema) {
        if (schema == null) return FileParamClass.NONE;
        String desc = schema.get("description") instanceof String d ? d.toLowerCase(Locale.ROOT) : "";
        String type = schema.get("type") instanceof String t ? t.toLowerCase(Locale.ROOT) : "";
        String lname = name == null ? "" : name.toLowerCase(Locale.ROOT);

        // array of objects whose items look like a FileRef → FILE_LIST
        if ("array".equals(type) && schema.get("items") instanceof Map<?, ?> items) {
            @SuppressWarnings("unchecked") Map<String, Object> itemsSchema = (Map<String, Object>) items;
            Object itemsDesc = itemsSchema.get("description");
            if (itemsDesc instanceof String id && id.toLowerCase(Locale.ROOT).contains("fileref")) {
                return FileParamClass.FILE_LIST;
            }
            return FileParamClass.NONE; // an array, but not file-shaped
        }

        if (desc.contains("directoryref")) {
            return (desc.contains("writable") || desc.contains("output")) ? FileParamClass.WRITE_DIR : FileParamClass.READ_DIR;
        }
        if (desc.contains("fileref")) {
            return FileParamClass.READ_FILE;
        }

        // Fallback by name for object-shaped refs whose prose does not use the FileRef terms.
        // This keeps older installed manifests (for example "Workbook selected from FengYu
        // files") working after a host upgrade without misclassifying ordinary string paths.
        if ("object".equals(type)) {
            if (lname.contains("output")) return FileParamClass.WRITE_DIR;
            if (lname.contains("input") || lname.contains("project")) return FileParamClass.READ_DIR;
            if (lname.contains("filepath") || lname.contains("filename")) return FileParamClass.READ_FILE;
        }
        return FileParamClass.NONE;
    }

    /**
     * Map the model's raw arguments to the arguments actually dispatched to the worker. Never
     * mutates the input map. Returns the original map reference when no injection applies.
     */
    public static Map<String, Object> injectFileRefs(Map<String, Object> modelParams,
            String pluginId, String inputSchema, List<ActiveFileRef> activeRefs) {
        if (modelParams == null) modelParams = Map.of();
        if (activeRefs == null) activeRefs = List.of();

        List<String> fileParamNames = fileParamNames(inputSchema);
        if (fileParamNames.isEmpty()) return modelParams;

        // Degrade A: multiple file params → let the model fill them from the system prompt.
        if (fileParamNames.size() != 1) return modelParams;

        String paramName = fileParamNames.get(0);
        FileParamClass cls = classifyParam(paramName, paramSchema(inputSchema, paramName));

        if (cls == FileParamClass.NONE) {
            return modelParams;
        }
        boolean wantDirectory = cls == FileParamClass.READ_DIR || cls == FileParamClass.WRITE_DIR;

        ActiveFileRef match = null;
        for (ActiveFileRef ref : activeRefs) {
            if (!pluginId.equals(ref.pluginId())) continue;
            FileRef f = ref.ref();
            boolean kindOk = wantDirectory ? "directory".equals(f.kind()) : "file".equals(f.kind());
            boolean accessOk = cls == FileParamClass.WRITE_DIR
                    ? "write".equals(f.access()) || "read-write".equals(f.access())
                    : "read".equals(f.access()) || "read-write".equals(f.access());
            if (kindOk && accessOk) { match = ref; break; }
        }
        if (match == null) return modelParams; // degrade A

        Map<String, Object> out = new LinkedHashMap<>(modelParams);
        out.put(paramName, toMap(match.ref()));
        return out;
    }

    /** Exact-match placeholder a workflow run uses for a file-class input: {@code @file:<name>}. */
    public static final String FILE_PLACEHOLDER_PREFIX = "@file:";

    /**
     * Replaces every {@code @file:<inputName>} placeholder in the dispatched args with the
     * current plugin's {@code FileRef} for that run-granted input, so
     * {@code PluginProcessManager.resolveRefs} can rewrite it into an absolute path the worker's
     * sandbox already covers. A placeholder without a matching grant fails the call loudly at
     * the host — a worker receiving the literal placeholder could only report a confusing
     * "file not found" from deep inside its own IO.
     *
     * @throws IllegalArgumentException when a placeholder names an input the run never granted,
     *         or one this plugin received no grant for
     */
    public static Map<String, Object> bindRunFilePlaceholders(Map<String, Object> params,
            String pluginId, Map<String, List<ActiveFileRef>> runRefs) {
        if (params == null || params.isEmpty()) return params == null ? Map.of() : params;
        if (!containsPlaceholder(params)) return params;
        Map<String, Object> bound = new LinkedHashMap<>(params);
        bindPlaceholders(bound, pluginId, runRefs);
        return bound;
    }

    private static void bindPlaceholders(Map<String, Object> params, String pluginId,
            Map<String, List<ActiveFileRef>> runRefs) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            entry.setValue(bindValuePlaceholder(entry.getValue(), pluginId, runRefs));
        }
    }

    private static Object bindValuePlaceholder(Object value, String pluginId,
            Map<String, List<ActiveFileRef>> runRefs) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> bound = new LinkedHashMap<>();
            map.forEach((key, child) -> bound.put(String.valueOf(key),
                    bindValuePlaceholder(child, pluginId, runRefs)));
            return bound;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(child -> bindValuePlaceholder(child, pluginId, runRefs)).toList();
        }
        if (!(value instanceof String text) || !text.startsWith(FILE_PLACEHOLDER_PREFIX)) return value;
        String name = text.substring(FILE_PLACEHOLDER_PREFIX.length());
        if (name.isEmpty() || !name.matches("[A-Za-z0-9_-]+")) return value;
        if (runRefs == null || !runRefs.containsKey(name)) {
            throw new IllegalArgumentException("Workflow file input '" + name
                    + "' has no granted file for this run");
        }
        for (ActiveFileRef ref : runRefs.get(name)) {
            if (pluginId.equals(ref.pluginId())) return toMap(ref.ref());
        }
        throw new IllegalArgumentException("Workflow file input '" + name
                + "' was not granted to plugin " + pluginId);
    }

    private static boolean containsPlaceholder(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(AiToolFileInjector::containsPlaceholder);
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(AiToolFileInjector::containsPlaceholder);
        }
        return value instanceof String text && text.startsWith(FILE_PLACEHOLDER_PREFIX)
                && text.substring(FILE_PLACEHOLDER_PREFIX.length()).matches("[A-Za-z0-9_-]+");
    }

    /**
     * Returns the name of the schema's single write-directory parameter when its current value
     * is blank — the signal for the host to substitute the plugin's default output staging dir
     * (canvas workflows and direct tool calls pass a typed path or nothing). {@code null} when
     * the schema has no such parameter or a value/ref is already present.
     */
    public static String blankWriteDirParam(Map<String, Object> params, String inputSchema) {
        if (params == null) params = Map.of();
        List<String> fileParamNames = fileParamNames(inputSchema);
        if (fileParamNames.size() != 1) return null;
        String name = fileParamNames.get(0);
        if (classifyParam(name, paramSchema(inputSchema, name)) != FileParamClass.WRITE_DIR) {
            return null;
        }
        Object value = params.get(name);
        if (value instanceof Map<?, ?> || value instanceof List<?>) return null;
        if (value instanceof String text && !text.isBlank()) return null;
        return name;
    }

    /**
     * Copies {@code params} with the default output {@code path} injected into the blank
     * write-dir parameter. A plain path — not a FileRef — on purpose: registering a grant here
     * would bump the grant version and restart stateful plugin workers mid-flow, while the
     * plugin-data root (where the default folder lives) is already sandbox-writable.
     */
    public static Map<String, Object> fillDefaultOutputDir(Map<String, Object> params,
            String inputSchema, String path) {
        String name = blankWriteDirParam(params, inputSchema);
        if (name == null || path == null || path.isBlank()) return params;
        Map<String, Object> out = new LinkedHashMap<>(params);
        out.put(name, path);
        return out;
    }

    /** Collect the names of file-class properties declared in the tool's inputSchema. */
    @SuppressWarnings("unchecked")
    private static List<String> fileParamNames(String inputSchema) {
        Map<String, Object> root = parseSchema(inputSchema);
        if (root == null) return List.of();
        Object propsObj = root.get("properties");
        if (!(propsObj instanceof Map<?, ?> raw)) return List.of();
        List<String> names = new ArrayList<>();
        for (Object e : raw.entrySet()) {
            Map.Entry<String, Object> entry = (Map.Entry<String, Object>) e;
            String name = entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> schema
                    && classifyParam(name, (Map<String, Object>) schema) != FileParamClass.NONE) {
                names.add(name);
            }
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paramSchema(String inputSchema, String paramName) {
        Map<String, Object> root = parseSchema(inputSchema);
        if (root == null) return Map.of();
        Object propsObj = root.get("properties");
        if (!(propsObj instanceof Map<?, ?> raw)) return Map.of();
        Object s = raw.get(paramName);
        return s instanceof Map<?, ?> schema ? (Map<String, Object>) schema : Map.of();
    }

    private static Map<String, Object> parseSchema(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) return null;
        try {
            return com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .build().readValue(inputSchema, java.util.Map.class);
        } catch (Exception e) {
            return null; // malformed schema → behave as "no file params" (degrade A)
        }
    }

    private static Map<String, Object> toMap(FileRef ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ref.id());
        m.put("name", ref.name());
        m.put("kind", ref.kind());
        m.put("access", ref.access());
        m.put("size", ref.size());
        return m;
    }
}
