# Email Center UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the crowded, mojibake-prone Email Center UI with the approved task-oriented bilingual workspace, email-safe Word rich text, direct/tag Compose, and one-message-per-attachment-tag batch sending with To/CC intersections and common attachments.

**Architecture:** Keep the isolated `.fyp` Worker and official SDK boundary. Fix UTF-8 asset delivery in the host, then move recipient and attachment planning into pure Worker services whose immutable result feeds both preview and confirmation. Rebuild the iframe as focused Vue/Vuetify workspaces backed by small Pinia stores, an SVG icon set, shared i18n, and one reusable rich-text editor.

**Tech Stack:** Java 21, Spring Boot MVC, FengYu Java/TypeScript Worker SDKs, MyBatis 3.5, Jsoup, Vue 3.5, Vuetify 3, Pinia 3, vue-i18n 10, TipTap 2, DOMPurify, Vitest, JUnit 5, GreenMail.

---

## File Structure

### Host asset delivery

- `FengYu/src/main/java/fan/summer/fengyu/web/controller/PluginRuntimeController.java` — UTF-8 media types for plugin text assets.
- `FengYu/src/test/java/fan/summer/fengyu/web/controller/PluginRuntimeControllerTest.java` — exact charset contract.

### Worker planning and sanitization

- `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/EmailHtmlSanitizer.java` — server-side email HTML allowlist and plain-text derivation.
- `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/BatchPlanner.java` — filename tag parsing, file grouping, To/CC normalization, common attachments, and one-message-per-tag planning.
- `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/repository/AddressBookRepository.java` — attachment-tag/group-tag intersection lookup.
- `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/PendingSendService.java` — Compose tag expansion, batch snapshots, detailed confirmation summaries, and removal of retry creation.
- `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/rpc/EmailRpcHandlers.java` — rich-text sanitization, batch preview, revised Compose/batch parameters, and no retry RPC.
- `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/EmailWorkerMain.java` — register the preview RPC and remove retry registration.
- `OfficialPlugins/packages/email/manifest.json` — revised AI tool schemas and descriptions.

### Iframe foundation

- `OfficialPlugins/plugin-email/ui-src/index.html` — complete UTF-8 HTML shell.
- `OfficialPlugins/plugin-email/ui-src/src/i18n/index.ts` — vue-i18n instance synchronized with SDK locale.
- `OfficialPlugins/plugin-email/ui-src/src/i18n/en.ts` and `zh-CN.ts` — complete English and Chinese strings.
- `OfficialPlugins/plugin-email/ui-src/src/components/EmailIcon.vue` — SVG icon renderer; no font glyphs.
- `OfficialPlugins/plugin-email/ui-src/src/components/TaskRail.vue` — responsive task navigation.
- `OfficialPlugins/plugin-email/ui-src/src/components/RichTextEditor.vue` — TipTap toolbar and Word paste normalization.
- `OfficialPlugins/plugin-email/ui-src/src/components/ConfirmationDialog.vue` — reusable immutable confirmation review.
- `OfficialPlugins/plugin-email/ui-src/src/richText.ts` — client allowlist, Word cleanup, and text derivation.
- `OfficialPlugins/plugin-email/ui-src/src/stores/navigation.ts` — selected workspace.
- `OfficialPlugins/plugin-email/ui-src/src/stores/compose.ts` — direct/tag Compose state and confirmation state.
- `OfficialPlugins/plugin-email/ui-src/src/stores/batch.ts` — directory, group tags, common attachments, preview, and execution state.
- Existing components are rewritten as focused workspaces; `RecordsAccountsTab.vue` is split into `SendRecordsView.vue` and `AccountSettingsView.vue`.

---

