package fan.summer.fengyu.account;

import com.sun.net.httpserver.HttpServer;
import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.store.StoreBearerTokenSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Cloud account sign-in for the desktop host (design §7.2): OAuth 2.1 authorization
 * code + PKCE through the system browser, code received on a temporary loopback
 * callback server, tokens persisted in {@code cloud_account_binding}. The renderer
 * opens the returned authorization URL because this backend is intentionally headless.
 *
 * <p>Signing in never changes local data ownership — it only enables authenticated
 * outbound calls to the store (ADR-002).
 */
@Service
public class CloudAccountService implements StoreBearerTokenSupplier {

    private static final Logger log = LoggerFactory.getLogger(CloudAccountService.class);
    private static final long ATTEMPT_TIMEOUT_SECONDS = 300;
    private static final long ACCESS_TOKEN_SAFETY_MARGIN_SECONDS = 30;

    /** View returned to the SPA: local virtual user when not signed in. */
    public record AccountView(boolean authenticated, String userId, String username,
            String email, List<String> roles) {}

    public enum AttemptStatus { PENDING, COMPLETED, FAILED }

    public record AttemptView(AttemptStatus status, AccountView user, String error) {}

    private static final class Attempt {
        final String state;
        final String codeVerifier;
        final String authorizationUrl;
        final CompletableFuture<String> code = new CompletableFuture<>();
        volatile AttemptView outcome;

        Attempt(String state, String codeVerifier, String authorizationUrl) {
            this.state = state;
            this.codeVerifier = codeVerifier;
            this.authorizationUrl = authorizationUrl;
        }
    }

    public record SignInStarted(String attemptId, String authorizationUrl) {}

    private final StoreAuthGateway gateway;
    private final CloudAccountBindingRepository bindings;
    private final String apiBase;
    private final String clientId;
    private final int callbackPort;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final ExecutorService attemptExecutor = Executors.newCachedThreadPool();

    public CloudAccountService(StoreAuthGateway gateway,
            CloudAccountBindingRepository bindings,
            @Value("${fengyu.store.api-base:http://localhost:8080}") String apiBase,
            @Value("${fengyu.store.client-id:fengyu-desktop}") String clientId,
            @Value("${fengyu.store.callback-port:24057}") int callbackPort) {
        this.gateway = gateway;
        this.bindings = bindings;
        this.apiBase = normalize(apiBase);
        this.clientId = clientId;
        this.callbackPort = callbackPort;
    }

