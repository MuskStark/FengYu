# Email Addressbook Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the email plugin's Contacts (address book) UI — list rows with avatars + visible folded tags, a split right column (contact form + an independent tag-manager card with a searchable scrollable list), and a new `notes` field plumbed through the backend.

**Architecture:** Backend-first. Add a `notes` column via the plugin's versioned `SchemaMigrator` (new V5 migration, `LATEST_VERSION` 4→5), thread `notes` through Contact record → repository → service → RPC DTO → frontend store. Then pure-presentation UI work: rewrite `AddressBookTab.vue` (list rows, tag folding, split right column, tag-manager-as-card, notes field), add supporting CSS + i18n. RPC method names are unchanged; only `email_contact_save` gains an optional `notes` param.

**Tech Stack:** Java 21 records + MyBatis annotations + H2/Postgres/SQLite/MySQL (backend); Vue 3.5 + Vuetify 3 + TypeScript + Pinia (frontend); JUnit 5 + Vitest (tests).

**Spec:** `docs/superpowers/specs/2026-07-31-email-addressbook-redesign-design.md`

## Global Constraints

- All work confined to `OfficialPlugins/plugin-email/`. No other module.
- RPC method names are unchanged (`email_contact_save`, `email_contacts_query`, `email_tags_*`). Only `email_contact_save` gains optional `notes`.
- DB changes use the plugin's **own versioned migrator** (`database/SchemaMigrator.java`): add `V5__add_contact_notes.sql` per dialect; bump `LATEST_VERSION` 4→5. Do NOT edit V1.
- `notes` is optional (nullable); empty/blank stored as null (`trimToNull`); not part of search matching (search = email + nickname only).
- No real avatar images — avatars are the first letter of nickname/email, brand-gradient background.
- Tag search in the tag-manager card is **frontend-only** local filtering of `store.tags` (no new RPC).
- Maven wrapper: `./mvnw` at repo root. Frontend: `OfficialPlugins/plugin-email/ui-src`.
- Commit convention: conventional commits with emojis — `✨` feat, `🐛` fix, `♻️` refactor, `📝` docs.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `src/main/resources/db/{h2,postgresql,sqlite,mysql}/V5__add_contact_notes.sql` | Create (×4) | `ALTER TABLE ... ADD COLUMN notes` |
| `src/main/java/.../database/SchemaMigrator.java` | Modify | `LATEST_VERSION` 4→5 |
| `src/main/java/.../repository/AddressBookRepository.java` | Modify | `ContactRow`, `ContactInput`, INSERT/UPDATE/SELECT add `notes` |
| `src/main/java/.../service/AddressBookService.java` | Modify | `ContactInput` record add `notes`; pass-through |
| `src/main/java/.../model/Contact.java` | Modify | record add `notes` |
| `src/main/java/.../rpc/AddressBookRpc.java` | Modify | `ContactRequest` add `notes`; adapter wiring |
| `src/test/.../repository/AddressBookRepositoryTest.java` | Modify | add notes round-trip test |
| `ui-src/src/stores/contacts.ts` | Modify | `Contact` interface add `notes?` |
| `ui-src/src/i18n/en.ts` + `zh-CN.ts` | Modify | new keys: `edit`, `tagSearch`, `tagsMore`; enable `notes` |
| `ui-src/src/styles.css` | Modify | `.contact-row`, `.contact-avatar`, `.tag-overflow`, `.tag-manager-list` |
| `ui-src/src/components/AddressBookTab.vue` | Modify (rewrite template + script) | list rows, tag folding, split right column, tag-manager card, notes field |
| `ui-src/src/components/ManagementViews.test.ts` | Modify | adjust tag-manager assertion; add tag-fold + tag-search tests |

---

### Task 1: Backend `notes` field — DB migration + model/repo/service/RPC

**Files:**
- Create: `OfficialPlugins/plugin-email/src/main/resources/db/h2/V5__add_contact_notes.sql`
- Create: `OfficialPlugins/plugin-email/src/main/resources/db/postgresql/V5__add_contact_notes.sql`
- Create: `OfficialPlugins/plugin-email/src/main/resources/db/sqlite/V5__add_contact_notes.sql`
- Create: `OfficialPlugins/plugin-email/src/main/resources/db/mysql/V5__add_contact_notes.sql`
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/database/SchemaMigrator.java:15`
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/model/Contact.java`
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/repository/AddressBookRepository.java` (ContactRow, ContactInput, contact(), Mapper SQL)
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/AddressBookService.java:48`
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/rpc/AddressBookRpc.java` (ContactRequest + saveContact adapter)
- Test: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/repository/AddressBookRepositoryTest.java`

