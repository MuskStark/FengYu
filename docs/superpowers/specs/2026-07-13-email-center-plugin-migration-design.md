# Email Center Plugin Migration Design

**Date:** 2026-07-13
**Status:** Approved
**Target:** FengYu 4.0.0 headless web/desktop architecture

## 1. Goal

Migrate the FengYu 3.2.0 email sender and email archive tools into one official
4.0.0 Email Center plugin. The plugin must provide visual workflows and AI tools
for single and batch sending, address-book management, multi-account SMTP/IMAP
configuration, manual IMAP collection, archive search, and send history.

The migrated plugin is a packaged, isolated 4.0.0 plugin. It does not reuse the
JavaFX UI or the legacy host email tables.

All host integration must use the official FengYu SDK. The plugin must not copy,
fork, or locally reimplement the browser bridge, file-reference contract,
JSON-RPC Worker loop, or database-environment parsing.

## 2. Confirmed Scope

The plugin includes:

- Multiple SMTP/IMAP accounts with an explicit default sending account.
- SMTP and IMAP connection tests.
- Single email sending with To, CC, BCC, HTML body, and attachments.
- Batch sending by address-book tags.
- Batch sending by attachment filename suffix, preserving the 3.2.0 behavior.
- Contact and tag management with a many-to-many relationship.
- Send logs and explicit retry of failed recipients.
- Manual IMAP collection only; scheduled collection is out of scope.
- `.eml` export, archive metadata persistence, UID deduplication, search, and
  message detail.
- AI tools for account/contact lookup, single and batch sending, send status,
  manual collection, and archive querying.
- Mandatory user confirmation before an AI-requested single or batch send.

The plugin does not migrate data from existing host email tables. Those tables,
entities, and repositories remain outside the plugin's data path.

## 3. Architecture Decision

Use the existing isolated plugin Worker architecture. A plugin that declares the
`database` permission receives the configured host JDBC connection descriptor
through its process environment. The plugin opens its own JDBC connections and
owns its schema, migrations, queries, and credential encryption.

This approach is preferred over a host SQL proxy because it fits the current
newline-delimited JSON-RPC Worker protocol and avoids designing a second query and
transaction protocol. Returning the email backend to the host Spring process was
rejected because it would break plugin isolation and independent package upgrades.

## 4. Host Database Capability

### 4.1 Permission gate

The email manifest declares these permissions:

- `database`
- `network.email`
- `files.read`
- `files.write`

`PluginProcessManager` injects database values only when the installed manifest
contains `database`. Plugins without this permission receive no database URL,
driver, username, or password.

The injected environment contract contains the configured database type, JDBC
driver, JDBC URL, username, and password. Environment keys are stable SDK
contract constants rather than email-specific names. Password values must never
be placed on the command line or written to logs.

The official Java Worker SDK gains an immutable database-configuration type and
an environment reader for this contract. The email Worker consumes that SDK API;
it does not read raw environment keys throughout plugin code. The SDK reader
returns no configuration when the permission-gated values are absent and redacts
the password from `toString()` and error messages.

### 4.2 Stable plugin data directory

The host creates and injects a stable plugin data directory at:

`~/.fengyu/plugin-data/<pluginId>/`

The directory is outside the installed package directory, survives plugin
upgrades, and stores the email credential-encryption key. Normal package removal
does not silently remove this directory; destructive data removal requires an
explicit user action.

### 4.3 Supported databases

The database contract covers every database type currently supported by FengYu:
H2, SQLite, MySQL, and PostgreSQL. The email Worker packages the required JDBC
drivers and uses dialect-specific, versioned schema migrations.

## 5. Plugin-Owned Data

Every plugin table is independent of the host's tables. Every DDL identifier
starts with the exact source-level prefix `FengTu_PL_Email_`; databases may apply
their normal unquoted-identifier case normalization.

The schema contains:

| Table | Responsibility |
|---|---|
| `FengTu_PL_Email_Schema_History` | Applied plugin schema versions |
| `FengTu_PL_Email_Account` | Multi-account SMTP/IMAP settings and encrypted credentials |
| `FengTu_PL_Email_Contact` | Address-book contacts |
| `FengTu_PL_Email_Tag` | Contact tags |
| `FengTu_PL_Email_Contact_Tag` | Contact-to-tag relationship |
| `FengTu_PL_Email_Mass_Config` | Reusable batch-send configuration |
| `FengTu_PL_Email_Pending_Send` | Immutable confirmation snapshots and state |
| `FengTu_PL_Email_Sent_Log` | Per-message send result and immutable account/recipient snapshot |
| `FengTu_PL_Email_Archive` | Collected message metadata and `.eml` location |

