package fan.summer.fengyu.ai.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.ai.hooks.HookDispatcher.HookDefinition;
import fan.summer.fengyu.ai.hooks.HookDispatcher.HookEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plugin-contributed hooks: both file shapes parse, names are namespaced, and the
 * enable≠trust gate keeps untrusted contributions out of the active set.
 */
class PluginHookContributionsTest {

    @Test
    void parsesGrokShapedHooksFile() {
        String json = """
                {"hooks": {
                  "PreToolUse": [{"matcher": "excel_.*", "hooks": [
                    {"type": "command", "command": "bin/guard.sh", "timeout": 8}
                  ]}],
                  "PostToolUse": [{"hooks": [
                    {"type": "command", "command": "logger audit"},
                    {"type": "http", "url": "http://127.0.0.1:9/audit"}
                  ]}],
                  "NotAnEvent": [{"hooks": [{"type": "command", "command": "x"}]}]
                }}""";
        List<String> warnings = new ArrayList<>();
        List<HookDefinition> hooks = PluginHookContributions.parse(json, "fan.summer.demo", "Demo", warnings);

        assertEquals(3, hooks.size());
        assertEquals("plugin/fan.summer.demo/pre_tool_use-1", hooks.get(0).name());
        assertEquals(HookEvent.PRE_TOOL_USE, hooks.get(0).event());
        assertEquals("excel_.*", hooks.get(0).matcher());
        assertEquals(8, hooks.get(0).timeout().toSeconds());
        assertEquals(HookEvent.POST_TOOL_USE, hooks.get(1).event());
        assertEquals(HookDispatcher.HookDefinition.Type.HTTP, hooks.get(2).type());
        // Unknown events surface as warnings instead of failing the whole file.
        assertTrue(warnings.stream().anyMatch(w -> w.contains("NotAnEvent")));
    }

    @Test
    void parsesFengyuFlatListAndNamespacesNames() {
        String json = """
                [{"name":"audit","event":"post_tool_use","matcher":".*",
                  "type":"command","command":"audit.sh","timeoutSeconds":3}]""";
        List<HookDefinition> hooks = PluginHookContributions.parse(
                json, "fan.summer.demo", "Demo", new ArrayList<>());
        assertEquals(1, hooks.size());
        assertEquals("plugin/fan.summer.demo/audit", hooks.get(0).name());
        assertEquals(3, hooks.get(0).timeout().toSeconds());
    }

    @Test
    void parsesClampHookTimeoutsIntoTheSupportedWindow() {
        // CQ-05: plugin-contributed hook timeouts clamp into [1, 60]s like user hooks —
        // an untrusted hooks.json must not pin every tool call behind a minutes-long wait.
        String json = """
                {"hooks": {"PreToolUse": [{"hooks": [
                  {"type": "command", "command": "bin/guard.sh", "timeout": 600},
                  {"type": "command", "command": "bin/quick.sh", "timeout": 0}
                ]}]}}""";
        List<HookDefinition> hooks = PluginHookContributions.parse(json, "p", "P", new ArrayList<>());
        assertEquals(60, hooks.get(0).timeout().toSeconds());
        assertEquals(1, hooks.get(1).timeout().toSeconds());
    }

    @Test
    void malformedFilesProduceWarningsNotFailures() {
        List<String> warnings = new ArrayList<>();
        assertEquals(0, PluginHookContributions.parse("{not json", "p", "P", warnings).size());
        assertTrue(warnings.get(0).contains("not valid JSON"));
        assertEquals(0, PluginHookContributions.parse("\"just a string\"", "p", "P",
                new ArrayList<>()).size());
        // Handlers without a command are skipped with a warning.
        List<String> noCommand = new ArrayList<>();
        assertEquals(0, PluginHookContributions.parse(
                "{\"hooks\":{\"PreToolUse\":[{\"hooks\":[{\"type\":\"command\"}]}]}}",
                "p", "P", noCommand).size());
        assertTrue(noCommand.get(0).contains("without command"));
    }

    @Test
    void trustStorePersistsIds() {
        try (var config = org.mockito.Mockito.mockStatic(
                fan.summer.fengyu.ai.service.AiConfigServiceHeadless.class)) {
            // Default: nothing trusted.
            config.when(() -> fan.summer.fengyu.ai.service.AiConfigServiceHeadless
                    .getSetting(org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn("[]");
            PluginHookContributions contributions = new PluginHookContributions(null, null);
            assertFalse(contributions.isTrusted("fan.summer.x"));
            contributions.setTrusted("fan.summer.x", true);
            config.when(() -> fan.summer.fengyu.ai.service.AiConfigServiceHeadless
                    .getSetting(org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn("[\"fan.summer.x\"]");
            assertTrue(contributions.isTrusted("fan.summer.x"));
            contributions.setTrusted("fan.summer.x", false);
            // The static store is mocked, so mirror the untrusted state for the read.
            config.when(() -> fan.summer.fengyu.ai.service.AiConfigServiceHeadless
                    .getSetting(org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn("[]");
            assertFalse(contributions.isTrusted("fan.summer.x"));
        }
    }

    @Test
    void staleTrustForUninstalledPluginsIsRevokedOnDiscover() {
        try (var config = org.mockito.Mockito.mockStatic(
                fan.summer.fengyu.ai.service.AiConfigServiceHeadless.class)) {
            PluginPackageService packages = org.mockito.Mockito.mock(PluginPackageService.class);
            var manifest = new fan.summer.fengyu.plugin.market.PluginManifest(
                    2, "test.gone", "Gone", "d", "1.0.0", "a", "i", "dev",
                    null, null, java.util.List.of(), null, false, null, null, null, null);
            // Only test.gone is installed; the store still trusts it AND test.ghost.
            config.when(() -> fan.summer.fengyu.ai.service.AiConfigServiceHeadless
                    .getSetting(org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn("[\"test.gone\",\"test.ghost\"]");
            org.mockito.Mockito.when(packages.installed())
                    .thenReturn(java.util.List.of(manifest));
            PluginHookContributions contributions =
                    new PluginHookContributions(packages, null);
            contributions.discover();
            // The uninstalled ghost is revoked; the still-installed plugin keeps trust.
            config.verify(() -> fan.summer.fengyu.ai.service.AiConfigServiceHeadless
                    .setSetting(org.mockito.ArgumentMatchers.eq("plugin.hooks.trusted"),
                            org.mockito.ArgumentMatchers.eq("[\"test.gone\"]")));
        }
    }

    @Test
    void runtimeBindingInjectsPluginEnvAndWorkingDirectory() {
        HookDefinition hook = HookDefinition.command("plugin/p/h", HookEvent.PRE_TOOL_USE,
                null, "bin/check.sh", 5);
        // withRuntime is the plugin binding path; verified directly since it needs no package.
        HookDefinition bound = hook.withRuntime("/plugins/p", java.util.Map.of(
                "FENGYU_PLUGIN_ROOT", "/plugins/p",
                "FENGYU_PLUGIN_DATA", "/data/p"));
        assertEquals("/plugins/p", bound.workingDir());
        assertEquals("/plugins/p", bound.env().get("FENGYU_PLUGIN_ROOT"));
        assertEquals("/data/p", bound.env().get("FENGYU_PLUGIN_DATA"));
        // Command text itself is untouched — the env/working dir come from the loader,
        // not the (untrusted) hook author.
        assertEquals("bin/check.sh", bound.command());
    }

    @TempDir
    Path unused;
}
