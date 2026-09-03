package fan.summer.fengyu.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.http.MediaType;
import fan.summer.fengyu.store.StoreEndpointProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP implementation of {@link StoreAccountGateway}. Follows
 * {@link HttpStoreAuthGateway}'s plain-JDK client and explicit error style so
 * upstream failures carry the store's status and body on the exception message.
 */
@Component
public class HttpStoreAccountGateway implements StoreAccountGateway {

    private final StoreEndpointProvider endpoints;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    public HttpStoreAccountGateway(StoreEndpointProvider endpoints) {
        this.endpoints = endpoints;
    }

    @Override
    public StoreAuthGateway.StoreProfile updateDisplayName(String accessToken,
            String displayName) {
        JsonNode body = execute(request("PUT", "/api/v1/me", accessToken,
                json(java.util.Map.of("displayName", displayName))), "profile update");
        return HttpStoreAuthGateway.parseProfile(body);
    }

    @Override
    public PasswordResult changePassword(String accessToken, String currentPassword,
            String newPassword) {
        JsonNode body = execute(request("PUT", "/api/v1/me/password", accessToken,
                json(java.util.Map.of("currentPassword", currentPassword,
                        "newPassword", newPassword))), "password change");
        return new PasswordResult(body.path("succeeded").asBoolean(false),
                body.path("message").asText(null));
    }

    @Override
    public Library library(String accessToken) {
        JsonNode body = execute(request("GET", "/api/v1/me/library", accessToken, null),
                "library");
        List<StoreAccountGateway.Favorite> favorites = new ArrayList<>();
        for (JsonNode node : body.path("favorites")) {
            favorites.add(new Favorite(text(node, "listingCoordinate"), text(node, "name"),
                    text(node, "addedAt")));
        }
        List<StoreAccountGateway.Entitlement> entitlements = new ArrayList<>();
        for (JsonNode node : body.path("entitlements")) {
            entitlements.add(new Entitlement(text(node, "listingCoordinate"),
                    node.path("free").asBoolean(false), text(node, "acquiredAt")));
        }
        List<StoreAccountGateway.InstallEvent> history = new ArrayList<>();
        for (JsonNode node : body.path("installHistory")) {
            history.add(new InstallEvent(text(node, "coordinate"), text(node, "version"),
                    text(node, "action"), text(node, "outcome"), text(node, "occurredAt")));
        }
        return new Library(List.copyOf(favorites), List.copyOf(entitlements),
                List.copyOf(history));
    }

    @Override
    public List<Session> sessions(String accessToken) {
        JsonNode body = execute(request("GET", "/api/v1/me/sessions", accessToken, null),
                "sessions");
        List<StoreAccountGateway.Session> sessions = new ArrayList<>();
        for (JsonNode node : body) {
            sessions.add(new Session(text(node, "sessionId"), text(node, "clientId"),
                    text(node, "kind"), text(node, "createdAt")));
        }
        return List.copyOf(sessions);
    }

    @Override
    public void revokeSession(String accessToken, String sessionId) {
        execute(request("DELETE", "/api/v1/me/sessions/" + url(requireId(sessionId)),
                accessToken, null), "session revoke");
    }

    @Override
    public List<Device> devices(String accessToken) {
        JsonNode body = execute(request("GET", "/api/v1/me/devices", accessToken, null),
                "devices");
        List<StoreAccountGateway.Device> devices = new ArrayList<>();
        for (JsonNode node : body) {
            devices.add(new Device(text(node, "deviceId"), text(node, "name"),
                    text(node, "platform"), node.path("revoked").asBoolean(false)));
        }
        return List.copyOf(devices);
    }

    @Override
    public void revokeDevice(String accessToken, String deviceId) {
        execute(request("DELETE", "/api/v1/me/devices/" + url(requireId(deviceId)),
                accessToken, null), "device revoke");
    }

    @Override
    public List<Organization> organizations(String accessToken) {
        JsonNode body = execute(request("GET", "/api/v1/organizations", accessToken, null),
                "organizations");
        List<StoreAccountGateway.Organization> organizations = new ArrayList<>();
        for (JsonNode node : body) {
            organizations.add(new Organization(text(node, "organizationId"),
                    text(node, "slug"), text(node, "name")));
        }
        return List.copyOf(organizations);
    }

    // ---- helpers ----

    private HttpRequest request(String method, String path, String accessToken,
            String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoints.base() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json");
        HttpRequest.BodyPublisher publisher = jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);
        if (jsonBody != null) {
            builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        }
        return builder.method(method, publisher).build();
    }

    private String json(java.util.Map<String, String> fields) {
        try {
            return mapper.writeValueAsString(fields);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot encode store request body", e);
        }
    }

    private JsonNode execute(HttpRequest request, String what) {
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Store " + what + " failed: HTTP "
                        + response.statusCode() + " " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Store " + what + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Store " + what + " interrupted", e);
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        return id.trim();
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
