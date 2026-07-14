# Email Center UI Redesign Design

**Date:** 2026-07-14

**Status:** Approved
**Target:** FengYu 4.0.0 Email Center `.fyp` plugin

## 1. Purpose

Redesign the Email Center UI because the first implementation is visually
disorganized, truncates navigation at the available iframe width, mixes unrelated
workflows, and renders Material Design icon glyphs as mojibake.

This design supersedes the UI layout and batch-send interaction described in
`2026-07-13-email-center-plugin-migration-design.md`. The Worker isolation,
official SDK requirement, database ownership, credential encryption, manual IMAP
collection, and confirmation protocol from that design remain unchanged.

The approved direction is a task-oriented workspace with a quiet professional
Material 3 visual style. Compose and batch sending are first-class tasks;
contacts, archive, send records, and account configuration are separate focused
workspaces.

## 2. Confirmed Product Decisions

- The plugin follows the FengYu host locale and supports Simplified Chinese and
  English without changing the information hierarchy.
- The plugin follows the host light and dark themes.
- Compose is the default workspace.
- Primary navigation is a compact left task rail rather than a wide row of tabs.
- The visual style uses low-saturation neutral surfaces. Theme purple is reserved
  for selection, focus, and primary actions.
- The body editor supports email-safe rich text pasted from Microsoft Word.
- Compose supports direct email addresses and contact-tag sending.
- Contact-tag sending from Compose sends a separate message to each resolved
  primary recipient so recipients do not see each other's addresses.
- Every send workflow supports primary recipients and CC recipients.
- Batch sending has one mode only: attachment filename tag matching.
- Batch sending does not expose tag-only sending or a failed-item retry mode.
- Each attachment business tag produces one message containing all resolved To
  and CC recipients for that tag.
- Common attachments are added to every tag message.
- Send records show failures and partial failures but do not expose a standalone
  retry workflow.

## 3. Information Architecture

The Email Center task rail contains:

1. **Compose** — direct address sending and contact-tag sending.
2. **Batch Send** — attachment filename tag matching.
3. **Contacts** — contact and tag administration.
4. **Archive** — manual IMAP collection and archive search.
5. **Send Records** — send tasks, per-message results, and failure details.
6. **Account Settings** — multi-account SMTP/IMAP configuration and testing.

Account Settings remains anchored at the bottom of the rail. The active workspace
occupies the center. A narrow summary area may appear on the right for counts,
validation, or task status, but it must not contain a second complex form.

At narrower widths the right summary disappears first. At mobile-like iframe
widths the task rail becomes a compact horizontal navigation row. Compose and
batch content always retain layout priority.

## 4. Visual System and Encoding

The plugin continues to use Vuetify 3 and the Material 3 baseline. It does not add
another component library.

- Avoid nested cards around every field. Use one main work surface and lightweight
  grouping borders.
- Use the host theme's neutral surface hierarchy and primary color.
- Use consistent 8–12 px control radii and the host spacing rhythm.
- Keep body text, labels, status, and actions visually distinct without relying on
  color alone.
- Use explicit SVG icons or a Vuetify SVG icon set. Do not emit private-use font
  glyphs into the iframe.
- The packaged HTML contains a valid document shell with
  `<meta charset="UTF-8">`.
- Plugin UI HTML, JavaScript, CSS, JSON, and server responses declare or guarantee
  UTF-8 decoding. The runtime static-resource response must not serve text assets
  with an ambiguous charset.
- Error rendering consumes structured message fields and never prints an encoded
  object or raw protocol payload.

These encoding rules address the reproduced failure where the plugin assets are
served as `text/html`, `text/javascript`, and `text/css` without a charset and MDI
private glyphs render as sequences such as `ó°…`.

## 5. Compose Workspace

Compose supports two recipient modes inside the same focused editor.

### 5.1 Direct addresses

The user may enter or paste primary and CC email addresses. Recognized contacts
render as named chips; arbitrary valid addresses remain valid address chips.
Duplicate addresses are removed. If an address exists in both To and CC, To takes
precedence.

