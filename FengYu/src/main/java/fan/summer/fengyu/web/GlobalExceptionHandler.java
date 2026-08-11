package fan.summer.fengyu.web;

import fan.summer.fengyu.plugin.runtime.PluginCancelledException;
import fan.summer.fengyu.plugin.runtime.PluginPermissionDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * Maps common validation exceptions to clean HTTP responses instead of default 500s with stack
 * traces. Particularly important for the setup-wizard endpoints (token-bypassed, first-run UX),
 * where a malformed request body (e.g. unknown db type) should surface as a 400, not a server error.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("success", false, "error", e.getMessage() != null ? e.getMessage() : "invalid request"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handlePluginFailure(IllegalStateException e) {
        String message = e.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "error",
                        message != null && !message.isBlank() ? message : "Plugin runtime failed"));
    }

    /**
     * T2-04 bullet 7: a worker PERMISSION_DENIED error must NOT degrade to a generic 500. It is a
     * business-level denial (the worker is healthy), so it surfaces as 403 and the caller can
     * distinguish authorization failure from an internal error.
     */
    @ExceptionHandler(PluginPermissionDeniedException.class)
    public ResponseEntity<Map<String, Object>> handlePermissionDenied(PluginPermissionDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("success", false, "error",
                        e.getMessage() != null ? e.getMessage() : "Permission denied"));
    }

    /**
     * T2-04 bullet 6: a cooperative cancellation (the worker returned CANCELLED via
     * $/cancelRequest) surfaces as a clean 499 Client Closed Request rather than a 500, so the
     * caller can tell the call was cancelled rather than failed.
     */
    @ExceptionHandler(PluginCancelledException.class)
    public ResponseEntity<Map<String, Object>> handleCancelled(PluginCancelledException e) {
        return ResponseEntity.status(499)
                .body(Map.of("success", false, "cancelled", true, "error",
                        e.getMessage() != null ? e.getMessage() : "Plugin call cancelled"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("success", false, "error", "File exceeds maximum upload size"));
    }
}
