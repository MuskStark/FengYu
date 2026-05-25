package fan.summer.ai.service;

import java.util.*;

/**
 * Minimal JSON parser for SSE response chunks. Only supports the subset
 * needed for OpenAI/Anthropic streaming responses: objects, arrays,
 * strings, numbers, booleans, null.
 */
final class JsonParser {

    private JsonParser() {}

    static Object parse(String json) {
        return new Parser(json.trim()).parseValue();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String json) {
        Object result = parse(json);
        return result instanceof Map ? (Map<String, Object>) result : Map.of();
    }

    private static class Parser {
        private final String json;
        private int pos;

        Parser(String json) { this.json = json; }

        Object parseValue() {
            skipWhitespace();
            if (pos >= json.length()) return null;
            char c = json.charAt(pos);
            return switch (c) {
                case '{' -> parseMap();
                case '[' -> parseList();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        Map<String, Object> parseMap() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (pos >= json.length() || json.charAt(pos) != ',') break;
                pos++; // skip comma
            }
            expect('}');
            return map;
        }

        List<Object> parseList() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (pos >= json.length() || json.charAt(pos) != ',') break;
                pos++;
            }
            expect(']');
            return list;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < json.length()) {
                char c = json.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= json.length()) break;
                    char esc = json.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 <= json.length()) {
                                String hex = json.substring(pos, pos + 4);
                                pos += 4;
                                sb.append((char) Integer.parseInt(hex, 16));
                            }
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Number parseNumber() {
            int start = pos;
            boolean isFloat = false;
            while (pos < json.length()) {
                char c = json.charAt(pos);
                if (c == '.' || c == 'e' || c == 'E') isFloat = true;
                if (Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') pos++;
                else break;
            }
            String num = json.substring(start, pos);
            if (isFloat) return Double.parseDouble(num);
            long l = Long.parseLong(num);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
            return l;
        }

        Boolean parseBoolean() {
            if (json.startsWith("true", pos)) { pos += 4; return true; }
            if (json.startsWith("false", pos)) { pos += 5; return false; }
            throw new IllegalStateException("Expected boolean at " + pos);
        }

        Object parseNull() {
            if (json.startsWith("null", pos)) { pos += 4; return null; }
            throw new IllegalStateException("Expected null at " + pos);
        }

        void expect(char c) {
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == c) { pos++; return; }
            throw new IllegalStateException("Expected '" + c + "' at " + pos);
        }

        void skipWhitespace() {
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) pos++;
        }
    }

    /** Navigate a nested map by dot-separated path, e.g. "choices.0.delta.content". */
    @SuppressWarnings("unchecked")
    static Object navigate(Map<String, Object> root, String path) {
        Object current = root;
        for (String key : path.split("\\.")) {
            if (current == null) return null;
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
            } else if (current instanceof List) {
                try {
                    int idx = Integer.parseInt(key);
                    List<Object> list = (List<Object>) current;
                    current = (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    static String getString(Map<String, Object> map, String path) {
        Object val = navigate(map, path);
        return val instanceof String s ? s : null;
    }

    static String getString(Map<String, Object> map, String path, String defaultVal) {
        String val = getString(map, path);
        return val != null ? val : defaultVal;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> getMap(Map<String, Object> map, String path) {
        Object val = navigate(map, path);
        return val instanceof Map ? (Map<String, Object>) val : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> getList(Map<String, Object> map, String path) {
        Object val = navigate(map, path);
        return val instanceof List ? (List<Object>) val : null;
    }
}
