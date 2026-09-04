package fan.summer.fengyu.account;

import fan.summer.fengyu.account.CloudAccountService.SignInStarted;
import fan.summer.fengyu.account.StoreAuthGateway.StoreProfile;
import fan.summer.fengyu.account.StoreAuthGateway.TokenGrant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cloud account unit tests for the native-app OAuth boundary (review M-5):
 * PKCE public client, ephemeral loopback callback port, memory-only access
 * token, refresh token only in the (faked) OS credential store, serialized
 * refresh with rotation persisted exactly once.
 */
class CloudAccountServiceTest {

    private static final String RFC7636_VERIFIER =
            "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String RFC7636_CHALLENGE =
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    /** In-memory stand-in for the OS credential store. */
    static class FakeSecretStore implements CloudSecretStore {
        final Map<String, String> secrets = new ConcurrentHashMap<>();

        @Override public void save(String name, String value) { secrets.put(name, value); }

        @Override public Optional<String> load(String name) {
            return Optional.ofNullable(secrets.get(name));
        }

        @Override public void delete(String name) { secrets.remove(name); }

        @Override public boolean available() { return true; }
    }

    private StoreAuthGateway gateway;
    private CloudAccountBindingRepository bindings;
    private FakeSecretStore secrets;
    private CloudAccountService service;

    @BeforeEach
    void setUp() {
        gateway = mock(StoreAuthGateway.class);
        bindings = mock(CloudAccountBindingRepository.class);
        when(bindings.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        secrets = new FakeSecretStore();
        service = new CloudAccountService(gateway, bindings, secrets,
                new fan.summer.fengyu.store.StoreEndpointProvider(
                        "http://localhost:8080/", () -> "", false),
                "fengyu-desktop");
    }

    private CloudAccountBindingEntity binding() {
        CloudAccountBindingEntity entity = new CloudAccountBindingEntity();
        entity.setId(CloudAccountBindingEntity.SINGLETON_ID);
        entity.setStoreUserId("user-1");
        entity.setEmail("dev@example.com");
        entity.setDisplayName("Dev");
        entity.setRoles("USER,PUBLISHER");
        return entity;
    }

    @Test
    void pkceChallenge_matchesRfc7636Vector() {
        assertEquals(RFC7636_CHALLENGE, service.pkceChallenge(RFC7636_VERIFIER));
    }

    @Test
    void authorizationUrl_isPkcePublicClientWithoutSecret() {
        String authorizationUrl = service.authorizationUrl(
                "http://127.0.0.1:49152/callback", "state-value", "challenge-value");

        assertTrue(authorizationUrl.contains("client_id=fengyu-desktop"));
        assertTrue(authorizationUrl.contains("scope=openid+profile+offline_access"));
        assertTrue(authorizationUrl.contains(
                "redirect_uri=http%3A%2F%2F127.0.0.1%3A49152%2Fcallback"));
        assertTrue(authorizationUrl.contains("code_challenge=challenge-value"));
        assertTrue(authorizationUrl.contains("code_challenge_method=S256"));
        assertFalse(authorizationUrl.contains("client_secret"),
                "a public client never carries a secret");
    }

    @Test
    void randomUrlSafe_isUrlSafeAndVariable() {
        String a = service.randomUrlSafe(32);
        String b = service.randomUrlSafe(32);
        assertTrue(a.matches("[A-Za-z0-9_-]+"));
        assertEquals(43, a.length()); // 32 bytes -> ceil(256/6) base64url chars
        assertFalse(a.equals(b));
    }

    @Test
    void signIn_completesOverTheEphemeralCallbackPortAndKeepsTokensOutOfTheDatabase()
            throws Exception {
        when(gateway.exchange(any(), any(), any())).thenReturn(
                new TokenGrant("access-1", 1800, "refresh-1"));
        when(gateway.me("access-1")).thenReturn(
                new StoreProfile("user-1", "dev@example.com", "Dev", List.of("USER"), 1, null));
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.empty());

        SignInStarted started = service.signIn();

        // The browser's redirect hits the one-time loopback server on its
        // OS-assigned port; state comes straight from the authorization URL.
        String redirectUri = queryParamOf(started.authorizationUrl(), "redirect_uri");
        String state = queryParamOf(started.authorizationUrl(), "state");
        HttpRequest callback = HttpRequest.newBuilder(
                URI.create(redirectUri + "?code=auth-code&state=" + state)).GET().build();
        int status = HttpClient.newHttpClient().send(callback,
                java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();

        assertEquals(200, status);
        awaitCompleted(started.attemptId());
        assertEquals(CloudAccountService.AttemptStatus.COMPLETED,
                service.attempt(started.attemptId()).status());
        // Access token serves from memory; the refresh token rests ONLY in the
        // OS credential store, never on the binding row.
        assertEquals("access-1", service.accessToken());
        assertEquals("refresh-1", secrets.secrets.get("fengyu.cloud.refresh-token"));
    }

    @Test
    void accessToken_nullWhenSignedOut() {
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.empty());
        assertNull(service.accessToken());
    }

