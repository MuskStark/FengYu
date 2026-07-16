# Email Center Plugin Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the 3.2.0 email sender and archive tools into one official, SDK-based FengYu 4.0.0 Email Center plugin with multi-account SMTP/IMAP, manual collection, address-book and batch workflows, and confirmed AI sending.

**Architecture:** Extend the official Java Worker SDK with a permission-gated database environment contract, inject the configured host JDBC descriptor into isolated plugin Workers, and keep all email tables, migrations, encryption, and business logic inside `plugin-email`. The sandboxed Vue/Vuetify UI communicates only through `@infinia/plugin-sdk`; AI sends create immutable pending operations and require a host-rendered confirmation before SMTP dispatch.

**Tech Stack:** Java 21, FengYu Java Worker SDK, JSON-RPC 2.0, JDBC, MyBatis 3.5, H2/SQLite/MySQL/PostgreSQL, Simple Java Mail 8.12.6, Angus Mail 2.0.4, GreenMail 2.1.3, Vue 3.5, Vuetify 3, TipTap 2, TypeScript, Vite, Vitest.

## Global Constraints

- All host integration uses the official Java and TypeScript FengYu SDKs; the email plugin may not implement its own JSON-RPC loop, `FileRef`, or `postMessage` bridge.
- The plugin is an isolated `.fyp` Worker package and does not run inside the host Spring context.
- The host provides only the configured JDBC connection descriptor and stable plugin data directory; the plugin owns schema, migrations, queries, and credential encryption.
- Every source DDL table identifier begins with `FengTu_PL_Email_`; normal unquoted identifier case normalization is allowed.
- No legacy host email table is read, written, or migrated.
- SMTP/IMAP credentials use plugin-owned AES-256-GCM encryption; secrets never enter logs, API responses, or AI context.
- Multiple accounts are supported; mail collection is manual only.
- AI single and batch sends require explicit user confirmation and are idempotent under replay or concurrent approval.
- Supported host databases are H2, SQLite, MySQL, and PostgreSQL.
- The existing untracked `plugin-markdown/` directory is user-owned and must not be edited, staged, or deleted.

---

## File Structure

### Official SDK and host

- `FengYu-Plugin-Sdk/src/main/java/fan/summer/fengyu/sdk/PluginDatabaseConfig.java` — official immutable database environment contract.
- `FengYu-Plugin-Sdk/src/main/java/fan/summer/fengyu/sdk/PluginEnvironment.java` — official environment key constants and parsers.
- `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginRuntimeEnvironmentService.java` — permission gate and Worker environment assembly.
- `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java` — inject assembled values when starting Workers.
- `frontend/src/stores/aiSession.ts` and `frontend/src/views/AiChat.vue` — retain tool events and render confirmation cards.

### Email Worker

- `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/EmailWorkerMain.java` — SDK `JsonRpcWorker` registration only.
- `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/database/EmailDatabase.java` and `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/database/SchemaMigrator.java` — connection/session factory and versioned migrations.
- `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/crypto/CredentialCipher.java` and `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/crypto/PluginKeyStore.java` — AES-GCM and stable key file.
- `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/model/` — immutable account, contact, send, and archive records.
- `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/repository/` — table-specific MyBatis mappers and repository façades.
- `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/` — account, address-book, send, pending-send, and archive behavior.
- `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/rpc/EmailRpcHandlers.java` — UI RPC and AI tool method adapters.
- `OfficialPlugins/plugin-email/src/main/resources/db/h2/V1__email_schema.sql`, `OfficialPlugins/plugin-email/src/main/resources/db/sqlite/V1__email_schema.sql`, `OfficialPlugins/plugin-email/src/main/resources/db/mysql/V1__email_schema.sql`, and `OfficialPlugins/plugin-email/src/main/resources/db/postgresql/V1__email_schema.sql` — exact per-dialect schemas.

### Email UI and package

- `OfficialPlugins/plugin-email/ui-src/` — Vue/Vuetify/TipTap iframe application using `@infinia/plugin-sdk`.
- `OfficialPlugins/packages/email/manifest.json` — permissions, UI/backend entries, and AI tool schemas.
- `OfficialPlugins/build-packages.sh` — build and package `fan.summer.email-4.0.0.fyp`.

---

### Task 1: Add the official Java SDK database environment contract

**Files:**
- Create: `FengYu-Plugin-Sdk/src/main/java/fan/summer/fengyu/sdk/PluginEnvironment.java`
- Create: `FengYu-Plugin-Sdk/src/main/java/fan/summer/fengyu/sdk/PluginDatabaseConfig.java`
- Create: `FengYu-Plugin-Sdk/src/test/java/fan/summer/fengyu/sdk/PluginDatabaseConfigTest.java`
- Modify: `docs/plugins/sdk-cli.md`

**Interfaces:**
- Produces: `PluginDatabaseConfig.fromEnvironment(Map<String,String>) -> Optional<PluginDatabaseConfig>`.
- Produces: `PluginEnvironment` constants `FENGYU_DB_TYPE`, `FENGYU_DB_DRIVER`, `FENGYU_DB_URL`, `FENGYU_DB_USERNAME`, `FENGYU_DB_PASSWORD`, and `FENGYU_PLUGIN_DATA_DIR`.

