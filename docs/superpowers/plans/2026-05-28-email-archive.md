# Email Archive Tool — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a built-in email archive tool that connects to IMAP servers via existing account configs, archives emails to local `.eml` files with H2 metadata, and exposes full AI-callable tools for fetch and query.

**Architecture:** 3-step wizard UI (SwissKitJPlugin + StepWizard), core IMAP service using Jakarta Mail (angus-mail, already in deps), two AI tools (fetch + query) bound to the plugin instance. All accounts share `swiss_kit_setting_email` table extended with IMAP fields.

**Tech Stack:** JavaFX, Jakarta Mail IMAP (angus-mail), H2 + MyBatis, Gson (JsonHelper), StepWizard, Lombok

**Build:** IDEA MCP only — no system Maven. Use `mcp__idea__build_project` to verify after each task.

---

## Task 1: Database — Entity, Mapper XML, init.sql, mybatis-config

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/database/entity/email/EmailArchiveEntity.java`
- Create: `SwissKit/src/main/java/fan/summer/database/mapper/email/EmailArchiveMapper.java`
- Create: `SwissKit/src/main/resources/mapper/email/EmailArchiveMapper.xml`
- Modify: `SwissKit/src/main/resources/init.sql`
- Modify: `SwissKit/src/main/resources/mybatis-config.xml`

- [ ] **Step 1: Create EmailArchiveEntity**

```java
package fan.summer.database.entity.email;

import lombok.Data;
import java.util.Date;

@Data
public class EmailArchiveEntity {
    private Integer id;
    private String accountEmail;
    private String folder;
    private String messageUid;
    private String subject;
    private String fromAddress;
    private String toAddress;
    private String ccAddress;
    private Date sendDate;
    private Boolean hasAttachment;
    private String emlPath;
    private String bodyPreview;
    private Date archivedAt;
}
```

- [ ] **Step 2: Create EmailArchiveMapper interface**

```java
package fan.summer.database.mapper.email;

import fan.summer.database.entity.email.EmailArchiveEntity;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface EmailArchiveMapper {
    int insert(EmailArchiveEntity entity);
    EmailArchiveEntity selectByUid(@Param("accountEmail") String accountEmail,
                                    @Param("folder") String folder,
                                    @Param("messageUid") String messageUid);
    List<EmailArchiveEntity> selectByQuery(@Param("accountEmail") String accountEmail,
                                            @Param("fromAddress") String fromAddress,
                                            @Param("subject") String subject,
                                            @Param("startDate") Date startDate,
                                            @Param("endDate") Date endDate);
}
```

- [ ] **Step 3: Create EmailArchiveMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper
        PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="fan.summer.database.mapper.email.EmailArchiveMapper">

    <insert id="insert" parameterType="fan.summer.database.entity.email.EmailArchiveEntity"
            useGeneratedKeys="true" keyProperty="id">
        INSERT INTO email_archive (account_email, folder, message_uid, subject,
                from_address, to_address, cc_address, send_date, has_attachment,
                eml_path, body_preview, archived_at)
        VALUES (#{accountEmail}, #{folder}, #{messageUid}, #{subject},
                #{fromAddress}, #{toAddress}, #{ccAddress}, #{sendDate}, #{hasAttachment},
                #{emlPath}, #{bodyPreview}, #{archivedAt})
    </insert>

    <select id="selectByUid" resultType="fan.summer.database.entity.email.EmailArchiveEntity">
        SELECT id, account_email AS accountEmail, folder, message_uid AS messageUid,
               subject, from_address AS fromAddress, to_address AS toAddress,
               cc_address AS ccAddress, send_date AS sendDate,
               has_attachment AS hasAttachment, eml_path AS emlPath,
               body_preview AS bodyPreview, archived_at AS archivedAt
        FROM email_archive
        WHERE account_email = #{accountEmail} AND folder = #{folder} AND message_uid = #{messageUid}
    </select>

    <select id="selectByQuery" resultType="fan.summer.database.entity.email.EmailArchiveEntity">
        SELECT id, account_email AS accountEmail, folder, message_uid AS messageUid,
               subject, from_address AS fromAddress, to_address AS toAddress,
               cc_address AS ccAddress, send_date AS sendDate,
               has_attachment AS hasAttachment, eml_path AS emlPath,
               body_preview AS bodyPreview, archived_at AS archivedAt
        FROM email_archive
        <where>
            <if test="accountEmail != null">AND account_email = #{accountEmail}</if>
            <if test="fromAddress != null">AND from_address LIKE '%' || #{fromAddress} || '%'</if>
            <if test="subject != null">AND subject LIKE '%' || #{subject} || '%'</if>
            <if test="startDate != null">AND send_date &gt;= #{startDate}</if>
            <if test="endDate != null">AND send_date &lt;= #{endDate}</if>
        </where>
        ORDER BY send_date DESC
        LIMIT 100
    </select>

</mapper>
```

