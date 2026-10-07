package fan.summer.fengyu.plugin.runtime;

import java.util.Set;

/**
 * Host-side constants for the out-of-process Worker protocol.
 *
 * <p>These values deliberately live in the host instead of importing the Worker SDK. The SDK
 * contains its own SLF4J provider for isolated worker processes; placing that implementation
 * artifact on Spring Boot's classpath would compete with the host's Logback provider.
 */
public final class PluginWorkerProtocol {
    public static final int PUBLIC_PROTOCOL_VERSION = 4;
    /**
     * Worker handshake versions the host accepts besides {@link #PUBLIC_PROTOCOL_VERSION}.
     * Handshake 1 (app 4.0.x-era plugins) is wire-identical to 4 — the 2.1.x toolchain bump
     * unified version lines without changing the wire — so already-installed legacy workers
     * keep running. New packages are still authored against {@link #PUBLIC_PROTOCOL_VERSION};
     * the initialize request carries the version each plugin's manifest declares and the
     * worker must echo it back (real SDKs echo their own constant, which pins manifest and
     * jar together).
     */
    public static final Set<Integer> SUPPORTED_PROTOCOL_VERSIONS = Set.of(1, PUBLIC_PROTOCOL_VERSION);
    static final String INITIALIZE_METHOD = "$/fengyu/initialize";
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