- [ ] **Step 1: Write failing SDK parsing and redaction tests**

```java
@Test void completeEnvironmentBuildsConfig() {
    var env = Map.of(
        PluginEnvironment.DB_TYPE, "h2", PluginEnvironment.DB_DRIVER, "org.h2.Driver",
        PluginEnvironment.DB_URL, "jdbc:h2:mem:mail", PluginEnvironment.DB_USERNAME, "sa",
        PluginEnvironment.DB_PASSWORD, "secret", PluginEnvironment.PLUGIN_DATA_DIR, temp.toString());
    var config = PluginDatabaseConfig.fromEnvironment(env).orElseThrow();
    assertEquals("jdbc:h2:mem:mail", config.url());
    assertEquals("secret", config.password());
    assertFalse(config.toString().contains("secret"));
}

@Test void absentDatabaseValuesReturnEmpty() {
    assertTrue(PluginDatabaseConfig.fromEnvironment(Map.of()).isEmpty());
}

@Test void partialDatabaseValuesFailWithoutEchoingSecrets() {
    var error = assertThrows(IllegalArgumentException.class, () ->
        PluginDatabaseConfig.fromEnvironment(Map.of(PluginEnvironment.DB_PASSWORD, "secret")));
    assertFalse(error.getMessage().contains("secret"));
}
```

- [ ] **Step 2: Run the SDK test and verify RED**

Run: `mvn -pl FengYu-Plugin-Sdk -Dtest=PluginDatabaseConfigTest test`

Expected: compilation failure because `PluginEnvironment` and `PluginDatabaseConfig` do not exist.

- [ ] **Step 3: Implement the minimal official SDK types**

```java
public record PluginDatabaseConfig(String type, String driver, String url,
        String username, String password, Path dataDirectory) {
    public static Optional<PluginDatabaseConfig> fromEnvironment(Map<String, String> env) {
        boolean any = PluginEnvironment.databaseKeys().stream().anyMatch(env::containsKey);
        if (!any) return Optional.empty();
        String type = required(env, PluginEnvironment.DB_TYPE);
        String driver = required(env, PluginEnvironment.DB_DRIVER);
        String url = required(env, PluginEnvironment.DB_URL);
        String data = required(env, PluginEnvironment.PLUGIN_DATA_DIR);
        return Optional.of(new PluginDatabaseConfig(type, driver, url,
            env.getOrDefault(PluginEnvironment.DB_USERNAME, ""),
            env.getOrDefault(PluginEnvironment.DB_PASSWORD, ""), Path.of(data)));
    }
    @Override public String toString() {
        return "PluginDatabaseConfig[type=" + type + ",driver=" + driver
            + ",url=" + url + ",username=" + username + ",password=<redacted>,dataDirectory="
            + dataDirectory + "]";
    }
}
```

- [ ] **Step 4: Run SDK tests and document the API**

Run: `mvn -pl FengYu-Plugin-Sdk test`

Expected: all SDK tests pass. Add the exact environment contract and redaction rule to `docs/plugins/sdk-cli.md`.

- [ ] **Step 5: Commit**

```bash
git add FengYu-Plugin-Sdk docs/plugins/sdk-cli.md
git commit -m "✨ feat(sdk): add plugin database environment contract"
```

### Task 2: Inject permission-gated database and data-directory values

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginRuntimeEnvironmentService.java`
- Create: `FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginRuntimeEnvironmentServiceTest.java`
- Modify: `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java`
- Modify: `FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginProcessManagerTest.java`

**Interfaces:**
- Consumes: `DataSourceConfigService.load()` and official `PluginEnvironment` keys.
- Produces: `Map<String,String> environmentFor(PluginManifest manifest)`.
- Produces: stable data directory `${user.home}/.fengyu/plugin-data/<pluginId>`.

- [ ] **Step 1: Write failing permission and stability tests**

```java
@Test void databasePermissionReceivesConnectionAndStableDataDirectory() {
    var values = service.environmentFor(manifest("fan.summer.email", List.of("database")));
    assertEquals("jdbc:h2:mem:host", values.get(PluginEnvironment.DB_URL));
    assertTrue(Path.of(values.get(PluginEnvironment.PLUGIN_DATA_DIR)).isDirectory());
    assertEquals(values, service.environmentFor(manifest("fan.summer.email", List.of("database"))));
}

