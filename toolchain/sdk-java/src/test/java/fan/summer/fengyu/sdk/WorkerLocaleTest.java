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

    /** wrap(Runnable) re-binds the SUBMIT-time locale around the task, so a reused pool thread
     *  observes the submitting call's locale instead of the one it was created with — the classic
     *  InheritableThreadLocal pool-reuse pitfall. */
    @Test
    void wrapBindsTheCapturedLocaleAroundPooledTasks() throws Exception {
        WorkerLocale.set("en");                 // the pool thread's birth locale
        java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            exec.submit(() -> "warm").get();    // thread is born and reused from here on
            WorkerLocale.set("zh");             // the submitting call's locale
            Runnable wrapped = WorkerLocale.wrap(
                () -> assertEquals("zh", WorkerLocale.current(), "wrapped task sees the captured locale"));
            WorkerLocale.set("en");             // simulate a reused thread's stale binding
            wrapped.run();
            assertEquals("en", WorkerLocale.current(), "wrap restores the thread's previous locale");
        } finally {
            WorkerLocale.clear();
            exec.shutdownNow();
        }
    }

    /** The Callable variant drives the same capture for executor submit()/invoke() paths. */
    @Test
    void wrapCallableCarriesTheCapturedLocaleAcrossThreads() throws Exception {
        java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            exec.submit(() -> "warm").get();     // pool thread born under the default locale
            WorkerLocale.set("zh");
            java.util.concurrent.Callable<String> probe = () -> WorkerLocale.current();
            var future = exec.submit(WorkerLocale.wrap(probe));
            assertEquals("zh", future.get(2, java.util.concurrent.TimeUnit.SECONDS),
                "the pooled thread must observe the submit-time locale through wrap");
        } finally {
            WorkerLocale.clear();
            exec.shutdownNow();
        }
    }
}
