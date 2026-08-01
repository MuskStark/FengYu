package fan.summer.fengyu.ai;

import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveFilesPromptAppenderTest {

    @Test
    void returnsBasePromptUnchangedWhenNoActiveFiles() {
        String out = ActiveFilesPromptAppender.append("base", List.of());
        assertTrue(out.equals("base"));
    }

    @Test
    void appendsFileSectionWhenActiveFilesPresent() {
        String out = ActiveFilesPromptAppender.append("base", List.of(
            new ActiveFileRef("fan.summer.excel", new FileRef("ref_3f2a", "report.xlsx", "file", "read", 123L))));
        assertTrue(out.startsWith("base"), out);
        assertTrue(out.contains("## Files available for this conversation"), out);
        assertTrue(out.contains("fan.summer.excel"), out);
        assertTrue(out.contains("\"id\":\"ref_3f2a\""), out);
        assertFalse(out.contains("model-magic"));
    }

    @Test
    void handlesNullBasePrompt() {
        String out = ActiveFilesPromptAppender.append(null, List.of(
            new ActiveFileRef("p", new FileRef("ref_1", "f", "file", "read", 1L))));
        assertTrue(out.contains("## Files available"), out);
    }
}
