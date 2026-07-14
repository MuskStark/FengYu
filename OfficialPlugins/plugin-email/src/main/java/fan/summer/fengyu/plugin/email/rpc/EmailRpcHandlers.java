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
import fan.summer.fengyu.plugin.email.service.EmailArchiveService;
import fan.summer.fengyu.plugin.email.service.EmailSendService;
import fan.summer.fengyu.plugin.email.service.PendingSendService;
import fan.summer.fengyu.sdk.FileRef;
import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.PluginHandler;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Adapts email services to official SDK handlers without owning any transport logic. */
public final class EmailRpcHandlers {
    private final Gson json = new Gson();
    private final AccountRpc accounts;
    private final AddressBookRpc addressBook;
    private final MassConfigRepository configs;
    private final EmailSendService sends;
    private final PendingSendService pending;
    private final EmailArchiveService archive;

    public EmailRpcHandlers(EmailDatabase database, CredentialCipher cipher) {
        accounts = new AccountRpc(new AccountService(database, cipher));
        addressBook = new AddressBookRpc(new AddressBookService(database));
        configs = new MassConfigRepository(database);
        sends = new EmailSendService(database, cipher);
        pending = new PendingSendService(database, sends);
        archive = new EmailArchiveService(database, cipher);
    }

    public Object listAccounts(Map<String, Object> params) {
        return result(() -> {
            var values = accounts.list();
            return ok("Found " + values.size() + " email account(s)", "accounts", values);
        });
    }

    public Object findAccount(Map<String, Object> params) {
        return result(() -> accounts.find(requiredLong(params, "id"))
            .map(value -> ok("Email account found", "account", value))
            .orElseGet(() -> failure("Email account not found")));
    }

    public Object saveAccount(Map<String, Object> params) {
        return result(() -> ok("Email account saved", "account",
            accounts.save(json.fromJson(json.toJson(params), AccountRpc.AccountRequest.class))));
    }

    public Object deleteAccount(Map<String, Object> params) {
        return result(() -> accounts.delete(requiredLong(params, "id"))
            ? ok("Email account deleted") : failure("Email account not found"));
    }

    public Object setDefaultAccount(Map<String, Object> params) {
        return result(() -> accounts.setDefault(requiredLong(params, "id"))
            ? ok("Default email account updated") : failure("Email account not found"));
    }

    public Object testAccount(Map<String, Object> params) {
        return result(() -> {
            var value = sends.testSmtp(requiredLong(params, "accountId"));
            return value.success() ? ok("SMTP connection succeeded") : failure(value.errorMessage());
        });
    }

    public Object queryContacts(Map<String, Object> params) {
        return result(() -> {
            var values = addressBook.search(new AddressBookRpc.SearchRequest(string(params, "query"),
                longSet(params.get("tagIds")), integer(params, "offset", 0), integer(params, "limit", 50)));
            return ok("Found " + values.size() + " contact(s)", "contacts", values);
        });
    }

    public Object findContact(Map<String, Object> params) {
        return result(() -> addressBook.findContact(requiredLong(params, "id"))
            .map(value -> ok("Contact found", "contact", value))
            .orElseGet(() -> failure("Contact not found")));
    }

    public Object saveContact(Map<String, Object> params) {
        return result(() -> ok("Contact saved", "contact", addressBook.saveContact(
            json.fromJson(json.toJson(params), AddressBookRpc.ContactRequest.class))));
    }

    public Object deleteContact(Map<String, Object> params) {
        return result(() -> addressBook.deleteContact(requiredLong(params, "id"))
            ? ok("Contact deleted") : failure("Contact not found"));
    }

    public Object listTags(Map<String, Object> params) {
        return result(() -> {
            var tags = addressBook.listTags();
            return ok("Found " + tags.size() + " tag(s)", "tags", tags);
        });
    }

    public Object saveTag(Map<String, Object> params) {
        return result(() -> ok("Tag saved", "tag", addressBook.saveTag(
            json.fromJson(json.toJson(params), AddressBookRpc.TagRequest.class))));
    }

    public Object deleteTag(Map<String, Object> params) {
        return result(() -> addressBook.deleteTag(requiredLong(params, "id"))
            ? ok("Tag deleted") : failure("Tag not found"));
    }

    public Object assignTags(Map<String, Object> params) {
        return result(() -> {
            addressBook.assignTags(new AddressBookRpc.BulkTagRequest(
                longSet(params.get("contactIds")), longSet(params.get("tagIds"))));
            return ok("Contact tags updated");
        });
    }

    public Object resolveRecipients(Map<String, Object> params) {
        return result(() -> {
            Set<String> recipients = addressBook.resolveRecipients(longSet(params.get("tagIds")));
            return ok("Resolved " + recipients.size() + " recipient(s)", "recipients", recipients);
        });
    }

    public Object listConfigs(Map<String, Object> params) {
        return result(() -> {
            var values = configs.list();
            return ok("Found " + values.size() + " batch configuration(s)", "configs", values);
        });
    }

    public Object findConfig(Map<String, Object> params) {
        return result(() -> configs.find(requiredLong(params, "id"))
            .map(value -> ok("Batch configuration found", "config", value))
            .orElseGet(() -> failure("Batch configuration not found")));
    }

    public Object saveConfig(Map<String, Object> params) {
        return result(() -> {
            Long id = optionalLong(params, "id");
            long saved = configs.save(id, requiredString(params, "name"), requiredString(params, "mode"),
                requiredString(params, "configJson"));
            return ok("Batch configuration saved", "config", configs.find(saved).orElseThrow());
        });
    }

