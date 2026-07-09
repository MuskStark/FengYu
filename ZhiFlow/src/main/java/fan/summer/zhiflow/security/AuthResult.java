package fan.summer.zhiflow.security;

/** Authenticated user identity returned by {@link AuthProvider#authenticate}. */
public record AuthResult(long userId, String username, String authProvider) {}
