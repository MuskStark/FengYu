package fan.summer.fengyu.plugin.email;

import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.crypto.PluginKeyStore;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.rpc.EmailRpcHandlers;
import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import fan.summer.fengyu.sdk.PluginMessages;
import fan.summer.email.generated.PluginMethods;

// Generated Input/Output records (one pair per rpc.methods entry). Explicit imports keep the set of
// registered methods auditable; the types are pure data carriers produced by the toolchain generator.
import fan.summer.email.contract.EmailContract.ConfirmSendInput;
import fan.summer.email.contract.EmailContract.ConfirmSendOutput;
import fan.summer.email.contract.EmailContract.EmailAccountDeleteInput;
import fan.summer.email.contract.EmailContract.EmailAccountDeleteOutput;
import fan.summer.email.contract.EmailContract.EmailAccountFindInput;
import fan.summer.email.contract.EmailContract.EmailAccountFindOutput;
import fan.summer.email.contract.EmailContract.EmailAccountSaveInput;
import fan.summer.email.contract.EmailContract.EmailAccountSaveOutput;
import fan.summer.email.contract.EmailContract.EmailAccountSetDefaultInput;
import fan.summer.email.contract.EmailContract.EmailAccountSetDefaultOutput;
import fan.summer.email.contract.EmailContract.EmailAccountTestImapInput;
import fan.summer.email.contract.EmailContract.EmailAccountTestImapOutput;
import fan.summer.email.contract.EmailContract.EmailAccountTestInput;
import fan.summer.email.contract.EmailContract.EmailAccountTestOutput;
import fan.summer.email.contract.EmailContract.EmailAccountsListInput;
import fan.summer.email.contract.EmailContract.EmailAccountsListOutput;
import fan.summer.email.contract.EmailContract.EmailArchiveDetailInput;
import fan.summer.email.contract.EmailContract.EmailArchiveDetailOutput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchCancelInput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchCancelOutput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchStartInput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchStartOutput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchStatusInput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchStatusOutput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchInput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchOutput;
import fan.summer.email.contract.EmailContract.EmailArchiveQueryInput;
import fan.summer.email.contract.EmailContract.EmailArchiveQueryOutput;
import fan.summer.email.contract.EmailContract.EmailBatchPreviewInput;
import fan.summer.email.contract.EmailContract.EmailBatchPreviewOutput;
import fan.summer.email.contract.EmailContract.EmailConfigDeleteInput;
import fan.summer.email.contract.EmailContract.EmailConfigDeleteOutput;
import fan.summer.email.contract.EmailContract.EmailConfigFindInput;
import fan.summer.email.contract.EmailContract.EmailConfigFindOutput;
import fan.summer.email.contract.EmailContract.EmailConfigSaveInput;
import fan.summer.email.contract.EmailContract.EmailConfigSaveOutput;
import fan.summer.email.contract.EmailContract.EmailConfigsListInput;
import fan.summer.email.contract.EmailContract.EmailConfigsListOutput;
import fan.summer.email.contract.EmailContract.EmailContactDeleteInput;
import fan.summer.email.contract.EmailContract.EmailContactDeleteOutput;
import fan.summer.email.contract.EmailContract.EmailContactFindInput;
import fan.summer.email.contract.EmailContract.EmailContactFindOutput;
import fan.summer.email.contract.EmailContract.EmailContactSaveInput;
import fan.summer.email.contract.EmailContract.EmailContactSaveOutput;
import fan.summer.email.contract.EmailContract.EmailContactsImportCommitInput;
import fan.summer.email.contract.EmailContract.EmailContactsImportCommitOutput;
import fan.summer.email.contract.EmailContract.EmailContactsImportPreviewInput;
import fan.summer.email.contract.EmailContract.EmailContactsImportPreviewOutput;
import fan.summer.email.contract.EmailContract.EmailContactsQueryInput;
import fan.summer.email.contract.EmailContract.EmailContactsQueryOutput;
import fan.summer.email.contract.EmailContract.EmailImapFoldersInput;
import fan.summer.email.contract.EmailContract.EmailImapFoldersOutput;
import fan.summer.email.contract.EmailContract.EmailSendBatchInput;
import fan.summer.email.contract.EmailContract.EmailSendBatchOutput;
import fan.summer.email.contract.EmailContract.EmailSendRecordsQueryInput;
import fan.summer.email.contract.EmailContract.EmailSendRecordsQueryOutput;
import fan.summer.email.contract.EmailContract.EmailSendSingleInput;
import fan.summer.email.contract.EmailContract.EmailSendSingleOutput;
import fan.summer.email.contract.EmailContract.EmailSendStatusInput;
import fan.summer.email.contract.EmailContract.EmailSendStatusOutput;
import fan.summer.email.contract.EmailContract.EmailTagDeleteInput;
import fan.summer.email.contract.EmailContract.EmailTagDeleteOutput;
import fan.summer.email.contract.EmailContract.EmailTagSaveInput;
import fan.summer.email.contract.EmailContract.EmailTagSaveOutput;
import fan.summer.email.contract.EmailContract.EmailTagsAssignInput;
import fan.summer.email.contract.EmailContract.EmailTagsAssignOutput;
import fan.summer.email.contract.EmailContract.EmailTagsListInput;
import fan.summer.email.contract.EmailContract.EmailTagsListOutput;
import fan.summer.email.contract.EmailContract.EmailTagsResolveInput;
import fan.summer.email.contract.EmailContract.EmailTagsResolveOutput;
import fan.summer.email.contract.EmailContract.RejectSendInput;
import fan.summer.email.contract.EmailContract.RejectSendOutput;

