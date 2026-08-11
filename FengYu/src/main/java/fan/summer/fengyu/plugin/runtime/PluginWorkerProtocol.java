package fan.summer.fengyu.plugin.runtime;

/**
 * Host-side constants for the out-of-process Worker protocol.
 *
 * <p>These values deliberately live in the host instead of importing the Worker SDK. The SDK
 * contains its own SLF4J provider for isolated worker processes; placing that implementation
 * artifact on Spring Boot's classpath would compete with the host's Logback provider.
 */
final class PluginWorkerProtocol {
    static final String DB_TYPE_ENV = "FENGYU_DB_TYPE";
    static final String DB_DRIVER_ENV = "FENGYU_DB_DRIVER";
    static final String DB_URL_ENV = "FENGYU_DB_URL";
    static final String DB_USERNAME_ENV = "FENGYU_DB_USERNAME";
    static final String DB_PASSWORD_ENV = "FENGYU_DB_PASSWORD";
    static final String LOG_LEVEL_ENV = "FENGYU_LOG_LEVEL";
    static final String PLUGIN_DATA_DIR_ENV = "FENGYU_PLUGIN_DATA_DIR";
    static final String SET_LOG_LEVEL_METHOD = "$/fengyu/logging/setLevel";
    /** JSON-RPC notification the host sends to cooperatively cancel an in-flight worker call. */
    static final String CANCEL_REQUEST_METHOD = "$/cancelRequest";
    static final String LOG_FRAME_PREFIX = "@fengyu-log:";

    private PluginWorkerProtocol() {}
}
