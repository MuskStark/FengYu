package fan.summer.fengyu.plugin.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the path-traversal guards that protect the agent-content installer. These lock
 * down the contract that a plugin name arriving from untrusted third-party marketplace JSON can
 * never become a filesystem path segment that escapes the runtime tree.
 */
class PluginContentPathSafetyTest {

    @Test
    void slugifyCollapsesTraversalAndUnsafeCharsToSingleSafeSegment() {
        assertEquals("sibling", PluginContentPathSafety.slugify("../../sibling"));
        assertEquals("a-b", PluginContentPathSafety.slugify("a/b"));
        // backslash + leading dots all get collapsed/stripped — only the trailing "a" survives
        assertEquals("a", PluginContentPathSafety.slugify("..\\..\\a"));
        assertEquals("plugin", PluginContentPathSafety.slugify("../../../.."));
        assertEquals("plugin", PluginContentPathSafety.slugify("////"));
    }

    @Test
    void slugifyKeepsSafeCharactersUntouched() {
        assertEquals("my-cool.plugin_2", PluginContentPathSafety.slugify("My-Cool.Plugin_2"));
        assertEquals("fan.summer.markdown", PluginContentPathSafety.slugify("fan.summer.markdown"));
    }

    @Test
    void slugifyHandlesNullAndBlank() {
        assertEquals("plugin", PluginContentPathSafety.slugify(null));
        assertEquals("plugin", PluginContentPathSafety.slugify(""));
        assertEquals("plugin", PluginContentPathSafety.slugify("   "));
    }

    @Test
    void slugifyAlwaysProducesASafeSegment() {
        // Any result of slugify must itself pass isSafeSegment — the guarantee the installer relies on.
        String[] inputs = {"../../etc", "a:b", "name with spaces", "ok.name", "", null, "..", "C:\\\\Windows"};
        for (String in : inputs) {
            assertTrue(PluginContentPathSafety.isSafeSegment(PluginContentPathSafety.slugify(in)),
                "slugify(\"" + in + "\") = " + PluginContentPathSafety.slugify(in) + " must be a safe segment");
        }
    }

    @Test
    void isSafeSegmentRejectsTraversalAndSeparators() {
        assertFalse(PluginContentPathSafety.isSafeSegment(".."));
        assertFalse(PluginContentPathSafety.isSafeSegment("."));
        assertFalse(PluginContentPathSafety.isSafeSegment(""));
        assertFalse(PluginContentPathSafety.isSafeSegment(null));
        assertFalse(PluginContentPathSafety.isSafeSegment("a/b"));
        assertFalse(PluginContentPathSafety.isSafeSegment("a\\b"));
        assertFalse(PluginContentPathSafety.isSafeSegment("a:b"));
        assertFalse(PluginContentPathSafety.isSafeSegment("a b"));
    }

    @Test
    void isSafeSegmentAcceptsCleanIds() {
        assertTrue(PluginContentPathSafety.isSafeSegment("demo"));
        assertTrue(PluginContentPathSafety.isSafeSegment("fan.summer.markdown"));
        assertTrue(PluginContentPathSafety.isSafeSegment("plugin_2"));
        assertTrue(PluginContentPathSafety.isSafeSegment("my-tool"));
    }
}