import java.util.Map;

/**
 * Email Center worker. Speaks newline-delimited JSON-RPC 2.0 on stdio under the Toolchain 2 typed
 * contract: every method is registered through {@link JsonRpcWorker#method}, so the SDK deserializes
 * each incoming {@code params} object into the matching generated Input record (package
 * {@code fan.summer.email.generated}), binds an {@code RpcContext} (call id, locale, cancellation
 * token, logger) to the handler thread, and serializes the returned envelope back into the response.
 *
 * <p>The handler implementations live in {@link EmailRpcHandlers}; they read typed fields off the
 * Input records (no raw {@code Map} parsing, no removed {@code JsonRpcWorker.string/integer}
 * extraction) and return the matching generated {@code <Method>Output} record, which Gson serializes
 * directly. Each record's {@link com.google.gson.annotations.SerializedName @SerializedName} values
 * are byte-identical to the JSON keys the previous free-form {@code Map} envelope emitted, so the
 * wire format is unchanged end to end (strongly typed on both sides of the bridge). SMTP/IMAP handlers
 * cooperative-check {@code RpcContext#cancellation()} so a {@code $/cancelRequest} from the host
 * yields a clean {@code CANCELLED} response instead of running to completion.
 *
 * <p>{@link JsonRpcWorker#run()} captures stdout as the JSON-RPC protocol stream and only then
 * redirects stdout to stderr to keep handler/JDBC noise off the wire, so this class must NOT call
 * {@code System.setOut} first (see the regression documented below).
 */
