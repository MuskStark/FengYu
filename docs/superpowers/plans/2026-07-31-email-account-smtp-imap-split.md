# Email Account SMTP/IMAP Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the email account settings form into separate SMTP and IMAP cards, and add a backend "Test IMAP connection" capability symmetric to the existing "Test SMTP".

**Architecture:** Pure presentation change on the frontend (the data model already holds SMTP/IMAP fields as flat siblings). Backend adds one new service method `EmailArchiveService.testImap()` mirroring `EmailSendService.testSmtp()`, exposed as a new JSON-RPC method `email_account_test_imap` (the existing SMTP-only `email_account_test` is left untouched for zero breakage).

**Tech Stack:** Vue 3.5 + Vuetify 3 + TypeScript (frontend); Java 21 records + jakarta.mail (`Store`/`Session`) + Simple Java Mail (backend); Vitest + JUnit 5 + GreenMail (tests).

**Spec:** `docs/superpowers/specs/2026-07-31-email-account-smtp-imap-split-design.md`

## Global Constraints

- All work confined to `OfficialPlugins/plugin-email/`. Do NOT touch top-level `FengYu/` backend, top-level `frontend/`, or other plugins.
- Data model is NOT changing: `Account`/`AccountDraft`/`EmailAccount` already hold `smtpHost/smtpPort/smtpSecurity` and `imapHost/imapPort/imapSecurity` as flat siblings.
- The existing SMTP test path stays intact: RPC method name `email_account_test`, `EmailSendService.testSmtp`, `Mailer.testConnection()` — none of these change.
- New IMAP test RPC method name is `email_account_test_imap` (params `{ accountId: long }`). Keep it distinct from `email_account_test`.
- Tests return credential-free results: every failure message must pass through `safeMessage`/`safeError` so passwords are redacted.
- Maven wrapper is `./mvnw` at repo root. Frontend tests run from `OfficialPlugins/plugin-email/ui-src`.
- Commit convention: conventional commits with emojis — `✨` feat, `🐛` fix, `♻️` refactor, `📝` docs, `⬆️` deps.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/.../service/EmailArchiveService.java` | Modify | Add `testImap(long)` — reuses existing `imapProperties`, `protocol`, `safeMessage` helpers. |
| `src/test/java/.../service/EmailArchiveServiceTest.java` | Modify | Add success + failure tests for `testImap` using the class's existing GreenMail IMAP harness. |
| `src/main/java/.../rpc/EmailRpcHandlers.java` | Modify | Add `testImapAccount(params)` — symmetric to existing `testAccount`. |
| `src/main/java/.../EmailWorkerMain.java` | Modify | Register `email_account_test_imap`. |
| `src/test/java/.../EmailWorkerMainTest.java` | Modify | Assert `email_account_test_imap` is registered. |
| `manifest.json` | Modify | Declare `email_account_test` (backfill) and `email_account_test_imap` (new). |
| `ui-src/src/i18n/en.ts` | Modify | Add `smtpSection`, `imapSection`, `testSmtp`, `testImap`. |
| `ui-src/src/i18n/zh-CN.ts` | Modify | Same 4 keys, Chinese copy. |
| `ui-src/src/styles.css` | Modify | Add `.full-row { grid-column: 1 / -1; }`. |
| `ui-src/src/components/AccountSettingsView.vue` | Modify | Split form into two cards; add IMAP test button + handler. |
| `ui-src/src/components/ManagementViews.test.ts` | Modify | Assert `imap-test` button exists. |

---

### Task 1: Backend IMAP connection test (`EmailArchiveService.testImap`)

**Files:**
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/EmailArchiveService.java` (add method after `collect`, ~line 100; no import additions needed — `Session`/`Store`/`Properties` already imported)
- Test: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/service/EmailArchiveServiceTest.java`

**Interfaces:**
- Consumes: existing private static helpers `imapProperties(String security)`, `protocol(String security)`, `safeMessage(Exception, String)` (all already in `EmailArchiveService`), and `AccountService.decryptPassword(long)` via field `accountService`.
- Produces: `public SendResult testImap(long accountId)` returning `fan.summer.fengyu.plugin.email.model.SendResult` (existing record: `success/messageId/errorMessage`). Later tasks call this.

**Context — the existing SMTP test we mirror (in `EmailSendService.java:40`):**
```java
public SendResult testSmtp(long accountId) {
    EmailAccount account = account(accountId);
    String password = accountService.decryptPassword(accountId);
    try (Mailer mailer = createMailer(account, password)) {
        mailer.testConnection();
        return SendResult.success(null);
    } catch (Exception e) {
        return SendResult.failure(safeError(e, password));
    }
}
```

**Context — the IMAP guard + connection pattern already used by `EmailArchiveService.collect()` (lines 60-70):**
```java
if (blank(account.imapHost()) || account.imapPort() == null || blank(account.imapSecurity())) {
    throw new IllegalArgumentException("IMAP is not configured for account: " + request.accountId());
}
String password = accountService.decryptPassword(account.id());
Properties properties = imapProperties(account.imapSecurity());
Session session = Session.getInstance(properties);
try (Store store = session.getStore(protocol(account.imapSecurity()))) {
    store.connect(account.imapHost(), account.imapPort(), account.email(), password);
    ...
```

- [ ] **Step 1: Write the two failing tests**

In `EmailArchiveServiceTest.java`, add these two tests (the `@BeforeEach` already starts a GreenMail IMAP server, saves an account `accountId` with email `collector@example.com` / password `imap-secret` / IMAP host `127.0.0.1` / IMAP port `greenMail.getImap().getPort()` / security `PLAIN`, and builds `service`). Place after the existing `@Test` methods, before the private helpers (e.g. after `cjkArchiveFilenameFitsUtf8ByteLimit`, ~line 243). `SendResult` is already imported in this file? — **check**: it imports `ArchiveRequest`, `ArchivedMessage` but NOT `SendResult`. Add this import at the top with the other model imports:

```java
import fan.summer.fengyu.plugin.email.model.SendResult;
```

Then add the two tests:

```java
@Test void imapConnectionTestSucceedsAgainstGreenMail() {
    SendResult result = service.testImap(accountId);
    assertTrue(result.success());
    assertNull(result.errorMessage());
}

@Test void imapConnectionTestFailsAndRedactsPasswordWithoutThrowing() throws Exception {
    long badAccount = new AccountService(database, credentialCipher).save(
        new AccountService.AccountInput(null, "Bad", "collector@example.com", "wrong-password",
            "127.0.0.1", 25, "PLAIN", "127.0.0.1", greenMail.getImap().getPort(), "PLAIN", false));

    SendResult result = service.testImap(badAccount);

    assertFalse(result.success());
    assertNotNull(result.errorMessage());
    assertFalse(result.errorMessage().contains("wrong-password"));
}
```

Add the `assertNull` import if not present (the file imports `assertEquals`, `assertFalse`, `assertNotNull`, `assertTrue` — add `assertNull`):
```java
import static org.junit.jupiter.api.Assertions.assertNull;
```

- [ ] **Step 2: Run tests to verify they fail**

Run from repo root:
```bash
./mvnw -pl OfficialPlugins/plugin-email test -Dtest=EmailArchiveServiceTest
```
Expected: compile error (`SendResult` import unused / `testImap` not found) → if it compiles, the two new tests FAIL with "method testImap not found" or similar.

- [ ] **Step 3: Implement `testImap`**

In `EmailArchiveService.java`, add this method immediately after `collect(...)` (after line 100, before `search`):

```java
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
        return SendResult.success(null);
    } catch (Exception e) {
        return SendResult.failure(safeMessage(e, password));
    }
}
```

Add the `SendResult` import with the other model imports near the top of the file:
```java
import fan.summer.fengyu.plugin.email.model.SendResult;
```

Note: `Session`, `Store`, `Properties`, `accounts`, `accountService`, `blank`, `imapProperties`, `protocol`, `safeMessage` are all already in scope (existing imports + fields + private static helpers). No other changes needed.

- [ ] **Step 4: Run tests to verify they pass**

```bash
./mvnw -pl OfficialPlugins/plugin-email test -Dtest=EmailArchiveServiceTest
```
Expected: PASS (all existing tests + the 2 new ones).

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/EmailArchiveService.java \
        OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/service/EmailArchiveServiceTest.java
git commit -m "✨ feat(email): add IMAP connection test to EmailArchiveService"
```

