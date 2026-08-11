package fan.summer.fengyu.plugin.email;

import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.crypto.PluginKeyStore;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.rpc.EmailRpcHandlers;
import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import fan.summer.fengyu.sdk.PluginMessages;
import fan.summer.email.generated.PluginMethods;

// Generated Input records (one per rpc.methods entry). Explicit imports keep the set of registered
// methods auditable; the types are pure data carriers produced by the toolchain generator.
import fan.summer.email.generated.EmailAccountDeleteInput;
import fan.summer.email.generated.EmailAccountFindInput;
import fan.summer.email.generated.EmailAccountSaveInput;
import fan.summer.email.generated.EmailAccountSetDefaultInput;
import fan.summer.email.generated.EmailAccountTestImapInput;
import fan.summer.email.generated.EmailAccountTestInput;
import fan.summer.email.generated.EmailAccountsListInput;
import fan.summer.email.generated.EmailArchiveDetailInput;
import fan.summer.email.generated.EmailArchiveFetchCancelInput;
import fan.summer.email.generated.EmailArchiveFetchStartInput;
import fan.summer.email.generated.EmailArchiveFetchStatusInput;
import fan.summer.email.generated.EmailArchiveFetchInput;
import fan.summer.email.generated.EmailArchiveQueryInput;
import fan.summer.email.generated.EmailBatchPreviewInput;
import fan.summer.email.generated.EmailConfigDeleteInput;
import fan.summer.email.generated.EmailConfigFindInput;
import fan.summer.email.generated.EmailConfigSaveInput;
import fan.summer.email.generated.EmailConfigsListInput;
import fan.summer.email.generated.EmailContactDeleteInput;
import fan.summer.email.generated.EmailContactFindInput;
import fan.summer.email.generated.EmailContactSaveInput;
import fan.summer.email.generated.EmailContactsImportCommitInput;
import fan.summer.email.generated.EmailContactsImportPreviewInput;
import fan.summer.email.generated.EmailContactsQueryInput;
import fan.summer.email.generated.EmailImapFoldersInput;
import fan.summer.email.generated.EmailSendBatchInput;
import fan.summer.email.generated.EmailSendRecordsQueryInput;
import fan.summer.email.generated.EmailSendSingleInput;
import fan.summer.email.generated.EmailSendStatusInput;
import fan.summer.email.generated.EmailTagDeleteInput;
import fan.summer.email.generated.EmailTagSaveInput;
import fan.summer.email.generated.EmailTagsAssignInput;
import fan.summer.email.generated.EmailTagsListInput;
import fan.summer.email.generated.EmailTagsResolveInput;
import fan.summer.email.generated.ConfirmSendInput;
import fan.summer.email.generated.RejectSendInput;

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
 * extraction) and return the existing {success, summary, ...} envelope, which Gson serializes
 * directly. <b>Output type.</b> Each method is registered with {@code Object.class} as its output
 * type: the SDK treats {@code outputClass} as documentary ("the returned value is serialized by Gson
 * regardless of its declared type") and the manifest declares the rich payload (accounts, contacts,
 * tags, ...) as a free-form object "carried in the runtime result", so the generated
 * {@code <Method>Output} records are structurally insufficient (empty nested types / missing data
 * fields) and the handlers return the full envelope {@link java.util.Map} instead. SMTP/IMAP handlers
 * cooperative-check {@code RpcContext.cancellation()} so a {@code $/cancelRequest} from the host
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
            .method(PluginMethods.EMAIL_ACCOUNTS_LIST, EmailAccountsListInput.class, Object.class, h::listAccounts)
            .method(PluginMethods.EMAIL_ACCOUNT_FIND, EmailAccountFindInput.class, Object.class, h::findAccount)
            .method(PluginMethods.EMAIL_ACCOUNT_SAVE, EmailAccountSaveInput.class, Object.class, h::saveAccount)
            .method(PluginMethods.EMAIL_ACCOUNT_DELETE, EmailAccountDeleteInput.class, Object.class, h::deleteAccount)
            .method(PluginMethods.EMAIL_ACCOUNT_SET_DEFAULT, EmailAccountSetDefaultInput.class, Object.class, h::setDefaultAccount)
            .method(PluginMethods.EMAIL_ACCOUNT_TEST, EmailAccountTestInput.class, Object.class, h::testAccount)
            .method(PluginMethods.EMAIL_ACCOUNT_TEST_IMAP, EmailAccountTestImapInput.class, Object.class, h::testImapAccount)
            // Contacts, tags, address book.
            .method(PluginMethods.EMAIL_CONTACTS_QUERY, EmailContactsQueryInput.class, Object.class, h::queryContacts)
            .method(PluginMethods.EMAIL_CONTACT_FIND, EmailContactFindInput.class, Object.class, h::findContact)
            .method(PluginMethods.EMAIL_CONTACT_SAVE, EmailContactSaveInput.class, Object.class, h::saveContact)
            .method(PluginMethods.EMAIL_CONTACT_DELETE, EmailContactDeleteInput.class, Object.class, h::deleteContact)
            .method(PluginMethods.EMAIL_TAGS_LIST, EmailTagsListInput.class, Object.class, h::listTags)
            .method(PluginMethods.EMAIL_TAG_SAVE, EmailTagSaveInput.class, Object.class, h::saveTag)
            .method(PluginMethods.EMAIL_TAG_DELETE, EmailTagDeleteInput.class, Object.class, h::deleteTag)
            .method(PluginMethods.EMAIL_TAGS_ASSIGN, EmailTagsAssignInput.class, Object.class, h::assignTags)
            .method(PluginMethods.EMAIL_TAGS_RESOLVE, EmailTagsResolveInput.class, Object.class, h::resolveRecipients)
            .method(PluginMethods.EMAIL_CONTACTS_IMPORT_PREVIEW, EmailContactsImportPreviewInput.class, Object.class, h::importContactsPreview)
            .method(PluginMethods.EMAIL_CONTACTS_IMPORT_COMMIT, EmailContactsImportCommitInput.class, Object.class, h::importContactsCommit)
            // Batch-send configuration templates.
            .method(PluginMethods.EMAIL_CONFIGS_LIST, EmailConfigsListInput.class, Object.class, h::listConfigs)
            .method(PluginMethods.EMAIL_CONFIG_FIND, EmailConfigFindInput.class, Object.class, h::findConfig)
            .method(PluginMethods.EMAIL_CONFIG_SAVE, EmailConfigSaveInput.class, Object.class, h::saveConfig)
            .method(PluginMethods.EMAIL_CONFIG_DELETE, EmailConfigDeleteInput.class, Object.class, h::deleteConfig)
            // Send: prepare (confirmation-first) → confirm/reject → status/records.
            .method(PluginMethods.EMAIL_SEND_SINGLE, EmailSendSingleInput.class, Object.class, h::prepareSingle)
            .method(PluginMethods.EMAIL_SEND_BATCH, EmailSendBatchInput.class, Object.class, h::prepareBatch)
            .method(PluginMethods.EMAIL_BATCH_PREVIEW, EmailBatchPreviewInput.class, Object.class, h::previewBatch)
            .method(PluginMethods.EMAIL_SEND_STATUS, EmailSendStatusInput.class, Object.class, h::sendStatus)
            .method(PluginMethods.EMAIL_SEND_RECORDS_QUERY, EmailSendRecordsQueryInput.class, Object.class, h::querySendRecords)
            .method(PluginMethods.CONFIRM_SEND, ConfirmSendInput.class, Object.class, h::confirmSend)
            .method(PluginMethods.REJECT_SEND, RejectSendInput.class, Object.class, h::rejectSend)
            // Archive (IMAP collection). Synchronous fetch + async job trio with domain cancel.
            .method(PluginMethods.EMAIL_ARCHIVE_FETCH, EmailArchiveFetchInput.class, Object.class, h::collect)
            .method(PluginMethods.EMAIL_ARCHIVE_FETCH_START, EmailArchiveFetchStartInput.class, Object.class, h::collectStart)
            .method(PluginMethods.EMAIL_ARCHIVE_FETCH_STATUS, EmailArchiveFetchStatusInput.class, Object.class, h::collectStatus)
            .method(PluginMethods.EMAIL_ARCHIVE_FETCH_CANCEL, EmailArchiveFetchCancelInput.class, Object.class, h::collectCancel)
            .method(PluginMethods.EMAIL_ARCHIVE_QUERY, EmailArchiveQueryInput.class, Object.class, h::queryArchive)
            .method(PluginMethods.EMAIL_ARCHIVE_DETAIL, EmailArchiveDetailInput.class, Object.class, h::archiveDetail)
            .method(PluginMethods.EMAIL_IMAP_FOLDERS, EmailImapFoldersInput.class, Object.class, h::listFolders);
    }

    static EmailRpcHandlers handlers(Map<String, String> environment) {
        PluginDatabaseConfig config = PluginDatabaseConfig.fromEnvironment(environment)
            .orElseThrow(() -> new IllegalStateException(MSGS.format("em.err.databasePermissionRequired")));
        EmailDatabase database = new EmailDatabase(config);
        CredentialCipher cipher = new CredentialCipher(new PluginKeyStore(config.dataDirectory()).loadOrCreate());
        return new EmailRpcHandlers(database, cipher);
    }
}
