<script setup lang="ts">
import Sidebar from './Sidebar.vue'
</script>

<template>
  <div class="cx-shell">
    <Sidebar />
    <main class="cx-main">
      <router-view v-slot="{ Component }">
        <component :is="Component" />
      </router-view>
    </main>
  </div>
</template>

<style scoped>
.cx-shell {
  display: flex;
  height: 100%;
  width: 100%;
  overflow: hidden;
}
.cx-main {
  flex: 1 1 auto;
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
