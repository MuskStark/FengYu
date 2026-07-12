<script setup lang="ts">
import Sidebar from './Sidebar.vue'
</script>

<template>
  <Sidebar />
  <v-main>
    <router-view v-slot="{ Component }">
      <component :is="Component" />
    </router-view>
  </v-main>
</template>