@Test void pluginWithoutPermissionReceivesNoDatabaseSecrets() {
    assertTrue(service.environmentFor(manifest("fan.summer.markdown", List.of())).isEmpty());
}
```

- [ ] **Step 2: Run host tests and verify RED**

Run: `mvn -pl FengYu -Dtest=PluginRuntimeEnvironmentServiceTest test`

Expected: compilation failure because the service does not exist.

- [ ] **Step 3: Implement environment assembly and Worker injection**

```java
public Map<String, String> environmentFor(PluginManifest manifest) {
    if (manifest.permissions() == null || !manifest.permissions().contains("database")) return Map.of();
    DataSourceConfig cfg = dataSources.load();
    if (cfg == null) throw new IllegalStateException("Host database is not configured");
    Path data = root.resolve(manifest.id()).normalize();
    Files.createDirectories(data);
    return Map.of(PluginEnvironment.DB_TYPE, cfg.type().name().toLowerCase(),
        PluginEnvironment.DB_DRIVER, cfg.driver(), PluginEnvironment.DB_URL, cfg.url(),
        PluginEnvironment.DB_USERNAME, nullToEmpty(cfg.username()),
        PluginEnvironment.DB_PASSWORD, nullToEmpty(cfg.password()),
        PluginEnvironment.PLUGIN_DATA_DIR, data.toString());
}
```

In `PluginProcessManager.start`, add only:

```java
runtimeEnvironment.environmentFor(manifest).forEach(builder.environment()::put);
```

- [ ] **Step 4: Update constructor tests and verify no secret reaches command/log text**

Run: `mvn -pl FengYu -Dtest=PluginRuntimeEnvironmentServiceTest,PluginProcessManagerTest test`

Expected: all selected tests pass; assertions confirm the command list and exception messages do not contain the configured password.

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main FengYu/src/test
git commit -m "✨ feat(plugin): provide permission-gated database environment"
```

### Task 3: Extend the official SDK with readable directory selection

**Files:**
- Modify: `plugin-sdk/typescript/src/index.ts`
- Modify: `plugin-sdk/typescript/dist/index.js`
- Modify: `plugin-sdk/typescript/dist/index.d.ts`
- Modify: `plugin-sdk/typescript/test/sdk.test.mjs`
- Modify: `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginFileGrantService.java`
- Modify: `FengYu/src/main/java/fan/summer/fengyu/web/controller/PluginRuntimeFileController.java`
- Create: `FengYu/src/test/java/fan/summer/fengyu/web/controller/PluginRuntimeFileControllerTest.java`
- Modify: `frontend/src/api/client.ts`
- Modify: `frontend/src/views/PluginView.vue`
- Modify: `frontend/test/plugin-sdk-bridge.test.mjs`

**Interfaces:**
- Produces: official TypeScript SDK `files.inputDirectory()` returning a read-only directory `FileRef`.
- Produces: web directory upload that preserves safe relative filenames and desktop native read grants.

- [ ] **Step 1: Write failing SDK and host traversal tests**

The SDK test asserts a request with method `files.inputDirectory`. The controller test uploads `reports/a_Q1.pdf` and `reports/b_Q2.pdf`, resolves the returned directory grant, and rejects `../outside.txt` and absolute paths.

- [ ] **Step 2: Run tests and verify RED**

Run: `npm --prefix plugin-sdk/typescript test && mvn -pl FengYu -Dtest=PluginRuntimeFileControllerTest test`

Expected: SDK assertion and Java compilation fail because the new capability is absent.

- [ ] **Step 3: Implement the SDK method and host bridge**

```ts
inputDirectory: (request?: InvokeOptions) =>
  this.request<FileRef | null>('files.inputDirectory', {}, request),
```

In desktop mode, `PluginView` uses `pickDirectory()` and grants `kind=directory, access=read`. In web mode, it creates an input with `webkitdirectory`, sends all selected files plus their `webkitRelativePath` values to the authenticated runtime file endpoint, and receives one read-only directory grant.

- [ ] **Step 4: Implement safe directory upload**

`PluginFileGrantService` creates one private temporary directory, normalizes every relative path against that root, rejects escapes/absolute paths, writes each file, and registers the root as a read-only directory `FileRef`.

- [ ] **Step 5: Verify SDK, host, and bridge tests**

Run: `npm --prefix plugin-sdk/typescript test && mvn -pl FengYu -Dtest=PluginRuntimeFileControllerTest test && npm --prefix frontend test`

Expected: all selected tests pass and `host.ready` advertises `files.inputDirectory`.

- [ ] **Step 6: Commit**

```bash
git add plugin-sdk/typescript FengYu/src frontend/src frontend/test
git commit -m "✨ feat(sdk): add readable directory capability"
```

### Task 4: Add generic AI tool confirmation cards