### 5.2 Contact-tag sending

The user selects one or more contact tags. The plugin resolves the unique contacts
from those tags and creates one message per primary recipient. Common CC
recipients are copied to every generated message. The preview displays tag names,
unique primary recipient count, CC count per message, subject, and attachments.

The UI makes the privacy behavior explicit: primary recipients receive separate
messages and cannot see other primary recipient addresses.

### 5.3 Common compose controls

Both modes provide:

- Sending-account selection.
- To and CC fields.
- Subject.
- Rich body editor.
- Attachments.
- Draft/autosave state.
- Preflight validation and confirmation.

## 6. Rich Text and Word Paste

The editor uses a structured rich-text model rather than an unrestricted HTML
textarea. Word paste is normalized through an email-safe allowlist.

The supported paste fidelity includes:

- Headings and paragraphs.
- Bold, italic, and underline.
- Font size and text color within safe limits.
- Alignment.
- Ordered and unordered lists.
- Links.
- Tables, rows, headers, cells, and simple borders.

Word-specific classes, metadata, XML, scripts, event attributes, unsupported CSS,
positioning, and unsafe URLs are removed. Unsupported styling is cleaned without
silently deleting the text content. The UI reports that Word formatting was
normalized.

The send preview and final SMTP body use the same sanitized HTML. A plain-text
alternative is derived from the structured document. Exact Word page layout and
embedded clipboard images are out of scope; images continue to use the normal
attachment workflow.

## 7. Batch Send Workspace

Batch Send implements filename-tag matching only.

### 7.1 Filename parsing

For each regular file, the business tag is the text between the final underscore
and the final extension separator:

- `Quarterly_Report_East.pdf` produces `East`.
- `Contract_2026_VIP.docx` produces `VIP`.

A name without both delimiters, an empty tag, an unreadable file, or an unsafe
path is ignored and shown in the plan. Matching uses the repository's normalized
tag equality rules while preserving the display value.

Files with the same parsed tag form one tag-attachment group.

### 7.2 Contact intersection

The user selects one or more sending-group tags and one or more CC-group tags.
For each attachment tag `T`:

```text
To(T) = contacts tagged T ∩ contacts in any selected sending group
CC(T) = contacts tagged T ∩ contacts in any selected CC group
```

Addresses are deduplicated within each set. An address present in both sets is
kept only in To.

### 7.3 Message generation

Each attachment tag `T` produces exactly one message:

- To: all contacts in `To(T)`.
- CC: all contacts in `CC(T)` after To precedence.
- Tag attachments: every selected-directory file parsed as `T`.
- Common attachments: every common attachment selected for the batch task.
- Subject and rich-text body: the task-level values.

Therefore the number of planned messages is the number of attachment tags with a
non-empty To set, not the number of contacts.

The UI displays a per-tag preview with the complete To and CC lists, tag
attachments, common attachments, and ignored files. A tag with no primary
recipient is invalid and is not sent. Invalid tags do not prevent valid tag
messages from being prepared, but the confirmation summary must list all omitted
tags and the reason.

## 8. Other Workspaces

### 8.1 Contacts

Contacts use a list/editor layout. The list supports search, tag filtering,
multi-selection, bulk tag assignment, and deletion. Contact and tag management
are visually separated so tag CRUD does not crowd the contact form.

### 8.2 Archive

Archive places manual collection controls above archive search. Account, folder,
date range, output directory, progress, new, duplicate, and failure counts remain
visible during a run. Search results are paginated and open a focused message
detail view.

### 8.3 Send Records

Send Records combines task-level status with per-message details. It supports
search and status/account filters and clearly distinguishes pending, succeeded,
partial, failed, rejected, and expired states. Failures provide safe actionable
details but no standalone retry workflow.

### 8.4 Account Settings

