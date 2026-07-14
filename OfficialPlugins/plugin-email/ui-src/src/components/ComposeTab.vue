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
const addAttachment = async () => { try { const value = await files.open(); if (value) compose.attachments.push(value) } catch(value) { error.value=actionable(value,'Selecting attachment') } }
async function prepare() {
  busy.value = true; error.value = ''
  try {
    const result = await invoke<{ confirmation: { confirmationId: string; summary: {label:string;value:string}[]; expiresAt: string; approveMethod:string;rejectMethod:string } }>('email_send_single', {
      accountId: accounts.selectedId, to: compose.to, cc: compose.cc, bcc: compose.bcc, subject: compose.subject,
      plainText: compose.plainText, htmlText: compose.htmlText, attachments: compose.attachments,
    })
    compose.setConfirmation(result.confirmation); dialog.value = true
  } catch (value) { error.value = actionable(value, 'Preparing email') } finally { busy.value = false }
}
async function confirm() { if (!compose.confirmation) return; try { const result=await invoke<{send:{status:string;succeeded:number;failed:number;failedRecipients:string[]}}>('confirm_send', { confirmationId: compose.confirmation.confirmationId }); compose.sendResult=result.send; dialog.value=false } catch(value){ error.value=actionable(value,'Sending email') } }
async function reject() { if (!compose.confirmation) return; try { await invoke('reject_send',{confirmationId:compose.confirmation.confirmationId}); dialog.value=false } catch(value){error.value=actionable(value,'Cancelling send')} }
</script>

<template><section class="panel-grid"><v-card class="surface" variant="flat"><v-card-title>New message</v-card-title><v-card-text>
  <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
  <v-select v-model="accounts.selectedId" :items="accounts.accounts" item-title="email" item-value="id" label="From account" />
  <v-combobox v-model="compose.to" chips multiple label="To" /><v-combobox v-model="compose.cc" chips multiple label="CC" /><v-combobox v-model="compose.bcc" chips multiple label="BCC" />
  <v-text-field v-model="compose.subject" label="Subject" />
  <div class="editor-toolbar"><v-btn size="small" @click="editor?.chain().focus().toggleBold().run()">B</v-btn><v-btn size="small" @click="editor?.chain().focus().toggleBulletList().run()">• List</v-btn></div>
  <EditorContent :editor="editor" class="editor" />
  <div class="attachment-row"><v-chip v-for="item in compose.attachments" :key="item.id">{{ item.name }}</v-chip><v-btn variant="tonal" @click="addAttachment">Attach</v-btn></div>
</v-card-text><v-card-actions><v-spacer/><v-btn color="primary" :loading="busy" :disabled="!accounts.selectedId || !compose.to.length" @click="prepare">Review & send</v-btn></v-card-actions></v-card>
<v-card class="surface preview" variant="flat"><v-card-title>Preview</v-card-title><v-card-text><v-alert v-if="compose.sendResult" :type="compose.sendResult.failed?'warning':'success'" class="mb-4">Sent {{compose.sendResult.succeeded}}, failed {{compose.sendResult.failed}}</v-alert><h3>{{ compose.subject || 'No subject' }}</h3><div v-html="compose.htmlText || '<p>Your message preview appears here.</p>'"/></v-card-text></v-card></section>
<v-dialog v-model="dialog" max-width="560" persistent><v-card><v-card-title>Confirm send</v-card-title><v-card-text><v-list density="compact"><v-list-item v-for="row in compose.confirmation?.summary" :key="row.label" :title="row.label" :subtitle="row.value"/></v-list><p class="hint">Expires {{compose.confirmation?.expiresAt}}</p></v-card-text><v-card-actions><v-spacer/><v-btn @click="reject">Reject</v-btn><v-btn color="primary" @click="confirm">Confirm send</v-btn></v-card-actions></v-card></v-dialog></template>
