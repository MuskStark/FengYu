package fan.summer.fengyu.store;

import java.util.List;

/**
 * DTOs mirroring the Infinia Store Platform REST contract (/api/v1, design §10).
 * Field names match the store's camelCase JSON; unknown fields are ignored.
 */
public final class StoreModels {

    private StoreModels() {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogPage(List<CatalogItem> items, String nextCursor) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogItem(
            String coordinate,
            String type,
            String namespace,
            String slug,
            String name,
            String summary,
            String category,
            String latestVersion,
            String channel,
            String publisherName,
            String updatedAt) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ListingDetail(
            String coordinate,
            String type,
            String namespace,
            String slug,
            String status,
            String category,
            String descriptionMarkdown,
            List<String> tags,
            String defaultChannel,
            String publisherName,
            long downloads,
            List<ListingRelease> releases) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ListingRelease(
            String releaseId,
            String version,
            String status,
            String channel,
            String publishedAt,
            String requiresHost,
            String changelogMarkdown,
            List<ArtifactRef> artifacts,
            List<DependencyRef> dependencies,
            List<PermissionRef> permissions) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ArtifactRef(
            String artifactId,
            String kind,
            String platform,
            String arch,
            String filename,
            long size,
            String sha256,
            String keyId) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record DependencyRef(String coordinate, String range, boolean optional) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record PermissionRef(String permissionId, String scope, boolean required, String reason) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolveResponse(
            boolean resolvable,
            String rootCoordinate,
            List<ResolutionItem> plan,
            List<MissingDependency> missing) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolutionItem(
            String coordinate,
            String releaseId,
            String version,
            String channel,
            String requiresHost,
            boolean alreadyInstalled,
            List<PermissionRef> permissions,
            List<ArtifactRef> artifacts) {}

    /** Compatibility shape for plans/callers that predate the per-item artifacts list (P2-16). */
    public static ResolutionItem resolutionItem(String coordinate, String releaseId, String version,
            String channel, String requiresHost, boolean alreadyInstalled,
            List<PermissionRef> permissions) {
        return new ResolutionItem(coordinate, releaseId, version, channel, requiresHost,
                alreadyInstalled, permissions, null);
    }

    /**
     * P2-17: one entry of the optional, batched install telemetry the host reports to
     * {@code POST /api/v1/install-events} (design §10.2 / ADR-009 — only sent with an
     * authenticated Bearer session, never blocking the install itself).
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record InstallEvent(
            String idempotencyKey,
            String coordinate,
            String version,
            String type,
            String action,
            String outcome,
            String hostVersion,
            String os,
            String arch,
            String occurredAt) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record MissingDependency(String coordinate, String range, String reason) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record DownloadTicket(
            String releaseId,
            String url,
            String expiresAt,
            String sha256,
            String signature,
            String keyId,
            long size) {}
}
