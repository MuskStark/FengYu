<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDisplay } from 'vuetify'
import { mdiBackburger, mdiForwardburger } from '@mdi/js'
import { isSvgPathIcon } from '../icon'
import FyIcon from './FyIcon.vue'
import FyNotificationCenter from './FyNotificationCenter.vue'

/**
 * A single navigation entry rendered by {@link FyPluginShell}.
 *
 * - `value` identifies the active item and is emitted via `update:modelValue`.
 * - `icon` is either inline SVG path data (a string from `@mdi/js` beginning
 *   with `M`, rendered via {@link FyIcon}) or an `mdi-*` name rendered via
 *   Vuetify's bundled MDI icon set.
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
     * Shell title shown in the mobile (temporary) app bar. The desktop drawer
     * shows nav item titles by default (icon + label); the user can collapse it
     * to an icon-only rail via the toggle at the drawer top.
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
const hasNavigation = computed(() => props.items.length > 0)

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
 * Desktop rail collapse state. Defaults to `false` (expanded — icon + label),
 * so the nav item titles are visible on first open; the user can collapse to
 * an icon-only rail via the toggle pinned at the drawer top. Only meaningful
 * on the permanent (non-temporary) drawer — mobile mode is unaffected.
 */
const collapsed = ref(false)

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
    <v-app-bar v-if="hasNavigation && temporary" flat density="comfortable">
      <v-app-bar-nav-icon @click="drawerOpen = !drawerOpen" />
      <v-app-bar-title>{{ title }}</v-app-bar-title>
    </v-app-bar>

    <v-navigation-drawer
      v-if="hasNavigation"
      class="fy-shell__rail"
      :rail="!temporary && collapsed"
      :temporary="temporary"
      :mobile-breakpoint="railBreakpoint"
      :model-value="temporary ? drawerOpen : true"
      @update:model-value="drawerOpen = $event"
    >
      <div v-if="!temporary" class="fy-shell__brand">
        <span class="fy-shell__brand-mark" aria-hidden="true" />
        <span class="fy-shell__brand-title">{{ title }}</span>
      </div>
      <!-- Desktop-only rail collapse toggle. Mobile uses the app-bar hamburger. -->
      <div v-if="!temporary" class="fy-shell__rail-toggle">
        <v-btn
          icon
          variant="text"
          size="small"
          :aria-label="collapsed ? 'Expand navigation' : 'Collapse navigation'"
          :title="collapsed ? 'Expand navigation' : 'Collapse navigation'"
          data-action="toggle-rail"
          @click="collapsed = !collapsed"
        >
          <FyIcon :path="collapsed ? mdiForwardburger : mdiBackburger" :size="20" />
        </v-btn>
      </div>
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
            <FyIcon v-if="isSvgPathIcon(item.icon)" :path="item.icon" :size="24" />
            <v-icon v-else :icon="item.icon" />
          </template>
          <v-list-item-title>{{ item.title }}</v-list-item-title>
        </v-list-item>
      </v-list>
    </v-navigation-drawer>

    <v-main>
      <slot />
      <FyNotificationCenter />
    </v-main>
  </v-app>
</template>
