package fan.summer.fengyu.plugin.runtime;

/**
 * A plugin worker call was cancelled. The cooperative {@code $/cancelRequest} path returned a
 * {@code CANCELLED} semantic error ({@code error.data.code == "CANCELLED"}); the worker process is
 * still alive and healthy. Surfaced to the caller so the HTTP layer can return a clean
 * cancellation response instead of a 500.
 */
public class PluginCancelledException extends RuntimeException {
    public PluginCancelledException(String message) {
        super(message);
    }
}
