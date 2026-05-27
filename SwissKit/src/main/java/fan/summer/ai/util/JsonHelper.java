package fan.summer.ai.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * Utility class for JSON serialization and deserialization using Gson.
 * Provides static methods for converting objects to JSON strings, parsing JSON
 * into typed or generic structures, and navigating nested JSON data with
 * dot-notation path expressions.
 *
 * <p>All parsing methods gracefully handle null or blank input by returning
 * empty/safe defaults rather than throwing exceptions.
 *
 * @see Gson
 */
public final class JsonHelper {

    private static final Gson GSON = new Gson();

    private JsonHelper() {}

    /**
     * Serializes an object to a JSON string.
     *
     * @param obj the object to serialize; may be null
     * @return a JSON string representation of the object, or {@code "null"} if obj is null
     */
    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    /**
     * Deserializes a JSON string into an object of the specified type.
     *
     * @param json the JSON string to parse
     * @param type the class type to deserialize into
     * @param <T>  the type of the returned object
     * @return an instance of type T, or null if json is null or blank
     */
    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    /**
     * Deserializes a JSON string into an object using a generic Type reference.
     * Useful for collections with generics such as {@code List<Map<String, Object>>}.
     *
     * @param json the JSON string to parse
     * @param type a {@link TypeToken} representing the target type
     * @param <T>  the type of the returned object
     * @return an instance of the specified type, or null if json is null or blank
     * @see TypeToken
     */
    public static <T> T fromJson(String json, Type type) {
        return GSON.fromJson(json, type);
    }

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final Type LIST_TYPE = new TypeToken<List<Object>>() {}.getType();

    /**
     * Parses a JSON string into a {@code Map<String, Object>}.
     * Returns an empty map if the input is null or blank.
     *
     * @param json the JSON string to parse
     * @return a map of string keys to arbitrary values, or an empty map on null/blank input
     */
    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) return Map.of();
        return GSON.fromJson(json, MAP_TYPE);
    }

    /**
     * Parses a JSON string into a {@code List<Object>}.
     * Returns an empty list if the input is null or blank.
     *
     * @param json the JSON string to parse
     * @return a list of arbitrary values, or an empty list on null/blank input
     */
    public static List<Object> parseList(String json) {
        if (json == null || json.isBlank()) return List.of();
        return GSON.fromJson(json, LIST_TYPE);
    }

    /**
     * Parses a JSON string into the most appropriate type (Map, List, or primitive).
     * Returns null if the input is null or blank.
     *
     * @param json the JSON string to parse
     * @return the parsed object (Map, List, String, Number, Boolean), or null on null/blank input
     */
    public static Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        return GSON.fromJson(json, Object.class);
    }

    /**
     * Navigates a nested map structure using a dot-separated path, supporting
     * both map keys and zero-based list indices in the path.
     * <p>
     * Example path: {@code "results.0.name"} navigates to the "name" key of the
     * first element in the "results" list within the root map.
     *
     * @param root the root map to navigate
     * @param path dot-separated path (e.g., "a.b.0.c"); uses {@code split("\\.")}
     * @return the value at the end of the path, or null if any key/index is not found
     */
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

    /**
     * Navigates to a value at the given path and returns it as a String.
     * Returns null if the value is not a String.
     *
     * @param map  the root map to navigate
     * @param path dot-separated path to the desired value
     * @return the String value at the path, or null if not found or not a String
     * @see #navigate(Map, String)
     */
    public static String getString(Map<String, Object> map, String path) {
        Object val = navigate(map, path);
        return val instanceof String s ? s : null;
    }

    /**
     * Navigates to a value at the given path and returns it as a Map.
     * Returns null if the value is not a Map.
     *
     * @param map  the root map to navigate
     * @param path dot-separated path to the desired value
     * @return the Map value at the path, or null if not found or not a Map
     * @see #navigate(Map, String)
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(Map<String, Object> map, String path) {
        Object val = navigate(map, path);
        return val instanceof Map ? (Map<String, Object>) val : null;
    }

    /**
     * Navigates to a value at the given path and returns it as a List.
     * Returns null if the value is not a List.
     *
     * @param map  the root map to navigate
     * @param path dot-separated path to the desired value
     * @return the List value at the path, or null if not found or not a List
     * @see #navigate(Map, String)
     */
    @SuppressWarnings("unchecked")
    public static List<Object> getList(Map<String, Object> map, String path) {
        Object val = navigate(map, path);
        return val instanceof List ? (List<Object>) val : null;
    }
}
