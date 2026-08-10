package fan.summer.fengyu.sdk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link WorkerLocale} binds the per-request locale for handler calls. The host injects a raw tag
 * via the {@code locale} params key; the SDK collapses it to the supported {@code en}/{@code zh}
 * code and defaults to {@code en} when absent (legacy hosts) so workers without localized bundles
 * keep their prior English behaviour.
 */
class WorkerLocaleTest {

    @AfterEach
    void clear() {
        WorkerLocale.clear();
    }

    @Test
    void defaultsToEnglishWhenNothingBound() {
        assertEquals("en", WorkerLocale.current());
    }

    @Test
    void collapsesZhVariantsToZh() {
        WorkerLocale.set("zh-CN");
        assertEquals("zh", WorkerLocale.current());
        WorkerLocale.set("zh_TW");
        assertEquals("zh", WorkerLocale.current());
        WorkerLocale.set("ZH");
        assertEquals("zh", WorkerLocale.current());
    }

    @Test
    void collapsesNonZhToEnglish() {
        WorkerLocale.set("en-US");
        assertEquals("en", WorkerLocale.current());
        WorkerLocale.set("fr");
        assertEquals("en", WorkerLocale.current());
        WorkerLocale.set("ja-JP");
        assertEquals("en", WorkerLocale.current());
    }

    @Test
    void nullAndBlankResolveToEnglish() {
        WorkerLocale.set(null);
        assertEquals("en", WorkerLocale.current());
        WorkerLocale.set("   ");
        assertEquals("en", WorkerLocale.current());
    }

    @Test
    void clearUnbindsTheLocale() {
        WorkerLocale.set("zh");
        assertEquals("zh", WorkerLocale.current());
        WorkerLocale.clear();
        assertEquals("en", WorkerLocale.current());
    }
}
