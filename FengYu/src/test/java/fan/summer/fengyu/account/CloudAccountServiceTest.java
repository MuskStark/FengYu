package fan.summer.fengyu.account;

import fan.summer.fengyu.account.StoreAuthGateway.StoreProfile;
import fan.summer.fengyu.account.StoreAuthGateway.TokenGrant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the cloud account service: PKCE, token refresh, views, sign-out. */
class CloudAccountServiceTest {

    private static final String RFC7636_VERIFIER =
            "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String RFC7636_CHALLENGE =
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private StoreAuthGateway gateway;
    private CloudAccountBindingRepository bindings;
    private CloudAccountService service;

    @BeforeEach
    void setUp() {
        gateway = mock(StoreAuthGateway.class);
        bindings = mock(CloudAccountBindingRepository.class);
        when(bindings.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new CloudAccountService(gateway, bindings, "http://localhost:8080/",
                "fengyu-desktop", 24057);
    }

    private CloudAccountBindingEntity binding(String accessToken, Instant expiresAt,
            String refreshToken) {
        CloudAccountBindingEntity entity = new CloudAccountBindingEntity();
        entity.setId(CloudAccountBindingEntity.SINGLETON_ID);
        entity.setStoreUserId("user-1");
        entity.setEmail("dev@example.com");
        entity.setDisplayName("Dev");
        entity.setRoles("USER,PUBLISHER");
        entity.setAccessToken(accessToken);
        entity.setAccessExpiresAt(expiresAt);
        entity.setRefreshToken(refreshToken);
        return entity;
    }

    @Test
    void pkceChallenge_matchesRfc7636Vector() {
        assertEquals(RFC7636_CHALLENGE, service.pkceChallenge(RFC7636_VERIFIER));
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
                .thenReturn(Optional.of(binding("tok", Instant.now().plusSeconds(600),
                        "refresh")));
        var view = service.currentUser();
        assertTrue(view.authenticated());
        assertEquals("Dev", view.username());
        assertEquals(List.of("USER", "PUBLISHER"), view.roles());
    }

    @Test
    void accessToken_nullWhenSignedOut() {
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.empty());
        assertNull(service.accessToken());
    }

    @Test
    void accessToken_returnsValidTokenWithoutRefresh() {
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(binding("valid-token",
                        Instant.now().plusSeconds(600), "refresh")));
        assertEquals("valid-token", service.accessToken());
    }

    @Test
    void accessToken_refreshesWhenCloseToExpiry() {
        CloudAccountBindingEntity expiring = binding("stale",
                Instant.now().plusSeconds(5), "refresh-token");
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(expiring));
        when(gateway.refresh("refresh-token"))
                .thenReturn(new TokenGrant("fresh-token", 1800, "rotated-refresh"));

        assertEquals("fresh-token", service.accessToken());
        verify(gateway).refresh("refresh-token");
        assertEquals("fresh-token", expiring.getAccessToken());
        assertEquals("rotated-refresh", expiring.getRefreshToken());
    }

    @Test
    void accessToken_refreshFailureFallsBackToAnonymous() {
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(binding("stale", Instant.now().plusSeconds(5),
                        "refresh-token")));
        when(gateway.refresh("refresh-token")).thenThrow(new IllegalStateException("400"));
        assertNull(service.accessToken());
    }

    @Test
    void signOut_revokesRefreshTokenAndDeletesBinding() {
        CloudAccountBindingEntity entity = binding("tok", Instant.now().plusSeconds(600),
                "refresh-token");
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(entity));
        service.signOut();
        verify(gateway).revoke("refresh-token");
        verify(bindings).delete(entity);
    }

    @Test
    void attempt_unknownIdReportsFailed() {
        var view = service.attempt("nope");
        assertEquals(CloudAccountService.AttemptStatus.FAILED, view.status());
    }
}
