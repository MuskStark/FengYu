package fan.summer.zhiflow.buildintool.emailarchive;

import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.zhiflow.database.DatabaseInit;
import fan.summer.zhiflow.database.entity.email.EmailArchiveEntity;
import fan.summer.zhiflow.database.entity.setting.email.ZhiFlowSettingEmailEntity;
import fan.summer.zhiflow.database.mapper.email.EmailArchiveMapper;
import fan.summer.zhiflow.database.mapper.setting.email.ZhiFlowSettingEmailMapper;
import jakarta.mail.*;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import org.apache.ibatis.session.SqlSession;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class EmailArchiveService {

    private static final PluginLogger log = LoggerFactory.getLogger(EmailArchiveService.class);

    public interface ProgressCallback {
        void update(double progress, String message);
    }

    public static class ArchiveResult {
        public int totalFetched;
        public int newArchived;
        public int skippedDuplicates;
        public int errors;
        public String errorMessage;
    }

    public ArchiveResult archive(EmailArchiveConfig config, ProgressCallback cb) {
        ArchiveResult result = new ArchiveResult();

        ZhiFlowSettingEmailEntity account = loadAccount(config.getAccountEmail());
        if (account == null) {
            result.errorMessage = "No email account found: " + config.getAccountEmail();
            return result;
        }
        if (account.getImapAddress() == null || account.getImapAddress().isBlank()) {
            result.errorMessage = "IMAP not configured for account: " + config.getAccountEmail();
            return result;
        }

        Path outputDir = config.getOutputDir();
        if (outputDir == null) {
            outputDir = Paths.get(".zhiflow", "email-archive");
        }
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            result.errorMessage = "Failed to create output dir: " + e.getMessage();
            return result;
        }

        if (cb != null) cb.update(0.0, "Connecting to IMAP server...");

        Session session = createImapSession(account);
        try (Store store = session.getStore("imaps")) {
            store.connect(account.getImapAddress(),
                    account.getImapPort() != null ? account.getImapPort() : 993,
                    account.getEmail(), account.getPassword());

            Folder folder = store.getFolder(config.getImapFolder());
            if (folder == null || !folder.exists()) {
                result.errorMessage = "Folder not found: " + config.getImapFolder();
                return result;
            }
            folder.open(Folder.READ_ONLY);

            if (cb != null) cb.update(0.1, "Fetching messages...");

            Message[] messages;
            Date since = null;
            if (config.getStartDate() != null) {
                since = java.sql.Date.valueOf(config.getStartDate());
            } else if (config.getDays() > 0) {
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_MONTH, -config.getDays());
                since = cal.getTime();
            }

            if (since != null) {
                messages = folder.search(new ReceivedDateTerm(ComparisonTerm.GT, since));
            } else {
                messages = folder.getMessages();
            }

            result.totalFetched = messages.length;
            if (cb != null) cb.update(0.2, "Found " + messages.length + " messages, archiving...");

            for (int i = 0; i < messages.length; i++) {
                try {
                    processMessage(messages[i], config, outputDir, result);
                } catch (Exception e) {
                    result.errors++;
                    log.error("Failed to archive message {}: {}", i, e.getMessage());
                }
                if (cb != null && (i % 10 == 0 || i == messages.length - 1)) {
                    double pct = 0.2 + 0.8 * (i + 1.0) / messages.length;
                    cb.update(pct, "Archiving " + (i + 1) + "/" + messages.length);
                }
            }

            folder.close(false);
        } catch (Exception e) {
            result.errorMessage = "IMAP error: " + e.getMessage();
            log.error("Archive failed", e);
            return result;
        }

        if (cb != null) cb.update(1.0, "Done");
        return result;
    }

    private void processMessage(Message msg, EmailArchiveConfig config, Path outputDir,
                                 ArchiveResult result) throws Exception {
        String uid = getMessageUid(msg);
        String folderName = config.getImapFolder();

        try (SqlSession session = DatabaseInit.getSqlSession()) {
            EmailArchiveMapper mapper = session.getMapper(EmailArchiveMapper.class);
            EmailArchiveEntity existing = mapper.selectByUid(config.getAccountEmail(), folderName, uid);
            if (existing != null) {
                result.skippedDuplicates++;
                return;
            }
        }

        String safeSubject = sanitizeFilename(
                msg.getSubject() != null ? msg.getSubject() : "no-subject");
        String filename = safeSubject + "_" + uid + ".eml";
        Path emlFile = outputDir.resolve(filename);

        try (OutputStream os = Files.newOutputStream(emlFile)) {
            msg.writeTo(os);
        }

        EmailArchiveEntity entity = new EmailArchiveEntity();
        entity.setAccountEmail(config.getAccountEmail());
        entity.setFolder(folderName);
        entity.setMessageUid(uid);
        entity.setSubject(msg.getSubject());
        entity.setFromAddress(addressToString(msg.getFrom()));
        entity.setToAddress(addressToString(msg.getRecipients(Message.RecipientType.TO)));
        entity.setCcAddress(addressToString(msg.getRecipients(Message.RecipientType.CC)));
        entity.setSendDate(msg.getSentDate());
        entity.setHasAttachment(hasAttachment(msg));
        entity.setEmlPath(emlFile.toString());
        entity.setBodyPreview(extractPreview(msg));
        entity.setArchivedAt(new Date());

        try (SqlSession session = DatabaseInit.getSqlSession()) {
            session.getMapper(EmailArchiveMapper.class).insert(entity);
            session.commit();
        }

        result.newArchived++;
    }

    public List<EmailArchiveEntity> query(String accountEmail, String fromAddress,
                                            String subject, Date startDate, Date endDate) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            EmailArchiveMapper mapper = session.getMapper(EmailArchiveMapper.class);
            return mapper.selectByQuery(accountEmail, fromAddress, subject, startDate, endDate);
        } catch (Exception e) {
            log.error("Query failed: {}", e.getMessage());
            return List.of();
        }
    }

    public ZhiFlowSettingEmailEntity loadAccount(String email) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            ZhiFlowSettingEmailMapper mapper =
                    session.getMapper(ZhiFlowSettingEmailMapper.class);
            ZhiFlowSettingEmailEntity config = mapper.selectLatest();
            if (config == null) return null;
            if (email != null && !email.equals(config.getEmail())) return null;
            return config;
        } catch (Exception e) {
            log.error("Load account failed: {}", e.getMessage());
            return null;
        }
    }

    private Session createImapSession(ZhiFlowSettingEmailEntity account) {
        Properties props = new Properties();
        String host = account.getImapAddress();
        int port = account.getImapPort() != null ? account.getImapPort() : 993;
        boolean ssl = account.getImapSSL() == null || account.getImapSSL();

        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", host);
        props.put("mail.imaps.port", String.valueOf(port));
        if (ssl) {
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.ssl.trust", "*");
        }
        return Session.getInstance(props);
    }

    private String getMessageUid(Message msg) throws MessagingException {
        Folder folder = msg.getFolder();
        if (folder instanceof UIDFolder uidFolder) {
            return String.valueOf(uidFolder.getUID(msg));
        }
        return String.valueOf(msg.hashCode());
    }

    private String addressToString(Address[] addresses) {
        if (addresses == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < addresses.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(addresses[i].toString());
        }
        return sb.toString();
    }

    private boolean hasAttachment(Message msg) throws Exception {
        Object content = msg.getContent();
        if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                if (Part.ATTACHMENT.equalsIgnoreCase(mp.getBodyPart(i).getDisposition())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String extractPreview(Message msg) throws Exception {
        Object content = msg.getContent();
        String text = null;
        if (content instanceof String s) {
            text = s;
        } else if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                if (part.isMimeType("text/plain")) {
                    text = (String) part.getContent();
                    break;
                }
            }
        }
        if (text == null) return null;
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() > 500 ? clean.substring(0, 500) : clean;
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_")
                    .replaceAll("_+", "_")
                    .trim();
    }
}