    private static String normalize(String base) {
        String trimmed = base == null ? "" : base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Starts a sign-in attempt: launches the loopback callback server and returns the
     * authorization URL plus the attempt id the SPA polls.
     */
    public SignInStarted signIn() {
        String attemptId = UUID.randomUUID().toString();
        String state = randomUrlSafe(32);
        String codeVerifier = randomUrlSafe(64);
        String redirectUri = "http://127.0.0.1:" + callbackPort + "/callback";
        String authorizationUrl = authorizationUrl(redirectUri, state,
                pkceChallenge(codeVerifier));
        Attempt attempt = new Attempt(state, codeVerifier, authorizationUrl);
        attempts.put(attemptId, attempt);

        CompletableFuture<String> code = startCallbackServer(attempt, redirectUri);
        attemptExecutor.submit(() -> awaitCode(attemptId, attempt, code, redirectUri));
        return new SignInStarted(attemptId, authorizationUrl);
    }

    String authorizationUrl(String redirectUri, String state, String codeChallenge) {
        return apiBase + "/oauth2/authorize"
                + "?response_type=code"
                + "&client_id=" + url(clientId)
                + "&scope=" + url("openid profile offline_access")
                + "&redirect_uri=" + url(redirectUri)
                + "&state=" + url(state)
                + "&code_challenge=" + url(codeChallenge)
                + "&code_challenge_method=S256";
    }

    private CompletableFuture<String> startCallbackServer(Attempt attempt, String redirectUri) {
        CompletableFuture<String> code = attempt.code;
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",
                    callbackPort), 0);
            server.createContext("/callback", exchange -> {
                URI uri = exchange.getRequestURI();
                String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
                String codeParam = queryParam(query, "code");
                String stateParam = queryParam(query, "state");
                byte[] body;
                if (codeParam != null && stateParam != null
                        && stateParam.equals(attempt.state)) {
                    body = "Sign-in received. You can close this tab and return to FengYu."
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    code.complete(codeParam);
                } else {
                    String error = queryParam(query, "error");
                    body = ("Sign-in failed" + (error == null ? "" : ": " + error)
                            + ". Please return to FengYu and try again.")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(400, body.length);
                    code.completeExceptionally(
                            new IllegalStateException("authorization callback error: " + error));
                }
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            // The callback server only needs to live until the code arrives (or times out).
            code.whenComplete((ignored, failure) -> {
                if (failure instanceof TimeoutException) {
                    server.stop(0);
                } else {
                    // Give the response a moment to flush before releasing the port.
                    attemptExecutor.submit(() -> {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        server.stop(0);
                    });
                }
            });
            code.orTimeout(ATTEMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (IOException e) {
            code.completeExceptionally(new IllegalStateException(
                    "cannot listen on 127.0.0.1:" + callbackPort + " for the sign-in callback: "
                            + e.getMessage(), e));
        }
        return code;
    }

    private void awaitCode(String attemptId, Attempt attempt, CompletableFuture<String> code,
            String redirectUri) {
        try {
            String authorizationCode = code.get(ATTEMPT_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);
            StoreAuthGateway.TokenGrant grant =
                    gateway.exchange(authorizationCode, attempt.codeVerifier, redirectUri);
            StoreAuthGateway.StoreProfile profile = gateway.me(grant.accessToken());
            saveBinding(profile, grant);
            attempt.outcome = new AttemptView(AttemptStatus.COMPLETED,
                    toView(profile), null);
            log.info("Cloud sign-in completed for {} ({})", profile.displayName(),
                    profile.userId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            attempt.outcome = new AttemptView(AttemptStatus.FAILED, null, "interrupted");
        } catch (ExecutionException | TimeoutException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            attempt.outcome = new AttemptView(AttemptStatus.FAILED, null,
                    cause.getMessage() == null ? "sign-in timed out" : cause.getMessage());
        } catch (RuntimeException e) {
            attempt.outcome = new AttemptView(AttemptStatus.FAILED, null, e.getMessage());
        } finally {
            attempts.remove(attemptId, attempt);
            // Keep terminal outcome queryable for a grace period via the completed future below.
            completedAttempts.put(attemptId, attempt.outcome);
        }
    }

    private final ConcurrentHashMap<String, AttemptView> completedAttempts =
            new ConcurrentHashMap<>();

    private void saveBinding(StoreAuthGateway.StoreProfile profile,
            StoreAuthGateway.TokenGrant grant) {
        CloudAccountBindingEntity binding =
                bindings.findById(CloudAccountBindingEntity.SINGLETON_ID)
                        .orElseGet(() -> {
                            CloudAccountBindingEntity fresh = new CloudAccountBindingEntity();
                            fresh.setId(CloudAccountBindingEntity.SINGLETON_ID);
                            return fresh;
                        });
        binding.setStoreUserId(profile.userId());
        binding.setEmail(profile.email());
        binding.setDisplayName(profile.displayName());
        binding.setRoles(String.join(",", profile.roles()));
        binding.setAccessToken(grant.accessToken());
        binding.setAccessExpiresAt(Instant.now().plusSeconds(
                Math.max(grant.expiresInSeconds(), 60)));
        if (grant.refreshToken() != null && !grant.refreshToken().isBlank()) {
            binding.setRefreshToken(grant.refreshToken());
        }
        binding.setUpdatedAt(Instant.now());
        bindings.saveAndFlush(binding);
    }

    /** Attempt status for SPA polling; unknown ids (post-cleanup) report FAILED. */
    public AttemptView attempt(String attemptId) {
        AttemptView completed = completedAttempts.get(attemptId);
        if (completed != null) {
            return completed;
        }
        Attempt attempt = attempts.get(attemptId);
        if (attempt != null && attempt.outcome != null) {
            return attempt.outcome;
        }
        return attempt == null
                ? new AttemptView(AttemptStatus.FAILED, null, "unknown sign-in attempt")
                : new AttemptView(AttemptStatus.PENDING, null, null);
    }

    /** Current account: the bound cloud user, or the local virtual user when signed out. */
    public AccountView currentUser() {
        return bindings.findById(CloudAccountBindingEntity.SINGLETON_ID)
                .map(this::toView)
                .orElse(localView());
    }

    private AccountView toView(CloudAccountBindingEntity binding) {
        List<String> roles = binding.getRoles() == null || binding.getRoles().isBlank()
                ? List.of()
                : List.of(binding.getRoles().split(","));
        return new AccountView(true, binding.getStoreUserId(), binding.getDisplayName(),
                binding.getEmail(), roles);
    }

    private AccountView toView(StoreAuthGateway.StoreProfile profile) {
        return new AccountView(true, profile.userId(), profile.displayName(),
                profile.email(), profile.roles());
    }

    private AccountView localView() {
        return new AccountView(false,
                String.valueOf(SecurityConstants.LOCAL_VIRTUAL_USER_ID),
                SecurityConstants.LOCAL_VIRTUAL_USERNAME, null, List.of());
    }

    /** Clears the binding and best-effort revokes the refresh token. */
    public void signOut() {
        bindings.findById(CloudAccountBindingEntity.SINGLETON_ID).ifPresent(binding -> {
            if (binding.getRefreshToken() != null) {
                gateway.revoke(binding.getRefreshToken());
            }
            bindings.delete(binding);
            bindings.flush();
        });
    }

    /**
     * Valid access token for outbound store calls, refreshing when close to expiry.
     * Returns null when signed out — callers fall back to anonymous access.
     */
    @Override
    public String accessToken() {
        CloudAccountBindingEntity binding =
                bindings.findById(CloudAccountBindingEntity.SINGLETON_ID).orElse(null);
        if (binding == null) {
            return null;
        }
        Instant safeExpiry = Instant.now()
                .plusSeconds(ACCESS_TOKEN_SAFETY_MARGIN_SECONDS);
        if (binding.getAccessToken() != null && binding.getAccessExpiresAt() != null
                && binding.getAccessExpiresAt().isAfter(safeExpiry)) {
            return binding.getAccessToken();
        }
        if (binding.getRefreshToken() == null) {
            return null;
        }
        try {
            StoreAuthGateway.TokenGrant grant = gateway.refresh(binding.getRefreshToken());
            binding.setAccessToken(grant.accessToken());
            binding.setAccessExpiresAt(Instant.now().plusSeconds(
                    Math.max(grant.expiresInSeconds(), 60)));
            if (grant.refreshToken() != null && !grant.refreshToken().isBlank()) {
                binding.setRefreshToken(grant.refreshToken());
            }
            binding.setUpdatedAt(Instant.now());
            bindings.saveAndFlush(binding);
            return grant.accessToken();
        } catch (RuntimeException e) {
            // A stale binding must not break anonymous store browsing.
            log.warn("Cloud token refresh failed, continuing anonymously: {}", e.getMessage());
            return null;
        }
    }

    String pkceChallenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    String randomUrlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static String queryParam(String rawQuery, String name) {
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder(pair.substring(eq + 1));
            }
        }
        return null;
    }

    private static String URLDecoder(String encoded) {
        return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    private static String url(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