---

### Task 2: Expose IMAP test over JSON-RPC + register the method

**Files:**
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/rpc/EmailRpcHandlers.java` (add method after `testAccount`, ~line 93)
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/EmailWorkerMain.java` (add `.on(...)` after the `email_account_test` line, line 43)
- Test: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/EmailWorkerMainTest.java`

**Interfaces:**
- Consumes: `archive.testImap(long)` (produced in Task 1) — `archive` is the `EmailArchiveService` field in `EmailRpcHandlers` (line 45).
- Produces: RPC method `email_account_test_imap` (params `{ accountId: long }`) → envelope `{ success: boolean, summary: string }`.

**Context — the existing SMTP RPC handler to mirror (`EmailRpcHandlers.java:86`):**
```java
public Object testAccount(Map<String, Object> params) {
    return result(() -> {
        long accountId = requiredLong(params, "accountId");
        var value = sends.testSmtp(accountId);
        log.info("SMTP test for account {}: {}", accountId, value.success() ? "succeeded" : "failed");
        return value.success() ? ok("SMTP connection succeeded") : failure(value.errorMessage());
    });
}
```

- [ ] **Step 1: Add registration-assertion test (the registration isn't there yet)**

In `EmailWorkerMainTest.java`, the test `registersAccountContactTagConfigSendAndArchiveUiOperations` (line 85) builds a `methods` list and asserts none return `-32601` (method-not-found). Add `"email_account_test_imap"` to that list. Find the `List<String> methods = List.of(` block (line 88) and insert the new entry alongside the existing `"email_account_test"`:

Change this line:
```java
            "email_account_test", "email_contact_save", "email_contact_find", "email_contact_delete",
```
to:
```java
            "email_account_test", "email_account_test_imap", "email_contact_save", "email_contact_find", "email_contact_delete",
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw -pl OfficialPlugins/plugin-email test -Dtest=EmailWorkerMainTest#registersAccountContactTagConfigSendAndArchiveUiOperations
```
Expected: FAIL — `email_account_test_imap` returns error code `-32601` (not registered), so the `assertTrue(responses.stream().noneMatch(...))` assertion fails.

- [ ] **Step 3: Add the RPC handler method**

In `EmailRpcHandlers.java`, add this method immediately after `testAccount(...)` (after line 93, before `queryContacts`):

```java
public Object testImapAccount(Map<String, Object> params) {
    return result(() -> {
        long accountId = requiredLong(params, "accountId");
        var value = archive.testImap(accountId);
        log.info("IMAP test for account {}: {}", accountId, value.success() ? "succeeded" : "failed");
        return value.success() ? ok("IMAP connection succeeded") : failure(value.errorMessage());
    });
}
```

No new imports needed (`archive`, `result`, `requiredLong`, `log`, `ok`, `failure` are all in scope).

- [ ] **Step 4: Register the method in the worker**

In `EmailWorkerMain.java`, add a new `.on(...)` line immediately after the `email_account_test` registration (after line 43):

```java
            .on("email_account_test", handlers.handle("email_account_test", handlers::testAccount))
            .on("email_account_test_imap", handlers.handle("email_account_test_imap", handlers::testImapAccount))
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./mvnw -pl OfficialPlugins/plugin-email test -Dtest=EmailWorkerMainTest
```
Expected: PASS (all tests including the updated registration test).

- [ ] **Step 6: Run full backend test suite for the plugin to confirm no regressions**

```bash
./mvnw -pl OfficialPlugins/plugin-email test
```
Expected: PASS (Tasks 1 + 2 together).

- [ ] **Step 7: Commit**

```bash
git add OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/rpc/EmailRpcHandlers.java \
        OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/EmailWorkerMain.java \
        OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/EmailWorkerMainTest.java
git commit -m "✨ feat(email): expose IMAP connection test as email_account_test_imap RPC"
```

---

### Task 3: Backfill + declare test methods in manifest.json

**Files:**
- Modify: `OfficialPlugins/plugin-email/manifest.json` (the `aiTools` array)

**Context:** The `email_account_test` method is registered in the worker but has NO `aiTools` entry in `manifest.json` (a pre-existing gap). Both test methods are useful to the AI, so declare both.

Existing `aiTools` array starts at line 15 and ends at line 22 (the `email_archive_query` entry). Each entry shape:
```json
{"name":"<method>","description":"<text>","method":"<method>","inputSchema":"{...JSON schema...}"}
```

- [ ] **Step 1: Add the two tool entries**

In `manifest.json`, the `aiTools` array's last element is `email_archive_query` (line 22) followed by `]` (line 23). Add a comma after that element's closing `}` and append two new entries before the `]`:

Change:
```json
    {"name":"email_archive_query","description":"Search paginated archived email metadata without exposing raw message contents.","method":"email_archive_query","inputSchema":"{\"type\":\"object\",\"properties\":{\"accountId\":{\"type\":\"integer\"},\"folder\":{\"type\":\"string\"},\"sender\":{\"type\":\"string\"},\"subject\":{\"type\":\"string\"},\"start\":{\"type\":\"string\",\"format\":\"date-time\"},\"end\":{\"type\":\"string\",\"format\":\"date-time\"},\"offset\":{\"type\":\"integer\",\"minimum\":0},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100}}}"}
  ]
