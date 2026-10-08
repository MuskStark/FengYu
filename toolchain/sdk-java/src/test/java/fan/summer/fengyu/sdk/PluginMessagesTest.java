package fan.summer.fengyu.sdk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginMessages} resolves bundle keys for the current {@link WorkerLocale}, interpolating
 * {@code MessageFormat} placeholders and falling back English → raw key so a worker never throws on
 * an untranslated code.
 */
class PluginMessagesTest {

    private final PluginMessages msgs =
            new PluginMessages("i18n.test-messages", getClass().getClassLoader());

    @AfterEach
    void clear() {
        WorkerLocale.clear();
    }

    @Test
    void resolvesEnglishByDefault() {
        WorkerLocale.set("en");
        assertEquals("Hello World", msgs.format("test.greeting", "World"));
        assertEquals("Welcome", msgs.get("test.noArgs"));
    }

    @Test
    void resolvesChineseWhenLocaleIsZh() {
        WorkerLocale.set("zh-CN");
        assertEquals("你好 世界", msgs.format("test.greeting", "世界"));
        assertEquals("欢迎", msgs.get("test.noArgs"));
    }

    @Test
    void chineseKeyMissingFallsBackToEnglish() {
        // test.englishOnly exists only in the English bundle; zh lookup must fall back to the English value.
        WorkerLocale.set("zh");
        assertEquals("English-only fallback target", msgs.get("test.englishOnly"));
    }

    @Test
    void unknownKeyReturnsTheRawKey() {
        WorkerLocale.set("en");
        assertEquals("test.does.not.exist", msgs.get("test.does.not.exist"));
        // An unknown key is returned raw and is NOT re-interpreted by MessageFormat.
        assertEquals("test.does.not.exist", msgs.format("test.does.not.exist", "ignored"));
    }

    @Test
    void malformedPatternReturnsRawPatternWithoutThrowing() {
        WorkerLocale.set("en");
        // test.broken has an unbalanced placeholder; format() must not throw — it returns the raw pattern.
        assertEquals("Invalid {0 placeholder", msgs.format("test.broken", "x"));
    }

    @Test
    void noArgsLookupSkipsInterpolation() {
        WorkerLocale.set("en");
        assertEquals("Static value", msgs.get("test.fixed"));
        // A pattern with placeholders but no args returns the raw pattern (no {0} substitution).
        assertTrue(msgs.get("test.greeting").contains("{0}"));
    }

    /**
     * Regression: a locale with NO bundle must be probed exactly once. A null computeIfAbsent
     * result is not stored, so every lookup used to re-run the MissingResourceException candidate
     * chain against the classloader.
     */
    @Test
    void missingBundleIsProbedOnceNotOnEveryLookup() {
        CountingLoader loader = new CountingLoader(getClass().getClassLoader());
        PluginMessages missing = new PluginMessages("i18n.no-such-bundle", loader);
        assertEquals("k", missing.get("k"));
        WorkerLocale.set("zh");
        try {
            assertEquals("k", missing.get("k"));
            int afterFirst = loader.probes;
            assertEquals("other", missing.get("other"));
            assertEquals("third", missing.get("third"));
            assertEquals(afterFirst, loader.probes,
                "a missing bundle must be cached as absent, not re-probed per lookup");
        } finally {
            WorkerLocale.set("en");
        }
    }

    /** Counts classloader probes for the (absent) bundle path; delegates everything to the parent. */
    private static final class CountingLoader extends ClassLoader {
        int probes;

        CountingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public java.net.URL getResource(String name) {
            if (name.startsWith("i18n/no-such-bundle")) probes++;
            return super.getResource(name);
        }
    }
}
