import { createRouter, createWebHashHistory, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { isAxiosError } from 'axios'
import { api } from '@/api/client'

const routes: RouteRecordRaw[] = [
  { path: '/setup', name: 'setup', component: () => import('@/views/SetupWizard.vue') },
  { path: '/', name: 'ai', component: () => import('@/views/AiChat.vue') },
  { path: '/tools', name: 'tools', component: () => import('@/views/ToolGrid.vue') },
  { path: '/agent', name: 'agent', component: () => import('@/views/AiAgent.vue') },
  { path: '/flows', name: 'flows', component: () => import('@/views/FlowLibrary.vue') },
  {
    path: '/flows/:id',
    name: 'flow-builder',
    component: () => import('@/views/FlowBuilder.vue'),
  },
  { path: '/plugins', name: 'plugin-market', component: () => import('@/views/PluginMarket.vue') },
  { path: '/store', name: 'store', component: () => import('@/views/StoreView.vue') },
  { path: '/account', name: 'account', component: () => import('@/views/AccountProfile.vue') },
  { path: '/settings', name: 'settings', component: () => import('@/views/Settings.vue') },
  { path: '/about', name: 'about', component: () => import('@/views/About.vue') },
  {
    path: '/plugin/:id',
    name: 'plugin',
    component: () => import('@/views/PluginView.vue'),
    props: true,
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFound.vue'),
  },
]

export const router = createRouter({
  // The packaged Electron shell loads index.html over file://. Hash history
  // keeps every route anchored to that real file, while the browser build
  // retains clean history URLs.
  history:
    typeof window !== 'undefined' && window.fengyu?.desktop
      ? createWebHashHistory()
      : createWebHistory(),
  routes,
})

// The packaged shell already probes setup mode before it creates this renderer.
// Consume that result for the first navigation, then resume live checks so the
// setup wizard can transition normally after its backend restart.
const hasDesktopBridge = typeof window !== 'undefined' && !!window.fengyu
let initialDesktopSetupMode = hasDesktopBridge ? window.fengyu!.setupMode() : null

// Global guard: redirect to /setup when the backend reports uninitialized.
// The setup route itself is always allowed; initialized backends bounce /setup back to /.
// In APP mode the /api/setup/** surface is intentionally not served (it is token-bypassed,
// so it only exists in SETUP mode) — a 404 there confirms APP mode and is remembered so we
// stop re-probing on every navigation. A full reload (e.g. after backend restart) resets it.
let appModeConfirmed = false
router.beforeEach(async (to) => {
  if (to.name === 'setup') return true
  if (initialDesktopSetupMode !== null) {
    const setupMode = initialDesktopSetupMode
    initialDesktopSetupMode = null
    return setupMode ? { name: 'setup' } : true
  }
  if (appModeConfirmed) return true
  try {
    const status = await api.getSetupStatus()
    if (!status.initialized) {
      return { name: 'setup' }
    }
  } catch (err) {
    if (isAxiosError(err) && err.response?.status === 404) {
      appModeConfirmed = true
      return true
    }
    // Backend unreachable — allow navigation; StatusBar surfaces connectivity.
  }
  return true
})
