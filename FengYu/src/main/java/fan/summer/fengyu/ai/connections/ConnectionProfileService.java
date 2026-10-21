package fan.summer.fengyu.ai.connections;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.database.entity.ai.ConnectionProfileEntity;
import fan.summer.fengyu.database.repository.ai.ConnectionProfileRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD for {@link ConnectionProfile}s — the "fill it in once" credential store behind the
 * Connections settings section and the inspector's connection dropdowns.
 *
 * <p>Values are returned to the owning user: this is a loopback, launch-token-gated surface
 * whose editor must be able to re-display stored values for editing (the same posture as the
 * MCP server form). Everything a tool needs to opt in is a schema annotation
 * ({@code x-fengyu-connection-type} plus optional {@code x-fengyu-connection-field}); the
 * profile's {@code type} must match it, and the named field feeds the node argument.</p>
 */
@Service
public class ConnectionProfileService {

    private static final int MAX_NAME = 80;
    private static final int MAX_TYPE = 40;
    private static final int MAX_DESCRIPTION = 200;
    private static final int MAX_FIELDS = 16;
    private static final int MAX_FIELD_KEY = 40;
    private static final int MAX_FIELD_VALUE = 4096;
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();

    private final ConnectionProfileRepository profiles;
    private final SecurityContext securityContext;

    public ConnectionProfileService(ConnectionProfileRepository profiles,
                                    SecurityContext securityContext) {
        this.profiles = profiles;
        this.securityContext = securityContext;
    }

    public List<ConnectionProfile> list() {
        return profiles.findByUserIdOrderByNameAsc(currentUserId()).stream()
                .map(ConnectionProfileService::toProfile)
                .toList();
    }

    /** Profiles of one type — what the inspector dropdown asks for. */
    public List<ConnectionProfile> list(String type) {
        return profiles.findByUserIdAndTypeOrderByNameAsc(currentUserId(), normalizeType(type))
                .stream().map(ConnectionProfileService::toProfile).toList();
    }

    @Transactional
    public ConnectionProfile create(ConnectionProfileRequest request) {
        if (request == null) throw new IllegalArgumentException("Request body is required");
        String name = requireName(request.name());
        String type = requireType(request.type());
        Map<String, String> fields = sanitizeFields(request.fields());
        long userId = currentUserId();
        if (profiles.findByUserIdAndName(userId, name).isPresent()) {
            throw new IllegalArgumentException("A connection named \"" + name + "\" already exists");
        }
        ConnectionProfileEntity entity = new ConnectionProfileEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(userId);
        apply(entity, name, type, request.description(), fields);
        return toProfile(profiles.save(entity));
    }

    @Transactional
    public ConnectionProfile update(String id, ConnectionProfileRequest request) {
        if (request == null) throw new IllegalArgumentException("Request body is required");
        ConnectionProfileEntity entity = profiles.findByIdAndUserId(id, currentUserId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown connection: " + id));
        String name = requireName(request.name());
        String type = requireType(request.type());
        profiles.findByUserIdAndName(entity.getUserId(), name)
                .filter(existing -> !existing.getId().equals(entity.getId()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException(
                            "A connection named \"" + name + "\" already exists");
                });
        apply(entity, name, type, request.description(), sanitizeFields(request.fields()));
        return toProfile(profiles.save(entity));
    }

    @Transactional
    public boolean delete(String id) {
        return profiles.findByIdAndUserId(id, currentUserId())
                .map(entity -> {
                    profiles.delete(entity);
                    return true;
                })
                .orElse(false);
    }

    private void apply(ConnectionProfileEntity entity, String name, String type,
                       String description, Map<String, String> fields) {
        entity.setName(name);
        entity.setType(type);
        entity.setDescription(description == null ? "" : description.strip());
        if (entity.getDescription().length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("Connection description must not exceed "
                    + MAX_DESCRIPTION + " characters");
        }
        entity.setFieldsJson(write(fields));
        if (entity.getCreatedAt() == null) entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) throw new IllegalArgumentException("Connection name is required");
        if (name.length() > MAX_NAME) {
            throw new IllegalArgumentException("Connection name must not exceed "
                    + MAX_NAME + " characters");
        }
        return name;
    }

    private static String requireType(String raw) {
        String type = normalizeType(raw);
        if (type.isEmpty()) throw new IllegalArgumentException("Connection type is required");
        if (type.length() > MAX_TYPE) {
            throw new IllegalArgumentException("Connection type must not exceed "
                    + MAX_TYPE + " characters");
        }
        return type;
    }

    /** Lowercase letters, digits, dash — the annotation values tools declare. */
    private static String normalizeType(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase().replaceAll("[^a-z0-9-]", "");
    }

    private static Map<String, String> sanitizeFields(Map<String, String> raw) {
        if (raw == null) throw new IllegalArgumentException("At least one connection field is required");
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().strip();
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (key.isEmpty()) throw new IllegalArgumentException("Connection field names must not be blank");
            if (key.length() > MAX_FIELD_KEY) {
                throw new IllegalArgumentException("Connection field names must not exceed "
                        + MAX_FIELD_KEY + " characters");
            }
            if (value.length() > MAX_FIELD_VALUE) {
                throw new IllegalArgumentException("Connection field \"" + key + "\" must not exceed "
                        + MAX_FIELD_VALUE + " characters");
            }
            fields.put(key, value);
        }
        if (fields.isEmpty()) throw new IllegalArgumentException("At least one connection field is required");
        if (fields.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("A connection must not exceed "
                    + MAX_FIELDS + " fields");
        }
        return fields;
    }

    private static ConnectionProfile toProfile(ConnectionProfileEntity entity) {
        return new ConnectionProfile(entity.getId(), entity.getName(), entity.getType(),
                entity.getDescription(), read(entity.getFieldsJson()),
                entity.getCreatedAt().toString(), entity.getUpdatedAt().toString());
    }

    private static Map<String, String> read(String json) {
        try {
            Map<String, String> fields = JSON.readValue(
                    json == null || json.isBlank() ? "{}" : json,
                    new TypeReference<Map<String, String>>() {});
            return fields == null ? Map.of() : fields;
        } catch (Exception malformed) {
            return Map.of();
        }
    }

    private static String write(Map<String, String> fields) {
        try {
            return JSON.writeValueAsString(fields);
        } catch (Exception malformed) {
            throw new IllegalArgumentException("Connection fields cannot be serialized", malformed);
        }
    }

    private long currentUserId() {
        Long id = securityContext.currentUserId();
        if (id == null) throw new IllegalStateException("No authenticated user");
        return id;
    }

    /** Owner-facing profile record (values included — the editor re-displays them). */
    public record ConnectionProfile(String id, String name, String type, String description,
                                    Map<String, String> fields,
                                    String createdAt, String updatedAt) {}

    public record ConnectionProfileRequest(String name, String type, String description,
                                           Map<String, String> fields) {}
}
