import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/', name: 'tools', component: () => import('@/views/ToolGrid.vue') },
  { path: '/ai', name: 'ai', component: () => import('@/views/AiChat.vue') },
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
