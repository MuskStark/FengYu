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

// jsdom also lacks `visualViewport`, which Vuetify's overlay location
// strategy (used by `v-snackbar`, `v-menu`, etc.) reads via `util/box.ts`.
// Without it, mounting any overlay component throws `ReferenceError:
// visualViewport is not defined` as an unhandled rejection. This minimal stub
// mirrors the ResizeObserver shim: it satisfies Vuetify without measuring.
if (typeof globalThis.visualViewport === 'undefined') {
  globalThis.visualViewport = {
    width: 1024,
    height: 768,
    offsetLeft: 0,
    offsetTop: 0,
    pageLeft: 0,
    pageTop: 0,
    scale: 1,
    onresize: null,
    onscroll: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  } as unknown as typeof globalThis.visualViewport
}
