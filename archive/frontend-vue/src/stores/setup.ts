import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type {
  ConnectionTestResult,
  DbTypeMeta,
  SetupStatus,
  WizardParams,
} from '@/api/types'

export const useSetupStore = defineStore('setup', () => {
  const status = ref<SetupStatus | null>(null)
  const types = ref<DbTypeMeta[]>([])
  const selectedType = ref<string>('')
  const params = ref<WizardParams>({})
  const testResult = ref<ConnectionTestResult | null>(null)
  const testing = ref(false)
  const initializing = ref(false)
  const error = ref('')

  async function loadStatus() {
    status.value = await api.getSetupStatus()
    return status.value
  }

  async function loadTypes() {
    types.value = await api.getSetupTypes()
  }

  function selectType(t: string) {
    selectedType.value = t
    // Reset params to defaults for the selected type
    const meta = types.value.find((x) => x.type === t)
    params.value = {}
    if (meta) {
      for (const f of meta.fields) {
        if (f.default !== undefined) {
          ;(params.value as Record<string, unknown>)[f.name] = f.default
        }
      }
    }
    testResult.value = null
  }

  async function testConnection() {
    if (!selectedType.value) return
    testing.value = true
    testResult.value = null
    error.value = ''
    try {
      testResult.value = await api.testConnection({
        type: selectedType.value,
        params: params.value,
      })
    } catch (e) {
      error.value = String(e)
      testResult.value = { success: false, error: String(e) }
    } finally {
      testing.value = false
    }
  }

  async function initialize(): Promise<boolean> {
    if (!selectedType.value) return false
    initializing.value = true
    error.value = ''
    try {
      const res = await api.initializeSetup({
        type: selectedType.value,
        params: params.value,
      })
      if (!res.success) {
        error.value = res.error ?? 'Initialization failed'
        return false
      }
      return true
    } catch (e) {
      error.value = String(e)
      return false
    } finally {
      initializing.value = false
    }
  }

  return {
    status,
    types,
    selectedType,
    params,
    testResult,
    testing,
    initializing,
    error,
    loadStatus,
    loadTypes,
    selectType,
    testConnection,
    initialize,
  }
})
