<script setup lang="ts">
/**
 * {{pluginName}} — a FengYu plugin with a Vue UI backed by a Java JSON-RPC worker.
 *
 * The UI calls the worker's `hello` method through the host RPC bridge and
 * displays the returned greeting. This is the smallest end-to-end wiring of
 * UI → host → worker; real plugins add file pickers, task tables, etc.
 *
 * Plugin id: {{pluginId}}
 */
import { ref } from 'vue'
import { FyPluginShell, FyPageHeader, useFengYuClient } from '@fengyu/plugin-ui'

const client = useFengYuClient()
const greeting = ref('')
const busy = ref(false)

async function sayHello(): Promise<void> {
  busy.value = true
  try {
    const result = await client.invoke<{ message: string }>('hello', { name: 'FengYu' })
    greeting.value = result.message
  } catch (error) {
    greeting.value = String(error)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <FyPluginShell title="{{pluginName}}" :items="[{ value: 'home', title: 'Home', icon: 'mdi-home-outline' }]">
    <div class="pa-4">
      <FyPageHeader title="{{pluginName}}" description="A FengYu plugin with a Vue UI and a Java worker." />
      <v-btn color="primary" variant="flat" prepend-icon="mdi-hand-wave" :loading="busy" @click="sayHello">
        Say hello
      </v-btn>
      <p v-if="greeting" class="mt-4 text-h6">{{ greeting }}</p>
    </div>
  </FyPluginShell>
</template>