**Files:**
- Modify: `frontend/src/stores/aiSession.ts`
- Modify: `frontend/src/views/AiChat.vue`
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/i18n/en.json`
- Modify: `frontend/src/i18n/zh.json`
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Create: `frontend/src/stores/aiConfirmation.ts`
- Create: `frontend/src/stores/aiConfirmation.test.ts`

**Interfaces:**
- Consumes: SSE `tool` events whose result `output` contains JSON.
- Produces: `ToolConfirmation` with `pluginId`, `confirmationId`, `approveMethod`, `rejectMethod`, `expiresAt`, and safe `summary` rows.
- Produces: `approveConfirmation()` and `rejectConfirmation()` using `api.pluginInvoke`.

- [ ] **Step 1: Write failing parser and idempotent action tests**

```ts
it('parses confirmation_required output', () => {
  const result = parseToolConfirmation({ phase: 'result', output: JSON.stringify({
    confirmation_required: true,
    confirmation: { pluginId: 'fan.summer.email', confirmationId: 'c1',
      approveMethod: 'confirm_send', rejectMethod: 'reject_send',
      expiresAt: '2026-07-13T12:30:00Z', summary: [{ label: 'Recipients', value: '12' }] },
  }) })
  expect(result?.confirmationId).toBe('c1')
})
```

- [ ] **Step 2: Run frontend test and verify RED**

Add `"typecheck": "vue-tsc --noEmit"` and `"test:unit": "vitest run"` without replacing the existing Node smoke-test script. Add Vitest as a dev dependency.

Run: `npm --prefix frontend run test:unit -- aiConfirmation.test.ts`

Expected: test fails because the parser does not exist.

- [ ] **Step 3: Implement parser, state, and one-shot approve/reject methods**

```ts
export async function actOnConfirmation(item: ToolConfirmation, approve: boolean) {
  if (item.status !== 'pending') return
  item.status = 'submitting'
  try {
    item.result = await api.pluginInvoke(item.pluginId,
      approve ? item.approveMethod : item.rejectMethod,
      { confirmationId: item.confirmationId })
    item.status = approve ? 'approved' : 'rejected'
  } catch (error) {
    item.status = 'error'
    item.error = error instanceof Error ? error.message : String(error)
  }
}
```

- [ ] **Step 4: Wire `onTool` into `aiSession` and render accessible confirmation cards**

The card displays safe summary rows, expiry time, Approve, Reject, submitted status, and the final plugin result. It must never render raw tool JSON or credentials.

Run: `npm --prefix frontend run typecheck && npm --prefix frontend run test:unit -- aiConfirmation.test.ts && npm --prefix frontend test`

Expected: typecheck and test pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "✨ feat(ai): add plugin tool confirmation cards"
```

### Task 5: Scaffold `plugin-email`, migrations, and credential encryption

**Files:**
- Modify: `pom.xml`
- Modify: `OfficialPlugins/pom.xml`
- Create: `OfficialPlugins/plugin-email/pom.xml`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/database/EmailDatabase.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/database/SchemaMigrator.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/crypto/PluginKeyStore.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/crypto/CredentialCipher.java`
- Create: `OfficialPlugins/plugin-email/src/main/resources/db/h2/V1__email_schema.sql`
- Create: `OfficialPlugins/plugin-email/src/main/resources/db/sqlite/V1__email_schema.sql`
- Create: `OfficialPlugins/plugin-email/src/main/resources/db/mysql/V1__email_schema.sql`
- Create: `OfficialPlugins/plugin-email/src/main/resources/db/postgresql/V1__email_schema.sql`
- Create: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/database/SchemaMigratorTest.java`
- Create: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/crypto/CredentialCipherTest.java`

**Interfaces:**
- Consumes: `PluginDatabaseConfig` from the official SDK.
- Produces: `EmailDatabase.openSession()` and `CredentialCipher.encrypt/decrypt`.

- [ ] **Step 1: Add failing schema-prefix, repeatability, and crypto tests**

```java
@Test void h2MigrationCreatesOnlyEmailPrefixedTables() {
    migrator.migrate(); migrator.migrate();
    assertEquals(Set.of("FENGTU_PL_EMAIL_SCHEMA_HISTORY", "FENGTU_PL_EMAIL_ACCOUNT",
        "FENGTU_PL_EMAIL_CONTACT", "FENGTU_PL_EMAIL_TAG", "FENGTU_PL_EMAIL_CONTACT_TAG",
        "FENGTU_PL_EMAIL_MASS_CONFIG", "FENGTU_PL_EMAIL_PENDING_SEND",
        "FENGTU_PL_EMAIL_SENT_LOG", "FENGTU_PL_EMAIL_ARCHIVE"), userTables(connection));
}