```
to:
```json
    {"name":"email_archive_query","description":"Search paginated archived email metadata without exposing raw message contents.","method":"email_archive_query","inputSchema":"{\"type\":\"object\",\"properties\":{\"accountId\":{\"type\":\"integer\"},\"folder\":{\"type\":\"string\"},\"sender\":{\"type\":\"string\"},\"subject\":{\"type\":\"string\"},\"start\":{\"type\":\"string\",\"format\":\"date-time\"},\"end\":{\"type\":\"string\",\"format\":\"date-time\"},\"offset\":{\"type\":\"integer\",\"minimum\":0},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100}}}"},
    {"name":"email_account_test","description":"Test the SMTP (outgoing) connection for a saved account without sending mail.","method":"email_account_test","inputSchema":"{\"type\":\"object\",\"properties\":{\"accountId\":{\"type\":\"integer\"}},\"required\":[\"accountId\"]}"},
    {"name":"email_account_test_imap","description":"Test the IMAP (incoming) connection for a saved account without fetching mail.","method":"email_account_test_imap","inputSchema":"{\"type\":\"object\",\"properties\":{\"accountId\":{\"type\":\"integer\"}},\"required\":[\"accountId\"]}"}
  ]
```

- [ ] **Step 2: Validate JSON**

```bash
node -e "JSON.parse(require('fs').readFileSync('OfficialPlugins/plugin-email/manifest.json','utf8')); console.log('manifest.json is valid JSON')"
```
Expected: prints `manifest.json is valid JSON`.

- [ ] **Step 3: Commit**

```bash
git add OfficialPlugins/plugin-email/manifest.json
git commit -m "📝 docs(email): declare account connection-test tools in manifest"
```

---

### Task 4: i18n keys (en + zh-CN)

**Files:**
- Modify: `OfficialPlugins/plugin-email/ui-src/src/i18n/en.ts` (line 9, the `accounts:` object)
- Modify: `OfficialPlugins/plugin-email/ui-src/src/i18n/zh-CN.ts` (line 9, the `accounts:` object)

**Interfaces:**
- Produces keys: `accounts.smtpSection`, `accounts.imapSection`, `accounts.testSmtp`, `accounts.testImap`. The old `accounts.test` key is removed (replaced by `accounts.testSmtp`); `accounts.testAction`/`testSuccess` remain and are reused by both test buttons.

- [ ] **Step 1: Update `en.ts`**

In `en.ts` line 9, the `accounts` object. Replace the whole line. The changes: add `smtpSection`, `imapSection`, `testSmtp`, `testImap`; remove the old `test` key; generalize `testAction`/`testSuccess` (no longer SMTP-specific, since both buttons reuse them).

Old (line 9):
```js
  accounts: { title: 'Account settings', loading: 'Loading accounts', newAccount: 'Add account', displayName: 'Display name', password: 'Password', passwordHelp: 'Leave blank to keep the saved password', smtp: 'SMTP server', imap: 'IMAP server', port: 'Port', security: 'Security', test: 'Test SMTP', defaultAccount: 'Default sending account', makeDefault: 'Make default', testAction: 'Testing SMTP connection', testSuccess: 'SMTP connection succeeded', saveAction: 'Saving account', saved: 'Account saved', defaultAction: 'Setting default account', deleteAction: 'Deleting account', deleteConfirm: 'Delete this account?' },
