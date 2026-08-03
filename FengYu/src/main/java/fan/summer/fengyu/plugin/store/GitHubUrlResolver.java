package fan.summer.fengyu.plugin.store;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a marketplace catalog URL back to its git repo + ref, so Codex "local" sources
 * (whose path is relative to the repo the marketplace lives in) can be cloned.
 *
 * <p>Handles {@code raw.githubusercontent.com} and {@code github.com/.../blob/...}. Other hosts
 * return {@code null} (the source is then marked with last_error by the caller).
 *
 * @since 4.0.0
 */
public final class GitHubUrlResolver {
    private GitHubUrlResolver() {}

    // raw.githubusercontent.com/{owner}/{repo}/{ref}/{path...}
    private static final Pattern RAW = Pattern.compile(
        "^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/.*$");
    // github.com/{owner}/{repo}/blob/{ref}/{path...}
    private static final Pattern BLOB = Pattern.compile(
        "^https://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/.*$");

    public record Resolved(String repoUrl, String ref) {}

    public static Resolved resolve(String catalogUrl) {
        if (catalogUrl == null) return null;
        Matcher m = RAW.matcher(catalogUrl);
        if (m.matches()) return new Resolved("https://github.com/" + m.group(1) + "/" + m.group(2), m.group(3));
        m = BLOB.matcher(catalogUrl);
        if (m.matches()) return new Resolved("https://github.com/" + m.group(1) + "/" + m.group(2), m.group(3));
        return null;
    }
}