@Test void aesGcmUsesFreshNonceAndRejectsTampering() {
    String first = cipher.encrypt("mail-password");
    String second = cipher.encrypt("mail-password");
    assertNotEquals(first, second);
    assertEquals("mail-password", cipher.decrypt(first));
    assertThrows(GeneralSecurityException.class, () -> cipher.decrypt(first.substring(0, first.length()-2) + "aa"));
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -pl OfficialPlugins/plugin-email -am -Dtest=SchemaMigratorTest,CredentialCipherTest test`

Expected: reactor failure because the module and classes do not exist.

- [ ] **Step 3: Create the module, exact nine-table DDL, and version history**

Add root-managed `mybatis.version=3.5.19` and `greenmail.version=2.1.3`. The email module depends on the official Worker SDK, Gson, MyBatis, Simple Java Mail, Angus Mail, all four JDBC drivers, JUnit Jupiter, and test-scoped GreenMail. Configure the shade plugin with both manifest and services-resource transformers.

Use unquoted source identifiers beginning `FengTu_PL_Email_`. Add unique constraints for account email, tag name, contact email, `(account_id,folder,message_uid)`, and pending confirmation ID. Add indexes for archive subject/from/date and send-log created/status.

- [ ] **Step 4: Implement AES-256-GCM format and key file**

```java
public String encrypt(String plaintext) {
    byte[] nonce = new byte[12]; random.nextBytes(nonce);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
    return "v1:" + b64(nonce) + ":" + b64(cipher.doFinal(plaintext.getBytes(UTF_8)));
}
```

Write `credential.key` atomically under `PluginDatabaseConfig.dataDirectory()` and request owner read/write permissions on POSIX filesystems.

- [ ] **Step 5: Verify H2 and SQLite migrations and crypto**

Run: `mvn -pl OfficialPlugins/plugin-email -am test`

Expected: selected migration and encryption tests pass twice in the same process.

- [ ] **Step 6: Commit**

```bash
git add pom.xml OfficialPlugins/pom.xml OfficialPlugins/plugin-email
git commit -m "✨ feat(email): scaffold database and credential encryption"
```

### Task 6: Implement accounts, contacts, tags, and batch configurations

**Files:**
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/model/EmailAccount.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/model/Contact.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/model/Tag.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/model/MassConfig.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/repository/AccountRepository.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/repository/AddressBookRepository.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/repository/MassConfigRepository.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/AccountService.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/AddressBookService.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/rpc/AccountRpc.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/rpc/AddressBookRpc.java`
- Create: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/service/AccountServiceTest.java`
- Create: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/service/AddressBookServiceTest.java`

**Interfaces:**
- Produces: account CRUD/list/default/test request models with password write-only semantics.
- Produces: contact/tag CRUD, search, bulk tag assignment, and recipient resolution.

- [ ] **Step 1: Write failing account and address-book behavior tests**

Cover two accounts, exactly one default account, password preservation on blank edit, encrypted database value, contact search, tag uniqueness, bulk tag assignment, and resolving distinct recipients for multiple tags.

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -pl OfficialPlugins/plugin-email -Dtest=AccountServiceTest,AddressBookServiceTest test`

Expected: compilation failure for missing services.

- [ ] **Step 3: Implement immutable models and parameterized MyBatis repositories**

Repository methods use exact signatures:

```java
Optional<EmailAccount> findAccount(long id);
List<EmailAccount> listAccounts();
long saveAccount(AccountInput input, String encryptedPassword);
List<Contact> searchContacts(String query, Set<Long> tagIds, int offset, int limit);
Set<String> resolveRecipientEmails(Set<Long> tagIds);
```

- [ ] **Step 4: Implement account and address-book services and RPC-safe DTOs**

Account output includes `passwordConfigured: boolean` and never the encrypted or plaintext password. Account deletion is implemented here for accounts with no send workflow; Task 8 adds the open-send guard when the pending repository exists.

- [ ] **Step 5: Verify service tests**

Run: `mvn -pl OfficialPlugins/plugin-email -Dtest=AccountServiceTest,AddressBookServiceTest test`

Expected: all selected tests pass.

- [ ] **Step 6: Commit**

```bash
git add OfficialPlugins/plugin-email
git commit -m "✨ feat(email): add accounts and address book"
```

### Task 7: Implement SMTP connection testing and single sending

**Files:**
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/model/EmailMessageRequest.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/model/SendResult.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/EmailSendService.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/repository/SentLogRepository.java`
- Create: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/service/EmailSendServiceTest.java`
- Modify: `OfficialPlugins/plugin-email/pom.xml`

**Interfaces:**
- Produces: `testSmtp(long accountId)` and `sendSingle(EmailMessageRequest request)`.
- Consumes: decrypted account credentials only inside the send call.

- [ ] **Step 1: Add GreenMail and write failing SMTP tests**

Test To/CC/BCC, HTML plus plain-text alternative, two attachments, CR/LF header rejection, nonexistent attachment rejection, timeout configuration, and per-message sent-log insertion.

- [ ] **Step 2: Run SMTP tests and verify RED**

Run: `mvn -pl OfficialPlugins/plugin-email -Dtest=EmailSendServiceTest test`

Expected: compilation failure because `EmailSendService` does not exist.

- [ ] **Step 3: Implement Simple Java Mail transport and validation**

```java
if (containsCrLf(request.subject()) || request.allRecipients().stream().anyMatch(this::containsCrLf))
    throw new IllegalArgumentException("Email headers contain illegal characters");
Mailer mailer = MailerBuilder.withSMTPServer(account.smtpHost(), account.smtpPort(),
    account.email(), cipher.decrypt(account.encryptedPassword()))
    .withTransportStrategy(account.transportStrategy()).withSessionTimeout(10_000).buildMailer();
```

- [ ] **Step 4: Persist immutable success/failure snapshots and verify tests**

Run: `mvn -pl OfficialPlugins/plugin-email -Dtest=EmailSendServiceTest test`

Expected: GreenMail receives the expected message and all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/plugin-email
git commit -m "✨ feat(email): add SMTP single sending"
```

### Task 8: Implement pending confirmation and both batch modes

**Files:**
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/model/PendingSend.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/repository/PendingSendRepository.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/PendingSendService.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/BatchPlanner.java`
- Create: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/service/PendingSendServiceTest.java`
- Create: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/service/BatchPlannerTest.java`
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/AccountService.java`
- Modify: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/service/AccountServiceTest.java`

**Interfaces:**
- Produces: `prepareSingle`, `prepareBatchByTags`, `prepareBatchByFilename`, `confirm`, `reject`, and `status`.
- Produces: generic `confirmation_required` envelope with a 30-minute expiry.

- [ ] **Step 1: Write failing confirmation and batch-planning tests**

Cover preparation sending zero messages, exact recipient snapshot, filename suffix grouping, partial batch failure, expiry, rejection, replay, and two concurrent confirmations resulting in one SMTP execution.

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -pl OfficialPlugins/plugin-email -Dtest=PendingSendServiceTest,BatchPlannerTest test`

Expected: compilation failure for missing pending services.

- [ ] **Step 3: Implement immutable JSON snapshots and atomic state transition**

Use a conditional update:

```sql
UPDATE FengTu_PL_Email_Pending_Send
SET status = 'SENDING', updated_at = CURRENT_TIMESTAMP
WHERE confirmation_id = #{id} AND status = 'PENDING' AND expires_at > CURRENT_TIMESTAMP
```

Proceed only when the update count is exactly one.

- [ ] **Step 4: Implement tag and filename planners and explicit failed-recipient retry**

The filename parser uses the final underscore and final extension dot. A file without both delimiters is ignored and reported in the preview. Retrying creates a new pending record and never reopens a terminal record.

Update `AccountService.deleteAccount(long id)` to reject deletion when `PendingSendRepository.hasOpenForAccount(id)` is true, and add the corresponding account service test.

- [ ] **Step 5: Verify concurrency and GreenMail counts**

Run: `mvn -pl OfficialPlugins/plugin-email -Dtest=PendingSendServiceTest,BatchPlannerTest test`

Expected: one message per planned batch and no duplicate under concurrent confirm.

- [ ] **Step 6: Commit**

```bash
git add OfficialPlugins/plugin-email
git commit -m "✨ feat(email): add confirmed batch sending"
```

### Task 9: Implement manual IMAP collection and archive search

**Files:**
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/model/ArchiveRequest.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/model/ArchivedMessage.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/repository/ArchiveRepository.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/EmailArchiveService.java`
- Create: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/service/EmailArchiveServiceTest.java`

**Interfaces:**
- Produces: `collect(ArchiveRequest, ProgressSink)`, paginated `search`, and `detail`.
- Deduplicates by `(account_id, folder, message_uid)`.

- [ ] **Step 1: Write failing GreenMail IMAP tests**

Cover date filtering, custom folder, `.eml` output, UID duplicate skipping, bounded preview, attachment detection, sanitized filenames, one malformed-message failure continuing the run, and paginated search.

- [ ] **Step 2: Run archive tests and verify RED**

Run: `mvn -pl OfficialPlugins/plugin-email -Dtest=EmailArchiveServiceTest test`

Expected: compilation failure because the archive service does not exist.

- [ ] **Step 3: Implement manual IMAP collection**

Write each message to `<final>.tmp-<uuid>`, close it, atomically move to `<safe-subject>_<uid>.eml`, then insert metadata. Delete the final file if insertion fails. Do not add a scheduler or background polling bean.

- [ ] **Step 4: Implement parameterized pagination and bounded AI results**

Search accepts account, folder, sender, subject, start/end timestamps, offset, and limit capped at 100. AI output returns a maximum 500-character preview and never raw `.eml` content.

- [ ] **Step 5: Verify archive tests**

Run: `mvn -pl OfficialPlugins/plugin-email -Dtest=EmailArchiveServiceTest test`

Expected: the second collection skips duplicates and all selected tests pass.

- [ ] **Step 6: Commit**

```bash
git add OfficialPlugins/plugin-email
git commit -m "✨ feat(email): add manual IMAP collection"
```

### Task 10: Register UI RPC and AI tools through the official Worker SDK

**Files:**
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/rpc/EmailRpcHandlers.java`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/EmailWorkerMain.java`
- Create: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/EmailWorkerMainTest.java`
- Create: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/OfficialSdkUsageTest.java`

**Interfaces:**
- Consumes: official `JsonRpcWorker`, `PluginHandler`, `FileRef`, and `PluginDatabaseConfig`.
- Produces: account/contact/tag/config/send/archive UI methods plus seven manifest AI methods.

- [ ] **Step 1: Write failing Worker round-trip and SDK-usage tests**

The test sends newline-delimited JSON-RPC for `email_accounts_list`, `email_contacts_query`, `email_send_single`, `email_send_batch`, `email_send_status`, `email_archive_fetch`, and `email_archive_query`. A source scan rejects declarations named `JsonRpcWorker` or `FileRef` under the email package.

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -pl OfficialPlugins/plugin-email -Dtest=EmailWorkerMainTest,OfficialSdkUsageTest test`

Expected: compilation failure because the Worker is absent.

- [ ] **Step 3: Register handlers on the official SDK Worker**

```java
return new JsonRpcWorker()
    .on("email_accounts_list", handlers::listAccounts)
    .on("email_contacts_query", handlers::queryContacts)
    .on("email_send_single", handlers::prepareSingle)
    .on("email_send_batch", handlers::prepareBatch)
    .on("email_send_status", handlers::sendStatus)
    .on("email_archive_fetch", handlers::collect)
    .on("email_archive_query", handlers::queryArchive)
    .on("confirm_send", handlers::confirmSend)
    .on("reject_send", handlers::rejectSend);
```

- [ ] **Step 4: Verify protocol cleanliness and tool result contracts**

Run: `mvn -pl OfficialPlugins/plugin-email -Dtest=EmailWorkerMainTest,OfficialSdkUsageTest test`

Expected: stdout contains only matching JSON-RPC responses; AI results contain `{success,summary}` and send preparation contains `confirmation_required`.

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/plugin-email
git commit -m "✨ feat(email): expose SDK worker and AI tools"
```

### Task 11: Build the sandboxed Vue/Vuetify/TipTap Email Center UI

**Files:**
- Create: `OfficialPlugins/plugin-email/ui-src/package.json`
- Create: `OfficialPlugins/plugin-email/ui-src/vite.config.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/main.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/App.vue`
- Create: `OfficialPlugins/plugin-email/ui-src/src/sdk.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/components/ComposeTab.vue`
- Create: `OfficialPlugins/plugin-email/ui-src/src/components/BatchTab.vue`
- Create: `OfficialPlugins/plugin-email/ui-src/src/components/AddressBookTab.vue`
- Create: `OfficialPlugins/plugin-email/ui-src/src/components/CollectTab.vue`
- Create: `OfficialPlugins/plugin-email/ui-src/src/components/RecordsAccountsTab.vue`
- Create: `OfficialPlugins/plugin-email/ui-src/src/stores/accounts.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/stores/contacts.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/stores/compose.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/stores/archive.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/stores/emailUi.test.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/styles.css`

**Interfaces:**
- Consumes: only `FengYuClient.ready()`, `invoke()`, `files.open()`, `files.inputDirectory()`, `files.outputDirectory()`, and `environment` events from `@infinia/plugin-sdk`.
- Produces: five-tab UI and local confirmation for visual single/batch sends.

- [ ] **Step 1: Write failing UI state tests**

Test multi-account switching, write-only password state, tag recipient preview, filename-mode preview, confirmation summary, archive pagination, task progress counters, and theme/locale environment updates.

- [ ] **Step 2: Run UI tests and verify RED**

Run: `npm --prefix OfficialPlugins/plugin-email/ui-src test`

Expected: failure because the UI project and stores do not exist.

- [ ] **Step 3: Scaffold Vue, Vuetify, TipTap, Vitest, and official SDK dependency**

Use `"@infinia/plugin-sdk": "file:../../../plugin-sdk/typescript"`. `src/sdk.ts` constructs one `FengYuClient`, calls `ready()`, subscribes to `environment`, and disposes it during app unmount. No component calls `window.postMessage` or `/api/*`.

- [ ] **Step 4: Implement the five tabs with focused stores**

All user-visible errors are actionable. Send buttons first call a preparation RPC and render the returned safe summary; visual sends call `confirm_send` only after a local confirmation dialog. Attachments and archive output use SDK file capabilities.

- [ ] **Step 5: Add a static official-SDK enforcement test**

```ts
expect(allSource).not.toMatch(/postMessage\s*\(/)
expect(allSource).not.toMatch(/fetch\s*\(\s*['"`]\/api\//)
expect(allSource).toContain('@infinia/plugin-sdk')
```

- [ ] **Step 6: Verify UI**

Run: `npm --prefix OfficialPlugins/plugin-email/ui-src run typecheck && npm --prefix OfficialPlugins/plugin-email/ui-src test && npm --prefix OfficialPlugins/plugin-email/ui-src run build`

Expected: typecheck, tests, and production build pass.

- [ ] **Step 7: Commit**

```bash
git add OfficialPlugins/plugin-email/ui-src OfficialPlugins/packages/email/ui
git commit -m "✨ feat(email): add SDK-based Email Center UI"
```

### Task 12: Package and seed the official Email Center

**Files:**
- Create: `OfficialPlugins/packages/email/manifest.json`
- Modify: `OfficialPlugins/build-packages.sh`
- Modify: `OfficialPlugins/plugin-email/pom.xml`
- Create: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/EmailManifestTest.java`
- Modify: `scripts/e2e-smoke.sh`

