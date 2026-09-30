package fan.summer.fengyu.account;

import java.util.List;

/**
 * Outbound OAuth 2.1 / identity calls to the Infinia Store platform. Kept as an
 * interface so {@link CloudAccountService} can be unit-tested against a fake.
 */
public interface StoreAuthGateway {

    record TokenGrant(String accessToken, long expiresInSeconds, String refreshToken) {}

    /**
     * The store-managed rotating credential of a desktop long-lived session
     * (public-client form): issued right after the code exchange, single-use
     * and rotated on every {@link #refresh(String)}.
     */
    record SessionCredential(String refreshToken, long refreshExpiresInSeconds) {}

    /**
     * The store's PublicUser view. {@code beeLevel} (0-4, the Infinia Level) and
     * {@code createdAt} power the desktop user center; both are optional in the
     * store contract, so they default to 0/null when absent.
     */
    record StoreProfile(String userId, String email, String displayName, List<String> roles,
            int beeLevel, String createdAt) {}

    /** Exchanges an authorization code (with PKCE verifier) for tokens. */
    TokenGrant exchange(String code, String codeVerifier, String redirectUri);

    /**
     * Obtains a fresh access token. Public-client form: the store's rotating
     * per-install credential (single use, rotation returned in the grant).
     * Confidential form (a configured client secret): the authorization
     * server's refresh-token grant.
     */
    TokenGrant refresh(String refreshToken);

    /**
     * Issues the session's rotating refresh credential for the public-client
     * form; a no-op for confidential pairings (the code exchange already
     * carried a refresh token).
     */
    SessionCredential issueSessionCredential(String accessToken);

    /** Best-effort token revocation on sign-out. */
    void revoke(String token);

    /** GET /api/v1/me with the given bearer token. */
    StoreProfile me(String accessToken);
}