```
New:
```js
  accounts: { title: 'Account settings', loading: 'Loading accounts', newAccount: 'Add account', displayName: 'Display name', password: 'Password', passwordHelp: 'Leave blank to keep the saved password', smtpSection: 'Outgoing · SMTP', smtp: 'SMTP server', port: 'Port', security: 'Security', testSmtp: 'Test SMTP', imapSection: 'Incoming · IMAP', imap: 'IMAP server', testImap: 'Test IMAP', defaultAccount: 'Default sending account', makeDefault: 'Make default', testAction: 'Testing connection', testSuccess: 'Connection succeeded', saveAction: 'Saving account', saved: 'Account saved', defaultAction: 'Setting default account', deleteAction: 'Deleting account', deleteConfirm: 'Delete this account?' },
```

- [ ] **Step 2: Update `zh-CN.ts`**

In `zh-CN.ts` line 9, mirror the same structure with Chinese copy:

Old:
```js
  accounts: { title: '账户设置', loading: '正在加载账户', newAccount: '添加账户', displayName: '显示名称', password: '密码', passwordHelp: '留空则保持已保存密码', smtp: 'SMTP 发件服务器', imap: 'IMAP 收件服务器', port: '端口', security: '安全方式', test: '测试 SMTP', defaultAccount: '默认发件账户', makeDefault: '设为默认', testAction: '测试 SMTP 连接', testSuccess: 'SMTP 连接成功', saveAction: '保存账户', saved: '账户已保存', defaultAction: '设置默认账户', deleteAction: '删除账户', deleteConfirm: '确定删除该账户吗？' },
