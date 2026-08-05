package fan.summer.fengyu.plugin.email.service;

import com.google.gson.Gson;
import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.ArchiveRequest;
import fan.summer.fengyu.plugin.email.model.ArchivedMessage;
import fan.summer.fengyu.plugin.email.model.EmailAccount;
import fan.summer.fengyu.plugin.email.model.SendResult;
import fan.summer.fengyu.plugin.email.repository.AccountRepository;
import fan.summer.fengyu.plugin.email.repository.ArchiveRepository;
import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

public final class EmailArchiveService {
    private static final int MAX_PREVIEW = 500;
    private static final int MAX_PAGE = 100;
    private static final int MAX_FILENAME_UTF8_BYTES = 254;
    private static final int IMAP_TIMEOUT_MILLIS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(EmailArchiveService.class);

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final ArchiveRepository archives;
    private final Gson gson = new Gson();

    public EmailArchiveService(EmailDatabase database, CredentialCipher cipher) {
        accounts = new AccountRepository(database);
        accountService = new AccountService(accounts, cipher);
        archives = new ArchiveRepository(database);
    }

    public CollectResult collect(ArchiveRequest request, ProgressSink progressSink) {
        validate(request);
        ProgressSink sink = progressSink == null ? ignored -> { } : progressSink;
        EmailAccount account = accounts.findAccount(request.accountId()).orElseThrow(
            () -> new IllegalArgumentException("Unknown account: " + request.accountId()));
        if (blank(account.imapHost()) || account.imapPort() == null || blank(account.imapSecurity())) {
            throw new IllegalArgumentException("IMAP is not configured for account: " + request.accountId());
        }
        String password = accountService.decryptPassword(account.id());
        Properties properties = imapProperties(account.imapSecurity());
        Session session = Session.getInstance(properties);
        int archived = 0;
        int skipped = 0;
        int failures = 0;
        try (Store store = session.getStore(protocol(account.imapSecurity()))) {
            store.connect(account.imapHost(), account.imapPort(), account.email(), password);
            try (Folder folder = store.getFolder(request.folder())) {
                if (!folder.exists()) throw new IllegalArgumentException("Unknown IMAP folder: " + request.folder());
                folder.open(Folder.READ_ONLY);
                if (!(folder instanceof UIDFolder uidFolder)) {
                    throw new IllegalStateException("IMAP server does not expose stable message UIDs");
                }
                List<Message> messages = filterByDate(folder.getMessages(), request.start(), request.end());
                log.info("archive collect: account={} folder='{}' messages={}", account.id(), request.folder(), messages.size());
                for (int i = 0; i < messages.size(); i++) {
                    Message message = messages.get(i);
                    try {
                        String uid = Long.toString(uidFolder.getUID(message));
                        if (archives.exists(account.id(), request.folder(), uid)) {
                            skipped++;
                        } else {
                            archiveOne(account, request.folder(), uid, message, request.outputDirectory());
                            archived++;
                        }
                    } catch (Exception messageFailure) {
                        failures++;
                        log.warn("archive failed for one message in account={} folder='{}': {}",
                            account.id(), request.folder(), messageFailure.toString());
                    }
                    sink.accept(new Progress(i + 1, messages.size(), archived, skipped, failures));
                }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("IMAP collection failed for account={} folder='{}': {}",
                account.id(), request.folder(), e.toString(), e);
            throw new IllegalStateException("IMAP collection failed: " + safeMessage(e, password), e);
        }
        log.info("archive collect done: account={} folder='{}' new={} skipped={} failed={}",
            account.id(), request.folder(), archived, skipped, failures);
        return new CollectResult(archived, skipped, failures);
    }

    public SendResult testImap(long accountId) {
        EmailAccount account = accounts.findAccount(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown account: " + accountId));
        if (blank(account.imapHost()) || account.imapPort() == null || blank(account.imapSecurity())) {
            return SendResult.failure("IMAP is not configured for account: " + accountId);
        }
        String password = accountService.decryptPassword(accountId);
        try (Store store = Session.getInstance(imapProperties(account.imapSecurity()))
                .getStore(protocol(account.imapSecurity()))) {
            store.connect(account.imapHost(), account.imapPort(), account.email(), password);
            log.info("IMAP test succeeded for account {} ({})", accountId, account.email());
            return SendResult.success(null);
        } catch (Exception e) {
            log.warn("IMAP test failed for account {} ({}): {}", accountId, account.email(), safeMessage(e, password));
            return SendResult.failure(safeMessage(e, password));
        }
    }

    public List<ArchivedMessage> search(SearchFilter filter) {
        if (filter == null) throw new IllegalArgumentException("filter is required");
        int offset = Math.max(0, filter.offset());
        int limit = Math.max(1, Math.min(MAX_PAGE, filter.limit()));
        return archives.search(new ArchiveRepository.SearchCriteria(filter.accountId(), trimToNull(filter.folder()),
            containsPattern(filter.sender()), containsPattern(filter.subject()), filter.start(), filter.end(),
            offset, limit));
    }

    public Optional<ArchivedMessage> detail(long id) {
        return archives.detail(id);
    }

    private void archiveOne(EmailAccount account, String folder, String uid, Message message, Path outputDirectory)
            throws Exception {
        String subject = message.getSubject();
        String from = join(message.getFrom());
        String recipients = recipients(message);
        Instant sentAt = instant(message.getSentDate());
        Instant receivedAt = instant(message.getReceivedDate());
        ExtractedContent content = extract(message);
        String preview = boundPreview(content.text());
        Path archiveDirectory = archiveDirectory(outputDirectory, account.id(), folder);
        Files.createDirectories(archiveDirectory);
        Path target = archiveDirectory.resolve(archiveFilename(subject, uid));
        Path temporary = temporaryArchivePath(target);
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                message.writeTo(output);
            }
            atomicMove(temporary, target);
            try {
                archives.insert(new ArchiveRepository.ArchiveEntry(account.id(), account.email(), folder, uid,
                    subject, from, recipients, sentAt, receivedAt, content.hasAttachment(), preview,
                    target.toAbsolutePath().normalize().toString()));
            } catch (RuntimeException metadataFailure) {
                Files.deleteIfExists(target);
                throw metadataFailure;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static List<Message> filterByDate(Message[] messages, Instant start, Instant end) throws Exception {
        List<Message> selected = new ArrayList<>();
        for (Message message : messages) {
            Instant received = instant(message.getReceivedDate());
            if (start != null && (received == null || received.isBefore(start))) continue;
            if (end != null && (received == null || received.isAfter(end))) continue;
            selected.add(message);
        }
        return selected;
    }

    private String recipients(Message message) throws Exception {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("to", addresses(message.getRecipients(Message.RecipientType.TO)));
        values.put("cc", addresses(message.getRecipients(Message.RecipientType.CC)));
        values.put("bcc", addresses(message.getRecipients(Message.RecipientType.BCC)));
        return gson.toJson(values);
    }

    private static ExtractedContent extract(Part part) throws Exception {
        boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || !blank(part.getFileName());
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            StringBuilder text = new StringBuilder();
            boolean nestedAttachment = attachment;
            for (int i = 0; i < multipart.getCount(); i++) {
                ExtractedContent nested = extract(multipart.getBodyPart(i));
                nestedAttachment |= nested.hasAttachment();
                if (!nested.text().isBlank()) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(nested.text());
                }
            }
            return new ExtractedContent(text.toString(), nestedAttachment);
        }
        if (attachment) return new ExtractedContent("", true);
        if (part.isMimeType("text/plain")) return new ExtractedContent(String.valueOf(part.getContent()), false);
        if (part.isMimeType("text/html")) {
            String html = String.valueOf(part.getContent());
            return new ExtractedContent(html.replaceAll("(?s)<[^>]*>", " "), false);
        }
        return new ExtractedContent("", false);
    }

    private static void atomicMove(Path temporary, Path target) throws Exception {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IllegalStateException("Archive directory does not support atomic file moves", e);
        }
    }

