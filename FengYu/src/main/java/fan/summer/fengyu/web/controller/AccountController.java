package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.account.CloudAccountService;
import fan.summer.fengyu.account.CloudAccountService.AccountView;
import fan.summer.fengyu.account.CloudAccountService.AttemptView;
import fan.summer.fengyu.account.CloudAccountService.SignInStarted;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local account endpoints (design §7.2): the SPA talks to the loopback host, the
 * host drives the OAuth 2.1 + PKCE browser flow against the store platform.
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final CloudAccountService accounts;

    public AccountController(CloudAccountService accounts) {
        this.accounts = accounts;
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
}