**Interfaces:**
- Produces: `Contact.notes()` (String, nullable); `AddressBookRepository.ContactInput(Long id, String email, String nickname, String notes)`; `AddressBookService.ContactInput(Long id, String email, String nickname, String notes)`; `AddressBookRpc.ContactRequest(... String notes)`. Later frontend tasks read `notes` from the contact payload and send it on save.

**Context — how the migrator decides what to run (`SchemaMigrator.java:34-38`):**
```java
if (!tableExists(connection, HISTORY)) executeScript(connection, resource("V1__email_schema.sql"));
int version = currentVersion(connection);
for (int next = version + 1; next <= LATEST_VERSION; next++) {
    executeScript(connection, resource("V" + next + "__email_schema.sql"));
}
```
A fresh install runs V1 (creates the Contact table WITHOUT notes), then V2..V5 in order — so V5's ALTER applies uniformly to fresh and existing DBs. `LATEST_VERSION` (line 15, currently `4`) is the upper bound and must become `5`.

- [ ] **Step 1: Write the failing test**

Add to `AddressBookRepositoryTest.java`. The class uses `@TempDir Path temp`, H2 in-memory via `PluginDatabaseConfig`, and `AddressBookRepository`. The existing test method `intersectsAttachmentTagWithAnySelectedGroupTag` builds its own `EmailDatabase`; add a new test method that saves a contact WITH notes and reads it back, plus a null-notes case.

Add this import (already imports `assertEquals`):
```java
import static org.junit.jupiter.api.Assertions.assertNull;
```

Append this test method inside the class (after the existing `@Test`):
```java
    @Test
    void persistsAndReadsBackContactNotes() {
        EmailDatabase database = new EmailDatabase(new PluginDatabaseConfig("h2", "org.h2.Driver",
            "jdbc:h2:mem:contact-notes;DB_CLOSE_DELAY=-1", "sa", "", temp));
        AddressBookRepository repository = new AddressBookRepository(database);

        long withNotes = repository.saveContact(new AddressBookRepository.ContactInput(
            null, "alice@example.com", "Alice", "VIP since 2024"));
        long withoutNotes = repository.saveContact(new AddressBookRepository.ContactInput(
            null, "bob@example.com", "Bob", "  "));

        assertEquals("VIP since 2024", repository.findContact(withNotes).orElseThrow().notes());
        assertNull(repository.findContact(withoutNotes).orElseThrow().notes());
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw -pl OfficialPlugins/plugin-email test -Dtest=AddressBookRepositoryTest#persistsAndReadsBackContactNotes
```
Expected: FAIL — compile error: `ContactInput` constructor does not accept a 4th `notes` arg (and `Contact.notes()` does not exist yet).

- [ ] **Step 3: Create the 4 V5 migration files**

Each file is two statements: the dialect-appropriate `ALTER TABLE` + a version-record insert (the migrator expects the version row; V2/V3/V4 use the same `INSERT INTO ... Schema_History` line).

`src/main/resources/db/h2/V5__add_contact_notes.sql`:
```sql
ALTER TABLE FENGYU_PL_Email_Contact ADD COLUMN notes VARCHAR(2000);
INSERT INTO FENGYU_PL_Email_Schema_History(version) VALUES (5);
```
`src/main/resources/db/postgresql/V5__add_contact_notes.sql`:
```sql
ALTER TABLE FENGYU_PL_Email_Contact ADD COLUMN notes VARCHAR(2000);
INSERT INTO FENGYU_PL_Email_Schema_History(version) VALUES (5);
```
`src/main/resources/db/sqlite/V5__add_contact_notes.sql`:
```sql
ALTER TABLE FENGYU_PL_Email_Contact ADD COLUMN notes TEXT;
INSERT INTO FENGYU_PL_Email_Schema_History(version) VALUES (5);
```
`src/main/resources/db/mysql/V5__add_contact_notes.sql`:
```sql
ALTER TABLE FENGYU_PL_Email_Contact ADD COLUMN notes VARCHAR(2000);
INSERT INTO FENGYU_PL_Email_Schema_History(version) VALUES (5);
```

