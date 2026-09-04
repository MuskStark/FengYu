package fan.summer.fengyu.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** HTTP implementation of {@link StoreAuthGateway} against the store authorization server. */
@Component
public class HttpStoreAuthGateway implements StoreAuthGateway {

    private final StoreEndpointProvider endpoints;
    private final String clientId;
    private final String clientSecret;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * Desktop client registration (RFC 8252 / RFC 7636). The default is the
     * public-client form — PKCE only, no client secret — because a secret
     * shipped inside a distributed desktop build is public knowledge
     * (RFC 8252 §8.5) and cannot make the client confidential. A deployment
     * whose store still registers {@code fengyu-desktop} as confidential opts
     * in explicitly via {@code fengyu.store.client-secret}
     * (FENGYU_STORE_CLIENT_SECRET); an empty value keeps the pure public form.
     * Long-term login for the public form is a store-side concern (per-install
     * credentials or BFF), not a shipped secret. All request URLs resolve
     * through {@link StoreEndpointProvider} so the Settings 升级渠道 routes
     * sign-in to the production store.
     */
    public HttpStoreAuthGateway(StoreEndpointProvider endpoints,
            @Value("${fengyu.store.client-id:fengyu-desktop}") String clientId,
            @Value("${fengyu.store.client-secret:}") String clientSecret) {
        this.endpoints = endpoints;
        this.clientId = clientId;
        this.clientSecret = normalize(clientSecret);
    }

    private static String normalize(String base) {
        String trimmed = base == null ? "" : base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Override
    public TokenGrant exchange(String code, String codeVerifier, String redirectUri) {
        return tokenRequest("authorization_code", "code", code, codeVerifier, redirectUri);
    }

    /**
     * Public-client form (no secret): the store's rotating per-install
     * credential — the credential itself is the only authenticator, so a store
     * upgrade can never break client refresh through a client-authentication
     * mismatch. Confidential form (a configured secret): the authorization
     * server's refresh-token grant, as before.
     */
    @Override
    public TokenGrant refresh(String refreshToken) {
        if (clientSecret == null || clientSecret.isEmpty()) {
            JsonNode body = execute(request("POST", "/api/v1/auth/refresh", null,
                    json(java.util.Map.of("refreshToken", refreshToken))), "refresh");
            return new TokenGrant(requiredText(body, "accessToken"),
                    body.path("expiresIn").asLong(0),
                    body.path("refreshToken").asText(null));
        }
        return tokenRequest("refresh_token", "refresh_token", refreshToken, null, null);
    }

    @Override
    public SessionCredential issueSessionCredential(String accessToken) {
        JsonNode body = execute(request("POST", "/api/v1/auth/desktop-session", accessToken,
                "{}"), "desktop session issue");
        String expiresAt = body.path("refreshExpiresAt").asText(null);
        long seconds = expiresAt == null ? 0
                : Math.max(0, Duration.between(Instant.now(), Instant.parse(expiresAt))
                        .toSeconds());
        return new SessionCredential(requiredText(body, "refreshToken"), seconds);
    }

    private TokenGrant tokenRequest(String grantType, String credentialName, String credential,
            String codeVerifier, String redirectUri) {
        StringBuilder form = new StringBuilder()
                .append("grant_type=").append(url(grantType))
                .append("&client_id=").append(url(clientId));
        if (clientSecret != null && !clientSecret.isEmpty()) {
            // client_secret_post — the confidential-client form the current
            // store registration requires; PKCE stays on top either way.
            form.append("&client_secret=").append(url(clientSecret));
        }
        form.append('&').append(credentialName).append('=').append(url(credential));
        if (codeVerifier != null) {
            form.append("&code_verifier=").append(url(codeVerifier));
        }
        if (redirectUri != null) {
            form.append("&redirect_uri=").append(url(redirectUri));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.base() + "/oauth2/token"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(form.toString(),
                        StandardCharsets.UTF_8))
                .build();
        JsonNode body = execute(request, "token",
                "the store rejected this desktop client — the default is the public PKCE "
                + "form (no secret); if this store registers fengyu-desktop as confidential, "
                + "set fengyu.store.client-secret (FENGYU_STORE_CLIENT_SECRET) to its "
                + "desktop-client secret");
        return new TokenGrant(requiredText(body, "access_token"),
                body.path("expires_in").asLong(0),
                body.path("refresh_token").asText(null));
    }

    /**
     * Best-effort revocation on sign-out. Public-client form: the store's
     * desktop-session revoke (the SAS endpoint rejects public clients);
     * confidential form: RFC 7009. Never blocks local sign-out.
     */
    @Override
    public void revoke(String token) {
        try {
            if (clientSecret == null || clientSecret.isEmpty()) {
                HttpRequest request = request("POST", "/api/v1/auth/revoke", null,
                        json(java.util.Map.of("refreshToken", token)));
                http.send(request, HttpResponse.BodyHandlers.ofString());
                return;
            }
            StringBuilder form = new StringBuilder()
                    .append("token=").append(url(token))
                    .append("&client_id=").append(url(clientId));
            form.append("&client_secret=").append(url(clientSecret));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.base() + "/oauth2/revoke"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString(),
                            StandardCharsets.UTF_8))
                    .build();
            http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            // Revocation is best-effort — a network failure must not block local sign-out.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public StoreProfile me(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.base() + "/api/v1/me"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        return parseProfile(execute(request, "me"));
    }


    /** Shared with {@link HttpStoreAccountGateway} — one source of truth for PublicUser. */
    static StoreProfile parseProfile(JsonNode body) {
        List<String> roles = new ArrayList<>();
        body.path("roles").forEach(r -> roles.add(r.asText()));
        return new StoreProfile(requiredText(body, "userId"),
                body.path("email").asText(null),
                body.path("displayName").asText(null),
                List.copyOf(roles),
                body.path("beeLevel").asInt(0),
                body.path("createdAt").asText(null));
    }

    private JsonNode execute(HttpRequest request, String what) {
        return execute(request, what, null);
    }

    private JsonNode execute(HttpRequest request, String what, String invalidClientHint) {
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // The body is an excerpt only: error and redirect responses can
                // carry entire HTML pages (the store's login-page bounce), which
                // otherwise swamp the message that reaches the UI error card.
                String message = "Store " + what + " failed: HTTP "
                        + response.statusCode() + " " + excerpt(response.body());
                // The classic misconfiguration is a client-secret mismatch with the
                // store's confidential desktop registration — say so inline.
                if (invalidClientHint != null && response.body().contains("invalid_client")) {
                    message = message + " — " + invalidClientHint;
                }
                throw new IllegalStateException(message);
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Store " + what + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Store " + what + " interrupted", e);
        }
    }

    /** Compact first 200 characters of a response body, for exception messages. */
    static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() > 200 ? compact.substring(0, 200) + "…" : compact;
    }

    /** JSON request builder shared by the desktop-session endpoints. */
    private HttpRequest request(String method, String path, String accessToken,
            String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(endpoints.base() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
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

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Store response missing field '" + field + "'");
        }
        return value;
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
