<script setup lang="ts">
import Sidebar from './Sidebar.vue'
</script>

<template>
  <div class="cx-shell">
    <Sidebar />
    <div class="cx-content-column">
      <main class="cx-main">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.cx-shell {
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  height: 100%;
  width: 100%;
  overflow: hidden;
}
.cx-content-column {
  flex: 1 1 auto;
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.cx-main {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