- [ ] **Step 4: Bump `LATEST_VERSION`**

In `SchemaMigrator.java` line 15, change:
```java
    private static final int LATEST_VERSION = 4;
```
to:
```java
    private static final int LATEST_VERSION = 5;
```

- [ ] **Step 5: Add `notes` to the `Contact` record**

In `model/Contact.java`, add `notes` before `tagIds` (keep `tagIds` last because the compact constructor copies it):
```java
public record Contact(long id, String email, String nickname, String notes, LocalDateTime createdAt, Set<Long> tagIds) {
    public Contact {
        tagIds = tagIds == null ? Set.of() : Set.copyOf(tagIds);
    }
}
```

- [ ] **Step 6: Add `notes` to repository `ContactRow`, `ContactInput`, `contact()`, and Mapper SQL**

In `AddressBookRepository.java`:

(a) `ContactInput` record (line 138) — add `notes`:
```java
    public record ContactInput(Long id, String email, String nickname, String notes) { }
```

(b) `ContactRow` (lines 139-152) — add a `notes` field, the constructor param, and accessor:
```java
    private static final class ContactRow {
        private Long id;
        private String email;
        private String nickname;
        private String notes;
        private LocalDateTime createdAt;
        private ContactRow() { }
        private ContactRow(Long id, String email, String nickname, String notes, LocalDateTime createdAt) {
            this.id = id; this.email = email; this.nickname = nickname;
            this.notes = notes; this.createdAt = createdAt;
        }
        public Long id() { return id; }
        public String email() { return email; }
        public String nickname() { return nickname; }
        public String notes() { return notes; }
        public LocalDateTime createdAt() { return createdAt; }
    }
```

(c) `saveContact(ContactInput, Set<Long>)` (line 77) — pass `notes` into the row:
```java
            ContactRow row = new ContactRow(input.id(), input.email(), input.nickname(), input.notes(), null);
```

(d) `contact(ContactRow, List<Long>)` (line 159-161) — pass `notes`:
```java
    private static Contact contact(ContactRow row, List<Long> tagIds) {
        return new Contact(row.id(), row.email(), row.nickname(), row.notes(), row.createdAt(), new LinkedHashSet<>(tagIds));
    }
```

(e) Mapper SQL (lines 164, 165-169, 191, 193) — add `notes` to SELECTs, INSERT, UPDATE:
```java
        @Select("SELECT id,email,nickname,notes,created_at AS createdAt FROM FENGYU_PL_Email_Contact WHERE id=#{id}") ContactRow findContact(long id);
```
```java
        @Select({"<script>", "SELECT DISTINCT c.id,c.email,c.nickname,c.notes,c.created_at AS createdAt FROM FENGYU_PL_Email_Contact c",
            "<if test='tagIds != null and !tagIds.isEmpty()'> JOIN FENGYU_PL_Email_Contact_Tag ct ON ct.contact_id=c.id</if>",
            "WHERE (LOWER(c.email) LIKE #{pattern} OR LOWER(COALESCE(c.nickname,'')) LIKE #{pattern})",
            "<if test='tagIds != null and !tagIds.isEmpty()'> AND ct.tag_id IN <foreach item='id' collection='tagIds' open='(' separator=',' close=')'>#{id}</foreach></if>",
            "ORDER BY c.id LIMIT #{limit} OFFSET #{offset}", "</script>"})
        List<ContactRow> search(@Param("pattern") String pattern, @Param("tagIds") Set<Long> tagIds,
            @Param("offset") int offset, @Param("limit") int limit);
```
```java
        @Insert("INSERT INTO FENGYU_PL_Email_Contact(email,nickname,notes,created_at) VALUES(#{email},#{nickname},#{notes},CURRENT_TIMESTAMP)")
        @Options(useGeneratedKeys=true,keyProperty="id") int insertContact(ContactRow row);
        @Update("UPDATE FENGYU_PL_Email_Contact SET email=#{email},nickname=#{nickname},notes=#{notes} WHERE id=#{id}") int updateContact(ContactRow row);
```

