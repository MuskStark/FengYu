---
title: Official Plugin — Email Center
description: Task-focused confirmed sending, filename-tag batches, contacts, manual collection, archives, and seven AI tools.
lang: en
---

# Official Plugin — Email Center

Email Center (`fan.summer.email`) is an official sandboxed `.fyp`. Its bilingual Vue/Vuetify iframe follows the host locale and light/dark theme. A task rail opens six focused workspaces:

| Workspace | Capabilities |
| --- | --- |
| Compose | Send to typed To/CC addresses, or select contact tags to create one private message per primary recipient. |
| Batch Send | Parse attachment filename tags, resolve To/CC group intersections, preview, and confirm one message per attachment tag. |
| Contacts | Contact/tag CRUD, search, filters, bulk tag assignment, and separate tag management. |
| Mail Archive | Manually collect IMAP mail into an authorized directory and browse paginated archive metadata. |
| Send Records | Search structured confirmation tasks and per-message send results. |
| Account Settings | Manage multiple SMTP/IMAP profiles with write-only passwords and separate SMTP test/save actions. |

## Rich text and Word paste

The Compose and Batch editors preserve an email-safe subset when content is copied from Word: headings, font size and color, bold/italic/underline, alignment, lists, links, and tables. Word-private markup, scripts, unsafe URLs/CSS, and embedded Word images are removed. Both the iframe and Worker sanitize the HTML, and the Worker derives a plain-text alternative when needed.

## Filename-tag batch rules

The attachment tag is the text after the final underscore and before the final extension: `report_East.pdf` resolves to `East`.

For every attachment tag `T`:

- To(`T`) = contacts tagged `T` ∩ selected sending-group tags.
- CC(`T`) = contacts tagged `T` ∩ selected CC-group tags.
- If an address is in both sets, To takes precedence and it is removed from CC.
- All files tagged `T` are attached to that tag's single message.
- Common attachments are appended to every generated message.
- Tags without a primary recipient are shown as skipped; malformed filenames are shown as ignored.

All sending is confirmation-first. Preparation persists an immutable snapshot and sends nothing; only `confirm_send` dispatches SMTP. There is no failed-item retry workflow. Collection is also manual only and never polls in the background.

The package permissions are exactly `database`, `network.email`, `files.read`, and `files.write`. SMTP/IMAP passwords are AES-GCM encrypted and never returned by account RPCs.

See [Plugin Database Standard](/en/plugins/database), [AI Tools](/en/plugins/ai-tools), and [File I/O](/en/plugins/file-io).
