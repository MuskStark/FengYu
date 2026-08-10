package fan.summer.fengyu.plugin.email.rpc;

import com.google.gson.Gson;
import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.ArchiveRequest;
import fan.summer.fengyu.plugin.email.model.EmailMessageRequest;
import fan.summer.fengyu.plugin.email.model.PendingSend;
import fan.summer.fengyu.plugin.email.repository.MassConfigRepository;
import fan.summer.fengyu.plugin.email.service.AccountService;
import fan.summer.fengyu.plugin.email.service.AddressBookService;
import fan.summer.fengyu.plugin.email.service.ContactImporter;
import fan.summer.fengyu.plugin.email.service.EmailArchiveService;
import fan.summer.fengyu.plugin.email.service.EmailHtmlSanitizer;
import fan.summer.fengyu.plugin.email.service.EmailSendService;
import fan.summer.fengyu.plugin.email.service.PendingSendService;
import fan.summer.fengyu.plugin.email.model.ContactImport.ImportOptions;
import fan.summer.fengyu.sdk.FileRef;
import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.Jobs;
import fan.summer.fengyu.sdk.PluginHandlerSupport;
import fan.summer.fengyu.sdk.PluginMessages;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adapts email services to official SDK handlers without owning any transport logic.
 *
 * <p>Entry/exit/failure logging, the {success, summary, ...} envelope and the {@code result()}
 * / {@code failure()} / {@code cast()} / {@code safeMessage()} helpers are inherited from
 * {@link PluginHandlerSupport}. Email additionally overrides {@code ok(summary,key,value)} so it
 * can JSON-encode records/Path/Instant values via {@link #jsonValue(Object)} before they leave the
 * worker. Register handlers via {@code worker.on("m", handlers.handle("m", handlers::m))}.
 */
public final class EmailRpcHandlers extends PluginHandlerSupport {
    /** Static message resolver for the static param/path-validation helpers (which cannot reach the
     * inherited instance {@link #msgs}). Resolves the same bundle the handler uses. */
    private static final PluginMessages MSGS =
            PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, EmailRpcHandlers.class);
    private final Gson json = new Gson();
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
        super("email");
        accounts = new AccountRpc(new AccountService(database, cipher));
        addressBook = new AddressBookRpc(new AddressBookService(database));
        importer = new ContactImporter(database);
        configs = new MassConfigRepository(database);
        sends = new EmailSendService(database, cipher);
        pending = new PendingSendService(database, sends);
        archive = new EmailArchiveService(database, cipher);
    }

    public Object listAccounts(Map<String, Object> params) {
        return result(() -> {
            var values = accounts.list();
            return ok(t("em.account.found", values.size()), "accounts", values);
        });
    }

    public Object findAccount(Map<String, Object> params) {
        return result(() -> accounts.find(requiredLong(params, "id"))
            .map(value -> ok(t("em.account.foundOne"), "account", value))
            .orElseGet(() -> failKey("em.account.notFound")));
    }

    public Object saveAccount(Map<String, Object> params) {
        return result(() -> ok(t("em.account.saved"), "account",
            accounts.save(json.fromJson(json.toJson(params), AccountRpc.AccountRequest.class))));
    }

    public Object deleteAccount(Map<String, Object> params) {
        return result(() -> accounts.delete(requiredLong(params, "id"))
            ? okKey("em.account.deleted") : failKey("em.account.notFound"));
    }

    public Object setDefaultAccount(Map<String, Object> params) {
        return result(() -> accounts.setDefault(requiredLong(params, "id"))
            ? okKey("em.account.defaultUpdated") : failKey("em.account.notFound"));
    }

    public Object testAccount(Map<String, Object> params) {
        return result(() -> {
            long accountId = requiredLong(params, "accountId");
            var value = sends.testSmtp(accountId);
            log.info("SMTP test for account {}: {}", accountId, value.success() ? "succeeded" : "failed");
            return value.success() ? okKey("em.account.smtpSucceeded") : failure(value.errorMessage());
        });
    }

    public Object testImapAccount(Map<String, Object> params) {
        return result(() -> {
            long accountId = requiredLong(params, "accountId");
            var value = archive.testImap(accountId);
            log.info("IMAP test for account {}: {}", accountId, value.success() ? "succeeded" : "failed");
            return value.success() ? okKey("em.account.imapSucceeded") : failure(value.errorMessage());
        });
    }

    public Object listFolders(Map<String, Object> params) {
        return result(() -> {
            long accountId = requiredLong(params, "accountId");
            var value = archive.listFolders(accountId);
            log.info("IMAP folder list for account {}: {} folder(s)", accountId, value.folders().size());
            return ok(t("em.folder.found", value.folders().size()), "folders", value.folders());
        });
    }

    public Object queryContacts(Map<String, Object> params) {
        return result(() -> {
            var values = addressBook.search(new AddressBookRpc.SearchRequest(string(params, "query"),
                longSet(params.get("tagIds")), integer(params, "offset", 0), integer(params, "limit", 50)));
            return ok(t("em.contact.found", values.size()), "contacts", values);
        });
    }

    public Object findContact(Map<String, Object> params) {
        return result(() -> addressBook.findContact(requiredLong(params, "id"))
            .map(value -> ok(t("em.contact.foundOne"), "contact", value))
            .orElseGet(() -> failKey("em.contact.notFound")));
    }

    public Object saveContact(Map<String, Object> params) {
        return result(() -> ok(t("em.contact.saved"), "contact", addressBook.saveContact(
            json.fromJson(json.toJson(params), AddressBookRpc.ContactRequest.class))));
    }

    public Object deleteContact(Map<String, Object> params) {
        return result(() -> addressBook.deleteContact(requiredLong(params, "id"))
            ? okKey("em.contact.deleted") : failKey("em.contact.notFound"));
    }

    public Object listTags(Map<String, Object> params) {
        return result(() -> {
            var tags = addressBook.listTags();
            return ok(t("em.tag.found", tags.size()), "tags", tags);
        });
    }

    public Object saveTag(Map<String, Object> params) {
        return result(() -> ok(t("em.tag.saved"), "tag", addressBook.saveTag(
            json.fromJson(json.toJson(params), AddressBookRpc.TagRequest.class))));
    }

    public Object deleteTag(Map<String, Object> params) {
        return result(() -> addressBook.deleteTag(requiredLong(params, "id"))
            ? okKey("em.tag.deleted") : failKey("em.tag.notFound"));
    }

    public Object assignTags(Map<String, Object> params) {
        return result(() -> {
            addressBook.assignTags(new AddressBookRpc.BulkTagRequest(
                longSet(params.get("contactIds")), longSet(params.get("tagIds"))));
            return okKey("em.contact.tagsUpdated");
        });
    }

    public Object resolveRecipients(Map<String, Object> params) {
        return result(() -> {
            Set<String> recipients = addressBook.resolveRecipients(longSet(params.get("tagIds")));
            return ok(t("em.contact.resolvedRecipients", recipients.size()), "recipients", recipients);
        });
    }

    public Object importContactsPreview(Map<String, Object> params) {
        return result(() -> {
            ImportOptions options = importOptions(params);
            var preview = importer.preview(
                path(params.get("sourceFile"), "sourceFile", "file"), options);
            return ok(t("em.contact.importPreview", preview.rowsTotal(), preview.createdContacts(),
                preview.mergedContacts(), preview.skippedContacts(), preview.createdTags().size(),
                preview.errors().size()), "preview", preview);
        });
    }

    public Object importContactsCommit(Map<String, Object> params) {
        return result(() -> {
            ImportOptions options = importOptions(params);
            var outcome = importer.commit(
                path(params.get("sourceFile"), "sourceFile", "file"), options);
            return outcome.errors().isEmpty()
                ? ok(t("em.contact.imported", outcome.created(), outcome.merged(), outcome.skipped(),
                    outcome.tagsCreated(), outcome.tagsAssigned()), "result", outcome)
                : ok(t("em.contact.importedWithErrors", outcome.created(), outcome.merged(),
                    outcome.skipped(), outcome.tagsCreated(), outcome.tagsAssigned(),
                    outcome.errors().size()), "result", outcome);
        });
    }

    private ImportOptions importOptions(Map<String, Object> params) {
        return new ImportOptions(string(params, "duplicateMode"), string(params, "tagDelimiter"));
    }

    public Object listConfigs(Map<String, Object> params) {
        return result(() -> {
            var values = configs.list();
            return ok(t("em.batch.foundConfigs", values.size()), "configs", values);
        });
    }

    public Object findConfig(Map<String, Object> params) {
        return result(() -> configs.find(requiredLong(params, "id"))
            .map(value -> ok(t("em.batch.configFound"), "config", value))
            .orElseGet(() -> failKey("em.batch.configNotFound")));
    }

    public Object saveConfig(Map<String, Object> params) {
        return result(() -> {
            Long id = optionalLong(params, "id");
            long saved = configs.save(id, requiredString(params, "name"), requiredString(params, "mode"),
                requiredString(params, "configJson"));
            return ok(t("em.batch.configSaved"), "config", configs.find(saved).orElseThrow());
        });
    }

    public Object deleteConfig(Map<String, Object> params) {
        return result(() -> configs.delete(requiredLong(params, "id"))
            ? okKey("em.batch.configDeleted") : failKey("em.batch.configNotFound"));
    }

    public Object prepareSingle(Map<String, Object> params) {
        return result(() -> {
            EmailMessageRequest request = message(params);
            Set<Long> recipientTagIds = longSet(params.get("recipientTagIds"));
            if (!recipientTagIds.isEmpty()) {
                return confirmation(t("em.send.taggedReady"),
                    pending.prepareComposeByTags(request, recipientTagIds));
            }
            if (request.to().isEmpty()) throw new IllegalArgumentException(t("em.err.toRequiredForDirect"));
            return confirmation(t("em.send.singleReady"), pending.prepareSingle(request));
        });
    }

    public Object prepareBatch(Map<String, Object> params) {
        return result(() -> confirmation(t("em.send.batchReady"),
            pending.prepareAttachmentBatch(message(params),
                path(params.get("inputDirectory"), "inputDirectory", "directory"),
                paths(params.get("commonAttachments")), longSet(params.get("recipientGroupTagIds")),
                longSet(params.get("ccGroupTagIds")))));
    }

    public Object previewBatch(Map<String, Object> params) {
        return result(() -> ok(t("em.batch.preview"), "preview", pending.previewBatch(message(params),
            path(params.get("inputDirectory"), "inputDirectory", "directory"),
            paths(params.get("commonAttachments")), longSet(params.get("recipientGroupTagIds")),
            longSet(params.get("ccGroupTagIds")))));
    }

    public Object sendStatus(Map<String, Object> params) {
        return result(() -> pending.status(requiredString(params, "confirmationId"))
            .map(value -> ok(t("em.send.statusIs", value.status()), "send", sendView(value)))
            .orElseGet(() -> failKey("em.send.confirmationNotFound")));
    }

    public Object querySendRecords(Map<String, Object> params) {
        return result(() -> {
            var records = pending.records(string(params, "taskStatus"), string(params, "confirmationId"),
                string(params, "messageStatus"), string(params, "query"), integer(params, "offset", 0),
                integer(params, "limit", 50));
            Map<String, Object> value = okKey("em.send.foundTasks", records.tasks().size());
            value.put("tasks", jsonValue(records.tasks()));
            value.put("messages", jsonValue(records.messages()));
            return value;
        });
    }

    public Object confirmSend(Map<String, Object> params) {
        return result(() -> {
            String confirmationId = requiredString(params, "confirmationId");
            var value = pending.confirm(confirmationId);
            log.info("send {} confirmed: {}", confirmationId, value.status());
            return ok(t("em.send.confirmationIs", value.status()), "send", value);
        });
    }

    public Object rejectSend(Map<String, Object> params) {
        return result(() -> {
            String confirmationId = requiredString(params, "confirmationId");
            var value = pending.reject(confirmationId);
            log.info("send {} rejected: {}", confirmationId, value.status());
            return ok(t("em.send.confirmationIs", value.status()), "send", value);
        });
    }

    public Object collect(Map<String, Object> params) {
        return result(() -> {
            long accountId = requiredLong(params, "accountId");
            String folder = requiredString(params, "folder");
            ArchiveRequest request = new ArchiveRequest(accountId, folder,
                instant(params, "start"), instant(params, "end"),
                path(params.get("outputDirectory"), "outputDirectory", "directory"));
            var value = archive.collect(request, ignored -> { });
            log.info("archived {} new, {} duplicate(s), {} failure(s) from account {} folder '{}'",
                value.newArchived(), value.skippedDuplicates(), value.failures(), accountId, folder);
            return ok(t("em.archive.archived", value.newArchived(), value.skippedDuplicates(), value.failures()),
                "collection", value);
        });
    }

    /**
     * Launch archive collection on a virtual thread so it survives beyond the host's per-RPC timeout.
     * Returns a {@code jobId} immediately; the UI polls {@link #collectStatus} to drain progress and
     * fetch the final result. The existing per-message {@link EmailArchiveService.ProgressSink} is
     * forwarded into the job's streamed log lines, so each poll sees the latest archived/skipped/failed
     * counts without holding the RPC open. Mirrors the Excel {@code split_start}/{@code split_status}
     * async trio built on the SDK {@link Jobs} registry.
     */
    public Object collectStart(Map<String, Object> params) {
        return result(() -> {
            long accountId = requiredLong(params, "accountId");
            String folder = requiredString(params, "folder");
            ArchiveRequest request = new ArchiveRequest(accountId, folder,
                instant(params, "start"), instant(params, "end"),
                path(params.get("outputDirectory"), "outputDirectory", "directory"));
            Jobs.Job job = jobs.start("ARCHIVE", handle -> {
                try {
                    var value = archive.collect(request, progress -> handle.log(
                        progress.completed() + "/" + progress.total() + " new=" + progress.newArchived()
                        + " skipped=" + progress.skippedDuplicates() + " failed=" + progress.failures()));
                    handle.setSummary(value);
                    log.info("archived {} new, {} duplicate(s), {} failure(s) from account {} folder '{}'",
                        value.newArchived(), value.skippedDuplicates(), value.failures(), accountId, folder);
                } catch (Exception e) {
                    // Jobs.start flattens exceptions to a one-line markFailed without a stack trace;
                    // log the full stack here so the host log surface has diagnostics, then rethrow.
                    log.error("archive job failed for account {} folder '{}': {}", accountId, folder, e.toString(), e);
                    throw e;
                }
            });
            return ok(t("em.archive.started"), "jobId", job.id);
        });
    }

    public Object collectStatus(Map<String, Object> params) {
        return result(() -> {
            String jobId = requiredString(params, "jobId");
            int cursor = JsonRpcWorker.integer(params, "cursor", 0);
            return jobs.snapshot(jobId, cursor);
        });
    }

    public Object collectCancel(Map<String, Object> params) {
        return result(() -> {
            String jobId = requiredString(params, "jobId");
            if (!jobs.cancel(jobId)) return failKey("em.archive.jobNotRunning", jobId);
            return ok(t("em.archive.cancelRequested"), "jobId", jobId);
        });
    }

    public Object queryArchive(Map<String, Object> params) {
        return result(() -> {
            var values = archive.search(new EmailArchiveService.SearchFilter(optionalLong(params, "accountId"),
                string(params, "folder"), string(params, "sender"), string(params, "subject"),
                instant(params, "start"), instant(params, "end"), integer(params, "offset", 0),
                integer(params, "limit", 50)));
            return ok(t("em.archive.found", values.size()), "messages", values);
        });
    }

    public Object archiveDetail(Map<String, Object> params) {
        return result(() -> archive.detail(requiredLong(params, "id"))
            .map(value -> ok(t("em.archive.messageFound"), "message", value))
            .orElseGet(() -> failKey("em.archive.messageNotFound")));
    }

    private Map<String, Object> confirmation(String summary, PendingSendService.ConfirmationEnvelope envelope) {
        Map<String, Object> value = ok(summary);
        value.put("confirmation_required", envelope.confirmationRequired());
        value.put("confirmation", jsonValue(envelope.confirmation()));
        return value;
    }

    private EmailMessageRequest message(Map<String, Object> params) {
        String html = htmlSanitizer.sanitize(string(params, "htmlText"));
        String plain = string(params, "plainText");
        if (plain == null || plain.isBlank()) plain = htmlSanitizer.toPlainText(html);
        return new EmailMessageRequest(requiredLong(params, "accountId"), strings(params.get("to")),
            strings(params.get("cc")), strings(params.get("bcc")), string(params, "subject"),
            plain, html, paths(params.get("attachments")));
    }

    private List<Path> paths(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        List<Path> result = new ArrayList<>(values.size());
        for (Object item : values) result.add(path(item, "attachment", "file"));
        return List.copyOf(result);
    }

    private Path path(Object value, String field, String expectedKind) {
        if (value instanceof String resolved && !resolved.isBlank()) return Path.of(resolved);
        if (value instanceof Map<?, ?>) {
            FileRef reference = json.fromJson(json.toJson(value), FileRef.class);
            if (!expectedKind.equals(reference.kind())) {
                throw new IllegalArgumentException(MSGS.format("em.err.fileRefKind", field, expectedKind));
            }
            throw new IllegalArgumentException(MSGS.format("em.err.fileRefUnresolved", field));
        }
        throw new IllegalArgumentException(MSGS.format("em.err.fieldRequired", field));
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

    /** Overrides the base envelope to JSON-encode records/Path/Instant values before they leave. */
    @Override
    protected Map<String, Object> ok(String summary, String key, Object value) {
        Map<String, Object> result = ok(summary);
        result.put(key, jsonValue(value));
        return result;
    }

    private Object jsonValue(Object value) {
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

    private static String string(Map<String, Object> params, String key) {
        return JsonRpcWorker.string(params, key);
    }

    private static String requiredString(Map<String, Object> params, String key) {
        String value = string(params, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(MSGS.format("em.err.fieldRequired", key));
        return value;
    }

    private static long requiredLong(Map<String, Object> params, String key) {
        Long value = optionalLong(params, key);
        if (value == null) throw new IllegalArgumentException(MSGS.format("em.err.fieldRequired", key));
        return value;
    }

    private static Long optionalLong(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(value.toString()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(MSGS.format("em.err.fieldMustBeInteger", key)); }
    }

    private static int integer(Map<String, Object> params, String key, int fallback) {
        return JsonRpcWorker.integer(params, key, fallback);
    }

    private static Instant instant(Map<String, Object> params, String key) {
        String value = string(params, key);
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).toList();
    }

    private static Set<String> stringSet(Object value) { return Set.copyOf(strings(value)); }

    private static Set<Long> longSet(Object value) {
        if (!(value instanceof List<?> values)) return Set.of();
        return values.stream().map(item -> {
            if (item instanceof Number number) return number.longValue();
            return Long.parseLong(item.toString());
        }).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
