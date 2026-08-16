<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import { api } from '@/api/client'
import type {
  AiConfigTestRequest,
  AiConfigTestResult,
  AiMode,
  LanguageName,
  LogLevel,
  McpPrompt,
  McpResource,
  McpStatus,
  McpServer,
  McpServerRequest,
  McpTransportType,
  PartialAiSettings,
  PluginDbProvisionResult,
  ProcessIsolationStatus,
  ThemeName,
} from '@/api/types'

const { t } = useI18n()
const router = useRouter()
const settings = useSettingsStore()
const mcpStatus = ref<McpStatus | null>(null)
const mcpServers = ref<McpServer[]>([])
const mcpSelectedId = ref<string | null>(null)
const mcpSaving = ref(false)
const mcpTesting = ref<string | null>(null)
const mcpError = ref<string | null>(null)
const mcpView = ref<'list' | 'detail'>('list')
const mcpQuery = ref('')
const mcpFilter = ref<'all' | 'enabled' | 'disabled' | 'stdio' | 'sse' | 'streamableHttp'>('all')
const mcpTab = ref<'general' | 'description' | 'tools' | 'prompts' | 'resources' | 'logs'>('general')
const mcpToolQuery = ref('')
const mcpPrompts = ref<McpPrompt[]>([])
const mcpResources = ref<McpResource[]>([])
const mcpDetailsLoading = ref(false)
const mcpCallTool = ref('')
const mcpCallArguments = ref('{}')
const mcpCalling = ref(false)
const mcpCallResult = ref<string | null>(null)
const mcpForm = ref({
  name: '', type: 'STDIO' as McpTransportType, command: '', args: '', url: '', endpoint: '',
  env: '', headers: '', enabled: true,
})
const isolationStatus = ref<ProcessIsolationStatus | null>(null)

// ── AI guard: permission rules + lifecycle hooks ─────────────────────────
const guardSaving = ref(false)
const guardError = ref<string | null>(null)
const localHooksJson = ref('[]')
const HOOKS_PLACEHOLDER = '[{"name":"audit","event":"post_tool_use","matcher":".*","type":"command","command":"logger -t fengyu","timeoutSeconds":5}]'

watch(() => settings.hooksJson, (next) => { localHooksJson.value = next }, { immediate: true })

// Placeholders live in the script: HTML entities like &#10; are not decoded inside
// Vue expression bindings, which breaks the template compiler.
const RULE_PLACEHOLDERS: Record<'allow' | 'ask' | 'deny', string> = {
  allow: 'Command(git status)\nEffect(read)',
  ask: 'Command(git push*)\nTool(browser_*)',
  deny: 'Tool(computer_*)\nWebFetch(domain:internal.example.com)',
}

function setRules(kind: 'allow' | 'ask' | 'deny', event: Event) {
  const lines = (event.target as HTMLTextAreaElement).value
    .split('\n').map((line) => line.trim()).filter(Boolean)
  settings.permissionRules = { ...settings.permissionRules, [kind]: lines }
}

async function saveRules() {
  guardSaving.value = true
  guardError.value = null
  try {
    await settings.savePermissionRules()
  } catch (e) {
    guardError.value = e instanceof Error ? e.message : String(e)
  } finally {
    guardSaving.value = false
  }
}

async function saveHooks() {
  guardSaving.value = true
  guardError.value = null
  try {
    await settings.saveHooks(localHooksJson.value)
  } catch (e) {
    guardError.value = e instanceof Error ? e.message : String(e)
  } finally {
    guardSaving.value = false
  }
}
const showUnsandboxedConfirm = ref(false)
const showDbProvisionConfirm = ref(false)
const dbProvisionTargetId = ref<string | null>(null)
const dbPlugins = ref<Array<PluginDbProvisionResult & { name: string }>>([])
const dbProvisioning = ref<string | null>(null)
const dbError = ref<string | null>(null)

onMounted(() => {
  if (!settings.loaded) void settings.load().catch(() => {})
  if (!settings.aiLoaded) void settings.loadAi().catch(() => {})
  void api.mcpStatus().then((value) => {
    mcpStatus.value = value
    // Older developer-mode backends expose the diagnostics endpoint but not
    // the runtime management endpoints. Avoid a noisy 404 while Settings is
    // still usable during a backend restart/rebuild.
    if (value.dynamicManagement === true) void loadMcpServers()
  }).catch(() => {})
  void api.processIsolationStatus()
    .then((value) => { isolationStatus.value = value })
    .catch(() => {})
  void loadDbPlugins()
})

const themeItems: { title: string; value: ThemeName }[] = [
  { title: t('settings.dark'), value: 'dark' },
  { title: t('settings.light'), value: 'light' },
]
const languageItems: { title: string; value: LanguageName }[] = [
  { title: t('settings.english'), value: 'en' },
  { title: t('settings.chinese'), value: 'zh' },
]
const logLevelItems: LogLevel[] = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'OFF']

const aiForm = ref({
  mode: 'local' as AiMode,
  openai: { endpoint: '', apiKey: '', model: '' },
  anthropic: { endpoint: '', apiKey: '', model: '' },
  deepseek: { endpoint: '', apiKey: '', model: '' },
  ollama: { baseUrl: '', model: '' },
  temperature: 0.7,
  topP: 0.9,
  maxTokens: 2048,
  maxToolRounds: 50,
  contextWindowTokens: 32768,
  systemPrompt: '',
})
const showKey = ref<Record<string, boolean>>({})
const testing = ref(false)
const testResult = ref<AiConfigTestResult | null>(null)
const saved = ref(false)

// ── Update channel proxy ───────────────────────────────────────────────
const proxyUrl = ref('')
const proxySaved = ref(false)
const proxyError = ref<string | null>(null)

watch(() => settings.updateApiBase, (v) => { proxyUrl.value = v ?? '' }, { immediate: true })

// Left-nav section switching (in-page, no new routes).
type SectionId = 'providers' | 'generate' | 'appearance' | 'runtime' | 'mcp' | 'database' | 'update'
const activeSection = ref<SectionId>('appearance')
const settingsQuery = ref('')
type NavGroup = 'ai' | 'personalize' | 'system'
const groupLabel = (g: NavGroup) =>
  g === 'ai' ? t('settings.groupAI') : g === 'personalize' ? t('settings.groupPersonalize') : t('settings.groupSystem')
const sections = computed(() => [
  { id: 'providers' as const, icon: 'mdi-robot-outline', label: t('aiSettings.providers'), group: 'ai' as NavGroup },
  { id: 'generate' as const, icon: 'mdi-tune-vertical', label: t('aiSettings.generate'), group: 'ai' as NavGroup },
  { id: 'appearance' as const, icon: 'mdi-palette-outline', label: t('settings.general'), group: 'personalize' as NavGroup },
  { id: 'runtime' as const, icon: 'mdi-shield-lock-outline', label: t('settings.runtimeSecurity'), group: 'system' as NavGroup },
  { id: 'mcp' as const, icon: 'mdi-connection', label: 'MCP', group: 'system' as NavGroup },
  { id: 'database' as const, icon: 'mdi-database-lock-outline', label: t('settings.pluginDbSection'), group: 'system' as NavGroup },
  { id: 'update' as const, icon: 'mdi-update', label: t('settings.updateChannelSection'), group: 'system' as NavGroup },
])
type NavRow =
  | { type: 'group'; label: string }
  | { type: 'item'; id: SectionId; icon: string; label: string }
const navRows = computed<NavRow[]>(() => {
  const rows: NavRow[] = []
  let last = ''
  for (const s of sections.value) {
    if (s.group !== last) {
      rows.push({ type: 'group', label: groupLabel(s.group) })
      last = s.group
    }
    rows.push({ type: 'item', id: s.id, icon: s.icon, label: s.label })
  }
  return rows
})
const visibleNavRows = computed<NavRow[]>(() => {
  const query = settingsQuery.value.trim().toLowerCase()
  if (!query) return navRows.value
  const matches = new Set(sections.value
    .filter((section) => `${section.label} ${groupLabel(section.group)}`.toLowerCase().includes(query))
    .map((section) => section.id))
  return navRows.value.filter((row) => row.type === 'item' && matches.has(row.id))
})

function backToApp() {
  void router.push('/')
}

// ── Provider master-detail ─────────────────────────────────────────────
type ProviderId = 'openai' | 'anthropic' | 'deepseek'
const providerList: { id: ProviderId; label: string; initial: string; color: string; domain: string }[] = [
  { id: 'openai', label: t('aiSettings.modeOpenai'), initial: 'O', color: '#10a37f', domain: 'api.openai.com' },
  { id: 'anthropic', label: t('aiSettings.modeAnthropic'), initial: 'A', color: '#c96342', domain: 'api.anthropic.com' },
  { id: 'deepseek', label: t('aiSettings.modeDeepseek'), initial: 'D', color: '#4d6bfe', domain: 'api.deepseek.com' },
]
const selectedProvider = ref<ProviderId>(
  ['openai', 'anthropic', 'deepseek'].includes(settings.aiSettings?.mode ?? '')
    ? (settings.aiSettings!.mode as ProviderId)
    : 'openai',
)
// selectedProvider is initialized before the AI config loads (mode unknown → openai).
// Once the config arrives, re-point the selection to the real active provider (once),
// so the list highlight, the green "active" dot, and the detail pane all agree on load.
let providersInitialized = false
const providerQuery = ref('')
const filteredProviders = computed(() => {
  const q = providerQuery.value.trim().toLowerCase()
  if (!q) return providerList
  return providerList.filter((p) =>
    p.label.toLowerCase().includes(q) || p.id.includes(q) || p.domain.includes(q),
  )
})
const selectedProviderMeta = computed(() => providerList.find((p) => p.id === selectedProvider.value)!)
const providerActive = computed(() => aiForm.value.mode === selectedProvider.value)
// OpenAI-compatible providers expect /v1 in the base URL; Anthropic does not.
const selectedNeedsV1 = computed(() => selectedProvider.value === 'openai' || selectedProvider.value === 'deepseek')
function activateProvider(id: ProviderId) {
  aiForm.value.mode = id
}
async function copyText(text: string) {
  try { await navigator.clipboard.writeText(text) } catch { /* clipboard unavailable */ }
}

