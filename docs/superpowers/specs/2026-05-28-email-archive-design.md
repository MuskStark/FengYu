# Email Archive Tool — Design Spec

## Overview

Built-in tool for archiving emails via IMAP. Reuses the existing `swiss_kit_setting_email` account configuration (extended with IMAP fields) so sending and receiving share one account. Stores metadata in H2, raw emails as `.eml` files on disk. Fully AI-callable via SwissKitJClaw.

## Architecture

```
buildintool/emailarchive/
  EmailArchivePlugin.java       ← SwissKitJPlugin (UI: 3-step wizard)
  EmailArchiveConfig.java       ← Shared state POJO
  EmailArchiveService.java      ← IMAP fetch + query logic

buildintool/ai/
  EmailArchiveFetchTool.java    ← AI: trigger archive
  EmailArchiveQueryTool.java    ← AI: query archived emails

database/entity/email/
  EmailArchiveEntity.java       ← H2 entity

database/mapper/email/
  EmailArchiveMapper.java       ← MyBatis mapper interface

resources/mapper/email/
  EmailArchiveMapper.xml        ← MyBatis XML

database/entity/setting/email/
  SwissKitSettingEmailEntity.java  ← MODIFY: add IMAP fields
```

## Database Changes

### 1. Extend email settings with IMAP fields

```sql
ALTER TABLE swiss_kit_setting_email ADD COLUMN IF NOT EXISTS imap_address VARCHAR(255);
ALTER TABLE swiss_kit_setting_email ADD COLUMN IF NOT EXISTS imap_port INTEGER DEFAULT 993;
ALTER TABLE swiss_kit_setting_email ADD COLUMN IF NOT EXISTS imap_ssl INTEGER NOT NULL DEFAULT 1;
```

`SwissKitSettingEmailEntity` adds:
- `imapAddress` (String) — IMAP server hostname
- `imapPort` (Integer, default 993) — IMAP port
- `imapSSL` (Boolean, default true) — use SSL for IMAP

### 2. New archive table

```sql
CREATE TABLE IF NOT EXISTS email_archive (
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

## UI — 3-Step Wizard

### Step 1: Select Account

- Read accounts from `swiss_kit_setting_email` via `SwissKitSettingEmailMapper`
- ComboBox listing configured email accounts
- If no accounts or IMAP fields empty, show warning linking to Settings
- Validate IMAP connectivity on selection (optional)

### Step 2: Archive Config

- Date range: "Last N days" spinner (default 30), or custom start/end date pickers
- Folder: ComboBox (INBOX, Sent, Drafts, Trash, custom name)
- Output directory: DirectoryChooser for local `.eml` storage
- Default output dir: `.swisskit/email-archive/` under working directory

### Step 3: Execute

- Background thread via `javafx.concurrent.Task`
- Progress bar + status label (connecting → fetching → writing → done)
- Result summary: N emails archived, M skipped (duplicates), output path
- "Open Folder" button to browse archived files

## Core Logic — EmailArchiveService

### `archive(config, progressCallback) → ArchiveResult`

1. Load `SwissKitSettingEmailEntity` from H2 by account email
2. Create Jakarta Mail `Session` with IMAP provider using `angus-mail`
3. Connect to `Store`, open `Folder` (READ_ONLY)
4. Search messages by date range (`SearchTerm` with `ReceivedDateTerm` or `SentDateTerm`)
5. For each message:
   - Check uniqueness via `(account_email, folder, UID)` in H2 — skip duplicates
   - Write `.eml` file to output dir via `MimeMessage.writeTo()`
   - Extract metadata: subject, from, to, cc, sendDate, hasAttachment, bodyPreview
   - Insert `EmailArchiveEntity` row into H2
6. Return `ArchiveResult(totalFetched, newArchived, skipped, errorCount)`

### `query(accountEmail, from, subject, startDate, endDate, limit) → List<EmailArchiveEntity>`

- Query H2 via `EmailArchiveMapper` with optional filters
- All parameters optional; `limit` defaults to 20

### IMAP connection reuse

```java
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
```

Uses `imaps` protocol from `org.eclipse.angus:angus-mail` (already in project dependencies).

## AI Tools

### email_archive_fetch

Trigger IMAP archive for a configured account.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| accountEmail | string | yes | Email address matching a configured account in H2 |
| days | integer | no | Fetch emails from last N days (default 30) |
| folder | string | no | IMAP folder name (default "INBOX") |
| outputDir | string | no | Local directory for .eml files (default `.swisskit/email-archive/`) |

Returns JSON:
```json
{
  "success": true,
  "totalFetched": 42,
  "newArchived": 38,
  "skippedDuplicates": 4,
  "errors": 0,
  "outputDir": "/path/to/archive"
}
```

### email_archive_query

Query archived emails from H2.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| accountEmail | string | no | Filter by account |
| fromAddress | string | no | Filter by sender (partial match) |
| subject | string | no | Filter by subject (partial match) |
| startDate | string | no | Start date (ISO format: 2026-01-01) |
| endDate | string | no | End date (ISO format: 2026-05-28) |
| limit | integer | no | Max results (default 20, max 100) |

Returns JSON:
```json
{
  "success": true,
  "totalResults": 2,
  "emails": [
    {
      "subject": "Meeting notes",
      "from": "alice@example.com",
      "date": "2026-05-20T10:30:00",
      "hasAttachment": true,
      "preview": "Hi team, here are the notes..."
    }
  ]
}
```

## Registration

### BuiltinToolRegistrar
```java
new EmailArchivePlugin()
```

### BuiltinAiToolRegistrar
```java
// Plugin-bound — fetch plugin instance from registry
registerEmailArchiveTools();
```

## Files Checklist

```
CREATE  buildintool/emailarchive/EmailArchivePlugin.java
CREATE  buildintool/emailarchive/EmailArchiveConfig.java
CREATE  buildintool/emailarchive/EmailArchiveService.java
CREATE  buildintool/ai/EmailArchiveFetchTool.java
CREATE  buildintool/ai/EmailArchiveQueryTool.java
CREATE  database/entity/email/EmailArchiveEntity.java
CREATE  database/mapper/email/EmailArchiveMapper.java
CREATE  resources/mapper/email/EmailArchiveMapper.xml
MODIFY  database/entity/setting/email/SwissKitSettingEmailEntity.java  (add IMAP fields)
MODIFY  resources/init.sql  (ALTER TABLE + CREATE TABLE)
MODIFY  Registrar/BuiltinToolRegistrar.java  (add EmailArchivePlugin)
MODIFY  ai/tools/BuiltinAiToolRegistrar.java  (register AI tools)
MODIFY  resources/i18n/messages.properties  (i18n keys)
MODIFY  resources/i18n/messages_en.properties  (i18n keys)
```

## i18n Keys

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
builtin.email-archive.defaultOutputDir=.swisskit/email-archive/
builtin.email-archive.archiving=Archiving emails...
builtin.email-archive.connecting=Connecting to IMAP server...
builtin.email-archive.fetching=Fetching messages...
builtin.email-archive.complete=Archive complete: {0} emails archived, {1} skipped (duplicates)
builtin.email-archive.failed=Archive failed: {0}
builtin.email-archive.openFolder=Open Folder
builtin.email-archive.selectAccount=Select email account
builtin.email-archive.dateRange=Date Range
```

## Metadata

- ID: `fan.summer.buildin.email-archive`
- Category: `ToolCategory.NET`
- MDI Icon: `email-check` (inbox/archive icon)
- Icon Style: `IconStyle.TEAL`
- Version: `1.0.0`
- Type: `ToolType.BUILTIN`