Account deletion does not delete send or archive history. Historical records keep
the relevant email-address snapshots and do not require the account row to render.
The plugin refuses account deletion while that account has a send in `PENDING` or
`SENDING` state.

## 6. Credential Encryption

The email plugin, not the host, encrypts SMTP/IMAP passwords using AES-256-GCM.
It generates a random 256-bit key on first use and stores it in the stable plugin
data directory with owner-only filesystem permissions where the platform supports
them. Each encryption uses a fresh random nonce; the stored database value carries
an explicit format version, nonce, ciphertext, and authentication tag.

The key, plaintext password, ciphertext, and host database password are excluded
from logs, API results, AI context, and UI state returned after saving. If the key
is missing while encrypted credentials exist, the plugin reports that credentials
are locked and requires the user to enter them again. It does not silently replace
the key or treat unreadable ciphertext as an empty password.

## 7. Plugin Components

The new `OfficialPlugins/plugin-email` module is divided into focused components:

- A JSON-RPC Worker entry point and dispatcher.
- Database configuration, dialect detection, migrations, and MyBatis session
  creation.
- Credential key management and AES-GCM encryption.
- Account, contact, tag, pending-send, send-log, and archive repositories.
- `EmailSendService` for SMTP messages and batch orchestration.
- `PendingSendService` for confirmation snapshots and idempotent state changes.
- `EmailArchiveService` for IMAP collection, `.eml` output, metadata extraction,
  and archive search.
- AI RPC methods that share the same application services as the visual UI.

The Worker entry point uses the official Java SDK's `JsonRpcWorker`,
`PluginHandler`, and `FileRef` types. Database connection discovery uses the new
official SDK database-environment API. No email-owned JSON-RPC loop or duplicate
file-reference DTO is permitted.

The plugin UI is a Vue 3 application running inside the standard sandboxed
`.fyp` iframe. It bundles its own Vuetify MD3 and TipTap dependencies because an
iframe cannot share the host's Vue/Vuetify runtime. Theme and locale are obtained
from `FengYuClient.ready()` and kept synchronized through official SDK
`environment` events.

All host communication goes through the official `@infinia/plugin-sdk`
`FengYuClient`: `ready()`, `invoke()`, and `files` operations. The package build
vendors the official SDK browser bundle in the same way as the existing official
Markdown and Excel packages; the email UI does not call `postMessage` or
host-internal HTTP endpoints directly.

## 8. User Interface

The Email Center appears as one plugin card and contains five primary tabs:

1. **Compose** — account selection, To/CC/BCC, subject, TipTap body, attachments,
   HTML/plain-text preview, and single-send confirmation.
2. **Batch Send** — tag-based and filename-suffix modes, recipient resolution,
   attachment grouping, exact summary, confirmation, execution, and progress.
3. **Address Book** — contact CRUD, tag CRUD, search, bulk tag assignment, and
   deletion.
4. **Collect Mail** — account, IMAP folder, date range, output directory, manual
   execution, progress, and results.
5. **Records & Accounts** — paginated send/archive records and message details,
   plus multi-account SMTP/IMAP configuration and connection testing.

Browser file selection uses host-issued file and directory references. Desktop
selection uses the Tauri dialog bridge. The Worker receives only paths already
resolved and authorized by the host.

Long-running sends and collection operations report real processed, successful,
failed, new, and duplicate counts. Changing tabs does not cancel work. Stopping
the plugin process terminates its active work.

## 9. AI Tools and Confirmation Protocol

The manifest exposes:

- `email_accounts_list`
- `email_contacts_query`
- `email_send_single`
- `email_send_batch`
- `email_send_status`
- `email_archive_fetch`
- `email_archive_query`

### 9.1 Two-phase send

AI send tools never dispatch SMTP mail during their initial call. They:

1. Validate the request and account.
2. Resolve contacts, tags, batches, and authorized attachments.
3. Store an immutable snapshot in `FengTu_PL_Email_Pending_Send`.
4. Return a generic `confirmation_required` envelope containing a confirmation
   ID and a safe summary: account, To/CC/BCC, batch count, recipient count,
   subject, and attachment names.

The host chat UI renders this envelope as Approve and Reject actions. Approve
calls the plugin's internal `confirm_send` method; Reject marks the snapshot
cancelled. Confirmation records expire after 30 minutes.

`confirm_send` performs an atomic `PENDING` to `SENDING` transition. Only the
caller that wins that transition may send. Repeated clicks, replayed requests,
and concurrent approvals therefore cannot send the same snapshot twice. Terminal
states are `SUCCEEDED`, `PARTIAL`, `FAILED`, `REJECTED`, and `EXPIRED`.

### 9.2 AI output limits