- [ ] **Step 4: Update init.sql** — append IMAP ALTER TABLE + email_archive CREATE TABLE

Append to end of `SwissKit/src/main/resources/init.sql`:

```sql
-- Add IMAP fields to email settings (unified send/receive)
ALTER TABLE swiss_kit_setting_email ADD COLUMN IF NOT EXISTS imap_address VARCHAR(255);
ALTER TABLE swiss_kit_setting_email ADD COLUMN IF NOT EXISTS imap_port INTEGER DEFAULT 993;
ALTER TABLE swiss_kit_setting_email ADD COLUMN IF NOT EXISTS imap_ssl INTEGER NOT NULL DEFAULT 1;

-- Email Archive Table
CREATE TABLE IF NOT EXISTS email_archive
(
    id             INTEGER PRIMARY KEY AUTO_INCREMENT,
    account_email  VARCHAR(255) NOT NULL,
    folder         VARCHAR(255) NOT NULL DEFAULT 'INBOX',
    message_uid    VARCHAR(255) NOT NULL,
    subject        VARCHAR(500),
    from_address   VARCHAR(500),
    to_address     VARCHAR(1000),
    cc_address     VARCHAR(1000),
    send_date      TIMESTAMP,
    has_attachment INTEGER      DEFAULT 0,
    eml_path       VARCHAR(1000),
    body_preview   VARCHAR(500),
    archived_at    TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(account_email, folder, message_uid)
);
```

- [ ] **Step 5: Update mybatis-config.xml** — add mapper resource

Add before the closing `</mappers>` tag:

```xml
        <mapper resource="mapper/email/EmailArchiveMapper.xml"/>
```

- [ ] **Step 6: Build verify**

Run: `mcp__idea__build_project(rebuild=true)` — expect PASS.

---

## Task 2: Extend SwissKitSettingEmailEntity with IMAP fields

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/database/entity/setting/email/SwissKitSettingEmailEntity.java`

- [ ] **Step 1: Add IMAP fields**

Add three new fields after `fromAddress`:

```java
    /** IMAP server hostname or IP address. */
    private String imapAddress;

    /** IMAP server port number (e.g., {@code 993} for SSL). */
    private Integer imapPort;

    /** Whether SSL/TLS is required for IMAP connection. */
    private Boolean imapSSL;
```

- [ ] **Step 2: Build verify**

Run: `mcp__idea__build_project(rebuild=true)` — expect PASS.

---

## Task 3: Config POJO + Core Service

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/buildintool/emailarchive/EmailArchiveConfig.java`
- Create: `SwissKit/src/main/java/fan/summer/buildintool/emailarchive/EmailArchiveService.java`

- [ ] **Step 1: Create EmailArchiveConfig**

```java
package fan.summer.buildintool.emailarchive;

import lombok.Data;
import java.nio.file.Path;
import java.time.LocalDate;

@Data
public class EmailArchiveConfig {
    private String accountEmail;
    private String imapFolder = "INBOX";
    private int days = 30;
    private LocalDate startDate;
    private LocalDate endDate;
    private Path outputDir;
}
```

- [ ] **Step 2: Create EmailArchiveService**

