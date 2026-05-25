package fan.summer.ai.service;

import java.util.List;
import java.util.Map;

/**
 * Minimal JSON builder to avoid external dependencies.
 * Produces JSON strings from maps/lists/scalars.
 */
final class JsonBuilder {

    private JsonBuilder() {}

    static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return escape(s);
        if (value instanceof Number n) {
            if (n instanceof Double d && (Double.isNaN(d) || Double.isInfinite(d))) return "null";
            if (n instanceof Float f && (Float.isNaN(f) || Float.isInfinite(f))) return "null";
            return n.toString();
        }
        if (value instanceof Boolean b) return b.toString();
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                sb.append(escape(e.getKey().toString())).append(':').append(toJson(e.getValue()));
                first = false;
            }
            sb.append('}');
            return sb.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                sb.append(toJson(item));
                first = false;
            }
            sb.append(']');
            return sb.toString();
        }
        return escape(value.toString());
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
