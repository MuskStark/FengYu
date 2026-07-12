<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { usePluginsStore } from '@/stores/plugins'
import { useThemeStore } from '@/stores/theme'
import { api } from '@/api/client'
import { i18n } from '@/i18n'
import { loadPlugin, type PluginContext } from '@/mf/loader'
import { vuetify } from '@/plugins/vuetify'

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
      locale: i18n.global.locale.value,
      t: (key: string) => i18n.global.t(key),
      onLocaleChange: (cb: (locale: string) => void) => {
        const unwatch = watch(() => i18n.global.locale.value, (l) => cb(l as string))
        return unwatch
      },
      notify: (msg) => console.info(`[${descriptor.name}]`, msg),
      vuetify, // shared MD3 instance — plugins call app.use(ctx.vuetify)
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
  <div style="display: flex; flex-direction: column; height: 100%">
    <div class="cx-topbar">
      <button class="cx-btn cx-btn--text cx-btn--sm" @click="router.push('/tools')">
        <i class="mdi mdi-arrow-left" />{{ $t('common.back') }}
      </button>
      <span style="font-weight: 600">{{ plugins.byId(props.id)?.name ?? props.id }}</span>
    </div>

    <div v-if="error" class="cx-alert cx-alert--error" style="margin: 16px">
      <div class="cx-alert__body">
        <div style="font-weight: 600; margin-bottom: 4px">{{ $t('plugin.failedTitle') }}</div>
        <div class="mono" style="font-size: 12px; overflow-wrap: anywhere">{{ error }}</div>
        <button class="cx-btn cx-btn--outline cx-btn--sm" style="margin-top: 10px" @click="mountPlugin()">
          {{ $t('common.retry') }}
        </button>
      </div>
    </div>

    <div v-show="!error" ref="host" style="flex: 1 1 auto; min-height: 0; overflow: auto" />
    <div v-if="loading" style="display: flex; justify-content: center; padding: 32px">
      <span class="cx-spin lg" />
    </div>
  </div>
</template>