// ── Models card ────────────────────────────────────────────────────────
// The backend stores a single `model` per provider; the card presents it as a
// one-row list with capability filtering, mirroring the multi-model mockup.
type ModelCap = 'text' | 'vision' | 'reasoning'
const modelFilters = [
  { id: 'all' as const, label: t('aiSettings.filterAll') },
  { id: 'text' as const, label: t('aiSettings.filterText') },
  { id: 'vision' as const, label: t('aiSettings.filterVision') },
  { id: 'reasoning' as const, label: t('aiSettings.filterReasoning') },
]
const modelFilter = ref<'all' | ModelCap>('all')
const currentModelCap = ref<ModelCap>('text')
const modelRows = computed(() => {
  const name = aiForm.value[selectedProvider.value].model.trim()
  return name ? [{ name, cap: currentModelCap.value }] : []
})
const filteredModelRows = computed(() =>
  modelFilter.value === 'all'
    ? modelRows.value
    : modelRows.value.filter((m) => m.cap === modelFilter.value),
)
const addingModel = ref(false)
const newModelName = ref('')
const newModelCap = ref<ModelCap>('text')
function commitModel() {
  const n = newModelName.value.trim()
  if (n) {
    aiForm.value[selectedProvider.value].model = n
    currentModelCap.value = newModelCap.value
  }
  newModelName.value = ''
  addingModel.value = false
}
function removeModel() {
  aiForm.value[selectedProvider.value].model = ''
}
function capLabel(c: ModelCap) {
  return c === 'text' ? t('aiSettings.capText')
    : c === 'vision' ? t('aiSettings.capVision')
      : t('aiSettings.capReasoning')
}
function capChipClass(c: ModelCap) {
  return c === 'text' ? 'cx-chip--success' : ''
}

function syncFormFromStore() {
  const s = settings.aiSettings
  if (!s) return
  aiForm.value.mode = s.mode
  aiForm.value.openai = { endpoint: s.openai.endpoint, apiKey: s.openai.apiKey, model: s.openai.model }
  aiForm.value.anthropic = { endpoint: s.anthropic.endpoint, apiKey: s.anthropic.apiKey, model: s.anthropic.model }
  aiForm.value.deepseek = { endpoint: s.deepseek.endpoint, apiKey: s.deepseek.apiKey, model: s.deepseek.model }
  aiForm.value.ollama = { baseUrl: s.ollama.baseUrl, model: s.ollama.model }
  aiForm.value.temperature = s.temperature
  aiForm.value.topP = s.topP
  aiForm.value.maxTokens = s.maxTokens
  aiForm.value.maxToolRounds = s.maxToolRounds
  aiForm.value.contextWindowTokens = s.contextWindowTokens
  aiForm.value.systemPrompt = s.systemPrompt
}

function applyLoadedSettings() {
  const s = settings.aiSettings
  if (!s) return
  syncFormFromStore()
  if (!providersInitialized) {
    providersInitialized = true
    if (s.mode === 'openai' || s.mode === 'anthropic' || s.mode === 'deepseek') {
      selectedProvider.value = s.mode
    }
  }
}
watch(() => settings.aiSettings, () => applyLoadedSettings())
if (settings.aiSettings) applyLoadedSettings()

// Process-isolation badge state.
const isolationChipClass = computed(() => {
  const s = isolationStatus.value
  if (!s) return ''
  if (s.compatibilityMode) return 'cx-chip--warn'
  return 'cx-chip--success'
})
const isolationChipLabel = computed(() => {
  const s = isolationStatus.value
  if (!s) return ''
  if (s.compatibilityMode) return t('settings.compatibilityApproval')
  if (s.sandboxed) return t('settings.sandboxActive', { backend: s.backend })
  if (s.reduced) return t('settings.sandboxReduced', { backend: s.backend })
  return t('settings.compatibilityApproval')
})

// Computer use (AI screen control) badge + guarded enable.
const showComputerUseConfirm = ref(false)
const computerUseChipClass = computed(() => {
  const status = settings.computerUse
  if (!status) return ''
  if (!status.available) return 'cx-chip--error'
  return settings.computerUseEnabled ? 'cx-chip--success' : 'cx-chip--warn'
})
const computerUseChipLabel = computed(() => {
  const status = settings.computerUse
  if (!status) return ''
  if (!status.available) return t('settings.computerUseUnavailableShort')
  return settings.computerUseEnabled
    ? t('settings.computerUseReady')
    : t('settings.computerUseDisabledShort')
})

function requestEnableComputerUse() {
  if (settings.computerUseEnabled) return
  showComputerUseConfirm.value = true
}

// Runtime toggles are optimistic in the store (they revert on failure); surface the
// rethrown error next to the toggles so the confirm dialogs and switches never fail silently.
const runtimeError = ref<string | null>(null)
async function runToggle(action: () => Promise<void>) {
  runtimeError.value = null
  try {
    await action()
  } catch (e: unknown) {
    runtimeError.value = e instanceof Error ? e.message : String(e)
  }
}

async function confirmEnableComputerUse() {
  showComputerUseConfirm.value = false
  await runToggle(() => settings.setComputerUseEnabled(true))
}

// Persist every provider's config (not just the active one) so editing any
// provider in the master-detail survives a save.
const saveError = ref<string | null>(null)
async function onSave() {
  const partial: PartialAiSettings = {
    mode: aiForm.value.mode,
    openai: { endpoint: aiForm.value.openai.endpoint, apiKey: aiForm.value.openai.apiKey, model: aiForm.value.openai.model },
    anthropic: { endpoint: aiForm.value.anthropic.endpoint, apiKey: aiForm.value.anthropic.apiKey, model: aiForm.value.anthropic.model },
    deepseek: { endpoint: aiForm.value.deepseek.endpoint, apiKey: aiForm.value.deepseek.apiKey, model: aiForm.value.deepseek.model },
    ollama: { baseUrl: aiForm.value.ollama.baseUrl, model: aiForm.value.ollama.model },
    temperature: aiForm.value.temperature,
    topP: aiForm.value.topP,
    maxTokens: aiForm.value.maxTokens,
    maxToolRounds: aiForm.value.maxToolRounds,
    contextWindowTokens: aiForm.value.contextWindowTokens,
    systemPrompt: aiForm.value.systemPrompt,
  }
  saveError.value = null
  try {
    await settings.updateAi(partial)
    syncFormFromStore()
    saved.value = true
    setTimeout(() => { saved.value = false }, 2000)
  } catch (e: unknown) {
    saveError.value = e instanceof Error ? e.message : String(e)
  }
}

function requestEnableUnsandboxed() {
  if (settings.unsandboxedPlugins) return
  showUnsandboxedConfirm.value = true
}

async function confirmEnableUnsandboxed() {
  showUnsandboxedConfirm.value = false
  await runToggle(() => settings.setUnsandboxedPlugins(true))
}

async function loadDbPlugins() {
  try {
    const all = await api.getPlugins()
    const dbOnes = all.filter((p) => p.permissions?.includes('database'))
    const results = await Promise.all(
      dbOnes.map(async (p) => {
        const status = await api.pluginDbStatus(p.id).catch(() => null)
        return {
          provisioned: status?.provisioned ?? false,
          status: status?.status ?? 'unknown',
          pluginId: p.id,
          name: p.name,
        }
      }),
    )
    dbPlugins.value = results
  } catch {
    dbPlugins.value = []
  }
}

function requestDbProvision(pluginId: string) {
  dbProvisionTargetId.value = pluginId
  dbError.value = null
  showDbProvisionConfirm.value = true
}

async function confirmDbProvision() {
  const id = dbProvisionTargetId
  showDbProvisionConfirm.value = false
  if (!id.value) return
  dbProvisioning.value = id.value
  dbError.value = null
  try {
    const result = await api.provisionPluginDb(id.value)
    if (!result.provisioned) {
      dbError.value = result.status
    } else {
      void loadDbPlugins()
    }
  } catch (e: unknown) {
    dbError.value = e instanceof Error ? e.message : String(e)
  } finally {
    dbProvisioning.value = null
  }
}

// Test the currently-selected provider's credentials.
async function onTest() {
  testing.value = true
  testResult.value = null
  try {
    const mode = selectedProvider.value
    const p = aiForm.value[mode]
    const req: AiConfigTestRequest = { mode, endpoint: p.endpoint, apiKey: p.apiKey, model: p.model }
    testResult.value = await settings.testAi(req)
  } catch (e: unknown) {
    testResult.value = { success: false, error: e instanceof Error ? e.message : String(e) }
  } finally {
    testing.value = false
  }
}

async function onSaveProxy() {
  proxyError.value = null
  try {
    await settings.setUpdateApiBase(proxyUrl.value.trim())
    proxySaved.value = true
    setTimeout(() => { proxySaved.value = false }, 2000)
  } catch (e: unknown) {
    proxyError.value = e instanceof Error ? e.message : String(e)
  }
}

function resetMcpForm() {
  mcpSelectedId.value = null
  mcpForm.value = { name: '', type: 'STDIO', command: '', args: '', url: '', endpoint: '', env: '', headers: '', enabled: true }
  mcpError.value = null
  mcpCallResult.value = null
  mcpTab.value = 'general'
  mcpView.value = 'detail'
}

/** Pre-fill the official mcp-chrome Streamable HTTP endpoint. */
function presetMcpChrome() {
  mcpSelectedId.value = null
  mcpForm.value = {
    name: 'mcp-chrome', type: 'STREAMABLE_HTTP', command: '', args: '',
    url: 'http://127.0.0.1:12306', endpoint: '/mcp', env: '', headers: '', enabled: true,
  }
  mcpError.value = null
  mcpCallResult.value = null
  mcpTab.value = 'general'
  mcpView.value = 'detail'
}

function selectMcpServer(server: McpServer) {
  mcpSelectedId.value = server.id
  mcpForm.value = {
    name: server.name, type: server.type, command: server.command ?? '', args: server.args.join('\n'),
    url: server.url ?? '', endpoint: server.endpoint ?? '', env: '', headers: '', enabled: server.enabled,
  }
  mcpError.value = null
  mcpCallTool.value = ''
  mcpCallArguments.value = '{}'
  mcpCallResult.value = null
  mcpTab.value = 'general'
  mcpView.value = 'detail'
  void loadMcpDetails(server)
}

const selectedMcpServer = computed(() => mcpServers.value.find((server) => server.id === mcpSelectedId.value) ?? null)
const filteredMcpServers = computed(() => {
  const query = mcpQuery.value.trim().toLowerCase()
  return mcpServers.value.filter((server) => {
    if (mcpFilter.value === 'enabled' && !server.enabled) return false
    if (mcpFilter.value === 'disabled' && server.enabled) return false
    if (mcpFilter.value === 'stdio' && server.type !== 'STDIO') return false
    if (mcpFilter.value === 'sse' && server.type !== 'SSE') return false
    if (mcpFilter.value === 'streamableHttp' && server.type !== 'STREAMABLE_HTTP') return false
    return !query || `${server.name} ${server.type} ${server.serverVersion}`.toLowerCase().includes(query)
  })
})
const filteredMcpTools = computed(() => {
  const query = mcpToolQuery.value.trim().toLowerCase()
  return (selectedMcpServer.value?.tools ?? []).filter((tool) => !query || tool.toLowerCase().includes(query))
})
const mcpConnected = computed(() => selectedMcpServer.value?.status === 'connected')

function mcpTypeLabel(type: McpTransportType) {
  return type === 'STDIO' ? t('settings.mcp.typeStdio')
    : type === 'SSE' ? t('settings.mcp.typeSse')
      : t('settings.mcp.typeStreamableHttp')
}

function mcpEndpointLabel(server: McpServer | null) {
  if (!server) return '—'
  if (!server.url) return server.command || '—'
  try { return new URL(server.endpoint || '/mcp', server.url).toString() }
  catch { return `${server.url}${server.endpoint || ''}` }
}