public final class EmailWorkerMain {
    private static final PluginMessages MSGS = PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, EmailWorkerMain.class);
    private EmailWorkerMain() { }

    public static void main(String[] args) throws Exception {
        // Do NOT call System.setOut here. JsonRpcWorker.run() captures System.out as the JSON-RPC
        // protocol stream and only then redirects System.out to stderr to keep handler/JDBC noise
        // off the wire. Redirecting here first makes run() capture stderr instead, so every
        // response lands on the host's stderr drain and all RPCs time out (60s). See SDK contract.
        worker(handlers(System.getenv())).run();
    }

    static JsonRpcWorker worker(EmailRpcHandlers h) {
        return new JsonRpcWorker()
            .onClose(h)
            // Accounts (credentials are write-only: accepted, encrypted at rest, never returned).
            .method(PluginMethods.EMAIL_ACCOUNTS_LIST, EmailAccountsListInput.class, EmailAccountsListOutput.class, h::listAccounts)
            .method(PluginMethods.EMAIL_ACCOUNT_FIND, EmailAccountFindInput.class, EmailAccountFindOutput.class, h::findAccount)
            .method(PluginMethods.EMAIL_ACCOUNT_SAVE, EmailAccountSaveInput.class, EmailAccountSaveOutput.class, h::saveAccount)
            .method(PluginMethods.EMAIL_ACCOUNT_DELETE, EmailAccountDeleteInput.class, EmailAccountDeleteOutput.class, h::deleteAccount)
            .method(PluginMethods.EMAIL_ACCOUNT_SET_DEFAULT, EmailAccountSetDefaultInput.class, EmailAccountSetDefaultOutput.class, h::setDefaultAccount)
            .method(PluginMethods.EMAIL_ACCOUNT_TEST, EmailAccountTestInput.class, EmailAccountTestOutput.class, h::testAccount)
            .method(PluginMethods.EMAIL_ACCOUNT_TEST_IMAP, EmailAccountTestImapInput.class, EmailAccountTestImapOutput.class, h::testImapAccount)
            // Contacts, tags, address book.
            .method(PluginMethods.EMAIL_CONTACTS_QUERY, EmailContactsQueryInput.class, EmailContactsQueryOutput.class, h::queryContacts)
            .method(PluginMethods.EMAIL_CONTACT_FIND, EmailContactFindInput.class, EmailContactFindOutput.class, h::findContact)
            .method(PluginMethods.EMAIL_CONTACT_SAVE, EmailContactSaveInput.class, EmailContactSaveOutput.class, h::saveContact)
            .method(PluginMethods.EMAIL_CONTACT_DELETE, EmailContactDeleteInput.class, EmailContactDeleteOutput.class, h::deleteContact)
            .method(PluginMethods.EMAIL_TAGS_LIST, EmailTagsListInput.class, EmailTagsListOutput.class, h::listTags)
            .method(PluginMethods.EMAIL_TAG_SAVE, EmailTagSaveInput.class, EmailTagSaveOutput.class, h::saveTag)
            .method(PluginMethods.EMAIL_TAG_DELETE, EmailTagDeleteInput.class, EmailTagDeleteOutput.class, h::deleteTag)
            .method(PluginMethods.EMAIL_TAGS_ASSIGN, EmailTagsAssignInput.class, EmailTagsAssignOutput.class, h::assignTags)
            .method(PluginMethods.EMAIL_TAGS_RESOLVE, EmailTagsResolveInput.class, EmailTagsResolveOutput.class, h::resolveRecipients)
            .method(PluginMethods.EMAIL_CONTACTS_IMPORT_PREVIEW, EmailContactsImportPreviewInput.class, EmailContactsImportPreviewOutput.class, h::importContactsPreview)
            .method(PluginMethods.EMAIL_CONTACTS_IMPORT_COMMIT, EmailContactsImportCommitInput.class, EmailContactsImportCommitOutput.class, h::importContactsCommit)
            // Batch-send configuration templates.
            .method(PluginMethods.EMAIL_CONFIGS_LIST, EmailConfigsListInput.class, EmailConfigsListOutput.class, h::listConfigs)
            .method(PluginMethods.EMAIL_CONFIG_FIND, EmailConfigFindInput.class, EmailConfigFindOutput.class, h::findConfig)
            .method(PluginMethods.EMAIL_CONFIG_SAVE, EmailConfigSaveInput.class, EmailConfigSaveOutput.class, h::saveConfig)
            .method(PluginMethods.EMAIL_CONFIG_DELETE, EmailConfigDeleteInput.class, EmailConfigDeleteOutput.class, h::deleteConfig)
            // Send: prepare (confirmation-first) → confirm/reject → status/records.
            .method(PluginMethods.EMAIL_SEND_SINGLE, EmailSendSingleInput.class, EmailSendSingleOutput.class, h::prepareSingle)
            .method(PluginMethods.EMAIL_SEND_BATCH, EmailSendBatchInput.class, EmailSendBatchOutput.class, h::prepareBatch)
            .method(PluginMethods.EMAIL_BATCH_PREVIEW, EmailBatchPreviewInput.class, EmailBatchPreviewOutput.class, h::previewBatch)
            .method(PluginMethods.EMAIL_SEND_STATUS, EmailSendStatusInput.class, EmailSendStatusOutput.class, h::sendStatus)
            .method(PluginMethods.EMAIL_SEND_RECORDS_QUERY, EmailSendRecordsQueryInput.class, EmailSendRecordsQueryOutput.class, h::querySendRecords)
            .method(PluginMethods.CONFIRM_SEND, ConfirmSendInput.class, ConfirmSendOutput.class, h::confirmSend)
            .method(PluginMethods.REJECT_SEND, RejectSendInput.class, RejectSendOutput.class, h::rejectSend)
            // Archive (IMAP collection). Synchronous fetch + async job trio with domain cancel.
            .method(PluginMethods.EMAIL_ARCHIVE_FETCH, EmailArchiveFetchInput.class, EmailArchiveFetchOutput.class, h::collect)
            .method(PluginMethods.EMAIL_ARCHIVE_FETCH_START, EmailArchiveFetchStartInput.class, EmailArchiveFetchStartOutput.class, h::collectStart)
            .method(PluginMethods.EMAIL_ARCHIVE_FETCH_STATUS, EmailArchiveFetchStatusInput.class, EmailArchiveFetchStatusOutput.class, h::collectStatus)
            .method(PluginMethods.EMAIL_ARCHIVE_FETCH_CANCEL, EmailArchiveFetchCancelInput.class, EmailArchiveFetchCancelOutput.class, h::collectCancel)
            .method(PluginMethods.EMAIL_ARCHIVE_QUERY, EmailArchiveQueryInput.class, EmailArchiveQueryOutput.class, h::queryArchive)
            .method(PluginMethods.EMAIL_ARCHIVE_DETAIL, EmailArchiveDetailInput.class, EmailArchiveDetailOutput.class, h::archiveDetail)
            .method(PluginMethods.EMAIL_IMAP_FOLDERS, EmailImapFoldersInput.class, EmailImapFoldersOutput.class, h::listFolders);
    }

    static EmailRpcHandlers handlers(Map<String, String> environment) {
        PluginDatabaseConfig config = PluginDatabaseConfig.fromEnvironment(environment)
            .orElseThrow(() -> new IllegalStateException(MSGS.format("em.err.databasePermissionRequired")));
        EmailDatabase database = new EmailDatabase(config);
        CredentialCipher cipher = new CredentialCipher(new PluginKeyStore(config.dataDirectory()).loadOrCreate());
        return new EmailRpcHandlers(database, cipher);
    }
}