- [ ] **Step 7: Add `notes` to `AddressBookService.ContactInput` + pass-through**

In `service/AddressBookService.java`:
- Line 48 record:
```java
    public record ContactInput(Long id, String email, String nickname, String notes) { }
```
- `saveContact(ContactInput, Set<Long>)` (lines 26-27) — pass `trimToNull(input.notes())` into the repository input:
```java
        return addressBook.saveContact(new AddressBookRepository.ContactInput(input.id(), email,
            trimToNull(input.nickname()), trimToNull(input.notes())), tagIds == null ? null : Set.copyOf(tagIds));
```

- [ ] **Step 8: Add `notes` to `AddressBookRpc.ContactRequest` + adapter wiring**

In `rpc/AddressBookRpc.java`:
- `ContactRequest` record (line 32):
```java
    public record ContactRequest(Long id, String email, String nickname, String notes, Set<Long> tagIds) { }
```
- `saveContact` adapter (lines 15-18) — pass `request.notes()`:
```java
    public Contact saveContact(ContactRequest request) {
        long id = addressBook.saveContact(new AddressBookService.ContactInput(
            request.id(), request.email(), request.nickname(), request.notes()), request.tagIds());
        return addressBook.findContact(id).orElseThrow();
    }
```

- [ ] **Step 9: Run the focused test to verify GREEN**

```bash
./mvnw -pl OfficialPlugins/plugin-email test -Dtest=AddressBookRepositoryTest
```
Expected: PASS (the new notes test + existing intersection test).

- [ ] **Step 10: Run the full backend suite to confirm no regressions**

```bash
./mvnw -pl OfficialPlugins/plugin-email test
```
Expected: PASS. (The `Contact` record signature changed — if any other test constructs `Contact` directly, fix it to add the `notes` arg; grep for `new Contact(` in `src/test` if a compile error appears.)

- [ ] **Step 11: Commit**

```bash
git add OfficialPlugins/plugin-email/src/main/resources/db/ \
        OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/database/SchemaMigrator.java \
        OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/model/Contact.java \
        OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/repository/AddressBookRepository.java \
        OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/AddressBookService.java \
        OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/rpc/AddressBookRpc.java \
        OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/repository/AddressBookRepositoryTest.java
git commit -m "✨ feat(email): add contact notes field (V5 migration + full stack)"
```

---

### Task 2: Frontend store `notes` + i18n keys

**Files:**
- Modify: `OfficialPlugins/plugin-email/ui-src/src/stores/contacts.ts` (Contact interface)
- Modify: `OfficialPlugins/plugin-email/ui-src/src/i18n/en.ts` (line 6, `contacts` object)
- Modify: `OfficialPlugins/plugin-email/ui-src/src/i18n/zh-CN.ts` (line 6, `contacts` object)

**Interfaces:**
- Produces: `Contact.notes?: string`; i18n keys `contacts.edit`, `contacts.tagSearch`, `contacts.tagsMore` (and `contacts.notes` already exists). Tasks 3/4 consume these.

- [ ] **Step 1: Add `notes` to the store `Contact` interface**

In `stores/contacts.ts`, the `Contact` interface (line 5):
```ts
export interface Contact { id: number; email: string; nickname?: string; notes?: string; tagIds?: number[] }
```

- [ ] **Step 2: Add i18n keys to `en.ts`**

In `en.ts` line 6, the `contacts:` object. Add `edit`, `tagSearch`, `tagsMore`. (`notes` already exists in the object.) Insert the three new keys; a minimal safe edit is to add them right after `filterTag`:

Change the segment:
```js
  contacts: { title: 'Contacts', newContact: 'New contact', editContact: 'Edit contact', manageTags: 'Manage tags', assignTags: 'Assign tags', filterTag: 'Filter by tags', newTag: 'New tag',
```
to:
```js
  contacts: { title: 'Contacts', newContact: 'New contact', editContact: 'Edit contact', edit: 'Edit', manageTags: 'Manage tags', assignTags: 'Assign tags', filterTag: 'Filter by tags', tagSearch: 'Search tags…', tagsMore: '+{count}', newTag: 'New tag',
```

- [ ] **Step 3: Add the same keys to `zh-CN.ts`**

