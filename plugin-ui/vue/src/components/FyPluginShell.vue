<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDisplay } from 'vuetify'

/**
 * A single navigation entry rendered by {@link FyPluginShell}.
 *
 * - `value` identifies the active item and is emitted via `update:modelValue`.
 * - `icon` is an MDI icon name (`mdi-*`) rendered with `v-icon`.
 * - `disabled` items do not emit on click.
 */
export interface FyNavItem {
  value: string
  title: string
  icon?: string
  disabled?: boolean
}

const props = withDefaults(
  defineProps<{
    /** Shell title shown in the navigation drawer header. */
    title?: string
    /** Navigation items to render. */
    items?: FyNavItem[]
    /** Active item value (v-model). */
    modelValue?: string
    /** Viewport width (px) below which the drawer becomes temporary. */
    railBreakpoint?: number
  }>(),
  {
    title: '',
    items: () => [],
    modelValue: undefined,
    railBreakpoint: 720,
  },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void
}>()

const { width } = useDisplay()

/**
 * Below `railBreakpoint` the drawer is temporary (overlays content and is
 * toggled by the built-in menu action); at or above it the drawer is permanent.
 * `useDisplay().width` is driven by Vuetify's resize observer and works under
 * jsdom, which is how the brief specifies the breakpoint be implemented.
 */
const temporary = computed(() => width.value < props.railBreakpoint)

/** Temporary drawer open state, toggled by the app-bar nav icon. */
const drawerOpen = ref(false)

function select(item: FyNavItem): void {
  if (item.disabled) return
  emit('update:modelValue', item.value)
  // Auto-close the temporary drawer after a selection so mobile users return
  // to the content.
  if (temporary.value) drawerOpen.value = false
}
</script>

<template>
  <v-app>
    <v-app-bar v-if="temporary" flat density="comfortable">
      <v-app-bar-nav-icon @click="drawerOpen = !drawerOpen" />
      <v-app-bar-title>{{ title }}</v-app-bar-title>
    </v-app-bar>

    <v-navigation-drawer
      :rail="!temporary"
      :temporary="temporary"
      :model-value="temporary ? drawerOpen : true"
      @update:model-value="drawerOpen = $event"
    >
      <div v-if="title && !temporary" class="fy-shell-title pa-4 text-h6">
        {{ title }}
      </div>
      <v-list nav>
        <v-list-item
          v-for="item in items"
          :key="item.value"
          :value="item.value"
          :active="modelValue === item.value"
          :disabled="item.disabled"
          :data-nav="item.value"
          @click="select(item)"
        >
          <template v-if="item.icon" #prepend>
            <v-icon :icon="item.icon" />
          </template>
          <v-list-item-title>{{ item.title }}</v-list-item-title>
        </v-list-item>
      </v-list>
    </v-navigation-drawer>

    <v-main>
      <slot />
    </v-main>
  </v-app>
</template>