    private static void validate(ArchiveRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        if (blank(request.folder())) throw new IllegalArgumentException("folder is required");
        if (request.outputDirectory() == null) throw new IllegalArgumentException("outputDirectory is required");
        if (request.start() != null && request.end() != null && request.start().isAfter(request.end())) {
            throw new IllegalArgumentException("start must not be after end");
        }
    }

    private static Properties imapProperties(String security) {
        String protocol = protocol(security);
        Properties properties = new Properties();
        properties.setProperty("mail." + protocol + ".connectiontimeout", Integer.toString(IMAP_TIMEOUT_MILLIS));
        properties.setProperty("mail." + protocol + ".timeout", Integer.toString(IMAP_TIMEOUT_MILLIS));
        properties.setProperty("mail." + protocol + ".writetimeout", Integer.toString(IMAP_TIMEOUT_MILLIS));
        if ("TLS".equalsIgnoreCase(security) || "STARTTLS".equalsIgnoreCase(security)) {
            properties.setProperty("mail.imap.starttls.enable", "true");
            properties.setProperty("mail.imap.starttls.required", "true");
        }
        return properties;
    }

    private static String protocol(String security) {
        return switch (security.trim().toUpperCase(Locale.ROOT)) {
            case "PLAIN", "IMAP", "NONE", "TLS", "STARTTLS" -> "imap";
            case "SSL", "IMAPS" -> "imaps";
            default -> throw new IllegalArgumentException("Unsupported IMAP security: " + security);
        };
    }

