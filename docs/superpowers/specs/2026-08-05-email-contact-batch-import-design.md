# Email Plugin — Contact Batch Import (with Tag Auto-Create)

**Date:** 2026-08-05
**Plugin:** `fan.summer.email` (OfficialPlugins/plugin-email)
**Status:** Design — approved, awaiting implementation

## Goal

Let users batch-upload contacts to the Email Center's address book from a file
(CSV or Excel `.xlsx`/`.xls`). The file may carry tags per row; **tag names that
don't yet exist are auto-created**, and existing contacts are handled gracefully
(non-destructive by default).

This extends the plugin's existing first-class contact + tag stack — it is **not**
a new subsystem. Contacts (`Contact(email, nickname, notes, tagIds)`) and tags
(`Tag(id, name UNIQUE)`) already have a complete vertical slice
(model → repository → service → RPC → UI). This feature adds a batch import path
on top of it.

## Non-goals

- No AI tool surface. Import is an interactive UI wizard, not an `aiTools` entry.
- No database schema migration. The existing V1–V5 contact/tag schema is reused
  unchanged; `SchemaMigrator.LATEST_VERSION` stays at 5.
- No vCard / `.vcf` support in this iteration (CSV + XLSX only). The parser
  interface is shaped so a vCard parser can be added later without touching the
  orchestrator.
- No partial-row import preview editing — the user reviews counts and errors but
  does not edit rows in the preview step.

## Decisions (defaults adopted — clarifying questions went unanswered)

| Question | Default chosen | Reasoning |
|---|---|---|
| Formats | **CSV + Excel (.xlsx/.xls)** | User explicitly confirmed both. Adds Apache POI (centrally versioned). |
| Duplicate handling | **Merge tags, additive** (also offer Skip, Overwrite) | Non-destructive & additive is the safe default for batch imports. |
| Tag encoding | **Single `tags` column, delimited** | Flexible; supports any number of tags per row. Header aliases (incl. Chinese) for Gmail/Outlook/native exports. |
| Workflow | **Dry-run preview → confirm → commit** | Matches the plugin's existing confirmation-first pattern (`prepareSingle`/`prepareBatch` → `confirm_send`) and the excel plugin's preview. Safer for batch ops. |
| Error handling | **Best-effort** — bad rows reported, import continues | A typo on row 8 should not abort 1000 good rows. Hard failures (unreadable file, DB error) roll back the whole transaction. |

## Architecture

### Two new UI-only RPC methods (NOT added to `aiTools`)

| Method | Effect | Returns |
|---|---|---|
| `email_contacts_import_preview` | **Reads.** Parses the file, computes what would happen against the DB, writes nothing. | `ImportPreview{ rowsTotal, rowsValid, createdContacts, mergedContacts, skippedContacts, createdTags:[names], errors:[{row,message}] }` |
| `email_contacts_import_commit` | **Writes.** Re-parses the same file + re-applies the same options, executes the import atomically in one `SqlSession`. | `ImportResult{ created, merged, skipped, tagsCreated, tagsAssigned, errors }` |

> The two record field-name sets differ intentionally — preview reports *contacts*
> (estimates that a separate `duplicateMode` could change), commit reports *final
> counts plus `tagsCreated`/`tagsAssigned`*. They are distinct shapes, not meant
> to match field-for-field.

**Why stateless preview→commit (re-parse on commit, no in-worker staging):** the
`FileRef` path is ephemeral on the worker; the cleanest contract is stateless —
both calls take the same `sourceFile` path param and `options`. The host's
`PluginProcessManager.resolveRefs` rewrites the `FileRef` → absolute path before
the worker sees it (exactly how `email_send_batch`'s `inputDirectory` already
works via `EmailRpcHandlers.path(...)`). No in-memory staging to leak or expire.

### New Java components (all in the plugin's existing packages)

```
service/ContactImporter.java         — orchestrator: parse → plan → commit (format-agnostic)
service/ContactFileParser.java       — interface: parse(Path) → (List<ParsedContact>, List<ParseError>) + close()
service/ContactCsvParser.java        — CSV/TXT impl (streamed, quoted-field aware, BOM-stripped UTF-8)
service/ContactExcelParser.java      — XLSX/XLS impl (Apache POI streaming)
service/ContactHeaderResolver.java   — maps raw header cells → logical columns (shared by both parsers)
model/ContactImport.java             — records: ParsedContact, ParseError, ImportPreview, ImportResult
```

**New RPC adapter methods** on `EmailRpcHandlers`:
`importContactsPreview(params)` and `importContactsCommit(params)`, both reusing
the existing `path(value, "sourceFile", "file")` helper so the `FileRef`→path
rewrite works.

