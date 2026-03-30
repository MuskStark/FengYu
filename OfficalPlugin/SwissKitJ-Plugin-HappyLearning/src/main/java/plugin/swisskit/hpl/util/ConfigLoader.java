package plugin.swisskit.hpl.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads request header configuration from netschool-headers.json.
 *
 * @author phoebej
 * @version 1.00
 */
public abstract class ConfigLoader {

    private static JSONObject config;

    /**
     * Directory path where plugin configuration files are stored.
     * Defaults to {@code .swisskit/plugins/config} under the current working directory.
     */
    public static String CONFIG_DIR = Path.of(System.getProperty("user.dir"))
            .resolve(".swisskit")
            .resolve("plugins")
            .resolve("config")
            .toAbsolutePath()
            .toString()
            .replace("\\", "/");

    /**
     * Load configuration from netschool-headers.json in CONFIG_DIR.
     * Must be called before using other static methods.
     *
     * @return the loaded JSON configuration object
     * @throws IllegalStateException if the config file is not found
     */
    public static JSONObject loadConfig() {
        try {
            Path configFile = Path.of(CONFIG_DIR, "netschool-headers.json");
            if (!configFile.toFile().exists()) {
                throw new IllegalStateException("netschool-headers.json not found in " + CONFIG_DIR);
            }
            config = JSON.parseObject(Files.readString(configFile, StandardCharsets.UTF_8)
            );
            return config;
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // -------------------------  URL Constants  -------------------------

    public static String url(String key) {
        return config.getJSONObject("urls").getString("baseUrl")
                + config.getJSONObject("urls").getString(key);
    }

    public static String rawUrl(String key) {
        return config.getJSONObject("urls").getString(key);
    }

    // -------------------------  Headers Builder  -------------------------

    /**
     * Build headers for the specified profile and inject dynamic fields.
     *
     * @param profile  the key in profiles section of the JSON config
     * @param dynamics runtime dynamic headers to append, e.g. token, cookie
     */
    public static Map<String, String> headers(String profile, Map<String, String> dynamics) {
        Map<String, String> result = new LinkedHashMap<>();

        JSONObject profiles = config.getJSONObject("profiles");
        JSONObject common = config.getJSONObject("common");
        JSONObject target = profiles.getJSONObject(profile);

        if (target == null) throw new IllegalArgumentException("Unknown profile: " + profile);

        // 1. Apply common headers first
        String extendsFrom = target.getString("extends");
        if ("common".equals(extendsFrom)) {
            common.forEach((k, v) -> result.put(k, v.toString()));
        } else if (extendsFrom != null) {
            // Recursively resolve parent profile
            result.putAll(headers(extendsFrom, Collections.emptyMap()));
        }

        // 2. Override with current profile fields (skip extends meta field)
        target.forEach((k, v) -> {
            if (!"extends".equals(k)) result.put(k, v.toString());
        });

        // 3. Finally inject dynamic fields (token / cookie, etc.)
        if (dynamics != null) result.putAll(dynamics);

        return result;
    }

    /**
     * Overload without dynamic fields.
     */
    public static Map<String, String> headers(String profile) {
        return headers(profile, null);
    }
    // -------------------------  Lesson Type  -------------------------

    /**
     * Get the server-side lesson type string by UI key.
     * <p>
     * Example keys: {@code "MajorSubject"}, {@code "ElectiveSubject"}
     *
     * @param key the UI-facing lesson type key defined in lessonType section
     * @return the corresponding server-side string value
     * @throws IllegalArgumentException if the key does not exist
     */
    public static String lessonType(String key) {
        JSONObject lessonTypes = config.getJSONObject("lessonType");
        if (lessonTypes == null) throw new IllegalStateException("lessonType section not found in config");
        String value = lessonTypes.getString(key);
        if (value == null) throw new IllegalArgumentException("Unknown lessonType key: " + key);
        return value;
    }
}