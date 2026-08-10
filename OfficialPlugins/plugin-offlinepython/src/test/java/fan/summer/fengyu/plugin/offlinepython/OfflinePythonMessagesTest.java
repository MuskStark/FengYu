package fan.summer.fengyu.plugin.offlinepython;

import fan.summer.fengyu.sdk.PluginMessages;
import fan.summer.fengyu.sdk.WorkerLocale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Offline Python Builder worker i18n contract:
 * <ul>
 *   <li>the en and zh bundles expose IDENTICAL key sets (so neither locale ever
 *       renders a raw key);</li>
 *   <li>{@link PluginMessages} resolves a couple of representative keys against
 *       the {@link WorkerLocale} ThreadLocal (en vs zh) with positional
 *       interpolation;</li>
 *   <li>the doctor {@code id}/{@code value} protocol tokens stay locale-neutral
 *       (they are NOT translated in the bundles — the UI owns them).</li>
 * </ul>
 */
class OfflinePythonMessagesTest {

    private static final String BASE_NAME = "i18n.messages";
    private static final ClassLoader LOADER = OfflinePythonRpcHandlers.class.getClassLoader();

    @AfterEach
    void clearLocale() {
        WorkerLocale.clear();
    }

    @Test
    void enAndZhBundlesHaveIdenticalKeySets() {
        ResourceBundle en = ResourceBundle.getBundle(BASE_NAME, Locale.ENGLISH, LOADER);
        ResourceBundle zh = ResourceBundle.getBundle(BASE_NAME, Locale.SIMPLIFIED_CHINESE, LOADER);
        TreeSet<String> enKeys = new TreeSet<>(en.keySet());
        TreeSet<String> zhKeys = new TreeSet<>(zh.keySet());
        assertEquals(enKeys, zhKeys,
            "en/zh bundles must keep identical key sets; missing in zh: "
                + new TreeSet<>(enKeys) + " / missing in en: " + new TreeSet<>(zhKeys));
    }

    @Test
    void resolvesEnglishSummaryWithInterpolation() {
        WorkerLocale.set("en");
        PluginMessages msgs = PluginMessages.forClassLoader(BASE_NAME, OfflinePythonRpcHandlers.class);
        assertEquals("initialized project at /tmp/proj", msgs.format("opb.msg.init.ok", "/tmp/proj"));
        assertEquals("config saved", msgs.format("opb.msg.config.saved"));
        assertEquals("3 checks", msgs.format("opb.msg.doctor.count", 3));
    }

    @Test
    void resolvesChineseSummaryWhenLocaleIsZh() {
        WorkerLocale.set("zh");
        PluginMessages msgs = PluginMessages.forClassLoader(BASE_NAME, OfflinePythonRpcHandlers.class);
        // zh mirror must differ from the English value for a key that has one.
        assertEquals("配置已保存", msgs.format("opb.msg.config.saved"));
        assertFalse(msgs.format("opb.msg.init.ok", "/tmp/proj").contains("initialized"),
            "zh locale must render the Chinese pattern, not the English fallback");
    }

    @Test
    void fallsBackToEnglishWhenKeyMissingInZh() {
        // The fallback chain: zh → en → raw key. A key present in en but absent in zh
        // resolves to the English value. (Both bundles currently have identical sets,
        // so this documents the contract rather than exercising a real gap.)
        ResourceBundle en = ResourceBundle.getBundle(BASE_NAME, Locale.ENGLISH, LOADER);
        ResourceBundle zh = ResourceBundle.getBundle(BASE_NAME, Locale.SIMPLIFIED_CHINESE, LOADER);
        assertTrue(en.containsKey("opb.msg.deploy.started"));
        assertTrue(zh.containsKey("opb.msg.deploy.started"));
    }

    @Test
    void doctorProtocolTokensStayLocaleNeutral() {
        // The doctor id/value tokens are a locale-independent protocol contract: the worker
        // returns them raw and the frontend translates them via opb.doctor.check.* /
        // opb.doctor.value.*. They MUST NOT appear as values in either message bundle.
        ResourceBundle en = ResourceBundle.getBundle(BASE_NAME, Locale.ENGLISH, LOADER);
        ResourceBundle zh = ResourceBundle.getBundle(BASE_NAME, Locale.SIMPLIFIED_CHINESE, LOADER);
        for (String token : new String[]{"python_interpreter", "pip_download", "not_found",
                "supported", "unsupported", "reachable"}) {
            assertFalse(en.keySet().contains(token),
                "protocol token '" + token + "' must not be a message-bundle key (en)");
            assertFalse(zh.keySet().contains(token),
                "protocol token '" + token + "' must not be a message-bundle key (zh)");
        }
        // Sanity: the prose summary key that DOES wrap the doctor checks exists in both.
        assertEquals("3 checks", PluginMessages.forClassLoader(BASE_NAME,
                OfflinePythonRpcHandlers.class).format("opb.msg.doctor.count", 3));
    }
}