function mcpStatusLabel(server: McpServer | null) {
  if (!server || !server.enabled) return t('settings.mcp.statusDisabled')
  if (server.status === 'connected') return t('settings.mcp.statusConnected')
  if (server.status === 'error') return t('settings.mcp.statusError')
  return t('settings.mcp.statusDisconnected')
}

async function loadMcpDetails(server: McpServer) {
  mcpPrompts.value = []
  mcpResources.value = []
  if (server.status !== 'connected') {
    mcpDetailsLoading.value = false
    return
  }
  mcpDetailsLoading.value = true
  try {
    const [prompts, resources] = await Promise.all([
      api.mcpPrompts(server.id).catch(() => []),
      api.mcpResources(server.id).catch(() => []),
    ])
    if (mcpSelectedId.value === server.id) {
      mcpPrompts.value = prompts
      mcpResources.value = resources
    }
  } finally {
    mcpDetailsLoading.value = false
  }
}

function parseMcpMap(text: string, label: string): Record<string, string> | undefined {
  if (!text.trim()) return undefined
  const parsed: unknown = JSON.parse(text)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error(t('settings.mcp.jsonObjectError', { label }))
  return Object.fromEntries(Object.entries(parsed as Record<string, unknown>).map(([key, value]) => {
    if (typeof value !== 'string') throw new Error(t('settings.mcp.jsonValueError', { label, key }))
    return [key, value]
  }))
}

async function loadMcpServers() {
  try { mcpServers.value = await api.mcpServers() } catch { mcpServers.value = [] }
}

async function saveMcpServer() {
  mcpSaving.value = true
  mcpError.value = null
  try {
    const form = mcpForm.value
    const request: McpServerRequest = {
      name: form.name.trim(), type: form.type, command: form.command.trim() || undefined,
      args: form.args.split('\n').map((value) => value.trim()).filter(Boolean), url: form.url.trim() || undefined,
      endpoint: form.endpoint.trim() || undefined, env: parseMcpMap(form.env, t('settings.mcp.environment')),
      headers: parseMcpMap(form.headers, t('settings.mcp.headers')), enabled: form.enabled,
    }
    const savedServer = mcpSelectedId.value
      ? await api.updateMcpServer(mcpSelectedId.value, request)
      : await api.createMcpServer(request)
    await loadMcpServers()
    selectMcpServer(savedServer)
  } catch (e: unknown) {
    mcpError.value = e instanceof Error ? e.message : String(e)
  } finally { mcpSaving.value = false }
}

async function testMcpServer(server: McpServer) {
  mcpTesting.value = server.id
  mcpError.value = null
  try {
    const tested = await api.testMcpServer(server.id)
    await loadMcpServers()
    selectMcpServer(tested)
  }
  catch (e: unknown) { mcpError.value = e instanceof Error ? e.message : String(e) }
  finally { mcpTesting.value = null }
}

async function removeMcpServer(server: McpServer) {
  if (!window.confirm(t('settings.mcp.deleteConfirm', { name: server.name }))) return
  try {
    await api.deleteMcpServer(server.id)
    if (mcpSelectedId.value === server.id) {
      mcpSelectedId.value = null
      mcpView.value = 'list'
    }
    await loadMcpServers()
  } catch (e: unknown) { mcpError.value = e instanceof Error ? e.message : String(e) }
}

async function toggleMcpServer(server: McpServer) {
  mcpError.value = null
  try {
    const updated = await api.updateMcpServer(server.id, {
      name: server.name,
      type: server.type,
      command: server.command ?? undefined,
      args: server.args,
      url: server.url ?? undefined,
      endpoint: server.endpoint ?? undefined,
      enabled: !server.enabled,
    })
    await loadMcpServers()
    if (mcpSelectedId.value === server.id) selectMcpServer(updated)
  } catch (e: unknown) { mcpError.value = e instanceof Error ? e.message : String(e) }
}

async function toggleSelectedMcpServer() {
  if (mcpSelectedId.value) await saveMcpServer()
}

async function callSelectedMcpTool() {
  const server = selectedMcpServer.value
  if (!server || !mcpCallTool.value) return
  mcpCalling.value = true
  mcpError.value = null
  mcpCallResult.value = null
  try {
    const parsed: unknown = JSON.parse(mcpCallArguments.value || '{}')
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error(t('settings.mcp.argumentsObject'))
    const result = await api.callMcpTool(server.id, mcpCallTool.value, parsed as Record<string, unknown>)
    mcpCallResult.value = JSON.stringify(result, null, 2)
  } catch (e: unknown) {
    mcpError.value = e instanceof Error ? e.message : String(e)
  } finally { mcpCalling.value = false }
}
</script>

