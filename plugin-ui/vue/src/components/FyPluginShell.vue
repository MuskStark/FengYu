<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDisplay } from 'vuetify'
import FyIcon from './FyIcon.vue'

/**
 * A single navigation entry rendered by {@link FyPluginShell}.
 *
 * - `value` identifies the active item and is emitted via `update:modelValue`.
 * - `icon` is either inline SVG path data (preferred — a string from
 *   `@mdi/js` beginning with `M`, rendered via {@link FyIcon}) or a legacy
 *   `mdi-*` webfont name (rendered via `v-icon`; note the webfont is not
 *   reliably bundled and may show tofu squares, so prefer path data).
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
    /**
     * Shell title shown in the mobile (temporary) app bar. The desktop rail is
     * icon-only and renders neither the title nor any brand label.
     */
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

/**
 * True when an icon string is SVG path data (from `@mdi/js`) rather than a
 * legacy `mdi-*` webfont name. MDI path data always begins with an `M`/`m`
 * move command; webfont names always begin with `mdi-`. Path data renders via
 * inline {@link FyIcon} (no font, no CSP risk); names fall back to `v-icon`.
 */
function isIconPath(icon: string | undefined): boolean {
  return !!icon && /^m/i.test(icon)
}

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
      class="fy-shell__rail"
      :rail="!temporary"
      :temporary="temporary"
      :mobile-breakpoint="railBreakpoint"
      :model-value="temporary ? drawerOpen : true"
      @update:model-value="drawerOpen = $event"
    >
      <v-list nav class="fy-shell__list">
        <v-list-item
          v-for="item in items"
          :key="item.value"
          :class="['fy-shell__item', { 'fy-shell__item--active': modelValue === item.value }]"
          :value="item.value"
          :active="modelValue === item.value"
          :disabled="item.disabled"
          :data-nav="item.value"
          :title="temporary ? item.title : undefined"
          @click="select(item)"
        >
          <template v-if="item.icon" #prepend>
            <FyIcon v-if="isIconPath(item.icon)" :path="item.icon" :size="24" />
            <v-icon v-else :icon="item.icon" />
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