    @Test
    void accessToken_returnsCachedTokenWithoutRefresh() {
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(binding()));
        service.cacheAccessTokenForTest("valid-token", Instant.now().plusSeconds(600));
        assertEquals("valid-token", service.accessToken());
        verify(gateway, org.mockito.Mockito.never()).refresh(any());
    }

    @Test
    void accessToken_refreshesWhenCloseToExpiryAndPersistsRotation() {
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(binding()));
        service.cacheAccessTokenForTest("stale", Instant.now().plusSeconds(5));
        secrets.secrets.put("fengyu.cloud.refresh-token", "refresh-token");
        when(gateway.refresh("refresh-token"))
                .thenReturn(new TokenGrant("fresh-token", 1800, "rotated-refresh"));

        assertEquals("fresh-token", service.accessToken());

        verify(gateway).refresh("refresh-token");
        assertEquals("rotated-refresh",
                secrets.secrets.get("fengyu.cloud.refresh-token"),
                "a rotated refresh token is written back to the credential store");
        assertEquals("fresh-token", service.accessToken(), "served from the cache");
    }

    @Test
    void accessToken_refreshFailureFallsBackToAnonymous() {
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(binding()));
        service.cacheAccessTokenForTest("stale", Instant.now().plusSeconds(5));
        secrets.secrets.put("fengyu.cloud.refresh-token", "refresh-token");
        when(gateway.refresh("refresh-token")).thenThrow(new IllegalStateException("400"));
        assertNull(service.accessToken());
    }

    @Test
    void signIn_publicGrantStoresTheIssuedRotatingCredential() throws Exception {
        // Public-client store: the code exchange carries no refresh token, so
        // the desktop session credential is issued separately and persisted.
        secrets.secrets.put("fengyu.cloud.refresh-token", "leftover-refresh");
        when(gateway.exchange(any(), any(), any())).thenReturn(
                new TokenGrant("access-1", 1800, null));
        when(gateway.issueSessionCredential("access-1")).thenReturn(
                new StoreAuthGateway.SessionCredential("issued-credential", 2592000));
        when(gateway.me("access-1")).thenReturn(
                new StoreProfile("user-1", "dev@example.com", "Dev", List.of("USER"), 1, null));
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.empty());

        SignInStarted started = service.signIn();
        String redirectUri = queryParamOf(started.authorizationUrl(), "redirect_uri");
        String state = queryParamOf(started.authorizationUrl(), "state");
        HttpRequest callback = HttpRequest.newBuilder(
                URI.create(redirectUri + "?code=auth-code&state=" + state)).GET().build();
        HttpClient.newHttpClient().send(callback,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        awaitCompleted(started.attemptId());

        assertEquals("issued-credential",
                secrets.secrets.get("fengyu.cloud.refresh-token"),
                "the issued credential replaces the stale stored one (localhost is secure)");
        assertNull(service.sessionOnlyRefreshTokenForTest(),
                "a secure channel never falls back to memory-only");
    }

    @Test
    void signIn_issueFailureStaysSessionOnlyAndWipesTheStaleToken() throws Exception {
        secrets.secrets.put("fengyu.cloud.refresh-token", "leftover-refresh");
        when(gateway.exchange(any(), any(), any())).thenReturn(
                new TokenGrant("access-1", 1800, null));
        when(gateway.issueSessionCredential("access-1")).thenThrow(
                new IllegalStateException("Store desktop session issue failed: HTTP 404"));
        when(gateway.me("access-1")).thenReturn(
                new StoreProfile("user-1", "dev@example.com", "Dev", List.of("USER"), 1, null));
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.empty());

        SignInStarted started = service.signIn();
        String redirectUri = queryParamOf(started.authorizationUrl(), "redirect_uri");
        String state = queryParamOf(started.authorizationUrl(), "state");
        HttpRequest callback = HttpRequest.newBuilder(
                URI.create(redirectUri + "?code=auth-code&state=" + state)).GET().build();
        HttpClient.newHttpClient().send(callback,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        awaitCompleted(started.attemptId());

        assertTrue(secrets.secrets.isEmpty(),
                "an issue failure wipes the stale stored token — it can never succeed");
        assertEquals("access-1", service.accessToken(),
                "the in-memory access token still serves this session");
    }

    @Test
    void signIn_overInsecureTransportKeepsTheCredentialMemoryOnly() throws Exception {
        // Plain HTTP to a LAN store: persisting a long-lived credential would
        // expose it to every network observer — the session is memory-only.
        CloudAccountService lan = new CloudAccountService(gateway, bindings, secrets,
                new fan.summer.fengyu.store.StoreEndpointProvider(
                        "http://10.0.0.5:8080/", () -> "", true),
                "fengyu-desktop");
        when(gateway.exchange(any(), any(), any())).thenReturn(
                new TokenGrant("access-1", 1800, null));
        when(gateway.issueSessionCredential("access-1")).thenReturn(
                new StoreAuthGateway.SessionCredential("lan-credential", 2592000));
        when(gateway.me("access-1")).thenReturn(
                new StoreProfile("user-1", "dev@example.com", "Dev", List.of("USER"), 1, null));
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.empty());

        SignInStarted started = lan.signIn();
        String redirectUri = queryParamOf(started.authorizationUrl(), "redirect_uri");
        String state = queryParamOf(started.authorizationUrl(), "state");
        HttpRequest callback = HttpRequest.newBuilder(
                URI.create(redirectUri + "?code=auth-code&state=" + state)).GET().build();
        HttpClient.newHttpClient().send(callback,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        awaitCompleted(lan, started.attemptId());

        assertTrue(secrets.secrets.isEmpty(),
                "nothing is written to the OS credential store over plain HTTP");
        assertEquals("lan-credential", lan.sessionOnlyRefreshTokenForTest());
    }

    @Test
    void accessToken_refreshesAMemoryOnlyCredentialOverInsecureTransport() {
        // The memory-only credential still renews mid-session (rotation stays
        // in memory); only a restart loses it — by design.
        CloudAccountService lan = new CloudAccountService(gateway, bindings, secrets,
                new fan.summer.fengyu.store.StoreEndpointProvider(
                        "http://10.0.0.5:8080/", () -> "", true),
                "fengyu-desktop");
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(binding()));
        lan.cacheAccessTokenForTest("stale", Instant.now().plusSeconds(5));
        lan.seedSessionOnlyRefreshTokenForTest("memory-credential");
        when(gateway.refresh("memory-credential")).thenReturn(
                new TokenGrant("fresh", 1800, "rotated-memory-credential"));

        assertEquals("fresh", lan.accessToken());
        assertEquals("rotated-memory-credential", lan.sessionOnlyRefreshTokenForTest(),
                "rotation over an insecure channel stays memory-only");
        assertTrue(secrets.secrets.isEmpty(), "the keychain is never touched");
    }

    @Test
    void accessToken_rejectedRefreshWipesTheStaleToken() {
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(binding()));
        service.cacheAccessTokenForTest("stale", Instant.now().plusSeconds(5));
        secrets.secrets.put("fengyu.cloud.refresh-token", "poisoned-refresh");
        when(gateway.refresh("poisoned-refresh")).thenThrow(new IllegalStateException(
                "Store token failed: HTTP 400 {\"error\":\"invalid_grant\"}"));

        assertNull(service.accessToken());
        assertTrue(secrets.secrets.isEmpty(),
                "a rejected refresh token is removed instead of poisoning every later call");
    }

    @Test
    void accessToken_networkFailureKeepsTheRefreshToken() {
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(binding()));
        service.cacheAccessTokenForTest("stale", Instant.now().plusSeconds(5));
        secrets.secrets.put("fengyu.cloud.refresh-token", "good-refresh");
        when(gateway.refresh("good-refresh")).thenThrow(new IllegalStateException(
                "Store token failed: java.net.ConnectException"));

        assertNull(service.accessToken());
        assertEquals("good-refresh", secrets.secrets.get("fengyu.cloud.refresh-token"),
                "a transport failure is not a credential rejection — the token stays");
        verify(bindings, org.mockito.Mockito.never()).delete(any());
    }

    @Test
    void accessToken_sessionWithoutRefreshTokenDropsTheDeadBinding() {
        // Public-client stores (SAS hard-gates refresh tokens away) never store a
        // refresh token; once the in-memory access token is gone the session can
        // never authenticate again. The binding must drop so the app falls back to
        // the local account instead of a "signed-in" shell that 401s forever.
        CloudAccountBindingEntity entity = binding();
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(entity));
        service.cacheAccessTokenForTest("expired", Instant.now().minusSeconds(60));

        assertNull(service.accessToken());

        verify(bindings).delete(entity);
        assertTrue(secrets.secrets.isEmpty());
    }

    @Test
    void accessToken_redirectRejectionWipesTokenAndDropsBinding() {
        // The public-client store answers the refresh grant with a login-page
        // redirect (HTTP 302), not an OAuth error — still a definitive rejection.
        CloudAccountBindingEntity entity = binding();
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(entity));
        service.cacheAccessTokenForTest("stale", Instant.now().plusSeconds(5));
        secrets.secrets.put("fengyu.cloud.refresh-token", "leftover-refresh");
        when(gateway.refresh("leftover-refresh")).thenThrow(new IllegalStateException(
                "Store token failed: HTTP 302 <html>sign-in page</html>"));

        assertNull(service.accessToken());

        assertTrue(secrets.secrets.isEmpty(),
                "a redirect rejection wipes the stored refresh token");
        verify(bindings).delete(entity);
    }

    @Test
    void accessToken_credentialStoreReadFailureKeepsTheBinding() {
        // A keychain hiccup is transient — it must not sign the user out.
        CloudAccountBindingEntity entity = binding();
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(entity));
        CloudSecretStore flaky = new CloudSecretStore() {
            @Override public void save(String name, String value) {
                throw new IllegalStateException("keychain locked");
            }
            @Override public Optional<String> load(String name) {
                throw new IllegalStateException("keychain locked");
            }
            @Override public void delete(String name) {
                throw new IllegalStateException("keychain locked");
            }
            @Override public boolean available() { return true; }
        };
        CloudAccountService flakyService = new CloudAccountService(gateway, bindings, flaky,
                new fan.summer.fengyu.store.StoreEndpointProvider(
                        "http://localhost:8080/", () -> "", false),
                "fengyu-desktop");
        flakyService.cacheAccessTokenForTest("stale", Instant.now().plusSeconds(5));

        assertNull(flakyService.accessToken());

        verify(bindings, org.mockito.Mockito.never()).delete(any());
    }

    @Test
    void signOut_survivesASecretStoreFailureAndStillDeletesTheBinding() {
        CloudSecretStore failing = new CloudSecretStore() {
            @Override public void save(String name, String value) {
                throw new IllegalStateException("no OS credential store");
            }
            @Override public Optional<String> load(String name) {
                throw new IllegalStateException("no OS credential store");
            }
            @Override public void delete(String name) {
                throw new IllegalStateException("no OS credential store");
            }
            @Override public boolean available() { return false; }
        };
        CloudAccountService brokenStoreService = new CloudAccountService(gateway, bindings,
                failing, new fan.summer.fengyu.store.StoreEndpointProvider(
                        "http://localhost:8080/", () -> "", false),
                "fengyu-desktop");
        CloudAccountBindingEntity entity = binding();
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(entity));

        brokenStoreService.signOut();

        verify(bindings).delete(entity);
    }

    @Test
    void accessToken_refreshIsSerializedSoRotationCannotRaceItself() throws Exception {
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(binding()));
        service.cacheAccessTokenForTest("stale", Instant.now().plusSeconds(5));
        secrets.secrets.put("fengyu.cloud.refresh-token", "refresh-token");
        CountDownLatch refreshEntered = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        AtomicInteger refreshCalls = new AtomicInteger();
        when(gateway.refresh("refresh-token")).thenAnswer(inv -> {
            refreshCalls.incrementAndGet();
            refreshEntered.countDown();
            releaseRefresh.await(5, TimeUnit.SECONDS);
            return new TokenGrant("fresh-token", 1800, null);
        });

        Thread first = new Thread(() -> service.accessToken());
        first.start();
        assertTrue(refreshEntered.await(5, TimeUnit.SECONDS));
        // A second caller while the refresh is in flight must wait, then see the
        // cached result instead of firing a second refresh (N-3 single-flight).
        Thread second = new Thread(() -> service.accessToken());
        second.start();
        Thread.sleep(200);
        releaseRefresh.countDown();
        first.join(5000);
        second.join(5000);

        assertEquals(1, refreshCalls.get());
    }

    @Test
    void signOut_revokesRefreshTokenFromTheStoreAndWipesEveryCopy() {
        CloudAccountBindingEntity entity = binding();
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(entity));
        secrets.secrets.put("fengyu.cloud.refresh-token", "refresh-token");
        service.cacheAccessTokenForTest("tok", Instant.now().plusSeconds(600));

        service.signOut();

        verify(gateway).revoke("refresh-token");
        verify(bindings).delete(entity);
        assertTrue(secrets.secrets.isEmpty(), "the stored refresh token is deleted");
        assertNull(service.accessToken(), "the in-memory access token is dropped");
    }

    @Test
    void currentUser_withoutBinding_returnsLocalVirtualUser() {
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.empty());
        var view = service.currentUser();
        assertFalse(view.authenticated());
        assertEquals("Summer", view.username());
    }

    @Test
    void currentUser_withBinding_returnsCloudUserWithRoles() {
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(binding()));
        var view = service.currentUser();
        assertTrue(view.authenticated());
        assertEquals("Dev", view.username());
        assertEquals(List.of("USER", "PUBLISHER"), view.roles());
    }

    @Test
    void attempt_unknownIdReportsFailed() {
        var view = service.attempt("nope");
        assertEquals(CloudAccountService.AttemptStatus.FAILED, view.status());
    }

    private void awaitCompleted(String attemptId) throws InterruptedException {
        awaitCompleted(service, attemptId);
    }

    private void awaitCompleted(CloudAccountService owner, String attemptId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (owner.attempt(attemptId).status()
                    != CloudAccountService.AttemptStatus.PENDING) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("sign-in attempt did not complete in time");
    }

    private static String queryParamOf(String url, String name) {
        String query = URI.create(url).getRawQuery();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("no " + name + " in " + url);
    }
}