```java
package fan.summer.buildintool.emailarchive;

import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.email.EmailArchiveEntity;
import fan.summer.database.entity.setting.email.SwissKitSettingEmailEntity;
import fan.summer.database.mapper.email.EmailArchiveMapper;
import fan.summer.database.mapper.setting.email.SwissKitSettingEmailMapper;
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

        SwissKitSettingEmailEntity account = loadAccount(config.getAccountEmail());
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
            outputDir = Paths.get(".swisskit", "email-archive");
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
            if (config.getStartDate() != null || config.getDays() > 0) {
                Date since;
                if (config.getStartDate() != null) {
                    since = java.sql.Date.valueOf(config.getStartDate());
                } else {
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.DAY_OF_MONTH, -config.getDays());
                    since = cal.getTime();
                }
                messages = folder.search(new ReceivedDateTerm(ComparisonTerm.GT, since));
            } else if (config.getStartDate() != null && config.getEndDate() != null) {
                messages = folder.search(new ReceivedDateTerm(ComparisonTerm.GT,
                        java.sql.Date.valueOf(config.getStartDate())));
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

    public SwissKitSettingEmailEntity loadAccount(String email) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            SwissKitSettingEmailMapper mapper =
                    session.getMapper(SwissKitSettingEmailMapper.class);
            List<SwissKitSettingEmailEntity> all = mapper.selectEmailAddressList();
            if (all == null) return null;
            return all.stream()
                    .filter(e -> email.equals(e.getEmail()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.error("Load account failed: {}", e.getMessage());
            return null;
        }
    }

    private Session createImapSession(SwissKitSettingEmailEntity account) {
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
        if (folder != null) {
            UIDFolder uidFolder = (UIDFolder) folder;
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
```

- [ ] **Step 3: Build verify**

Run: `mcp__idea__build_project(rebuild=true)` — expect PASS.

- [ ] **Step 4: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/database/entity/email/EmailArchiveEntity.java \
        SwissKit/src/main/java/fan/summer/database/mapper/email/EmailArchiveMapper.java \
        SwissKit/src/main/resources/mapper/email/EmailArchiveMapper.xml \
        SwissKit/src/main/java/fan/summer/database/entity/setting/email/SwissKitSettingEmailEntity.java \
        SwissKit/src/main/java/fan/summer/buildintool/emailarchive/EmailArchiveConfig.java \
        SwissKit/src/main/java/fan/summer/buildintool/emailarchive/EmailArchiveService.java \
        SwissKit/src/main/resources/init.sql \
        SwissKit/src/main/resources/mybatis-config.xml
git commit -m "✨ feat(email-archive): add database layer, IMAP fields, and core archive service"
```

---

## Task 4: UI Plugin — EmailArchivePlugin with 3-step wizard

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/buildintool/emailarchive/EmailArchivePlugin.java`

- [ ] **Step 1: Create EmailArchivePlugin**

Full file at `SwissKit/src/main/java/fan/summer/buildintool/emailarchive/EmailArchivePlugin.java`. The class implements `SwissKitJPlugin`, uses `StepWizard` with 3 steps:

**Metadata:** id=`fan.summer.buildin.email-archive`, category=NET, icon=`email-check`, style=TEAL, type=BUILTIN.

**Step 1 (Select Account):** Loads accounts from H2 via `SwissKitSettingEmailMapper.selectEmailAddressList()`, shows ComboBox. Warning if no accounts or no IMAP config.

**Step 2 (Archive Config):** Spinner for "last N days" (default 30), ComboBox for folder (INBOX, Sent, Drafts, Trash), DirectoryChooser for output dir (default `.swisskit/email-archive/`).

**Step 3 (Execute):** Background `Task<ArchiveResult>`, ProgressBar + status label, result summary with "Open Folder" button.

Follow the `ExcelSplitterPlugin` pattern exactly: inner classes `Step1View`, `Step2View`, `Step3View` extending `VBox`, shared `EmailArchiveConfig`, `StepWizard` with `addStep` + `build` + `setOnStepChanged`.

