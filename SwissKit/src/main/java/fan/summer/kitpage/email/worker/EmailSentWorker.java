package fan.summer.kitpage.email.worker;

import com.alibaba.fastjson2.JSON;
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

import javax.swing.*;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Background plugin.swisskit.hpl.worker for sending emails with attachments.
 * Supports both single recipient mode and mass sending mode based on email tags.
 *
 * <p>Mass sending mode workflow:</p>
 * <ol>
 *   <li>Load all email addresses from address book</li>
 *   <li>Load mass sending configuration by taskId</li>
 *   <li>Parse attachment files and extract tags from filenames</li>
 *   <li>For each tag, find matching recipients and send emails</li>
 * </ol>
 *
 * @author phoebej
 * @version 1.00
 * @Date 2026/3/11
 */
public class EmailSentWorker extends SwingWorker<Void, Integer> {

    private static final Logger log = LoggerFactory.getLogger(EmailSentWorker.class);

    private String taskId;
    private String subject;
    private String body;
    private List<String> toTags;
    private List<String> ccTags;
    private String attachmentPath;
    private boolean isMassModel;
    private JProgressBar progressBar;

    /**
     * Constructor for single recipient mode (non-mass sending).
     *
     * @param subject        email subject
     * @param body           email body content
     * @param toTags         list of recipient email addresses
     * @param ccTags         list of CC email addresses
     * @param attachmentPath path to attachment file (can be null)
     * @param isMassModel    flag indicating if mass sending mode is enabled
     */
    public EmailSentWorker(String subject, String body, List<String> toTags, List<String> ccTags, String attachmentPath, boolean isMassModel) {
        this.subject = subject;
        this.body = body;
        this.toTags = toTags;
        this.ccTags = ccTags;
        this.attachmentPath = attachmentPath;
        this.isMassModel = isMassModel;
        log.debug("EmailSentWorker initialized in single recipient mode, isMassModel: {}", isMassModel);
    }

    /**
     * Constructor for mass sending mode.
     *
     * @param subject     email subject
     * @param body        email body content
     * @param taskId      unique task identifier for mass sending
     * @param isMassModel flag indicating if mass sending mode is enabled
     */
    public EmailSentWorker(String subject, String body, String taskId, boolean isMassModel, JProgressBar progressBar) {
        this.subject = subject;
        this.body = body;
        this.taskId = taskId;
        this.isMassModel = isMassModel;
        this.progressBar = progressBar;
        log.debug("EmailSentWorker initialized in mass sending mode, taskId: {}, isMassModel: {}", taskId, isMassModel);
    }

    /**
     * Default constructor.
     */
    public EmailSentWorker() {
        log.debug("EmailSentWorker initialized with default constructor");
    }

    @Override
    protected Void doInBackground() throws Exception {
        log.info("Starting email sending task, isMassModel: {}", isMassModel);
        if (progressBar != null) {
            SwingUtilities.invokeLater(() -> {
                progressBar.setValue(0);
                progressBar.setStringPainted(true);
                progressBar.setString("Sending... 0%");
            });
        }

        if (isMassModel) {
            executeMassSending();
        } else {
            log.info("Single recipient mode - no action needed in this plugin.swisskit.hpl.worker");
        }

        log.info("Email sending task completed");
        return null;
    }

    @Override
    protected void process(List<Integer> chunks) {
        if (!chunks.isEmpty()) {
            int latestProgress = chunks.get(chunks.size() - 1);
            progressBar.setValue(latestProgress);
            progressBar.setString("Sending... " + latestProgress + "%");
        }
    }