    public Object deleteConfig(Map<String, Object> params) {
        return result(() -> configs.delete(requiredLong(params, "id"))
            ? ok("Batch configuration deleted") : failure("Batch configuration not found"));
    }

    public Object prepareSingle(Map<String, Object> params) {
        return result(() -> confirmation("Single email is ready for confirmation",
            pending.prepareSingle(message(params))));
    }

    public Object prepareBatch(Map<String, Object> params) {
        return result(() -> {
            String mode = requiredString(params, "mode").toUpperCase();
            EmailMessageRequest template = message(params);
            PendingSendService.ConfirmationEnvelope envelope = switch (mode) {
                case "TAGS" -> pending.prepareBatchByTags(template, longSet(params.get("tagIds")));
                case "FILENAME" -> pending.prepareBatchByFilename(template,
                    path(params.get("inputDirectory"), "inputDirectory", "directory"));
                default -> throw new IllegalArgumentException("Unsupported batch mode: " + mode);
            };
            return confirmation("Batch email is ready for confirmation", envelope);
        });
    }

    public Object sendStatus(Map<String, Object> params) {
        return result(() -> pending.status(requiredString(params, "confirmationId"))
            .map(value -> ok("Send status is " + value.status(), "send", sendView(value)))
            .orElseGet(() -> failure("Send confirmation not found")));
    }

    public Object retrySend(Map<String, Object> params) {
        return result(() -> confirmation("Retry is ready for confirmation", pending.retryFailed(
            requiredString(params, "confirmationId"), stringSet(params.get("failedRecipients")))));
    }

    public Object confirmSend(Map<String, Object> params) {
        return result(() -> {
            var value = pending.confirm(requiredString(params, "confirmationId"));
            return ok("Send confirmation is " + value.status(), "send", value);
        });
    }

    public Object rejectSend(Map<String, Object> params) {
        return result(() -> {
            var value = pending.reject(requiredString(params, "confirmationId"));
            return ok("Send confirmation is " + value.status(), "send", value);
        });
    }

    public Object collect(Map<String, Object> params) {
        return result(() -> {
            ArchiveRequest request = new ArchiveRequest(requiredLong(params, "accountId"),
                requiredString(params, "folder"), instant(params, "start"), instant(params, "end"),
                path(params.get("outputDirectory"), "outputDirectory", "directory"));
            var value = archive.collect(request, ignored -> { });
            return ok("Archived " + value.newArchived() + " new message(s); skipped "
                + value.skippedDuplicates() + " duplicate(s); " + value.failures() + " failure(s)",
                "collection", value);
        });
    }

    public Object queryArchive(Map<String, Object> params) {
        return result(() -> {
            var values = archive.search(new EmailArchiveService.SearchFilter(optionalLong(params, "accountId"),
                string(params, "folder"), string(params, "sender"), string(params, "subject"),
                instant(params, "start"), instant(params, "end"), integer(params, "offset", 0),
                integer(params, "limit", 50)));
            return ok("Found " + values.size() + " archived message(s)", "messages", values);
        });
    }

    public Object archiveDetail(Map<String, Object> params) {
        return result(() -> archive.detail(requiredLong(params, "id"))
            .map(value -> ok("Archived message found", "message", value))
            .orElseGet(() -> failure("Archived message not found")));
    }

    /** Uses the official handler type to keep registration transport-owned by the SDK. */
    public PluginHandler safe(PluginHandler handler) {
        return params -> {
            try { return cast(handler.handle(params)); }
            catch (Exception error) { return failure(safeMessage(error)); }
        };
    }

    private Map<String, Object> confirmation(String summary, PendingSendService.ConfirmationEnvelope envelope) {
        Map<String, Object> value = ok(summary);
        value.put("confirmation_required", envelope.confirmationRequired());
        value.put("confirmation", jsonValue(envelope.confirmation()));
        return value;
    }

    private EmailMessageRequest message(Map<String, Object> params) {
        return new EmailMessageRequest(requiredLong(params, "accountId"), strings(params.get("to")),
            strings(params.get("cc")), strings(params.get("bcc")), string(params, "subject"),
            string(params, "plainText"), string(params, "htmlText"), paths(params.get("attachments")));
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
                throw new IllegalArgumentException(field + " must reference a " + expectedKind);
            }
            throw new IllegalArgumentException(field + " FileRef must be resolved by the FengYu host");
        }
        throw new IllegalArgumentException(field + " is required");
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

    private Map<String, Object> result(Supplier<Map<String, Object>> operation) {
        try { return operation.get(); }
        catch (Exception error) { return failure(safeMessage(error)); }
    }

    private static Map<String, Object> ok(String summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("summary", summary);
        return result;
    }

    private Map<String, Object> ok(String summary, String key, Object value) {
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
                throw new IllegalStateException("Could not encode email result", error);
            }
        }
        return value.toString();
    }

    private static Map<String, Object> failure(String summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("summary", summary == null || summary.isBlank() ? "Email operation failed" : summary);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalArgumentException("Handler returned an invalid result");
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "Email operation failed";
        return message.replace('\r', ' ').replace('\n', ' ');
    }

    private static String string(Map<String, Object> params, String key) {
        return JsonRpcWorker.string(params, key);
    }

    private static String requiredString(Map<String, Object> params, String key) {
        String value = string(params, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static long requiredLong(Map<String, Object> params, String key) {
        Long value = optionalLong(params, key);
        if (value == null) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static Long optionalLong(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(value.toString()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer"); }
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
