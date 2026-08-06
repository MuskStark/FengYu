package fan.summer.fengyu.plugin.email;

import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.crypto.PluginKeyStore;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.rpc.EmailRpcHandlers;
import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;

import java.util.Map;

public final class EmailWorkerMain {
    private EmailWorkerMain() { }

    public static void main(String[] args) throws Exception {
        // Do NOT call System.setOut here. JsonRpcWorker.run() captures System.out as the JSON-RPC
        // protocol stream and only then redirects System.out to stderr to keep handler/JDBC noise
        // off the wire. Redirecting here first makes run() capture stderr instead, so every
        // response lands on the host's stderr drain and all RPCs time out (60s). See SDK contract.
        worker(handlers(System.getenv())).run();
    }

    static JsonRpcWorker worker(EmailRpcHandlers handlers) {
        return new JsonRpcWorker()
            .on("email_accounts_list", handlers.handle("email_accounts_list", handlers::listAccounts))
            .on("email_contacts_query", handlers.handle("email_contacts_query", handlers::queryContacts))
            .on("email_send_single", handlers.handle("email_send_single", handlers::prepareSingle))
            .on("email_send_batch", handlers.handle("email_send_batch", handlers::prepareBatch))
            .on("email_batch_preview", handlers.handle("email_batch_preview", handlers::previewBatch))
            .on("email_send_status", handlers.handle("email_send_status", handlers::sendStatus))
            .on("email_send_records_query", handlers.handle("email_send_records_query", handlers::querySendRecords))
            .on("email_archive_fetch", handlers.handle("email_archive_fetch", handlers::collect))
            .on("email_archive_fetch_start", handlers.handle("email_archive_fetch_start", handlers::collectStart))
            .on("email_archive_fetch_status", handlers.handle("email_archive_fetch_status", handlers::collectStatus))
            .on("email_archive_fetch_cancel", handlers.handle("email_archive_fetch_cancel", handlers::collectCancel))
            .on("email_archive_query", handlers.handle("email_archive_query", handlers::queryArchive))
            .on("email_imap_folders", handlers.handle("email_imap_folders", handlers::listFolders))
            .on("confirm_send", handlers.handle("confirm_send", handlers::confirmSend))
            .on("reject_send", handlers.handle("reject_send", handlers::rejectSend))
            .on("email_account_find", handlers.handle("email_account_find", handlers::findAccount))
            .on("email_account_save", handlers.handle("email_account_save", handlers::saveAccount))
            .on("email_account_delete", handlers.handle("email_account_delete", handlers::deleteAccount))
            .on("email_account_set_default", handlers.handle("email_account_set_default", handlers::setDefaultAccount))
            .on("email_account_test", handlers.handle("email_account_test", handlers::testAccount))
            .on("email_account_test_imap", handlers.handle("email_account_test_imap", handlers::testImapAccount))
            .on("email_contact_find", handlers.handle("email_contact_find", handlers::findContact))
            .on("email_contact_save", handlers.handle("email_contact_save", handlers::saveContact))
            .on("email_contact_delete", handlers.handle("email_contact_delete", handlers::deleteContact))
            .on("email_tags_list", handlers.handle("email_tags_list", handlers::listTags))
            .on("email_tag_save", handlers.handle("email_tag_save", handlers::saveTag))
            .on("email_tag_delete", handlers.handle("email_tag_delete", handlers::deleteTag))
            .on("email_tags_assign", handlers.handle("email_tags_assign", handlers::assignTags))
            .on("email_tags_resolve", handlers.handle("email_tags_resolve", handlers::resolveRecipients))
            .on("email_contacts_import_preview", handlers.handle("email_contacts_import_preview", handlers::importContactsPreview))
            .on("email_contacts_import_commit", handlers.handle("email_contacts_import_commit", handlers::importContactsCommit))
            .on("email_configs_list", handlers.handle("email_configs_list", handlers::listConfigs))
            .on("email_config_find", handlers.handle("email_config_find", handlers::findConfig))
            .on("email_config_save", handlers.handle("email_config_save", handlers::saveConfig))
            .on("email_config_delete", handlers.handle("email_config_delete", handlers::deleteConfig))
            .on("email_archive_detail", handlers.handle("email_archive_detail", handlers::archiveDetail));
    }

    static EmailRpcHandlers handlers(Map<String, String> environment) {
        PluginDatabaseConfig config = PluginDatabaseConfig.fromEnvironment(environment)
            .orElseThrow(() -> new IllegalStateException("Email plugin requires the database permission"));
        EmailDatabase database = new EmailDatabase(config);
        CredentialCipher cipher = new CredentialCipher(new PluginKeyStore(config.dataDirectory()).loadOrCreate());
        return new EmailRpcHandlers(database, cipher);
    }
}