    @Override
    protected void done() {
        // EDT thread: Task completion
        try {
            get(); // Check for exceptions
            progressBar.setValue(100);
            progressBar.setString("Sending completed!");
        } catch (Exception ex) {
            progressBar.setString("Sending failed!");
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(progressBar), "Email sending task failed!，Info:" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Executes mass sending logic.
     * Loads email addresses, config, parses attachments, and sends emails to recipients based on tags.
     */
    private void executeMassSending() {
        log.info("Starting mass sending process for taskId: {}", taskId);
        int totalEmails = 0;

        // Step 1: Load all email addresses from address book
        log.debug("Step 1: Loading all email addresses from address book");
        List<EmailAddressBookEntity> allEmailAddresses;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            EmailAddressBookMapper mapper = session.getMapper(EmailAddressBookMapper.class);
            allEmailAddresses = mapper.selectEmailAddressBook();
            session.commit();
            log.info("Loaded {} email addresses from address book", allEmailAddresses.size());
        } catch (Exception e) {
            log.error("Failed to load email addresses from database", e);
            return;
        }

        // Step 2: Load All Tags
        List<EmailTagEntity> emailTags;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            EmailTagMapper mapper = session.getMapper(EmailTagMapper.class);
            emailTags = mapper.selectAll();
            if (emailTags == null) {
                log.error("No email tags found in database");
                return;
            }
        } catch (Exception e) {
            log.error("Failed to load email tags from database", e);
            return;
        }
        Map<String, List<EmailTagEntity>> tagCollect = emailTags.stream().collect(Collectors.groupingBy(EmailTagEntity::getTag));

        // Step 2: Load mass sending configuration by taskId
        log.debug("Step 2: Loading mass sending configuration for taskId: {}", taskId);
        EmailMassSentConfigEntity config;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            EmailMassSentConfigMapper mapper = session.getMapper(EmailMassSentConfigMapper.class);
            config = mapper.selectByTaskId(taskId);
            session.commit();

            if (config == null) {
                log.error("No configuration found for taskId: {}", taskId);
                return;
            }
            log.debug("Loaded config - ToTag: {}, CcTag: {}, SendAttachment: {}",
                    config.getToTag(), config.getCcTag(), config.isSentAtt());
        } catch (Exception e) {
            log.error("Failed to load mass sending configuration", e);
            return;
        }

        // Step 3: Parse attachment files and extract tags from filenames
        log.debug("Step 3: Parsing attachment files from: {}", config.getAttFolderPath());
        Map<String, List<File>> taggedFiles = parseAttachmentFiles(config.getAttFolderPath());
        log.info("Found {} unique tags from attachment files", taggedFiles.size());

        if (taggedFiles.isEmpty()) {
            log.warn("No attachment files found in directory: {}", config.getAttFolderPath());
            return;
        }

