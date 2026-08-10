package fan.summer.fengyu.update;

/**
 * Result of checking GitHub Releases for a newer build of the main application.
 *
 * <p>This is the payload for {@code GET /api/updates/check}. {@code portableMode} tells the
 * frontend whether the running backend can self-update its own JAR (portable/{@code java -jar}
 * deployment) or whether the update is owned by the Electron shell (desktop deployment).
 * {@code downloadAssetUrl} is the {@code Infinia.jar} asset URL used by the portable
 * self-update path; it is {@code null} when the asset is absent from the latest release.
 */
public record UpdateInfo(
        String currentVersion,
        String latestVersion,
        boolean updateAvailable,
        String releaseUrl,
        String releaseName,
        String publishedAt,
        boolean prerelease,
        String releaseNotes,
        boolean portableMode,
        String downloadAssetUrl
) {}
