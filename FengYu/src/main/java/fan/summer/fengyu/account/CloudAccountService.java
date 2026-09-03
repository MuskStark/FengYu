package fan.summer.fengyu.account;

import com.sun.net.httpserver.HttpServer;
import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.store.StoreBearerTokenSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import fan.summer.fengyu.store.StoreEndpointProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
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
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cloud account sign-in for the desktop host (design §7.2): OAuth 2.1
 * authorization code + PKCE through the system browser, with the code received
 * on a temporary loopback callback server bound to an OS-assigned port (RFC
 * 8252 §7.3 — a native public client must not ship a client secret). The
 * renderer opens the returned authorization URL because this backend is
 * intentionally headless.
 *
 * <p>Token storage follows the native-app boundary (review M-5): the access
 * token lives only in memory, refreshes are serialized (a rotated refresh
 * token can never be lost to a concurrent refresh), and the refresh token
 * only ever rests in the OS credential store — never in the database. Signing
 * in never changes local data ownership — it only enables authenticated
 * outbound calls to the store (ADR-002).
 */
@Service
public class CloudAccountService implements StoreBearerTokenSupplier {

    private static final Logger log = LoggerFactory.getLogger(CloudAccountService.class);
    private static final long ATTEMPT_TIMEOUT_SECONDS = 300;
    private static final long ACCESS_TOKEN_SAFETY_MARGIN_SECONDS = 30;
    /** Terminal attempt outcomes stay queryable for a grace period, then go. */
    private static final Duration ATTEMPT_GRACE = Duration.ofMinutes(10);
    private static final String REFRESH_TOKEN_SECRET = "fengyu.cloud.refresh-token";

    /** View returned to the SPA: local virtual user when not signed in. */
    public record AccountView(boolean authenticated, String userId, String username,
            String email, List<String> roles) {}

    public enum AttemptStatus { PENDING, COMPLETED, FAILED }

    public record AttemptView(AttemptStatus status, AccountView user, String error) {}

    private record CachedAccessToken(String token, Instant expiresAt) {}

    private record CompletedAttempt(AttemptView view, Instant completedAt) {}

    private static final class Attempt {
        final String state;
        final String codeVerifier;
        final String authorizationUrl;
        final CompletableFuture<String> code;
        volatile AttemptView outcome;

        Attempt(String state, String codeVerifier, String authorizationUrl,
                CompletableFuture<String> code) {
            this.state = state;
            this.codeVerifier = codeVerifier;
            this.authorizationUrl = authorizationUrl;
            this.code = code;
        }
    }

    public record SignInStarted(String attemptId, String authorizationUrl) {}

    private final StoreAuthGateway gateway;
    private final CloudAccountBindingRepository bindings;
    private final CloudSecretStore secrets;
    private final StoreEndpointProvider endpoints;
    private final String clientId;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletedAttempt> completedAttempts =
            new ConcurrentHashMap<>();
    private final ExecutorService attemptExecutor = Executors.newCachedThreadPool();
    /** Serializes token refreshes so a rotated refresh token is persisted exactly once. */
    private final ReentrantLock refreshLock = new ReentrantLock();
    /** Access token cache — memory only, by design. */
    private volatile CachedAccessToken cachedAccessToken;

    public CloudAccountService(StoreAuthGateway gateway,
            CloudAccountBindingRepository bindings, CloudSecretStore secrets,
            StoreEndpointProvider endpoints,
            @Value("${fengyu.store.client-id:fengyu-desktop}") String clientId) {
        this.gateway = gateway;
        this.bindings = bindings;
        this.secrets = secrets;
        this.endpoints = endpoints;
        this.clientId = clientId;
    }