```
New:
```js
  accounts: { title: '账户设置', loading: '正在加载账户', newAccount: '添加账户', displayName: '显示名称', password: '密码', passwordHelp: '留空则保持已保存密码', smtpSection: '发件 · SMTP', smtp: 'SMTP 发件服务器', port: '端口', security: '安全方式', testSmtp: '测试 SMTP', imapSection: '收件 · IMAP', imap: 'IMAP 收件服务器', testImap: '测试 IMAP', defaultAccount: '默认发件账户', makeDefault: '设为默认', testAction: '正在测试连接', testSuccess: '连接成功', saveAction: '保存账户', saved: '账户已保存', defaultAction: '设置默认账户', deleteAction: '删除账户', deleteConfirm: '确定删除该账户吗？' },
```

- [ ] **Step 3: Commit**

```bash
git add OfficialPlugins/plugin-email/ui-src/src/i18n/en.ts OfficialPlugins/plugin-email/ui-src/src/i18n/zh-CN.ts
git commit -m "✨ feat(email): add SMTP/IMAP section + test button i18n keys"
```

---

### Task 5: Split the form into two cards + IMAP test button + `.full-row` style

**Files:**
- Modify: `OfficialPlugins/plugin-email/ui-src/src/styles.css` (add one rule near `.form-grid`, line 99)
- Modify: `OfficialPlugins/plugin-email/ui-src/src/components/AccountSettingsView.vue` (template lines 50-53; add one handler in `<script setup>`)

**Interfaces:**
- Consumes: i18n keys from Task 4 (`accounts.smtpSection`, `accounts.imapSection`, `accounts.testSmtp`, `accounts.testImap`, `accounts.smtp`, `accounts.imap`, `accounts.port`, `accounts.security`, `accounts.testAction`, `accounts.testSuccess`); RPC method `email_account_test_imap` from Task 2.
- Produces: a rendered form with two `v-card` sections and a second test button carrying `data-testid="imap-test"`.

**Context — current template (lines 46-53 of `AccountSettingsView.vue`):**
```vue
        <div>
          <v-text-field v-model="accounts.draft.displayName" :label="t('accounts.displayName')" />
          <v-text-field v-model="accounts.draft.email" :label="t('contacts.email')" />
          <v-text-field v-model="accounts.draft.password" type="password" autocomplete="new-password" :label="t('accounts.password')" :hint="t('accounts.passwordHelp')" persistent-hint />
          <div class="form-grid"><v-text-field v-model="accounts.draft.smtpHost" :label="t('accounts.smtp')" /><v-text-field v-model.number="accounts.draft.smtpPort" type="number" :label="t('accounts.port')" /><v-select v-model="accounts.draft.smtpSecurity" :items="['SSL','STARTTLS','PLAIN']" :label="t('accounts.security')" />
          <v-text-field v-model="accounts.draft.imapHost" :label="t('accounts.imap')" /><v-text-field v-model.number="accounts.draft.imapPort" type="number" :label="t('accounts.port')" /><v-select v-model="accounts.draft.imapSecurity" :items="['SSL','STARTTLS','PLAIN']" :label="t('accounts.security')" /></div>
          <v-checkbox v-model="accounts.draft.defaultAccount" :label="t('accounts.defaultAccount')" />
          <div class="d-flex ga-2 justify-end"><v-btn v-if="accounts.draft.id" color="error" variant="text" @click="removeAccount">{{ t('common.delete') }}</v-btn><v-btn v-if="accounts.draft.id && !accounts.draft.defaultAccount" variant="tonal" @click="makeDefault">{{ t('accounts.makeDefault') }}</v-btn><v-btn data-testid="smtp-test" variant="tonal" :loading="busy" @click="testAccount">{{ t('accounts.test') }}</v-btn><v-btn data-testid="account-save" color="primary" :loading="busy" @click="saveAccount">{{ t('common.save') }}</v-btn></div>
        </div>
