<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { EditorContent, useEditor } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Placeholder from '@tiptap/extension-placeholder'
import Underline from '@tiptap/extension-underline'
import TextStyle from '@tiptap/extension-text-style'
import Color from '@tiptap/extension-color'
import TextAlign from '@tiptap/extension-text-align'
import Link from '@tiptap/extension-link'
import Table from '@tiptap/extension-table'
import TableRow from '@tiptap/extension-table-row'
import TableHeader from '@tiptap/extension-table-header'
import TableCell from '@tiptap/extension-table-cell'
import { FontSize } from '../extensions/FontSize'
import { plainTextFromHtml, sanitizeEmailHtml, shouldApplyExternalContent } from '../richText'

const props = withDefaults(defineProps<{ modelValue?: string; disabled?: boolean }>(), { modelValue: '', disabled: false })
const emit = defineEmits<{
  'update:modelValue': [html: string]
  'update:plainText': [plainText: string]
}>()
const { t } = useI18n()
const pasteNotice = ref(false)
const linkDialog = ref(false)
const linkHref = ref('')

// IME composition state. While a CJK IME is composing, the parent echoes our
// emitted HTML back via v-model; writing it back with setContent would tear down
// the node the IME is writing into and abort the input. See shouldApplyExternalContent.
let composing = false
let lastEmittedHtml: string | null = null

const editor = useEditor({
  content: sanitizeEmailHtml(props.modelValue),
  editable: !props.disabled,
  editorProps: {
    transformPastedHTML: html => sanitizeEmailHtml(html),
    handleDOMEvents: {
      compositionstart: () => { composing = true },
      compositionend: () => { composing = false },
    },
  },
  extensions: [
    StarterKit,
    Underline,
    TextStyle,
    FontSize,
    Color,
    TextAlign.configure({ types: ['heading', 'paragraph'] }),
    Link.configure({ openOnClick: false, protocols: ['http', 'https', 'mailto'] }),
    Table.configure({ resizable: false }),
    TableRow,
    TableHeader,
    TableCell,
    Placeholder.configure({ placeholder: t('compose.bodyPlaceholder') }),
  ],
  onUpdate: ({ editor: current }) => {
    const html = sanitizeEmailHtml(current.getHTML())
    lastEmittedHtml = html
    emit('update:modelValue', html)
    emit('update:plainText', plainTextFromHtml(html))
  },
})

watch(() => props.modelValue, value => {
  if (!editor.value) return
  if (!shouldApplyExternalContent(value, lastEmittedHtml, composing)) return
  const clean = sanitizeEmailHtml(value)
  if (sanitizeEmailHtml(editor.value.getHTML()) !== clean) {
    editor.value.commands.setContent(clean, false)
  }
})
watch(() => props.disabled, value => editor.value?.setEditable(!value))
onBeforeUnmount(() => editor.value?.destroy())

function openLinkDialog(): void {
  linkHref.value = editor.value?.getAttributes('link').href as string | undefined ?? 'https://'
  linkDialog.value = true
}
function applyLink(): void {
  const href = linkHref.value.trim()
  if (!href) editor.value?.chain().focus().unsetLink().run()
  else editor.value?.chain().focus().extendMarkRange('link').setLink({ href }).run()
  linkDialog.value = false
}

function onPaste(): void {
  pasteNotice.value = true
  window.setTimeout(() => { pasteNotice.value = false }, 4000)
}
</script>

<template>
  <div class="rich-text-editor" :class="{ 'rich-text-editor--disabled': disabled }">
    <div class="editor-toolbar" role="toolbar" :aria-label="t('editor.toolbar')">
      <v-btn size="small" variant="text" :aria-label="t('editor.bold')" @click="editor?.chain().focus().toggleBold().run()"><strong>B</strong></v-btn>
      <v-btn size="small" variant="text" :aria-label="t('editor.italic')" @click="editor?.chain().focus().toggleItalic().run()"><em>I</em></v-btn>
      <v-btn size="small" variant="text" :aria-label="t('editor.underline')" @click="editor?.chain().focus().toggleUnderline().run()"><u>U</u></v-btn>
      <select :aria-label="t('editor.heading')" @change="editor?.chain().focus().toggleHeading({ level: Number(($event.target as HTMLSelectElement).value) as 1 | 2 | 3 }).run()">
        <option value="1">H1</option><option value="2">H2</option><option value="3">H3</option>
      </select>
      <select :aria-label="t('editor.fontSize')" @change="editor?.chain().focus().setMark('textStyle', { fontSize: ($event.target as HTMLSelectElement).value }).run()">
        <option value="12px">12</option><option value="14px">14</option><option value="16px">16</option><option value="20px">20</option><option value="24px">24</option>
      </select>
      <input type="color" :aria-label="t('editor.color')" @input="editor?.chain().focus().setColor(($event.target as HTMLInputElement).value).run()">
      <v-btn size="small" variant="text" :aria-label="t('editor.alignLeft')" @click="editor?.chain().focus().setTextAlign('left').run()">↤</v-btn>
      <v-btn size="small" variant="text" :aria-label="t('editor.alignCenter')" @click="editor?.chain().focus().setTextAlign('center').run()">↔</v-btn>
      <v-btn size="small" variant="text" :aria-label="t('editor.alignRight')" @click="editor?.chain().focus().setTextAlign('right').run()">↦</v-btn>
      <v-btn size="small" variant="text" @click="editor?.chain().focus().toggleBulletList().run()">{{ t('editor.bullets') }}</v-btn>
      <v-btn size="small" variant="text" @click="editor?.chain().focus().toggleOrderedList().run()">{{ t('editor.numbering') }}</v-btn>
      <v-btn size="small" variant="text" @click="openLinkDialog">{{ t('editor.link') }}</v-btn>
      <v-btn size="small" variant="text" @click="editor?.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()">{{ t('editor.table') }}</v-btn>
      <v-btn size="small" variant="text" @click="editor?.chain().focus().unsetAllMarks().clearNodes().run()">{{ t('editor.clear') }}</v-btn>
    </div>
    <EditorContent :editor="editor" class="editor" @paste="onPaste" />
    <v-alert v-if="pasteNotice" density="compact" type="info" variant="tonal" class="mt-2">
      {{ t('compose.wordNormalized') }}
    </v-alert>
  </div>
  <v-dialog v-model="linkDialog" max-width="480">
    <v-card class="codex-dialog">
      <v-card-title>{{ t('editor.link') }}</v-card-title>
      <v-card-text><v-text-field v-model="linkHref" autofocus :label="t('editor.linkPrompt')" @keyup.enter="applyLink" /></v-card-text>
      <v-card-actions><v-spacer /><v-btn variant="tonal" @click="linkDialog = false">{{ t('common.cancel') }}</v-btn><v-btn color="primary" @click="applyLink">{{ t('common.confirm') }}</v-btn></v-card-actions>
    </v-card>
  </v-dialog>
</template>
