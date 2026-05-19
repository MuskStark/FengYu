package fan.summer.buildintool.email;

import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.email.EmailMassSentConfigEntity;
import fan.summer.database.entity.email.EmailSentLogEntity;
import fan.summer.database.entity.setting.email.EmailAddressBookEntity;
import fan.summer.database.entity.setting.email.EmailTagEntity;
import fan.summer.database.mapper.email.EmailMassSentConfigMapper;
import fan.summer.database.mapper.email.EmailSentLogMapper;
import fan.summer.database.mapper.setting.email.EmailAddressBookMapper;
import fan.summer.database.mapper.setting.email.EmailTagMapper;
import fan.summer.utils.EmailUtil;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Email sending service supporting both single recipient and mass-by-tag modes.
 *
 * Mass mode workflow:
 *  1. Load all address book entries
 *  2. Load mass sending config by taskId
 *  3. Parse attachment folder, grouping files by tag suffix (between last "_" and ".")
 *  4. For each tag, resolve recipients from address book and send
 */
public class EmailSendService {

    private static final Logger log = LoggerFactory.getLogger(EmailSendService.class);
    private static final Pattern TAG_ID_PATTERN = Pattern.compile("\\d+");

    public interface ProgressCallback {
        void update(double progress, String message);
    }

    /**
     * Sends a single email immediately. Recipients are concrete addresses, not tags.
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
     * Executes mass sending for the given taskId. Reads address book, tag list, config and
     * attachment folder; sends one email per tag with the matching attachments.
     */
    public Result sendMass(String subject, String htmlBody, String taskId, ProgressCallback progress) {
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

        if (progress != null) progress.update(0.1, "Loading config...");
        EmailMassSentConfigEntity config;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            config = session.getMapper(EmailMassSentConfigMapper.class).selectByTaskId(taskId);
            if (config == null) {
                result.errorMessage = "No configuration found for taskId: " + taskId;
                return result;
            }
        } catch (Exception e) {
            log.error("Failed to load mass config", e);
            result.errorMessage = "Failed to load config: " + e.getMessage();
            return result;
        }

        if (progress != null) progress.update(0.15, "Parsing attachments...");
        Map<String, List<File>> taggedFiles = parseAttachmentFiles(config.getAttFolderPath());
        if (taggedFiles.isEmpty()) {
            result.errorMessage = "No attachment files found with valid tag format in: " + config.getAttFolderPath();
            return result;
        }

        int total = taggedFiles.size();
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

            toList.clear();
            ccList.clear();
            Long toTagId = parseLong(config.getToTag());
            Long ccTagId = parseLong(config.getCcTag());

            for (EmailAddressBookEntity addr : allAddresses) {
                List<Long> contactTagIds = parseTagIds(addr.getTags());
                if (!contactTagIds.contains(fileTag.getId())) continue;

                if (toTagId != null && contactTagIds.contains(toTagId)) {
                    toList.add(addr.getEmailAddress());
                }
                if (ccTagId != null && contactTagIds.contains(ccTagId)) {
                    ccList.add(addr.getEmailAddress());
                }
            }

            if (toList.isEmpty()) {
                log.warn("No recipients found for tag {}", tagName);
                continue;
            }

            if (progress != null) {
                double pct = 0.15 + 0.85 * processed / total;
                progress.update(pct, "Sending [" + processed + "/" + total + "] " + tagName);
            }

            EmailSentLogEntity logEntity = new EmailSentLogEntity();
            logEntity.setSubject(subject);
            logEntity.setTo(toList.toString());
            logEntity.setCc(ccList.isEmpty() ? null : ccList.toString());
            logEntity.setContent(htmlBody);
            logEntity.setAttachment(files.toString());
            logEntity.setSendTime(new Date());

            try {
                EmailUtil.EmailMessage message = EmailUtil.EmailMessage.builder()
                        .to(new ArrayList<>(toList))
                        .cc(ccList.isEmpty() ? null : new ArrayList<>(ccList))
                        .subject(subject)
                        .htmlBody(htmlBody)
                        .attachments(config.isSentAtt() ? files : null)
                        .build();
                EmailUtil.sendEmail(message);
                logEntity.setSuccess(true);
                result.successCount++;
                log.info("Email sent successfully for tag {} to {} recipients", tagName, toList.size());
            } catch (Exception e) {
                logEntity.setSuccess(false);
                result.failCount++;
                log.error("Email send failed for tag {}", tagName, e);
            }

            try (SqlSession session = DatabaseInit.getSqlSession()) {
                session.getMapper(EmailSentLogMapper.class).insert(logEntity);
                session.commit();
            } catch (Exception dbEx) {
                log.error("Failed to persist sent log", dbEx);
            }
        }

        if (progress != null) progress.update(1.0, "Done");
        return result;
    }

    /**
     * Parses attachment files in the given directory. Tag is the substring between the last
     * underscore (_) and the file extension dot (.).
     * Example: report_2024_Q1.xlsx → tag "Q1"; data_test_important.xlsx → tag "important".
     */
    public Map<String, List<File>> parseAttachmentFiles(String attachmentPath) {
        Map<String, List<File>> result = new HashMap<>();
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
     * Parses a tag JSON array string (e.g. "[1,2,3]") into a list of longs without using
     * a JSON library — extracts all numeric literals.
     */
    private List<Long> parseTagIds(String tagsJson) {
        List<Long> ids = new ArrayList<>();
        if (tagsJson == null || tagsJson.isBlank()) return ids;
        Matcher m = TAG_ID_PATTERN.matcher(tagsJson);
        while (m.find()) {
            try {
                ids.add(Long.parseLong(m.group()));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    private Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static class Result {
        public int successCount;
        public int failCount;
        public String errorMessage;
    }
}