### Task 1: Guarantee UTF-8 plugin assets and remove ambiguous HTML

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/web/controller/PluginRuntimeController.java`
- Create: `FengYu/src/test/java/fan/summer/fengyu/web/controller/PluginRuntimeControllerTest.java`
- Modify: `OfficialPlugins/plugin-email/ui-src/index.html`

- [ ] **Step 1: Write the failing media-type contract test**

```java
package fan.summer.fengyu.web.controller;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginRuntimeControllerTest {
    @Test void textAssetsDeclareUtf8() {
        assertEquals(StandardCharsets.UTF_8,
            PluginRuntimeController.contentType("index.html").getCharset());
        assertEquals(StandardCharsets.UTF_8,
            PluginRuntimeController.contentType("app.js").getCharset());
        assertEquals(StandardCharsets.UTF_8,
            PluginRuntimeController.contentType("app.css").getCharset());
        assertEquals(StandardCharsets.UTF_8,
            PluginRuntimeController.contentType("messages.json").getCharset());
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn -pl FengYu -Dtest=PluginRuntimeControllerTest test`

Expected: compilation fails because `contentType` is private, or assertions fail because the current types have no charset.

- [ ] **Step 3: Make text media types explicitly UTF-8**

Change `contentType` to package-private and use one helper:

```java
static MediaType contentType(String name) {
    if (name.endsWith(".html")) return utf8("text", "html");
    if (name.endsWith(".js") || name.endsWith(".mjs")) return utf8("text", "javascript");
    if (name.endsWith(".css")) return utf8("text", "css");
    if (name.endsWith(".json")) return utf8("application", "json");
    if (name.endsWith(".svg")) return MediaType.parseMediaType("image/svg+xml");
    return MediaType.APPLICATION_OCTET_STREAM;
}

private static MediaType utf8(String type, String subtype) {
    return new MediaType(type, subtype, StandardCharsets.UTF_8);
}
```

Replace the email UI HTML with:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Email Center</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

- [ ] **Step 4: Verify host test and packaged headers**

Run: `mvn -pl FengYu -Dtest=PluginRuntimeControllerTest test`

Expected: 1 test passes.

After a package build, run:

```bash
curl -sSI http://127.0.0.1:24056/plugin-runtime/fan.summer.email/ui/index.html | rg -i 'content-type: text/html;charset=UTF-8'
```

Expected: one matching header line.

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/web/controller/PluginRuntimeController.java \
  FengYu/src/test/java/fan/summer/fengyu/web/controller/PluginRuntimeControllerTest.java \
  OfficialPlugins/plugin-email/ui-src/index.html
git commit -m "🐛 fix(plugin): serve iframe text assets as UTF-8"
```

### Task 2: Add bilingual task-shell and SVG icon foundation

**Files:**
- Modify: `OfficialPlugins/plugin-email/ui-src/package.json`
- Modify: `OfficialPlugins/plugin-email/ui-src/package-lock.json`
- Modify: `OfficialPlugins/plugin-email/ui-src/src/main.ts`
- Modify: `OfficialPlugins/plugin-email/ui-src/src/App.vue`
- Create: `OfficialPlugins/plugin-email/ui-src/src/i18n/index.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/i18n/en.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/i18n/zh-CN.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/components/EmailIcon.vue`
- Create: `OfficialPlugins/plugin-email/ui-src/src/components/TaskRail.vue`
- Create: `OfficialPlugins/plugin-email/ui-src/src/stores/navigation.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/stores/navigation.test.ts`

- [ ] **Step 1: Write failing navigation and locale tests**

```ts
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useNavigationStore } from './navigation'
import { localeFor } from '../i18n'

beforeEach(() => setActivePinia(createPinia()))

describe('Email Center shell', () => {
  it('opens Compose and exposes six focused workspaces', () => {
    const store = useNavigationStore()
    expect(store.active).toBe('compose')
    expect(store.items.map(item => item.id)).toEqual([
      'compose', 'batch', 'contacts', 'archive', 'records', 'accounts',
    ])
  })

  it('normalizes host locale to supported messages', () => {
    expect(localeFor('zh-CN')).toBe('zh-CN')
    expect(localeFor('zh-TW')).toBe('zh-CN')
    expect(localeFor('en-US')).toBe('en')
  })
})
```

- [ ] **Step 2: Run the test and verify RED**

Run: `npm --prefix OfficialPlugins/plugin-email/ui-src test -- navigation.test.ts`

Expected: module resolution fails because `navigation.ts` and `i18n/index.ts` do not exist.

- [ ] **Step 3: Add dependencies and focused shell types**

Run:

```bash
npm --prefix OfficialPlugins/plugin-email/ui-src uninstall @mdi/font
npm --prefix OfficialPlugins/plugin-email/ui-src install @mdi/js@^7.4.47 vue-i18n@^10.0.0
```

Create the store with an explicit union:

```ts
export type WorkspaceId = 'compose' | 'batch' | 'contacts' | 'archive' | 'records' | 'accounts'
export interface WorkspaceItem { id: WorkspaceId; labelKey: string; icon: string; bottom?: boolean }

export const useNavigationStore = defineStore('email-navigation', () => {
  const active = ref<WorkspaceId>('compose')
  const items: WorkspaceItem[] = [
    { id: 'compose', labelKey: 'nav.compose', icon: mdiEmailEditOutline },
    { id: 'batch', labelKey: 'nav.batch', icon: mdiEmailMultipleOutline },
    { id: 'contacts', labelKey: 'nav.contacts', icon: mdiAccountMultipleOutline },
    { id: 'archive', labelKey: 'nav.archive', icon: mdiArchiveArrowDownOutline },
    { id: 'records', labelKey: 'nav.records', icon: mdiHistory },
    { id: 'accounts', labelKey: 'nav.accounts', icon: mdiCogOutline, bottom: true },
  ]
  return { active, items }
})
```

`EmailIcon.vue` renders the imported path, never text glyphs:

```vue
<script setup lang="ts">
defineProps<{ path: string; size?: number }>()
</script>
<template>
  <svg :width="size ?? 20" :height="size ?? 20" viewBox="0 0 24 24" aria-hidden="true">
    <path :d="path" fill="currentColor" />
  </svg>
</template>
```

- [ ] **Step 4: Add complete locale wiring and replace top tabs**

`i18n/index.ts` must export `i18n`, `localeFor`, and `syncLocale`:

```ts
export function localeFor(value: string): 'en' | 'zh-CN' {
  return value.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en'
}

export const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en, 'zh-CN': zhCN },
})

export function syncLocale(value: string): void {
  i18n.global.locale.value = localeFor(value)
}
```

Start the locale files with the same complete key shape; later component tasks add
new keys to both files in the same commit:

```ts
// en.ts
export default {
  nav: { compose: 'Compose', batch: 'Batch Send', contacts: 'Contacts', archive: 'Archive', records: 'Send Records', accounts: 'Account Settings' },
  common: { save: 'Save', cancel: 'Cancel', delete: 'Delete', search: 'Search', add: 'Add', close: 'Close', loading: 'Loading…', none: 'None' },
  compose: { title: 'Compose email', direct: 'Direct addresses', contactTags: 'Contact tags', from: 'From account', to: 'To', cc: 'CC', subject: 'Subject', bodyPlaceholder: 'Write your message…', attach: 'Add attachment', review: 'Review and send', separateMessages: '{count} separate messages', wordNormalized: 'Word formatting was cleaned and preserved safely.' },
  batch: { title: 'Send by attachment tag', directory: 'Attachment directory', recipientGroups: 'Sending group tags', ccGroups: 'CC group tags', commonAttachments: 'Common attachments', preview: 'Message preview', ignoredFiles: 'Ignored files', skippedTags: 'Skipped tags', review: 'Generate and review {count} messages' },
  contacts: { title: 'Contacts', newContact: 'New contact', manageTags: 'Manage tags', assignTags: 'Assign tags', name: 'Name', email: 'Email', notes: 'Notes' },
  archive: { title: 'Mail archive', collect: 'Collect now', account: 'IMAP account', folder: 'Folder', range: 'Date range', output: 'Archive directory', processed: 'Processed', new: 'New', duplicates: 'Duplicates', failed: 'Failed' },
  records: { title: 'Send records', status: 'Status', progress: 'Progress', mode: 'Mode', sentAt: 'Sent at', partial: 'Partially failed' },
  accounts: { title: 'Account settings', newAccount: 'Add account', passwordHelp: 'Leave blank to keep the saved password', smtp: 'SMTP server', imap: 'IMAP server', test: 'Test SMTP', defaultAccount: 'Default sending account' },
  confirmation: { title: 'Confirm send', approve: 'Confirm send', reject: 'Reject', expires: 'Expires {time}' },
  errors: { unknown: 'Email operation failed. Try again.', actionFailed: '{action} failed: {detail}' },
}

// zh-CN.ts uses the identical keys
export default {
  nav: { compose: '写邮件', batch: '批量发送', contacts: '联系人', archive: '邮件归档', records: '发送记录', accounts: '账户设置' },
  common: { save: '保存', cancel: '取消', delete: '删除', search: '搜索', add: '添加', close: '关闭', loading: '加载中…', none: '无' },
  compose: { title: '写一封邮件', direct: '直接输入地址', contactTags: '按联系人标签群发', from: '发件账户', to: '收件人', cc: '抄送', subject: '主题', bodyPlaceholder: '请输入邮件正文…', attach: '添加附件', review: '检查并发送', separateMessages: '共 {count} 封独立邮件', wordNormalized: '已安全清理并保留 Word 格式。' },
  batch: { title: '按附件标签批量发送', directory: '附件目录', recipientGroups: '发送群组标签', ccGroups: '抄送群组标签', commonAttachments: '公共附件', preview: '邮件预览', ignoredFiles: '忽略文件', skippedTags: '跳过标签', review: '生成并检查 {count} 封邮件' },
  contacts: { title: '联系人', newContact: '新建联系人', manageTags: '管理标签', assignTags: '批量添加标签', name: '姓名', email: '邮箱', notes: '备注' },
  archive: { title: '邮件归档', collect: '开始收取', account: 'IMAP 账户', folder: '文件夹', range: '时间范围', output: '归档目录', processed: '已处理', new: '新增', duplicates: '重复', failed: '失败' },
  records: { title: '发送记录', status: '状态', progress: '进度', mode: '模式', sentAt: '发送时间', partial: '部分失败' },
  accounts: { title: '账户设置', newAccount: '添加账户', passwordHelp: '留空则保持已保存密码', smtp: 'SMTP 发件服务器', imap: 'IMAP 收件服务器', test: '测试 SMTP', defaultAccount: '默认发件账户' },
  confirmation: { title: '确认发送', approve: '确认发送', reject: '拒绝', expires: '有效期至 {time}' },
  errors: { unknown: '邮件操作失败，请重试。', actionFailed: '{action}失败：{detail}' },
}
```

In `main.ts`, install `i18n` and watch both theme and locale:

```ts
app.use(createPinia()).use(i18n).use(vuetify).mount('#app')
watchEffect(() => {
  vuetify.theme.global.name.value = useEnvironment().theme
  syncLocale(useEnvironment().locale)
})
```

Replace `v-tabs`/`v-window` in `App.vue` with `TaskRail` and a keyed workspace
component selected from `navigation.active`.

- [ ] **Step 5: Verify shell tests and static icon enforcement**

Extend `officialSdk.test.ts`:

```ts
expect(allSource).not.toMatch(/mdi-[a-z-]+/)
expect(allSource).not.toContain('@mdi/font')
expect(allSource).not.toMatch(/[\uF000-\uF8FF]/)
```

Run:

```bash
npm --prefix OfficialPlugins/plugin-email/ui-src run typecheck
npm --prefix OfficialPlugins/plugin-email/ui-src test -- navigation.test.ts officialSdk.test.ts
```

Expected: typecheck succeeds and both test files pass.

- [ ] **Step 6: Commit**

```bash
git add OfficialPlugins/plugin-email/ui-src
git commit -m "✨ feat(email): add bilingual task workspace shell"
```

### Task 3: Add email-safe rich text and Word paste normalization

**Files:**
- Modify: `pom.xml`
- Modify: `OfficialPlugins/plugin-email/pom.xml`
- Create: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/EmailHtmlSanitizer.java`
- Create: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/service/EmailHtmlSanitizerTest.java`
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/rpc/EmailRpcHandlers.java`
- Modify: `OfficialPlugins/plugin-email/ui-src/package.json`
- Modify: `OfficialPlugins/plugin-email/ui-src/package-lock.json`
- Create: `OfficialPlugins/plugin-email/ui-src/src/richText.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/richText.test.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/extensions/FontSize.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/components/RichTextEditor.vue`

- [ ] **Step 1: Write failing Java and TypeScript sanitizer tests**

```java
@Test void keepsEmailSafeWordFormattingAndDropsActiveContent() {
    String input = "<p class='MsoNormal' style='text-align:center;color:#336699;position:absolute'>"
        + "<b>Quarterly</b><script>alert(1)</script></p>"
        + "<table><tr><th>Milestone</th></tr><tr><td>Done</td></tr></table>";
    String clean = new EmailHtmlSanitizer().sanitize(input);
    assertTrue(clean.contains("<b>Quarterly</b>"));
    assertTrue(clean.contains("<table>"));
    assertTrue(clean.contains("text-align: center"));
    assertFalse(clean.contains("script"));
    assertFalse(clean.contains("MsoNormal"));
    assertFalse(clean.contains("position"));
}
```

```ts
it('normalizes Word HTML without losing tables or text', () => {
  const clean = sanitizeEmailHtml(`<p class="MsoNormal"><b>Hello</b><o:p>&nbsp;</o:p></p>
    <table><tr><td>Q1</td></tr></table><img src="javascript:alert(1)">`)
  expect(clean).toContain('<b>Hello</b>')
  expect(clean).toContain('<table>')
  expect(clean).not.toContain('MsoNormal')
  expect(clean).not.toContain('javascript:')
})
```

- [ ] **Step 2: Run both tests and verify RED**

Run:

```bash
mvn -pl OfficialPlugins/plugin-email -am -Dtest=EmailHtmlSanitizerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
npm --prefix OfficialPlugins/plugin-email/ui-src test -- richText.test.ts
```

Expected: Java compilation and TypeScript module resolution fail because the sanitizers do not exist.

- [ ] **Step 3: Add Jsoup and the server allowlist**

Add `org.jsoup:jsoup` under dependency management and to `plugin-email`. Implement
an explicit safelist:

```java
private static final Safelist ALLOWED = new Safelist()
    .addTags("p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b", "em", "i", "u",
        "ol", "ul", "li", "a", "table", "thead", "tbody", "tr", "th", "td", "blockquote", "span")
    .addAttributes("a", "href", "title")
    .addProtocols("a", "href", "http", "https", "mailto")
    .addAttributes(":all", "style");

public String sanitize(String html) {
    Document dirty = Jsoup.parseBodyFragment(html == null ? "" : html);
    dirty.select("[class]").removeAttr("class");
    dirty.select("[style]").forEach(element -> element.attr("style", safeStyle(element.attr("style"))));
    return Jsoup.clean(dirty.body().html(), "", ALLOWED, new Document.OutputSettings().prettyPrint(false));
}
```

Implement style filtering without accepting unknown CSS:

```java
private static final Set<String> SAFE_STYLE_NAMES = Set.of(
    "text-align", "color", "background-color", "font-size", "font-weight",
    "font-style", "text-decoration", "border", "border-color", "border-style", "border-width");

private static String safeStyle(String input) {
    if (input == null || input.isBlank()) return "";
    List<String> safe = new ArrayList<>();
    for (String declaration : input.split(";")) {
        int colon = declaration.indexOf(':');
        if (colon < 1) continue;
        String name = declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String value = declaration.substring(colon + 1).trim();
        String folded = value.toLowerCase(Locale.ROOT);
        if (!SAFE_STYLE_NAMES.contains(name) || value.length() > 80
                || folded.contains("url(") || folded.contains("expression")
                || folded.contains("javascript:")) continue;
        safe.add(name + ": " + value);
    }
    return String.join("; ", safe);
}
```

In `EmailRpcHandlers.message`, sanitize `htmlText` and derive plain text when the
caller omits it:

```java
String html = htmlSanitizer.sanitize(string(params, "htmlText"));
String plain = string(params, "plainText");
if (plain == null || plain.isBlank()) plain = htmlSanitizer.toPlainText(html);
return new EmailMessageRequest(accountId, to, cc, bcc, subject, plain, html, attachments);
```

- [ ] **Step 4: Add DOMPurify and the complete TipTap extension set**

Run:

```bash
npm --prefix OfficialPlugins/plugin-email/ui-src install dompurify@^3.2.0 \
  @tiptap/extension-color@^2.27.2 @tiptap/extension-text-style@^2.27.2 \
  @tiptap/extension-text-align@^2.27.2 @tiptap/extension-underline@^2.27.2 \
  @tiptap/extension-link@^2.27.2 @tiptap/extension-table@^2.27.2 \
  @tiptap/extension-table-row@^2.27.2 @tiptap/extension-table-header@^2.27.2 \
  @tiptap/extension-table-cell@^2.27.2
```

`richText.ts` uses the same tag/attribute/style allowlist as the Worker:

```ts
const ALLOWED_TAGS = ['p','br','h1','h2','h3','h4','h5','h6','strong','b','em','i','u',
  'ol','ul','li','a','table','thead','tbody','tr','th','td','blockquote','span']
const SAFE_STYLES = new Set(['text-align','color','background-color','font-size','font-weight',
  'font-style','text-decoration','border','border-color','border-style','border-width'])

export function sanitizeEmailHtml(input: string): string {
  const clean = DOMPurify.sanitize(input ?? '', {
    ALLOWED_TAGS, ALLOWED_ATTR: ['href', 'title', 'style'],
    ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto):|[^a-z]|[a-z+.-]+(?:[^a-z+.-:]|$))/i,
  })
  const document = new DOMParser().parseFromString(`<body>${clean}</body>`, 'text/html')
  document.body.querySelectorAll<HTMLElement>('[style]').forEach(element => {
    const declarations = element.getAttribute('style')!.split(';').flatMap(item => {
      const colon = item.indexOf(':'); if (colon < 1) return []
      const name = item.slice(0, colon).trim().toLowerCase(); const value = item.slice(colon + 1).trim()
      return SAFE_STYLES.has(name) && !/url\(|expression|javascript:/i.test(value) ? [`${name}: ${value}`] : []
    })
    declarations.length ? element.setAttribute('style', declarations.join('; ')) : element.removeAttribute('style')
  })
  return document.body.innerHTML
}

export function plainTextFromHtml(html: string): string {
  return new DOMParser().parseFromString(sanitizeEmailHtml(html), 'text/html').body.textContent?.trim() ?? ''
}
```

`RichTextEditor.vue` configures:

```ts
editorProps: {
  transformPastedHTML: html => sanitizeEmailHtml(html),
},
extensions: [
  StarterKit, Underline, TextStyle, FontSize, Color,
  TextAlign.configure({ types: ['heading', 'paragraph'] }),
  Link.configure({ openOnClick: false, protocols: ['http', 'https', 'mailto'] }),
  Table.configure({ resizable: false }), TableRow, TableHeader, TableCell,
  Placeholder.configure({ placeholder: t('compose.bodyPlaceholder') }),
]
```

`FontSize.ts` is an explicit TextStyle global attribute rather than a nonexistent
TipTap package:

```ts
export const FontSize = Extension.create({
  name: 'fontSize',
  addGlobalAttributes() {
    return [{ types: ['textStyle'], attributes: {
      fontSize: {
        default: null,
        parseHTML: element => element.style.fontSize || null,
        renderHTML: attributes => attributes.fontSize
          ? { style: `font-size: ${attributes.fontSize}` } : {},
      },
    } }]
  },
})
```

The toolbar exposes bold, italic, underline, heading, font size, color, alignment,
lists, link, table, and clear formatting. It emits sanitized HTML and derived plain
text on every update and shows a localized Word-normalization notice after a paste.

- [ ] **Step 5: Verify sanitizer parity and editor build**

Run:

```bash
mvn -pl OfficialPlugins/plugin-email -am -Dtest=EmailHtmlSanitizerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
npm --prefix OfficialPlugins/plugin-email/ui-src run typecheck
npm --prefix OfficialPlugins/plugin-email/ui-src test -- richText.test.ts
```

Expected: all commands exit 0; tests confirm allowed formatting survives and active/Word-private content is removed.

- [ ] **Step 6: Commit**

```bash
git add pom.xml OfficialPlugins/plugin-email/pom.xml OfficialPlugins/plugin-email/src \
  OfficialPlugins/plugin-email/ui-src
git commit -m "✨ feat(email): preserve safe Word rich text"
```

### Task 4: Add attachment-tag and group-tag intersection queries

**Files:**
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/repository/AddressBookRepository.java`
- Create: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/repository/AddressBookRepositoryTest.java`

- [ ] **Step 1: Write failing intersection tests**

Create the repository fixture and contacts directly:

```java
@TempDir Path temp;

@Test void intersectsAttachmentTagWithAnySelectedGroupTag() {
    EmailDatabase database = new EmailDatabase(new PluginDatabaseConfig("h2", "org.h2.Driver",
        "jdbc:h2:mem:tag-intersection;DB_CLOSE_DELAY=-1", "sa", "", temp));
    AddressBookRepository repository = new AddressBookRepository(database);
    long east = repository.saveTag(null, "East");
    long south = repository.saveTag(null, "South");
    long customer = repository.saveTag(null, "Customer");
    long manager = repository.saveTag(null, "Manager");
    long alice = repository.saveContact(new AddressBookRepository.ContactInput(null, "alice@example.com", "Alice"));
    long bob = repository.saveContact(new AddressBookRepository.ContactInput(null, "bob@example.com", "Bob"));
    long carol = repository.saveContact(new AddressBookRepository.ContactInput(null, "carol@example.com", "Carol"));
    long dana = repository.saveContact(new AddressBookRepository.ContactInput(null, "dana@example.com", "Dana"));
    repository.assignTags(Set.of(alice), Set.of(east, customer));
    repository.assignTags(Set.of(bob), Set.of(east, customer, manager));
    repository.assignTags(Set.of(carol), Set.of(east, manager));
    repository.assignTags(Set.of(dana), Set.of(south, customer));

assertEquals(Set.of("alice@example.com", "bob@example.com"),
    repository.resolveEmailsForAttachmentTag("East", Set.of(customer)));
assertEquals(Set.of("bob@example.com", "carol@example.com"),
    repository.resolveEmailsForAttachmentTag("East", Set.of(manager)));
assertEquals(Set.of("dana@example.com"),
    repository.resolveEmailsForAttachmentTag("South", Set.of(customer)));
    assertEquals(Set.of("alice@example.com", "bob@example.com"),
        repository.resolveEmailsForAttachmentTag("east", Set.of(customer)));
    assertEquals(Set.of(), repository.resolveEmailsForAttachmentTag("Missing", Set.of(customer)));
    assertEquals(Set.of(), repository.resolveEmailsForAttachmentTag("East", Set.of()));
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -pl OfficialPlugins/plugin-email -am -Dtest=AddressBookRepositoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because `resolveEmailsForAttachmentTag` is absent.

- [ ] **Step 3: Implement the parameterized intersection query**

Add:

```java
public Set<String> resolveEmailsForAttachmentTag(String attachmentTag, Set<Long> groupTagIds) {
    if (attachmentTag == null || attachmentTag.isBlank() || groupTagIds == null || groupTagIds.isEmpty()) {
        return Set.of();
    }
    try (SqlSession session = database.openSession()) {
        return Set.copyOf(session.getMapper(Mapper.class)
            .resolveIntersection(attachmentTag.trim().toLowerCase(), groupTagIds));
    }
}
```

The mapper query joins `Contact_Tag` twice: once through `Tag` for the normalized
attachment tag name and once for any selected group tag ID. It selects distinct
contact emails and binds every value through MyBatis parameters.

- [ ] **Step 4: Run repository/service tests and verify GREEN**

Run the Step 2 command.

Expected: all `AddressBookRepositoryTest` tests pass on mandatory H2.

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/repository/AddressBookRepository.java \
  OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/repository/AddressBookRepositoryTest.java
git commit -m "✨ feat(email): resolve attachment and group tag intersections"
```

### Task 5: Replace batch planning with one-message-per-tag semantics

**Files:**
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/BatchPlanner.java`
- Modify: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/service/BatchPlannerTest.java`

- [ ] **Step 1: Replace old recipient-suffix tests with failing tag-plan tests**

The core test creates:

```text
report_East.pdf
supplement_East.xlsx
report_South.pdf
README
```

and asserts:

```java
var plan = BatchPlanner.byAttachmentTags(template, directory, List.of(commonTerms), tag -> switch (tag) {
    case "East" -> new RecipientGroups(
        Set.of("alice@example.com", "bob@example.com"),
        Set.of("bob@example.com", "manager@example.com"));
    case "South" -> new RecipientGroups(Set.of("dana@example.com"), Set.of());
    default -> new RecipientGroups(Set.of(), Set.of());
});

assertEquals(2, plan.messages().size());
assertEquals(List.of("alice@example.com", "bob@example.com"), plan.messages().get(0).request().to());
assertEquals(List.of("manager@example.com"), plan.messages().get(0).request().cc());
assertEquals(3, plan.messages().get(0).request().attachments().size());
assertEquals(List.of(Path.of("README")), plan.ignoredFiles().stream().map(Path::getFileName).toList());
```

Add tests for final-underscore parsing, deterministic tag/message order, duplicate
address removal, To precedence over CC, a skipped tag with no To contacts, and
common attachments in every generated request.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
mvn -pl OfficialPlugins/plugin-email -am -Dtest=BatchPlannerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because `byAttachmentTags`, `RecipientGroups`, and planned-message metadata do not exist.

- [ ] **Step 3: Introduce explicit planning records**

Use these exact public records:

```java
public record RecipientGroups(Set<String> to, Set<String> cc) { }
@FunctionalInterface public interface RecipientResolver { RecipientGroups resolve(String attachmentTag); }
public record PlannedMessage(String attachmentTag, EmailMessageRequest request,
        List<Path> tagAttachments, List<Path> commonAttachments) { }
public record SkippedTag(String attachmentTag, String reason, List<Path> attachments) { }
public record BatchPlan(List<PlannedMessage> messages, List<Path> ignoredFiles,
        List<SkippedTag> skippedTags) { }
```

`byAttachmentTags` must:

1. Sort regular files by filename.
2. Parse the final underscore/final extension tag.
3. Group files by tag in insertion order.
4. Normalize, sort, and deduplicate To and CC.
5. Remove every To address from CC.
6. Emit `SkippedTag(tag, "No primary recipients", files)` when To is empty.
7. Combine tag attachments followed by common attachments in each request.
8. Produce one `PlannedMessage` per valid tag.

Keep a renamed `byContactTags` helper for Compose; it creates one request per
primary recipient while preserving the template CC, BCC, body, and attachments.

- [ ] **Step 4: Run tests and verify GREEN**

Run the Step 2 command.

Expected: all batch planner tests pass with deterministic order.

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/BatchPlanner.java \
  OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/service/BatchPlannerTest.java
git commit -m "♻️ refactor(email): plan one message per attachment tag"
```

### Task 6: Expose preview, revise confirmation snapshots, and remove retry

**Files:**
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/PendingSendService.java`
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/service/EmailSendService.java`
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/repository/PendingSendRepository.java`
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/repository/SentLogRepository.java`
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/rpc/EmailRpcHandlers.java`
- Modify: `OfficialPlugins/plugin-email/src/main/java/fan/summer/fengyu/plugin/email/EmailWorkerMain.java`
- Modify: `OfficialPlugins/packages/email/manifest.json`
- Modify: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/service/PendingSendServiceTest.java`
- Modify: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/EmailWorkerMainTest.java`
- Modify: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/EmailManifestTest.java`
- Modify: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/service/EmailSendServiceTest.java`

- [ ] **Step 1: Write failing Worker and confirmation tests**

Add assertions that:

- `email_batch_preview` returns parsed tags, ignored files, per-tag To/CC, tag attachments, common attachments, and planned message count without creating a pending row.
- `email_send_batch` accepts `recipientGroupTagIds`, `ccGroupTagIds`, `inputDirectory`, and `commonAttachments`; it creates one snapshot message per valid attachment tag.
- Compose with `recipientTagIds` creates one snapshot message per unique primary contact and preserves common CC.
- Confirmation rows include per-tag complete To, CC, tag attachments, common attachments, ignored files, and skipped tags.
- `email_send_retry` is no longer registered and `retryFailed` no longer exists.
- `email_send_records_query` returns paginated pending tasks and per-message sent
  logs without requiring the user to type a confirmation ID.

Use a round-trip request shaped as:

```json
{
  "method": "email_send_batch",
  "params": {
    "accountId": 7,
    "recipientGroupTagIds": [10],
    "ccGroupTagIds": [11],
    "inputDirectory": "/authorized/input",
    "commonAttachments": ["/authorized/common/terms.pdf"],
    "subject": "Quarterly report",
    "plainText": "Please review",
    "htmlText": "<p>Please review</p>"
  }
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
mvn -pl OfficialPlugins/plugin-email -am \
  -Dtest=PendingSendServiceTest,EmailWorkerMainTest,EmailManifestTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation/assertion failures for the new plan records, preview method, and revised schema.

- [ ] **Step 3: Add one planning path shared by preview and prepare**

Add a service method with this signature:

```java
public BatchPlanner.BatchPlan planAttachmentBatch(EmailMessageRequest template, Path directory,
        List<Path> commonAttachments, Set<Long> recipientGroupTagIds, Set<Long> ccGroupTagIds) {
    return BatchPlanner.byAttachmentTags(template, directory, commonAttachments, tag ->
        new BatchPlanner.RecipientGroups(
            addressBook.resolveEmailsForAttachmentTag(tag, recipientGroupTagIds),
            addressBook.resolveEmailsForAttachmentTag(tag, ccGroupTagIds)));
}
```

`previewBatch` serializes this plan but never calls `pending.create`. `prepareBatch`
calls the same method and persists its immutable result.

Adapt single preparation to the new plan type explicitly:

```java
public ConfirmationEnvelope prepareSingle(EmailMessageRequest request) {
    var message = new BatchPlanner.PlannedMessage(null, request, request.attachments(), List.of());
    return prepare("SINGLE", new BatchPlanner.BatchPlan(List.of(message), List.of(), List.of()));
}
```

For Compose tag mode, add:

```java
public ConfirmationEnvelope prepareComposeByTags(EmailMessageRequest template, Set<Long> tagIds) {
    return prepareBatch("TAG_COMPOSE",
        BatchPlanner.byContactTags(template, addressBook.resolveRecipientEmails(tagIds)));
}
```

- [ ] **Step 4: Store and summarize tag/common attachment metadata**

Extend `MessageSnapshot` with `attachmentTag`, `tagAttachments`, and
`commonAttachments`. Keep `EmailMessageRequest.attachments` as the combined send
list. Summary rows use stable localized-neutral keys (`Message 1 / To`,
`Message 1 / CC`, `Message 1 / Tag attachments`, `Message 1 / Common attachments`)
so both host AI cards and plugin dialogs can review exact values.

Delete `retryFailed`, remove `retrySend`, unregister `email_send_retry`, and remove
the retry test. Existing terminal records remain readable; no database migration is
needed because no schema column represents the UI retry feature.

Make confirmation IDs flow into future sent-log rows. Change the pending sender
boundary to:

```java
@FunctionalInterface public interface Sender {
    SendResult send(String confirmationId, EmailMessageRequest request);
}
```

Update existing pending-service test senders from `request -> result` to
`(confirmationId, request) -> result`; add an assertion that the claimed ID passed
to the sender equals the prepared confirmation ID.

Add `EmailSendService.sendSingle(request, confirmationId)` and have the existing
one-argument method delegate with `null` for compatibility. `PendingSendService`
passes the active confirmation ID so each newly written sent-log row is linked to
its task.

Add paginated read models:

```java
public record SendTaskView(String confirmationId, long accountId, String mode,
        String status, LocalDateTime expiresAt, LocalDateTime updatedAt) { }
public record SentMessageView(long id, String confirmationId, String accountEmail,
        String recipientsJson, String subject, String attachmentJson, String status,
        String errorMessage, LocalDateTime sentAt) { }
```

`PendingSendRepository.search(status, offset, limit)` and
`SentLogRepository.search(confirmationId, status, query, offset, limit)` use
parameterized filters and newest-first ordering. Register UI-only
`email_send_records_query`; its result contains `tasks` and `messages`. Old
sent-log rows with a null confirmation ID remain listable.

- [ ] **Step 5: Update manifest schema**

Keep seven AI tools, but change `email_send_batch` to filename-tag semantics only.
Its schema requires `accountId`, `recipientGroupTagIds`, `ccGroupTagIds`, and
`inputDirectory`, and optionally accepts `commonAttachments`, `subject`,
`plainText`, and `htmlText`. Its description states “one message per attachment
tag” and “first call prepares confirmation without sending.”

`email_send_single` optionally accepts `recipientTagIds`; when present the Worker
uses Compose tag mode, otherwise it requires a non-empty `to` array.

- [ ] **Step 6: Run focused and full email tests**

Run:

```bash
mvn -pl OfficialPlugins/plugin-email -am \
  -Dtest=PendingSendServiceTest,EmailWorkerMainTest,EmailManifestTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl OfficialPlugins/plugin-email -am test
```

Expected: all selected tests and the email module reactor pass; no retry RPC is advertised.

- [ ] **Step 7: Commit**

```bash
git add OfficialPlugins/plugin-email OfficialPlugins/packages/email/manifest.json
git commit -m "✨ feat(email): expose tag-matched batch previews"
```

### Task 7: Rebuild Compose for direct addresses and private tag sends

**Files:**
- Modify: `OfficialPlugins/plugin-email/ui-src/src/stores/compose.ts`
- Modify: `OfficialPlugins/plugin-email/ui-src/src/components/ComposeTab.vue`
- Create: `OfficialPlugins/plugin-email/ui-src/src/components/ConfirmationDialog.vue`
- Modify: `OfficialPlugins/plugin-email/ui-src/src/stores/emailUi.test.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/components/ComposeTab.test.ts`

- [ ] **Step 1: Write failing Compose state and component tests**

Cover:

```ts
const compose = useComposeStore()
compose.mode = 'CONTACT_TAGS'
compose.recipientTagIds = [4, 5]
compose.to = ['direct@example.com']
compose.cc = ['manager@example.com', 'direct@example.com']
expect(compose.normalizedCc).toEqual(['manager@example.com'])
```

The component test asserts direct mode sends `to`/`cc`, tag mode sends
`recipientTagIds` plus common `cc`, `RichTextEditor` supplies HTML/plain text, and
no `confirm_send` call occurs until the confirmation dialog action.

```ts
it('prepares tag Compose and dispatches only after confirmation', async () => {
  bridge.invoke
    .mockResolvedValueOnce({ success: true, confirmation_required: true,
      confirmation: { confirmationId: 'c1', expiresAt: '2026-07-14T12:00:00Z', summary: [] } })
    .mockResolvedValueOnce({ success: true, send: { status: 'COMPLETED', succeeded: 2, failed: 0 } })
  const wrapper = mount(ComposeTab, composeMountOptions())
  await wrapper.get('[data-testid="compose-mode-tags"]').trigger('click')
  useComposeStore().recipientTagIds = [4]
  useComposeStore().cc = ['manager@example.com']
  await wrapper.get('[data-testid="compose-review"]').trigger('click')
  expect(bridge.invoke).toHaveBeenCalledWith('email_send_single',
    expect.objectContaining({ recipientTagIds: [4], cc: ['manager@example.com'] }))
  expect(bridge.invoke).toHaveBeenCalledTimes(1)
  await wrapper.get('[data-testid="confirmation-approve"]').trigger('click')
  expect(bridge.invoke.mock.calls.map(call => call[0])).toEqual(['email_send_single', 'confirm_send'])
})
```

Add a draft test:

```ts
compose.subject = 'Quarterly update'
compose.htmlText = '<p>Draft</p>'
compose.persistDraft()
const restored = useComposeStore()
restored.restoreDraft()
expect(restored.subject).toBe('Quarterly update')
expect(restored.attachments).toEqual([])
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
npm --prefix OfficialPlugins/plugin-email/ui-src test -- emailUi.test.ts ComposeTab.test.ts
```

Expected: failures because mode, recipient tag state, normalized CC, and the rebuilt component are absent.

- [ ] **Step 3: Implement focused Compose state**

Use:

```ts
export type ComposeMode = 'DIRECT' | 'CONTACT_TAGS'
const mode = ref<ComposeMode>('DIRECT')
const recipientTagIds = ref<number[]>([])
const to = ref<string[]>([])
const cc = ref<string[]>([])
const normalizedTo = computed(() => normalizeAddresses(to.value))
const normalizedCc = computed(() => normalizeAddresses(cc.value)
  .filter(address => !normalizedTo.value.includes(address)))
```

Remove `filenameRecipients` and `setFilenamePreview` from Compose; filename state
belongs only in the batch store.

Implement `persistDraft` as a 400 ms debounced write to
`localStorage['fengyu.email.compose.v1']`. Persist mode, To, CC, recipient tag IDs,
subject, sanitized HTML, and plain text. Do not persist `FileRef` attachments,
confirmation IDs, send results, account passwords, or error text because grants
and transient protocol state are not reusable after a restart.

- [ ] **Step 4: Rebuild the approved Compose workspace**

`ComposeTab.vue` contains account, mode switch, direct To or contact-tag selector,
CC, subject, `RichTextEditor`, attachments, autosave status, validation summary,
and the reusable `ConfirmationDialog`. Do not render BCC in the visual workflow;
the Worker retains BCC compatibility for AI/existing callers.

Direct mode calls `email_send_single` with `to` and `cc`. Tag mode calls the same
method with `recipientTagIds` and `cc`; the confirmation displays that recipients
receive separate messages.

- [ ] **Step 5: Verify Compose tests and typecheck**

Run:

```bash
npm --prefix OfficialPlugins/plugin-email/ui-src run typecheck
npm --prefix OfficialPlugins/plugin-email/ui-src test -- emailUi.test.ts ComposeTab.test.ts
```

Expected: all tests pass and direct/tag payload snapshots match the Worker schema.

- [ ] **Step 6: Commit**

```bash
git add OfficialPlugins/plugin-email/ui-src/src
git commit -m "✨ feat(email): rebuild Compose recipient workflows"
```

### Task 8: Rebuild Batch Send around tag intersections and common attachments

**Files:**
- Create: `OfficialPlugins/plugin-email/ui-src/src/stores/batch.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/stores/batch.test.ts`
- Modify: `OfficialPlugins/plugin-email/ui-src/src/components/BatchTab.vue`
- Modify: `OfficialPlugins/plugin-email/ui-src/src/stores/emailUi.integration.test.ts`

- [ ] **Step 1: Write failing batch store and UI integration tests**

The store test asserts:

```ts
store.recipientGroupTagIds = [10]
store.ccGroupTagIds = [11]
store.commonAttachments = [{ id: 'f1', name: 'terms.pdf', kind: 'file', access: 'read', size: 128 }]
store.applyPreview({
  messages: [{ attachmentTag: 'East', to: ['a@example.com', 'b@example.com'],
    cc: ['manager@example.com'], tagAttachments: ['report_East.pdf'],
    commonAttachments: ['terms.pdf'] }],
  ignoredFiles: ['README'], skippedTags: [],
})
expect(store.messageCount).toBe(1)
expect(store.preview.messages[0].commonAttachments).toEqual(['terms.pdf'])
```

The component test verifies `email_batch_preview` precedes
`email_send_batch`, both receive the same directory/group/common-attachment
parameters, and SMTP dispatch still waits for `confirm_send`.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
npm --prefix OfficialPlugins/plugin-email/ui-src test -- batch.test.ts emailUi.integration.test.ts
```

Expected: module/test failures because the store and revised flow are absent.

- [ ] **Step 3: Implement batch state and preview**

The store owns:

```ts
const inputDirectory = ref<FileRef | null>(null)
const recipientGroupTagIds = ref<number[]>([])
const ccGroupTagIds = ref<number[]>([])
const commonAttachments = ref<FileRef[]>([])
const subject = ref('')
const htmlText = ref('')
const plainText = ref('')
const preview = ref<BatchPreview>({ messages: [], ignoredFiles: [], skippedTags: [] })
const messageCount = computed(() => preview.value.messages.length)
```

`BatchPreviewMessage` contains `attachmentTag`, `to`, `cc`, `tagAttachments`, and
`commonAttachments`. The preview call is debounced only after all required fields
exist; a manual “Refresh preview” remains available after errors.

- [ ] **Step 4: Rebuild the approved Batch workspace**

Remove mode toggles and failed retry controls. Render:

- Authorized input-directory picker and parsed-tag/file table.
- Sending-group tag selector and CC-group tag selector.
- The intersection formula and To-over-CC rule.
- Subject and `RichTextEditor`.
- Common attachment picker/list.
- Per-tag message preview with complete To/CC and both attachment classes.
- Ignored files and skipped no-recipient tags.
- “Generate and review N messages” confirmation action.

- [ ] **Step 5: Verify batch tests and production build**

Run:

```bash
npm --prefix OfficialPlugins/plugin-email/ui-src run typecheck
npm --prefix OfficialPlugins/plugin-email/ui-src test -- batch.test.ts emailUi.integration.test.ts
npm --prefix OfficialPlugins/plugin-email/ui-src run build
```

Expected: all commands pass; no tag-only batch mode or retry UI text remains.

- [ ] **Step 6: Commit**

```bash
git add OfficialPlugins/plugin-email/ui-src/src
git commit -m "✨ feat(email): redesign tag-matched batch sending"
```

### Task 9: Split and polish contacts, archive, records, and accounts

**Files:**
- Modify: `OfficialPlugins/plugin-email/ui-src/src/components/AddressBookTab.vue`
- Modify: `OfficialPlugins/plugin-email/ui-src/src/components/CollectTab.vue`
- Delete: `OfficialPlugins/plugin-email/ui-src/src/components/RecordsAccountsTab.vue`
- Create: `OfficialPlugins/plugin-email/ui-src/src/components/SendRecordsView.vue`
- Create: `OfficialPlugins/plugin-email/ui-src/src/components/AccountSettingsView.vue`
- Modify: `OfficialPlugins/plugin-email/ui-src/src/stores/archive.ts`
- Modify: `OfficialPlugins/plugin-email/ui-src/src/stores/accounts.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/components/ManagementViews.test.ts`

- [ ] **Step 1: Write failing management-workspace tests**

Mount the focused components with SDK stubs and assert their explicit contracts:

```ts
it('renders structured send records without retry controls or raw JSON', async () => {
  bridge.invoke.mockResolvedValue({ success: true, summary: 'found',
    tasks: [{ confirmationId: 'c1', status: 'PARTIAL_FAILED', mode: 'FILENAME_TAGS',
      updatedAt: '2026-07-14T10:00:00Z' }],
    messages: [{ confirmationId: 'c1', subject: 'Quarterly', status: 'FAILED',
      accountEmail: 'mail@example.com', errorMessage: 'Mailbox rejected' }],
  })
  const wrapper = mount(SendRecordsView, managementMountOptions())
  await wrapper.get('[data-testid="record-search"]').setValue('c1')
  await wrapper.get('[data-testid="record-search-submit"]').trigger('click')
  expect(wrapper.text()).toContain('PARTIAL_FAILED')
  expect(bridge.invoke).toHaveBeenCalledWith('email_send_records_query',
    expect.objectContaining({ query: 'c1', offset: 0 }))
  expect(wrapper.find('pre').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('retry')
})

it('keeps account passwords write-only and separates test from save', async () => {
  const wrapper = mount(AccountSettingsView, managementMountOptions())
  expect(wrapper.get('input[type="password"]').attributes('autocomplete')).toBe('new-password')
  await wrapper.get('[data-testid="smtp-test"]').trigger('click')
  expect(bridge.invoke).toHaveBeenLastCalledWith('email_account_test', expect.any(Object))
  await wrapper.get('[data-testid="account-save"]').trigger('click')
  expect(bridge.invoke).toHaveBeenLastCalledWith('email_account_save', expect.any(Object))
})
```

Add these focused assertions in the same test file:

```ts
it('keeps bulk contact actions separate from tag management', async () => {
  const wrapper = mount(AddressBookTab, managementMountOptions())
  expect(wrapper.get('[data-testid="contact-bulk-tags"]').exists()).toBe(true)
  expect(wrapper.get('[data-testid="tag-manager-open"]').exists()).toBe(true)
  expect(wrapper.find('[data-testid="tag-manager-dialog"]').exists()).toBe(false)
})

it('shows collection counters and archive pagination together', () => {
  const store = useArchiveStore()
  store.updateProgress({ processed: 8, successful: 5, failed: 1, newArchived: 5, duplicates: 2 })
  const wrapper = mount(CollectTab, managementMountOptions())
  expect(wrapper.get('[data-testid="archive-progress"]').text()).toContain('8')
  expect(wrapper.get('[data-testid="archive-results"]').exists()).toBe(true)
  expect(wrapper.get('[data-testid="archive-next-page"]').exists()).toBe(true)
})
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
npm --prefix OfficialPlugins/plugin-email/ui-src test -- ManagementViews.test.ts
```

Expected: failures because split record/account components do not exist and current records render raw objects/retry controls.

- [ ] **Step 3: Implement the four approved layouts**

Use the existing RPC methods and stores; do not change Worker behavior in this task.

- Contacts: list/editor split, search and tag filter, bulk assignment toolbar, separate tag-management dialog.
- Archive: manual collection controls above search table, visible progress metrics, focused detail drawer.
- Send Records: filters and structured task/message status; no `email_send_retry` call.
- Send Records calls `email_send_records_query` with status/query/offset/limit and
  renders its `tasks` and `messages` arrays; confirmation-ID lookup remains an
  optional detail action, not the primary list workflow.
- Accounts: account list beside SMTP/IMAP form, write-only password help, separate Test/Save, confirmed delete.

Every visible string must use `t(...)`; every icon must use `EmailIcon`.

`App.vue` maps workspaces explicitly so Records and Accounts cannot collapse back
into one overloaded component:

```ts
const workspaces: Record<WorkspaceId, Component> = {
  compose: ComposeTab,
  batch: BatchTab,
  contacts: AddressBookTab,
  archive: CollectTab,
  records: SendRecordsView,
  accounts: AccountSettingsView,
}
const activeWorkspace = computed(() => workspaces[navigation.active])
```

Each view exposes stable test IDs for its primary search/save/test/delete actions;
production behavior must not branch on those IDs.

- [ ] **Step 4: Verify management tests and full UI suite**

Run:

```bash
npm --prefix OfficialPlugins/plugin-email/ui-src run typecheck
npm --prefix OfficialPlugins/plugin-email/ui-src test
```

Expected: all UI tests pass; no raw record object or retry action appears.

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/plugin-email/ui-src/src
git commit -m "♻️ refactor(email): separate management workspaces"
```

### Task 10: Apply responsive, theme, accessibility, and error-state polish

**Files:**
- Modify: `OfficialPlugins/plugin-email/ui-src/src/styles.css`
- Modify: `OfficialPlugins/plugin-email/ui-src/src/App.vue`
- Modify: `OfficialPlugins/plugin-email/ui-src/src/sdk.ts`
- Create: `OfficialPlugins/plugin-email/ui-src/src/components/ResponsiveShell.test.ts`

- [ ] **Step 1: Write failing shell/error contract tests**

Check the rendered shell and safe error conversion:

```ts
it('renders one labelled current workspace in Chinese', async () => {
  applyEnvironment({ locale: 'zh-CN', theme: 'dark' })
  const wrapper = mount(App, appMountOptions())
  expect(wrapper.get('nav[aria-label="Email Center"]').exists()).toBe(true)
  expect(wrapper.findAll('[aria-current="page"]')).toHaveLength(1)
  expect(wrapper.text()).toContain('写邮件')
  expect(wrapper.text()).not.toContain('Compose')
})

it('never stringifies unknown objects into user errors', () => {
  expect(actionable({ summary: 'Folder unavailable' }, 'Collect')).toContain('Folder unavailable')
  expect(actionable({ secret: 'hidden' }, 'Collect')).not.toContain('[object Object]')
  expect(actionable({ secret: 'hidden' }, 'Collect')).not.toContain('hidden')
})
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
npm --prefix OfficialPlugins/plugin-email/ui-src test -- ResponsiveShell.test.ts
```

Expected: assertions fail against the current CSS and string-based `actionable` implementation.

- [ ] **Step 3: Implement approved layout breakpoints and safe errors**

Implement the breakpoints using theme variables rather than fixed page colors:

```css
.email-layout { min-height: 100vh; display: grid; grid-template-columns: 88px minmax(0, 1fr); background: rgb(var(--v-theme-background)); color: rgb(var(--v-theme-on-background)); }
.task-rail { border-inline-end: 1px solid rgba(var(--v-border-color), var(--v-border-opacity)); background: rgb(var(--v-theme-surface-variant)); }
.workspace-grid { display: grid; grid-template-columns: minmax(0, 1fr) 230px; gap: 16px; }
:where(button, [href], input, textarea, [tabindex]):focus-visible { outline: 3px solid rgb(var(--v-theme-primary)); outline-offset: 2px; }
@media (max-width: 1000px) { .workspace-summary { display: none; } .workspace-grid { grid-template-columns: 1fr; } }
@media (max-width: 720px) { .email-layout { display: block; } .task-rail { display: flex; overflow-x: auto; border-inline-end: 0; border-block-end: 1px solid rgba(var(--v-border-color), var(--v-border-opacity)); } .form-grid { grid-template-columns: 1fr; } }
```

Required behavior:

- Wide: 88 px task rail, fluid workspace, optional 220–240 px summary.
- Under 1000 px: hide summary before shrinking forms.
- Under 720 px: task rail becomes horizontally scrollable navigation; two-column forms become one column.
- Use Vuetify theme variables only; remove hardcoded page background colors.
- Focus rings remain visible in light and dark themes.
- Status never depends on color alone.

Change `actionable` to extract `summary`/`message` only from recognized shapes and
return `t('errors.actionFailed', { action, detail })`; for unknown values use
`t('errors.unknown')` rather than `String(object)`.

- [ ] **Step 4: Verify responsive contracts and static source audits**

Run:

```bash
npm --prefix OfficialPlugins/plugin-email/ui-src run typecheck
npm --prefix OfficialPlugins/plugin-email/ui-src test
rg -n 'mdi-|@mdi/font|postMessage|fetch\s*\(' OfficialPlugins/plugin-email/ui-src/src || true
```

Expected: typecheck/tests pass and the audit prints no forbidden bridge or font-icon use.

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/plugin-email/ui-src/src
git commit -m "🐛 fix(email): polish responsive and error states"
```

### Task 11: Update package contracts and documentation

**Files:**
- Modify: `OfficialPlugins/build-packages.sh`
- Modify: `OfficialPlugins/plugin-email/src/test/java/fan/summer/fengyu/plugin/email/EmailManifestTest.java`
- Modify: `scripts/e2e-smoke.sh`
- Modify: `docs/en/plugins/email-center.md`
- Modify: `docs/zh/plugins/email-center.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add failing package and smoke assertions**

Extend `EmailManifestTest` to assert the revised batch schema and absence of
`TAGS`/retry wording. Extend the smoke script to invoke `email_batch_preview` on a
fixture directory containing `report_East.pdf` and verify one parsed `East` tag.

Add package content assertions:

```bash
unzip -p OfficialPlugins/target/packages/fan.summer.email-4.0.0.fyp ui/index.html \
  | rg '<meta charset="UTF-8"'
! unzip -p OfficialPlugins/target/packages/fan.summer.email-4.0.0.fyp 'ui/assets/*.js' \
  | rg '@font-face|materialdesignicons'
```

- [ ] **Step 2: Run focused checks and verify RED**

Run:

```bash
mvn -pl OfficialPlugins/plugin-email -am -Dtest=EmailManifestTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
bash OfficialPlugins/build-packages.sh
```

Expected: manifest/smoke assertions fail until schemas, package contents, and fixtures are updated.

- [ ] **Step 3: Update documentation without overstating coverage**

Document:

- Task-rail navigation and host locale/theme following.
- Direct-address and private per-contact tag Compose.
- Final-underscore attachment tag parsing.
- To/CC intersection formulas and To precedence.
- One message per attachment tag.
- Tag-scoped versus common attachments.
- Email-safe Word formatting subset and excluded embedded Word images.
- Manual collection and no failed-item retry mode.

- [ ] **Step 4: Rebuild package and run smoke**

Run:

```bash
bash OfficialPlugins/build-packages.sh
bash scripts/e2e-smoke.sh
```

Expected: Email Worker is discovered, account list responds, batch preview parses the fixture tag, and package UTF-8/icon assertions pass. The existing Excel file-flow may report its documented `openpyxl` skip when unavailable.

- [ ] **Step 5: Commit**

```bash
git add OfficialPlugins/build-packages.sh OfficialPlugins/plugin-email/src/test \
  scripts/e2e-smoke.sh docs/en/plugins/email-center.md docs/zh/plugins/email-center.md CHANGELOG.md
git commit -m "📝 docs(email): document redesigned sending workflows"
```

### Task 12: Full verification and real-browser acceptance

**Files:**
- Verify only; modify a failing file only through a new RED/GREEN regression cycle.

- [ ] **Step 1: Run the complete Java reactor**

Run: `mvn clean verify`

Expected: all eight reactor modules succeed with zero failures/errors; optional MySQL/PostgreSQL contracts are explicitly skipped only when their environment URLs are absent.

- [ ] **Step 2: Run host and plugin frontend verification**

Run:

```bash
npm --prefix frontend run typecheck
npm --prefix frontend run test:unit
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix OfficialPlugins/plugin-email/ui-src run typecheck
npm --prefix OfficialPlugins/plugin-email/ui-src test
npm --prefix OfficialPlugins/plugin-email/ui-src run build
npm --prefix docs run docs:build
```

Expected: every command exits 0; UI tests cover Word paste, both Compose modes, batch intersections, common attachments, management workspaces, i18n, and responsive shell.

- [ ] **Step 3: Rebuild installable packages and run end-to-end smoke**

Run:

```bash
bash OfficialPlugins/build-packages.sh
bash scripts/e2e-smoke.sh
unzip -l OfficialPlugins/target/packages/fan.summer.email-4.0.0.fyp
```

Expected: package contains UTF-8 `ui/index.html`, JavaScript/CSS assets, manifest,
and Worker JAR; runtime smoke discovers and invokes Email Center successfully.

- [ ] **Step 4: Inspect the real iframe in the browser**

Start the backend and frontend, open
`http://127.0.0.1:5173/plugin/fan.summer.email`, and verify:

- No mojibake in navigation, buttons, toolbar, dialogs, or icons.
- Chinese and English layouts at wide, 900 px, and 680 px iframe widths.
- Light/dark theme propagation.
- Word paste preserves the approved formatting subset.
- Direct Compose, tag Compose, filename-tag preview, To/CC intersections, public
  attachments, and confirmation summaries match the approved design.
- Contacts, Archive, Send Records, and Account Settings match their focused layouts.

- [ ] **Step 5: Audit source and working tree**

Run:

```bash
rg -n 'mdi-|@mdi/font|postMessage|fetch\s*\(' OfficialPlugins/plugin-email/ui-src/src || true
rg -n 'email_send_retry|Address-book tags|"TAGS"' OfficialPlugins/plugin-email OfficialPlugins/packages/email || true
git diff --check
git status --short
```

Expected: forbidden UI bridge/font-icon and removed workflow searches are empty;
diff check is clean; only intentional changes plus preserved user-owned documentation
work remain.

- [ ] **Step 6: Commit any verification-only fixture changes**

If Step 1–5 required no fixes, do not create an empty commit. If a regression fix
was required, commit only its focused test and implementation using the appropriate
emoji conventional commit message after rerunning the failed command.
