package fan.summer.fengyu.plugin.email;

import fan.summer.fengyu.sdk.PluginMessages;
import fan.summer.fengyu.sdk.WorkerLocale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that the Email worker message bundles ship complete, matching key sets in both locales so
 * neither ever renders a raw key, and that a representative send / account key resolves localized
 * through {@link WorkerLocale}.
 */
class EmailMessagesTest {

    private final PluginMessages msgs =
            PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, EmailWorkerMain.class);

    @AfterEach
    void clear() {
        WorkerLocale.clear();
    }

    @Test
    void enAndZhBundlesHaveIdenticalKeys() {
        ResourceBundle en = ResourceBundle.getBundle("i18n.messages", java.util.Locale.ENGLISH,
                EmailWorkerMain.class.getClassLoader());
        ResourceBundle zh = ResourceBundle.getBundle("i18n.messages", java.util.Locale.SIMPLIFIED_CHINESE,
                EmailWorkerMain.class.getClassLoader());
        assertEquals(en.keySet(), zh.keySet(), "en/zh email bundles must keep identical key sets");
    }

    @Test
    void foundAccountsSummaryIsLocalized() {
        WorkerLocale.set("en");
        assertEquals("Found 3 email account(s)", msgs.format("em.account.found", 3));
        WorkerLocale.set("zh");
        assertEquals("找到 3 个邮件账户", msgs.format("em.account.found", 3));
    }

    @Test
    void sendSingleReadySummaryIsLocalized() {
        WorkerLocale.set("en");
        assertEquals("Single email is ready for confirmation", msgs.format("em.send.singleReady"));
        WorkerLocale.set("zh");
        assertEquals("单封邮件已就绪，等待确认", msgs.format("em.send.singleReady"));
    }

    @Test
    void unknownAccountErrorIsLocalizedWithArg() {
        WorkerLocale.set("en");
        assertEquals("Unknown account: 42", msgs.format("em.err.accountUnknown", 42));
        WorkerLocale.set("zh");
        assertEquals("未知账户：42", msgs.format("em.err.accountUnknown", 42));
    }

    @Test
    void archivedSummaryIsLocalizedWithMultipleArgs() {
        WorkerLocale.set("en");
        assertEquals("Archived 5 new message(s); skipped 2 duplicate(s); 1 failure(s)",
                msgs.format("em.archive.archived", 5, 2, 1));
        WorkerLocale.set("zh");
        assertEquals("已归档 5 条新邮件；跳过 2 条重复；1 条失败",
                msgs.format("em.archive.archived", 5, 2, 1));
    }
}