**New repository method** on `AddressBookRepository`:
`ensureTagsByName(Set<String> names) → Map<String,Long>` — single `SELECT` for
all names, then batched `INSERT`s for the missing ones, all inside the caller's
session. Used by `ContactImporter` so tag auto-create stays atomic with the
contact writes. The existing single-tag `saveTag` is left untouched for the
single-tag UI flow.

**No DB schema changes.** The contact/tag tables already cover everything.

### pom.xml

One new dependency in `plugin-email/pom.xml`, version inherited from the parent
`<dependencyManagement>`:

```xml
<dependency><groupId>org.apache.poi</groupId><artifactId>poi-ooxml</artifactId></dependency>
```

`poi-ooxml` (5.5.1) transitively brings `poi`, `poi-ooxml-lite`, `commons-io`,
`xmlbeans`. The shade plugin already runs with `minimizeJar=false`, so nothing is
stripped. No `commons-csv` dependency — a small in-house CSV reader handles
RFC-4180-style quoting (contact CSVs are regular; a new dependency is not
warranted).

## Data flow (end to end)

```
[UI: file picker]
   fengyu.files.open({ extensions: ['csv','txt','xlsx','xls'] })
        │ postMessage → host SPA bridge
        ▼
[Host]  multipart upload → PluginFileGrantService → FileRef{ id, kind:"file" }
        │ passed by value in invoke() params
        ▼
[Host]  PluginProcessManager.resolveRefs() rewrites FileRef → absolute path string
        ▼
[Worker] email_contacts_import_preview { sourceFile: "/abs/path/contacts.csv", options{...} }
        │ ContactFileParser → List<ParsedContact> + List<ParseError>
        │ ContactImporter.plan() → diff against DB (existing emails, existing tag names)
        │ NO writes
        ▼
        returns ImportPreview { counts, createdTags:[names], errors:[{row,msg}] }

[UI: preview dialog]
   shows: "12 new contacts, 3 will merge, 2 new tags will be created, 1 error on row 8"
   user reviews → clicks "Confirm Import"
        ▼
[Worker] email_contacts_import_commit { sourceFile: <same path>, options{...} }
        │ re-parse → re-plan → execute in ONE SqlSession (atomic)
        │   1. ensureTagsByName(...) → name→id map (auto-create missing)
        │   2. upsert contacts by email (create / merge / skip / overwrite per option)
        │   3. assign tag IDs (union for merge; replace for overwrite)
        │ commit
        ▼
        returns ImportResult { created, merged, skipped, tagsCreated, tagsAssigned, errors }
[UI: reload contact list + success toast]
```

## File format contract

Single `sourceFile` param; format detected by **extension + content sniff**
(robust to mislabeled exports):

| Format | Detection | Parser |
|---|---|---|
| CSV / TXT | extension `.csv`/`.txt` OR first bytes not ZIP magic (`PK`) | `ContactCsvParser` — streamed `BufferedReader`, BOM-stripped UTF-8 |
| Excel (.xlsx/.xls) | extension `.xlsx`/`.xls` OR first bytes are `PK` ZIP magic | `ContactExcelParser` — POI `WorkbookFactory`, streaming reader |

Both parsers emit the same logical model, so the plan→commit logic is
format-agnostic:

```java
record ParsedContact(int row, String email, String nickname, String notes, List<String> tags) {}
record ParseError(int row, String message) {}
```

### Header matching (case-insensitive + aliases; shared by both formats)

Logical column accepted headers (case-insensitive):

| Logical column | Accepted headers | Required |
|---|---|---|
| email | `email`, `e-mail`, `邮箱`, `电子邮件` | **yes** |
| nickname | `name`, `nickname`, `姓名`, `昵称` | no |
| notes | `notes`, `note`, `备注`, `注释` | no |
| tags | `tags`, `tag`, `labels`, `标签`, `分组` | no |

- **Tag delimiters:** `,` `;` `|` or newline. e.g. `Marketing, VIP` or
  `Sales|Priority`. Each tag whitespace-trimmed; empty tags dropped. Comparison
  is case-insensitive (matches the existing `LOWER(name)` constraint), but the
  original casing is kept as the display name on create. A file with **no
  `tags` column at all** is a valid plain contact import — no tags are created
  or assigned; every row simply lands as an untagged contact (subject to the
  chosen duplicate mode).
- **Row rules:** blank rows after the header are skipped. A row with blank
  `email` is reported as an error, not an abort. Email validation reuses the
  `AddressBookService` contract (must contain `@`; trimmed + lowercased before
  compare, so `Jane@x.com` == `jane@x.com`). A malformed email is an error for
  that row; the import continues (best-effort).