In `zh-CN.ts` line 6, mirror with Chinese copy:
```js
  contacts: { title: '联系人', newContact: '新建联系人', editContact: '编辑联系人', edit: '编辑', manageTags: '管理标签', assignTags: '批量添加标签', filterTag: '按标签筛选', tagSearch: '搜索标签…', tagsMore: '+{count}', newTag: '新标签',
```
(replace the same segment, prefix to `newTag`).

- [ ] **Step 4: Commit**

```bash
git add OfficialPlugins/plugin-email/ui-src/src/stores/contacts.ts \
        OfficialPlugins/plugin-email/ui-src/src/i18n/en.ts \
        OfficialPlugins/plugin-email/ui-src/src/i18n/zh-CN.ts
git commit -m "✨ feat(email): add contact notes + addressbook i18n keys"
```

---

### Task 3: Contacts list — C detail rows with avatars + folded tags + supporting CSS

**Files:**
- Modify: `OfficialPlugins/plugin-email/ui-src/src/components/AddressBookTab.vue` (list portion of template + a script helper for avatar/fold)
- Modify: `OfficialPlugins/plugin-email/ui-src/src/styles.css` (add layout classes)

**Interfaces:**
- Consumes: `store.contacts` (now with `notes`), `store.tags`, `selectedContacts`, i18n keys. Existing `edit(item)` / `deleteContact(id)` handlers stay.
- Produces: a `<div class="contact-row">` per contact (replacing the `<v-list-item>`), with `data-testid="contact-row"`, tag pills folded to 2 + `+N`.

**Context — current list (AddressBookTab.vue:44):**
```vue
<v-list lines="two"><v-list-item v-for="item in store.contacts" :key="item.id" :title="item.nickname || item.email" :subtitle="item.email" @click="edit(item)"><template #prepend><v-checkbox-btn v-model="selectedContacts" :value="item.id" @click.stop /></template><template #append><v-btn variant="text" color="error" @click.stop="deleteContact(item.id)">{{ t('common.delete') }}</v-btn></template></v-list-item></v-list>
```

- [ ] **Step 1: Add the supporting CSS classes**

In `styles.css`, add after the `.inline-fields` rule (around line 100):
```css
.contact-row { display: flex; gap: 12px; align-items: flex-start; padding: 10px 4px; border-bottom: 1px solid var(--email-border); }
.contact-row__main { flex: 1; min-width: 0; }
.contact-row__name { font-weight: 600; font-size: 14px; }
.contact-row__email { color: var(--email-text-muted); font-size: 12px; margin-top: 1px; }
.contact-avatar { width: 36px; height: 36px; border-radius: 50%; flex: 0 0 36px; display: flex; align-items: center; justify-content: center; font-weight: 700; color: #fff; background: linear-gradient(135deg, var(--email-accent), var(--email-accent-2, #9c7fd8)); font-size: 14px; text-transform: uppercase; }
.tag-overflow { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
.tag-manager-list { max-height: 220px; overflow-y: auto; }
.tag-manager-row { display: flex; justify-content: space-between; align-items: center; padding: 8px 4px; border-bottom: 1px solid var(--email-border); }
.tag-manager-row:last-child { border-bottom: 0; }
```

- [ ] **Step 2: Add avatar + tag-fold helpers to `<script setup>`**

In `AddressBookTab.vue` `<script setup>`, after the `edit`/`reset` functions (around line 19), add:
```ts
const initials = (item: Contact) => (item.nickname || item.email || '?').charAt(0)
function tagName(id: number): string { return store.tags.find(tag => tag.id === id)?.name ?? '' }
function visibleTags(item: Contact): number[] { return (item.tagIds ?? []).slice(0, 2) }
function hiddenTagCount(item: Contact): number { return Math.max(0, (item.tagIds ?? []).length - 2) }
```

- [ ] **Step 3: Replace the `<v-list>` with C detail rows**

