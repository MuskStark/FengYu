package fan.summer.fengyu.account;

import java.util.List;

/**
 * Outbound OAuth 2.1 / identity calls to the Infinia Store platform. Kept as an
 * interface so {@link CloudAccountService} can be unit-tested against a fake.
 */
public interface StoreAuthGateway {

    record TokenGrant(String accessToken, long expiresInSeconds, String refreshToken) {}

    record StoreProfile(String userId, String email, String displayName, List<String> roles) {}

    /** Exchanges an authorization code (with PKCE verifier) for tokens. */
    TokenGrant exchange(String code, String codeVerifier, String redirectUri);

    /** Uses the refresh-token grant to obtain a fresh access token. */
    TokenGrant refresh(String refreshToken);

    /** Best-effort token revocation (RFC 7009) on sign-out. */
    void revoke(String token);

    /** GET /api/v1/me with the given bearer token. */
    StoreProfile me(String accessToken);
}
