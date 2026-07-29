package fan.summer.fengyu.sdk;

import java.util.List;

/** Environment variables supplied by the FengYu host to plugin workers. */
public final class PluginEnvironment {
    public static final String DB_TYPE = "FENGYU_DB_TYPE";
    public static final String DB_DRIVER = "FENGYU_DB_DRIVER";
    public static final String DB_URL = "FENGYU_DB_URL";
    public static final String DB_USERNAME = "FENGYU_DB_USERNAME";
    public static final String DB_PASSWORD = "FENGYU_DB_PASSWORD";
    public static final String PLUGIN_DATA_DIR = "FENGYU_PLUGIN_DATA_DIR";
    /** Host-wide log threshold inherited by every Java Worker. */
    public static final String LOG_LEVEL = "FENGYU_LOG_LEVEL";

    private static final List<String> DATABASE_KEYS = List.of(
        DB_TYPE, DB_DRIVER, DB_URL, DB_USERNAME, DB_PASSWORD, PLUGIN_DATA_DIR);

    private PluginEnvironment() {}

    public static List<String> databaseKeys() {
        return DATABASE_KEYS;
    }
}
