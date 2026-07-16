package fan.summer.fengyu.plugin.offlinepython.infra;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.Map;

/**
 * Serialization helpers for converting Gson-friendly model objects (records / Lombok @Data beans)
 * into plain {@code Map<String,Object>} trees safe to return from a {@code PluginHandler}.
 *
 * <p>Gson mirrors a record/bean into a {@code LinkedHashMap} (field order preserved), so the host's
 * Jackson layer and the JSON-RPC response stay schema-stable regardless of the source type. This
 * avoids needing a custom Gson {@code TypeAdapter} for every record in the {@code domain/} layer.
 */
public final class JsonRpcParams {

    private static final Gson GSON = new Gson();
    private static final TypeToken<Map<String, Object>> MAP = new TypeToken<>() {};

    private JsonRpcParams() {}

    /** Deep-convert any Gson-serialisable object into a {@code Map<String,Object>}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object obj) {
        if (obj == null) return Map.of();
        if (obj instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return GSON.fromJson(GSON.toJson(obj), MAP.getType());
    }

    public static String string(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v == null ? null : v.toString();
    }

    public static int integer(Map<String, Object> params, String key, int fallback) {
        Object v = params.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }
}