    /**
     * Starts a sign-in attempt: binds a one-time loopback callback server on an
     * OS-assigned port and returns the authorization URL plus the attempt id the
     * SPA polls.
     */
    public SignInStarted signIn() {
        String attemptId = UUID.randomUUID().toString();
        String state = randomUrlSafe(32);
        String codeVerifier = randomUrlSafe(64);

        CompletableFuture<String> code = new CompletableFuture<>();
        HttpServer server = bindCallbackServer(state, code);
        if (server == null) {
            throw new IllegalStateException(
                    "cannot listen on 127.0.0.1 for the sign-in callback");
        }
        String redirectUri = "http://127.0.0.1:" + server.getAddress().getPort()
                + "/callback";
        code.orTimeout(ATTEMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        code.whenComplete((ignored, failure) -> attemptExecutor.submit(() -> {
            try {
                Thread.sleep(500); // let the browser response flush
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            server.stop(0);
        }));

        String authorizationUrl = authorizationUrl(redirectUri, state,
                pkceChallenge(codeVerifier));
        Attempt attempt = new Attempt(state, codeVerifier, authorizationUrl, code);
        attempts.put(attemptId, attempt);
        attemptExecutor.submit(() -> awaitCode(attemptId, attempt, redirectUri));
        return new SignInStarted(attemptId, authorizationUrl);
    }

    private HttpServer bindCallbackServer(String state, CompletableFuture<String> code) {
        try {
            // Port 0 → OS-assigned: per RFC 8252 the loopback redirect uses an
            // ephemeral port, so nothing is pinned and no second instance collides.
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/callback", exchange -> {
                URI uri = exchange.getRequestURI();
                String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
                String codeParam = queryParam(query, "code");
                String stateParam = queryParam(query, "state");
                boolean success = codeParam != null && stateParam != null
                        && stateParam.equals(state);
                String error = success ? null : queryParam(query, "error");
                String acceptLanguage = exchange.getRequestHeaders()
                        .getFirst("Accept-Language");
                byte[] body = callbackPage(success, error, acceptLanguage);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(success ? 200 : 400, body.length);
                if (success) {
                    code.complete(codeParam);
                } else {
                    code.completeExceptionally(
                            new IllegalStateException("authorization callback error: " + error));
                }
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Self-contained branded callback page the user sees in the browser between
     * the store sign-in and the host. Localized by Accept-Language (zh first);
     * the page tries to close itself and always offers a manual close, because
     * window.close() only works on script-opened tabs.
     */
    private static byte[] callbackPage(boolean success, String error, String acceptLanguage) {
        boolean zh = acceptLanguage != null
                && acceptLanguage.toLowerCase(java.util.Locale.ROOT).contains("zh");
        String title = success
                ? (zh ? "登录成功" : "Sign-in successful")
                : (zh ? "登录未完成" : "Sign-in failed");
        String message = success
                ? (zh
                        ? "FengYu 已安全连接 Infinia 商店。请返回主程序继续,此页面可以关闭。"
                        : "FengYu is now connected to the Infinia Store. Please return to "
                                + "the FengYu app; you can close this tab.")
                : (zh
                        ? "授权没有到达 FengYu,请回到主程序重新发起登录。"
                        : "The authorization did not reach FengYu. Please start the "
                                + "sign-in again from the FengYu app.");
        String closeLabel = zh ? "关闭此页面" : "Close this tab";
        String closeHint = zh ? "若页面未自动关闭,请手动关闭后返回 FengYu。" : null;
        String errorBlock = error == null ? ""
                : "<p class=\"error\"><span class=\"error-label\">"
                        + (zh ? "错误详情" : "Error") + "</span>"
                        + escape(error) + "</p>";
        String icon = success
                ? """
                  <svg class="icon" viewBox="0 0 48 48" fill="none" aria-hidden="true">
                    <circle cx="24" cy="24" r="22" class="icon-ring"/>
                    <path d="M14 24.5 21 31.5 34 17.5" class="icon-stroke"/>
                  </svg>"""
                : """
                  <svg class="icon" viewBox="0 0 48 48" fill="none" aria-hidden="true">
                    <circle cx="24" cy="24" r="22" class="icon-ring icon-ring-fail"/>
                    <path d="M16.5 16.5 31.5 31.5 M31.5 16.5 16.5 31.5" class="icon-stroke icon-stroke-fail"/>
                  </svg>""";
        return """
              <!doctype html>
              <html lang="%LANG%">
              <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>%TITLE%</title>
              <style>
                :root { color-scheme: light dark; }
                * { box-sizing: border-box; margin: 0; }
                body {
                  min-height: 100vh; display: grid; place-items: center; padding: 24px;
                  font-family: Inter, 'SF Pro Text', -apple-system, BlinkMacSystemFont,
                    'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
                  color: #0f172a;
                  background:
                    radial-gradient(60rem 60rem at 120% -20%, rgba(99,102,241,.14), transparent 60%),
                    radial-gradient(50rem 50rem at -20% 120%, rgba(217,70,239,.10), transparent 60%),
                    #f6f7fb;
                }
                .card {
                  width: min(420px, 100%); text-align: center;
                  background: #ffffff; border: 1px solid #e2e8f0; border-radius: 20px;
                  padding: 44px 36px 36px;
                  box-shadow: 0 24px 60px -32px rgba(15, 23, 42, .28);
                }
                .logo {
                  width: 56px; height: 56px; margin: 0 auto 18px; border-radius: 16px;
                  display: grid; place-items: center;
                  background: linear-gradient(135deg, #6366f1, #d946ef);
                  color: #fff; font-size: 30px; font-weight: 700; line-height: 1;
                }
                .icon { width: 72px; height: 72px; margin: 0 auto 14px; display: block; }
                .icon-ring { stroke: #e2e8f0; stroke-width: 2.5; }
                .icon-ring-fail { stroke: #fecaca; }
                .icon-stroke {
                  stroke: #22c55e; stroke-width: 4; stroke-linecap: round; stroke-linejoin: round;
                  stroke-dasharray: 40; stroke-dashoffset: 40; animation: draw .5s .15s ease-out forwards;
                }
                .icon-stroke-fail { stroke: #ef4444; stroke-dasharray: 24; animation: draw .4s .15s ease-out forwards; }
                @keyframes draw { to { stroke-dashoffset: 0; } }
                h1 { font-size: 20px; font-weight: 700; letter-spacing: -.01em; }
                .message { margin-top: 10px; font-size: 14px; line-height: 1.7; color: #64748b; }
                .error {
                  margin: 18px 0 0; padding: 10px 12px; border-radius: 10px; text-align: left;
                  border: 1px solid #fecaca; background: #fef2f2; color: #b91c1c;
                  font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace;
                  font-size: 12px; line-height: 1.5; word-break: break-all;
                }
                .error-label { display: block; font-family: inherit; font-weight: 600; margin-bottom: 4px; }
                button {
                  margin-top: 26px; min-width: 180px; padding: 11px 22px;
                  border: 0; border-radius: 12px; cursor: pointer;
                  background: linear-gradient(110deg, #6366f1, #d946ef);
                  color: #fff; font-size: 14px; font-weight: 600; font-family: inherit;
                }
                button:hover { filter: brightness(1.05); }
                .hint { margin-top: 16px; font-size: 12px; color: #94a3b8; }
                @media (prefers-color-scheme: dark) {
                  body {
                    color: #e2e8f0;
                    background:
                      radial-gradient(60rem 60rem at 120% -20%, rgba(99,102,241,.20), transparent 60%),
                      radial-gradient(50rem 50rem at -20% 120%, rgba(217,70,239,.14), transparent 60%),
                      #020617;
                  }
                  .card { background: #0f172a; border-color: #1e293b; box-shadow: 0 24px 60px -32px rgba(0,0,0,.8); }
                  .icon-ring { stroke: #1e293b; }
                  .icon-ring-fail { stroke: #7f1d1d; }
                  .message { color: #94a3b8; }
                  .error { border-color: #7f1d1d; background: rgba(127,29,29,.18); color: #fca5a5; }
                  .hint { color: #64748b; }
                }
                @media (prefers-reduced-motion: reduce) { .icon-stroke { animation: none; stroke-dashoffset: 0; } }
              </style>
              </head>
              <body>
              <main class="card">
                <div class="logo">&infin;</div>
                %ICON%
                <h1>%TITLE%</h1>
                <p class="message">%MESSAGE%</p>
                %ERROR%
                <button type="button" onclick="window.close()">%CLOSE%</button>
                %HINT%
              </main>
              <script>setTimeout(function () { window.close(); }, 1500);</script>
              </body>
              </html>
              """
                .replace("%LANG%", zh ? "zh-CN" : "en")
                .replace("%TITLE%", escape(title))
                .replace("%ICON%", icon)
                .replace("%MESSAGE%", escape(message))
                .replace("%ERROR%", errorBlock)
                .replace("%CLOSE%", escape(closeLabel))
                .replace("%HINT%", closeHint == null
                        ? "<p class=\"hint\">You can safely close this tab.</p>"
                        : "<p class=\"hint\">" + escape(closeHint) + "</p>")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Minimal HTML escaping — the error string is reflected query input. */
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    String authorizationUrl(String redirectUri, String state, String codeChallenge) {
        return endpoints.base() + "/oauth2/authorize"
                + "?response_type=code"
                + "&client_id=" + url(clientId)
                + "&scope=" + url("openid profile offline_access")
                + "&redirect_uri=" + url(redirectUri)
                + "&state=" + url(state)
                + "&code_challenge=" + url(codeChallenge)
                + "&code_challenge_method=S256";
    }

    private void awaitCode(String attemptId, Attempt attempt, String redirectUri) {
        try {
            String authorizationCode = attempt.code
                    .get(ATTEMPT_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);
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
            rememberOutcome(attemptId, attempt.outcome);
        }
    }

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
        binding.setUpdatedAt(Instant.now());
        bindings.saveAndFlush(binding);

        cachedAccessToken = new CachedAccessToken(grant.accessToken(),
                Instant.now().plusSeconds(Math.max(grant.expiresInSeconds(), 60)));
        if (grant.refreshToken() != null && !grant.refreshToken().isBlank()) {
            try {
                secrets.save(REFRESH_TOKEN_SECRET, grant.refreshToken());
            } catch (RuntimeException e) {
                // No OS credential store (or it failed): stay signed in for this
                // session only; nothing weaker is ever written.
                log.warn("Could not persist the refresh token in the OS credential "
                        + "store; the session stays signed in until restart: {}",
                        e.toString());
            }
        }
    }

    /** Attempt status for SPA polling; unknown ids (post-cleanup) report FAILED. */
    public AttemptView attempt(String attemptId) {
        CompletedAttempt completed = completedAttempts.get(attemptId);
        if (completed != null) {
            return completed.view();
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

    /** Clears the binding, best-effort revokes, and wipes every token copy. */
    public void signOut() {
        cachedAccessToken = null;
        bindings.findById(CloudAccountBindingEntity.SINGLETON_ID).ifPresent(binding -> {
            secrets.load(REFRESH_TOKEN_SECRET).ifPresent(gateway::revoke);
            try {
                secrets.delete(REFRESH_TOKEN_SECRET);
            } catch (RuntimeException e) {
                log.warn("Could not delete the stored refresh token: {}", e.toString());
            }
            bindings.delete(binding);
            bindings.flush();
        });
    }

    /**
     * Valid access token for outbound store calls, refreshing when close to expiry.
     * Refreshes are serialized so a server-side refresh-token rotation can never
     * race a second concurrent call into anonymous mode. Returns null when signed
     * out — callers fall back to anonymous access.
     */
    @Override
    public String accessToken() {
        CachedAccessToken cached = cachedAccessToken;
        if (valid(cached)) {
            return cached.token();
        }
        if (!bindings.findById(CloudAccountBindingEntity.SINGLETON_ID).isPresent()) {
            return null;
        }
        refreshLock.lock();
        try {
            cached = cachedAccessToken; // re-check: a concurrent refresh may have won
            if (valid(cached)) {
                return cached.token();
            }
            String refreshToken;
            try {
                refreshToken = secrets.load(REFRESH_TOKEN_SECRET).orElse(null);
            } catch (RuntimeException e) {
                log.warn("OS credential store read failed, continuing anonymously: {}",
                        e.toString());
                return null;
            }
            if (refreshToken == null) {
                return null;
            }
            try {
                StoreAuthGateway.TokenGrant grant = gateway.refresh(refreshToken);
                cachedAccessToken = new CachedAccessToken(grant.accessToken(),
                        Instant.now().plusSeconds(Math.max(grant.expiresInSeconds(), 60)));
                if (grant.refreshToken() != null && !grant.refreshToken().isBlank()
                        && !grant.refreshToken().equals(refreshToken)) {
                    secrets.save(REFRESH_TOKEN_SECRET, grant.refreshToken());
                }
                return grant.accessToken();
            } catch (RuntimeException e) {
                // A stale binding must not break anonymous store browsing.
                log.warn("Cloud token refresh failed, continuing anonymously: {}",
                        e.getMessage());
                return null;
            }
        } finally {
            refreshLock.unlock();
        }
    }

    private static boolean valid(CachedAccessToken cached) {
        return cached != null && cached.expiresAt() != null && cached.expiresAt()
                .isAfter(Instant.now().plusSeconds(ACCESS_TOKEN_SAFETY_MARGIN_SECONDS));
    }

    /** Test seam: seeds the in-memory access-token cache. */
    void cacheAccessTokenForTest(String token, Instant expiresAt) {
        this.cachedAccessToken = new CachedAccessToken(token, expiresAt);
    }

    private void rememberOutcome(String attemptId, AttemptView outcome) {
        Instant now = Instant.now();
        // Bound the map: terminal outcomes only outlive their attempt briefly (N-2).
        completedAttempts.values().removeIf(
                completed -> completed.completedAt().isBefore(now.minus(ATTEMPT_GRACE)));
        completedAttempts.put(attemptId, new CompletedAttempt(outcome, now));
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
