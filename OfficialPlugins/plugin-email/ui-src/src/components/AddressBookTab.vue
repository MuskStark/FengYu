<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useContactsStore } from '../stores/contacts'
import { actionable, invoke } from '../sdk'
const store=useContactsStore(), contactId=ref<number>(), email=ref(''), nickname=ref(''), tagName=ref(''), selectedContacts=ref<number[]>([]), assignTagIds=ref<number[]>([]), error=ref('')
onMounted(()=>store.load().catch(value=>error.value=actionable(value,'Loading address book')))
function edit(item:{id:number;email:string;nickname?:string}){contactId.value=item.id;email.value=item.email;nickname.value=item.nickname??''}
function reset(){contactId.value=undefined;email.value='';nickname.value=''}
async function run(action:string,task:()=>Promise<unknown>){try{await task();await store.load()}catch(value){error.value=actionable(value,action)}}
const saveContact=()=>run('Saving contact',async()=>{await invoke('email_contact_save',{id:contactId.value,email:email.value,nickname:nickname.value});reset()})
const deleteContact=(id:number)=>run('Deleting contact',()=>invoke('email_contact_delete',{id}))
const addTag=()=>run('Saving tag',async()=>{await invoke('email_tag_save',{name:tagName.value});tagName.value=''})
const deleteTag=(id:number)=>run('Deleting tag',()=>invoke('email_tag_delete',{id}))
const assign=()=>run('Assigning tags',()=>invoke('email_tags_assign',{contactIds:selectedContacts.value,tagIds:assignTagIds.value}))
</script>
<template><section class="panel-grid"><v-card class="surface" variant="flat"><v-card-title>Contacts</v-card-title><v-card-text><v-alert v-if="error" type="error" class="mb-4">{{error}}</v-alert><div class="inline-fields"><v-text-field v-model="store.query" label="Search" prepend-inner-icon="mdi-magnify" @keyup.enter="store.load"/><v-btn @click="store.load">Search</v-btn></div><v-list lines="two"><v-list-item v-for="item in store.contacts" :key="item.id" :title="item.nickname||item.email" :subtitle="item.email" prepend-icon="mdi-account-circle" @click="edit(item)"><template #prepend><v-checkbox-btn v-model="selectedContacts" :value="item.id" @click.stop/></template><template #append><v-btn icon="mdi-delete" variant="text" color="error" @click.stop="deleteContact(item.id)"/></template></v-list-item></v-list><div class="inline-fields mt-4"><v-select v-model="assignTagIds" :items="store.tags" item-title="name" item-value="id" multiple chips label="Assign tags to selected"/><v-btn :disabled="!selectedContacts.length" @click="assign">Assign</v-btn></div></v-card-text></v-card>
<div><v-card class="surface mb-4" variant="flat"><v-card-title>{{contactId?'Edit':'Add'}} contact</v-card-title><v-card-text><v-text-field v-model="email" label="Email"/><v-text-field v-model="nickname" label="Nickname"/></v-card-text><v-card-actions><v-btn v-if="contactId" @click="reset">New</v-btn><v-spacer/><v-btn color="primary" @click="saveContact">Save</v-btn></v-card-actions></v-card><v-card class="surface" variant="flat"><v-card-title>Tags</v-card-title><v-card-text><v-chip v-for="tag in store.tags" :key="tag.id" class="mr-2 mb-2" closable @click:close="deleteTag(tag.id)">{{tag.name}}</v-chip><div class="inline-fields mt-4"><v-text-field v-model="tagName" label="New tag"/><v-btn @click="addTag">Add</v-btn></div></v-card-text></v-card></div></section></template>
