/**
 * Vitest setup for the @fengyu/plugin-ui component suite.
 *
 * jsdom does not implement `ResizeObserver`, but Vuetify's layout system
 * (`v-app`, `v-main`, `v-navigation-drawer`, `useDisplay`) installs one via
 * `useResizeObserver`. Mounting any layout component under jsdom throws
 * `ReferenceError: ResizeObserver is not defined` unless a minimal stub is
 * present. This no-op stub satisfies Vuetify without performing real
 * measurement; the components under test do not depend on observed sizes.
 */
class ResizeObserverStub {
  observe(): void {
    // intentionally empty — jsdom layouts are not observed
  }
  unobserve(): void {
    // intentionally empty
  }
  disconnect(): void {
    // intentionally empty
  }
}

// `globalThis` is the same object jsdom's `window` resolves to.
globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver
