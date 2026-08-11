package fan.summer.fengyu.plugin.email.rpc;

import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.ArchiveRequest;
import fan.summer.fengyu.plugin.email.model.EmailMessageRequest;
import fan.summer.fengyu.plugin.email.model.PendingSend;
import fan.summer.fengyu.plugin.email.model.ContactImport.ImportOptions;
import fan.summer.fengyu.plugin.email.repository.MassConfigRepository;
import fan.summer.fengyu.plugin.email.service.AccountService;
import fan.summer.fengyu.plugin.email.service.AddressBookService;
import fan.summer.fengyu.plugin.email.service.ContactImporter;
import fan.summer.fengyu.plugin.email.service.EmailArchiveService;
import fan.summer.fengyu.plugin.email.service.EmailHtmlSanitizer;
import fan.summer.fengyu.plugin.email.service.EmailSendService;
import fan.summer.fengyu.plugin.email.service.PendingSendService;
import fan.summer.fengyu.sdk.Jobs;
import fan.summer.fengyu.sdk.PluginMessages;
import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.email.generated.ConfirmSendInput;
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
import fan.summer.email.generated.RejectSendInput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed RPC handlers for the Email Center worker, registered through the SDK's typed
 * {@code worker.method(PluginMethods.X, XInput.class, Object.class, h::x)} API in
 * {@link fan.summer.fengyu.plugin.email.EmailWorkerMain}. Each handler reads its fields off a
 * generated Input record (no raw {@code Map} parsing, no removed
 * {@code JsonRpcWorker.string/integer} extraction) and returns the existing {success, summary, ...}
 * envelope; Gson serializes that envelope directly, so records/Path/Instant values are still
 * normalised through {@link #jsonValue} before they leave the worker. The class no longer extends
 * {@code PluginHandlerSupport}: the envelope builders and failure-flattening {@link #result} wrapper
 * are inlined here, mirroring how {@code MarkdownRpcHandlers} drops the base class and assembles its
 * typed output directly.
 *
 * <p><b>Output shape.</b> Handlers return the rich {success, summary, &lt;payload&gt;} envelope
 * {@link Map} rather than the generated {@code <Method>Output} record. The manifest declares each
 * payload (accounts, contacts, tags, ...) as a free-form {@code object} "carried in the runtime
 * result", so the generator emits empty nested records / omits the data field entirely — returning
 * those records would drop every list and detail payload the UI needs. The SDK treats the registered
 * {@code outputClass} as documentary ("the returned value is serialized by Gson regardless of its
 * declared type"), so {@code Object.class} is registered and the full envelope Map is returned.
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
 * the credential-free {@link AccountService.AccountView}. The typed registration never logs params
 * (the SDK entry path logs method identity only). The {@link #result} wrapper logs only the exception
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

    public Map<String, Object> listAccounts(EmailAccountsListInput input, RpcContext ctx) {
        return result(ctx, () -> {
            var values = accounts.list();
            return ok(t("em.account.found", values.size()), "accounts", values);
        });
    }

    public Map<String, Object> findAccount(EmailAccountFindInput input, RpcContext ctx) {
        return result(ctx, () -> accounts.find(input.id())
            .map(value -> ok(t("em.account.foundOne"), "account", value))
            .orElseGet(() -> failKey("em.account.notFound")));
    }

    public Map<String, Object> saveAccount(EmailAccountSaveInput input, RpcContext ctx) {
        return result(ctx, () -> ok(t("em.account.saved"), "account",
            accounts.save(toAccountRequest(input))));
    }

    public Map<String, Object> deleteAccount(EmailAccountDeleteInput input, RpcContext ctx) {
        return result(ctx, () -> accounts.delete(input.id())
            ? okKey("em.account.deleted") : failKey("em.account.notFound"));
    }

    public Map<String, Object> setDefaultAccount(EmailAccountSetDefaultInput input, RpcContext ctx) {
        return result(ctx, () -> accounts.setDefault(input.id())
            ? okKey("em.account.defaultUpdated") : failKey("em.account.notFound"));
    }

    public Map<String, Object> testAccount(EmailAccountTestInput input, RpcContext ctx) {
        return result(ctx, () -> {
            // SMTP I/O: honour a transport cancel that arrived before we open the socket.
            ctx.cancellation().throwIfCancelled();
            long accountId = input.accountId();
            var value = sends.testSmtp(accountId);
            ctx.logger().info("SMTP test for account {}: {}", accountId, value.success() ? "succeeded" : "failed");
            return value.success() ? okKey("em.account.smtpSucceeded") : failure(value.errorMessage());
        });
    }

    public Map<String, Object> testImapAccount(EmailAccountTestImapInput input, RpcContext ctx) {
        return result(ctx, () -> {
            // IMAP I/O: honour a transport cancel that arrived before we open the socket.
            ctx.cancellation().throwIfCancelled();
            long accountId = input.accountId();
            var value = archive.testImap(accountId);
            ctx.logger().info("IMAP test for account {}: {}", accountId, value.success() ? "succeeded" : "failed");
            return value.success() ? okKey("em.account.imapSucceeded") : failure(value.errorMessage());
        });
    }

    public Map<String, Object> listFolders(EmailImapFoldersInput input, RpcContext ctx) {
        return result(ctx, () -> {
            // IMAP I/O: honour a transport cancel that arrived before we list folders.
            ctx.cancellation().throwIfCancelled();
            long accountId = input.accountId();
            var value = archive.listFolders(accountId);
            ctx.logger().info("IMAP folder list for account {}: {} folder(s)", accountId, value.folders().size());
            return ok(t("em.folder.found", value.folders().size()), "folders", value.folders());
        });
    }

    // ── contacts / tags / address book ──────────────────────────────────────

    public Map<String, Object> queryContacts(EmailContactsQueryInput input, RpcContext ctx) {
        return result(ctx, () -> {
            var values = addressBook.search(new AddressBookRpc.SearchRequest(input.query(),
                longSet(input.tagIds()), orZero(input.offset()), orDefault(input.limit(), 50)));
            return ok(t("em.contact.found", values.size()), "contacts", values);
        });
    }

    public Map<String, Object> findContact(EmailContactFindInput input, RpcContext ctx) {
        return result(ctx, () -> addressBook.findContact(input.id())
            .map(value -> ok(t("em.contact.foundOne"), "contact", value))
            .orElseGet(() -> failKey("em.contact.notFound")));
    }

    public Map<String, Object> saveContact(EmailContactSaveInput input, RpcContext ctx) {
        return result(ctx, () -> ok(t("em.contact.saved"), "contact", addressBook.saveContact(
            new AddressBookRpc.ContactRequest(toLong(input.id()), input.email(), input.nickname(),
                input.notes(), longSet(input.tagIds())))));
    }

    public Map<String, Object> deleteContact(EmailContactDeleteInput input, RpcContext ctx) {
        return result(ctx, () -> addressBook.deleteContact(input.id())
            ? okKey("em.contact.deleted") : failKey("em.contact.notFound"));
    }

    public Map<String, Object> listTags(EmailTagsListInput input, RpcContext ctx) {
        return result(ctx, () -> {
            var tags = addressBook.listTags();
            return ok(t("em.tag.found", tags.size()), "tags", tags);
        });
    }

    public Map<String, Object> saveTag(EmailTagSaveInput input, RpcContext ctx) {
        return result(ctx, () -> ok(t("em.tag.saved"), "tag",
            addressBook.saveTag(new AddressBookRpc.TagRequest(toLong(input.id()), input.name()))));
    }

    public Map<String, Object> deleteTag(EmailTagDeleteInput input, RpcContext ctx) {
        return result(ctx, () -> addressBook.deleteTag(input.id())
            ? okKey("em.tag.deleted") : failKey("em.tag.notFound"));
    }

    public Map<String, Object> assignTags(EmailTagsAssignInput input, RpcContext ctx) {
        return result(ctx, () -> {
            addressBook.assignTags(new AddressBookRpc.BulkTagRequest(
                longSet(input.contactIds()), longSet(input.tagIds())));
            return okKey("em.contact.tagsUpdated");
        });
    }

    public Map<String, Object> resolveRecipients(EmailTagsResolveInput input, RpcContext ctx) {
        return result(ctx, () -> {
            Set<String> recipients = addressBook.resolveRecipients(longSet(input.tagIds()));
            return ok(t("em.contact.resolvedRecipients", recipients.size()), "recipients", recipients);
        });
    }

    public Map<String, Object> importContactsPreview(EmailContactsImportPreviewInput input, RpcContext ctx) {
        return result(ctx, () -> {
            ImportOptions options = importOptions(input.duplicateMode(), input.tagDelimiter());
            var preview = importer.preview(path(input.sourceFile(), "sourceFile", "file"), options);
            return ok(t("em.contact.importPreview", preview.rowsTotal(), preview.createdContacts(),
                preview.mergedContacts(), preview.skippedContacts(), preview.createdTags().size(),
                preview.errors().size()), "preview", preview);
        });
    }

    public Map<String, Object> importContactsCommit(EmailContactsImportCommitInput input, RpcContext ctx) {
        return result(ctx, () -> {
            ImportOptions options = importOptions(input.duplicateMode(), input.tagDelimiter());
            var outcome = importer.commit(path(input.sourceFile(), "sourceFile", "file"), options);
            return outcome.errors().isEmpty()
                ? ok(t("em.contact.imported", outcome.created(), outcome.merged(), outcome.skipped(),
                    outcome.tagsCreated(), outcome.tagsAssigned()), "result", outcome)
                : ok(t("em.contact.importedWithErrors", outcome.created(), outcome.merged(),
                    outcome.skipped(), outcome.tagsCreated(), outcome.tagsAssigned(),
                    outcome.errors().size()), "result", outcome);
        });
    }

    // ── batch-send configuration templates ──────────────────────────────────

    public Map<String, Object> listConfigs(EmailConfigsListInput input, RpcContext ctx) {
        return result(ctx, () -> {
            var values = configs.list();
            return ok(t("em.batch.foundConfigs", values.size()), "configs", values);
        });
    }

    public Map<String, Object> findConfig(EmailConfigFindInput input, RpcContext ctx) {
        return result(ctx, () -> configs.find(input.id())
            .map(value -> ok(t("em.batch.configFound"), "config", value))
            .orElseGet(() -> failKey("em.batch.configNotFound")));
    }

    public Map<String, Object> saveConfig(EmailConfigSaveInput input, RpcContext ctx) {
        return result(ctx, () -> {
            long saved = configs.save(toLong(input.id()), requiredField(input.name(), "name"),
                requiredField(input.mode(), "mode"), requiredField(input.configJson(), "configJson"));
            return ok(t("em.batch.configSaved"), "config", configs.find(saved).orElseThrow());
        });
    }

    public Map<String, Object> deleteConfig(EmailConfigDeleteInput input, RpcContext ctx) {
        return result(ctx, () -> configs.delete(input.id())
            ? okKey("em.batch.configDeleted") : failKey("em.batch.configNotFound"));
    }

    // ── send: confirmation-first prepare → confirm/reject → status/records ──

    public Map<String, Object> prepareSingle(EmailSendSingleInput input, RpcContext ctx) {
        return result(ctx, () -> {
            EmailMessageRequest request = message(input);
            Set<Long> recipientTagIds = longSet(input.recipientTagIds());
            if (!recipientTagIds.isEmpty()) {
                return confirmation(t("em.send.taggedReady"),
                    pending.prepareComposeByTags(request, recipientTagIds));
            }
            if (request.to().isEmpty()) throw new IllegalArgumentException(t("em.err.toRequiredForDirect"));
            return confirmation(t("em.send.singleReady"), pending.prepareSingle(request));
        });
    }

    public Map<String, Object> prepareBatch(EmailSendBatchInput input, RpcContext ctx) {
        return result(ctx, () -> confirmation(t("em.send.batchReady"),
            pending.prepareAttachmentBatch(message(input),
                path(input.inputDirectory(), "inputDirectory", "directory"),
                paths(input.commonAttachments()), longSet(input.recipientGroupTagIds()),
                longSet(input.ccGroupTagIds()))));
    }

    public Map<String, Object> previewBatch(EmailBatchPreviewInput input, RpcContext ctx) {
        return result(ctx, () -> ok(t("em.batch.preview"), "preview", pending.previewBatch(message(input),
            path(input.inputDirectory(), "inputDirectory", "directory"),
            paths(input.commonAttachments()), longSet(input.recipientGroupTagIds()),
            longSet(input.ccGroupTagIds()))));
    }

    public Map<String, Object> sendStatus(EmailSendStatusInput input, RpcContext ctx) {
        return result(ctx, () -> pending.status(requiredField(input.confirmationId(), "confirmationId"))
            .map(value -> ok(t("em.send.statusIs", value.status()), "send", sendView(value)))
            .orElseGet(() -> failKey("em.send.confirmationNotFound")));
    }

    public Map<String, Object> querySendRecords(EmailSendRecordsQueryInput input, RpcContext ctx) {
        return result(ctx, () -> {
            var records = pending.records(input.taskStatus(), input.confirmationId(),
                input.messageStatus(), input.query(), orZero(input.offset()), orDefault(input.limit(), 50));
            Map<String, Object> value = okKey("em.send.foundTasks", records.tasks().size());
            value.put("tasks", jsonValue(records.tasks()));
            value.put("messages", jsonValue(records.messages()));
            return value;
        });
    }

    public Map<String, Object> confirmSend(ConfirmSendInput input, RpcContext ctx) {
        return result(ctx, () -> {
            // SMTP send: honour a transport cancel that arrived before the confirmed dispatch.
            ctx.cancellation().throwIfCancelled();
            String confirmationId = requiredField(input.confirmationId(), "confirmationId");
            var value = pending.confirm(confirmationId);
            ctx.logger().info("send {} confirmed: {}", confirmationId, value.status());
            return ok(t("em.send.confirmationIs", value.status()), "send", value);
        });
    }

    public Map<String, Object> rejectSend(RejectSendInput input, RpcContext ctx) {
        return result(ctx, () -> {
            String confirmationId = requiredField(input.confirmationId(), "confirmationId");
            var value = pending.reject(confirmationId);
            ctx.logger().info("send {} rejected: {}", confirmationId, value.status());
            return ok(t("em.send.confirmationIs", value.status()), "send", value);
        });
    }

    // ── archive (IMAP collection): synchronous fetch + async job trio ───────

    public Map<String, Object> collect(EmailArchiveFetchInput input, RpcContext ctx) {
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
            return ok(t("em.archive.archived", value.newArchived(), value.skippedDuplicates(), value.failures()),
                "collection", value);
        });
    }

    /**
     * Launch archive collection on a virtual thread so it survives beyond the host's per-RPC timeout.
     * Returns a {@code jobId} immediately; the UI polls {@link #collectStatus} to drain progress and
     * fetch the final result, and cancels via {@link #collectCancel} (domain-level job cancel, which
     * interrupts the job thread independent of the launching RPC).
     */
    public Map<String, Object> collectStart(EmailArchiveFetchStartInput input, RpcContext ctx) {
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
            return ok(t("em.archive.started"), "jobId", job.id);
        });
    }

    public Map<String, Object> collectStatus(EmailArchiveFetchStatusInput input, RpcContext ctx) {
        return result(ctx, () -> jobs.snapshot(input.jobId(), orZero(input.cursor())));
    }

    public Map<String, Object> collectCancel(EmailArchiveFetchCancelInput input, RpcContext ctx) {
        return result(ctx, () -> {
            String jobId = input.jobId();
            if (!jobs.cancel(jobId)) return failKey("em.archive.jobNotRunning", jobId);
            return ok(t("em.archive.cancelRequested"), "jobId", jobId);
        });
    }

    public Map<String, Object> queryArchive(EmailArchiveQueryInput input, RpcContext ctx) {
        return result(ctx, () -> {
            var values = archive.search(new EmailArchiveService.SearchFilter(toLong(input.accountId()),
                input.folder(), input.sender(), input.subject(),
                instant(input.start()), instant(input.end()), orZero(input.offset()), orDefault(input.limit(), 50)));
            return ok(t("em.archive.found", values.size()), "messages", values);
        });
    }

    public Map<String, Object> archiveDetail(EmailArchiveDetailInput input, RpcContext ctx) {
        return result(ctx, () -> archive.detail(input.id())
            .map(value -> ok(t("em.archive.messageFound"), "message", value))
            .orElseGet(() -> failKey("em.archive.messageNotFound")));
    }

    // ── envelope + conversion helpers ───────────────────────────────────────

    private static Map<String, Object> confirmation(String summary, PendingSendService.ConfirmationEnvelope envelope) {
        Map<String, Object> value = ok(summary);
        value.put("confirmation_required", envelope.confirmationRequired());
        value.put("confirmation", jsonValue(envelope.confirmation()));
        return value;
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

    private static Map<String, Object> sendView(PendingSend value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("confirmationId", value.confirmationId());
        result.put("accountId", value.accountId());
        result.put("mode", value.mode());
        result.put("status", value.status());
        result.put("expiresAt", value.expiresAt() == null ? null : value.expiresAt().toString());
        result.put("updatedAt", value.updatedAt() == null ? null : value.updatedAt().toString());
        return result;
    }

    // ── inlined {success, summary, ...} envelope (was PluginHandlerSupport) ──

    /** Resolve a message-bundle key for the current worker locale with positional interpolation. */
    private static String t(String key, Object... args) { return MSGS.format(key, args); }

    private static Map<String, Object> ok(String summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("summary", summary);
        return result;
    }

    /** Success envelope with a single result field; the value is JSON-normalised via {@link #jsonValue}. */
    private static Map<String, Object> ok(String summary, String key, Object value) {
        Map<String, Object> result = ok(summary);
        if (key != null) result.put(key, jsonValue(value));
        return result;
    }

    private static Map<String, Object> okKey(String summaryKey, Object... args) {
        return ok(t(summaryKey, args));
    }

    private static Map<String, Object> failKey(String failureKey, Object... args) {
        return failure(t(failureKey, args));
    }

    private static Map<String, Object> failure(String summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("summary", summary == null || summary.isBlank()
            ? DEFAULT_FAILURE_SUMMARY : summary);
        return result;
    }

    /**
     * Run an operation that may throw, flattening it into the result envelope so a thrown exception
     * escapes as a {@code {success:false, summary}} response rather than an RPC error. Logs only the
     * exception <em>type</em> — never the message or stack — to keep request-carried values (which a
     * transport/parse exception message can echo) out of the shared log channel.
     */
    private Map<String, Object> result(RpcContext ctx, ThrowingOperation operation) {
        try {
            return operation.run();
        } catch (Exception error) {
            ctx.logger().warn("email operation failed: {}", error.getClass().getSimpleName());
            return failure(safeMessage(error));
        }
    }

    /** One-line, throwable→message conversion that strips newlines so the summary stays single-line. */
    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return DEFAULT_FAILURE_SUMMARY;
        return message.replace('\r', ' ').replace('\n', ' ');
    }

    /** Normalise records/Path/Instant values to JSON-friendly forms before they leave the worker. */
    private static Object jsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof TemporalAccessor || value instanceof Path) return value.toString();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), jsonValue(item)));
            return result;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> result = new ArrayList<>();
            items.forEach(item -> result.add(jsonValue(item)));
            return List.copyOf(result);
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> result = new LinkedHashMap<>();
            try {
                for (RecordComponent component : value.getClass().getRecordComponents()) {
                    result.put(component.getName(), jsonValue(component.getAccessor().invoke(value)));
                }
                return result;
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(MSGS.format("em.err.couldNotEncode"), error);
            }
        }
        return value.toString();
    }

    /** An operation that may throw a checked exception, so handlers can call IO methods directly. */
    @FunctionalInterface
    private interface ThrowingOperation {
        Map<String, Object> run() throws Exception;
    }
}
