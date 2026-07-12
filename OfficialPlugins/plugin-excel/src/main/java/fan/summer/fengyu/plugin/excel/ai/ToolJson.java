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