<template>
  <div class="set-shell set-shell--settings">
    <!-- Left section navigation -->
    <aside class="set-nav">
      <div class="set-nav-back">
        <button class="set-nav-back-btn" @click="backToApp">
          <i class="mdi mdi-arrow-left" />
          <span>{{ $t('settings.backToApp') }}</span>
        </button>
      </div>
      <div class="set-nav-search">
        <i class="mdi mdi-magnify" />
        <input v-model="settingsQuery" :placeholder="$t('settings.searchSettings')" :aria-label="$t('settings.searchSettings')">
      </div>
      <template v-for="(row, i) in visibleNavRows" :key="i">
        <div v-if="row.type === 'group'" class="set-nav-grp">{{ row.label }}</div>
        <button
          v-else
          class="set-nav-item"
          :class="{ active: activeSection === row.id }"
          @click="activeSection = row.id"
        >
          <i class="mdi" :class="row.icon" />
          <span>{{ row.label }}</span>
        </button>
      </template>
    </aside>

    <!-- Right content pane -->
    <div class="set-content">
      <!-- ═══ AI Providers: master-detail ═══ -->
      <div v-if="activeSection === 'providers'" class="prov">
        <!-- Provider list -->
        <aside class="prov-list">
          <div class="prov-list-hd">
            <div class="prov-list-title">{{ $t('aiSettings.providers') }}</div>
            <div class="cx-input-wrap prov-search">
              <i class="mdi mdi-magnify prov-search-icon" />
              <input v-model="providerQuery" class="cx-input" :placeholder="$t('aiSettings.searchProvider')">
            </div>
          </div>
          <div class="prov-list-body">
            <div class="prov-grp">{{ $t('aiSettings.providersGroup') }}</div>
            <button
              v-for="p in filteredProviders"
              :key="p.id"
              class="prov-item"
              :class="{ active: selectedProvider === p.id }"
              @click="selectedProvider = p.id"
            >
              <span class="prov-logo" :style="{ background: p.color }">{{ p.initial }}</span>
              <span class="prov-name">{{ p.label }}</span>
              <span v-if="aiForm.mode === p.id" class="prov-on" />
            </button>
          </div>
        </aside>

        <!-- Provider detail -->
        <div class="prov-detail">
          <div class="prov-detail-inner">
            <div class="prov-head">
              <span class="prov-logo prov-logo--lg" :style="{ background: selectedProviderMeta.color }">{{ selectedProviderMeta.initial }}</span>
              <div class="prov-head-ti">
                <h2>{{ selectedProviderMeta.label }}
                  <span class="cx-muted prov-domain">{{ selectedProviderMeta.domain }}</span>
                </h2>
                <div class="cx-muted prov-status">
                  {{ providerActive ? $t('aiSettings.enabled') : $t('aiSettings.notEnabled') }}
                </div>
              </div>
              <label class="prov-toggle" :title="providerActive ? $t('aiSettings.enabled') : $t('aiSettings.activate')">
                <input type="checkbox" :checked="providerActive" @change="activateProvider(selectedProvider)">
                <span class="prov-toggle__track" /><span class="prov-toggle__thumb" />
              </label>
            </div>

            <div class="cx-card">
              <h3 class="prov-card-h">{{ $t('aiSettings.authSection') }}</h3>
              <p class="cx-muted prov-card-desc">{{ $t('aiSettings.authHint') }}</p>
              <div class="prov-divider" />

              <div class="cx-field" style="margin-bottom: 14px">
                <label class="cx-label">{{ $t('aiSettings.apiKey') }}</label>
                <div class="prov-key-row">
                  <input
                    v-model="aiForm[selectedProvider].apiKey"
                    class="cx-input prov-key-input"
                    :type="showKey[selectedProvider] ? 'text' : 'password'"
                    :placeholder="settings.aiSettings?.[selectedProvider]?.apiKeySet ? $t('aiSettings.apiKeyHint') : ''"
                  />
                  <button class="prov-key-mini" :title="showKey[selectedProvider] ? $t('aiSettings.hideKey') : $t('aiSettings.showKey')" @click="showKey[selectedProvider] = !showKey[selectedProvider]">
                    <i class="mdi" :class="showKey[selectedProvider] ? 'mdi-eye-off' : 'mdi-eye'" />
                  </button>
                  <button class="prov-key-mini" :title="$t('aiSettings.test')" @click="onTest">
                    <i class="mdi" :class="testing ? 'mdi-loading mdi-spin' : 'mdi-connection'" />
                  </button>
                </div>
              </div>

              <div class="cx-field" style="margin-bottom: 14px">
                <label class="cx-label">{{ $t('aiSettings.endpoint') }}</label>
                <div class="cx-input-wrap">
                  <input
                    v-model="aiForm[selectedProvider].endpoint"
                    class="cx-input"
                    :placeholder="selectedNeedsV1 ? 'https://api.openai.com/v1' : 'https://api.anthropic.com'"
                  />
                  <button class="cx-iconbtn cx-iconbtn--sm" :title="$t('aichat.copy')" @click="copyText(aiForm[selectedProvider].endpoint)">
                    <i class="mdi mdi-content-copy" />
                  </button>
                </div>
                <div class="cx-muted" style="font-size: 12px; margin-top: 4px">
                  {{ selectedNeedsV1 ? $t('aiSettings.endpointHintOpenai') : $t('aiSettings.endpointHintAnthropic') }}
                </div>
              </div>

              <div v-if="testResult" class="cx-alert" :class="testResult.success ? 'cx-alert--success' : 'cx-alert--error'">
                <div class="cx-alert__body">
                  {{ testResult.success ? $t('aiSettings.testSuccess') : $t('aiSettings.testFailed') }}
                  <div v-if="testResult.error" style="font-size: 12px">{{ testResult.error }}</div>
                  <div v-if="testResult.warning" style="font-size: 12px">{{ testResult.warning }}</div>
                </div>
              </div>
            </div>

            <!-- Models -->
            <div class="cx-card" style="margin-top: 16px">
              <div class="prov-models-head">
                <h3 class="prov-card-h">{{ $t('aiSettings.models') }}</h3>
                <button class="cx-btn cx-btn--primary cx-btn--sm" @click="addingModel = !addingModel">
                  <i class="mdi mdi-plus" />{{ $t('aiSettings.addModel') }}
                </button>
              </div>
              <p class="cx-muted prov-card-desc">{{ $t('aiSettings.modelsHint') }}</p>
              <div class="prov-divider" />
              <div class="prov-filter-chips">
                <button v-for="c in modelFilters" :key="c.id" class="cx-chip" :class="{ 'cx-chip--solid': modelFilter === c.id }" @click="modelFilter = c.id">{{ c.label }}</button>
              </div>
              <div v-if="addingModel" class="prov-add-model">
                <input v-model="newModelName" class="cx-input" :placeholder="$t('aiSettings.modelNamePh')" @keydown.enter="commitModel" />
                <select v-model="newModelCap" class="cx-select prov-cap-select">
                  <option value="text">{{ $t('aiSettings.capText') }}</option>
                  <option value="vision">{{ $t('aiSettings.capVision') }}</option>
                  <option value="reasoning">{{ $t('aiSettings.capReasoning') }}</option>
                </select>
                <button class="cx-btn cx-btn--primary cx-btn--sm" @click="commitModel">{{ $t('common.confirm') }}</button>
              </div>
              <div v-if="filteredModelRows.length" class="prov-model-list">
                <div v-for="m in filteredModelRows" :key="m.name" class="prov-model-row">
                  <i class="mdi mdi-lightning-bolt" />
                  <span class="prov-model-name">{{ m.name }}</span>
                  <span class="cx-chip" :class="capChipClass(m.cap)">{{ capLabel(m.cap) }}</span>
                  <button class="cx-iconbtn cx-iconbtn--sm" :title="$t('common.cancel')" @click="removeModel"><i class="mdi mdi-close" /></button>
                </div>
              </div>
              <div v-else class="cx-muted prov-model-empty">{{ $t('aiSettings.noModels') }}</div>
            </div>

            <div class="cx-row" style="margin-top: 16px">
              <button class="cx-btn cx-btn--primary" @click="onSave">{{ $t('aiSettings.save') }}</button>
              <span v-if="saved" class="cx-chip cx-chip--success">{{ $t('aiSettings.saved') }}</span>
              <span v-if="saveError" class="cx-alert cx-alert--error">{{ saveError }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- ═══ Other sections: single content column ═══ -->
      <div v-else class="set-inner">
        <!-- Generation params -->
        <section v-show="activeSection === 'generate'">
          <h2 class="set-h">{{ $t('aiSettings.generate') }}</h2>
          <div class="cx-card">
            <div class="cx-row" style="margin-bottom: 16px">
              <span class="cx-muted" style="font-size: 13px">{{ $t('aiSettings.status') }}</span>
              <span class="cx-chip" :class="settings.aiSettings?.ready ? 'cx-chip--success' : ''">
                {{ settings.aiSettings?.ready ? $t('aiSettings.ready') : $t('aiSettings.notReady') }}
              </span>
              <span class="cx-muted" style="font-size: 13px">({{ settings.aiSettings?.activeMode }})</span>
            </div>
            <div class="cx-setting-row">
              <div class="cx-setting-row__label"><span>{{ $t('aiSettings.temperature') }}</span></div>
              <input v-model.number="aiForm.temperature" class="cx-input cx-input--narrow" type="number" step="0.1" min="0" max="2" />
            </div>
            <div class="cx-setting-row">
              <div class="cx-setting-row__label"><span>{{ $t('aiSettings.topP') }}</span></div>
              <input v-model.number="aiForm.topP" class="cx-input cx-input--narrow" type="number" step="0.05" min="0" max="1" />
            </div>
            <div class="cx-setting-row">
              <div class="cx-setting-row__label"><span>{{ $t('aiSettings.maxTokens') }}</span></div>
              <input v-model.number="aiForm.maxTokens" class="cx-input cx-input--narrow" type="number" step="1" min="1" />
            </div>
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <span>{{ $t('aiSettings.maxToolRounds') }}</span>
                <span class="cx-muted" style="font-size: 12px; margin-left: 6px">{{ $t('aiSettings.maxToolRoundsHint') }}</span>
              </div>
              <input v-model.number="aiForm.maxToolRounds" class="cx-input cx-input--narrow" type="number" step="1" min="0" max="10000" />
            </div>
            <div class="cx-setting-row" style="margin-bottom: 14px">
              <div class="cx-setting-row__label">
                <span>{{ $t('aiSettings.contextWindowTokens') }}</span>
                <span class="cx-muted" style="font-size: 12px; margin-left: 6px">{{ $t('aiSettings.contextWindowTokensHint') }}</span>
              </div>
              <input v-model.number="aiForm.contextWindowTokens" class="cx-input cx-input--narrow" type="number" step="1024" min="0" max="2000000" />
            </div>
            <div class="cx-field" style="margin-bottom: 16px">
              <label class="cx-label">{{ $t('aiSettings.systemPrompt') }}</label>
              <textarea v-model="aiForm.systemPrompt" class="cx-textarea" rows="3" />
            </div>
            <div class="cx-row">
              <button class="cx-btn cx-btn--primary" @click="onSave">{{ $t('aiSettings.save') }}</button>
              <span v-if="saved" class="cx-chip cx-chip--success">{{ $t('aiSettings.saved') }}</span>
              <span v-if="saveError" class="cx-alert cx-alert--error">{{ saveError }}</span>
            </div>
          </div>
        </section>

        <!-- Appearance -->
        <section v-show="activeSection === 'appearance'">
          <h2 class="set-h">{{ $t('settings.general') }}</h2>
          <div class="cx-card">
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-palette-outline" />
                <span>{{ $t('settings.theme') }}</span>
              </div>
              <div class="cx-segment">
                <button v-for="i in themeItems" :key="i.value" :class="{ active: settings.theme === i.value }" @click="settings.setTheme(i.value)">{{ i.title }}</button>
              </div>
            </div>
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-translate" />
                <span>{{ $t('settings.language') }}</span>
              </div>
              <div class="cx-segment">
                <button v-for="i in languageItems" :key="i.value" :class="{ active: settings.language === i.value }" @click="settings.setLanguage(i.value)">{{ i.title }}</button>
              </div>
            </div>
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-text-box-search-outline" />
                <div>
                  <div>{{ $t('settings.logLevel') }}</div>
                  <div class="cx-muted" style="font-size: 12px">{{ $t('settings.logLevelHint') }}</div>
                </div>
              </div>
              <select
                :value="settings.logLevel"
                class="cx-select"
                style="width: 140px"
                @change="settings.setLogLevel(($event.target as HTMLSelectElement).value as LogLevel)"
              >
                <option v-for="level in logLevelItems" :key="level" :value="level">{{ level }}</option>
              </select>
            </div>
          </div>
        </section>

        <!-- Runtime & security -->
        <section v-show="activeSection === 'runtime'">
          <h2 class="set-h">{{ $t('settings.runtimeSecurity') }}</h2>
          <div v-if="runtimeError" class="cx-alert cx-alert--error" style="margin-bottom: 12px">
            <span class="cx-alert__body">{{ runtimeError }}</span>
            <button class="cx-iconbtn cx-iconbtn--sm" @click="runtimeError = null"><i class="mdi mdi-close" /></button>
          </div>
          <div class="cx-card">
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-shield-lock-outline" />
                <span>{{ $t('settings.processIsolation') }}</span>
              </div>
              <span v-if="isolationStatus" class="cx-chip" :class="isolationChipClass">{{ isolationChipLabel }}</span>
            </div>
            <div v-if="isolationStatus?.reduced" class="cx-muted" style="font-size: 12px; margin: -6px 0 12px;">
              {{ $t('settings.sandboxReducedHint') }}
            </div>
            <div v-if="isolationStatus?.compatibilityMode" class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-shield-alert-outline" />
                <span>{{ $t('settings.unsandboxedPluginsTitle') }}</span>
              </div>
              <div class="cx-segment">
                <button :class="{ active: !settings.unsandboxedPlugins }" @click="runToggle(() => settings.setUnsandboxedPlugins(false))">{{ $t('settings.unsandboxedOff') }}</button>
                <button :class="{ active: settings.unsandboxedPlugins }" @click="requestEnableUnsandboxed()">{{ $t('settings.unsandboxedOn') }}</button>
              </div>
            </div>
            <div v-if="isolationStatus?.compatibilityMode" class="cx-muted" style="color: rgb(var(--v-theme-error)); font-size: 12px; margin: -6px 0 0;">
              {{ $t('settings.unsandboxedPluginsWarn') }}
            </div>
          </div>

          <!-- AI guard: permission rules, lifecycle hooks, memory, marketplace pinning -->
          <div class="cx-card" style="margin-top: 16px">
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-shield-check-outline" />
                <span>{{ $t('settings.guardTitle') }}</span>
              </div>
            </div>
            <div class="cx-muted" style="font-size: 12px; margin: -6px 0 12px;">
              {{ $t('settings.guardHint') }}
            </div>

            <div class="guard-grid">
              <label v-for="kind in (['allow', 'ask', 'deny'] as const)" :key="kind" class="guard-field">
                <span class="guard-field__label">
                  {{ $t(`settings.guardRules.${kind}`) }}
                  <em class="guard-field__badge" :data-kind="kind">{{ $t(`settings.guardRules.${kind}Desc`) }}</em>
                </span>
                <textarea
                  class="cx-textarea mono"
                  rows="4"
                  spellcheck="false"
                  :placeholder="RULE_PLACEHOLDERS[kind]"
                  :value="(settings.permissionRules[kind] ?? []).join('\n')"
                  @change="setRules(kind, $event)"
                />
              </label>
            </div>
            <div v-if="settings.invalidPermissionRules.length" class="cx-alert cx-alert--error" style="margin: 8px 0">
              <span class="cx-alert__body">{{ $t('settings.guardInvalid', { rules: settings.invalidPermissionRules.join('; ') }) }}</span>
            </div>
            <div v-if="guardError" class="cx-alert cx-alert--error" style="margin: 8px 0">
              <span class="cx-alert__body">{{ guardError }}</span>
            </div>
            <div class="cx-row" style="margin-top: 8px">
              <button class="cx-btn cx-btn--primary" :disabled="guardSaving" @click="saveRules">
                <i class="mdi mdi-content-save-outline" /> {{ $t('settings.guardSaveRules') }}
              </button>
              <span class="cx-muted" style="font-size: 11px">{{ $t('settings.guardRuleSyntax') }}</span>
            </div>

            <label class="guard-field" style="margin-top: 16px">
              <span class="guard-field__label">{{ $t('settings.guardHooks') }}</span>
              <textarea
                v-model="localHooksJson"
                class="cx-textarea mono"
                rows="5"
                spellcheck="false"
                :placeholder="HOOKS_PLACEHOLDER"
              />
            </label>
            <div class="cx-row" style="margin-top: 8px">
              <button class="cx-btn cx-btn--primary" :disabled="guardSaving" @click="saveHooks">
                <i class="mdi mdi-content-save-outline" /> {{ $t('settings.guardSaveHooks') }}
              </button>
              <span class="cx-muted" style="font-size: 11px">{{ $t('settings.guardHooksHint') }}</span>
            </div>
          </div>

          <!-- Cross-session memory (experimental) -->
          <div class="cx-card" style="margin-top: 16px">
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-book-clock-outline" />
                <span>{{ $t('settings.memoryTitle') }}</span>
              </div>
              <div class="cx-segment">
                <button :class="{ active: !settings.memoryEnabled }" @click="runToggle(() => settings.setMemoryEnabled(false))">{{ $t('common.off') }}</button>
                <button :class="{ active: settings.memoryEnabled }" @click="runToggle(() => settings.setMemoryEnabled(true))">{{ $t('common.on') }}</button>
              </div>
            </div>
            <div class="cx-muted" style="font-size: 12px; margin: -6px 0 12px;">
              {{ $t('settings.memoryHint') }}
            </div>
          </div>

          <!-- Marketplace checksum pinning -->
          <div class="cx-card" style="margin-top: 16px">
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-certificate-outline" />
                <span>{{ $t('settings.checksumTitle') }}</span>
              </div>
              <div class="cx-segment">
                <button :class="{ active: !settings.marketplaceRequireChecksum }" @click="runToggle(() => settings.setMarketplaceRequireChecksum(false))">{{ $t('common.off') }}</button>
                <button :class="{ active: settings.marketplaceRequireChecksum }" @click="runToggle(() => settings.setMarketplaceRequireChecksum(true))">{{ $t('common.on') }}</button>
              </div>
            </div>
            <div class="cx-muted" style="font-size: 12px; margin: -6px 0 12px;">
              {{ $t('settings.checksumHint') }}
            </div>
          </div>

          <!-- Computer use (AI screen control) — only present in desktop mode -->
          <div v-if="settings.computerUse" class="cx-card" style="margin-top: 16px">
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-cursor-default-click" />
                <span>{{ $t('settings.computerUseTitle') }}</span>
              </div>
              <span class="cx-chip" :class="computerUseChipClass">{{ computerUseChipLabel }}</span>
            </div>
            <div class="cx-muted" style="font-size: 12px; margin: -6px 0 12px;">
              {{ $t('settings.computerUseHint') }}
            </div>
            <div v-if="!settings.computerUse.available" class="cx-muted" style="color: rgb(var(--v-theme-error)); font-size: 12px; margin: -6px 0 12px;">
              {{ $t('settings.computerUseUnavailable', { reason: settings.computerUse.reason ?? '' }) }}
            </div>
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-robot-outline" />
                <span>{{ $t('settings.computerUseAllowAi') }}</span>
              </div>
              <div class="cx-segment">
                <button :class="{ active: !settings.computerUseEnabled }" @click="runToggle(() => settings.setComputerUseEnabled(false))">{{ $t('settings.computerUseOff') }}</button>
                <button :class="{ active: settings.computerUseEnabled }" @click="requestEnableComputerUse()">{{ $t('settings.computerUseOn') }}</button>
              </div>
            </div>
          </div>
        </section>

        <!-- MCP -->
        <section v-show="activeSection === 'mcp'">
          <div v-if="mcpView === 'list'" class="mcp-page">
            <div class="mcp-page-head">
              <div>
                <h2 class="set-h">{{ $t('settings.mcp.allServers') }}</h2>
                <div class="cx-muted mcp-page-subtitle">
                  {{ mcpStatus ? $t('settings.mcpSummary', { connections: mcpStatus.connectionCount, tools: mcpStatus.toolCount }) : $t('settings.mcp.subtitle') }}
                </div>
              </div>
              <div class="mcp-page-actions">
                <button class="cx-btn" @click="presetMcpChrome"><i class="mdi mdi-google-chrome" /> {{ $t('settings.mcp.addChrome') }}</button>
                <button class="cx-btn cx-btn--primary" @click="resetMcpForm"><i class="mdi mdi-plus" /> {{ $t('settings.mcp.addServer') }}</button>
              </div>
            </div>
            <div class="mcp-toolbar">
              <div class="cx-input-wrap mcp-search">
                <i class="mdi mdi-magnify mcp-search-icon" />
                <input v-model="mcpQuery" class="cx-input" :placeholder="$t('settings.mcp.search')">
              </div>
              <select v-model="mcpFilter" class="cx-input mcp-filter" :aria-label="$t('settings.mcp.filter')">
                <option value="all">{{ $t('settings.mcp.filterAll') }}</option>
                <option value="enabled">{{ $t('settings.mcp.filterEnabled') }}</option>
                <option value="disabled">{{ $t('settings.mcp.filterDisabled') }}</option>
                <option value="stdio">{{ $t('settings.mcp.typeStdio') }}</option>
                <option value="sse">{{ $t('settings.mcp.typeSse') }}</option>
                <option value="streamableHttp">{{ $t('settings.mcp.typeStreamableHttp') }}</option>
              </select>
            </div>
            <div class="mcp-table">
              <div class="mcp-table-head">
                <span>{{ $t('settings.mcp.server') }}</span><span>{{ $t('settings.mcp.version') }}</span><span>{{ $t('settings.mcp.transport') }}</span><span>{{ $t('settings.mcp.tools') }}</span><span />
              </div>
              <button v-for="server in filteredMcpServers" :key="server.id" class="mcp-server-row" @click="selectMcpServer(server)">
                <span class="mcp-server-name">
                  <span class="mcp-avatar">{{ server.name.slice(0, 1).toUpperCase() }}</span>
                  <span class="mcp-status-dot" :class="`mcp-status-dot--${server.enabled ? server.status : 'disabled'}`" />
                  <span class="mcp-server-name-text"><strong>{{ server.name }}</strong><small>{{ mcpStatusLabel(server) }}</small></span>
                </span>
                <span class="mcp-table-muted">{{ server.serverVersion || '—' }}</span>
                <span><span class="mcp-type-badge" :class="`mcp-type-badge--${server.type.toLowerCase()}`">{{ mcpTypeLabel(server.type) }}</span></span>
                <span class="mcp-table-muted">{{ server.tools.length }}</span>
                <span class="mcp-row-actions" @click.stop>
                  <button class="mcp-icon-btn" :title="$t('settings.mcp.edit')" @click="selectMcpServer(server)"><i class="mdi mdi-pencil-outline" /></button>
                  <label class="mcp-switch" :title="$t('settings.mcp.toggle')"><input :checked="server.enabled" type="checkbox" @change="toggleMcpServer(server)"><span /></label>
                </span>
              </button>
              <div v-if="filteredMcpServers.length === 0" class="mcp-empty">
                <i class="mdi mdi-connection" />
                <strong>{{ mcpServers.length === 0 ? $t('settings.mcp.empty') : $t('settings.mcp.noResults') }}</strong>
                <span>{{ mcpServers.length === 0 ? $t('settings.mcp.emptyHint') : $t('settings.mcp.noResultsHint') }}</span>
              </div>
            </div>
          </div>

          <div v-else class="mcp-detail">
            <div class="mcp-detail-head">
              <button class="mcp-back-btn" :title="$t('common.back')" @click="mcpView = 'list'"><i class="mdi mdi-arrow-left" /></button>
              <span class="mcp-avatar mcp-avatar--large">{{ (mcpSelectedId ? (selectedMcpServer?.name ?? mcpForm.name) : mcpForm.name || '?').slice(0, 1).toUpperCase() }}</span>
              <div class="mcp-detail-title"><h2>{{ mcpSelectedId ? selectedMcpServer?.name : $t('settings.mcp.newServer') }}</h2><span v-if="mcpSelectedId" class="mcp-status-badge" :class="`mcp-status-badge--${mcpForm.enabled ? selectedMcpServer?.status : 'disabled'}`">{{ mcpStatusLabel(selectedMcpServer) }}</span><span v-if="selectedMcpServer?.serverVersion" class="mcp-table-muted">{{ selectedMcpServer.serverVersion }}</span></div>
              <label v-if="mcpSelectedId" class="mcp-switch mcp-switch--large" :title="$t('settings.mcp.toggle')"><input v-model="mcpForm.enabled" type="checkbox" :disabled="mcpSaving" @change="toggleSelectedMcpServer"><span /></label>
            </div>
            <div class="mcp-tabs" role="tablist">
              <button v-for="tab in (['general', 'description', 'tools', 'prompts', 'resources', 'logs'] as const)" :key="tab" class="mcp-tab" :class="{ active: mcpTab === tab }" @click="mcpTab = tab">{{ $t(`settings.mcp.tabs.${tab}`) }}<span v-if="tab === 'tools' && selectedMcpServer">{{ selectedMcpServer.tools.length }}</span><span v-if="tab === 'prompts'">{{ mcpPrompts.length }}</span><span v-if="tab === 'resources'">{{ mcpResources.length }}</span></button>
            </div>
            <div v-if="mcpError" class="cx-alert cx-alert--error mcp-detail-alert">{{ mcpError }}</div>

            <div v-if="mcpTab === 'general'" class="mcp-detail-body">
              <div class="mcp-form-section"><div class="mcp-section-title">{{ $t('settings.mcp.identity') }}</div><div class="mcp-form-grid"><div class="cx-field"><label class="cx-label">{{ $t('settings.mcp.name') }}</label><input v-model="mcpForm.name" class="cx-input" :placeholder="$t('settings.mcp.placeholderName')"></div><div class="cx-field"><label class="cx-label">{{ $t('settings.mcp.transport') }}</label><select v-model="mcpForm.type" class="cx-input"><option value="STDIO">{{ $t('settings.mcp.typeStdio') }}</option><option value="STREAMABLE_HTTP">{{ $t('settings.mcp.typeStreamableHttp') }}</option><option value="SSE">{{ $t('settings.mcp.typeSse') }}</option></select></div></div></div>
              <div class="mcp-form-section"><div class="mcp-section-title">{{ $t('settings.mcp.connection') }}</div><div v-if="mcpForm.type === 'STDIO'" class="mcp-form-grid"><div class="cx-field"><label class="cx-label">{{ $t('settings.mcp.command') }}</label><input v-model="mcpForm.command" class="cx-input" :placeholder="$t('settings.mcp.placeholderCommand')"></div><div class="cx-field"><label class="cx-label">{{ $t('settings.mcp.arguments') }}</label><textarea v-model="mcpForm.args" class="cx-input mcp-textarea" :placeholder="$t('settings.mcp.placeholderArguments')" /></div></div><div v-else class="mcp-form-grid"><div class="cx-field"><label class="cx-label">{{ $t('settings.mcp.url') }}</label><input v-model="mcpForm.url" class="cx-input" :placeholder="$t('settings.mcp.placeholderUrl')"></div><div class="cx-field"><label class="cx-label">{{ $t('settings.mcp.endpoint') }}</label><input v-model="mcpForm.endpoint" class="cx-input" :placeholder="mcpForm.type === 'SSE' ? $t('settings.mcp.placeholderSseEndpoint') : $t('settings.mcp.placeholderHttpEndpoint')"></div></div></div>
              <div class="mcp-form-section"><div class="mcp-section-title">{{ $t('settings.mcp.credentials') }}<span>{{ $t('settings.mcp.credentialsHint') }}</span></div><div class="mcp-form-grid"><div class="cx-field"><label class="cx-label">{{ $t('settings.mcp.environment') }}</label><textarea v-model="mcpForm.env" class="cx-input mcp-textarea" :placeholder="$t('settings.mcp.placeholderEnvironment')" /></div><div v-if="mcpForm.type !== 'STDIO'" class="cx-field"><label class="cx-label">{{ $t('settings.mcp.headers') }}</label><textarea v-model="mcpForm.headers" class="cx-input mcp-textarea" :placeholder="$t('settings.mcp.placeholderHeaders')" /></div></div></div>
            </div>

            <div v-else-if="mcpTab === 'description'" class="mcp-detail-body"><div class="mcp-info-card"><i class="mdi mdi-information-outline" /><div><strong>{{ $t('settings.mcp.liveConnection') }}</strong><p>{{ $t('settings.mcp.liveConnectionHint') }}</p></div></div><div class="mcp-meta-grid"><div><small>{{ $t('settings.mcp.protocol') }}</small><strong>{{ selectedMcpServer?.protocolVersion || '—' }}</strong></div><div><small>{{ $t('settings.mcp.endpoint') }}</small><strong>{{ mcpEndpointLabel(selectedMcpServer) }}</strong></div><div><small>{{ $t('settings.mcp.environment') }}</small><strong>{{ selectedMcpServer?.envKeys.length || 0 }} {{ $t('settings.mcp.keys') }}</strong></div><div><small>{{ $t('settings.mcp.headers') }}</small><strong>{{ selectedMcpServer?.headerNames.length || 0 }} {{ $t('settings.mcp.keys') }}</strong></div></div><div v-if="selectedMcpServer?.error" class="cx-alert cx-alert--error">{{ selectedMcpServer.error }}</div></div>

            <div v-else-if="mcpTab === 'tools'" class="mcp-detail-body"><div class="mcp-tab-toolbar"><div><strong>{{ $t('settings.mcp.tools') }}</strong><span class="cx-muted">{{ $t('settings.mcp.toolsHint') }}</span></div><div class="cx-input-wrap mcp-tool-search"><i class="mdi mdi-magnify mcp-search-icon" /><input v-model="mcpToolQuery" class="cx-input" :placeholder="$t('settings.mcp.searchTools')"></div></div><div v-if="!mcpConnected" class="mcp-empty mcp-empty--small"><i class="mdi mdi-lan-disconnect" /><strong>{{ $t('settings.mcp.notConnected') }}</strong><span>{{ $t('settings.mcp.notConnectedHint') }}</span></div><div v-else-if="filteredMcpTools.length === 0" class="mcp-empty mcp-empty--small"><i class="mdi mdi-wrench-outline" /><strong>{{ $t('settings.mcp.noTools') }}</strong><span>{{ $t('settings.mcp.noToolsHint') }}</span></div><div v-else class="mcp-tool-list"><button v-for="tool in filteredMcpTools" :key="tool" class="mcp-tool-row" :class="{ active: mcpCallTool === tool }" @click="mcpCallTool = tool; mcpCallResult = null"><i class="mdi mdi-wrench-outline" /><span>{{ tool }}</span><i class="mdi mdi-chevron-right" /></button></div><div v-if="mcpCallTool && mcpConnected" class="mcp-call-panel"><div class="mcp-call-title"><strong>{{ $t('settings.mcp.callTool') }}</strong><code>{{ mcpCallTool }}</code></div><label class="cx-label">{{ $t('settings.mcp.arguments') }}</label><textarea v-model="mcpCallArguments" class="cx-input mcp-textarea mcp-call-input" spellcheck="false" :placeholder="$t('settings.mcp.placeholderArgumentsObject')" /><div class="mcp-call-actions"><button class="cx-btn cx-btn--primary" :disabled="mcpCalling" @click="callSelectedMcpTool">{{ mcpCalling ? $t('settings.mcp.calling') : $t('settings.mcp.call') }}</button><span class="cx-muted">{{ $t('settings.mcp.callHint') }}</span></div><pre v-if="mcpCallResult" class="mcp-call-result">{{ mcpCallResult }}</pre></div></div>

            <div v-else-if="mcpTab === 'prompts'" class="mcp-detail-body"><div v-if="mcpDetailsLoading" class="mcp-loading"><i class="mdi mdi-loading mdi-spin" /> {{ $t('settings.mcp.loading') }}</div><div v-else-if="mcpPrompts.length === 0" class="mcp-empty mcp-empty--small"><i class="mdi mdi-message-text-outline" /><strong>{{ $t('settings.mcp.noPrompts') }}</strong><span>{{ $t('settings.mcp.noPromptsHint') }}</span></div><div v-else class="mcp-resource-list"><div v-for="prompt in mcpPrompts" :key="prompt.name" class="mcp-resource-row"><i class="mdi mdi-message-text-outline" /><div><strong>{{ prompt.title || prompt.name }}</strong><small>{{ prompt.description || prompt.name }}</small></div><span v-if="prompt.arguments.length" class="mcp-table-muted">{{ $t('settings.mcp.argumentsCount', { count: prompt.arguments.length }) }}</span></div></div></div>
            <div v-else-if="mcpTab === 'resources'" class="mcp-detail-body"><div v-if="mcpDetailsLoading" class="mcp-loading"><i class="mdi mdi-loading mdi-spin" /> {{ $t('settings.mcp.loading') }}</div><div v-else-if="mcpResources.length === 0" class="mcp-empty mcp-empty--small"><i class="mdi mdi-file-link-outline" /><strong>{{ $t('settings.mcp.noResources') }}</strong><span>{{ $t('settings.mcp.noResourcesHint') }}</span></div><div v-else class="mcp-resource-list"><div v-for="resource in mcpResources" :key="resource.uri" class="mcp-resource-row"><i class="mdi mdi-file-link-outline" /><div><strong>{{ resource.title || resource.name }}</strong><small>{{ resource.uri }}</small></div><span class="mcp-table-muted">{{ resource.mimeType || $t('settings.mcp.resourceType') }}</span></div></div></div>
            <div v-else class="mcp-detail-body"><div class="mcp-info-card"><i class="mdi mdi-console-line" /><div><strong>{{ $t('settings.mcp.logs') }}</strong><p>{{ $t('settings.mcp.logsHint') }}</p></div></div><pre v-if="selectedMcpServer?.error" class="mcp-call-result">{{ selectedMcpServer.error }}</pre><div v-else class="mcp-empty mcp-empty--small"><i class="mdi mdi-check-circle-outline" /><strong>{{ $t('settings.mcp.noLogs') }}</strong><span>{{ $t('settings.mcp.noLogsHint') }}</span></div></div>

            <div class="mcp-detail-footer"><button class="cx-btn cx-btn--danger" :disabled="!mcpSelectedId" @click="selectedMcpServer && removeMcpServer(selectedMcpServer)">{{ $t('common.delete') }}</button><div class="mcp-footer-actions"><button v-if="mcpSelectedId" class="cx-btn" :disabled="mcpTesting === mcpSelectedId" @click="selectedMcpServer && testMcpServer(selectedMcpServer)">{{ mcpTesting ? $t('settings.mcp.testing') : $t('settings.mcp.test') }}</button><button class="cx-btn cx-btn--primary" :disabled="mcpSaving" @click="saveMcpServer">{{ mcpSaving ? $t('settings.mcp.saving') : $t('common.save') }}</button></div></div>
          </div>
        </section>

        <!-- Database isolation -->
        <section v-show="activeSection === 'database'">
          <h2 class="set-h">{{ $t('settings.pluginDbSection') }}</h2>
          <div class="cx-card">
            <div class="cx-muted" style="font-size: 12px; margin-bottom: 12px">{{ $t('settings.pluginDbSectionHint') }}</div>
            <div v-if="dbPlugins.length === 0" class="cx-muted" style="font-size: 13px">{{ $t('settings.pluginDbNoPlugins') }}</div>
            <div v-for="p in dbPlugins" :key="p.pluginId" class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-database-lock-outline" />
                <span>{{ p.name }} <span class="cx-muted" style="font-size: 12px">{{ p.pluginId }}</span></span>
              </div>
              <span v-if="p.provisioned" class="cx-chip cx-chip--success">{{ $t('settings.pluginDbAuthorized') }}</span>
              <button v-else class="cx-btn cx-btn--primary" :disabled="dbProvisioning === p.pluginId" @click="requestDbProvision(p.pluginId)">
                {{ dbProvisioning === p.pluginId ? $t('settings.pluginDbProvisioning') : $t('settings.pluginDbAuthorize') }}
              </button>
            </div>
            <div v-if="dbError" class="cx-muted" style="color: rgb(var(--v-theme-error)); font-size: 12px; margin-top: 8px">
              {{ $t('settings.pluginDbError', { message: dbError }) }}
            </div>
          </div>
        </section>

        <!-- Update channel -->
        <section v-show="activeSection === 'update'">
          <h2 class="set-h">{{ $t('settings.updateChannelSection') }}</h2>
          <div class="cx-card">
            <div class="cx-field" style="margin-bottom: 14px">
              <label class="cx-label">{{ $t('settings.updateProxyUrl') }}</label>
              <input v-model="proxyUrl" class="cx-input" :placeholder="$t('settings.updateProxyUrlPlaceholder')" />
              <div class="cx-muted" style="font-size: 12px; margin-top: 4px">{{ $t('settings.updateProxyUrlHint') }}</div>
            </div>
            <div class="cx-row">
              <button class="cx-btn cx-btn--primary" @click="onSaveProxy">{{ $t('aiSettings.save') }}</button>
              <span v-if="proxySaved" class="cx-chip cx-chip--success">{{ $t('settings.updateProxyUrlSaved') }}</span>
              <span v-if="proxyError" class="cx-alert cx-alert--error">{{ $t('settings.updateProxyUrlInvalid', { message: proxyError }) }}</span>
            </div>
          </div>
        </section>
      </div>
    </div>
  </div>

  <v-dialog v-model="showUnsandboxedConfirm" max-width="480">
    <v-card>
      <v-card-text>{{ $t('settings.unsandboxedPluginsConfirm') }}</v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="showUnsandboxedConfirm = false">{{ $t('common.cancel') }}</v-btn>
        <v-btn color="error" variant="tonal" @click="confirmEnableUnsandboxed()">{{ $t('common.confirm') }}</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <v-dialog v-model="showComputerUseConfirm" max-width="480">
    <v-card>
      <v-card-title>{{ $t('settings.computerUseTitle') }}</v-card-title>
      <v-card-text>{{ $t('settings.computerUseConfirm') }}</v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="showComputerUseConfirm = false">{{ $t('common.cancel') }}</v-btn>
        <v-btn color="primary" variant="tonal" @click="confirmEnableComputerUse()">{{ $t('common.confirm') }}</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <v-dialog v-model="showDbProvisionConfirm" max-width="480">
    <v-card>
      <v-card-title>{{ $t('settings.pluginDbConfirmTitle') }}</v-card-title>
      <v-card-text>{{ $t('settings.pluginDbConfirm') }}</v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="showDbProvisionConfirm = false">{{ $t('common.cancel') }}</v-btn>
        <v-btn color="primary" variant="tonal" @click="confirmDbProvision()">{{ $t('common.confirm') }}</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.guard-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
@media (max-width: 960px) { .guard-grid { grid-template-columns: 1fr; } }
.guard-field { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.guard-field__label { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; }
.guard-field__badge { padding: 1px 6px; font-size: 10px; font-style: normal; border-radius: 8px; background: rgb(var(--v-theme-surface-variant)); color: rgba(var(--v-theme-on-surface), .6); }
.guard-field textarea { width: 100%; font-size: 11px; }

.set-shell { flex: 1 1 auto; min-height: 0; height: 100%; display: flex; }
.set-nav {
  width: 232px; flex: 0 0 232px; height: 100%; overflow-y: auto;
  border-right: 1px solid var(--cx-border); background: rgb(var(--v-theme-background)); padding: 14px 10px;
}
.set-nav-hd { font-size: 15px; font-weight: 650; padding: 4px 10px 12px; }
.set-nav-grp { font-size: 11px; font-weight: 650; letter-spacing: 0.05em; text-transform: uppercase; color: rgb(var(--v-theme-secondary)); opacity: 0.7; padding: 14px 12px 4px; }
.set-nav-item {
  width: 100%; display: flex; align-items: center; gap: 10px; height: 36px; padding: 0 12px; margin: 1px 0;
  border: 0; border-radius: var(--cx-radius); background: transparent; color: rgb(var(--v-theme-secondary));
  font: inherit; font-size: 13px; text-align: left; cursor: pointer;
  transition: background 0.12s ease, color 0.12s ease;
}
.set-nav-item:hover { background: var(--cx-hover); color: rgb(var(--v-theme-on-surface)); }
.set-nav-item.active { background: var(--cx-hover-strong); color: rgb(var(--v-theme-on-surface)); }
.set-nav-item .mdi { font-size: 18px; flex: 0 0 auto; }

/* Standalone settings window — a quiet, native-feeling settings surface. */
.set-shell--settings { background: rgb(var(--v-theme-background)); }
.set-shell--settings .set-nav {
  width: 270px; flex-basis: 270px; padding: 10px 12px 18px;
  background: rgb(var(--v-theme-surface-container));
}
.set-nav-back { padding: 4px 0 12px; }
.set-nav-back-btn {
  display: flex; align-items: center; gap: 7px; width: 100%; height: 30px; padding: 0 6px;
  border: 0; border-radius: 8px; background: transparent; color: rgb(var(--v-theme-secondary));
  font: inherit; font-size: 13px; text-align: left; cursor: pointer;
}
.set-nav-back-btn:hover { background: var(--cx-hover); color: rgb(var(--v-theme-on-surface)); }
.set-nav-back-btn .mdi { font-size: 17px; }
.set-nav-search { position: relative; margin-bottom: 12px; }
.set-nav-search .mdi { position: absolute; left: 9px; top: 7px; color: rgb(var(--v-theme-secondary)); font-size: 16px; pointer-events: none; }
.set-nav-search input {
  width: 100%; height: 31px; padding: 0 10px 0 30px; border: 0; border-radius: 9px;
  outline: 0; background: rgb(var(--v-theme-surface)); color: rgb(var(--v-theme-on-surface));
  font: inherit; font-size: 12px; box-shadow: inset 0 0 0 1px var(--cx-border-subtle);
}
.set-nav-search input::placeholder { color: rgb(var(--v-theme-secondary)); }
.set-shell--settings .set-nav-grp { padding: 14px 8px 5px; font-size: 12px; letter-spacing: 0; text-transform: none; }
.set-shell--settings .set-nav-item { height: 34px; padding: 0 9px; border-radius: 8px; }
.set-shell--settings .set-nav-item .mdi { font-size: 16px; }
.set-shell--settings .set-nav-item.active { background: var(--cx-hover-strong); }
.set-shell--settings .set-content { background: rgb(var(--v-theme-background)); }
.set-shell--settings .set-inner { max-width: 820px; padding: 20px 42px 72px; }
.set-shell--settings .set-h { margin-bottom: 28px; font-size: 24px; font-weight: 600; letter-spacing: -0.02em; }

.set-content { flex: 1 1 auto; min-width: 0; height: 100%; overflow: hidden; }
.set-inner { height: 100%; overflow-y: auto; max-width: 720px; margin: 0 auto; padding: 28px 32px 48px; }
.set-h { font-size: 20px; font-weight: 650; margin: 0 0 18px; }

/* ── Provider master-detail ── */
.prov { display: flex; width: 100%; height: 100%; min-height: 0; }
.prov-list {
  width: 248px; flex: 0 0 248px; height: 100%; display: flex; flex-direction: column;
  border-right: 1px solid var(--cx-border); background: rgb(var(--v-theme-surface-container));
}
.prov-list-hd { padding: 16px 14px 12px; border-bottom: 1px solid var(--cx-border); }
.prov-list-title { font-weight: 650; font-size: 14px; margin-bottom: 10px; }
.prov-search { max-width: 100%; }
.prov-search .cx-input { padding-left: 34px; }
.prov-search-icon { position: absolute; left: 10px; top: 9px; color: rgb(var(--v-theme-secondary)); font-size: 16px; pointer-events: none; }
.prov-list-body { flex: 1 1 auto; overflow-y: auto; padding: 8px; }
.prov-grp { font-size: 11px; font-weight: 650; letter-spacing: 0.04em; text-transform: uppercase; color: rgb(var(--v-theme-secondary)); padding: 10px 8px 4px; }
.prov-item {
  width: 100%; display: flex; align-items: center; gap: 10px; height: 40px; padding: 0 8px; margin: 1px 0;
  border: 0; border-radius: var(--cx-radius); background: transparent; color: rgb(var(--v-theme-on-surface));
  font: inherit; font-size: 13px; text-align: left; cursor: pointer;
  transition: background 0.12s ease;
}
.prov-item:hover { background: var(--cx-hover); }
.prov-item.active { background: var(--cx-hover-strong); }
.prov-logo {
  width: 26px; height: 26px; border-radius: 7px; flex: 0 0 auto;
  display: grid; place-items: center; color: #fff; font-weight: 700; font-size: 12px;
}
.prov-logo--lg { width: 40px; height: 40px; border-radius: 10px; font-size: 16px; }
.prov-name { flex: 1 1 auto; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.prov-on { width: 7px; height: 7px; border-radius: 50%; background: rgb(var(--v-theme-tertiary)); flex: 0 0 auto; }

.prov-detail { flex: 1 1 auto; min-width: 0; height: 100%; overflow-y: auto; }
.prov-detail-inner { max-width: 680px; margin: 0 auto; padding: 26px 30px 48px; }
.prov-head { display: flex; align-items: center; gap: 14px; margin-bottom: 22px; }
.prov-head-ti { flex: 1 1 auto; min-width: 0; }
.prov-head-ti h2 { margin: 0; font-size: 17px; font-weight: 650; display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.prov-domain { font-size: 12px; font-weight: 400; }
.prov-status { font-size: 12px; margin-top: 3px; }
.prov-card-h { margin: 0 0 4px; font-size: 15px; font-weight: 650; }
.prov-card-desc { font-size: 12.5px; margin: 0 0 14px; }
.prov-divider { height: 1px; background: var(--cx-border-subtle); margin-bottom: 14px; }

/* Enable toggle (on = this provider is the active mode) */
.prov-toggle { position: relative; display: inline-flex; width: 40px; height: 22px; flex: 0 0 auto; cursor: pointer; }
.prov-toggle input { position: absolute; opacity: 0; width: 100%; height: 100%; margin: 0; cursor: pointer; }
.prov-toggle__track { width: 40px; height: 22px; border-radius: 999px; background: rgb(var(--v-theme-surface-variant)); transition: background .15s ease; }
.prov-toggle__thumb { position: absolute; top: 3px; left: 3px; width: 16px; height: 16px; border-radius: 50%; background: rgb(var(--v-theme-surface)); transition: transform .15s ease; box-shadow: 0 1px 2px rgba(0,0,0,.3); }
.prov-toggle input:checked ~ .prov-toggle__track { background: rgb(var(--v-theme-primary)); }
.prov-toggle input:checked ~ .prov-toggle__thumb { transform: translateX(18px); }

/* API key row with attached show/check mini buttons */
.prov-key-row { display: flex; align-items: stretch; }
.prov-key-input { flex: 1 1 auto; border-radius: var(--cx-radius) 0 0 var(--cx-radius); }
.prov-key-mini { width: 40px; flex: 0 0 auto; display: grid; place-items: center; border: 1px solid var(--cx-border); border-left: 0; background: rgb(var(--v-theme-surface)); color: rgb(var(--v-theme-secondary)); cursor: pointer; }
.prov-key-mini:last-child { border-radius: 0 var(--cx-radius) var(--cx-radius) 0; }
.prov-key-mini:hover { background: var(--cx-hover); color: rgb(var(--v-theme-on-surface)); }
.prov-key-mini .mdi { font-size: 18px; }

/* Models card */
.prov-models-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.prov-filter-chips { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 12px; }
.prov-filter-chips .cx-chip { cursor: pointer; }
.prov-add-model { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.prov-add-model .cx-input { flex: 1 1 auto; }
.prov-cap-select { width: auto; max-width: 130px; }
.prov-model-list { display: flex; flex-direction: column; gap: 2px; }
.prov-model-row { display: flex; align-items: center; gap: 10px; height: 38px; padding: 0 4px; border-radius: var(--cx-radius); }
.prov-model-row:hover { background: var(--cx-hover); }
.prov-model-row .mdi-lightning-bolt { color: rgb(var(--v-theme-secondary)); font-size: 16px; }
.prov-model-name { flex: 1 1 auto; font-family: 'SF Mono','JetBrains Mono',ui-monospace,monospace; font-size: 12.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.prov-model-empty { font-size: 13px; padding: 8px 0; }

.mcp-page, .mcp-detail { height: 100%; overflow-y: auto; padding: 28px 32px 48px; max-width: 980px; margin: 0 auto; }
.mcp-page-head, .mcp-detail-head { display: flex; align-items: center; gap: 12px; margin-bottom: 22px; }
.mcp-page-head > div:first-child { flex: 1 1 auto; min-width: 0; }
.mcp-page-head .set-h { margin-bottom: 4px; }
.mcp-page-actions { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; }
.mcp-page-subtitle { font-size: 12px; }
.mcp-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.mcp-search { flex: 1 1 auto; max-width: 320px; }
.mcp-search .cx-input, .mcp-tool-search .cx-input { padding-left: 32px; }
.mcp-search-icon { position: absolute; left: 10px; top: 9px; color: rgb(var(--v-theme-secondary)); font-size: 16px; pointer-events: none; }
.mcp-filter { width: auto; min-width: 140px; }
.mcp-table { border-top: 1px solid var(--cx-border); }
.mcp-table-head, .mcp-server-row { display: grid; grid-template-columns: minmax(220px, 1.7fr) minmax(70px, .6fr) minmax(120px, .9fr) 60px 108px; align-items: center; gap: 14px; }
.mcp-table-head { min-height: 34px; padding: 0 12px; color: rgb(var(--v-theme-secondary)); font-size: 11px; text-transform: uppercase; letter-spacing: .04em; }
.mcp-server-row { width: 100%; min-height: 62px; padding: 8px 12px; border: 0; border-top: 1px solid var(--cx-border-subtle); background: transparent; color: rgb(var(--v-theme-on-surface)); text-align: left; cursor: pointer; transition: background .12s ease; }
.mcp-server-row:hover { background: var(--cx-hover); }
.mcp-server-name { min-width: 0; display: flex; align-items: center; gap: 8px; }
.mcp-avatar { width: 27px; height: 27px; display: grid; place-items: center; flex: 0 0 auto; border-radius: 7px; background: rgb(var(--v-theme-primary)); color: rgb(var(--v-theme-on-primary)); font-weight: 700; font-size: 12px; }
.mcp-avatar--large { width: 38px; height: 38px; border-radius: 10px; font-size: 16px; }
.mcp-status-dot { width: 7px; height: 7px; flex: 0 0 auto; border-radius: 50%; background: rgb(var(--v-theme-secondary)); opacity: .45; }
.mcp-status-dot--connected { background: rgb(var(--v-theme-success)); opacity: 1; }
.mcp-status-dot--error { background: rgb(var(--v-theme-error)); opacity: 1; }
.mcp-status-dot--disconnected { background: rgb(var(--v-theme-warning)); opacity: 1; }
.mcp-status-dot--disabled { background: rgb(var(--v-theme-secondary)); }
.mcp-server-name-text { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.mcp-server-name-text strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; font-weight: 600; }
.mcp-server-name-text small { color: rgb(var(--v-theme-secondary)); font-size: 11px; }
.mcp-table-muted { color: rgb(var(--v-theme-secondary)); font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mcp-type-badge, .mcp-status-badge { display: inline-flex; align-items: center; min-height: 21px; padding: 0 8px; border: 1px solid var(--cx-border); border-radius: 999px; color: rgb(var(--v-theme-secondary)); font-size: 11px; white-space: nowrap; }
.mcp-type-badge--sse { color: rgb(var(--v-theme-success)); border-color: rgba(var(--v-theme-success), .35); }
.mcp-type-badge--streamable_http { color: rgb(var(--v-theme-info)); border-color: rgba(var(--v-theme-info), .35); }
.mcp-row-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; }
.mcp-icon-btn, .mcp-back-btn { width: 28px; height: 28px; display: grid; place-items: center; border: 0; border-radius: 50%; background: transparent; color: rgb(var(--v-theme-secondary)); cursor: pointer; }
.mcp-icon-btn:hover, .mcp-back-btn:hover { background: var(--cx-hover-strong); color: rgb(var(--v-theme-on-surface)); }
.mcp-switch { position: relative; display: inline-flex; width: 34px; height: 19px; flex: 0 0 auto; cursor: pointer; }
.mcp-switch input { position: absolute; inset: 0; z-index: 1; opacity: 0; margin: 0; cursor: pointer; }
.mcp-switch span { width: 34px; height: 19px; border-radius: 999px; background: rgb(var(--v-theme-surface-variant)); transition: background .15s ease; }
.mcp-switch span::after { content: ''; position: absolute; top: 3px; left: 3px; width: 13px; height: 13px; border-radius: 50%; background: rgb(var(--v-theme-surface)); box-shadow: 0 1px 2px rgba(0,0,0,.3); transition: transform .15s ease; }
.mcp-switch input:checked + span { background: rgb(var(--v-theme-success)); }
.mcp-switch input:checked + span::after { transform: translateX(15px); }
.mcp-switch--large { width: 40px; height: 22px; margin-left: auto; }
.mcp-switch--large span { width: 40px; height: 22px; }
.mcp-switch--large span::after { width: 16px; height: 16px; }
.mcp-switch--large input:checked + span::after { transform: translateX(18px); }
.mcp-empty { display: flex; min-height: 230px; align-items: center; justify-content: center; flex-direction: column; gap: 7px; color: rgb(var(--v-theme-secondary)); text-align: center; }
.mcp-empty .mdi { font-size: 28px; opacity: .6; margin-bottom: 3px; }
.mcp-empty strong { color: rgb(var(--v-theme-on-surface)); font-size: 13px; }
.mcp-empty span { font-size: 12px; max-width: 330px; }
.mcp-empty--small { min-height: 170px; border: 1px dashed var(--cx-border); border-radius: var(--cx-radius); }
.mcp-detail { max-width: 820px; padding-top: 22px; }
.mcp-detail-head { margin-bottom: 16px; }
.mcp-detail-title { min-width: 0; display: flex; align-items: center; flex-wrap: wrap; gap: 8px; flex: 1 1 auto; }
.mcp-detail-title h2 { margin: 0; font-size: 17px; font-weight: 650; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mcp-status-badge--connected { color: rgb(var(--v-theme-success)); border-color: rgba(var(--v-theme-success), .35); }
.mcp-status-badge--error { color: rgb(var(--v-theme-error)); border-color: rgba(var(--v-theme-error), .35); }
.mcp-status-badge--disabled { color: rgb(var(--v-theme-secondary)); }
.mcp-tabs { display: flex; gap: 4px; overflow-x: auto; padding: 4px; margin-bottom: 18px; border-radius: 999px; background: rgb(var(--v-theme-surface-container)); }
.mcp-tab { min-height: 30px; padding: 0 12px; border: 0; border-radius: 999px; background: transparent; color: rgb(var(--v-theme-secondary)); font: inherit; font-size: 12px; white-space: nowrap; cursor: pointer; }
.mcp-tab:hover { color: rgb(var(--v-theme-on-surface)); }
.mcp-tab.active { background: rgb(var(--v-theme-surface)); color: rgb(var(--v-theme-on-surface)); box-shadow: 0 1px 3px rgba(0,0,0,.18); }
.mcp-tab span { margin-left: 4px; opacity: .65; }
.mcp-detail-alert { margin-bottom: 14px; }
.mcp-detail-body { min-height: 260px; padding-bottom: 16px; }
.mcp-form-section { padding: 0 0 18px; margin-bottom: 18px; border-bottom: 1px solid var(--cx-border-subtle); }
.mcp-section-title { display: flex; align-items: baseline; gap: 8px; margin-bottom: 12px; font-size: 14px; font-weight: 650; }
.mcp-section-title span { color: rgb(var(--v-theme-secondary)); font-size: 11px; font-weight: 400; }
.mcp-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.mcp-textarea { min-height: 74px; resize: vertical; font-family: 'SF Mono','JetBrains Mono',ui-monospace,monospace; font-size: 12px; }
.mcp-tab-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.mcp-tab-toolbar > div:first-child { display: flex; flex-direction: column; gap: 3px; }
.mcp-tab-toolbar .cx-muted { font-size: 12px; }
.mcp-tool-search { width: 190px; }
.mcp-tool-list, .mcp-resource-list { display: flex; flex-direction: column; border-top: 1px solid var(--cx-border); }
.mcp-tool-row, .mcp-resource-row { display: flex; align-items: center; gap: 10px; min-height: 44px; padding: 0 10px; border: 0; border-bottom: 1px solid var(--cx-border-subtle); background: transparent; color: rgb(var(--v-theme-on-surface)); text-align: left; font: inherit; font-size: 13px; cursor: pointer; }
.mcp-tool-row:hover, .mcp-tool-row.active { background: var(--cx-hover); }
.mcp-tool-row .mdi:first-child, .mcp-resource-row > .mdi { color: rgb(var(--v-theme-secondary)); font-size: 17px; }
.mcp-tool-row span { flex: 1; font-family: 'SF Mono','JetBrains Mono',ui-monospace,monospace; font-size: 12px; overflow: hidden; text-overflow: ellipsis; }
.mcp-resource-row > div { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.mcp-resource-row small { color: rgb(var(--v-theme-secondary)); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mcp-call-panel { margin-top: 18px; padding: 14px; border: 1px solid var(--cx-border); border-radius: var(--cx-radius); background: rgb(var(--v-theme-surface-container)); }
.mcp-call-title { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.mcp-call-title code { color: rgb(var(--v-theme-primary)); font-size: 12px; }
.mcp-call-input { width: 100%; min-height: 100px; }
.mcp-call-actions, .mcp-detail-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.mcp-call-actions { margin-top: 10px; }
.mcp-call-result { max-height: 260px; margin: 12px 0 0; overflow: auto; padding: 12px; border-radius: var(--cx-radius); background: rgb(var(--v-theme-background)); color: rgb(var(--v-theme-on-surface)); font: 12px/1.5 'SF Mono','JetBrains Mono',ui-monospace,monospace; white-space: pre-wrap; }
.mcp-info-card { display: flex; gap: 12px; padding: 14px; margin-bottom: 16px; border: 1px solid var(--cx-border); border-radius: var(--cx-radius); background: rgb(var(--v-theme-surface-container)); }
.mcp-info-card > .mdi { color: rgb(var(--v-theme-primary)); font-size: 20px; }
.mcp-info-card p { margin: 5px 0 0; color: rgb(var(--v-theme-secondary)); font-size: 12px; line-height: 1.5; }
.mcp-meta-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1px; overflow: hidden; border: 1px solid var(--cx-border); border-radius: var(--cx-radius); background: var(--cx-border); }
.mcp-meta-grid > div { display: flex; min-height: 74px; flex-direction: column; gap: 5px; padding: 12px; background: rgb(var(--v-theme-surface)); }
.mcp-meta-grid small { color: rgb(var(--v-theme-secondary)); font-size: 11px; }
.mcp-meta-grid strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; font-weight: 500; }
.mcp-loading { display: flex; align-items: center; justify-content: center; min-height: 160px; gap: 8px; color: rgb(var(--v-theme-secondary)); font-size: 12px; }
.mcp-detail-footer { min-height: 52px; padding-top: 14px; border-top: 1px solid var(--cx-border); }
.mcp-footer-actions { display: flex; align-items: center; gap: 8px; }
@media (max-width: 760px) { .mcp-page, .mcp-detail { padding: 20px 16px 36px; } .mcp-table-head { display: none; } .mcp-server-row { grid-template-columns: minmax(0, 1fr) auto; gap: 8px; } .mcp-server-row > :nth-child(2), .mcp-server-row > :nth-child(3), .mcp-server-row > :nth-child(4) { display: none; } .mcp-form-grid, .mcp-meta-grid { grid-template-columns: 1fr; } .mcp-tab-toolbar { align-items: flex-start; flex-direction: column; } .mcp-tool-search { width: 100%; } }
</style>
