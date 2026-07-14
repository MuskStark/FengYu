package fan.summer.fengyu.plugin.email;

import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.crypto.PluginKeyStore;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.rpc.EmailRpcHandlers;
import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;

import java.io.PrintStream;
import java.util.Map;

public final class EmailWorkerMain {
    private EmailWorkerMain() { }

    public static void main(String[] args) throws Exception {
        PrintStream protocolOutput = System.out;
        System.setOut(System.err);
        try {
            worker(handlers(System.getenv())).run(System.in, protocolOutput);
        } finally {
            System.setOut(protocolOutput);
        }
    }

    static JsonRpcWorker worker(EmailRpcHandlers handlers) {
        return new JsonRpcWorker()
            .on("email_accounts_list", handlers.safe(handlers::listAccounts))
            .on("email_contacts_query", handlers.safe(handlers::queryContacts))
            .on("email_send_single", handlers.safe(handlers::prepareSingle))
            .on("email_send_batch", handlers.safe(handlers::prepareBatch))
            .on("email_batch_preview", handlers.safe(handlers::previewBatch))
            .on("email_send_status", handlers.safe(handlers::sendStatus))
            .on("email_send_records_query", handlers.safe(handlers::querySendRecords))
            .on("email_archive_fetch", handlers.safe(handlers::collect))
            .on("email_archive_query", handlers.safe(handlers::queryArchive))
            .on("confirm_send", handlers.safe(handlers::confirmSend))
            .on("reject_send", handlers.safe(handlers::rejectSend))
            .on("email_account_find", handlers::findAccount)
            .on("email_account_save", handlers::saveAccount)
            .on("email_account_delete", handlers::deleteAccount)
            .on("email_account_set_default", handlers::setDefaultAccount)
            .on("email_account_test", handlers::testAccount)
            .on("email_contact_find", handlers::findContact)
            .on("email_contact_save", handlers::saveContact)
            .on("email_contact_delete", handlers::deleteContact)
            .on("email_tags_list", handlers::listTags)
            .on("email_tag_save", handlers::saveTag)
            .on("email_tag_delete", handlers::deleteTag)
            .on("email_tags_assign", handlers::assignTags)
            .on("email_tags_resolve", handlers::resolveRecipients)
            .on("email_configs_list", handlers::listConfigs)
            .on("email_config_find", handlers::findConfig)
            .on("email_config_save", handlers::saveConfig)
            .on("email_config_delete", handlers::deleteConfig)
            .on("email_archive_detail", handlers::archiveDetail);
    }

    static EmailRpcHandlers handlers(Map<String, String> environment) {
        PluginDatabaseConfig config = PluginDatabaseConfig.fromEnvironment(environment)
            .orElseThrow(() -> new IllegalStateException("Email plugin requires the database permission"));
        EmailDatabase database = new EmailDatabase(config);
        CredentialCipher cipher = new CredentialCipher(new PluginKeyStore(config.dataDirectory()).loadOrCreate());
        return new EmailRpcHandlers(database, cipher);
    }
}