```

**Compliance constraint:** `shellCompliance.test.ts` asserts `styles.css` contains the literal `.account-layout > :last-child > * + *`. The new `v-card`s become direct children of the form column (`.account-layout > :last-child`), so that selector still applies to them (giving 14px gap). Do NOT remove or rename that rule.

- [ ] **Step 1: Add the `.full-row` CSS rule**

In `styles.css`, add this line immediately after the `.form-grid` rule (line 99):

```css
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.full-row { grid-column: 1 / -1; }
```

- [ ] **Step 2: Add the `testImapAccount` handler in `<script setup>`**

In `AccountSettingsView.vue`, add this handler immediately after the existing `testAccount` (after line 20):

```ts
const testAccount = () => guard(t('accounts.testAction'), async () => {
  await invoke('email_account_test', { accountId: accounts.draft.id }); notice.value = t('accounts.testSuccess')
})
const testImapAccount = () => guard(t('accounts.testAction'), async () => {
  await invoke('email_account_test_imap', { accountId: accounts.draft.id }); notice.value = t('accounts.testSuccess')
})
```

- [ ] **Step 3: Replace the form-grid block with two cards**

Replace the `<div class="form-grid">...</div>` block (the single mixed grid, lines 50-51) AND update the SMTP test button label. The replacement is the form column's inner content. Replace this exact block:

```vue
          <v-text-field v-model="accounts.draft.password" type="password" autocomplete="new-password" :label="t('accounts.password')" :hint="t('accounts.passwordHelp')" persistent-hint />
          <div class="form-grid"><v-text-field v-model="accounts.draft.smtpHost" :label="t('accounts.smtp')" /><v-text-field v-model.number="accounts.draft.smtpPort" type="number" :label="t('accounts.port')" /><v-select v-model="accounts.draft.smtpSecurity" :items="['SSL','STARTTLS','PLAIN']" :label="t('accounts.security')" />
          <v-text-field v-model="accounts.draft.imapHost" :label="t('accounts.imap')" /><v-text-field v-model.number="accounts.draft.imapPort" type="number" :label="t('accounts.port')" /><v-select v-model="accounts.draft.imapSecurity" :items="['SSL','STARTTLS','PLAIN']" :label="t('accounts.security')" /></div>
          <v-checkbox v-model="accounts.draft.defaultAccount" :label="t('accounts.defaultAccount')" />
          <div class="d-flex ga-2 justify-end"><v-btn v-if="accounts.draft.id" color="error" variant="text" @click="removeAccount">{{ t('common.delete') }}</v-btn><v-btn v-if="accounts.draft.id && !accounts.draft.defaultAccount" variant="tonal" @click="makeDefault">{{ t('accounts.makeDefault') }}</v-btn><v-btn data-testid="smtp-test" variant="tonal" :loading="busy" @click="testAccount">{{ t('accounts.test') }}</v-btn><v-btn data-testid="account-save" color="primary" :loading="busy" @click="saveAccount">{{ t('common.save') }}</v-btn></div>
