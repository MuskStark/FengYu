package fan.summer.fengyu.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
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

/** HTTP implementation of {@link StoreAuthGateway} against the store authorization server. */
@Component
public class HttpStoreAuthGateway implements StoreAuthGateway {

    private final String apiBase;
    private final String clientId;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * Public OAuth 2.1 client (RFC 8252 / RFC 7636): the native app ships no
     * client secret — the PKCE code_verifier is the proof of the token request's
     * origin, so nothing static is here to leak.
     */
    public HttpStoreAuthGateway(
            @Value("${fengyu.store.api-base:http://localhost:8080}") String apiBase,
            @Value("${fengyu.store.client-id:fengyu-desktop}") String clientId) {
        this.apiBase = normalize(apiBase);
        this.clientId = clientId;
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

    @Override
    public TokenGrant refresh(String refreshToken) {
        return tokenRequest("refresh_token", "refresh_token", refreshToken, null, null);
    }

    private TokenGrant tokenRequest(String grantType, String credentialName, String credential,
            String codeVerifier, String redirectUri) {
        StringBuilder form = new StringBuilder()
                .append("grant_type=").append(url(grantType))
                .append("&client_id=").append(url(clientId))
                .append('&').append(credentialName).append('=').append(url(credential));
        if (codeVerifier != null) {
            form.append("&code_verifier=").append(url(codeVerifier));
        }
        if (redirectUri != null) {
            form.append("&redirect_uri=").append(url(redirectUri));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/oauth2/token"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(form.toString(),
                        StandardCharsets.UTF_8))
                .build();
        JsonNode body = execute(request, "token");
        return new TokenGrant(requiredText(body, "access_token"),
                body.path("expires_in").asLong(0),
                body.path("refresh_token").asText(null));
    }

    @Override
    public void revoke(String token) {
        try {
            String form = "token=" + url(token) + "&client_id=" + url(clientId);
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/oauth2/revoke"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/api/v1/me"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        JsonNode body = execute(request, "me");
        List<String> roles = new ArrayList<>();
        body.path("roles").forEach(r -> roles.add(r.asText()));
        return new StoreProfile(requiredText(body, "userId"),
                body.path("email").asText(null),
                body.path("displayName").asText(null),
                List.copyOf(roles));
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
