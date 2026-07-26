import { createRouter, createWebHashHistory, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { api } from '@/api/client'

const routes: RouteRecordRaw[] = [
  { path: '/setup', name: 'setup', component: () => import('@/views/SetupWizard.vue') },
  { path: '/', name: 'ai', component: () => import('@/views/AiChat.vue') },
  { path: '/tools', name: 'tools', component: () => import('@/views/ToolGrid.vue') },
  { path: '/agent', name: 'agent', component: () => import('@/views/AiAgent.vue') },
  { path: '/plugins', name: 'plugin-market', component: () => import('@/views/PluginMarket.vue') },
  { path: '/settings', name: 'settings', component: () => import('@/views/Settings.vue') },
  { path: '/about', name: 'about', component: () => import('@/views/About.vue') },
  {
    path: '/plugin/:id',
    name: 'plugin',
    component: () => import('@/views/PluginView.vue'),
    props: true,
  },
]

export const router = createRouter({
  // The packaged Electron shell loads index.html over file://. Hash history
  // keeps every route anchored to that real file, while the browser build
  // retains clean history URLs.
  history: window.fengyu?.desktop ? createWebHashHistory() : createWebHistory(),
  routes,
})

// Global guard: redirect to /setup when the backend reports uninitialized.
// The setup route itself is always allowed; initialized backends bounce /setup back to /.
router.beforeEach(async (to) => {
  if (to.name === 'setup') return true
  try {
    const status = await api.getSetupStatus()
    if (!status.initialized) {
      return { name: 'setup' }
    }
  } catch {
    // Backend unreachable — allow navigation; StatusBar surfaces connectivity.
  }
  return true
})