    private static String safeSubject(String subject) {
        String safe = subject == null ? "message" : subject.trim().replaceAll("[^\\p{L}\\p{N}._-]+", "_")
            .replaceAll("^[._-]+|[._-]+$", "");
        if (safe.isBlank()) safe = "message";
        return safe;
    }

    private static String archiveFilename(String subject, String uid) {
        String suffix = "_" + uid + ".eml";
        int available = MAX_FILENAME_UTF8_BYTES - suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return truncateUtf8(safeSubject(subject), available) + suffix;
    }

    private static String truncateUtf8(String value, int maxBytes) {
        StringBuilder truncated = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int codePointBytes = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (bytes + codePointBytes > maxBytes) break;
            truncated.appendCodePoint(codePoint);
            bytes += codePointBytes;
            offset += Character.charCount(codePoint);
        }
        return truncated.toString();
    }

    private static Path archiveDirectory(Path outputDirectory, long accountId, String folder) {
        return outputDirectory.resolve("account-" + accountId).resolve("folder-" + sha256(folder).substring(0, 24));
    }

    static Path temporaryArchivePath(Path target) {
        return target.resolveSibling(".tmp-" + UUID.randomUUID());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String boundPreview(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.substring(0, Math.min(MAX_PREVIEW, normalized.length()));
    }

    private static String join(Address[] addresses) {
        return String.join(", ", addresses(addresses));
    }

    private static List<String> addresses(Address[] addresses) {
        if (addresses == null) return List.of();
        List<String> result = new ArrayList<>(addresses.length);
        for (Address address : addresses) result.add(address.toString());
        return List.copyOf(result);
    }

    private static Instant instant(java.util.Date value) { return value == null ? null : value.toInstant(); }
    private static String trimToNull(String value) { return blank(value) ? null : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String containsPattern(String value) {
        if (blank(value)) return null;
        String literal = value.trim().toLowerCase(Locale.ROOT)
            .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + literal + "%";
    }
    private static String safeMessage(Exception error, String password) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        if (message == null || message.isBlank()) message = cause.getClass().getSimpleName();
        return password == null || password.isEmpty() ? message : message.replace(password, "<redacted>");
    }

    public record SearchFilter(Long accountId, String folder, String sender, String subject,
            Instant start, Instant end, int offset, int limit) { }
    public record CollectResult(int newArchived, int skippedDuplicates, int failures) { }
    public record Progress(int completed, int total, int newArchived, int skippedDuplicates, int failures) { }
    @FunctionalInterface public interface ProgressSink { void accept(Progress progress); }
    private record ExtractedContent(String text, boolean hasAttachment) { }
}