**Interfaces:**
- Produces: `OfficialPlugins/target/packages/fan.summer.email-4.0.0.fyp`.
- Manifest ID: `fan.summer.email`; permissions: `database`, `network.email`, `files.read`, `files.write`.

- [ ] **Step 1: Write failing manifest contract test**

Assert semantic version, official ID, existing UI/backend paths, exact permissions, unique tool names, valid JSON schemas, and tool-to-Worker method mapping.

- [ ] **Step 2: Run manifest test and verify RED**

Run: `mvn -pl OfficialPlugins/plugin-email -Dtest=EmailManifestTest test`

Expected: failure because `packages/email/manifest.json` does not exist.

- [ ] **Step 3: Add the manifest and shaded Worker configuration**

The manifest exposes the seven AI tools from Task 10. Single and batch descriptions explicitly state that the first call prepares a confirmation and does not send immediately.

- [ ] **Step 4: Extend the package builder**

Build the official TypeScript SDK first, build the email UI, copy `email-worker.jar`, validate service resources, zip the package, and retain existing Markdown/Excel behavior.

- [ ] **Step 5: Verify package contents and smoke discovery**

Run: `mvn -pl OfficialPlugins/plugin-email -am package && bash OfficialPlugins/build-packages.sh && unzip -l OfficialPlugins/target/packages/fan.summer.email-4.0.0.fyp`