```

with:

```vue
          <v-text-field v-model="accounts.draft.password" type="password" autocomplete="new-password" :label="t('accounts.password')" :hint="t('accounts.passwordHelp')" persistent-hint />
          <v-card variant="outlined" class="pa-4">
            <div class="text-subtitle-1 font-weight-bold mb-3">{{ t('accounts.smtpSection') }}</div>
            <div class="form-grid">
              <v-text-field class="full-row" v-model="accounts.draft.smtpHost" :label="t('accounts.smtp')" />
              <v-text-field v-model.number="accounts.draft.smtpPort" type="number" :label="t('accounts.port')" />
              <v-select v-model="accounts.draft.smtpSecurity" :items="['SSL','STARTTLS','PLAIN']" :label="t('accounts.security')" />
            </div>
            <div class="d-flex ga-2 mt-3"><v-btn data-testid="smtp-test" variant="tonal" :loading="busy" @click="testAccount">{{ t('accounts.testSmtp') }}</v-btn></div>
          </v-card>
          <v-card variant="outlined" class="pa-4">
            <div class="text-subtitle-1 font-weight-bold mb-3">{{ t('accounts.imapSection') }}</div>
            <div class="form-grid">
              <v-text-field class="full-row" v-model="accounts.draft.imapHost" :label="t('accounts.imap')" />
              <v-text-field v-model.number="accounts.draft.imapPort" type="number" :label="t('accounts.port')" />
              <v-select v-model="accounts.draft.imapSecurity" :items="['SSL','STARTTLS','PLAIN']" :label="t('accounts.security')" />
            </div>
            <div class="d-flex ga-2 mt-3"><v-btn data-testid="imap-test" variant="tonal" :loading="busy" @click="testImapAccount">{{ t('accounts.testImap') }}</v-btn></div>
          </v-card>
          <v-checkbox v-model="accounts.draft.defaultAccount" :label="t('accounts.defaultAccount')" />
          <div class="d-flex ga-2 justify-end"><v-btn v-if="accounts.draft.id" color="error" variant="text" @click="removeAccount">{{ t('common.delete') }}</v-btn><v-btn v-if="accounts.draft.id && !accounts.draft.defaultAccount" variant="tonal" @click="makeDefault">{{ t('accounts.makeDefault') }}</v-btn><v-btn data-testid="account-save" color="primary" :loading="busy" @click="saveAccount">{{ t('common.save') }}</v-btn></div>
```

Key changes: the two `v-card`s replace the single grid; the SMTP test button moves into the SMTP card and its label key changes from `accounts.test` to `accounts.testSmtp`; a new IMAP card holds the IMAP test button (`data-testid="imap-test"`); the Save/Delete/Make-default buttons stay in the bottom action row (only the SMTP test button left that row).

- [ ] **Step 4: Run the frontend tests to verify nothing broke**

```bash
cd OfficialPlugins/plugin-email/ui-src && npm test
```
Expected: PASS. The existing `keeps account passwords write-only and separates test from save` test still works (the `smtp-test` and `account-save` buttons and the password input still render — `v-card`/`VCard` stubs are transparent). `shellCompliance.test.ts` passes (`.account-layout > :last-child > * + *` still in `styles.css`).

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/plugin-email/ui-src/src/styles.css OfficialPlugins/plugin-email/ui-src/src/components/AccountSettingsView.vue
git commit -m "✨ feat(email): split account SMTP/IMAP into separate cards with per-protocol test"
```

---

### Task 6: Assert the IMAP test button renders

