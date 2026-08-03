package fan.summer.fengyu.plugin.store;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GitHubUrlResolverTest {

    @Test
    void resolvesRawGithubUsercontentUrl() {
        var r = GitHubUrlResolver.resolve(
            "https://raw.githubusercontent.com/o/r/main/.agents/plugins/marketplace.json");
        assertEquals("https://github.com/o/r", r.repoUrl());
        assertEquals("main", r.ref());
    }

    @Test
    void resolvesGithubBlobUrl() {
        var r = GitHubUrlResolver.resolve(
            "https://github.com/o/r/blob/v1.0/.agents/plugins/marketplace.json");
        assertEquals("https://github.com/o/r", r.repoUrl());
        assertEquals("v1.0", r.ref());
    }

    @Test
    void returnsNullForNonGithubHost() {
        assertNull(GitHubUrlResolver.resolve("https://gitlab.com/o/r/main/m.json"));
    }
}
