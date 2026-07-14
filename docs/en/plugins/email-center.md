---
title: Official Plugin — Email Center
description: Multi-account confirmed sending, address books, manual collection, archives, and seven AI tools.
lang: en
---

# Official Plugin — Email Center

Email Center (`fan.summer.email`) is an official sandboxed `.fyp`. Its Vue/Vuetify/TipTap iframe and isolated Java Worker provide five workflows:

| Tab | Capabilities |
| --- | --- |
| Compose | To/CC/BCC, rich and plain text, attachments, safe review, explicit confirmation. |
| Batch Send | Tag or filename-suffix batches, exact confirmation rows, counters, retry preparation. |
| Address Book | Contact/tag CRUD, search, bulk tag assignment, recipient resolution. |
| Collect Mail | Manual IMAP collection by account, folder, and date range into an authorized directory. |
| Records & Accounts | Paginated archives, send status/retry, multiple SMTP/IMAP profiles. |

Collection is **manual only**; the plugin never polls in the background. It writes RFC-822 `.eml` files to an authorized directory and metadata to its independent database. Account/folder namespaces and folder-scoped UID deduplication prevent collisions.

The package permissions are exactly `database`, `network.email`, `files.read`, and `files.write`. SMTP/IMAP passwords are AES-GCM encrypted and never returned by account RPCs.

## AI confirmation

Seven tools cover accounts, contacts, single/batch preparation, send status, archive fetch, and archive query. Send preparation persists an immutable snapshot and returns `confirmation_required`; only `confirm_send` dispatches SMTP, while `reject_send` closes the operation. Replayed confirmation is idempotent. Archive queries return metadata and a bounded preview, never raw `.eml` content.

See [Plugin Database Standard](/en/plugins/database), [AI Tools](/en/plugins/ai-tools), and [File I/O](/en/plugins/file-io).
