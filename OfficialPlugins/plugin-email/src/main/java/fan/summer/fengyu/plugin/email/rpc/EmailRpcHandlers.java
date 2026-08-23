package fan.summer.fengyu.plugin.email.rpc;

import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.ArchiveRequest;
import fan.summer.fengyu.plugin.email.model.ArchivedMessage;
import fan.summer.fengyu.plugin.email.model.Contact;
import fan.summer.fengyu.plugin.email.model.EmailMessageRequest;
import fan.summer.fengyu.plugin.email.model.MassConfig;
import fan.summer.fengyu.plugin.email.model.Tag;
import fan.summer.fengyu.plugin.email.model.ContactImport.ImportOptions;
import fan.summer.fengyu.plugin.email.repository.MassConfigRepository;
import fan.summer.fengyu.plugin.email.repository.PendingSendRepository;
import fan.summer.fengyu.plugin.email.repository.SentLogRepository;
import fan.summer.fengyu.plugin.email.service.AccountService;
import fan.summer.fengyu.plugin.email.service.AccountService.AccountView;
import fan.summer.fengyu.plugin.email.service.AddressBookService;
import fan.summer.fengyu.plugin.email.service.ContactImporter;
import fan.summer.fengyu.plugin.email.service.EmailArchiveService;
import fan.summer.fengyu.plugin.email.service.EmailHtmlSanitizer;
import fan.summer.fengyu.plugin.email.service.EmailSendService;
import fan.summer.fengyu.plugin.email.service.PendingSendService;
import fan.summer.fengyu.sdk.Jobs;
import fan.summer.fengyu.sdk.PluginMessages;
import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.fengyu.sdk.RpcException;
import fan.summer.email.contract.EmailContract.ConfirmSendInput;
import fan.summer.email.contract.EmailContract.ConfirmSendOutput;
import fan.summer.email.contract.EmailContract.ConfirmSendOutput.ConfirmSendOutputSend;
import fan.summer.email.contract.EmailContract.EmailAccountDeleteInput;
import fan.summer.email.contract.EmailContract.EmailAccountDeleteOutput;
import fan.summer.email.contract.EmailContract.EmailAccountFindInput;
import fan.summer.email.contract.EmailContract.EmailAccountFindOutput;
import fan.summer.email.contract.EmailContract.EmailAccountFindOutput.EmailAccountFindOutputAccount;
import fan.summer.email.contract.EmailContract.EmailAccountSaveInput;
import fan.summer.email.contract.EmailContract.EmailAccountSaveOutput;
import fan.summer.email.contract.EmailContract.EmailAccountSaveOutput.EmailAccountSaveOutputAccount;
import fan.summer.email.contract.EmailContract.EmailAccountSetDefaultInput;
import fan.summer.email.contract.EmailContract.EmailAccountSetDefaultOutput;
import fan.summer.email.contract.EmailContract.EmailAccountTestImapInput;
import fan.summer.email.contract.EmailContract.EmailAccountTestImapOutput;
import fan.summer.email.contract.EmailContract.EmailAccountTestInput;
import fan.summer.email.contract.EmailContract.EmailAccountTestOutput;
import fan.summer.email.contract.EmailContract.EmailAccountsListInput;
import fan.summer.email.contract.EmailContract.EmailAccountsListOutput;
import fan.summer.email.contract.EmailContract.EmailAccountsListOutput.EmailAccountsListOutputAccounts;
import fan.summer.email.contract.EmailContract.EmailArchiveDetailInput;
import fan.summer.email.contract.EmailContract.EmailArchiveDetailOutput;
import fan.summer.email.contract.EmailContract.EmailArchiveDetailOutput.EmailArchiveDetailOutputMessage;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchCancelInput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchCancelOutput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchInput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchOutput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchOutput.EmailArchiveFetchOutputCollection;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchStartInput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchStartOutput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchStatusInput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchStatusOutput;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchStatusOutput.EmailArchiveFetchStatusOutputResult;
import fan.summer.email.contract.EmailContract.EmailArchiveFetchStatusOutput.EmailArchiveFetchStatusOutputStatus;
import fan.summer.email.contract.EmailContract.EmailArchiveQueryInput;
import fan.summer.email.contract.EmailContract.EmailArchiveQueryOutput;
import fan.summer.email.contract.EmailContract.EmailArchiveQueryOutput.EmailArchiveQueryOutputMessages;
import fan.summer.email.contract.EmailContract.EmailBatchPreviewInput;
import fan.summer.email.contract.EmailContract.EmailBatchPreviewOutput;
import fan.summer.email.contract.EmailContract.EmailBatchPreviewOutput.EmailBatchPreviewOutputPreview;
import fan.summer.email.contract.EmailContract.EmailBatchPreviewOutput.EmailBatchPreviewOutputPreview.EmailBatchPreviewOutputPreviewMessages;
import fan.summer.email.contract.EmailContract.EmailBatchPreviewOutput.EmailBatchPreviewOutputPreview.EmailBatchPreviewOutputPreviewSkippedTags;
import fan.summer.email.contract.EmailContract.EmailConfigDeleteInput;
import fan.summer.email.contract.EmailContract.EmailConfigDeleteOutput;
import fan.summer.email.contract.EmailContract.EmailConfigFindInput;
import fan.summer.email.contract.EmailContract.EmailConfigFindOutput;
import fan.summer.email.contract.EmailContract.EmailConfigFindOutput.EmailConfigFindOutputConfig;
import fan.summer.email.contract.EmailContract.EmailConfigSaveInput;
import fan.summer.email.contract.EmailContract.EmailConfigSaveOutput;
import fan.summer.email.contract.EmailContract.EmailConfigSaveOutput.EmailConfigSaveOutputConfig;
import fan.summer.email.contract.EmailContract.EmailConfigsListInput;
import fan.summer.email.contract.EmailContract.EmailConfigsListOutput;
import fan.summer.email.contract.EmailContract.EmailConfigsListOutput.EmailConfigsListOutputConfigs;
import fan.summer.email.contract.EmailContract.EmailContactDeleteInput;
import fan.summer.email.contract.EmailContract.EmailContactDeleteOutput;
import fan.summer.email.contract.EmailContract.EmailContactFindInput;
import fan.summer.email.contract.EmailContract.EmailContactFindOutput;
import fan.summer.email.contract.EmailContract.EmailContactFindOutput.EmailContactFindOutputContact;
import fan.summer.email.contract.EmailContract.EmailContactSaveInput;
import fan.summer.email.contract.EmailContract.EmailContactSaveOutput;
import fan.summer.email.contract.EmailContract.EmailContactSaveOutput.EmailContactSaveOutputContact;
import fan.summer.email.contract.EmailContract.EmailContactsImportCommitInput;
import fan.summer.email.contract.EmailContract.EmailContactsImportCommitOutput;
import fan.summer.email.contract.EmailContract.EmailContactsImportCommitOutput.EmailContactsImportCommitOutputResult;
import fan.summer.email.contract.EmailContract.EmailContactsImportCommitOutput.EmailContactsImportCommitOutputResult.EmailContactsImportCommitOutputResultErrors;
import fan.summer.email.contract.EmailContract.EmailContactsImportPreviewInput;
import fan.summer.email.contract.EmailContract.EmailContactsImportPreviewOutput;
import fan.summer.email.contract.EmailContract.EmailContactsImportPreviewOutput.EmailContactsImportPreviewOutputPreview;
import fan.summer.email.contract.EmailContract.EmailContactsImportPreviewOutput.EmailContactsImportPreviewOutputPreview.EmailContactsImportPreviewOutputPreviewErrors;
import fan.summer.email.contract.EmailContract.EmailContactsQueryInput;
import fan.summer.email.contract.EmailContract.EmailContactsQueryOutput;
import fan.summer.email.contract.EmailContract.EmailContactsQueryOutput.EmailContactsQueryOutputContacts;
import fan.summer.email.contract.EmailContract.EmailImapFoldersInput;
import fan.summer.email.contract.EmailContract.EmailImapFoldersOutput;
import fan.summer.email.contract.EmailContract.EmailSendBatchInput;
import fan.summer.email.contract.EmailContract.EmailSendBatchOutput;
import fan.summer.email.contract.EmailContract.EmailSendBatchOutput.EmailSendBatchOutputConfirmation;
import fan.summer.email.contract.EmailContract.EmailSendBatchOutput.EmailSendBatchOutputConfirmation.EmailSendBatchOutputConfirmationSummary;
import fan.summer.email.contract.EmailContract.EmailSendRecordsQueryInput;
import fan.summer.email.contract.EmailContract.EmailSendRecordsQueryOutput;
import fan.summer.email.contract.EmailContract.EmailSendRecordsQueryOutput.EmailSendRecordsQueryOutputMessages;
import fan.summer.email.contract.EmailContract.EmailSendRecordsQueryOutput.EmailSendRecordsQueryOutputTasks;
import fan.summer.email.contract.EmailContract.EmailSendSingleInput;
import fan.summer.email.contract.EmailContract.EmailSendSingleOutput;
import fan.summer.email.contract.EmailContract.EmailSendSingleOutput.EmailSendSingleOutputConfirmation;
import fan.summer.email.contract.EmailContract.EmailSendSingleOutput.EmailSendSingleOutputConfirmation.EmailSendSingleOutputConfirmationSummary;
import fan.summer.email.contract.EmailContract.EmailSendStatusInput;
import fan.summer.email.contract.EmailContract.EmailSendStatusOutput;
import fan.summer.email.contract.EmailContract.EmailSendStatusOutput.EmailSendStatusOutputSend;
import fan.summer.email.contract.EmailContract.EmailTagDeleteInput;
import fan.summer.email.contract.EmailContract.EmailTagDeleteOutput;
import fan.summer.email.contract.EmailContract.EmailTagSaveInput;
import fan.summer.email.contract.EmailContract.EmailTagSaveOutput;
import fan.summer.email.contract.EmailContract.EmailTagSaveOutput.EmailTagSaveOutputTag;
import fan.summer.email.contract.EmailContract.EmailTagsAssignInput;
import fan.summer.email.contract.EmailContract.EmailTagsAssignOutput;
import fan.summer.email.contract.EmailContract.EmailTagsListInput;
import fan.summer.email.contract.EmailContract.EmailTagsListOutput;
import fan.summer.email.contract.EmailContract.EmailTagsListOutput.EmailTagsListOutputTags;
import fan.summer.email.contract.EmailContract.EmailTagsResolveInput;
import fan.summer.email.contract.EmailContract.EmailTagsResolveOutput;
import fan.summer.email.contract.EmailContract.RejectSendInput;
import fan.summer.email.contract.EmailContract.RejectSendOutput;
import fan.summer.email.contract.EmailContract.RejectSendOutput.RejectSendOutputSend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Typed RPC handlers for the Email Center worker, registered through the SDK's typed
 * {@code worker.method(PluginMethods.X, XInput.class, XOutput.class, h::x)} API in
 * {@link fan.summer.fengyu.plugin.email.EmailWorkerMain}. Each handler reads its fields off a
 * generated Input record (no raw {@code Map} parsing) and returns its generated
 * {@code <Method>Output} record, which Gson serializes directly. The record's
 * {@link com.google.gson.annotations.SerializedName @SerializedName} values are byte-identical to
 * the JSON keys the previous free-form {@code Map} envelope emitted, so the wire format is
 * unchanged. The envelope builders and failure-flattening {@link #result} wrapper are inlined
 * here (the class no longer extends {@code PluginHandlerSupport}), mirroring how
 * {@code MarkdownRpcHandlers} assembles its typed output directly.
 *
 * <p><b>Failure fidelity.</b> Each output record declares only {@code success}/{@code summary} as
 * required; every payload field is an optional (wrapper/nested-record) type. So a failure, which
 * builds the record with {@code success=false}, the error summary, and {@code null} payloads,
 * serializes (Gson omits nulls) to the same {@code {success:false, summary}} envelope the old
 * {@code Map} failure path produced — no spurious empty keys leak onto the wire.
 *
 * <p><b>Cancellation.</b> SMTP/IMAP-touching handlers ({@link #testAccount}, {@link #testImapAccount},
 * {@link #listFolders}, {@link #collect}, {@link #confirmSend}) cooperative-check
 * {@link RpcContext#cancellation()} before the network call so a {@code $/cancelRequest} from the
 * host returns a clean {@code CANCELLED} response (transport-level cancel). The long-running archive
 * job ({@link #collectStart}/{@link #collectStatus}/{@link #collectCancel}) keeps its own
 * <em>domain</em> cancel via the {@link Jobs} registry keyed by {@code jobId}, independent of any
 * single RPC.
 *
 * <p><b>Secret discipline.</b> Credential fields are accepted on account writes and scrubbed before
 * they can reach any log or response: the {@link EmailAccountSaveInput#password()} secret is funneled
 * straight into the secret-aware {@link AccountRpc.AccountRequest} (whose {@code toString} redacts
 * it as {@code password=<redacted>}) and encrypted at rest by {@link AccountService}; responses use
 * the credential-free {@link AccountView}. The typed registration never logs params (the SDK entry
 * path logs method identity only). The {@link #result} wrapper logs only the exception
 * <em>type</em> — never {@code getMessage()} or the stack — because a transport/parse exception
 * message can echo request values (a parsed path, a credential touched by an SMTP/IMAP error); this
 * matches the SDK dispatch loop's own type-only WARN policy.
 */
public final class EmailRpcHandlers implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(EmailRpcHandlers.class);
    /** Static message resolver for the envelope summaries and the path-validation helpers. */
    private static final PluginMessages MSGS =
            PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, EmailRpcHandlers.class);
    /** English fallback summary when an exception/result carries no message (prior behaviour came
     * from the SDK bundle; inlined here so no raw key is ever rendered to a client). */
    private static final String DEFAULT_FAILURE_SUMMARY = "email operation failed";

    private final AccountRpc accounts;
    private final AddressBookRpc addressBook;
    private final ContactImporter importer;
    private final MassConfigRepository configs;
    private final EmailSendService sends;
    private final PendingSendService pending;
    private final EmailArchiveService archive;
    private final EmailHtmlSanitizer htmlSanitizer = new EmailHtmlSanitizer();
    private final Jobs jobs = new Jobs();

    public EmailRpcHandlers(EmailDatabase database, CredentialCipher cipher) {
        accounts = new AccountRpc(new AccountService(database, cipher));
        addressBook = new AddressBookRpc(new AddressBookService(database));
        importer = new ContactImporter(database);
        configs = new MassConfigRepository(database);
        sends = new EmailSendService(database, cipher);
        pending = new PendingSendService(database, sends);
        archive = new EmailArchiveService(database, cipher);
    }

    @Override public void close() { jobs.close(); }

    // ── accounts ────────────────────────────────────────────────────────────

    public EmailAccountsListOutput listAccounts(EmailAccountsListInput input, RpcContext ctx) {
        return result(ctx, () -> {
            List<AccountView> values = accounts.list();
            return new EmailAccountsListOutput(values.stream().map(v -> toAccount(v, EmailAccountsListOutputAccounts::new)).toList(),
                true, t("em.account.found", values.size()));
        }, s -> new EmailAccountsListOutput(null, false, s));
    }

    public EmailAccountFindOutput findAccount(EmailAccountFindInput input, RpcContext ctx) {
        return result(ctx, () -> accounts.find(input.id())
            .map(value -> new EmailAccountFindOutput(toAccount(value, EmailAccountFindOutputAccount::new), true, t("em.account.foundOne")))
            .orElseGet(() -> new EmailAccountFindOutput(null, false, t("em.account.notFound"))),
            s -> new EmailAccountFindOutput(null, false, s));
    }

    public EmailAccountSaveOutput saveAccount(EmailAccountSaveInput input, RpcContext ctx) {
        return result(ctx, () -> {
            AccountView value = accounts.save(toAccountRequest(input));
            return new EmailAccountSaveOutput(toAccount(value, EmailAccountSaveOutputAccount::new), true, t("em.account.saved"));
        }, s -> new EmailAccountSaveOutput(null, false, s));
    }

    public EmailAccountDeleteOutput deleteAccount(EmailAccountDeleteInput input, RpcContext ctx) {
        return result(ctx, () -> accounts.delete(input.id())
                ? new EmailAccountDeleteOutput(true, t("em.account.deleted"))
                : new EmailAccountDeleteOutput(false, t("em.account.notFound")),
            s -> new EmailAccountDeleteOutput(false, s));
    }

    public EmailAccountSetDefaultOutput setDefaultAccount(EmailAccountSetDefaultInput input, RpcContext ctx) {
        return result(ctx, () -> accounts.setDefault(input.id())
                ? new EmailAccountSetDefaultOutput(true, t("em.account.defaultUpdated"))
                : new EmailAccountSetDefaultOutput(false, t("em.account.notFound")),
            s -> new EmailAccountSetDefaultOutput(false, s));
    }

    public EmailAccountTestOutput testAccount(EmailAccountTestInput input, RpcContext ctx) {
        return result(ctx, () -> {
            // SMTP I/O: honour a transport cancel that arrived before we open the socket.
            ctx.cancellation().throwIfCancelled();
            long accountId = input.accountId();
            var value = sends.testSmtp(accountId);
            ctx.logger().info("SMTP test for account {}: {}", accountId, value.success() ? "succeeded" : "failed");
            return value.success() ? new EmailAccountTestOutput(true, t("em.account.smtpSucceeded"))
                : new EmailAccountTestOutput(false, value.errorMessage());
        }, s -> new EmailAccountTestOutput(false, s));
    }

    public EmailAccountTestImapOutput testImapAccount(EmailAccountTestImapInput input, RpcContext ctx) {
        return result(ctx, () -> {
            // IMAP I/O: honour a transport cancel that arrived before we open the socket.
            ctx.cancellation().throwIfCancelled();
            long accountId = input.accountId();
            var value = archive.testImap(accountId);
            ctx.logger().info("IMAP test for account {}: {}", accountId, value.success() ? "succeeded" : "failed");
            return value.success() ? new EmailAccountTestImapOutput(true, t("em.account.imapSucceeded"))
                : new EmailAccountTestImapOutput(false, value.errorMessage());
        }, s -> new EmailAccountTestImapOutput(false, s));
    }

    public EmailImapFoldersOutput listFolders(EmailImapFoldersInput input, RpcContext ctx) {
        return result(ctx, () -> {
            // IMAP I/O: honour a transport cancel that arrived before we list folders.
            ctx.cancellation().throwIfCancelled();
            long accountId = input.accountId();
            var value = archive.listFolders(accountId);
            ctx.logger().info("IMAP folder list for account {}: {} folder(s)", accountId, value.folders().size());
            return new EmailImapFoldersOutput(value.folders(), true, t("em.folder.found", value.folders().size()));
        }, s -> new EmailImapFoldersOutput(null, false, s));
    }

    // ── contacts / tags / address book ──────────────────────────────────────

    public EmailContactsQueryOutput queryContacts(EmailContactsQueryInput input, RpcContext ctx) {
        return result(ctx, () -> {
            List<Contact> values = addressBook.search(new AddressBookRpc.SearchRequest(input.query(),
                longSet(input.tagIds()), orZero(input.offset()), orDefault(input.limit(), 50)));
            return new EmailContactsQueryOutput(values.stream().map(c -> toContact(c, EmailContactsQueryOutputContacts::new)).toList(),
                true, t("em.contact.found", values.size()));
        }, s -> new EmailContactsQueryOutput(null, false, s));
    }

    public EmailContactFindOutput findContact(EmailContactFindInput input, RpcContext ctx) {
        return result(ctx, () -> addressBook.findContact(input.id())
            .map(value -> new EmailContactFindOutput(toContact(value, EmailContactFindOutputContact::new), true, t("em.contact.foundOne")))
            .orElseGet(() -> new EmailContactFindOutput(null, false, t("em.contact.notFound"))),
            s -> new EmailContactFindOutput(null, false, s));
    }

    public EmailContactSaveOutput saveContact(EmailContactSaveInput input, RpcContext ctx) {
        return result(ctx, () -> {
            Contact value = addressBook.saveContact(
                new AddressBookRpc.ContactRequest(toLong(input.id()), input.email(), input.nickname(),
                    input.notes(), longSet(input.tagIds())));
            return new EmailContactSaveOutput(toContact(value, EmailContactSaveOutputContact::new), true, t("em.contact.saved"));
        }, s -> new EmailContactSaveOutput(null, false, s));
    }

    public EmailContactDeleteOutput deleteContact(EmailContactDeleteInput input, RpcContext ctx) {
        return result(ctx, () -> addressBook.deleteContact(input.id())
                ? new EmailContactDeleteOutput(true, t("em.contact.deleted"))
                : new EmailContactDeleteOutput(false, t("em.contact.notFound")),
            s -> new EmailContactDeleteOutput(false, s));
    }

    public EmailTagsListOutput listTags(EmailTagsListInput input, RpcContext ctx) {
        return result(ctx, () -> {
            List<Tag> tags = addressBook.listTags();
            return new EmailTagsListOutput(true, t("em.tag.found", tags.size()),
                tags.stream().map(tag -> toTag(tag, EmailTagsListOutputTags::new)).toList());
        }, s -> new EmailTagsListOutput(false, s, null));
    }

    public EmailTagSaveOutput saveTag(EmailTagSaveInput input, RpcContext ctx) {
        return result(ctx, () -> {
            Tag value = addressBook.saveTag(new AddressBookRpc.TagRequest(toLong(input.id()), input.name()));
            return new EmailTagSaveOutput(true, t("em.tag.saved"), toTag(value, EmailTagSaveOutputTag::new));
        }, s -> new EmailTagSaveOutput(false, s, null));
    }

    public EmailTagDeleteOutput deleteTag(EmailTagDeleteInput input, RpcContext ctx) {
        return result(ctx, () -> addressBook.deleteTag(input.id())
                ? new EmailTagDeleteOutput(true, t("em.tag.deleted"))
                : new EmailTagDeleteOutput(false, t("em.tag.notFound")),
            s -> new EmailTagDeleteOutput(false, s));
    }

    public EmailTagsAssignOutput assignTags(EmailTagsAssignInput input, RpcContext ctx) {
        return result(ctx, () -> {
            addressBook.assignTags(new AddressBookRpc.BulkTagRequest(
                longSet(input.contactIds()), longSet(input.tagIds())));
            return new EmailTagsAssignOutput(true, t("em.contact.tagsUpdated"));
        }, s -> new EmailTagsAssignOutput(false, s));
    }

    public EmailTagsResolveOutput resolveRecipients(EmailTagsResolveInput input, RpcContext ctx) {
        return result(ctx, () -> {
            Set<String> recipients = addressBook.resolveRecipients(longSet(input.tagIds()));
            return new EmailTagsResolveOutput(List.copyOf(recipients), true, t("em.contact.resolvedRecipients", recipients.size()));
        }, s -> new EmailTagsResolveOutput(null, false, s));
    }

    public EmailContactsImportPreviewOutput importContactsPreview(EmailContactsImportPreviewInput input, RpcContext ctx) {
        return result(ctx, () -> {
            ImportOptions options = importOptions(input.duplicateMode() == null ? null : input.duplicateMode().name(), input.tagDelimiter());
            var preview = importer.preview(path(input.sourceFile(), "sourceFile", "file"), options);
            EmailContactsImportPreviewOutputPreview record = new EmailContactsImportPreviewOutputPreview(preview.createdContacts(),
                preview.createdTags(), preview.errors().stream().map(e -> new EmailContactsImportPreviewOutputPreviewErrors(e.message(), e.row())).toList(),
                preview.mergedContacts(), preview.rowsTotal(), preview.rowsValid(), preview.skippedContacts());
            return new EmailContactsImportPreviewOutput(record, true, t("em.contact.importPreview", preview.rowsTotal(), preview.createdContacts(),
                preview.mergedContacts(), preview.skippedContacts(), preview.createdTags().size(), preview.errors().size()));
        }, s -> new EmailContactsImportPreviewOutput(null, false, s));
    }

    public EmailContactsImportCommitOutput importContactsCommit(EmailContactsImportCommitInput input, RpcContext ctx) {
        return result(ctx, () -> {
            ImportOptions options = importOptions(input.duplicateMode() == null ? null : input.duplicateMode().name(), input.tagDelimiter());
            var outcome = importer.commit(path(input.sourceFile(), "sourceFile", "file"), options);
            EmailContactsImportCommitOutputResult record = new EmailContactsImportCommitOutputResult(outcome.created(),
                outcome.errors().stream().map(e -> new EmailContactsImportCommitOutputResultErrors(e.message(), e.row())).toList(),
                outcome.merged(), outcome.skipped(), outcome.tagsCreated(), outcome.tagsAssigned());
            String summary = outcome.errors().isEmpty()
                ? t("em.contact.imported", outcome.created(), outcome.merged(), outcome.skipped(), outcome.tagsCreated(), outcome.tagsAssigned())
                : t("em.contact.importedWithErrors", outcome.created(), outcome.merged(), outcome.skipped(),
                    outcome.tagsCreated(), outcome.tagsAssigned(), outcome.errors().size());
            return new EmailContactsImportCommitOutput(record, true, summary);
        }, s -> new EmailContactsImportCommitOutput(null, false, s));
    }

    // ── batch-send configuration templates ──────────────────────────────────

    public EmailConfigsListOutput listConfigs(EmailConfigsListInput input, RpcContext ctx) {
        return result(ctx, () -> {
            List<MassConfig> values = configs.list();
            return new EmailConfigsListOutput(values.stream().map(c -> toMassConfig(c, EmailConfigsListOutputConfigs::new)).toList(),
                true, t("em.batch.foundConfigs", values.size()));
        }, s -> new EmailConfigsListOutput(null, false, s));
    }

    public EmailConfigFindOutput findConfig(EmailConfigFindInput input, RpcContext ctx) {
        return result(ctx, () -> configs.find(input.id())
            .map(value -> new EmailConfigFindOutput(toMassConfig(value, EmailConfigFindOutputConfig::new), true, t("em.batch.configFound")))
            .orElseGet(() -> new EmailConfigFindOutput(null, false, t("em.batch.configNotFound"))),
            s -> new EmailConfigFindOutput(null, false, s));
    }

    public EmailConfigSaveOutput saveConfig(EmailConfigSaveInput input, RpcContext ctx) {
        return result(ctx, () -> {
            long saved = configs.save(toLong(input.id()), requiredField(input.name(), "name"),
                requiredField(input.mode(), "mode"), requiredField(input.configJson(), "configJson"));
            return new EmailConfigSaveOutput(toMassConfig(configs.find(saved).orElseThrow(), EmailConfigSaveOutputConfig::new),
                true, t("em.batch.configSaved"));
        }, s -> new EmailConfigSaveOutput(null, false, s));
    }

    public EmailConfigDeleteOutput deleteConfig(EmailConfigDeleteInput input, RpcContext ctx) {
        return result(ctx, () -> configs.delete(input.id())
                ? new EmailConfigDeleteOutput(true, t("em.batch.configDeleted"))
                : new EmailConfigDeleteOutput(false, t("em.batch.configNotFound")),
            s -> new EmailConfigDeleteOutput(false, s));
    }

    // ── send: confirmation-first prepare → confirm/reject → status/records ──

    public EmailSendSingleOutput prepareSingle(EmailSendSingleInput input, RpcContext ctx) {
        return result(ctx, () -> {
            EmailMessageRequest request = message(input);
            Set<Long> recipientTagIds = longSet(input.recipientTagIds());
            if (!recipientTagIds.isEmpty()) {
                return toConfirmationOutput(pending.prepareComposeByTags(request, recipientTagIds), t("em.send.taggedReady"),
                    EmailSendSingleOutputConfirmation::new, EmailSendSingleOutputConfirmationSummary::new,
                    EmailSendSingleOutput::new);
            }
            if (request.to().isEmpty()) throw new IllegalArgumentException(t("em.err.toRequiredForDirect"));
            return toConfirmationOutput(pending.prepareSingle(request), t("em.send.singleReady"),
                EmailSendSingleOutputConfirmation::new, EmailSendSingleOutputConfirmationSummary::new,
                EmailSendSingleOutput::new);
        }, s -> new EmailSendSingleOutput(null, null, false, s));
    }

    public EmailSendBatchOutput prepareBatch(EmailSendBatchInput input, RpcContext ctx) {
        return result(ctx, () -> toConfirmationOutput(pending.prepareAttachmentBatch(message(input),
                path(input.inputDirectory(), "inputDirectory", "directory"),
                paths(input.commonAttachments()), longSet(input.recipientGroupTagIds()),
                longSet(input.ccGroupTagIds())), t("em.send.batchReady"),
            EmailSendBatchOutputConfirmation::new, EmailSendBatchOutputConfirmationSummary::new,
            EmailSendBatchOutput::new),
            s -> new EmailSendBatchOutput(null, null, false, s));
    }

    public EmailBatchPreviewOutput previewBatch(EmailBatchPreviewInput input, RpcContext ctx) {
        return result(ctx, () -> {
            var plan = pending.previewBatch(message(input),
                path(input.inputDirectory(), "inputDirectory", "directory"),
                paths(input.commonAttachments()), longSet(input.recipientGroupTagIds()),
                longSet(input.ccGroupTagIds()));
            EmailBatchPreviewOutputPreview preview = new EmailBatchPreviewOutputPreview(plan.ignoredFiles(), plan.messageCount(),
                plan.messages().stream().map(m -> new EmailBatchPreviewOutputPreviewMessages(m.attachmentTag(), m.cc(),
                    m.commonAttachments(), m.tagAttachments(), m.to())).toList(),
                plan.skippedTags().stream().map(s -> new EmailBatchPreviewOutputPreviewSkippedTags(s.attachmentTag(),
                    s.attachments(), s.reason())).toList());
            return new EmailBatchPreviewOutput(preview, true, t("em.batch.preview"));
        }, s -> new EmailBatchPreviewOutput(null, false, s));
    }

    public EmailSendStatusOutput sendStatus(EmailSendStatusInput input, RpcContext ctx) {
        return result(ctx, () -> pending.status(requiredField(input.confirmationId(), "confirmationId"))
            .map(value -> new EmailSendStatusOutput(toSendView(value), true, t("em.send.statusIs", value.status())))
            .orElseGet(() -> new EmailSendStatusOutput(null, false, t("em.send.confirmationNotFound"))),
            s -> new EmailSendStatusOutput(null, false, s));
    }

    public EmailSendRecordsQueryOutput querySendRecords(EmailSendRecordsQueryInput input, RpcContext ctx) {
        return result(ctx, () -> {
            var records = pending.records(input.taskStatus(), input.confirmationId(),
                input.messageStatus(), input.query(), orZero(input.offset()), orDefault(input.limit(), 50));
            return new EmailSendRecordsQueryOutput(
                records.messages().stream().map(EmailRpcHandlers::toSentMessage).toList(),
                true, t("em.send.foundTasks", records.tasks().size()),
                records.tasks().stream().map(v -> new EmailSendRecordsQueryOutputTasks((int) v.accountId(), v.confirmationId(),
                    str(v.expiresAt()), v.mode(), v.status(), str(v.updatedAt()))).toList());
        }, s -> new EmailSendRecordsQueryOutput(null, false, s, null));
    }

    public ConfirmSendOutput confirmSend(ConfirmSendInput input, RpcContext ctx) {
        return result(ctx, () -> {
            // SMTP send: honour a transport cancel that arrived before the confirmed dispatch.
            ctx.cancellation().throwIfCancelled();
            String confirmationId = requiredField(input.confirmationId(), "confirmationId");
            var value = pending.confirm(confirmationId);
            ctx.logger().info("send {} confirmed: {}", confirmationId, value.status());
            return new ConfirmSendOutput(toSendResult(value, ConfirmSendOutputSend::new),
                true, t("em.send.confirmationIs", value.status()));
        }, s -> new ConfirmSendOutput(null, false, s));
    }

    public RejectSendOutput rejectSend(RejectSendInput input, RpcContext ctx) {
        return result(ctx, () -> {
            String confirmationId = requiredField(input.confirmationId(), "confirmationId");
            var value = pending.reject(confirmationId);
            ctx.logger().info("send {} rejected: {}", confirmationId, value.status());
            return new RejectSendOutput(toSendResult(value, RejectSendOutputSend::new),
                true, t("em.send.confirmationIs", value.status()));
        }, s -> new RejectSendOutput(null, false, s));
    }

    // ── archive (IMAP collection): synchronous fetch + async job trio ───────

    public EmailArchiveFetchOutput collect(EmailArchiveFetchInput input, RpcContext ctx) {
        return result(ctx, () -> {
            // IMAP fetch: honour a transport cancel that arrived before the synchronous collect.
            ctx.cancellation().throwIfCancelled();
            long accountId = input.accountId();
            String folder = input.folder();
            ArchiveRequest request = new ArchiveRequest(accountId, folder,
                instant(input.start()), instant(input.end()),
                path(input.outputDirectory(), "outputDirectory", "directory"));
            var value = archive.collect(request, ignored -> { });
            ctx.logger().info("archived {} new, {} duplicate(s), {} failure(s) from account {} folder '{}'",
                value.newArchived(), value.skippedDuplicates(), value.failures(), accountId, folder);
            return new EmailArchiveFetchOutput(toCollection(value), true,
                t("em.archive.archived", value.newArchived(), value.skippedDuplicates(), value.failures()));
        }, s -> new EmailArchiveFetchOutput(null, false, s));
    }

    /**
     * Launch archive collection on a virtual thread so it survives beyond the host's per-RPC timeout.
     * Returns a {@code jobId} immediately; the UI polls {@link #collectStatus} to drain progress and
     * fetch the final result, and cancels via {@link #collectCancel} (domain-level job cancel, which
     * interrupts the job thread independent of the launching RPC).
     */
    public EmailArchiveFetchStartOutput collectStart(EmailArchiveFetchStartInput input, RpcContext ctx) {
        return result(ctx, () -> {
            long accountId = input.accountId();
            String folder = input.folder();
            ArchiveRequest request = new ArchiveRequest(accountId, folder,
                instant(input.start()), instant(input.end()),
                path(input.outputDirectory(), "outputDirectory", "directory"));
            Jobs.Job job = jobs.start("ARCHIVE", handle -> {
                try {
                    var value = archive.collect(request, progress -> handle.log(
                        progress.completed() + "/" + progress.total() + " new=" + progress.newArchived()
                        + " skipped=" + progress.skippedDuplicates() + " failed=" + progress.failures()));
                    handle.setSummary(value);
                    LOG.info("archived {} new, {} duplicate(s), {} failure(s) from account {} folder '{}'",
                        value.newArchived(), value.skippedDuplicates(), value.failures(), accountId, folder);
                } catch (Exception e) {
                    // Log the exception TYPE only: the message/stack of a transport exception can
                    // echo request-carried values, so it is not safe in the shared log channel. The
                    // job is marked failed (one-line) regardless; the host log surface keeps the type.
                    LOG.warn("archive job failed for account {} folder '{}': {}",
                        accountId, folder, e.getClass().getSimpleName());
                    throw e;
                }
            });
            return new EmailArchiveFetchStartOutput(job.id, true, t("em.archive.started"));
        }, s -> new EmailArchiveFetchStartOutput(null, false, s));
    }

    public EmailArchiveFetchStatusOutput collectStatus(EmailArchiveFetchStatusInput input, RpcContext ctx) {
        // jobs.snapshot returns the prior free-form envelope Map (success/summary + jobId/type/status/
        // logs/cursor/droppedLogs/done/result/error/elapsedMs); rebuild the typed record field-for-field
        // so the wire stays byte-identical, including the {success:false,jobId,done:true} unknown-job shape.
        return result(ctx, () -> fromSnapshot(jobs.snapshot(input.jobId(), orZero(input.cursor()))),
            s -> new EmailArchiveFetchStatusOutput(null, null, null, null, null, null, null, null, null, false, s, null));
    }

    public EmailArchiveFetchCancelOutput collectCancel(EmailArchiveFetchCancelInput input, RpcContext ctx) {
        return result(ctx, () -> {
            String jobId = input.jobId();
            if (!jobs.cancel(jobId)) return new EmailArchiveFetchCancelOutput(null, false, t("em.archive.jobNotRunning", jobId));
            return new EmailArchiveFetchCancelOutput(jobId, true, t("em.archive.cancelRequested"));
        }, s -> new EmailArchiveFetchCancelOutput(null, false, s));
    }

    public EmailArchiveQueryOutput queryArchive(EmailArchiveQueryInput input, RpcContext ctx) {
        return result(ctx, () -> {
            var values = archive.search(new EmailArchiveService.SearchFilter(toLong(input.accountId()),
                input.folder(), input.sender(), input.subject(),
                instant(input.start()), instant(input.end()), orZero(input.offset()), orDefault(input.limit(), 50)));
            return new EmailArchiveQueryOutput(values.stream().map(m -> toArchived(m, EmailArchiveQueryOutputMessages::new)).toList(),
                true, t("em.archive.found", values.size()));
        }, s -> new EmailArchiveQueryOutput(null, false, s));
    }

    public EmailArchiveDetailOutput archiveDetail(EmailArchiveDetailInput input, RpcContext ctx) {
        return result(ctx, () -> archive.detail(input.id())
            .map(value -> new EmailArchiveDetailOutput(toArchived(value, EmailArchiveDetailOutputMessage::new), true, t("em.archive.messageFound")))
            .orElseGet(() -> new EmailArchiveDetailOutput(null, false, t("em.archive.messageNotFound"))),
            s -> new EmailArchiveDetailOutput(null, false, s));
    }

    // ── record conversion helpers (build generated item records from domain objects) ─

    // Repeated payload shapes share one converter per shape, parameterised by the generated record's
    // canonical constructor (so the same AccountView/Contact/Tag/... maps to each method's distinct
    // nested record type without duplicating the field wiring). Constructor argument order matches the
    // generated record's component order (alphabetical by @SerializedName == the wire key).

    @FunctionalInterface private interface AccountOut<T> {
        T create(boolean defaultAccount, String displayName, String email, int id, String imapHost, Integer imapPort,
            String imapSecurity, boolean imapSkipCertVerify, boolean passwordConfigured, String smtpHost, int smtpPort,
            String smtpSecurity, boolean smtpSkipCertVerify);
    }
    @FunctionalInterface private interface ContactOut<T> {
        T create(String createdAt, String email, int id, String nickname, String notes, List<Integer> tagIds);
    }
    @FunctionalInterface private interface TagOut<T> { T create(int id, String name); }
    @FunctionalInterface private interface MassConfigOut<T> {
        T create(String configJson, String createdAt, int id, String mode, String name);
    }
    @FunctionalInterface private interface ArchivedOut<T> {
        T create(String accountEmail, int accountId, String archivedAt, String bodyPreview, String emlPath, String folder,
            String fromAddress, boolean hasAttachment, int id, String messageUid, String receivedAt, String recipientsJson,
            String sentAt, String subject);
    }
    @FunctionalInterface private interface ConfirmationOut<C, S, O> {
        O create(C confirmation, Boolean confirmationRequired, boolean success, String summary);
    }
    @FunctionalInterface private interface ConfirmationRecordOut<C, S> {
        C create(String approveMethod, String confirmationId, String expiresAt, String pluginId, String rejectMethod, List<S> summary);
    }
    @FunctionalInterface private interface SummaryOut<S> { S create(String group, String label, String value); }
    @FunctionalInterface private interface SendResultOut<T> {
        T create(int failed, List<String> failedRecipients, String status, int succeeded);
    }

    private static <T> T toAccount(AccountView v, AccountOut<T> out) {
        return out.create(v.defaultAccount(), v.displayName(), v.email(), (int) v.id(), v.imapHost(), v.imapPort(),
            v.imapSecurity(), v.imapSkipCertVerify(), v.passwordConfigured(), v.smtpHost(), v.smtpPort(),
            v.smtpSecurity(), v.smtpSkipCertVerify());
    }

    private static <T> T toContact(Contact c, ContactOut<T> out) {
        return out.create(str(c.createdAt()), c.email(), (int) c.id(), c.nickname(), c.notes(),
            c.tagIds().stream().map(Number::intValue).toList());
    }

    private static <T> T toTag(Tag tag, TagOut<T> out) { return out.create((int) tag.id(), tag.name()); }

    private static <T> T toMassConfig(MassConfig c, MassConfigOut<T> out) {
        return out.create(c.configJson(), str(c.createdAt()), (int) c.id(), c.mode(), c.name());
    }

    private static <T> T toArchived(ArchivedMessage m, ArchivedOut<T> out) {
        return out.create(m.accountEmail(), (int) m.accountId(), str(m.archivedAt()), m.bodyPreview(), m.emlPath(), m.folder(),
            m.fromAddress(), m.hasAttachment(), (int) m.id(), m.messageUid(), str(m.receivedAt()), m.recipientsJson(),
            str(m.sentAt()), m.subject());
    }

    private static EmailSendStatusOutputSend toSendView(fan.summer.fengyu.plugin.email.model.PendingSend value) {
        return new EmailSendStatusOutputSend((int) value.accountId(), value.confirmationId(), str(value.expiresAt()),
            value.mode(), value.status(), str(value.updatedAt()));
    }

    private static EmailSendRecordsQueryOutputMessages toSentMessage(SentLogRepository.SentMessageView v) {
        return new EmailSendRecordsQueryOutputMessages(v.accountEmail(), v.attachmentJson(), v.confirmationId(),
            v.errorMessage(), (int) v.id(), v.recipientsJson(), str(v.sentAt()), v.status(), v.subject());
    }

    private static <T> T toSendResult(PendingSendService.ConfirmationResult value, SendResultOut<T> out) {
        return out.create(value.failed(), value.failedRecipients(), value.status(), value.succeeded());
    }

    private static EmailArchiveFetchOutputCollection toCollection(EmailArchiveService.CollectResult value) {
        return new EmailArchiveFetchOutputCollection(value.failures(), value.newArchived(), value.skippedDuplicates());
    }

    private static <C, S, O> O toConfirmationOutput(PendingSendService.ConfirmationEnvelope envelope, String summary,
            ConfirmationRecordOut<C, S> recordOut, SummaryOut<S> summaryOut, ConfirmationOut<C, S, O> out) {
        PendingSendService.Confirmation c = envelope.confirmation();
        C confirmation = recordOut.create(c.approveMethod(), c.confirmationId(), str(c.expiresAt()), c.pluginId(),
            c.rejectMethod(), c.summary().stream().map(row -> summaryOut.create(row.group(), row.label(), row.value())).toList());
        return out.create(confirmation, envelope.confirmationRequired(), true, summary);
    }

    /** Rebuild the typed status record from the SDK {@link Jobs} snapshot Map (byte-identical wire). */
    @SuppressWarnings("unchecked")
    private static EmailArchiveFetchStatusOutput fromSnapshot(Map<String, Object> snap) {
        Object result = snap.get("result");
        EmailArchiveFetchStatusOutputResult resultRecord = result instanceof EmailArchiveService.CollectResult c
            ? new EmailArchiveFetchStatusOutputResult(c.failures(), c.newArchived(), c.skippedDuplicates()) : null;
        Object status = snap.get("status");
        EmailArchiveFetchStatusOutputStatus statusEnum = status instanceof String s
            ? EmailArchiveFetchStatusOutputStatus.valueOf(s) : null;
        return new EmailArchiveFetchStatusOutput((Integer) snap.get("cursor"), (Boolean) snap.get("done"),
            (Integer) snap.get("droppedLogs"), (Integer) snap.get("elapsedMs"), (String) snap.get("error"),
            (String) snap.get("jobId"), (List<String>) snap.get("logs"), resultRecord, statusEnum,
            (Boolean) snap.get("success"), (String) snap.get("summary"), (String) snap.get("type"));
    }

    /** Build the credential-bearing AccountRequest from the typed save input. The password flows
     * straight into {@link AccountRequest} (whose toString redacts it) and is encrypted at rest. */
    private static AccountRpc.AccountRequest toAccountRequest(EmailAccountSaveInput input) {
        return new AccountRpc.AccountRequest(toLong(input.id()), input.displayName(), input.email(), input.password(),
            input.smtpHost(), input.smtpPort() == null ? 0 : input.smtpPort(), input.smtpSecurity(),
            input.imapHost(), input.imapPort(), input.imapSecurity(),
            input.smtpSkipCertVerify() == null ? false : input.smtpSkipCertVerify(),
            input.imapSkipCertVerify() == null ? false : input.imapSkipCertVerify(),
            input.defaultAccount() == null ? false : input.defaultAccount());
    }

    private EmailMessageRequest message(EmailSendSingleInput input) {
        return messageOf(input.accountId(), input.to(), input.cc(), input.bcc(), input.subject(),
            input.plainText(), input.htmlText(), input.attachments());
    }

    private EmailMessageRequest message(EmailSendBatchInput input) {
        // Batch messages carry the shared body only; recipients come from the group tags and the
        // attachment files come from the input directory, so to/cc/bcc/attachments are empty here.
        return messageOf(input.accountId(), List.of(), List.of(), List.of(), input.subject(),
            input.plainText(), input.htmlText(), List.of());
    }

    private EmailMessageRequest message(EmailBatchPreviewInput input) {
        // A preview reuses the batch message shape (shared body, group-tag recipients, directory
        // attachments) so the planner computes the exact plan that prepare would later confirm.
        return messageOf(input.accountId(), List.of(), List.of(), List.of(), input.subject(),
            input.plainText(), input.htmlText(), List.of());
    }

    private EmailMessageRequest messageOf(long accountId, List<String> to, List<String> cc, List<String> bcc,
            String subject, String plainText, String htmlText, List<String> attachments) {
        String html = htmlSanitizer.sanitize(htmlText);
        String plain = plainText;
        if (plain == null || plain.isBlank()) plain = htmlSanitizer.toPlainText(html);
        return new EmailMessageRequest(accountId, orEmpty(to), orEmpty(cc), orEmpty(bcc), subject,
            plain, html, paths(attachments));
    }

    private List<Path> paths(List<String> values) {
        List<String> safe = orEmpty(values);
        List<Path> result = new ArrayList<>(safe.size());
        for (String item : safe) result.add(path(item, "attachment", "file"));
        return List.copyOf(result);
    }

    private Path path(String resolved, String field, String expectedKind) {
        if (resolved != null && !resolved.isBlank()) return Path.of(resolved);
        throw new IllegalArgumentException(MSGS.format("em.err.fieldRequired", field));
    }

    private static ImportOptions importOptions(String duplicateMode, String tagDelimiter) {
        return new ImportOptions(duplicateMode, tagDelimiter);
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    /** ISO-8601 string for a temporal (Instant/LocalDateTime), or null — mirroring the prior jsonValue
     *  normalisation that emitted {@code value.toString()} for every TemporalAccessor. */
    private static String str(TemporalAccessor value) { return value == null ? null : value.toString(); }

    private static <T> List<T> orEmpty(List<T> values) { return values == null ? List.of() : values; }

    private static int orZero(Integer value) { return value == null ? 0 : value; }

    private static int orDefault(Integer value, int fallback) { return value == null ? fallback : value; }

    /** Widen a nullable generated {@code Integer} id/accountId to the {@code Long} the service layer
     * expects (save/create inputs declare {@code id} nullable → {@code Integer}; reads use {@code int}). */
    private static Long toLong(Integer value) { return value == null ? null : value.longValue(); }

    private static String requiredField(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(MSGS.format("em.err.fieldRequired", field));
        return value;
    }

    private static Set<Long> longSet(List<Integer> values) {
        if (values == null) return Set.of();
        return values.stream().map(Number::longValue)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // ── inlined {success, summary, ...} envelope (was PluginHandlerSupport) ──

    /** Resolve a message-bundle key for the current worker locale with positional interpolation. */
    private static String t(String key, Object... args) { return MSGS.format(key, args); }

    /**
     * Run an operation that may throw, flattening it into the result envelope so a thrown exception
     * escapes as a typed {@code <Method>Output} failure (success=false) rather than an RPC error.
     * {@code onFailure} builds that method's specific Output record with the error summary and null
     * payloads. Logs only the exception <em>type</em> — never the message or stack — to keep
     * request-carried values (which a transport/parse exception message can echo) out of the shared
     * log channel.
     */
    private <T> T result(RpcContext ctx, ThrowingSupplier<T> operation, Function<String, T> onFailure) {
        try {
            return operation.get();
        } catch (RpcException e) {
            // Semantic/transport errors (notably CANCELLED from throwIfCancelled) must propagate to
            // the dispatch loop so a $/cancelRequest yields a clean CANCELLED response — never
            // swallowed into a success=false envelope. Mirrors OfflinePythonRpcHandlers.run().
            throw e;
        } catch (Exception error) {
            ctx.logger().warn("email operation failed: {}", error.getClass().getSimpleName());
            return onFailure.apply(safeMessage(error));
        }
    }

    /** One-line, throwable→message conversion that strips newlines so the summary stays single-line. */
    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return DEFAULT_FAILURE_SUMMARY;
        return message.replace('\r', ' ').replace('\n', ' ');
    }

    /** An operation that may throw a checked exception, so handlers can call IO methods directly. */
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
