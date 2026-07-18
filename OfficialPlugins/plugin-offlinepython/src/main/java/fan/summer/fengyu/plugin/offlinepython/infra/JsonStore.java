package fan.summer.fengyu.plugin.offlinepython.infra;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Gson-backed load/save of JSON model objects. */
public final class JsonStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson GSON_PLAIN = new GsonBuilder().create();

    private JsonStore() {}

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON_PLAIN.fromJson(json, type);
    }

    /** Serialize an object to JSON (plain, UTF-8 string). */
    public static String toJson(Object obj) {
        return GSON_PLAIN.toJson(obj);
    }

    /** Serialize an object to a JSON tree (object/array/primitive). */
    public static JsonObject toJsonTree(Object obj) {
        return GSON_PLAIN.toJsonTree(obj).getAsJsonObject();
    }

    public static <T> T load(Path file, Class<T> type) throws IOException {
        return GSON.fromJson(Files.readString(file), type);
    }

    public static <T> void save(T obj, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(obj));
    }

    /**
     * Deep-merge {@code incoming} into {@code target}: for each key in incoming,
     * if both target and incoming hold a JSON object, recurse; otherwise the
     * incoming value overwrites target. Mutates and returns {@code target}.
     *
     * Used by config-save to avoid resetting sections the caller omitted: the
     * worker deserializes the merged object, so missing sections keep their
     * on-disk values instead of falling back to Java field defaults.
     */
    public static JsonObject mergeInto(JsonObject target, JsonObject incoming) {
        if (incoming == null) return target;
        for (String key : incoming.keySet()) {
            JsonElement inc = incoming.get(key);
            JsonElement cur = target.get(key);
            if (inc != null && inc.isJsonObject() && cur != null && cur.isJsonObject()) {
                mergeInto(cur.getAsJsonObject(), inc.getAsJsonObject());
            } else if (inc != null && !inc.isJsonNull()) {
                target.add(key, inc);
            }
        }
        return target;
    }
}
