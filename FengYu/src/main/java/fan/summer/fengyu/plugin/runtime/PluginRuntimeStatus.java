package fan.summer.fengyu.plugin.runtime;

import java.time.Instant;

/** Read-only operational snapshot for one installed plugin Worker. */
public record PluginRuntimeStatus(
        String pluginId,
        State state,
        FaultType fault,
        String message,
        String runtime,
        Long pid,
        Instant startedAt,
        int restartCount,
        Instant backoffUntil,
        String sandbox) {

    public enum State {
        STOPPED, STARTING, HEALTHY, DEGRADED, BACKOFF, FAILED, UPDATING, DISABLED
    }

    public enum FaultType {
        NONE, CONFIGURATION, COMPATIBILITY, INTEGRITY, SIGNATURE, SPAWN, HANDSHAKE,
        PROTOCOL, TIMEOUT, CRASH, SANDBOX, RESOURCE_LIMIT, PERMISSION, UNKNOWN
    }

    public static PluginRuntimeStatus stopped(String pluginId, String runtime) {
        return new PluginRuntimeStatus(pluginId, State.STOPPED, FaultType.NONE, null,
                runtime, null, null, 0, null, null);
    }
}