Replace the entire `<v-list>...</v-list>` line (line 44) with:
```vue
          <div class="contact-row" v-for="item in store.contacts" :key="item.id" data-testid="contact-row" @click="edit(item)">
            <v-checkbox-btn v-model="selectedContacts" :value="item.id" @click.stop />
            <div class="contact-avatar">{{ initials(item) }}</div>
            <div class="contact-row__main">
              <div class="contact-row__name">{{ item.nickname || item.email }}</div>
              <div class="contact-row__email">{{ item.email }}</div>
              <div class="tag-overflow" v-if="(item.tagIds ?? []).length">
                <v-chip v-for="id in visibleTags(item)" :key="id" size="x-small" label>{{ tagName(id) }}</v-chip>
                <v-chip v-if="hiddenTagCount(item) > 0" size="x-small" label :title="(item.tagIds ?? []).slice(2).map(tagName).join(', ')">
                  {{ t('contacts.tagsMore', { count: hiddenTagCount(item) }) }}
                </v-chip>
              </div>
            </div>
            <v-btn variant="text" color="error" size="small" @click.stop="deleteContact(item.id)">{{ t('common.delete') }}</v-btn>
          </div>
```

- [ ] **Step 4: Run the frontend tests**

```bash
cd OfficialPlugins/plugin-email/ui-src && npm test
```
Expected: PASS. (The existing `ManagementViews.test.ts` contact test stubs `VList`/`VListItem` and clicks `smtp-test` etc.; the contact-row change does not remove any testid the existing assertions use. If a test breaks because it expected `v-list-item`, adjust in Task 5's test work.)

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/plugin-email/ui-src/src/components/AddressBookTab.vue OfficialPlugins/plugin-email/ui-src/src/styles.css
git commit -m "✨ feat(email): contact list rows with avatars and folded tag pills"
```

---

### Task 4: Split right column — contact form (with notes) + independent tag-manager card

**Files:**
- Modify: `OfficialPlugins/plugin-email/ui-src/src/components/AddressBookTab.vue` (right column template + remove `v-dialog` + add `notes` ref + tag-search ref)

**Interfaces:**
- Consumes: i18n keys (incl. `contacts.notes`, `contacts.tagSearch`), `email_tag_save`/`email_tag_delete` RPC (unchanged). Existing `tagName`/`addTag`/`deleteTag` helpers from Task 3.
- Produces: right column = `<div>` stacking two `v-card`s: contact form (with `v-textarea` notes) + tag-manager card (`data-testid="tag-manager-card"`, searchable list). The `v-dialog` tag manager and `data-testid="tag-manager-open"` button are removed.

**Context — current right card + dialog (AddressBookTab.vue:47 and 49):**
- Right card (line 47): form card with actions incl. `<v-btn data-testid="tag-manager-open" ...>Manage tags</v-btn>`.
- Dialog (line 49): `<v-dialog v-model="tagDialog">` tag manager.

- [ ] **Step 1: Add `notes` ref + tag-search ref to script; drop `tagDialog`**

In `<script setup>`:
- Change the `const contactId = ref..., email = ref(''), nickname = ref(''), tagName = ref('')` line (line 9) to add a `notes` ref:
```ts
const contactId = ref<number>(), email = ref(''), nickname = ref(''), notes = ref(''), tagName = ref('')
```
- Remove `tagDialog` from the `selectedContacts`/`error`/`tagDialog` line (line 11) — it is no longer needed:
```ts
const selectedContacts = ref<number[]>([]), assignTagIds = ref<number[]>([]), error = ref(''), tagQuery = ref('')
```
- `edit(item)` (line 18) — set notes:
```ts
function edit(item: Contact) { contactId.value = item.id; email.value = item.email; nickname.value = item.nickname ?? ''; notes.value = item.notes ?? ''; contactTagIds.value = [...(item.tagIds ?? [])] }
```
- `reset()` (line 19) — clear notes:
```ts
function reset() { contactId.value = undefined; email.value = ''; nickname.value = ''; notes.value = ''; contactTagIds.value = [] }
```
- `saveContact` (lines 23-25) — send notes:
```ts
const saveContact = () => run(t('contacts.saveAction'), async () => { await invoke('email_contact_save', {
  id: contactId.value, email: email.value, nickname: nickname.value, notes: notes.value, tagIds: [...contactTagIds.value],
}); reset() })
```
- Add a computed for filtered tags (frontend-only filter). Add `computed` to the existing `vue` import on line 2 (it already imports `computed`). After `pendingDelete`, add:
```ts
const filteredTags = computed(() => {
  const q = tagQuery.value.trim().toLowerCase()
  return q ? store.tags.filter(tag => tag.name.toLowerCase().includes(q)) : store.tags
})
```

- [ ] **Step 2: Replace the right card (line 47) with form card + remove the dialog (line 49)**

The right column is currently a single `<v-card>`. Replace that whole `<v-card class="surface" ...>...</v-card>` (line 47) AND delete the `<v-dialog v-model="tagDialog">...</v-dialog>` (line 49) with a stacked two-card structure:

Replace line 47's card with:
```vue
    <div class="d-flex flex-column ga-4">
      <v-card class="surface" variant="flat"><v-card-title>{{ contactId ? t('contacts.editContact') : t('contacts.newContact') }}</v-card-title><v-card-text>
        <v-text-field v-model="email" data-testid="contact-email" :label="t('contacts.email')" />
        <v-text-field v-model="nickname" :label="t('contacts.name')" />
        <v-select v-model="contactTagIds" data-testid="contact-tags" :items="store.tags" item-title="name" item-value="id" multiple chips :label="t('contacts.assignTags')" />
        <v-textarea v-model="notes" data-testid="contact-notes" :label="t('contacts.notes')" rows="2" auto-grow />
      </v-card-text><v-card-actions><v-btn v-if="contactId" variant="text" @click="reset">{{ t('contacts.newContact') }}</v-btn><v-spacer /><v-btn data-testid="contact-save" color="primary" @click="saveContact">{{ t('common.save') }}</v-btn></v-card-actions></v-card>
      <v-card class="surface" variant="flat" data-testid="tag-manager-card"><v-card-title>{{ t('contacts.manageTags') }}</v-card-title><v-card-text>
        <v-text-field v-model="tagQuery" data-testid="tag-search" density="compact" :label="t('contacts.tagSearch')" />
        <div class="tag-manager-list">
          <div class="tag-manager-row" v-for="tag in filteredTags" :key="tag.id" data-testid="tag-manager-row">
            <span>{{ tag.name }}</span><v-btn variant="text" color="error" size="small" @click="deleteTag(tag.id)">{{ t('common.delete') }}</v-btn>
          </div>
        </div>
        <div class="inline-fields mt-4"><v-text-field v-model="tagName" :label="t('contacts.newTag')" /><v-btn @click="addTag">{{ t('common.add') }}</v-btn></div>
      </v-card-text></v-card>
    </div>
```

And delete the entire `<v-dialog v-model="tagDialog" max-width="520">...</v-dialog>` element (line 49) — the tag manager is now the card above.

- [ ] **Step 3: Run the frontend tests**

```bash
cd OfficialPlugins/plugin-email/ui-src && npm test
```
Expected: likely ONE failure — `ManagementViews.test.ts` asserts `data-testid="tag-manager-open"` (the removed dialog-open button) and that `tag-manager-dialog` is absent. That test adjustment is Task 5. Note the failure and proceed; do not revert.

- [ ] **Step 4: Commit**

```bash
git add OfficialPlugins/plugin-email/ui-src/src/components/AddressBookTab.vue
git commit -m "✨ feat(email): split contact form and tag manager into separate cards; add notes field"
```

---

### Task 5: Tests — fix tag-manager assertion + add tag-fold and tag-search tests; final verification

**Files:**
- Modify: `OfficialPlugins/plugin-email/ui-src/src/components/ManagementViews.test.ts`

**Context — the test to update (`ManagementViews.test.ts:49-55`):**
```ts
it('keeps bulk contact actions separate from tag management', () => {
  bridge.invoke.mockResolvedValue({ success: true, contacts: [], tags: [] })
  const wrapper = mount(AddressBookTab, options())
  expect(wrapper.get('[data-testid="contact-bulk-tags"]').element).toBeTruthy()
  expect(wrapper.get('[data-testid="tag-manager-open"]').element).toBeTruthy()
  expect(wrapper.find('[data-testid="tag-manager-dialog"]').exists()).toBe(false)
})
```
After the redesign: the tag manager is a card (`tag-manager-card`), not a dialog; `tag-manager-open` no longer exists. The bulk-tags row (`contact-bulk-tags`) is unchanged.

- [ ] **Step 1: Update the tag-management test for the new card**

In `ManagementViews.test.ts`, replace the `'keeps bulk contact actions separate from tag management'` test body:
```ts
it('keeps bulk contact actions separate from tag management', () => {
  bridge.invoke.mockResolvedValue({ success: true, contacts: [], tags: [] })
  const wrapper = mount(AddressBookTab, options())
  expect(wrapper.get('[data-testid="contact-bulk-tags"]').element).toBeTruthy()
  // tag manager is now a always-visible card, not a dialog opened by a button
  expect(wrapper.get('[data-testid="tag-manager-card"]').element).toBeTruthy()
  expect(wrapper.find('[data-testid="tag-manager-dialog"]').exists()).toBe(false)
  expect(wrapper.find('[data-testid="tag-manager-open"]').exists()).toBe(false)
})
```

- [ ] **Step 2: Add a tag-fold test (list shows +N when a contact has 3+ tags)**

Append a new test. It mounts `AddressBookTab` with a contact carrying 3 tagIds and asserts the `+N` chip renders (3 tags → 2 shown + `+1`):
```ts
it('folds contact tag pills beyond the first two', () => {
  bridge.invoke.mockResolvedValue({ success: true, contacts: [{ id: 1, email: 'a@example.com', tagIds: [10, 20, 30] }], tags: [{ id: 10, name: '客户' }, { id: 20, name: 'VIP' }, { id: 30, name: '内部' }] })
  const wrapper = mount(AddressBookTab, options())
  expect(wrapper.findAll('[data-testid="contact-row"]')).toHaveLength(1)
  expect(wrapper.get('[data-testid="contact-row"]').text()).toContain('+1')
})
```

- [ ] **Step 3: Add a tag-search test (filtering the tag-manager list)**

Append a test that types into `tag-search` and checks the filtered list:
```ts
it('filters the tag manager list by the search query', async () => {
  bridge.invoke.mockResolvedValue({ success: true, contacts: [], tags: [{ id: 1, name: '客户' }, { id: 2, name: 'VIP' }, { id: 3, name: '供应商' }] })
  const wrapper = mount(AddressBookTab, options())
  await wrapper.get('[data-testid="tag-search"] input').setValue('vi')
  const rows = wrapper.findAll('[data-testid="tag-manager-row"]')
  expect(rows).toHaveLength(1)
  expect(rows[0].text()).toContain('VIP')
})
```

- [ ] **Step 4: Run the full frontend suite**

```bash
cd OfficialPlugins/plugin-email/ui-src && npm test
```
Expected: PASS (all suites green, incl. the 3 updated/new contact tests).

- [ ] **Step 5: Build (TS typecheck)**

```bash
cd OfficialPlugins/plugin-email/ui-src && npm run build
```
Expected: build succeeds — confirms `notes` is typed through the store/form and no removed i18n key is referenced.

- [ ] **Step 6: Run the full backend suite once more**

```bash
./mvnw -pl OfficialPlugins/plugin-email test
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add OfficialPlugins/plugin-email/ui-src/src/components/ManagementViews.test.ts
git commit -m "✅ test(email): tag-manager card, tag folding, and tag search"
```

- [ ] **Step 8: Manual visual verification (optional but recommended)**

Rebuild + reinstall the plugin into the running host (auth disabled at :24056):
```bash
node toolchain/cli/bin/fengyu.mjs plugin build OfficialPlugins/plugin-email --skip-tests
curl -s -X POST http://127.0.0.1:24056/api/plugin-market/upload-native -H 'Content-Type: application/json' \
  -d '{"path":"/Users/phoebej/Develop/Java/FengYu/OfficialPlugins/plugin-email/dist-package/fan.summer.email-4.0.0.fyp"}'
```
Then in the browser open the Email Center → Contacts tab and confirm: C detail rows with avatars, folded tag pills, the notes field, and the searchable tag-manager card. Narrow the window to confirm the `.panel-grid` collapses to one column under 1000px.

- [ ] **Step 9: Clean up temporary mockup files**

Remove the throwaway mockups placed in the frontend public dir during design:
```bash
rm -f frontend/public/contact-style-compare.html frontend/public/contact-layout-compare.html frontend/public/contact-tag-overflow.html frontend/public/contact-final-layout.html frontend/public/contact-final-v2.html
git add frontend/public
git commit -m "🔥 chore: remove throwaway addressbook design mockups"
```
