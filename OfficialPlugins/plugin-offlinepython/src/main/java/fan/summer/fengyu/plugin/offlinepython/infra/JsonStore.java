package fan.summer.fengyu.plugin.offlinepython.infra;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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

    public static <T> T load(Path file, Class<T> type) throws IOException {
        return GSON.fromJson(Files.readString(file), type);
    }

    public static <T> void save(T obj, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(obj));
    }
}
