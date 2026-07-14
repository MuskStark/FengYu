<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useContactsStore } from '../stores/contacts'
import { actionable, invoke } from '../sdk'
const store=useContactsStore(), email=ref(''), nickname=ref(''), tagName=ref(''), error=ref('')
onMounted(()=>store.load().catch(value=>error.value=actionable(value,'Loading address book')))
async function addContact(){try{await invoke('email_contact_save',{email:email.value,nickname:nickname.value});email.value='';nickname.value='';await store.load()}catch(value){error.value=actionable(value,'Saving contact')}}
async function addTag(){try{await invoke('email_tag_save',{name:tagName.value});tagName.value='';await store.load()}catch(value){error.value=actionable(value,'Saving tag')}}
</script>
<template><section class="panel-grid"><v-card class="surface" variant="flat"><v-card-title>Contacts</v-card-title><v-card-text><v-alert v-if="error" type="error" class="mb-4">{{error}}</v-alert><div class="inline-fields"><v-text-field v-model="store.query" label="Search" prepend-inner-icon="mdi-magnify" @keyup.enter="store.load"/><v-btn @click="store.load">Search</v-btn></div><v-list lines="two"><v-list-item v-for="item in store.contacts" :key="item.id" :title="item.nickname||item.email" :subtitle="item.email" prepend-icon="mdi-account-circle"/></v-list></v-card-text></v-card>
<div><v-card class="surface mb-4" variant="flat"><v-card-title>Add contact</v-card-title><v-card-text><v-text-field v-model="email" label="Email"/><v-text-field v-model="nickname" label="Nickname"/></v-card-text><v-card-actions><v-spacer/><v-btn color="primary" @click="addContact">Save</v-btn></v-card-actions></v-card><v-card class="surface" variant="flat"><v-card-title>Tags</v-card-title><v-card-text><v-chip v-for="tag in store.tags" :key="tag.id" class="mr-2">{{tag.name}}</v-chip><div class="inline-fields mt-4"><v-text-field v-model="tagName" label="New tag"/><v-btn @click="addTag">Add</v-btn></div></v-card-text></v-card></div></section></template>
