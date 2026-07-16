<script setup lang="ts">
import { computed, ref } from 'vue'
import { useFengYuClient, FyFilePicker, FyDirectoryPicker, FyStepWizard } from '@infinia/plugin-ui'
import type { FyWizardStep } from '@infinia/plugin-ui'
import type { FileRef } from '@infinia/plugin-sdk'

const client = useFengYuClient()

type SplitMode = 'BY_SHEET' | 'BY_COLUMN' | 'COMPLEX'

interface AnalyzeResponse {
  success: boolean
  summary?: string
  sheets?: Record<string, Record<string, string>>
  error?: string
}
interface ConfigureResponse { success: boolean; summary?: string; error?: string }
interface SplitResponse {
  success: boolean
  summary?: string
  fileCount?: number
  files?: string[]
  error?: string
}
interface ComplexEntryRow {
  fieldName: string
  sheetName: string
  headerIndex: number
  columnIndex: number
  copyAll: boolean
}

const steps: FyWizardStep[] = [
  { value: 'source', title: 'Source' },
  { value: 'mode', title: 'Mode' },
  { value: 'output', title: 'Output' },
  { value: 'run', title: 'Run' },
]
const step = ref<string>('source')

// Step 1 — source. sourceFileRef is the host FileRef (resolved to an absolute path
// by PluginProcessManager before reaching the worker).
const sourceFileRef = ref<FileRef | null>(null)
const session = ref<string | null>(null)
const analyzing = ref(false)
const analyzeError = ref<string | null>(null)
const sheets = ref<Record<string, Record<string, string>> | null>(null)

// Step 2 — mode + config
const mode = ref<SplitMode>('BY_SHEET')
const selectedSheets = ref<string[]>([])
const splitSheet = ref<string | null>(null)
const splitColumn = ref<string | null>(null)
const filePrefix = ref('')
const complexEntries = ref<ComplexEntryRow[]>([])
const configuring = ref(false)
const configureError = ref<string | null>(null)

// Step 3 — output
const outputDirRef = ref<FileRef | null>(null)

// Step 4 — run
const running = ref(false)
const downloading = ref(false)
const runError = ref<string | null>(null)
const result = ref<{ fileCount: number; files: string[] } | null>(null)

const sheetNames = computed<string[]>(() => (sheets.value ? Object.keys(sheets.value) : []))

function columnsForSheet(sheetName: string | null): string[] {
  if (!sheetName || !sheets.value) return []
  return Object.values(sheets.value[sheetName] ?? {})
}
const columnsForSplitSheet = computed<string[]>(() => columnsForSheet(splitSheet.value))

const canContinueSource = computed(() => !!sourceFileRef.value && !!sheets.value && !analyzing.value)
const canContinueMode = computed(() => {
  if (mode.value === 'BY_SHEET') return true // empty selection means "all sheets"
  if (mode.value === 'BY_COLUMN') return !!splitSheet.value && !!splitColumn.value
  if (mode.value === 'COMPLEX') {
    return complexEntries.value.length > 0 && complexEntries.value.every((e) => !!e.sheetName)
  }
  return false
})

function errMsg(err: unknown): string {
  if (err && typeof err === 'object') {
    const value = err as Record<string, unknown>
    if (typeof value.summary === 'string' && value.summary.trim()) return value.summary
    if (typeof value.message === 'string' && value.message.trim()) return value.message
  }
  return err instanceof Error && err.message ? err.message : String(err)
}

function notifyErr(msg: string): void {
  void client.notify(msg)
}

async function runAnalyze(): Promise<void> {
  if (!sourceFileRef.value) return
  analyzing.value = true
  analyzeError.value = null
  try {
    const res = await client.invoke<AnalyzeResponse>('analyze', {
      session: session.value,
      sourceFile: sourceFileRef.value,
    })
    if (!res.success) {
      const msg = res.error ?? 'Analyze failed'
      analyzeError.value = msg
      notifyErr(msg)
      return
    }
    sheets.value = res.sheets ?? {}
  } catch (err) {
    const msg = errMsg(err)
    analyzeError.value = msg
    notifyErr(msg)
  } finally {
    analyzing.value = false
  }
}

// FyFilePicker emits the FileRef once the host resolves it; then we analyze.
function onFilePicked(ref: FileRef | null): void {
  sourceFileRef.value = ref
  if (ref) {
    session.value = crypto.randomUUID()
    void runAnalyze()
  }
}

function addComplexEntry(): void {
  complexEntries.value.push({
    fieldName: '',
    sheetName: sheetNames.value[0] ?? '',
    headerIndex: 1,
    columnIndex: 1,
    copyAll: false,
  })
}