Key UI helpers reused from ExcelSplitter pattern: `sectionTitle()`, `subLabel()`, `glassBtn()`, `fieldStyle()`, `comboStyle()`.

Use `I18n.get()` for all user-visible strings with keys from spec.

**Important layout rules:**
- Root VBox: `setPadding(Insets(24))`, `setStyle("-fx-background-color: transparent;")`
- ProgressBar: `setMaxWidth(Double.MAX_VALUE)`
- ComboBox: `setMaxWidth(Double.MAX_VALUE)`
- StepWizard: `VBox.setVgrow(wizard, Priority.ALWAYS)`

The plugin exposes `getConfig()` getter for AI tools to access shared state.

- [ ] **Step 2: Build verify**

Run: `mcp__idea__build_project(rebuild=true)` — expect PASS.

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/emailarchive/EmailArchivePlugin.java
git commit -m "✨ feat(email-archive): add 3-step wizard UI plugin"
```

---

## Task 5: AI Tools — EmailArchiveFetchTool + EmailArchiveQueryTool

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/buildintool/ai/EmailArchiveFetchTool.java`
- Create: `SwissKit/src/main/java/fan/summer/buildintool/ai/EmailArchiveQueryTool.java`

- [ ] **Step 1: Create EmailArchiveFetchTool**

