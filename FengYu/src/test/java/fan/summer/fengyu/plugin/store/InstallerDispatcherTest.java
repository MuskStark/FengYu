package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InstallerDispatcherTest {

    @TempDir Path temp;

    @Test
    void routesFengyuToPackageService() {
        // A spy/stub: track that installFromUrl is called for the FENGYU entry.
        var pkg = new PluginPackageService(temp.toString()); // real, but URL is unreachable — we only assert routing throws the right type
        // Use a fake AgentContentInstaller that records calls.
        CapturingAgentInstaller agent = new CapturingAgentInstaller();

        InstallerDispatcher d = new InstallerDispatcher(pkg, agent);
        UnifiedCatalogEntry fyp = new UnifiedCatalogEntry(
            "fengyu-default:FENGYU:x", "fengyu-default", StoreSourceType.FENGYU,
            "x", "x", "d", null, null, List.of(), null, null,
            new UnifiedCatalogEntry.ZipUrlSource("https://example.com/x.fyp"),
            List.of(), List.of(), null, false, null, false, false);

        // For FENGYU, the dispatcher must call the package service (which will try to fetch the URL).
        // We assert that the agent installer is NOT invoked, and the package service path is taken.
        assertThrows(Exception.class, () -> d.install(fyp)); // URL unreachable in test
        assertFalse(agent.invoked, "FENGYU must NOT go through AgentContentInstaller");
    }

    @Test
    void routesClaudeToAgentInstaller() {
        var pkg = new PluginPackageService(temp.toString());
        CapturingAgentInstaller agent = new CapturingAgentInstaller();
        InstallerDispatcher d = new InstallerDispatcher(pkg, agent);

        UnifiedCatalogEntry cl = new UnifiedCatalogEntry(
            "test:CLAUDE:y", "test", StoreSourceType.CLAUDE,
            "y", "y", "d", null, null, List.of(), null, "sha",
            new UnifiedCatalogEntry.GitUrlSource("file:///tmp/x", "sha"),
            List.of(), List.of(), null, false, null, false, false);

        d.install(cl);
        assertTrue(agent.invoked, "CLAUDE must go through AgentContentInstaller");
        assertEquals("test:CLAUDE:y", agent.lastUid);
    }

    /** Minimal AgentContentInstaller stand-in that records invocations. */
    static class CapturingAgentInstaller extends AgentContentInstaller {
        boolean invoked;
        String lastUid;
        CapturingAgentInstaller() { super(null, Path.of(System.getProperty("java.io.tmpdir")), 10); }
        @Override public void install(UnifiedCatalogEntry e) { invoked = true; lastUid = e.uid(); }
        @Override public void uninstall(String uid) { invoked = true; lastUid = uid; }
    }
}