        // Step 4: Iterate through each tag and send emails
        List<String> toList = new ArrayList<>();
        List<String> ccList = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        log.debug("Step 4: Starting to iterate through each tag and send emails");
        totalEmails = taggedFiles.entrySet().size();
        for (Map.Entry<String, List<File>> entry : taggedFiles.entrySet()) {
            if (isCancelled()) {
                log.info("Mass sending cancelled by user");
                return;
            }
            EmailTagEntity fileTag;
            List<EmailTagEntity> tagList = tagCollect.get(entry.getKey());
            if (tagList == null || tagList.isEmpty()) {
                log.warn("No tag found for key: {}, skipping", entry.getKey());
                continue;
            }
            try {
                fileTag = tagList.get(0);
            } catch (Exception e) {
                log.warn("Failed to parse attachment file: {}", entry.getKey());
                continue;
            }

            List<File> files = entry.getValue();
            log.debug("Processing tag: {} with {} attachment files", fileTag, files.size());

            // Clear recipient lists for each iteration
            toList.clear();
            ccList.clear();

            // Build recipient lists based on tags
            log.debug("Building recipient lists for tag: {}", fileTag);
            for (EmailAddressBookEntity entity : allEmailAddresses) {
                // Parse tags from JSON string
                List<Long> emailTagIds;
                try {
                    emailTagIds = JSON.parseArray(entity.getTags(), Long.class);
                } catch (Exception e) {
                    log.warn("Failed to parse tags for email: {}, skipping", entity.getEmailAddress(), e);
                    continue;
                }

                // Check if contact has the To tag
                if (emailTagIds.contains(fileTag.getId())) {
                    if (config.getToTag() != null && emailTagIds.contains(Long.parseLong(config.getToTag()))) {
                        toList.add(entity.getEmailAddress());
                        log.trace("Added {} to To list (matched tag: {})", entity.getEmailAddress(), config.getToTag());
                    }

                    // Check if contact has the Cc tag
                    if (config.getCcTag() != null && emailTagIds.contains(Long.parseLong(config.getCcTag()))) {
                        ccList.add(entity.getEmailAddress());
                        log.trace("Added {} to Cc list (matched tag: {})", entity.getEmailAddress(), config.getCcTag());
                    }
                }
            }

            // Skip if no recipients found
            if (toList.isEmpty()) {
                log.warn("No recipients found for tag: {}, skipping", fileTag);
                continue;
            }

            log.info("Sending email for tag: {}, To: {} recipients, Cc: {} recipients",
                    fileTag, toList.size(), ccList.size());

            // Build and send email with attachments
            EmailSentLogEntity emailSentLogEntity = new EmailSentLogEntity();
            emailSentLogEntity.setSubject(subject);
            emailSentLogEntity.setTo(toList.toString());
            emailSentLogEntity.setCc(ccList.isEmpty() ? null : ccList.toString());
            emailSentLogEntity.setContent(body);
            emailSentLogEntity.setAttachment(files.toString());
            emailSentLogEntity.setSendTime(new Date());
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                EmailUtil.EmailMessage message = EmailUtil.EmailMessage.builder()
                        .to(toList)
                        .cc(ccList.isEmpty() ? null : ccList)
                        .subject(subject)
                        .textBody(body)
                        .attachments(files)
                        .build();

                EmailUtil.sendEmail(message);
                log.info("Email sent successfully for tag: {} to {} recipients", fileTag, toList.size());
                successCount++;
                // Log success to database
                EmailSentLogMapper mapper = session.getMapper(EmailSentLogMapper.class);
                emailSentLogEntity.setSuccess(true);
                mapper.insert(emailSentLogEntity);
                session.commit();
            } catch (EmailUtil.EmailException e) {
                log.error("Failed to send email for tag: {} - Error: {}", fileTag, e.getMessage(), e);
                failCount++;
                // Log failure to database
                try (SqlSession session = DatabaseInit.getSqlSession()) {
                    try {
                        EmailSentLogMapper mapper = session.getMapper(EmailSentLogMapper.class);
                        emailSentLogEntity.setSuccess(false);
                        mapper.insert(emailSentLogEntity);
                        session.commit();
                    } catch (Exception dbEx) {
                        log.error("Failed to save error log to database", dbEx);
                    }
                }
            }
            publish(successCount * 100 / totalEmails);
        }


        log.info("Mass sending completed - Success: {}, Failed: {}", successCount, failCount);
    }

    /**
     * Parses all files in the given directory and extracts tags from filenames.
     * Tags are extracted from the characters between the last underscore (_) and the file extension (.).
     *
     * <p>Example:</p>
     * <ul>
     *   <li>report_2024_Q1.xlsx → tag: "2024_Q1"</li>
     *   <li>summary_finance_monthly.pdf → tag: "finance_monthly"</li>
     *   <li>data_test_important.xlsx → tag: "test_important"</li>
     * </ul>
     *
     * @param attachmentPath the directory path containing attachment files
     * @return a map with tag as key and list of Files as value
     */
    private Map<String, List<File>> parseAttachmentFiles(String attachmentPath) {
        Map<String, List<File>> taggedFiles = new HashMap<>();

        if (attachmentPath == null || attachmentPath.trim().isEmpty()) {
            log.debug("Attachment path is null or empty, skipping file parsing");
            return taggedFiles;
        }

        File dir = new File(attachmentPath);

        if (!dir.exists()) {
            log.warn("Attachment directory does not exist: {}", attachmentPath);
            return taggedFiles;
        }

        if (!dir.isDirectory()) {
            log.warn("Attachment path is not a directory: {}", attachmentPath);
            return taggedFiles;
        }

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            log.debug("No files found in attachment directory: {}", attachmentPath);
            return taggedFiles;
        }

        log.debug("Found {} files in attachment directory, starting tag extraction", files.length);

        for (File file : files) {
            if (!file.isFile()) {
                log.trace("Skipping directory: {}", file.getName());
                continue;
            }

            String fileName = file.getName();
            int lastUnderscoreIndex = fileName.lastIndexOf('_');
            int lastDotIndex = fileName.lastIndexOf('.');

            // Must have both underscore and dot, and underscore must come before dot
            if (lastUnderscoreIndex > 0 && lastDotIndex > lastUnderscoreIndex) {
                String tag = fileName.substring(lastUnderscoreIndex + 1, lastDotIndex);
                taggedFiles.computeIfAbsent(tag, k -> new ArrayList<>()).add(file);
                log.debug("Parsed file: {} -> tag: {}", fileName, tag);
            } else {
                log.debug("Skipping file (no valid tag format): {}", fileName);
            }
        }

        log.debug("Finished parsing attachment files, found {} unique tags", taggedFiles.size());
        return taggedFiles;
    }
}