package fan.summer.fengyu.plugin.email.service;

import com.google.gson.Gson;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.EmailMessageRequest;
import fan.summer.fengyu.plugin.email.model.PendingSend;
import fan.summer.fengyu.plugin.email.model.SendResult;
import fan.summer.fengyu.plugin.email.repository.AddressBookRepository;
import fan.summer.fengyu.plugin.email.repository.PendingSendRepository;
import fan.summer.fengyu.plugin.email.repository.SentLogRepository;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PendingSendService {
    private static final String PLUGIN_ID = "fan.summer.email";
    private final PendingSendRepository pending;
    private final AddressBookRepository addressBook;
    private final SentLogRepository sentLogs;
    private final Sender sender;
    private final Clock clock;
    private final Duration ttl;
    private final Gson gson = new Gson();

    public PendingSendService(EmailDatabase database, EmailSendService sender) {
        this(database, (confirmationId, request) -> sender.sendSingle(request, confirmationId),
            Clock.systemUTC(), Duration.ofMinutes(30));
    }

    public PendingSendService(EmailDatabase database, Sender sender, Clock clock, Duration ttl) {
        this.pending = new PendingSendRepository(database);
        this.addressBook = new AddressBookRepository(database);
        this.sentLogs = new SentLogRepository(database);
        this.sender = sender;
        this.clock = clock;
        this.ttl = ttl;
    }

    public ConfirmationEnvelope prepareSingle(EmailMessageRequest request) {
        var message = new BatchPlanner.PlannedMessage(null, request, request.attachments(), List.of());
        return prepare("SINGLE", new BatchPlanner.BatchPlan(List.of(message), List.of(), List.of()));
    }

    public ConfirmationEnvelope prepareBatch(String mode, BatchPlanner.BatchPlan plan) {
        if (plan.messages().isEmpty()) throw new IllegalArgumentException("Batch contains no recipients");
        return prepare(mode, plan);
    }

    public ConfirmationEnvelope prepareBatchByTags(EmailMessageRequest template, Set<Long> tagIds) {
        return prepareComposeByTags(template, tagIds);
    }

    public ConfirmationEnvelope prepareBatchByFilename(EmailMessageRequest template, Path directory) {
        return prepareBatch("FILENAME", BatchPlanner.byFilename(template, directory));
    }

    public BatchPlanner.BatchPlan planAttachmentBatch(EmailMessageRequest template, Path directory,
            List<Path> commonAttachments, Set<Long> recipientGroupTagIds, Set<Long> ccGroupTagIds) {
        return BatchPlanner.byAttachmentTags(template, directory, commonAttachments, tag ->
            new BatchPlanner.RecipientGroups(
                addressBook.resolveEmailsForAttachmentTag(tag, recipientGroupTagIds),
                addressBook.resolveEmailsForAttachmentTag(tag, ccGroupTagIds)));
    }

    public BatchPreview previewBatch(EmailMessageRequest template, Path directory,
            List<Path> commonAttachments, Set<Long> recipientGroupTagIds, Set<Long> ccGroupTagIds) {
        return BatchPreview.from(planAttachmentBatch(template, directory, commonAttachments,
            recipientGroupTagIds, ccGroupTagIds));
    }

    public ConfirmationEnvelope prepareAttachmentBatch(EmailMessageRequest template, Path directory,
            List<Path> commonAttachments, Set<Long> recipientGroupTagIds, Set<Long> ccGroupTagIds) {
        return prepareBatch("ATTACHMENT_TAGS", planAttachmentBatch(template, directory, commonAttachments,
            recipientGroupTagIds, ccGroupTagIds));
    }

    public ConfirmationEnvelope prepareComposeByTags(EmailMessageRequest template, Set<Long> tagIds) {
        return prepareBatch("TAG_COMPOSE",
            BatchPlanner.byContactTags(template, addressBook.resolveRecipientEmails(tagIds)));
    }

    public SendRecords records(String taskStatus, String confirmationId, String messageStatus,
            String query, int offset, int limit) {
        return new SendRecords(pending.search(taskStatus, offset, limit),
            sentLogs.search(confirmationId, messageStatus, query, offset, limit));
    }

    public Optional<PendingSend> status(String confirmationId) {
        pending.expirePast(confirmationId, now());
        return pending.find(confirmationId);
    }

    public ConfirmationResult reject(String confirmationId) {
        pending.reject(confirmationId);
        PendingSend value = require(confirmationId);
        return new ConfirmationResult(value.status(), 0, 0, List.of());
    }

    public ConfirmationResult confirm(String confirmationId) {
        if (!pending.claim(confirmationId, now())) {
            pending.expirePast(confirmationId, now());
            PendingSend value = require(confirmationId);
            return new ConfirmationResult(value.status(), 0, 0, List.of());
        }
        Snapshot snapshot = decode(require(confirmationId).snapshotJson());
        int succeeded = 0;
        List<String> failed = new ArrayList<>();
        for (MessageSnapshot message : snapshot.messages()) {
            SendResult result;
            try { result = sender.send(confirmationId, message.toRequest()); }
            catch (RuntimeException error) { result = SendResult.failure(error.getMessage()); }
            if (result.success()) succeeded++; else failed.addAll(message.to());
        }
        String terminal = failed.isEmpty() ? "COMPLETED" : succeeded == 0 ? "FAILED" : "PARTIAL_FAILED";
        pending.finish(confirmationId, terminal);
        return new ConfirmationResult(terminal, succeeded, failed.size(), failed);
    }

    private ConfirmationEnvelope prepare(String mode, BatchPlanner.BatchPlan plan) {
        Snapshot snapshot = new Snapshot(plan.messages().stream().map(MessageSnapshot::from).toList(),
            plan.ignoredFiles().stream().map(Path::toString).toList(),
            plan.skippedTags().stream().map(SkippedTagSnapshot::from).toList());
        return persist(mode, snapshot);
    }

    private ConfirmationEnvelope persist(String mode, Snapshot snapshot) {
        String id = UUID.randomUUID().toString();
        LocalDateTime expires = LocalDateTime.ofInstant(clock.instant().plus(ttl), ZoneOffset.UTC);
        long accountId = snapshot.messages().getFirst().accountId();
        pending.create(id, accountId, mode, gson.toJson(snapshot), expires);
        List<SummaryRow> summary = summary(mode, snapshot);
        return new ConfirmationEnvelope(true,
            new Confirmation(PLUGIN_ID, id, "confirm_send", "reject_send", expires.toInstant(ZoneOffset.UTC), summary));
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

    private static List<SummaryRow> summary(String mode, Snapshot snapshot) {
        List<MessageSnapshot> messages = snapshot.messages();
        MessageSnapshot first = messages.getFirst();
        List<SummaryRow> rows = new ArrayList<>();
        rows.add(new SummaryRow("Account", Long.toString(first.accountId())));
        rows.add(new SummaryRow("Mode", mode));
        rows.add(new SummaryRow("Messages", Integer.toString(messages.size())));
        for (int index = 0; index < messages.size(); index++) {
            MessageSnapshot message = messages.get(index);
            String prefix = "Message " + (index + 1) + " / ";
            if (message.attachmentTag() != null) rows.add(new SummaryRow(prefix + "Attachment tag", message.attachmentTag()));
            rows.add(new SummaryRow(prefix + "To", joinDistinct(message.to())));
            rows.add(new SummaryRow(prefix + "CC", joinDistinct(message.cc())));
            rows.add(new SummaryRow(prefix + "BCC", joinDistinct(message.bcc())));
            rows.add(new SummaryRow(prefix + "Subject", String.valueOf(message.subject())));
            rows.add(new SummaryRow(prefix + "Tag attachments", fileNames(message.tagAttachments())));
            rows.add(new SummaryRow(prefix + "Common attachments", fileNames(message.commonAttachments())));
        }
        rows.add(new SummaryRow("Ignored files", joinDistinct(snapshot.ignoredFiles().stream()
            .map(path -> Path.of(path).getFileName().toString()).toList())));
        rows.add(new SummaryRow("Skipped tags", joinDistinct(snapshot.skippedTags().stream()
            .map(value -> value.attachmentTag() + ": " + value.reason()).toList())));
        return List.copyOf(rows);
    }

    private static String fileNames(List<String> paths) {
        return joinDistinct(paths.stream().map(path -> Path.of(path).getFileName().toString()).toList());
    }

    private static String joinDistinct(List<String> values) {
        String joined = values.stream().filter(value -> value != null && !value.isBlank()).distinct()
            .collect(java.util.stream.Collectors.joining(", "));
        return joined.isBlank() ? "None" : joined;
    }

    private PendingSend require(String id) {
        return pending.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown confirmation: " + id));
    }

    private Snapshot decode(String json) { return gson.fromJson(json, Snapshot.class); }

    @FunctionalInterface public interface Sender { SendResult send(String confirmationId, EmailMessageRequest request); }
    public record ConfirmationEnvelope(boolean confirmationRequired, Confirmation confirmation) { }
    public record Confirmation(String pluginId, String confirmationId, String approveMethod,
        String rejectMethod, java.time.Instant expiresAt, List<SummaryRow> summary) { }
    public record SummaryRow(String label, String value) { }
    public record ConfirmationResult(String status, int succeeded, int failed, List<String> failedRecipients) {
        public ConfirmationResult { failedRecipients = List.copyOf(failedRecipients); }
    }
    public record SendRecords(List<PendingSendRepository.SendTaskView> tasks,
            List<SentLogRepository.SentMessageView> messages) { }
    public record PreviewMessage(String attachmentTag, List<String> to, List<String> cc,
            List<String> tagAttachments, List<String> commonAttachments) { }
    public record PreviewSkippedTag(String attachmentTag, String reason, List<String> attachments) { }
    public record BatchPreview(List<PreviewMessage> messages, List<String> ignoredFiles,
            List<PreviewSkippedTag> skippedTags, int messageCount) {
        private static BatchPreview from(BatchPlanner.BatchPlan plan) {
            List<PreviewMessage> messages = plan.messages().stream().map(value -> new PreviewMessage(
                value.attachmentTag(), value.request().to(), value.request().cc(), strings(value.tagAttachments()),
                strings(value.commonAttachments()))).toList();
            return new BatchPreview(messages, strings(plan.ignoredFiles()), plan.skippedTags().stream()
                .map(value -> new PreviewSkippedTag(value.attachmentTag(), value.reason(), strings(value.attachments())))
                .toList(), messages.size());
        }
        private static List<String> strings(List<Path> paths) { return paths.stream().map(Path::toString).toList(); }
    }
    private record Snapshot(List<MessageSnapshot> messages, List<String> ignoredFiles,
            List<SkippedTagSnapshot> skippedTags) {
        private Snapshot {
            messages = messages == null ? List.of() : List.copyOf(messages);
            ignoredFiles = ignoredFiles == null ? List.of() : List.copyOf(ignoredFiles);
            skippedTags = skippedTags == null ? List.of() : List.copyOf(skippedTags);
        }
    }
    private record SkippedTagSnapshot(String attachmentTag, String reason, List<String> attachments) {
        private static SkippedTagSnapshot from(BatchPlanner.SkippedTag value) {
            return new SkippedTagSnapshot(value.attachmentTag(), value.reason(),
                value.attachments().stream().map(Path::toString).toList());
        }
    }
    private record MessageSnapshot(long accountId, List<String> to, List<String> cc, List<String> bcc,
            String subject, String plainText, String htmlText, List<String> attachments,
            String attachmentTag, List<String> tagAttachments, List<String> commonAttachments) {
        private MessageSnapshot {
            to = to == null ? List.of() : List.copyOf(to);
            cc = cc == null ? List.of() : List.copyOf(cc);
            bcc = bcc == null ? List.of() : List.copyOf(bcc);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            tagAttachments = tagAttachments == null ? attachments : List.copyOf(tagAttachments);
            commonAttachments = commonAttachments == null ? List.of() : List.copyOf(commonAttachments);
        }
        private static MessageSnapshot from(BatchPlanner.PlannedMessage planned) {
            EmailMessageRequest value = planned.request();
            return new MessageSnapshot(value.accountId(), value.to(), value.cc(), value.bcc(), value.subject(),
                value.plainText(), value.htmlText(), value.attachments().stream().map(Path::toString).toList(),
                planned.attachmentTag(), planned.tagAttachments().stream().map(Path::toString).toList(),
                planned.commonAttachments().stream().map(Path::toString).toList());
        }
        private EmailMessageRequest toRequest() {
            return new EmailMessageRequest(accountId, to, cc, bcc, subject, plainText, htmlText,
                attachments.stream().map(Path::of).toList());
        }
    }
}
