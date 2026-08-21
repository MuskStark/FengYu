package fan.summer.fengyu.web;

import fan.summer.fengyu.ai.tasks.BackgroundTaskCapacityException;
import fan.summer.fengyu.ai.workflow.WorkflowRevisionConflictException;
import fan.summer.fengyu.ai.workflow.WorkflowWebhookAuthenticationException;
import fan.summer.fengyu.ai.workflow.WorkflowWebhookUnavailableException;
import fan.summer.fengyu.plugin.runtime.PluginCancelledException;
import fan.summer.fengyu.plugin.runtime.PluginPermissionDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps common validation exceptions to clean HTTP responses instead of default 500s with stack
 * traces. Particularly important for the setup-wizard endpoints (token-bypassed, first-run UX),
 * where a malformed request body (e.g. unknown db type) should surface as a 400, not a server error.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("success", false, "error", e.getMessage() != null ? e.getMessage() : "invalid request"));
    }

    @ExceptionHandler(WorkflowRevisionConflictException.class)
    public ResponseEntity<Map<String, Object>> handleWorkflowRevisionConflict(
            WorkflowRevisionConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "success", false,
                        "error", e.getMessage(),
                        "workflowId", e.workflowId(),
                        "expectedRevision", e.expectedRevision(),
                        "actualRevision", e.actualRevision()));
    }

    @ExceptionHandler(WorkflowWebhookAuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleWebhookAuthentication(
            WorkflowWebhookAuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("success", false, "error", e.getMessage()));
    }

    @ExceptionHandler(WorkflowWebhookUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleWebhookUnavailable(
            WorkflowWebhookUnavailableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("success", false, "error", e.getMessage()));
    }

    /** Bounded-queue exhaustion is transient load shedding, not an internal server failure. */
    @ExceptionHandler(BackgroundTaskCapacityException.class)
    public ResponseEntity<Map<String, Object>> handleBackgroundTaskCapacity(
            BackgroundTaskCapacityException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", e.getMessage());
        body.put("retryable", true);
        body.put("capacityScope", e.capacityScope());
        if (e.capacityPriority() != null) {
            body.put("capacityPriority", e.capacityPriority());
        }
        body.put("retryAfterSeconds", e.retryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handlePluginFailure(IllegalStateException e) {
        String message = e.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "error",
                        message != null && !message.isBlank() ? message : "Plugin runtime failed"));
    }

    /**
     * Install/uninstall paths (upload, upload-native, uninstall, ...) declare {@code throws
     * IOException}: staging/extract/atomic-move failures land here. Without this handler they
     * fell through to Spring's default 500 whose body is the message-less "Internal Server
     * Error" — the UI could only show an opaque internal error and nothing reached the host
     * log, leaving the actual cause (disk full, file lock, ...) undiscoverable.
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> handleIoFailure(IOException e) {
        log.error("Request failed with IOException: {}", e.getMessage(), e);
        String message = e.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "error",
                        message != null && !message.isBlank()
                                ? "I/O failure: " + message
                                : "I/O failure while processing the request"));
    }

    /**
     * Last-resort mapping so an unmapped runtime failure (an installer wrapper exception, an
     * NPE, ...) still answers a body carrying its reason — the frontend error banner shows the
     * backend's {@code error} field — and leaves an ERROR log line for diagnosis, instead of the
     * bare whitelabel "Internal Server Error" with nothing in the log. Deliberately scoped to
     * {@code RuntimeException}: Spring MVC's own status-mapped exceptions (405/404/415 ...) live
     * on the ServletException branch and must keep their precise status codes. Runtime-status
     * exceptions ({@code ResponseStatusException} 409/429 thrown by controllers,
     * {@code AsyncRequestTimeoutException} 503 on SSE) reach this handler before Spring's own
     * resolvers, so their declared status is preserved instead of degrading to a 500.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(RuntimeException e) {
        if (e instanceof org.springframework.web.ErrorResponse framework) {
            org.springframework.http.ProblemDetail detail = framework.getBody();
            String message = detail == null || detail.getDetail() == null
                    || detail.getDetail().isBlank()
                    ? "Request failed with HTTP " + framework.getStatusCode().value()
                    : detail.getDetail();
            return ResponseEntity.status(framework.getStatusCode())
                    .body(Map.of("success", false, "error", message));
        }
        log.error("Unhandled exception in request handling", e);
        String message = e.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "error",
                        message != null && !message.isBlank()
                                ? e.getClass().getSimpleName() + ": " + message
                                : "Internal error: " + e.getClass().getSimpleName()));
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
