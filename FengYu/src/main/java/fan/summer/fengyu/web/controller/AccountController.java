package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.account.AccountCenterService;
import fan.summer.fengyu.account.AccountCenterService.PasswordChangeRequest;
import fan.summer.fengyu.account.AccountCenterService.UpdateProfileRequest;
import fan.summer.fengyu.account.CloudAccountService;
import fan.summer.fengyu.account.CloudAccountService.AccountView;
import fan.summer.fengyu.account.CloudAccountService.AttemptView;
import fan.summer.fengyu.account.CloudAccountService.SignInStarted;
import fan.summer.fengyu.account.StoreAccountGateway.Device;
import fan.summer.fengyu.account.StoreAccountGateway.Library;
import fan.summer.fengyu.account.StoreAccountGateway.Organization;
import fan.summer.fengyu.account.StoreAccountGateway.PasswordResult;
import fan.summer.fengyu.account.StoreAccountGateway.Session;
import fan.summer.fengyu.account.StoreAuthGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Local account endpoints (design §7.2): the SPA talks to the loopback host, the
 * host drives the OAuth 2.1 + PKCE browser flow against the store platform.
 * The /center-family endpoints below proxy the signed-in user's live store
 * data — the desktop mirror of the store's user center page.
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final CloudAccountService accounts;
    private final AccountCenterService center;

    public AccountController(CloudAccountService accounts, AccountCenterService center) {
        this.accounts = accounts;
        this.center = center;
    }

    @GetMapping("/me")
    public AccountView me() {
        return accounts.currentUser();
    }

    @PostMapping("/sign-in")
    public SignInStarted signIn() {
        return accounts.signIn();
    }

    @GetMapping("/sign-in/{attemptId}")
    public AttemptView signInStatus(@PathVariable String attemptId) {
        return accounts.attempt(attemptId);
    }

    @PostMapping("/sign-out")
    public AccountView signOut() {
        accounts.signOut();
        return accounts.currentUser();
    }

    /** Live store profile — carries the Infinia Level (beeLevel) and createdAt. */
    @GetMapping("/store-profile")
    public StoreAuthGateway.StoreProfile storeProfile() {
        return center.storeProfile();
    }

    @PutMapping("/profile")
    public StoreAuthGateway.StoreProfile updateProfile(@RequestBody UpdateProfileRequest body) {
        return center.updateProfile(body);
    }

    @PutMapping("/password")
    public PasswordResult changePassword(@RequestBody PasswordChangeRequest body) {
        return center.changePassword(body);
    }

    @GetMapping("/library")
    public Library library() {
        return center.library();
    }

    @GetMapping("/organizations")
    public List<Organization> organizations() {
        return center.organizations();
    }

    @GetMapping("/sessions")
    public List<Session> sessions() {
        return center.sessions();
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> revokeSession(@PathVariable String sessionId) {
        center.revokeSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/devices")
    public List<Device> devices() {
        return center.devices();
    }

    @DeleteMapping("/devices/{deviceId}")
    public ResponseEntity<Void> revokeDevice(@PathVariable String deviceId) {
        center.revokeDevice(deviceId);
        return ResponseEntity.noContent().build();
    }
}
