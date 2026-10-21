package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.connections.ConnectionProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Launch-token-authenticated CRUD over the connection profile store (`/api/connections`).
 * The optional `?type=` filter backs the inspector's per-annotation dropdown.
 */
@RestController
public class ConnectionProfileController {

    private final ConnectionProfileService connections;

    public ConnectionProfileController(ConnectionProfileService connections) {
        this.connections = connections;
    }

    @GetMapping("/api/connections")
    public List<ConnectionProfileService.ConnectionProfile> list(
            @RequestParam(required = false) String type) {
        return type == null || type.isBlank() ? connections.list() : connections.list(type);
    }

    @PostMapping("/api/connections")
    public ResponseEntity<ConnectionProfileService.ConnectionProfile> create(
            @RequestBody ConnectionProfileService.ConnectionProfileRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(connections.create(request));
    }

    @PutMapping("/api/connections/{id}")
    public ConnectionProfileService.ConnectionProfile update(
            @PathVariable String id,
            @RequestBody ConnectionProfileService.ConnectionProfileRequest request) {
        return connections.update(id, request);
    }

    @DeleteMapping("/api/connections/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("ok", connections.delete(id), "id", id);
    }
}
