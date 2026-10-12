package fan.summer.fengyu.ai.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExplicitSkillInvocationParserTest {

    private static final Skill SKILL = new Skill(
            "fengyu-plugin-dev", "Plugin dev", "guides plugin authors",
            "body", Skill.Source.BUILTIN);

    private SkillRegistry registryWith(Skill skill) {
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.find("fengyu-plugin-dev")).thenReturn(Optional.of(skill));
        when(registry.find("missing")).thenReturn(Optional.empty());
        when(registry.find("fengyu-features")).thenReturn(Optional.of(new Skill(
                "fengyu-features", "Features", "d", "b", Skill.Source.INSTALLED)));
        when(registry.isEnabled(SKILL)).thenReturn(true);
        when(registry.enabled()).thenReturn(List.of(SKILL));
        return registry;
    }

    @Test
    void parsesBareIdAndArgumentForms() {
        var result = ExplicitSkillInvocationParser.parse("/fengyu-plugin-dev", registryWith(SKILL));
        var invoked = assertInstanceOf(ExplicitSkillInvocationParser.Result.Invoked.class, result);
        assertEquals("fengyu-plugin-dev", invoked.skill().id());
        assertEquals("", invoked.arguments());

        result = ExplicitSkillInvocationParser.parse(
                "/fengyu-plugin-dev scaffold a worker\nwith two lines", registryWith(SKILL));
        invoked = assertInstanceOf(ExplicitSkillInvocationParser.Result.Invoked.class, result);
        assertEquals("scaffold a worker\nwith two lines", invoked.arguments());
    }

    @Test
    void foldsCaseAndTrimsSurroundingWhitespace() {
        var result = ExplicitSkillInvocationParser.parse(
                "  /FengYu-Plugin-Dev  args here ", registryWith(SKILL));
        var invoked = assertInstanceOf(ExplicitSkillInvocationParser.Result.Invoked.class, result);
        assertEquals("fengyu-plugin-dev", invoked.skill().id());
        assertEquals("args here", invoked.arguments());
    }

    @Test
    void slashAnywhereElseOrPathsAreNotCommands() {
        var registry = registryWith(SKILL);
        assertInstanceOf(ExplicitSkillInvocationParser.Result.NotACommand.class,
                ExplicitSkillInvocationParser.parse("go ahead and /fengyu-plugin-dev", registry));
        assertInstanceOf(ExplicitSkillInvocationParser.Result.NotACommand.class,
                ExplicitSkillInvocationParser.parse("/usr/bin/env", registry));
        assertInstanceOf(ExplicitSkillInvocationParser.Result.NotACommand.class,
                ExplicitSkillInvocationParser.parse("/a/b/c path", registry));
        assertInstanceOf(ExplicitSkillInvocationParser.Result.NotACommand.class,
                ExplicitSkillInvocationParser.parse("plain text", registry));
        assertInstanceOf(ExplicitSkillInvocationParser.Result.NotACommand.class,
                ExplicitSkillInvocationParser.parse("", registry));
        assertInstanceOf(ExplicitSkillInvocationParser.Result.NotACommand.class,
                ExplicitSkillInvocationParser.parse(null, registry));
    }

    @Test
    void unknownIdFallsThroughAsPlainMessage() {
        // A custom-command name (expanded client-side) or a typo names no skill: the turn
        // must proceed untouched, exactly as before the feature existed.
        assertInstanceOf(ExplicitSkillInvocationParser.Result.NotACommand.class,
                ExplicitSkillInvocationParser.parse("/deploy staging", registryWith(SKILL)));
    }

    @Test
    void discoveredButDisabledSkillReportsDisabled() {
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.find("fengyu-features")).thenReturn(Optional.of(new Skill(
                "fengyu-features", "Features", "d", "b", Skill.Source.INSTALLED)));
        when(registry.isEnabled(org.mockito.ArgumentMatchers.any(Skill.class))).thenReturn(false);

        var result = ExplicitSkillInvocationParser.parse("/fengyu-features", registry);
        var disabled = assertInstanceOf(ExplicitSkillInvocationParser.Result.Disabled.class, result);
        assertEquals("fengyu-features", disabled.id());
    }

    @Test
    void nullRegistryOrExceptionIsNotACommand() {
        assertInstanceOf(ExplicitSkillInvocationParser.Result.NotACommand.class,
                ExplicitSkillInvocationParser.parse("/fengyu-plugin-dev", null));
        SkillRegistry throwing = mock(SkillRegistry.class);
        when(throwing.find("fengyu-plugin-dev")).thenThrow(new RuntimeException("boom"));
        assertInstanceOf(ExplicitSkillInvocationParser.Result.NotACommand.class,
                ExplicitSkillInvocationParser.parse("/fengyu-plugin-dev", throwing));
    }
}