- **Encoding:** BOM detected and stripped at file start; remainder UTF-8.
- **Excel specifics:** first row is the header row (detected — if the first row's
  cells match any known alias, it's a header). The **first worksheet** is read
  (`workbook.getSheetAt(0)`); if further sheets exist they are reported as an
  informational note ("Import used Sheet 1; ignored Sheet 2, Sheet 3"), not a
  failure and not silently used. Tag-cell delimiters are the same (`,;|` +
  in-cell newline).
- **Performance:** `.xlsx` parsed via POI's streaming API so a 10k-row workbook
  doesn't OOM. CSV is already streamed. Both parsers close the source on
  completion.

### `options` param (optional; identical on preview and commit, keeping them symmetric & stateless)

```json
{
  "duplicateMode": "merge" | "skip" | "overwrite",   // default "merge"
  "tagDelimiter":  "," | ";" | "|" | "auto"          // default "auto"
}
```

`tagDelimiter: "auto"` means split on any of `,;\|`/newline.

## Import semantics — tag auto-create + duplicate handling

### Tag resolution (computed at preview, applied at commit)

```
1. Collect all distinct tag names from each row's parsed tags
   (normalize: trim, drop empties; compare case-insensitively
    to match the existing LOWER(name) constraint)

2. Load all existing tags: SELECT id,name FROM FENGYU_PL_Email_Tag
   → existingByName (lowercase → tag)

3. For each distinct tag name:
     if existsByName(lowercase(name)): reuse its id
     else: mark "to-create" → createdTags[]

   → tagResolution = Map<name, {id, willCreate}>
```

### Atomic execution at commit (single `SqlSession`)

```
tx begin
  # Phase 1 — upsert tags FIRST so ids exist before assignments reference them
  tagIdByName = ensureTagsByName(distinctTagNames)   # SELECT then batch INSERT missing

  # Phase 2 — upsert contacts
  for each ParsedContact:
    existing = SELECT id,nickname,notes WHERE lower(email)=lower(email)
    switch (duplicateMode):
      case "skip"      & existing:  skipped++; continue
      case "merge"     & existing:
          nickname = file.nickname ?: existing.nickname   # file fills blanks only
          notes    = file.notes    ?: existing.notes
          UPDATE contact SET nickname,notes WHERE id
          merged++
      case "overwrite" & existing:
          UPDATE contact SET nickname=file.nickname, notes=file.notes WHERE id
          merged++                                   # counted as "merged" (touched existing)
      case (no existing):
          id = INSERT contact(email, nickname, notes)
          created++

    # Phase 3 — assignments (union for merge; replace for overwrite)
    tagIds = [tagIdByName[t] for t in contact.tags]
    if duplicateMode == "merge" & existing:
        tagIds = union(existing.tagIds, tagIds)      # additive, never removes
    if duplicateMode == "overwrite" & existing:
        DELETE assignments WHERE contact_id = id      # then bulk INSERT below
    INSERT INTO FENGYU_PL_Email_Contact_Tag(contact_id, tag_id) for each
tx commit   # single atomic unit — no partial writes
```

### Key semantics

1. **Tags idempotent by name.** Re-importing the same file is safe — tags are
   never duplicated (the existing `LOWER(name)` uniqueness check guarantees
   this). Auto-create is precise: only nonexistent distinct tag names are
   created.
2. **Merge mode is additive.** A merge never removes tags from a contact — it
   unions file tags ∪ existing tags. nickname/notes are filled only when the
   file cell is non-blank. This is the safe, non-destructive default.
3. **Overwrite mode replaces.** nickname/notes overwritten, tag assignment
   replaced wholesale by the file's (DELETE then bulk INSERT). Intentionally
   destructive.
4. **Skip mode preserves.** Existing contacts are left entirely untouched (no
   tag/nickname/notes mutation); counted as `skipped`. Only new emails are
   created.
5. **Race on `Tag.name`:** the existing `saveTag` already checks
   `findTagByName` inside its transaction. To keep the import atomic without
   N round-trips, the new `ensureTagsByName(Set<String>)` does one `SELECT`,
   then batched `INSERT`s for the missing ones, all inside the import session.
   Single-tag `saveTag` is untouched.
6. **Preview accuracy:** preview computes its plan by reading existing tags and
   existing emails in a read-only session. Between preview and commit, an
   externally-added contact/tag could change the picture; commit re-checks
   (re-plans) so commit counts are authoritative. The preview is a faithful
   estimate, not a guarantee.
7. **`@` validation** reuses `AddressBookService`'s contract; an email without
   `@` becomes a `ParseError` for that row; the import continues (best-effort,
   §file format).

### Error handling

- **Parse errors** (malformed row, unrecognized header, `@` validation fail):
  recorded in `errors[{row,message}]`, row skipped, import continues. Preview
  surfaces these before commit; commit re-reports them.
- **Hard failures** (unreadable file, DB error): the whole transaction rolls
  back — nothing is written. Returned as a `failure` envelope.
- **`success:false` vs `success:true` with errors:** parse/DB errors →
  `success:false`. Row-level errors but contacts still written → `success:true`
  with errors in the result (user sees which rows failed while still getting a
  successful import).

## UI + i18n

New **`ImportContactsDialog.vue`** (keeps `AddressBookTab.vue` focused; that file
already owns list + edit + tag-manager). Two-step flow matching preview→commit:

```
┌─ Import contacts ───────────────────────┐
│ Step 1 — pick file                       │
│  [Choose file…] contacts.csv  ⚠ .xlsx ok │
│  Duplicates: (•) Merge  ( ) Skip ( ) Overwrite │
│                          [Cancel] [Preview] │
└──────────────────────────────────────────┘
        │ Preview → invoke('email_contacts_import_preview', ...)
        ▼
┌─ Import contacts — preview ─────────────┐
│ ✅ Parsed 20 rows · 18 valid · 1 error   │
│ ┌────────────────────────────────────┐ │
│ │ 12 contacts will be created         │ │
│ │ 3 existing will merge (add tags)    │ │
│ │ 2 duplicates will be skipped        │ │
│ │ 4 tags will be created: Marketing…  │ │ ← auto-created tags surfaced here
│ └────────────────────────────────────┘ │
│ ⚠ Errors (1):                            │
│   Row 8: email "not-an-email" missing @ │
│                       [Back] [Confirm Import] │
└──────────────────────────────────────────┘
        │ Confirm → invoke('email_contacts_import_commit', ...)
        ▼
[toast: "Created 12, merged 3, created 4 tags"]
+ contacts store reloads to show new contacts/tags
```

- `AddressBookTab.vue` gains an "Import contacts" button that opens the dialog
  and, on success, calls `store.load()` to refresh the list.
- File picking uses the existing `fengyu.files.open({ extensions: [...] })` SDK
  call (same one `plugin-excel`'s `FyFilePicker` uses).
- **i18n:** new `contacts.import.*` key block in `ui-src/src/i18n/` (en + zh),
  matched to the existing locale mechanism. No hardcoded strings.

## Manifest

- **No new permissions.** `files.read` is already present and is what's needed
  to read the import file. (`files.write` is also present but unused by import —
  the import file is read-only.)
- **No `aiTools` entries.** Import is an interactive wizard, not an AI tool —
  the two new methods are UI-only RPC, like the existing `email_contact_save`,
  `email_tag_save`, etc.
- **`description` unchanged.** The address book is already covered by the
  existing copy.

## Testing

Matches the plugin's existing setup (JUnit 5 + devkit loopback server via
`PluginDevMain`; Vitest UI tests in `*.test.ts`).

1. **`ContactCsvParserTest`** — header aliases (`Email`/`E-mail`/`邮箱`), quoted
   fields, BOM strip, tag delimiters (`,;\|`), blank-row skip, no-`@` row →
   ParseError, UTF-8.
2. **`ContactExcelParserTest`** — build an in-memory `.xlsx` with POI's
   `SXSSFWorkbook`, parse, assert the same logical model. Multi-sheet →
   informational note.
3. **`ContactImporterTest`** (test H2 DB) — the plan→commit core:
   - Fresh DB: import creates contacts + auto-creates tags.
   - Re-import same file: no duplicate tags (idempotent), no duplicate contacts
     in merge/skip modes.
   - Merge mode: existing contact keeps notes, file tags ∪ existing tags.
   - Overwrite mode: notes replaced, assignments replaced.
   - Atomicity: simulate mid-commit failure → nothing written (rollback check).
4. **UI** (`ImportContactsDialog.test.ts`) — Vitest against rendered DOM, not
   real RPC: mock `invoke` + `fengyu.files.open`, assert preview step shows
   counts, and confirm calls commit with the right args.

## Docs

Contacts/tags are UI-only RPC today; per `AGENTS.md` the module docs under
`docs/en/` + `docs/zh/` (if a corresponding email-plugin module page exists)
will be synced via the `docs-updater` skill flow after implementation, as a
separate step.

## Out of scope / future

- vCard (`.vcf`) import — the `ContactFileParser` interface is shaped to accept
  a future vCard parser without touching the orchestrator.
- Row-level preview editing (the user reviews counts/errors but can't edit rows
  inline before commit).
- Import history / audit log table.
