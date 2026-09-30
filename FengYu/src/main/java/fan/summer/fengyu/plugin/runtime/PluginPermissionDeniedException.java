package fan.summer.fengyu.plugin.runtime;

/**
 * A plugin worker reported a {@code PERMISSION_DENIED} semantic error
 * ({@code error.data.code == "PERMISSION_DENIED"}). This is a business-level denial, not a worker
 * crash: the worker process stays alive and the host surfaces it as HTTP 403 rather than a generic
 * 500, so the caller can distinguish authorization failure from an internal error.
 */
public class PluginPermissionDeniedException extends RuntimeException {
    public PluginPermissionDeniedException(String message) {
        super(message);
    }
}
