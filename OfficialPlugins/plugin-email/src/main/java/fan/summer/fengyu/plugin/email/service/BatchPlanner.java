package fan.summer.fengyu.plugin.email.service;

import fan.summer.fengyu.plugin.email.model.EmailMessageRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure deterministic batch expansion; no messages are sent while a plan is built. */
public final class BatchPlanner {
    private BatchPlanner() { }

    public static BatchPlan byAttachmentTags(EmailMessageRequest template, Path directory,
            List<Path> commonAttachments, RecipientResolver recipientResolver) {
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Batch input directory does not exist: " + directory);
        }
        if (recipientResolver == null) throw new IllegalArgumentException("Recipient resolver is required");
        List<Path> common = commonAttachments == null ? List.of() : List.copyOf(commonAttachments);
        Map<String, List<Path>> grouped = new LinkedHashMap<>();
        List<Path> ignored = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .forEach(path -> {
                    String tag = attachmentTag(path.getFileName().toString());
                    if (tag == null) ignored.add(path);
                    else grouped.computeIfAbsent(tag, unused -> new ArrayList<>()).add(path);
                });
        } catch (IOException e) {
            throw new IllegalStateException("Could not read batch input directory", e);
        }

        List<PlannedMessage> messages = new ArrayList<>();
        List<SkippedTag> skipped = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            RecipientGroups groups = recipientResolver.resolve(entry.getKey());
            List<String> to = normalize(groups == null ? Set.of() : groups.to());
            Set<String> toKeys = to.stream().map(BatchPlanner::addressKey)
                .collect(java.util.stream.Collectors.toSet());
            List<String> cc = normalize(groups == null ? Set.of() : groups.cc()).stream()
                .filter(address -> !toKeys.contains(addressKey(address)))
                .toList();
            List<Path> tagFiles = List.copyOf(entry.getValue());
            if (to.isEmpty()) {
                skipped.add(new SkippedTag(entry.getKey(), "No primary recipients", tagFiles));
                continue;
            }
            List<Path> all = new ArrayList<>(tagFiles);
            all.addAll(common);
            EmailMessageRequest request = copy(template, to, cc, all);
            messages.add(new PlannedMessage(entry.getKey(), request, tagFiles, common));
        }
        return new BatchPlan(messages, ignored, skipped);
    }

    public static BatchPlan byContactTags(EmailMessageRequest template, Set<String> recipients) {
        List<PlannedMessage> messages = normalize(recipients).stream()
            .map(recipient -> {
                EmailMessageRequest request = copy(template, List.of(recipient), template.cc(), template.attachments());
                return new PlannedMessage(null, request, List.of(), template.attachments());
            })
            .toList();
        return new BatchPlan(messages, List.of(), List.of());
    }

    /** Transitional compatibility for tests and callers migrated in the following RPC task. */
    static BatchPlan byTags(EmailMessageRequest template, Set<String> recipients) {
        return byContactTags(template, recipients);
    }

    /** Transitional compatibility for callers migrated in the following RPC task. */
    static BatchPlan byFilename(EmailMessageRequest template, Path directory) {
        return byAttachmentTags(template, directory, List.of(),
            tag -> new RecipientGroups(Set.of(tag), Set.of()));
    }

    static String attachmentTag(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        int underscore = filename.lastIndexOf('_', dot - 1);
        if (dot <= 0 || underscore < 0 || underscore + 1 >= dot) return null;
        String tag = filename.substring(underscore + 1, dot).trim();
        return tag.isBlank() ? null : tag;
    }

    private static List<String> normalize(Set<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        Map<String, String> distinct = new LinkedHashMap<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String trimmed = value.trim();
            distinct.merge(addressKey(trimmed), trimmed,
                (first, next) -> first.compareTo(next) <= 0 ? first : next);
        }
        return distinct.values().stream()
            .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
            .toList();
    }

    private static String addressKey(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static EmailMessageRequest copy(EmailMessageRequest source, List<String> to,
            List<String> cc, List<Path> attachments) {
        return new EmailMessageRequest(source.accountId(), to, cc, source.bcc(), source.subject(),
            source.plainText(), source.htmlText(), attachments);
    }

    public record RecipientGroups(Set<String> to, Set<String> cc) { }
    @FunctionalInterface public interface RecipientResolver { RecipientGroups resolve(String attachmentTag); }
    public record PlannedMessage(String attachmentTag, EmailMessageRequest request,
            List<Path> tagAttachments, List<Path> commonAttachments) {
        public PlannedMessage {
            tagAttachments = List.copyOf(tagAttachments);
            commonAttachments = List.copyOf(commonAttachments);
        }
    }
    public record SkippedTag(String attachmentTag, String reason, List<Path> attachments) {
        public SkippedTag { attachments = List.copyOf(attachments); }
    }
    public record BatchPlan(List<PlannedMessage> messages, List<Path> ignoredFiles,
            List<SkippedTag> skippedTags) {
        public BatchPlan {
            messages = List.copyOf(messages);
            ignoredFiles = List.copyOf(ignoredFiles);
            skippedTags = List.copyOf(skippedTags);
        }
    }
}
