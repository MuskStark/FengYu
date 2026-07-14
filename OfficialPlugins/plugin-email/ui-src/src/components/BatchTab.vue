<script setup lang="ts">
import { ref } from 'vue'
import { useAccountsStore } from '../stores/accounts'
import { useComposeStore } from '../stores/compose'
import { useContactsStore } from '../stores/contacts'
import { actionable, files, invoke } from '../sdk'
const accounts = useAccountsStore(), contacts = useContactsStore(), compose = useComposeStore()
const mode = ref<'TAGS'|'FILENAME'>('TAGS'), directory = ref<{id:string;name:string}|null>(null), dialog = ref(false), error = ref('')
async function chooseDirectory() { directory.value = await files.inputDirectory() }
async function prepare() { try { const result = await invoke<{confirmation:{confirmationId:string;summary?:string;expiresAt:string}}>('email_send_batch', { mode: mode.value, accountId: accounts.selectedId, tagIds: contacts.selectedTagIds, inputDirectory: directory.value, subject: compose.subject, plainText: compose.plainText, htmlText: compose.htmlText }); compose.setConfirmation({...result.confirmation, summary: result.confirmation.summary ?? result.summary}); dialog.value=true } catch(value){ error.value=actionable(value,'Preparing batch') } }
async function confirm(){ if(compose.confirmation) await invoke('confirm_send',{confirmationId:compose.confirmation.confirmationId}); dialog.value=false }
</script>
<template><v-card class="surface" variant="flat"><v-card-title>Batch send</v-card-title><v-card-text><v-alert v-if="error" type="error" class="mb-4">{{error}}</v-alert><v-btn-toggle v-model="mode" mandatory color="primary"><v-btn value="TAGS">Address-book tags</v-btn><v-btn value="FILENAME">Filename suffix</v-btn></v-btn-toggle>
<template v-if="mode==='TAGS'"><v-select v-model="contacts.selectedTagIds" :items="contacts.tags" item-title="name" item-value="id" label="Recipient tags" multiple chips/><p class="hint">{{contacts.recipientPreview.length}} resolved recipients: {{contacts.recipientPreview.join(', ')||'select tags to preview'}}</p></template>
<template v-else><v-btn prepend-icon="mdi-folder-open" variant="tonal" @click="chooseDirectory">Choose attachment directory</v-btn><p class="hint">{{directory?.name||'Each filename stem becomes a recipient key.'}}</p></template>
<v-text-field v-model="compose.subject" label="Subject template"/><v-textarea v-model="compose.plainText" label="Message template"/></v-card-text><v-card-actions><v-spacer/><v-btn color="primary" @click="prepare">Review batch</v-btn></v-card-actions></v-card>
<v-dialog v-model="dialog" max-width="560"><v-card><v-card-title>Confirm batch</v-card-title><v-card-text>{{compose.confirmationSummary}}</v-card-text><v-card-actions><v-spacer/><v-btn @click="dialog=false">Cancel</v-btn><v-btn color="primary" @click="confirm">Confirm batch</v-btn></v-card-actions></v-card></v-dialog></template>
