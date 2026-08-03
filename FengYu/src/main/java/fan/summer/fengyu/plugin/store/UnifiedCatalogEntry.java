package fan.summer.fengyu.plugin.store;

import java.util.List;

/**
 * One entry in the unified catalog — the union of FengYu, Claude Code, and OpenAI Codex
 * marketplace entry shapes. Fields that don't apply to a given source type are null/empty.
 *
 * @param uid             globally-unique id = origin:sourceType:pluginName
 * @param origin          source identifier (uid prefix)
 * @param sourceType      which ecosystem this entry came from
 * @param name            plugin identifier within its source (kebab-case)
 * @param displayName     Codex interface.displayName; equals name for other sources
 * @param description     one-line summary
 * @param author          author metadata (name/email/url); null if absent
 * @param category        raw category string from the source (frontend normalizes)
 * @param keywords        discovery keywords (Claude/Codex); empty for FengYu
 * @param homepage        project URL
 * @param pinnedSha       git commit sha declared by the source (Claude); null otherwise
 * @param sourceRef       normalized install-source descriptor (sealed union)
 * @param declaredSkills  skill names/paths; populated AFTER install (empty in catalog list)
 * @param mcpServers      mcp server names; populated AFTER install (empty in catalog list)
 * @param interfaceMeta   Codex UX metadata (screenshots/logo/brandColor); null otherwise
 * @param installed       true if installed locally (merged by UnifiedStoreService)
 * @param installedVersion installed version; null if not installed
 * @param updateAvailable true if a newer version is available remotely
 * @param enabled         true if installed and enabled
 */
public record UnifiedCatalogEntry(
        String uid,
        String origin,
        StoreSourceType sourceType,
        String name,
        String displayName,
        String description,
        Author author,
        String category,
        List<String> keywords,
        String homepage,
        String pinnedSha,
        SourceRef sourceRef,
        List<String> declaredSkills,
        List<String> mcpServers,
        InterfaceMeta interfaceMeta,
        boolean installed,
        String installedVersion,
        boolean updateAvailable,
        boolean enabled) {

    /** Author metadata. All fields optional except name. */
    public record Author(String name, String email, String url) {}

    /** Sealed union of normalized install-source descriptors. */
    public sealed interface SourceRef
            permits ZipUrlSource, GitUrlSource, GitSubdirSource, GitLocalInRepoSource {}

    /** FengYu .fyp direct download. */
    public record ZipUrlSource(String url) implements SourceRef {}

    /** Claude url source — whole-repo git clone at a pinned sha. */
    public record GitUrlSource(String url, String sha) implements SourceRef {}

    /** Claude git-subdir source — clone whole repo, take a subdirectory. */
    public record GitSubdirSource(String url, String path, String ref, String sha) implements SourceRef {}

    /** Codex local source — marketplace lives in a repo; plugin is a path inside it. */
    public record GitLocalInRepoSource(String repoUrl, String ref, String path) implements SourceRef {}

    /** Codex interface UX metadata. All fields nullable; lists empty when absent. */
    public record InterfaceMeta(
            String displayName,
            String shortDescription,
            String longDescription,
            String developerName,
            String category,
            List<String> capabilities,
            String websiteURL,
            String privacyPolicyURL,
            String termsOfServiceURL,
            List<String> defaultPrompt,
            String brandColor,
            String composerIcon,
            String logo,
            String logoDark,
            List<String> screenshots) {}
}