function removeComplexEntry(index: number): void {
  complexEntries.value.splice(index, 1)
}

function onCopyAllToggle(entry: ComplexEntryRow): void {
  if (entry.copyAll) {
    entry.headerIndex = -1
    entry.columnIndex = -1
  } else {
    entry.headerIndex = 1
    entry.columnIndex = 1
  }
}

async function runConfigure(): Promise<void> {
  if (!session.value) return
  configuring.value = true
  configureError.value = null
  try {
    const args: Record<string, unknown> = { session: session.value, mode: mode.value }
    if (filePrefix.value) args.filePrefix = filePrefix.value
    if (mode.value === 'BY_SHEET') {
      args.selectedSheets = selectedSheets.value
    } else if (mode.value === 'BY_COLUMN') {
      args.splitSheet = splitSheet.value
      args.splitColumn = splitColumn.value
    } else if (mode.value === 'COMPLEX') {
      args.complexEntries = complexEntries.value.map((e) => ({
        fieldName: e.fieldName,
        sheetName: e.sheetName,
        headerIndex: e.headerIndex,
        columnIndex: e.columnIndex,
      }))
    }
    const res = await client.invoke<ConfigureResponse>('configure', args)
    if (!res.success) {
      const msg = res.error ?? 'Configure failed'
      configureError.value = msg
      notifyErr(msg)
      throw new Error(msg)
    }
  } catch (err) {
    const msg = errMsg(err)
    configureError.value = msg
    notifyErr(msg)
    throw err
  } finally {
    configuring.value = false
  }
}

async function runSplit(): Promise<void> {
  if (!session.value || !sourceFileRef.value) return
  running.value = true
  runError.value = null
  result.value = null
  try {
    const res = await client.invoke<SplitResponse>('split', {
      session: session.value,
      sourceFile: sourceFileRef.value,
      outputDir: outputDirRef.value,
    })
    if (!res.success) {
      const msg = res.error ?? 'Split failed'
      runError.value = msg
      notifyErr(msg)
      return
    }
    result.value = { fileCount: res.fileCount ?? 0, files: res.files ?? [] }
    // Offer the output directory as a zip download via the host.
    if (outputDirRef.value) {
      downloading.value = true
      try {
        await client.files.export(outputDirRef.value)
      } catch (err) {
        const msg = errMsg(err)
        runError.value = msg
        notifyErr(msg)
      } finally {
        downloading.value = false
      }
    }
  } catch (err) {
    const msg = errMsg(err)
    runError.value = msg
    notifyErr(msg)
  } finally {
    running.value = false
  }
}

async function canContinue(from: string): Promise<boolean> {
  if (from === 'source') return canContinueSource.value
  if (from === 'mode') {
    if (!canContinueMode.value) return false
    try { await runConfigure(); return true }
    catch { return false }
  }
  if (from === 'output') return !!outputDirRef.value
  return true
}

async function onComplete(): Promise<void> {
  await runSplit()
}
</script>

