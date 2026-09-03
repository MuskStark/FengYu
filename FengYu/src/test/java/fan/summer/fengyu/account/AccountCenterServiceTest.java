package fan.summer.fengyu.account;

import fan.summer.fengyu.account.AccountCenterService.PasswordChangeRequest;
import fan.summer.fengyu.account.AccountCenterService.UpdateProfileRequest;
import fan.summer.fengyu.account.StoreAccountGateway.PasswordResult;
import fan.summer.fengyu.account.StoreAuthGateway.StoreProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The user-center proxy layer: every call requires the cloud account's access
 * token (401 when signed out), request validation happens before any outbound
 * call, and a display-name rename syncs the local binding so /api/account/me
 * stays fast and DB-only.
 */
class AccountCenterServiceTest {

    private CloudAccountService accounts;
    private StoreAuthGateway authGateway;
    private StoreAccountGateway accountGateway;
    private CloudAccountBindingRepository bindings;
    private AccountCenterService center;

    @BeforeEach
    void setUp() {
        accounts = mock(CloudAccountService.class);
        authGateway = mock(StoreAuthGateway.class);
        accountGateway = mock(StoreAccountGateway.class);
        bindings = mock(CloudAccountBindingRepository.class);
        when(bindings.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        center = new AccountCenterService(accounts, authGateway, accountGateway, bindings);
    }

    private void signedIn() {
        when(accounts.accessToken()).thenReturn("token-1");
    }

    private CloudAccountBindingEntity binding() {
        CloudAccountBindingEntity entity = new CloudAccountBindingEntity();
        entity.setId(CloudAccountBindingEntity.SINGLETON_ID);
        entity.setStoreUserId("user-1");
        entity.setEmail("old@example.com");
        entity.setDisplayName("Old Name");
        entity.setRoles("USER");
        entity.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return entity;
    }

    @Test
    void storeProfile_withoutCloudAccountAnswers401() {
        when(accounts.accessToken()).thenReturn(null);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> center.storeProfile());
        assertEquals(401, e.getStatusCode().value());
        verifyNoInteractions(authGateway, accountGateway);
    }

    @Test
    void storeProfile_ridesTheAccessToken() {
        signedIn();
        when(authGateway.me("token-1")).thenReturn(
                new StoreProfile("user-1", "dev@example.com", "Dev", List.of("USER"), 2,
                        "2025-05-01T00:00:00Z"));

        StoreProfile profile = center.storeProfile();

        assertEquals(2, profile.beeLevel());
        assertEquals("Dev", profile.displayName());
        verify(authGateway).me("token-1");
    }

    @Test
    void updateProfile_validatesBeforeAnyOutboundCall() {
        signedIn();
        assertThrows(IllegalArgumentException.class,
                () -> center.updateProfile(new UpdateProfileRequest("  ")));
        assertThrows(IllegalArgumentException.class,
                () -> center.updateProfile(new UpdateProfileRequest("x".repeat(65))));
        verifyNoInteractions(accountGateway);
    }

    @Test
    void updateProfile_renamesOnTheStoreAndSyncsTheLocalBinding() {
        signedIn();
        CloudAccountBindingEntity entity = binding();
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(entity));
        when(accountGateway.updateDisplayName("token-1", "New Name")).thenReturn(
                new StoreProfile("user-1", "dev@example.com", "New Name",
                        List.of("USER", "PUBLISHER"), 2, null));

        StoreProfile profile = center.updateProfile(new UpdateProfileRequest(" New Name "));

        assertEquals("New Name", profile.displayName());
        assertEquals("New Name", entity.getDisplayName());
        assertEquals("dev@example.com", entity.getEmail());
        assertEquals("USER,PUBLISHER", entity.getRoles());
        verify(bindings).saveAndFlush(entity);
    }

    @Test
    void updateProfile_leavesTheBindingAloneWhenSignedOutOnTheStoreSide() {
        signedIn();
        when(bindings.findById(CloudAccountBindingEntity.SINGLETON_ID))
                .thenReturn(Optional.empty());
        when(accountGateway.updateDisplayName(anyString(), anyString())).thenThrow(
                new IllegalStateException("Store profile update failed: HTTP 401"));

        assertThrows(IllegalStateException.class,
                () -> center.updateProfile(new UpdateProfileRequest("New Name")));
        verify(bindings, never()).saveAndFlush(any());
    }

    @Test
    void changePassword_validatesLengthsBeforeAnyOutboundCall() {
        signedIn();
        assertThrows(IllegalArgumentException.class,
                () -> center.changePassword(new PasswordChangeRequest("", "long-enough")));
        assertThrows(IllegalArgumentException.class,
                () -> center.changePassword(new PasswordChangeRequest("current", "short")));
        assertThrows(IllegalArgumentException.class,
                () -> center.changePassword(new PasswordChangeRequest("current", "x".repeat(129))));
        verifyNoInteractions(accountGateway);
    }

    @Test
    void changePassword_proxiesTheResult() {
        signedIn();
        when(accountGateway.changePassword("token-1", "current", "new-password"))
                .thenReturn(new PasswordResult(true, "Password updated"));

        PasswordResult result = center.changePassword(
                new PasswordChangeRequest("current", "new-password"));

        assertEquals(true, result.succeeded());
    }

    @Test
    void revokeEndpoints_passTheTokenAndId() {
        signedIn();
        center.revokeSession("session-1");
        verify(accountGateway).revokeSession("token-1", "session-1");
        center.revokeDevice("device-1");
        verify(accountGateway).revokeDevice("token-1", "device-1");
    }

    @Test
    void libraryAndOrganizations_rideTheAccessToken() {
        signedIn();
        center.library();
        verify(accountGateway).library("token-1");
        center.organizations();
        verify(accountGateway).organizations("token-1");
        center.sessions();
        verify(accountGateway).sessions("token-1");
        center.devices();
        verify(accountGateway).devices("token-1");
    }
}
