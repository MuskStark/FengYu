<script setup lang="ts">
import Sidebar from './Sidebar.vue'
import StatusBar from './StatusBar.vue'
</script>

<template>
  <div class="app-root">
    <Sidebar />
    <main class="content">
      <router-view v-slot="{ Component }">
        <component :is="Component" />
      </router-view>
    </main>
    <StatusBar />
  </div>
</template>

<style scoped>
.app-root {
  display: grid;
  grid-template-columns: auto 1fr;
  grid-template-rows: 1fr auto;
  grid-template-areas:
    'sidebar content'
    'statusbar statusbar';
  height: 100%;
  width: 100%;
  background: var(--sk-bg);
}
.content {
  grid-area: content;
  overflow: auto;
  min-width: 0;
}
</style>
