package fan.summer.fengyu.ai.mention;

import fan.summer.fengyu.ai.skill.ExplicitSkillInvocationParser;
import fan.summer.fengyu.ai.skill.Skill;
import fan.summer.fengyu.ai.skill.SkillRegistry;
import fan.summer.fengyu.ai.workflow.WorkflowDefinition;
import fan.summer.fengyu.ai.workflow.WorkflowService;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExplicitMentionParserTest {

    private static final Skill SKILL = new Skill(
            "fengyu-plugin-dev", "Plugin dev", "guides authors",
            "## Guidance\nBuild it.", Skill.Source.BUILTIN);

    // ── raw grammar ───────────────────────────────────────────────────────────────────

    @Test
    void parsesAllThreeSchemesAnywhereInTheMessage() {
        String message = "help me with [@Excel](plugin://excel-tools) and [@Review](skill://Fengyu-Plugin-Dev)"
                + " then [@ETL](flow://nightly-etl), plus a file [note](./a.md) link";

        List<ExplicitMentionParser.Raw> raw = ExplicitMentionParser.parse(message);

        assertEquals(List.of(
                new ExplicitMentionParser.Raw(ExplicitMentionParser.Scheme.PLUGIN, "excel-tools"),
                new ExplicitMentionParser.Raw(ExplicitMentionParser.Scheme.SKILL, "fengyu-plugin-dev"),
                new ExplicitMentionParser.Raw(ExplicitMentionParser.Scheme.FLOW, "nightly-etl")), raw,
                "skill ids fold to lowercase like the / grammar; other schemes stay exact");
    }

    @Test
    void deduplicatesRepeatedLinksAndIgnoresForeignShapes() {
        assertEquals(List.of(new ExplicitMentionParser.Raw(ExplicitMentionParser.Scheme.PLUGIN, "p")),
                ExplicitMentionParser.parse("[@p](plugin://p) [@p again](plugin://p)"));
        assertEquals(List.of(), ExplicitMentionParser.parse("[@x](https://example.com)"));
        assertEquals(List.of(), ExplicitMentionParser.parse("[@x](mcp://y)"));
        assertEquals(List.of(), ExplicitMentionParser.parse("plain @plugin text"));
        assertEquals(List.of(), ExplicitMentionParser.parse((String) null));
        // Empty destination (composer never emits it; hand-typing defense).
        assertEquals(List.of(), ExplicitMentionParser.parse("[@x](skill://)"));
    }

    @Test
    void unescapesComposerEscapedDestinations() {
        List<ExplicitMentionParser.Raw> raw = ExplicitMentionParser.parse(
                "[@Weird](plugin://weird\\_id)");
        assertEquals("weird_id", raw.getFirst().id());
    }

    // ── resolution ────────────────────────────────────────────────────────────────────

    @Test
    void resolvesSkillsPluginsAndFlowsInFirstAppearanceOrder() {
        SkillRegistry skills = mock(SkillRegistry.class);
        when(skills.find("fengyu-plugin-dev")).thenReturn(Optional.of(SKILL));
        when(skills.isEnabled(SKILL)).thenReturn(true);
        PluginPackageService packages = mock(PluginPackageService.class);
        when(packages.installed()).thenReturn(List.of(manifest("excel-tools", "Excel", "sheet_run")));
        when(packages.isEnabled("excel-tools")).thenReturn(true);
        WorkflowService workflows = mock(WorkflowService.class);
        when(workflows.list()).thenReturn(List.of(flow("nightly-etl", "Nightly ETL", true)));

        ExplicitMentionParser.Resolved resolved = ExplicitMentionParser.resolve(
                "use [@Excel](plugin://excel-tools), [@Review](skill://fengyu-plugin-dev),"
                        + " [@ETL](flow://nightly-etl)",
                skills, packages, workflows);

        assertEquals(1, resolved.skills().size());
        assertTrue(resolved.skills().getFirst()
                instanceof ExplicitSkillInvocationParser.Result.Invoked);
        assertEquals(List.of("excel-tools"), resolved.plugins().stream()
                .map(ExplicitMentionParser.PluginMention::id).toList());
        assertEquals(List.of("sheet_run"), resolved.plugins().getFirst().toolNames());
        assertEquals(List.of("nightly-etl"), resolved.flows().stream()
                .map(ExplicitMentionParser.FlowMention::id).toList());
        assertEquals("run_workflow_nightly_etl", resolved.flows().getFirst().toolName());
        assertEquals(List.of("sheet_run", "run_workflow_nightly_etl"), resolved.forcedToolNames());
    }

    @Test
    void disabledSkillAndMissingPluginAndUnpublishedFlowProduceVerdicts() {
        Skill disabled = new Skill("off", "Off", "d", "b", Skill.Source.BUILTIN);
        SkillRegistry skills = mock(SkillRegistry.class);
        when(skills.find("off")).thenReturn(Optional.of(disabled));
        when(skills.isEnabled(disabled)).thenReturn(false);
        PluginPackageService packages = mock(PluginPackageService.class);
        when(packages.installed()).thenReturn(List.of(manifest("installed-off", "Off plugin", "tool_a")));
        when(packages.isEnabled("installed-off")).thenReturn(false);
        WorkflowService workflows = mock(WorkflowService.class);
        when(workflows.list()).thenReturn(List.of(flow("draft-flow", "Draft", false)));

        ExplicitMentionParser.Resolved resolved = ExplicitMentionParser.resolve(
                "[@x](skill://off) [@y](plugin://installed-off) [@z](plugin://ghost)"
                        + " [@w](flow://draft-flow) [@v](flow://ghost-flow) [@u](skill://ghost)",
                skills, packages, workflows);

        assertTrue(resolved.skills().getFirst() instanceof ExplicitSkillInvocationParser.Result.Disabled);
        ExplicitMentionParser.PluginMention installed = resolved.plugins().stream()
                .filter(p -> p.id().equals("installed-off")).findFirst().orElseThrow();
        assertTrue(installed.installed() && !installed.enabled());
        ExplicitMentionParser.PluginMention ghost = resolved.plugins().stream()
                .filter(p -> p.id().equals("ghost")).findFirst().orElseThrow();
        assertTrue(!ghost.installed());
        assertTrue(resolved.flows().getFirst().known() && !resolved.flows().getFirst().published());
        assertTrue(!resolved.flows().get(1).known());
        // Unknown skill ids drop out entirely (the / grammar's backward-compat rule) and
        // unavailable targets contribute no forced tools.
        assertEquals(List.of(), resolved.forcedToolNames());
    }

    @Test
    void nullServicesAndThrowingRegistriesDegradeToPlainText() {
        assertEquals(ExplicitMentionParser.Resolved.EMPTY, ExplicitMentionParser.resolve(
                "[@x](plugin://x) [@y](flow://y) [@z](skill://z)", null, null, null));

        SkillRegistry throwing = mock(SkillRegistry.class);
        when(throwing.find("z")).thenThrow(new RuntimeException("boom"));
        PluginPackageService throwingPackages = mock(PluginPackageService.class);
        when(throwingPackages.installed()).thenThrow(new RuntimeException("boom"));
        WorkflowService throwingWorkflows = mock(WorkflowService.class);
        when(throwingWorkflows.list()).thenThrow(new RuntimeException("boom"));

        assertEquals(ExplicitMentionParser.Resolved.EMPTY, ExplicitMentionParser.resolve(
                "[@x](plugin://x) [@y](flow://y) [@z](skill://z)",
                throwing, throwingPackages, throwingWorkflows));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────

    private static PluginManifest manifest(String id, String name, String... tools) {
        List<PluginManifest.AiTool> aiTools = java.util.Arrays.stream(tools)
                .map(tool -> new PluginManifest.AiTool(tool, "d", "m", "read"))
                .toList();
        return new PluginManifest(2, id, name, "d", "1.0.0", "a", null, null,
                null, null, List.of(), null, false, null, aiTools);
    }

    private static WorkflowDefinition flow(String id, String name, boolean published) {
        return new WorkflowDefinition(id, name, "d", null, null, null, null,
                published, 1, LocalDateTime.now(), LocalDateTime.now());
    }
}
