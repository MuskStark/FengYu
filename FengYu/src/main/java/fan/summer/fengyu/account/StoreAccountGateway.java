package fan.summer.fengyu.account;

import java.util.List;

/**
 * Outbound account-resource calls against the Infinia Store platform — the
 * /api/v1/me and /api/v1/organization surfaces behind the desktop user center
 * (library summary, organizations, sessions, devices, profile and password
 * management). Kept as an interface so the service layer can be unit-tested
 * against a fake, mirroring {@link StoreAuthGateway}.
 */
public interface StoreAccountGateway {

    record Library(List<Favorite> favorites, List<Entitlement> entitlements,
            List<InstallEvent> installHistory) {}

    record Favorite(String listingCoordinate, String name, String addedAt) {}

    record Entitlement(String listingCoordinate, boolean free, String acquiredAt) {}

    record InstallEvent(String coordinate, String version, String action,
            String outcome, String occurredAt) {}

    record Session(String sessionId, String clientId, String kind, String createdAt) {}

    record Device(String deviceId, String name, String platform, boolean revoked) {}

    record Organization(String organizationId, String slug, String name) {}

    record PasswordResult(boolean succeeded, String message) {}

    /** PUT /api/v1/me — display-name update; responds with the refreshed profile. */
    StoreAuthGateway.StoreProfile updateDisplayName(String accessToken, String displayName);

    /** PUT /api/v1/me/password — credential rotation with the current password as proof. */
    PasswordResult changePassword(String accessToken, String currentPassword,
            String newPassword);

    /** GET /api/v1/me/library — favorites, entitlements and install telemetry. */
    Library library(String accessToken);

    /** GET /api/v1/me/sessions — active authorization grants. */
    List<Session> sessions(String accessToken);

    /** DELETE /api/v1/me/sessions/{id} — revokes one session (the store answers 204). */
    void revokeSession(String accessToken, String sessionId);

    /** GET /api/v1/me/devices — registered devices with their revocation state. */
    List<Device> devices(String accessToken);

    /** DELETE /api/v1/me/devices/{id} — revokes one device. */
    void revokeDevice(String accessToken, String deviceId);

    /** GET /api/v1/organizations — organizations the user belongs to. */
    List<Organization> organizations(String accessToken);
}
