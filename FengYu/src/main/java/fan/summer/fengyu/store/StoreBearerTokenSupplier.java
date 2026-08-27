package fan.summer.fengyu.store;

/**
 * Supplies a bearer token for authenticated outbound store calls, or null for
 * anonymous access. Implemented by the cloud account service when signed in.
 */
public interface StoreBearerTokenSupplier {

    String accessToken();
}
