<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { usePluginsStore } from '@/stores/plugins'
import { useThemeStore } from '@/stores/theme'
import { api } from '@/api/client'
import { loadPlugin, type PluginContext } from '@/mf/loader'

const props = defineProps<{ id: string }>()

const { t } = useI18n()
const plugins = usePluginsStore()
const theme = useThemeStore()
const router = useRouter()

const host = ref<HTMLElement | null>(null)
const error = ref<string | null>(null)
const loading = ref(false)
let unmount: (() => void) | null = null

function teardown() {
  if (unmount) {
    try {
      unmount()
    } catch {
      /* ignore plugin teardown errors */
    }
    unmount = null
  }
  if (host.value) host.value.innerHTML = ''
}

async function mountPlugin() {
  teardown()
  error.value = null

  const descriptor = plugins.byId(props.id)
  if (!descriptor) {
    error.value = t('plugin.unknown', { id: props.id })
    return
  }
  const el = host.value
  if (!el) return

  loading.value = true
  try {
    const mod = await loadPlugin(descriptor.uiEntry)
    const ctx: PluginContext = {
      api: {
        invoke: (action, args = {}) => api.pluginInvoke(descriptor.id, action, args),
      },
      theme: theme.theme,
      onThemeChange: (cb) => theme.onChange(cb),
      i18n: (key) => key,
      notify: (msg) => console.info(`[${descriptor.name}]`, msg),
    }
    unmount = mod.mount(el, ctx)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  if (plugins.plugins.length === 0) await plugins.load()
  await mountPlugin()
})

watch(
  () => props.id,
  () => {
    void mountPlugin()
  },
)

onBeforeUnmount(teardown)
</script>

<template>
  <div class="plugin-page">
    <div class="bar">
      <button class="sk-btn-secondary" @click="router.push('/')">← {{ $t('common.back') }}</button>
      <span class="title">{{ plugins.byId(props.id)?.name ?? props.id }}</span>
    </div>

    <div v-if="error" class="error-card">
      <div class="error-title">{{ $t('plugin.failedTitle') }}</div>
      <div class="error-msg">{{ error }}</div>
      <button class="sk-btn-primary" @click="mountPlugin()">{{ $t('common.retry') }}</button>
    </div>

    <div v-show="!error" ref="host" class="mount" />
    <div v-if="loading" class="loading">{{ $t('plugin.loading') }}</div>
  </div>
</template>

<style scoped>
.plugin-page {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--sk-border);
}
.title {
  font-weight: 600;
  color: var(--sk-text);
}
.mount {
  flex: 1;
  overflow: auto;
  min-height: 0;
}
.loading {
  padding: 20px;
  color: var(--sk-text-secondary);
}
.error-card {
  margin: 24px;
  padding: 20px;
  background: var(--sk-danger-soft);
  border: 1px solid var(--sk-danger);
  border-radius: 10px;
  color: var(--sk-text);
}
.error-title {
  font-weight: 600;
  color: var(--sk-danger);
  margin-bottom: 8px;
}
.error-msg {
  font-family: monospace;
  font-size: 12px;
  margin-bottom: 12px;
  overflow-wrap: anywhere;
}
</style>
