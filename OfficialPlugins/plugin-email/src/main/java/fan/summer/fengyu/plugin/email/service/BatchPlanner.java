package fan.summer.fengyu.plugin.email.service;

import fan.summer.fengyu.plugin.email.model.EmailMessageRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure batch expansion; no messages are sent while a plan is built. */
public final class BatchPlanner {
    private BatchPlanner() { }

    public static BatchPlan byTags(EmailMessageRequest template, Set<String> recipients) {
        List<EmailMessageRequest> messages = recipients == null ? List.of() : recipients.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .sorted()
            .map(recipient -> copy(template, recipient, template.attachments()))
            .toList();
        return new BatchPlan(messages, List.of());
    }

    public static BatchPlan byFilename(EmailMessageRequest template, Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Batch input directory does not exist: " + directory);
        }
        Map<String, List<Path>> grouped = new LinkedHashMap<>();
        List<Path> ignored = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .forEach(path -> {
                    String recipient = recipientSuffix(path.getFileName().toString());
                    if (recipient == null) ignored.add(path);
                    else grouped.computeIfAbsent(recipient, unused -> new ArrayList<>()).add(path);
                });
        } catch (IOException e) {
            throw new IllegalStateException("Could not read batch input directory", e);
        }
        List<EmailMessageRequest> messages = grouped.entrySet().stream()
            .map(entry -> copy(template, entry.getKey(), entry.getValue()))
            .toList();
        return new BatchPlan(messages, ignored);
    }

    private static String recipientSuffix(String filename) {
        int dot = filename.lastIndexOf('.');
        int underscore = filename.lastIndexOf('_', dot - 1);
        if (dot <= 0 || underscore < 0 || underscore + 1 >= dot) return null;
        return filename.substring(underscore + 1, dot).trim();
    }

    private static EmailMessageRequest copy(EmailMessageRequest source, String recipient, List<Path> attachments) {
        return new EmailMessageRequest(source.accountId(), List.of(recipient), List.of(), List.of(),
            source.subject(), source.plainText(), source.htmlText(), attachments);
    }

    public record BatchPlan(List<EmailMessageRequest> messages, List<Path> ignoredFiles) {
        public BatchPlan {
            messages = List.copyOf(messages);
            ignoredFiles = List.copyOf(ignoredFiles);
        }
    }
}
