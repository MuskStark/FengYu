package fan.summer.ai.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

public final class JsonHelper {

    private static final Gson GSON = new Gson();

    private JsonHelper() {}

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static <T> T fromJson(String json, Type type) {
        return GSON.fromJson(json, type);
    }

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final Type LIST_TYPE = new TypeToken<List<Object>>() {}.getType();

    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) return Map.of();
        return GSON.fromJson(json, MAP_TYPE);
    }

    public static List<Object> parseList(String json) {
        if (json == null || json.isBlank()) return List.of();
        return GSON.fromJson(json, LIST_TYPE);
    }

    public static Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        return GSON.fromJson(json, Object.class);
    }

    @SuppressWarnings("unchecked")
    public static Object navigate(Map<String, Object> root, String path) {
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

    public static String getString(Map<String, Object> map, String path) {
        Object val = navigate(map, path);
        return val instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(Map<String, Object> map, String path) {
        Object val = navigate(map, path);
        return val instanceof Map ? (Map<String, Object>) val : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getList(Map<String, Object> map, String path) {
        Object val = navigate(map, path);
        return val instanceof List ? (List<Object>) val : null;
    }
}
