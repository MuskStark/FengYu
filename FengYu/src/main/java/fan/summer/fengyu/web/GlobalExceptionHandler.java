package fan.summer.fengyu.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
}
