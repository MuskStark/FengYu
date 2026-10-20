package fan.summer.fengyu.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguagePromptAppenderTest {

    @Test
    void appendsTheLanguageRuleAsTheLastSection() {
        String out = LanguagePromptAppender.append("base\n\n## Workspace\nrules\n");

        assertTrue(out.startsWith("base\n\n## Workspace\nrules"), out);
        assertTrue(out.endsWith(LanguagePromptAppender.SECTION), out);
        assertEquals(out.indexOf("## Response language"), out.lastIndexOf("## Response language"),
                "the rule must appear exactly once");
    }

    @Test
    void ruleFollowsTheUsersLatestMessageAndSurvivesEnglishContext() {
        // Whitespace-folded: the text block hard-wraps, and the wording is what matters.
        String flat = LanguagePromptAppender.append("base").replaceAll("\\s+", " ");

        assertTrue(flat.contains("language of their most recent message"), flat);
        assertTrue(flat.contains("answer in Chinese when the user writes in Chinese"), flat);
        assertTrue(flat.contains("unless they explicitly ask for another language"), flat);
        assertTrue(flat.contains("attached project files, tool or subagent results, "
                + "and conversation summaries never changes that choice"), flat);
        assertTrue(flat.contains("Code, identifiers, paths, and commands stay as written"), flat);
    }

    @Test
    void handlesNullAndBlankBasePrompts() {
        assertEquals(LanguagePromptAppender.SECTION, LanguagePromptAppender.append(null));
        assertEquals(LanguagePromptAppender.SECTION, LanguagePromptAppender.append("   \n"));
    }
}
