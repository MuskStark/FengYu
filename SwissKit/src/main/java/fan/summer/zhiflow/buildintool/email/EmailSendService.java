package fan.summer.zhiflow.buildintool.email;

import fan.summer.zhiflow.database.DatabaseInit;
import fan.summer.zhiflow.database.entity.email.EmailMassSentConfigEntity;
import fan.summer.zhiflow.database.entity.email.EmailSentLogEntity;
import fan.summer.zhiflow.database.entity.setting.email.EmailAddressBookEntity;
import fan.summer.zhiflow.database.entity.setting.email.EmailTagEntity;
import fan.summer.zhiflow.database.mapper.email.EmailMassSentConfigMapper;
import fan.summer.zhiflow.database.mapper.email.EmailSentLogMapper;
import fan.summer.zhiflow.database.mapper.setting.email.EmailAddressBookMapper;
import fan.summer.zhiflow.database.mapper.setting.email.EmailTagMapper;
import fan.summer.zhiflow.utils.EmailUtil;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Email sending service supporting single, mass-by-tag, and filename-tag modes.
 *
 * @since 1.0.0
 */
public class EmailSendService {

    private static final Logger log = LoggerFactory.getLogger(EmailSendService.class);
    private static final Pattern TAG_ID_PATTERN = Pattern.compile("\\d+");

    /**
     * Callback interface for reporting mass-send progress back to the caller.
     */
    public interface ProgressCallback {
        /**
         * Called periodically during a mass send operation.
         *
         * @param progress progress value between 0.0 and 1.0
         * @param message  a human-readable status message describing the current step
         */
        void update(double progress, String message);
    }

    /**
     * Sends a single email immediately, with optional CC/BCC recipients and attachments.
     * The operation is synchronous and blocks until the email is handed off to the SMTP server.
     *
     * <p>Either list may be null or empty. At least one To recipient must be provided.
     *
     * @param subject    email subject line (must not be blank)
     * @param htmlBody   HTML content of the email body
     * @param toList     primary recipient email addresses (may be null or empty; a
     *                   validation error is returned if null and empty)
     * @param ccList     CC recipient addresses (nullable)
     * @param bccList    BCC recipient addresses (nullable)
     * @param attachments files to attach; may be null
     * @return a {@link Result} indicating success/failure counts and an error message if applicable
     */
    public Result sendSingle(String subject, String htmlBody,
                             List<String> toList, List<String> ccList, List<String> bccList,
                             List<File> attachments) {
        Result result = new Result();
        EmailSentLogEntity logEntity = new EmailSentLogEntity();
        logEntity.setSubject(subject);
        logEntity.setTo(toList != null ? toList.toString() : null);
        logEntity.setCc(ccList != null && !ccList.isEmpty() ? ccList.toString() : null);
        logEntity.setBcc(bccList != null && !bccList.isEmpty() ? bccList.toString() : null);
        logEntity.setContent(htmlBody);
        logEntity.setAttachment(attachments != null && !attachments.isEmpty() ? attachments.toString() : null);
        logEntity.setSendTime(new Date());

        try {
            EmailUtil.EmailMessage message = EmailUtil.EmailMessage.builder()
                    .to(toList)
                    .cc(ccList != null && !ccList.isEmpty() ? ccList : null)
                    .bcc(bccList != null && !bccList.isEmpty() ? bccList : null)
                    .subject(subject)
                    .htmlBody(htmlBody)
                    .attachments(attachments)
                    .build();
            EmailUtil.sendEmail(message);
            logEntity.setSuccess(true);
            result.successCount = 1;
            log.info("Single email sent successfully to {}", toList);
        } catch (Exception e) {
            log.error("Single email send failed", e);
            logEntity.setSuccess(false);
            result.failCount = 1;
            result.errorMessage = e.getMessage();
        }

        try (SqlSession session = DatabaseInit.getSqlSession()) {
            session.getMapper(EmailSentLogMapper.class).insert(logEntity);
            session.commit();
        } catch (Exception dbEx) {
            log.error("Failed to save send log", dbEx);
        }

        return result;
    }

