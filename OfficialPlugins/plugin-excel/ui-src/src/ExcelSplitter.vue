<script setup lang="ts">
import { computed, inject, ref } from 'vue';
import type { PluginUiContext } from './pluginUi';
import { deriveOutDir, downloadArchive, uploadFile } from './fileIo';

// Host context is provided by main.ts via app.provide('pluginCtx', ctx).
const ctx = inject<PluginUiContext>('pluginCtx')!;

type SplitMode = 'BY_SHEET' | 'BY_COLUMN' | 'COMPLEX';

interface AnalyzeResponse {
  success: boolean;
  summary?: string;
  sheets?: Record<string, Record<string, string>>;
  error?: string;
}
interface ConfigureResponse {
  success: boolean;
  summary?: string;
  error?: string;
}
interface SplitResponse {
  success: boolean;
  summary?: string;
  fileCount?: number;
  files?: string[];
  error?: string;
}
interface ComplexEntryRow {
  fieldName: string;
  sheetName: string;
  headerIndex: number;
  columnIndex: number;
  copyAll: boolean;
}

const step = ref(1);

// Step 1 — source
const sourceFile = ref<string | null>(null);
const fileName = ref<string | null>(null);
const session = ref<string | null>(null);
const uploading = ref(false);
const analyzing = ref(false);
const analyzeError = ref<string | null>(null);
const sheets = ref<Record<string, Record<string, string>> | null>(null);

// Step 2 — mode + config
const mode = ref<SplitMode>('BY_SHEET');
const selectedSheets = ref<string[]>([]);
const splitSheet = ref<string | null>(null);
const splitColumn = ref<string | null>(null);
const filePrefix = ref('');
const complexEntries = ref<ComplexEntryRow[]>([]);
const configuring = ref(false);
const configureError = ref<string | null>(null);

// Step 3 — output
const outputDir = ref<string | null>(null);

// Step 4 — run
const running = ref(false);
const downloading = ref(false);
const runError = ref<string | null>(null);
const result = ref<{ fileCount: number; files: string[] } | null>(null);

const sheetNames = computed<string[]>(() => (sheets.value ? Object.keys(sheets.value) : []));

function columnsForSheet(sheetName: string | null): string[] {
  if (!sheetName || !sheets.value) return [];
  return Object.values(sheets.value[sheetName] ?? {});
}
const columnsForSplitSheet = computed<string[]>(() => columnsForSheet(splitSheet.value));

const canProceedStep1 = computed(() => !!sourceFile.value && !!sheets.value && !analyzing.value && !uploading.value);
const canProceedStep2 = computed(() => {
  if (mode.value === 'BY_SHEET') return true; // empty selection means "all sheets"
  if (mode.value === 'BY_COLUMN') return !!splitSheet.value && !!splitColumn.value;
  if (mode.value === 'COMPLEX') {
    return complexEntries.value.length > 0 && complexEntries.value.every((e) => !!e.sheetName);
  }
  return false;
});
const canGoNext = computed(() => {
  if (step.value === 1) return canProceedStep1.value;
  if (step.value === 2) return canProceedStep2.value;
  if (step.value === 3) return !ctx.desktop || !!outputDir.value;
  return false;
});

function notifyErr(msg: string): void {
  ctx.notify?.(msg);
}

async function runAnalyze(): Promise<void> {
  if (!sourceFile.value || !session.value) return;
  analyzing.value = true;
  analyzeError.value = null;
  try {
    const res = (await ctx.api.invoke('analyze', {
      session: session.value,
      sourceFile: sourceFile.value
    })) as AnalyzeResponse;
    if (!res.success) {
      const msg = res.error ?? 'Analyze failed';
      analyzeError.value = msg;
      notifyErr(msg);
      return;
    }
    sheets.value = res.sheets ?? {};
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    analyzeError.value = msg;
    notifyErr(msg);
  } finally {
    analyzing.value = false;
  }
}

async function pickDesktopFile(): Promise<void> {
  if (!ctx.desktop) return;
  analyzeError.value = null;
  try {
    const picked = await ctx.desktop.pickFile([{ name: 'Excel', extensions: ['xlsx', 'xls'] }]);
    if (!picked) return;
    sourceFile.value = picked;
    fileName.value = picked.split(/[/\\]/).pop() ?? picked;
    session.value = crypto.randomUUID();
    await runAnalyze();
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    analyzeError.value = msg;
    notifyErr(msg);
  }
}

async function onWebFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  analyzeError.value = null;
  uploading.value = true;
  try {
    const uploaded = await uploadFile(ctx, file);
    session.value = uploaded.session;
    sourceFile.value = uploaded.path;
    fileName.value = file.name;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    analyzeError.value = msg;
    notifyErr(msg);
    return;
  } finally {
    uploading.value = false;
  }
  await runAnalyze();
}

function addComplexEntry(): void {
  complexEntries.value.push({
    fieldName: '',
    sheetName: sheetNames.value[0] ?? '',
    headerIndex: 1,
    columnIndex: 1,
    copyAll: false
  });
}

function removeComplexEntry(index: number): void {
  complexEntries.value.splice(index, 1);
}

function onCopyAllToggle(entry: ComplexEntryRow): void {
  if (entry.copyAll) {
    entry.headerIndex = -1;
    entry.columnIndex = -1;
  } else {
    entry.headerIndex = 1;
    entry.columnIndex = 1;
  }
}

