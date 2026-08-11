<script setup lang="ts">
import { onBeforeMount, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { usePluginsStore } from '@/stores/plugins'
import { useThemeStore } from '@/stores/theme'
import { useSettingsStore } from '@/stores/settings'
import { api } from '@/api/client'
import { makeDesktop } from '@/mf/desktop'
import { pluginAssetUrl } from '@/api/config'
import {
  HOST_CAPABILITIES,
  HOST_MESSAGE_SOURCE,
  HOST_METHODS,
  PROTOCOL_VERSION,
  hostError,
  isPluginMessage,
  type HostError,
} from '@infinia/plugin-sdk/protocol'

const props = defineProps<{ id: string }>()
const { t } = useI18n()
const plugins = usePluginsStore()
const theme = useThemeStore()
const settings = useSettingsStore()
const router = useRouter()
const frame = ref<HTMLIFrameElement | null>(null)
const error = ref<string | null>(null)
const loading = ref(true)
const bridgeListening = ref(false)
const bridgeReady = ref(false)
const frameKey = ref(0)
let pluginHandshakeTimeout: ReturnType<typeof setTimeout> | undefined
const activeInvokes = new Map<string, AbortController>()
const desktop = makeDesktop()
const pluginUrl = () => {
  const entry = plugins.byId(props.id)?.uiEntry
  return entry ? pluginAssetUrl(entry) : undefined
}
const pluginOrigin = () => {
  const url = pluginUrl()
  return url ? new URL(url, window.location.href).origin : undefined
}

function respond(id: string, result?: unknown, error?: HostError) {
  const targetOrigin = pluginOrigin()
  if (!targetOrigin) return
  frame.value?.contentWindow?.postMessage(
    { source: HOST_MESSAGE_SOURCE, type: 'response', protocolVersion: PROTOCOL_VERSION, id, result, error },
    targetOrigin,
  )
}

function clearPluginHandshakeTimeout() {
  if (pluginHandshakeTimeout !== undefined) clearTimeout(pluginHandshakeTimeout)
  pluginHandshakeTimeout = undefined
}

async function onMessage(event: MessageEvent) {
  if (event.source !== frame.value?.contentWindow) return
  if (event.origin !== pluginOrigin()) return
  if (!isPluginMessage(event.data)) return
  const request = event.data
  if (request.type === 'cancel') {
    activeInvokes.get(request.id)?.abort()
    activeInvokes.delete(request.id)
    void api.cancelPluginInvoke(props.id, request.id).catch(() => {})
    return
  }
  const requestId = request.id
  try {
    if (request.method === HOST_METHODS.invoke) {
      const method = String(request.params?.method ?? '')
      const params = (request.params?.params ?? {}) as Record<string, unknown>
      if (!method) throw new Error('rpc.invoke requires a method')
      const controller = new AbortController()
      activeInvokes.set(request.id, controller)
      try {
        respond(request.id, await api.pluginInvoke(props.id, method, params, {
          callId: request.id,
          signal: controller.signal,
        }))
      } finally {
        activeInvokes.delete(request.id)
      }
    } else if (request.method === HOST_METHODS.ready) {
      const descriptor = plugins.byId(props.id)
      respond(request.id, {
        protocolVersion: PROTOCOL_VERSION,
        pluginId: props.id,
        pluginVersion: descriptor?.version ?? '',
        permissions: descriptor?.permissions ?? [],
        theme: theme.theme, locale: settings.language,
        platform: desktop ? 'desktop' : 'web',
        capabilities: HOST_CAPABILITIES,
      })
      bridgeReady.value = true
      loading.value = false
      clearPluginHandshakeTimeout()
    } else if (request.method === HOST_METHODS.notify) {
      console.info(`[${props.id}]`, request.params?.message)
      respond(request.id, true)
    } else if (request.method === HOST_METHODS.filesOpen) {
      if (desktop) {
        const path = await desktop.pickFile((request.params?.filters ?? []) as { name: string; extensions: string[] }[])
        respond(request.id, path ? await api.grantRuntimeNativePath(props.id, path, 'file', 'read') : null)
      } else {
        const input = document.createElement('input')
        input.type = 'file'
        input.accept = ((request.params?.extensions ?? []) as string[]).map(x => `.${x}`).join(',')
        input.onchange = async () => {
          try { respond(requestId, input.files?.[0] ? await api.uploadRuntimeFile(props.id, input.files[0]) : null) }
          catch (e) { respond(requestId, undefined, hostError(e)) }
        }
        input.click()
      }
    } else if (request.method === HOST_METHODS.filesInputDirectory) {
      if (desktop) {
        const path = await desktop.pickDirectory()
        respond(request.id, path ? await api.grantRuntimeNativePath(props.id, path, 'directory', 'read') : null)
      } else {
        const input = document.createElement('input')
        input.type = 'file'
        input.multiple = true
        input.setAttribute('webkitdirectory', '')
        input.onchange = async () => {
          try {
            const selected = Array.from(input.files ?? [])
            respond(requestId, selected.length ? await api.uploadRuntimeDirectory(props.id, selected) : null)
          } catch (e) {
            respond(requestId, undefined, hostError(e))
          }
        }
        input.click()
      }
    } else if (request.method === HOST_METHODS.filesWorkspaceDirectory) {
      if (desktop) {
        const path = await desktop.pickDirectory()
        respond(request.id, path ? await api.grantRuntimeNativePath(props.id, path, 'directory', 'read-write') : null)
      } else {
        const input = document.createElement('input')
        input.type = 'file'
        input.multiple = true
        input.setAttribute('webkitdirectory', '')
        input.onchange = async () => {
          try {
            const selected = Array.from(input.files ?? [])
            respond(requestId, selected.length
              ? await api.uploadRuntimeDirectory(props.id, selected, 'read-write')
              : null)
          } catch (e) {
            respond(requestId, undefined, hostError(e))
          }
        }
        input.click()
      }
    } else if (request.method === HOST_METHODS.filesOutputDirectory) {
      if (desktop) {
        const path = await desktop.pickDirectory()
        respond(request.id, path ? await api.grantRuntimeNativePath(props.id, path, 'directory', 'write') : null)
      } else respond(request.id, await api.createRuntimeOutput(props.id))
    } else if (request.method === HOST_METHODS.filesExport) {
      await api.exportRuntimeOutput(props.id, String(request.params?.id ?? ''))
      respond(request.id, true)
    } else {
      throw new Error(`Unsupported host capability: ${request.method}`)
    }
  } catch (e) {
    if (!(e instanceof DOMException && e.name === 'AbortError')) respond(request.id, undefined, hostError(e))
  }
}

function sendEnvironment() {
  const targetOrigin = pluginOrigin()
  if (!targetOrigin) return
  frame.value?.contentWindow?.postMessage(
    { source: HOST_MESSAGE_SOURCE, type: 'event', protocolVersion: PROTOCOL_VERSION, event: 'environment', data: { theme: theme.theme, locale: settings.language } },
    targetOrigin,
  )
}

function onFrameLoad() {
  sendEnvironment()
  clearPluginHandshakeTimeout()
  if (!bridgeReady.value) {
    pluginHandshakeTimeout = setTimeout(() => {
      loading.value = false
      error.value = t('plugin.handshakeTimeout')
    }, 8_000)
  }
}

function retryPlugin() {
  clearPluginHandshakeTimeout()
  error.value = null
  loading.value = true
  bridgeReady.value = false
  frameKey.value += 1
}

onBeforeMount(() => {
  window.addEventListener('message', onMessage)
  bridgeListening.value = true
})
onMounted(async () => {
  if (!plugins.plugins.length) await plugins.load()
  if (!plugins.byId(props.id)) error.value = t('plugin.unknown', { id: props.id })
})
watch(() => theme.theme, sendEnvironment)
watch(() => settings.language, sendEnvironment)
watch(() => props.id, retryPlugin)
onBeforeUnmount(() => {
  activeInvokes.forEach(controller => controller.abort())
  activeInvokes.clear()
  clearPluginHandshakeTimeout()
  window.removeEventListener('message', onMessage)
})
</script>

<template>
  <div class="plugin-host">
    <div class="cx-topbar">
      <button class="cx-btn cx-btn--text cx-btn--sm" @click="router.push('/tools')"><i class="mdi mdi-arrow-left" />{{ t('common.back') }}</button>
      <span style="font-weight: 600">{{ plugins.byId(props.id)?.name ?? props.id }}</span>
    </div>
    <div v-if="error" class="cx-alert cx-alert--error" style="margin: 16px">
      <div style="font-weight: 650; margin-bottom: 4px">{{ t('plugin.failedTitle') }}</div>
      <div>{{ error }}</div>
      <button class="cx-btn cx-btn--outline cx-btn--sm" style="margin-top: 12px" @click="retryPlugin">{{ t('plugin.retry') }}</button>
    </div>
    <div v-else class="frame-wrap">
      <iframe
        v-if="bridgeListening"
        :key="frameKey"
        ref="frame"
        class="plugin-frame"
        :src="pluginUrl()"
        sandbox="allow-scripts allow-same-origin allow-forms allow-downloads"
        referrerpolicy="no-referrer"
        @load="onFrameLoad"
      />
      <div v-if="loading" class="frame-loading"><span class="cx-spin lg" /></div>
    </div>
  </div>
</template>

<style scoped>
.plugin-host,.frame-wrap { flex: 1; min-height: 0; display: flex; flex-direction: column; position: relative; }
.plugin-frame { flex: 1; width: 100%; border: 0; background: rgb(var(--v-theme-background)); }
.frame-loading { position: absolute; inset: 0; display: grid; place-items: center; background: rgb(var(--v-theme-background)); }
</style>