Expected: exit 0; archive contains `manifest.json`, `ui/index.html`, JavaScript/CSS assets, and `backend/worker.jar`.

- [ ] **Step 6: Commit**

```bash
git add OfficialPlugins scripts/e2e-smoke.sh
git commit -m "✨ feat(email): package official Email Center plugin"
```

### Task 13: Add MySQL/PostgreSQL contracts, documentation, and full verification

**Files:**
- Create: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/database/RemoteDatabaseContractTest.java`
- Modify: `docs/plugins/database.md`
- Modify: `docs/zh/plugins/database.md`
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Create: `docs/plugins/email-center.md`

**Interfaces:**
- Consumes: all completed host, SDK, Worker, UI, and package surfaces.
- Produces: documented plugin database standard and release-ready verification evidence.

- [ ] **Step 1: Add opt-in MySQL and PostgreSQL contract tests**

Use CI-provided `FENGYU_TEST_MYSQL_URL` and `FENGYU_TEST_POSTGRESQL_URL`. When present, run migration twice, exercise account/contact/pending/archive repositories, and assert every created table begins with `FengTu_PL_Email_`. H2 and SQLite remain mandatory local tests.

- [ ] **Step 2: Run database contracts**

Run: `mvn -pl OfficialPlugins/plugin-email -Dtest=SchemaMigratorTest,RemoteDatabaseContractTest test`

Expected: H2 and SQLite pass; configured remote contracts pass, while absent remote URLs are reported as skipped rather than successful coverage.

- [ ] **Step 3: Update documentation**

Document the SDK database environment, permission gate, stable data directory, `FengTu_PL_` naming rule, independent plugin schemas, AES-GCM responsibility, multi-account Email Center, manual-only collection, and AI send confirmation.

- [ ] **Step 4: Run full backend and SDK reactor tests**

Run: `mvn clean verify`

Expected: reactor exits 0 with SDK, official plugins, and host tests passing.

- [ ] **Step 5: Run frontend and plugin UI verification**

Run: `npm --prefix frontend run typecheck && npm --prefix frontend run test:unit && npm --prefix frontend test && npm --prefix frontend run build && npm --prefix OfficialPlugins/plugin-email/ui-src run typecheck && npm --prefix OfficialPlugins/plugin-email/ui-src test && npm --prefix OfficialPlugins/plugin-email/ui-src run build`

Expected: all commands exit 0.

- [ ] **Step 6: Run packaging and end-to-end smoke**

Run: `bash OfficialPlugins/build-packages.sh && bash scripts/e2e-smoke.sh`

Expected: package build and smoke script exit 0; plugin discovery includes `fan.summer.email`.

- [ ] **Step 7: Audit requirements and secrets**

Run:

```bash
rg -n "CREATE TABLE" OfficialPlugins/plugin-email/src/main/resources/db
rg -n "postMessage|fetch\(.*\/api\/|class JsonRpcWorker|record FileRef" OfficialPlugins/plugin-email || true
git diff --check
git status --short
```

Expected: every DDL table uses `FengTu_PL_Email_`; no forbidden SDK duplication/direct host bridge appears; diff check is clean; only intended files plus the preserved untracked `plugin-markdown/` appear.

- [ ] **Step 8: Commit**

```bash
git add docs README.md CHANGELOG.md OfficialPlugins/plugin-email/src/test
git commit -m "📝 docs(email): document Email Center and database contract"
```

## 2026-07-14 execution handoff

Work was performed directly on branch `4.0.0-FengYu` as requested. Tasks 7–13 are implemented in
commits `568490d` through `5d1592f`; host locale propagation was fixed in `04cc2fe`.

Fresh verification completed during this run:

- `mvn clean verify`: all 8 reactor modules passed.
- Host frontend: 8 Node tests and 2 Vitest tests passed; TypeScript production build passed.
- Email UI: 10 test files / 22 tests passed; typecheck and production build passed.
- `npm run docs:build`: passed.
- `bash OfficialPlugins/build-packages.sh`: passed and produced the Email Center `.fyp` package.
- `bash scripts/e2e-smoke.sh`: passed the Email filename-tag batch-preview flow. The optional Excel
  smoke case was skipped because `openpyxl` was not installed.
- Browser verification at `http://127.0.0.1:5173/plugin/fan.summer.email` confirmed the redesigned
  six-workspace shell and Chinese host locale propagation.

Continuation note: after the locale fix, the TipTap rich-text placeholder still initially displays
the English text `Write your message…` because `Placeholder.configure` captures the translation at
editor creation time. No production change for this follow-up was started; continue with a failing
test and make the placeholder resolve the current locale reactively.
