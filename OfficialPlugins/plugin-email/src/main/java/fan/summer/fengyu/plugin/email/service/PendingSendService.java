package fan.summer.fengyu.plugin.email.service;

import com.google.gson.Gson;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.EmailMessageRequest;
import fan.summer.fengyu.plugin.email.model.PendingSend;
import fan.summer.fengyu.plugin.email.model.SendResult;
import fan.summer.fengyu.plugin.email.repository.AddressBookRepository;
import fan.summer.fengyu.plugin.email.repository.PendingSendRepository;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PendingSendService {
    private static final String PLUGIN_ID = "fan.summer.email";
    private final PendingSendRepository pending;
    private final AddressBookRepository addressBook;
    private final Sender sender;
    private final Clock clock;
    private final Duration ttl;
    private final Gson gson = new Gson();

    public PendingSendService(EmailDatabase database, EmailSendService sender) {
        this(database, sender::sendSingle, Clock.systemUTC(), Duration.ofMinutes(30));
    }

    public PendingSendService(EmailDatabase database, Sender sender, Clock clock, Duration ttl) {
        this.pending = new PendingSendRepository(database);
        this.addressBook = new AddressBookRepository(database);
        this.sender = sender;
        this.clock = clock;
        this.ttl = ttl;
    }

    public ConfirmationEnvelope prepareSingle(EmailMessageRequest request) {
        return prepare("SINGLE", new BatchPlanner.BatchPlan(List.of(request), List.of()));
    }

    public ConfirmationEnvelope prepareBatch(String mode, BatchPlanner.BatchPlan plan) {
        if (plan.messages().isEmpty()) throw new IllegalArgumentException("Batch contains no recipients");
        return prepare(mode, plan);
    }

    public ConfirmationEnvelope prepareBatchByTags(EmailMessageRequest template, Set<Long> tagIds) {
        return prepareBatch("TAGS", BatchPlanner.byTags(template, addressBook.resolveRecipientEmails(tagIds)));
    }

    public ConfirmationEnvelope prepareBatchByFilename(EmailMessageRequest template, Path directory) {
        return prepareBatch("FILENAME", BatchPlanner.byFilename(template, directory));
    }

    public Optional<PendingSend> status(String confirmationId) {
        pending.expirePast(confirmationId);
        return pending.find(confirmationId);
    }

    public ConfirmationResult reject(String confirmationId) {
        pending.reject(confirmationId);
        PendingSend value = require(confirmationId);
        return new ConfirmationResult(value.status(), 0, 0, List.of());
    }

    public ConfirmationResult confirm(String confirmationId) {
        if (!pending.claim(confirmationId)) {
            pending.expirePast(confirmationId);
            PendingSend value = require(confirmationId);
            return new ConfirmationResult(value.status(), 0, 0, List.of());
        }
        Snapshot snapshot = decode(require(confirmationId).snapshotJson());
        int succeeded = 0;
        List<String> failed = new ArrayList<>();
        for (MessageSnapshot message : snapshot.messages()) {
            SendResult result;
            try { result = sender.send(message.toRequest()); }
            catch (RuntimeException error) { result = SendResult.failure(error.getMessage()); }
            if (result.success()) succeeded++; else failed.addAll(message.to());
        }
        String terminal = failed.isEmpty() ? "COMPLETED" : succeeded == 0 ? "FAILED" : "PARTIAL_FAILED";
        pending.finish(confirmationId, terminal);
        return new ConfirmationResult(terminal, succeeded, failed.size(), failed);
    }

    public ConfirmationEnvelope retryFailed(String confirmationId, Set<String> failedRecipients) {
        PendingSend original = require(confirmationId);
        if ("PENDING".equals(original.status()) || "SENDING".equals(original.status())) {
            throw new IllegalStateException("Original send is not terminal");
        }
        Snapshot snapshot = decode(original.snapshotJson());
        List<MessageSnapshot> retry = snapshot.messages().stream()
            .filter(message -> message.to().stream().anyMatch(failedRecipients::contains)).toList();
        if (retry.isEmpty()) throw new IllegalArgumentException("No failed recipients selected");
        return persist("RETRY", new Snapshot(retry, List.of()));
    }

    private ConfirmationEnvelope prepare(String mode, BatchPlanner.BatchPlan plan) {
        Snapshot snapshot = new Snapshot(plan.messages().stream().map(MessageSnapshot::from).toList(),
            plan.ignoredFiles().stream().map(Path::toString).toList());
        return persist(mode, snapshot);
    }

    private ConfirmationEnvelope persist(String mode, Snapshot snapshot) {
        String id = UUID.randomUUID().toString();
        ZoneId databaseZone = ZoneId.systemDefault();
        LocalDateTime expires = LocalDateTime.ofInstant(clock.instant().plus(ttl), databaseZone);
        long accountId = snapshot.messages().getFirst().accountId();
        pending.create(id, accountId, mode, gson.toJson(snapshot), expires);
        List<SummaryRow> summary = List.of(new SummaryRow("Recipients", Integer.toString(snapshot.messages().size())),
            new SummaryRow("Ignored files", Integer.toString(snapshot.ignoredFiles().size())));
        return new ConfirmationEnvelope(true,
            new Confirmation(PLUGIN_ID, id, "confirm_send", "reject_send", expires.atZone(databaseZone).toInstant(), summary));
    }

    private PendingSend require(String id) {
        return pending.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown confirmation: " + id));
    }

    private Snapshot decode(String json) { return gson.fromJson(json, Snapshot.class); }

    @FunctionalInterface public interface Sender { SendResult send(EmailMessageRequest request); }
    public record ConfirmationEnvelope(boolean confirmationRequired, Confirmation confirmation) { }
    public record Confirmation(String pluginId, String confirmationId, String approveMethod,
        String rejectMethod, java.time.Instant expiresAt, List<SummaryRow> summary) { }
    public record SummaryRow(String label, String value) { }
    public record ConfirmationResult(String status, int succeeded, int failed, List<String> failedRecipients) {
        public ConfirmationResult { failedRecipients = List.copyOf(failedRecipients); }
    }
    private record Snapshot(List<MessageSnapshot> messages, List<String> ignoredFiles) { }
    private record MessageSnapshot(long accountId, List<String> to, List<String> cc, List<String> bcc,
            String subject, String plainText, String htmlText, List<String> attachments) {
        private static MessageSnapshot from(EmailMessageRequest value) {
            return new MessageSnapshot(value.accountId(), value.to(), value.cc(), value.bcc(), value.subject(),
                value.plainText(), value.htmlText(), value.attachments().stream().map(Path::toString).toList());
        }
        private EmailMessageRequest toRequest() {
            return new EmailMessageRequest(accountId, to, cc, bcc, subject, plainText, htmlText,
                attachments.stream().map(Path::of).toList());
        }
    }
}
