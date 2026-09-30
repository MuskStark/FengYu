package fan.summer.fengyu.account;

import fan.summer.fengyu.account.StoreAccountGateway.Device;
import fan.summer.fengyu.account.StoreAccountGateway.Library;
import fan.summer.fengyu.account.StoreAccountGateway.Organization;
import fan.summer.fengyu.account.StoreAccountGateway.PasswordResult;
import fan.summer.fengyu.account.StoreAccountGateway.Session;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Live store-account data for the desktop user center — the mirror of the store
 * platform's account page: profile with the Infinia Level, library summary,
 * organizations, and the security surfaces (password, sessions, devices). Every
 * call rides the cloud account's access token ({@link CloudAccountService} owns
 * refresh) and answers 401 when no cloud account is bound, so the SPA can keep
 * its local-account view. Nothing is persisted locally except the binding sync
 * after a display-name change, keeping /api/account/me fast and DB-only.
 */
@Service
public class AccountCenterService {

    public record UpdateProfileRequest(String displayName) {}

    public record PasswordChangeRequest(String currentPassword, String newPassword) {}

    private final CloudAccountService accounts;
    private final StoreAuthGateway authGateway;
    private final StoreAccountGateway accountGateway;
    private final CloudAccountBindingRepository bindings;

    public AccountCenterService(CloudAccountService accounts, StoreAuthGateway authGateway,
            StoreAccountGateway accountGateway, CloudAccountBindingRepository bindings) {
        this.accounts = accounts;
        this.authGateway = authGateway;
        this.accountGateway = accountGateway;
        this.bindings = bindings;
    }

    /** GET-mirrors the store's /api/v1/me — adds the live beeLevel/createdAt. */
    public StoreAuthGateway.StoreProfile storeProfile() {
        return authGateway.me(requireToken());
    }

    /** Renames the store profile, then keeps the local binding in step. */
    public StoreAuthGateway.StoreProfile updateProfile(UpdateProfileRequest request) {
        String displayName = request == null || request.displayName() == null
                ? "" : request.displayName().trim();
        if (displayName.isEmpty() || displayName.length() > 64) {
            throw new IllegalArgumentException("displayName must be 1-64 characters");
        }
        StoreAuthGateway.StoreProfile profile =
                accountGateway.updateDisplayName(requireToken(), displayName);
        bindings.findById(CloudAccountBindingEntity.SINGLETON_ID).ifPresent(binding -> {
            binding.setDisplayName(profile.displayName());
            binding.setEmail(profile.email());
            binding.setRoles(String.join(",", profile.roles()));
            binding.setUpdatedAt(Instant.now());
            bindings.saveAndFlush(binding);
        });
        return profile;
    }

    public PasswordResult changePassword(PasswordChangeRequest request) {
        String current = request == null ? null : request.currentPassword();
        String next = request == null ? null : request.newPassword();
        if (current == null || current.isBlank()) {
            throw new IllegalArgumentException("currentPassword is required");
        }
        if (next == null || next.length() < 8 || next.length() > 128) {
            throw new IllegalArgumentException("newPassword must be 8-128 characters");
        }
        return accountGateway.changePassword(requireToken(), current, next);
    }

    public Library library() {
        return accountGateway.library(requireToken());
    }

    public List<Organization> organizations() {
        return accountGateway.organizations(requireToken());
    }

    public List<Session> sessions() {
        return accountGateway.sessions(requireToken());
    }

    public void revokeSession(String sessionId) {
        accountGateway.revokeSession(requireToken(), sessionId);
    }

    public List<Device> devices() {
        return accountGateway.devices(requireToken());
    }

    public void revokeDevice(String deviceId) {
        accountGateway.revokeDevice(requireToken(), deviceId);
    }

    private String requireToken() {
        String token = accounts.accessToken();
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "no cloud account is signed in");
        }
        return token;
    }
}
