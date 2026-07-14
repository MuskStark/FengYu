<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { EditorContent, useEditor } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Placeholder from '@tiptap/extension-placeholder'
import { useAccountsStore } from '../stores/accounts'
import { useComposeStore } from '../stores/compose'
import { actionable, files, invoke } from '../sdk'

const accounts = useAccountsStore(), compose = useComposeStore()
const busy = ref(false), error = ref(''), dialog = ref(false)
const editor = useEditor({ extensions: [StarterKit, Placeholder.configure({ placeholder: 'Write your message…' })], content: '', onUpdate: ({ editor }) => { compose.htmlText = editor.getHTML(); compose.plainText = editor.getText() } })
onBeforeUnmount(() => editor.value?.destroy())
const addAttachment = async () => { const value = await files.open(); if (value) compose.attachments.push(value) }
async function prepare() {
  busy.value = true; error.value = ''
  try {
    const result = await invoke<{ confirmation: { confirmationId: string; summary?: string; expiresAt: string } }>('email_send_single', {
      accountId: accounts.selectedId, to: compose.to, cc: compose.cc, bcc: compose.bcc, subject: compose.subject,
      plainText: compose.plainText, htmlText: compose.htmlText, attachments: compose.attachments,
    })
    compose.setConfirmation({ ...result.confirmation, summary: result.confirmation.summary ?? result.summary }); dialog.value = true
  } catch (value) { error.value = actionable(value, 'Preparing email') } finally { busy.value = false }
}
async function confirm() { if (!compose.confirmation) return; await invoke('confirm_send', { confirmationId: compose.confirmation.confirmationId }); dialog.value = false }
</script>

<template><section class="panel-grid"><v-card class="surface" variant="flat"><v-card-title>New message</v-card-title><v-card-text>
  <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
  <v-select v-model="accounts.selectedId" :items="accounts.accounts" item-title="email" item-value="id" label="From account" />
  <v-combobox v-model="compose.to" chips multiple label="To" /><v-combobox v-model="compose.cc" chips multiple label="CC" /><v-combobox v-model="compose.bcc" chips multiple label="BCC" />
  <v-text-field v-model="compose.subject" label="Subject" />
  <div class="editor-toolbar"><v-btn size="small" icon="mdi-format-bold" @click="editor?.chain().focus().toggleBold().run()"/><v-btn size="small" icon="mdi-format-list-bulleted" @click="editor?.chain().focus().toggleBulletList().run()"/></div>
  <EditorContent :editor="editor" class="editor" />
  <div class="attachment-row"><v-chip v-for="item in compose.attachments" :key="item.id" prepend-icon="mdi-paperclip">{{ item.name }}</v-chip><v-btn variant="tonal" prepend-icon="mdi-paperclip" @click="addAttachment">Attach</v-btn></div>
</v-card-text><v-card-actions><v-spacer/><v-btn color="primary" :loading="busy" :disabled="!accounts.selectedId || !compose.to.length" @click="prepare">Review & send</v-btn></v-card-actions></v-card>
<v-card class="surface preview" variant="flat"><v-card-title>Preview</v-card-title><v-card-text><h3>{{ compose.subject || 'No subject' }}</h3><div v-html="compose.htmlText || '<p>Your message preview appears here.</p>'"/></v-card-text></v-card></section>
<v-dialog v-model="dialog" max-width="560"><v-card><v-card-title>Confirm send</v-card-title><v-card-text>{{ compose.confirmationSummary }}</v-card-text><v-card-actions><v-spacer/><v-btn @click="dialog=false">Cancel</v-btn><v-btn color="primary" @click="confirm">Confirm send</v-btn></v-card-actions></v-card></v-dialog></template>
