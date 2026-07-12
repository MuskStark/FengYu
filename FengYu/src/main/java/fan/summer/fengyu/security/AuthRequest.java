package fan.summer.fengyu.security;

/** Authentication request payload. Groundwork — not used until login is implemented. */
public record AuthRequest(String username, String password, String oauthToken, String provider) {}