**Files:**
- Test: `OfficialPlugins/plugin-email/ui-src/src/components/ManagementViews.test.ts`

**Context — the existing account-settings test (lines 36-47):**
```ts
it('keeps account passwords write-only and separates test from save', async () => {
  bridge.invoke.mockResolvedValue({ success: true, accounts: [] })
  const wrapper = mount(AccountSettingsView, options())
  expect(wrapper.get('input[type="password"]').attributes('autocomplete')).toBe('new-password')
  await wrapper.get('[data-testid="smtp-test"]').trigger('click')
  expect(bridge.invoke).toHaveBeenLastCalledWith('email_account_test', expect.any(Object))
  await wrapper.get('[data-testid="account-save"]').trigger('click')
  expect(bridge.invoke).toHaveBeenCalledWith('email_account_save', expect.any(Object))
  await vi.waitFor(() => expect(bridge.invoke).toHaveBeenLastCalledWith('email_accounts_list'))
})
```

- [ ] **Step 1: Add IMAP test button assertions to the existing test**

In `ManagementViews.test.ts`, inside the `keeps account passwords write-only and separates test from save` test, after the `smtp-test` assertion block (after line 41 `expect(bridge.invoke).toHaveBeenLastCalledWith('email_account_test', expect.any(Object))`) and before the `account-save` click, insert IMAP button existence + invocation assertions:

Change:
```ts
  await wrapper.get('[data-testid="smtp-test"]').trigger('click')
  expect(bridge.invoke).toHaveBeenLastCalledWith('email_account_test', expect.any(Object))
  await wrapper.get('[data-testid="account-save"]').trigger('click')
```
to:
```ts
  await wrapper.get('[data-testid="smtp-test"]').trigger('click')
  expect(bridge.invoke).toHaveBeenLastCalledWith('email_account_test', expect.any(Object))
  // SMTP and IMAP each have their own test button dispatching distinct methods.
  expect(wrapper.get('[data-testid="imap-test"]').element).toBeTruthy()
  await wrapper.get('[data-testid="imap-test"]').trigger('click')
  expect(bridge.invoke).toHaveBeenLastCalledWith('email_account_test_imap', expect.any(Object))
  await wrapper.get('[data-testid="account-save"]').trigger('click')
```

- [ ] **Step 2: Run frontend tests**

```bash
cd OfficialPlugins/plugin-email/ui-src && npm test
```
Expected: PASS — the `imap-test` button now renders inside the new IMAP card, and clicking it dispatches `email_account_test_imap`.

- [ ] **Step 3: Commit**

```bash
git add OfficialPlugins/plugin-email/ui-src/src/components/ManagementViews.test.ts
git commit -m "✅ test(email): assert IMAP test button renders and dispatches its method"
```

---

### Task 7: Full verification + manual visual check

- [ ] **Step 1: Run the entire plugin-email backend test suite**

```bash
./mvnw -pl OfficialPlugins/plugin-email test
```
Expected: PASS (all Java tests green).

- [ ] **Step 2: Run the entire frontend test suite**

```bash
cd OfficialPlugins/plugin-email/ui-src && npm test
```
Expected: PASS (all Vitest suites green, including `shellCompliance.test.ts` and `ManagementViews.test.ts`).

- [ ] **Step 3: Build the plugin UI (catches TS type errors the unit tests may skip)**

```bash
cd OfficialPlugins/plugin-email/ui-src && npm run build
```
Expected: build succeeds, no TS errors (validates the new i18n keys are referenced correctly and no removed key like `accounts.test` is still referenced anywhere).

- [ ] **Step 4 (manual, optional but recommended): visual check**

Start the dev frontend and open Account Settings:
```bash
cd OfficialPlugins/plugin-email/ui-src && npm run dev
```
Confirm visually: two outlined cards stacked vertically (Outgoing · SMTP on top, Incoming · IMAP below), each with its own title, host field on its own row, port + security on one row, and a test button inside each card. Narrow the browser to <720px and confirm each card's grid collapses to a single column.

- [ ] **Step 5: Final diff sanity check**

```bash
git diff main --stat -- OfficialPlugins/plugin-email/
```
Expected: only the 11 files listed in the File Structure table are touched; no stray files.