<template>
  <v-app>
    <v-main>
      <v-container class="excel-splitter">
        <FyStepWizard v-model="step" :steps="steps" :can-continue="canContinue" @complete="onComplete">
          <template #source>
            <v-card variant="flat">
              <v-card-text>
                <FyFilePicker
                  :model-value="sourceFileRef"
                  :extensions="['xlsx', 'xls']"
                  :filters="[{ name: 'Excel', extensions: ['xlsx', 'xls'] }]"
                  label="Choose Excel file"
                  @update:model-value="onFilePicked"
                />

                <div v-if="sourceFileRef" class="mt-2 text-body-2">
                  Selected: {{ sourceFileRef.name }}
                </div>

                <v-alert v-if="analyzing" type="info" class="mt-3" density="compact">
                  Analyzing…
                </v-alert>
                <v-alert v-if="analyzeError" type="error" class="mt-3" density="compact">
                  {{ analyzeError }}
                </v-alert>

                <div v-if="sheets" class="mt-4">
                  <div class="text-subtitle-2 mb-2">Sheets</div>
                  <v-expansion-panels variant="accordion">
                    <v-expansion-panel v-for="name in sheetNames" :key="name" :title="name">
                      <v-expansion-panel-text>
                        <v-chip
                          v-for="col in columnsForSheet(name)"
                          :key="col"
                          size="small"
                          class="mr-1 mb-1"
                        >
                          {{ col }}
                        </v-chip>
                      </v-expansion-panel-text>
                    </v-expansion-panel>
                  </v-expansion-panels>
                </div>
              </v-card-text>
            </v-card>
          </template>

          <template #mode>
            <v-card variant="flat">
              <v-card-text>
                <v-radio-group v-model="mode" inline>
                  <v-radio label="By sheet" value="BY_SHEET" />
                  <v-radio label="By column" value="BY_COLUMN" />
                  <v-radio label="Complex" value="COMPLEX" />
                </v-radio-group>

                <v-select
                  v-if="mode === 'BY_SHEET'"
                  v-model="selectedSheets"
                  :items="sheetNames"
                  label="Sheets (leave empty for all)"
                  multiple
                  chips
                  clearable
                />

                <template v-else-if="mode === 'BY_COLUMN'">
                  <v-select v-model="splitSheet" :items="sheetNames" label="Sheet" />
                  <v-select
                    v-model="splitColumn"
                    :items="columnsForSplitSheet"
                    label="Column"
                    :disabled="!splitSheet"
                  />
                </template>

                <template v-else-if="mode === 'COMPLEX'">
                  <v-table density="compact">
                    <thead>
                      <tr>
                        <th>Field name</th>
                        <th>Sheet</th>
                        <th>Header row</th>
                        <th>Column</th>
                        <th>Copy entire sheet</th>
                        <th></th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="(entry, i) in complexEntries" :key="i">
                        <td>
                          <v-text-field v-model="entry.fieldName" density="compact" hide-details />
                        </td>
                        <td>
                          <v-select
                            v-model="entry.sheetName"
                            :items="sheetNames"
                            density="compact"
                            hide-details
                          />
                        </td>
                        <td>
                          <v-text-field
                            v-model.number="entry.headerIndex"
                            type="number"
                            density="compact"
                            hide-details
                            :disabled="entry.copyAll"
                          />
                        </td>
                        <td>
                          <v-text-field
                            v-model.number="entry.columnIndex"
                            type="number"
                            density="compact"
                            hide-details
                            :disabled="entry.copyAll"
                          />
                        </td>
                        <td>
                          <v-checkbox
                            v-model="entry.copyAll"
                            density="compact"
                            hide-details
                            @update:model-value="onCopyAllToggle(entry)"
                          />
                        </td>
                        <td>
                          <v-btn variant="text" size="small" @click="removeComplexEntry(i)">
                            Remove
                          </v-btn>
                        </td>
                      </tr>
                    </tbody>
                  </v-table>
                  <v-btn class="mt-2" variant="tonal" @click="addComplexEntry">Add rule</v-btn>
                </template>

                <v-text-field
                  v-model="filePrefix"
                  label="Output file prefix (optional)"
                  class="mt-4"
                />

                <v-alert v-if="configureError" type="error" class="mt-3" density="compact">
                  {{ configureError }}
                </v-alert>
              </v-card-text>
            </v-card>
          </template>

          <template #output>
            <v-card variant="flat">
              <v-card-text>
                <FyDirectoryPicker
                  :model-value="outputDirRef"
                  mode="output"
                  label="Choose output folder"
                  @update:model-value="(ref: FileRef | null) => (outputDirRef = ref)"
                />
                <div v-if="outputDirRef" class="mt-2 text-body-2">
                  Output: {{ outputDirRef.name }}
                </div>
                <v-alert type="info" density="compact" class="mt-3">
                  Results are written to this folder; after the split you can download it as a zip.
                </v-alert>
              </v-card-text>
            </v-card>
          </template>

          <template #run>
            <v-card variant="flat">
              <v-card-text>
                <div v-if="running" class="d-flex align-center">
                  <v-progress-circular indeterminate size="24" class="mr-2" />
                  Splitting…
                </div>

                <v-alert v-if="runError" type="error" density="compact">{{ runError }}</v-alert>

                <template v-if="result">
                  <v-alert type="success" density="compact" class="mb-3">
                    {{ result.fileCount }} file(s) written
                    <span v-if="downloading"> — preparing download…</span>
                  </v-alert>
                  <div v-if="outputDirRef" class="text-body-2 mb-2">
                    Output folder: {{ outputDirRef.name }}
                  </div>
                  <v-list density="compact">
                    <v-list-item v-for="f in result.files" :key="f">{{ f }}</v-list-item>
                  </v-list>
                </template>
              </v-card-text>
            </v-card>
          </template>
        </FyStepWizard>
      </v-container>
    </v-main>
  </v-app>
</template>

<style scoped>
.excel-splitter {
  max-width: 960px;
}
</style>