Archive queries are paginated and return summaries by default. Full message
metadata and body preview are loaded only when requested. Credentials and full
raw `.eml` content are never returned to the model.

## 10. Sending Behavior

Single sending supports To, CC, BCC, HTML/plain text, and multiple attachments.
Batch sending preserves both 3.2.0 modes:

- Address-book recipients selected by one or more tags.
- Attachments grouped by the filename suffix between the last underscore and the
  extension, then matched to a tag of the same name.

Each outbound message gets its own send-log row. A failure in one batch does not
stop subsequent batches. Network failures are not automatically retried because
the SMTP server may have accepted a message before the connection failed. The UI
offers an explicit retry for failed recipients using a new idempotency record.

## 11. Collection Behavior

Collection is manual from either the plugin UI or the `email_archive_fetch` AI
tool. The user chooses an account, IMAP folder, date range, and authorized output
directory.

Messages are deduplicated by account, folder, and IMAP UID. For each new message,
the service writes a temporary `.eml`, atomically moves it to its final sanitized
UID-based filename, and then inserts metadata. If the database insert fails, it
removes the newly written file. A parsing failure for one message increments the
error count and collection continues. Authentication errors, a missing folder, or
an unwritable output directory stop the run with an actionable error.

Stored metadata includes sender, recipients, subject, sent/received dates,
attachment presence, a bounded body preview, archive time, and `.eml` path.

## 12. Error Handling and Security

- Validate addresses, reject CR/LF header injection, and validate attachment
  grants before creating a pending send.
- Apply explicit SMTP/IMAP connect, read, and write timeouts.
- Do not reveal whether unrelated host tables exist and do not query them.
- Parameterize every SQL statement; table names come only from compiled migration
  resources, never user input.
- Redact database and mail credentials from exceptions before returning them.
- Keep per-message failures in send logs while returning an aggregate task result.
- Use temporary files and atomic moves for `.eml` output where supported.

## 13. Testing

### 13.1 Host tests

- A plugin without `database` receives no JDBC environment values.
- A permitted plugin receives the correct database type, driver, URL, username,
  and password.
- Official Java SDK tests cover absent and complete database environments,
  validation, and password redaction.
- Process commands and logs do not contain the database password.
- Plugin data directories are stable across package upgrades.
- The chat UI recognizes `confirmation_required`, and Approve/Reject invoke the
  correct plugin methods once.

### 13.2 Plugin tests

- Migration contract tests cover H2 and SQLite on every run and MySQL and
  PostgreSQL in CI service containers.
- Every created table starts with `FengTu_PL_Email_`, and repeated initialization
  is safe.
- Encryption round trips correctly, uses distinct nonces, and rejects a wrong key
  or modified ciphertext.
- Repository tests cover account isolation, contacts/tags, pending state changes,
  logs, archive pagination, and UID uniqueness.
- GreenMail tests cover SMTP To/CC/BCC, HTML/plain text, attachments, batch partial
  failure, IMAP collection, `.eml` output, and duplicate skipping.
- AI tests prove that preparation sends nothing, approval sends once, replay and
  concurrent approval do not resend, and rejection or expiry sends nothing.
- Vue tests cover account switching, tag filtering, batch summaries, confirmation,
  paginated records, and actionable errors.
- Static contract tests reject direct plugin `postMessage` calls, duplicate
  JSON-RPC loops, and duplicate `FileRef` declarations so official SDK usage
  remains enforceable.
- Worker protocol tests cover every public method and ensure stdout remains valid
  newline-delimited JSON-RPC.

### 13.3 Packaging and smoke tests

The official build produces `fan.summer.email-4.0.0.fyp`. A smoke run installs
the package, saves and tests an account, sends through a test SMTP server,
collects from a test IMAP server, and queries the resulting archive record.

## 14. Documentation and Definition of Done

Update the plugin database guide with the `database` permission, environment
contract, stable data directory, naming convention, and credential-handling
rules. Update the official SDK guide with the Java database-environment API.
Update README and CHANGELOG to describe the merged Email Center.

The migration is complete when:

- The Email Center installs and appears as one official plugin.
- All five UI areas work with multiple accounts.
- Single and both batch modes send through SMTP and record per-message results.
- Manual IMAP collection writes `.eml` files and searchable metadata without
  duplicates.
- AI single and batch sends cannot bypass confirmation or execute twice.
- The Worker and micro-frontend use only the official Java and TypeScript SDKs
  for host integration.
- All plugin tables use the `FengTu_PL_Email_` prefix and no legacy host email
  table is read or migrated.
- H2, SQLite, MySQL, and PostgreSQL database contracts pass.
- Unit, UI, Worker, packaging, and smoke verification complete successfully.