Account Settings uses an account list beside a focused form. SMTP and IMAP
settings have separate groups. Password remains write-only: a blank edit keeps
the stored credential. SMTP testing and saving are separate actions. Destructive
account deletion requires confirmation and obeys the existing open-send guard.

## 9. Data Flow and Confirmation

All UI communication uses `@fengyu/plugin-sdk`. Components do not call host HTTP
endpoints or `postMessage` directly.

1. The UI receives locale, theme, and capabilities from the official SDK.
2. Focused Pinia stores maintain compose, batch, contacts, archive, records, and
   accounts state.
3. File and directory selections use SDK-issued `FileRef` grants.
4. The Worker validates and resolves all addresses, tags, attachment grants, and
   sanitized rich text.
5. A send preparation creates an immutable pending snapshot.
6. The UI renders the safe snapshot and requires explicit confirmation.
7. Confirmation atomically claims the pending send before SMTP dispatch.
8. Final and per-message results update Send Records.

The immutable snapshot includes account, mode, subject, sanitized HTML and plain
text, complete To/CC sets per message, tag attachments, common attachments,
ignored files/tags, and expiry. Repeated or concurrent confirmation cannot send a
snapshot twice.

## 10. Error Handling

- Show validation next to the affected field and keep a page-level summary only
  for cross-field failures.
- Reject invalid addresses, CR/LF header injection, unsafe links, inaccessible
  file grants, and unreadable attachments before confirmation.
- Show filename parse failures and unmatched tags in the batch plan.
- Prevent a tag message with no primary recipients while retaining other valid
  tag messages.
- Preserve the user's draft when Worker or network calls fail.
- Keep secrets, raw protocol output, database details, and encrypted credentials
  out of UI errors.
- Represent loading, empty, success, partial, and failure states with text and
  structure, not color alone.

## 11. Testing

### 11.1 Rich text

- Word HTML fixtures retain allowed headings, emphasis, colors, alignment, lists,
  links, and tables.
- Word metadata, unsafe attributes, scripts, positioning, and unsafe URLs are
  removed without losing text.
- Preview HTML equals the sanitized HTML handed to the Worker.
- Plain-text alternatives remain readable.

### 11.2 Recipient and batch planning

- Direct To/CC validation and deduplication.
- Contact-tag Compose creates one message per primary recipient.
- Final-underscore filename parsing, including multiple underscores and malformed
  names.
- Attachment grouping by parsed tag.
- To and CC intersection with selected group tags.
- To precedence when an address appears in both sets.
- One message per valid attachment tag.
- Tag attachments remain tag-scoped; common attachments appear in every message.
- Empty-recipient tags and ignored files appear in the confirmation summary.

### 11.3 UI and integration

- Chinese and English at supported widths without clipped navigation.
- Light and dark themes use the host environment.
- No private-font mojibake appears; HTML and assets decode as UTF-8.
- Keyboard navigation, focus order, labels, contrast, and status text meet the
  existing accessibility baseline.
- Responsive tests cover summary removal and horizontal navigation fallback.
- Worker round trips cover direct Compose, tag Compose, batch preparation,
  confirmation, partial failure, records, archive, and accounts.
- Static tests continue to forbid direct host bridges and SDK duplication.

## 12. Out of Scope

- Pixel-perfect preservation of Word page layout.
- Clipboard-embedded Word images.
- Scheduled IMAP collection.
- A standalone failed-recipient retry mode.
- A tag-only mode in the Batch Send workspace.
- A second UI framework or private icon font.

## 13. Definition of Done

- The approved task-rail design replaces the five crowded top tabs.
- Compose and Batch Send implement the recipient and attachment behavior in this
  document.
- Word paste is safe and preserves the approved formatting subset.
- Every visible string follows host locale and every surface follows host theme.
- Mojibake is absent in the packaged iframe under the real runtime asset server.
- All unit, integration, UI, packaging, and runtime smoke tests pass.
- The final `.fyp` is inspected in the FengYu host at narrow and wide viewport
  widths before release.