```java
package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import fan.summer.buildintool.emailarchive.*;

import java.nio.file.*;
import java.util.*;

public class EmailArchiveFetchTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(EmailArchiveFetchTool.class);
    private final EmailArchivePlugin plugin;

    public EmailArchiveFetchTool(EmailArchivePlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "email_archive_fetch"; }

    @Override public String getDescription() {
        return "Connect to IMAP server and archive emails to local storage. " +
               "Call this to fetch and save emails. " +
               "Args: accountEmail (string, required) — the configured email account; " +
               "days (integer, optional) — fetch from last N days, default 30; " +
               "folder (string, optional) — IMAP folder, default INBOX; " +
               "outputDir (string, optional) — local directory for .eml files.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("accountEmail", "string", "Configured email account address", true),
            AiToolParam.of("days", "integer", "Fetch emails from last N days (default 30)", false),
            AiToolParam.of("folder", "string", "IMAP folder name (default INBOX)", false),
            AiToolParam.of("outputDir", "string", "Local directory for .eml files", false)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String accountEmail = (String) args.get("accountEmail");
        if (accountEmail == null || accountEmail.isBlank())
            return AiToolResult.error("accountEmail is required");

        EmailArchiveConfig config = plugin.getConfig();
        config.setAccountEmail(accountEmail.trim());
        config.setDays(args.get("days") != null ? ((Number) args.get("days")).intValue() : 30);
        config.setImapFolder(args.get("folder") != null ? (String) args.get("folder") : "INBOX");
        if (args.get("outputDir") != null) {
            config.setOutputDir(Paths.get((String) args.get("outputDir")));
        }

        try {
            EmailArchiveService service = new EmailArchiveService();
            EmailArchiveService.ArchiveResult result = service.archive(config, null);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", result.errorMessage == null);
            out.put("totalFetched", result.totalFetched);
            out.put("newArchived", result.newArchived);
            out.put("skippedDuplicates", result.skippedDuplicates);
            out.put("errors", result.errors);
            out.put("outputDir", config.getOutputDir() != null
                    ? config.getOutputDir().toString() : ".swisskit/email-archive/");
            if (result.errorMessage != null) out.put("error", result.errorMessage);

            log.info("email_archive_fetch: {} archived, {} skipped",
                    result.newArchived, result.skippedDuplicates);
            return AiToolResult.success(JsonHelper.toJson(out));
        } catch (Exception e) {
            log.error("email_archive_fetch error: {}", e.getMessage());
            return AiToolResult.error("Archive failed: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Create EmailArchiveQueryTool**

```java
package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import fan.summer.buildintool.emailarchive.*;
import fan.summer.database.entity.email.EmailArchiveEntity;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class EmailArchiveQueryTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(EmailArchiveQueryTool.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    @Override public String getName() { return "email_archive_query"; }

    @Override public String getDescription() {
        return "Search archived emails in the local database. " +
               "All parameters are optional. " +
               "Args: accountEmail (string) — filter by account; " +
               "fromAddress (string) — filter by sender (partial match); " +
               "subject (string) — filter by subject (partial match); " +
               "startDate (string) — ISO date like 2026-01-01; " +
               "endDate (string) — ISO date like 2026-05-28; " +
               "limit (integer) — max results, default 20.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("accountEmail", "string", "Filter by account email", false),
            AiToolParam.of("fromAddress", "string", "Filter by sender (partial match)", false),
            AiToolParam.of("subject", "string", "Filter by subject (partial match)", false),
            AiToolParam.of("startDate", "string", "Start date (ISO: 2026-01-01)", false),
            AiToolParam.of("endDate", "string", "End date (ISO: 2026-05-28)", false),
            AiToolParam.of("limit", "integer", "Max results (default 20, max 100)", false)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String accountEmail = (String) args.get("accountEmail");
        String fromAddress = (String) args.get("fromAddress");
        String subject = (String) args.get("subject");

        Date startDate = null, endDate = null;
        if (args.get("startDate") != null) {
            startDate = Date.valueOf(LocalDate.parse((String) args.get("startDate"), ISO));
        }
        if (args.get("endDate") != null) {
            endDate = Date.valueOf(LocalDate.parse((String) args.get("endDate"), ISO));
        }

        try {
            EmailArchiveService service = new EmailArchiveService();
            List<EmailArchiveEntity> results =
                    service.query(accountEmail, fromAddress, subject, startDate, endDate);

            int limit = args.get("limit") != null ? Math.min(((Number) args.get("limit")).intValue(), 100) : 20;
            if (results.size() > limit) results = results.subList(0, limit);

            List<Map<String, Object>> emails = new ArrayList<>();
            for (EmailArchiveEntity e : results) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("subject", e.getSubject());
                map.put("from", e.getFromAddress());
                map.put("to", e.getToAddress());
                map.put("date", e.getSendDate() != null ? e.getSendDate().toString() : null);
                map.put("hasAttachment", e.getHasAttachment());
                map.put("preview", e.getBodyPreview());
                map.put("folder", e.getFolder());
                emails.add(map);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("totalResults", emails.size());
            out.put("emails", emails);

            log.info("email_archive_query: {} results", emails.size());
            return AiToolResult.success(JsonHelper.toJson(out));
        } catch (Exception e) {
            log.error("email_archive_query error: {}", e.getMessage());
            return AiToolResult.error("Query failed: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Build verify**

Run: `mcp__idea__build_project(rebuild=true)` — expect PASS.

- [ ] **Step 4: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/ai/EmailArchiveFetchTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/EmailArchiveQueryTool.java
git commit -m "✨ feat(email-archive): add AI tools for fetch and query"
```

---

## Task 6: Registration + i18n

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/Registrar/BuiltinToolRegistrar.java`
- Modify: `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinAiToolRegistrar.java`
- Modify: `SwissKit/src/main/resources/i18n/messages.properties`
- Modify: `SwissKit/src/main/resources/i18n/messages_en.properties` (if exists)

- [ ] **Step 1: Add import and entry to BuiltinToolRegistrar**

Add import:
```java
import fan.summer.buildintool.emailarchive.EmailArchivePlugin;
```

Add `new EmailArchivePlugin()` to the `List.of(...)` in `register()`.

- [ ] **Step 2: Add AI tool registration to BuiltinAiToolRegistrar**

Add imports:
```java
import fan.summer.buildintool.ai.EmailArchiveFetchTool;
import fan.summer.buildintool.ai.EmailArchiveQueryTool;
import fan.summer.buildintool.emailarchive.EmailArchivePlugin;
```

Add method and call it from `register()`:

```java
    private static void registerEmailArchiveTools() {
        PluginRegistry registry = PluginRegistry.getInstance();
        if (registry == null) return;

        Optional<EmailArchivePlugin> opt = registry.findPlugin("fan.summer.buildin.email-archive")
                .map(p -> (EmailArchivePlugin) p);
        if (opt.isEmpty()) return;

        EmailArchivePlugin plugin = opt.get();
        AiServiceProvider.registerTool(new EmailArchiveFetchTool(plugin));
        AiServiceProvider.registerTool(new EmailArchiveQueryTool());
        log.info("Email archive AI tools registered (2 tools)");
    }
```

Add `registerEmailArchiveTools();` call in `register()` method and update log message.

- [ ] **Step 3: Add i18n keys to messages.properties**

Append to `SwissKit/src/main/resources/i18n/messages.properties`:

```properties
# Built-in: Email Archive
builtin.email-archive.name=Email Archive
builtin.email-archive.desc=Archive emails from IMAP server to local storage
builtin.email-archive.step.selectAccount=Select Account
builtin.email-archive.step.archiveConfig=Archive Config
builtin.email-archive.step.execute=Execute Archive
builtin.email-archive.noAccounts=No email accounts configured. Please configure one in Settings first.
builtin.email-archive.noImapConfig=IMAP settings not configured for this account. Please update in Settings.
builtin.email-archive.days=Last N Days
builtin.email-archive.folder=IMAP Folder
builtin.email-archive.outputDir=Output Directory
builtin.email-archive.archiving=Archiving emails...
builtin.email-archive.connecting=Connecting to IMAP server...
builtin.email-archive.fetching=Fetching messages...
builtin.email-archive.complete=Archive complete: {0} emails archived, {1} skipped (duplicates)
builtin.email-archive.failed=Archive failed: {0}
builtin.email-archive.openFolder=Open Folder
builtin.email-archive.selectAccountPrompt=Select email account
builtin.email-archive.dateRange=Date Range
builtin.email-archive.chooseOutputDir=Choose Output Directory
builtin.email-archive.defaultOutputHint=.swisskit/email-archive/
builtin.email-archive.subject=Subject
builtin.email-archive.from=From
builtin.email-archive.date=Date
builtin.email-archive.noMessages=No messages found
```

- [ ] **Step 4: Add i18n keys to messages_en.properties** (if file exists, add same keys)

Check if file exists. If yes, append the same keys. If no, skip.

- [ ] **Step 5: Build verify**

Run: `mcp__idea__build_project(rebuild=true)` — expect PASS.

- [ ] **Step 6: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/Registrar/BuiltinToolRegistrar.java \
        SwissKit/src/main/java/fan/summer/ai/tools/BuiltinAiToolRegistrar.java \
        SwissKit/src/main/resources/i18n/messages.properties \
        SwissKit/src/main/resources/i18n/messages_en.properties
git commit -m "✨ feat(email-archive): register plugin and AI tools, add i18n"
```

---

## Self-Review

**1. Spec coverage:**
- Database (entity, mapper, init.sql, mybatis-config) → Task 1
- IMAP fields on settings entity → Task 2
- Config + Core service (archive + query) → Task 3
- UI 3-step wizard → Task 4
- AI tools (fetch + query) → Task 5
- Registration (both registrars) + i18n → Task 6
- No gaps found.

**2. Placeholder scan:**
- Task 4 uses a descriptive summary rather than full 700+ line code block since it follows the exact same pattern as `ExcelSplitterPlugin`. The implementing agent must read `ExcelSplitterPlugin.java` as reference. This is acceptable since the task specifies exact structure.
- All other tasks contain complete code.

**3. Type consistency:**
- `EmailArchiveConfig` fields match between Task 3 (service), Task 4 (UI), and Task 5 (AI tools)
- `EmailArchiveEntity` field names match mapper XML column aliases
- `SwissKitSettingEmailEntity` IMAP field names consistent across Task 2 and Task 3
- `ArchiveResult` fields match JSON output in Task 5 fetch tool
- `getEmailArchiveConfig()` vs `getConfig()` — standardizing on `getConfig()` throughout
