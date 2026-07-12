import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { api } from '@/api/client'

const routes: RouteRecordRaw[] = [
  { path: '/setup', name: 'setup', component: () => import('@/views/SetupWizard.vue') },
  { path: '/', name: 'ai', component: () => import('@/views/AiChat.vue') },
  { path: '/tools', name: 'tools', component: () => import('@/views/ToolGrid.vue') },
  { path: '/agent', name: 'agent', component: () => import('@/views/AiAgent.vue') },
  { path: '/settings', name: 'settings', component: () => import('@/views/Settings.vue') },
  {
    path: '/plugin/:id',
    name: 'plugin',
    component: () => import('@/views/PluginView.vue'),
    props: true,
  },
]

export const router = createRouter({
  history: createWebHistory(),
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