async function runConfigure(): Promise<void> {
  if (!session.value) return;
  configuring.value = true;
  configureError.value = null;
  try {
    const args: Record<string, unknown> = {
      session: session.value,
      mode: mode.value
    };
    if (filePrefix.value) args.filePrefix = filePrefix.value;
    if (mode.value === 'BY_SHEET') {
      args.selectedSheets = selectedSheets.value;
    } else if (mode.value === 'BY_COLUMN') {
      args.splitSheet = splitSheet.value;
      args.splitColumn = splitColumn.value;
    } else if (mode.value === 'COMPLEX') {
      args.complexEntries = complexEntries.value.map((e) => ({
        fieldName: e.fieldName,
        sheetName: e.sheetName,
        headerIndex: e.headerIndex,
        columnIndex: e.columnIndex
      }));
    }
    const res = (await ctx.api.invoke('configure', args)) as ConfigureResponse;
    if (!res.success) {
      const msg = res.error ?? 'Configure failed';
      configureError.value = msg;
      notifyErr(msg);
      throw new Error(msg);
    }
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    configureError.value = msg;
    notifyErr(msg);
    throw err;
  } finally {
    configuring.value = false;
  }
}

async function pickDesktopOutputDir(): Promise<void> {
  if (!ctx.desktop) return;
  const picked = await ctx.desktop.pickDirectory();
  if (picked) outputDir.value = picked;
}

async function runSplit(): Promise<void> {
  if (!session.value || !sourceFile.value) return;
  running.value = true;
  runError.value = null;
  result.value = null;
  try {
    const resolvedOutputDir = ctx.desktop ? outputDir.value : deriveOutDir(sourceFile.value);
    const res = (await ctx.api.invoke('split', {
      session: session.value,
      sourceFile: sourceFile.value,
      outputDir: resolvedOutputDir
    })) as SplitResponse;
    if (!res.success) {
      const msg = res.error ?? 'Split failed';
      runError.value = msg;
      notifyErr(msg);
      return;
    }
    result.value = { fileCount: res.fileCount ?? 0, files: res.files ?? [] };
    if (!ctx.desktop) {
      downloading.value = true;
      try {
        await downloadArchive(ctx, session.value);
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        runError.value = msg;
        notifyErr(msg);
      } finally {
        downloading.value = false;
      }
    }
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    runError.value = msg;
    notifyErr(msg);
  } finally {
    running.value = false;
  }
}

async function goNext(): Promise<void> {
  if (step.value === 2) {
    try {
      await runConfigure();
    } catch {
      return; // stay on step 2, error already surfaced
    }
  }
  if (step.value === 3) {
    outputDir.value = ctx.desktop ? outputDir.value : deriveOutDir(sourceFile.value ?? '');
  }
  if (step.value < 4) step.value += 1;
  if (step.value === 4) await runSplit();
}

function goBack(): void {
  if (step.value > 1) step.value -= 1;
}
</script>

<template>
  <v-container class="excel-splitter">
    <v-stepper v-model="step" :items="['Source', 'Mode', 'Output', 'Run']" hide-actions>
      <template #item.1>
        <v-card variant="flat">
          <v-card-text>
            <template v-if="ctx.desktop">
              <v-btn color="primary" :loading="analyzing" @click="pickDesktopFile">
                Choose file
              </v-btn>
            </template>
            <template v-else>
              <input
                type="file"
                accept=".xlsx,.xls"
                :disabled="uploading || analyzing"
                @change="onWebFileChange"
              />
            </template>

            <div v-if="fileName" class="mt-2 text-body-2">Selected: {{ fileName }}</div>

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

      <template #item.2>
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
                      <v-btn icon="mdi-delete" variant="text" size="small" @click="removeComplexEntry(i)" />
                    </td>
                  </tr>
                </tbody>
              </v-table>
              <v-btn class="mt-2" prepend-icon="mdi-plus" variant="tonal" @click="addComplexEntry">
                Add rule
              </v-btn>
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

      <template #item.3>
        <v-card variant="flat">
          <v-card-text>
            <template v-if="ctx.desktop">
              <v-btn color="primary" @click="pickDesktopOutputDir">Choose output folder</v-btn>
              <div v-if="outputDir" class="mt-2 text-body-2">Output: {{ outputDir }}</div>
            </template>
            <template v-else>
              <v-alert type="info" density="compact">
                Results will be packaged as a downloadable zip once the split finishes.
              </v-alert>
            </template>
          </v-card-text>
        </v-card>
      </template>

      <template #item.4>
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
              <div v-if="ctx.desktop && outputDir" class="text-body-2 mb-2">
                Output folder: {{ outputDir }}
              </div>
              <v-list density="compact">
                <v-list-item v-for="f in result.files" :key="f">{{ f }}</v-list-item>
              </v-list>
            </template>
          </v-card-text>
        </v-card>
      </template>
    </v-stepper>

    <div class="d-flex justify-space-between mt-4">
      <v-btn variant="text" :disabled="step === 1 || running" @click="goBack">Back</v-btn>
      <v-btn
        v-if="step < 4"
        color="primary"
        :disabled="!canGoNext"
        :loading="configuring"
        @click="goNext"
      >
        Next
      </v-btn>
    </div>
  </v-container>
</template>

<style scoped>
.excel-splitter {
  max-width: 960px;
}
</style>