    /**
     * Executes a mass send for the given taskId, which must correspond to a row in the
     * {@code EmailMassSentConfigEntity} table.
     *
     * <p>Delegates to {@link #sendMassByFilename(String, String, String, ProgressCallback)}
     * when {@code config.isSendByFilename()} is true, otherwise to
     * {@link #sendMassByTag(String, String, EmailMassSentConfigEntity, ProgressCallback)}.
     *
     * @param subject   email subject line
     * @param htmlBody  HTML body content
     * @param taskId    the mass-send configuration task ID stored in the database
     * @param progress  progress callback (may be null)
     * @return a {@link Result} summarizing successes, failures, and any error message
     */
    public Result sendMass(String subject, String htmlBody, String taskId, ProgressCallback progress) {
        EmailMassSentConfigEntity config;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            config = session.getMapper(EmailMassSentConfigMapper.class).selectByTaskId(taskId);
            if (config == null) {
                Result r = new Result();
                r.errorMessage = "No configuration found for taskId: " + taskId;
                return r;
            }
        } catch (Exception e) {
            log.error("Failed to load mass config", e);
            Result r = new Result();
            r.errorMessage = "Failed to load config: " + e.getMessage();
            return r;
        }

        if (config.isSendByFilename()) {
            return sendMassByFilename(subject, htmlBody, config.getAttFolderPath(), progress);
        }
        return sendMassByTag(subject, htmlBody, config, progress);
    }

    /**
     * Tag-based mass send with multi-tag to/cc support.
     * For each attachment group (tagged by filename suffix), the method:
     * <ol>
     *   <li>Finds all contacts whose tag set overlaps the attachment's tag.</li>
     *   <li>Further filters contacts by to-tag / cc-tag inclusion rules.</li>
     *   <li>Sends one email to the matched recipients with the group's files attached.</li>
     * </ol>
     * If no attachment files with a valid tag format are found, all matching contacts
     * receive a single email without attachments.
     *
     * @param subject   email subject line
     * @param htmlBody  HTML body content
     * @param config    mass-send configuration loaded from the database
     * @param progress  progress callback (may be null)
     * @return a {@link Result} summarizing successes and failures
     */
    private Result sendMassByTag(String subject, String htmlBody, EmailMassSentConfigEntity config, ProgressCallback progress) {
        Result result = new Result();
        if (progress != null) progress.update(0.0, "Loading address book...");

        List<EmailAddressBookEntity> allAddresses;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            allAddresses = session.getMapper(EmailAddressBookMapper.class).selectEmailAddressBook();
        } catch (Exception e) {
            log.error("Failed to load address book", e);
            result.errorMessage = "Failed to load address book: " + e.getMessage();
            return result;
        }

        if (progress != null) progress.update(0.05, "Loading tags...");
        List<EmailTagEntity> emailTags;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            emailTags = session.getMapper(EmailTagMapper.class).selectAll();
            if (emailTags == null) {
                result.errorMessage = "No email tags found in database";
                return result;
            }
        } catch (Exception e) {
            log.error("Failed to load email tags", e);
            result.errorMessage = "Failed to load tags: " + e.getMessage();
            return result;
        }
        Map<String, List<EmailTagEntity>> tagByName =
                emailTags.stream().collect(Collectors.groupingBy(EmailTagEntity::getTag));

        if (progress != null) progress.update(0.15, "Parsing attachments...");
        Map<String, List<File>> taggedFiles = parseAttachmentFiles(config.getAttFolderPath());

        // Parse multi-tag to/cc IDs
        Set<Long> toTagIds = parseTagIdSet(config.getToTag());
        Set<Long> ccTagIds = parseTagIdSet(config.getCcTag());

        int total = taggedFiles.isEmpty() ? 1 : taggedFiles.size();
        int processed = 0;
        List<String> toList = new ArrayList<>();
        List<String> ccList = new ArrayList<>();

        for (Map.Entry<String, List<File>> entry : taggedFiles.entrySet()) {
            processed++;
            String tagName = entry.getKey();
            List<File> files = entry.getValue();

            List<EmailTagEntity> matchedTags = tagByName.get(tagName);
            if (matchedTags == null || matchedTags.isEmpty()) {
                log.warn("No matching tag found in database for: {}", tagName);
                continue;
            }
            EmailTagEntity fileTag = matchedTags.get(0);

            List<String> batchTo = new ArrayList<>();
            List<String> batchCc = new ArrayList<>();

            for (EmailAddressBookEntity addr : allAddresses) {
                Set<Long> contactTagIds = parseTagIdSet(addr.getTags());
                if (!contactTagIds.contains(fileTag.getId())) continue;

                if (toTagIds.isEmpty() || contactTagIds.stream().anyMatch(toTagIds::contains)) {
                    batchTo.add(addr.getEmailAddress());
                }
                if (!ccTagIds.isEmpty() && contactTagIds.stream().anyMatch(ccTagIds::contains)) {
                    batchCc.add(addr.getEmailAddress());
                }
            }

            if (batchTo.isEmpty()) {
                log.warn("No recipients found for tag {}", tagName);
                continue;
            }

            if (progress != null) {
                double pct = 0.15 + 0.85 * processed / total;
                progress.update(pct, "Sending [" + processed + "/" + total + "] " + tagName);
            }

            sendOneWithLog(subject, htmlBody, batchTo, batchCc, config.isSentAtt() ? files : null, result, tagName);
        }

        if (taggedFiles.isEmpty()) {
            // No files with tag format — send to all matching contacts in one batch
            List<String> allToList = new ArrayList<>();
            List<String> allCcList = new ArrayList<>();
            for (EmailAddressBookEntity addr : allAddresses) {
                Set<Long> contactTagIds = parseTagIdSet(addr.getTags());
                if (toTagIds.isEmpty() || contactTagIds.stream().anyMatch(toTagIds::contains)) {
                    allToList.add(addr.getEmailAddress());
                }
                if (!ccTagIds.isEmpty() && contactTagIds.stream().anyMatch(ccTagIds::contains)) {
                    allCcList.add(addr.getEmailAddress());
                }
            }
            if (!allToList.isEmpty()) {
                if (progress != null) progress.update(0.5, "Sending to all matching contacts...");
                sendOneWithLog(subject, htmlBody, allToList, allCcList, null, result, "all");
            }
        }

        if (progress != null) progress.update(1.0, "Done");
        return result;
    }

    /**
     * Filename-tag mode: parses attachment filenames to extract a tag suffix
     * (text between the last underscore and the dot), looks up each tag name in
     * the address book to find matching contacts, and sends one email per tag group.
     *
     * <p>Example: files {@code report_Q1.xlsx} and {@code report_Q2.xlsx} in the
     * attachment folder will send one email to all contacts tagged "Q1" and a
     * separate email to all contacts tagged "Q2".
     *
     * @param subject   email subject line
     * @param htmlBody  HTML body content
     * @param attachmentPath directory containing the attachment files
     * @param progress  progress callback (may be null)
     * @return a {@link Result} summarizing successes and failures
     */
    private Result sendMassByFilename(String subject, String htmlBody, String attachmentPath, ProgressCallback progress) {
        Result result = new Result();
        if (progress != null) progress.update(0.0, "Loading address book...");

        List<EmailAddressBookEntity> allAddresses;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            allAddresses = session.getMapper(EmailAddressBookMapper.class).selectEmailAddressBook();
        } catch (Exception e) {
            log.error("Failed to load address book", e);
            result.errorMessage = "Failed to load address book: " + e.getMessage();
            return result;
        }

        if (progress != null) progress.update(0.05, "Loading tags...");
        List<EmailTagEntity> allTags;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            allTags = session.getMapper(EmailTagMapper.class).selectAll();
            if (allTags == null) allTags = new ArrayList<>();
        } catch (Exception e) {
            log.error("Failed to load tags", e);
            result.errorMessage = "Failed to load tags: " + e.getMessage();
            return result;
        }

        // Build tagName → tagId map
        Map<String, Long> tagNameToId = new HashMap<>();
        for (EmailTagEntity t : allTags) tagNameToId.put(t.getTag(), t.getId());

        if (progress != null) progress.update(0.1, "Parsing attachments...");
        Map<String, List<File>> taggedFiles = parseAttachmentFiles(attachmentPath);
        if (taggedFiles.isEmpty()) {
            result.errorMessage = "No attachment files found with valid tag format in: " + attachmentPath;
            return result;
        }

        int total = taggedFiles.size();
        int processed = 0;

        for (Map.Entry<String, List<File>> entry : taggedFiles.entrySet()) {
            processed++;
            String tagName = entry.getKey();
            List<File> files = entry.getValue();

            // Look up tag ID by name
            Long tagId = tagNameToId.get(tagName);
            if (tagId == null) {
                log.warn("No tag found in database matching filename tag: {}", tagName);
                continue;
            }

            // Find all contacts that have this tag
            List<String> recipients = new ArrayList<>();
            for (EmailAddressBookEntity addr : allAddresses) {
                Set<Long> contactTagIds = parseTagIdSet(addr.getTags());
                if (contactTagIds.contains(tagId)) {
                    recipients.add(addr.getEmailAddress());
                }
            }

            if (recipients.isEmpty()) {
                log.warn("No recipients found for filename tag {}", tagName);
                continue;
            }

            if (progress != null) {
                double pct = 0.1 + 0.9 * processed / total;
                progress.update(pct, "Sending [" + processed + "/" + total + "] " + tagName);
            }

            sendOneWithLog(subject, htmlBody, recipients, null, files, result, tagName);
        }

        if (progress != null) progress.update(1.0, "Done");
        return result;
    }

    /**
     * Sends one email and records the send outcome in the database log.
     *
     * @param subject     email subject line
     * @param htmlBody    HTML body content
     * @param toList      primary recipient addresses (must not be empty)
     * @param ccList      CC addresses (may be null)
     * @param attachments attachment files (may be null)
     * @param result      the Result object to accumulate success/failure counts into
     * @param label       human-readable label for this send operation (used in log messages)
     */
    private void sendOneWithLog(String subject, String htmlBody, List<String> toList, List<String> ccList,
                                List<File> attachments, Result result, String label) {
        EmailSentLogEntity logEntity = new EmailSentLogEntity();
        logEntity.setSubject(subject);
        logEntity.setTo(toList.toString());
        logEntity.setCc(ccList != null && !ccList.isEmpty() ? ccList.toString() : null);
        logEntity.setContent(htmlBody);
        logEntity.setAttachment(attachments != null ? attachments.toString() : null);
        logEntity.setSendTime(new Date());

        try {
            EmailUtil.EmailMessage message = EmailUtil.EmailMessage.builder()
                    .to(new ArrayList<>(toList))
                    .cc(ccList != null && !ccList.isEmpty() ? new ArrayList<>(ccList) : null)
                    .subject(subject)
                    .htmlBody(htmlBody)
                    .attachments(attachments)
                    .build();
            EmailUtil.sendEmail(message);
            logEntity.setSuccess(true);
            result.successCount++;
            log.info("Email sent successfully for tag {} to {} recipients", label, toList.size());
        } catch (Exception e) {
            logEntity.setSuccess(false);
            result.failCount++;
            log.error("Email send failed for tag {}", label, e);
        }

        try (SqlSession session = DatabaseInit.getSqlSession()) {
            session.getMapper(EmailSentLogMapper.class).insert(logEntity);
            session.commit();
        } catch (Exception dbEx) {
            log.error("Failed to persist sent log", dbEx);
        }
    }

    /**
     * Parses attachment files in the given directory and groups them by tag.
     * The tag is extracted as the substring between the last underscore ({@code _})
     * and the last dot ({@code .}) in the filename.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code report_2024_Q1.xlsx} → tag {@code "Q1"}</li>
     *   <li>{@code data_test_important.xlsx} → tag {@code "important"}</li>
     *   <li>{@code plainfile.xlsx} → no tag (ignored)</li>
     * </ul>
     *
     * @param attachmentPath directory path to scan for attachment files (may be null or blank;
     *                       in that case an empty map is returned)
     * @return an ordered map from tag name to the list of files sharing that tag;
     *         never null
     */
    public Map<String, List<File>> parseAttachmentFiles(String attachmentPath) {
        Map<String, List<File>> result = new LinkedHashMap<>();
        if (attachmentPath == null || attachmentPath.trim().isEmpty()) return result;
        File dir = new File(attachmentPath);
        if (!dir.exists() || !dir.isDirectory()) return result;
        File[] files = dir.listFiles();
        if (files == null) return result;

        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            int lastUnderscore = name.lastIndexOf('_');
            int lastDot = name.lastIndexOf('.');
            if (lastUnderscore > 0 && lastDot > lastUnderscore) {
                String tag = name.substring(lastUnderscore + 1, lastDot);
                result.computeIfAbsent(tag, k -> new ArrayList<>()).add(f);
            }
        }
        return result;
    }

    /**
     * Parses a tag field (stored as a JSON array string like {@code "[1,2,3]"} or a
     * comma-separated string like {@code "1,2,3"}) into a set of Long tag IDs.
     *
     * @param tagsJson the raw tag string from the database; may be null or blank,
     *                 in which case an empty set is returned
     * @return a set of parsed tag IDs (never null)
     */
    private Set<Long> parseTagIdSet(String tagsJson) {
        Set<Long> ids = new HashSet<>();
        if (tagsJson == null || tagsJson.isBlank()) return ids;
        // Support both "[1,2,3]" and "1,2,3" formats
        Matcher m = TAG_ID_PATTERN.matcher(tagsJson);
        while (m.find()) {
            try {
                ids.add(Long.parseLong(m.group()));
            } catch (NumberFormatException ignored) {}
        }
        return ids;
    }

    /**
     * Result of an email send operation, containing success and failure counts
     * and an optional error message.
     */
    public static class Result {
        /** Number of emails sent successfully. */
        public int successCount;
        /** Number of emails that failed to send. */
        public int failCount;
        /** Error message describing the failure, or null on full success. */
        public String errorMessage;
    }
}
