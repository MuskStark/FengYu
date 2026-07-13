# Sidebar Theme Persistence Fix

## Problem

When the application is using the light theme, collapsing or expanding the sidebar switches the UI back to dark. The sidebar theme control currently calls `useThemeStore().toggle()`, which updates the live Vuetify theme but does not persist the new value. A later sidebar setting update returns the backend's complete settings object, where the saved theme is still `dark`; `useSettingsStore().apply()` then reapplies that stale value.

## Design

Keep the existing responsibility split:

- `useThemeStore` applies a theme to Vuetify and notifies micro-frontends.
- `useSettingsStore` is the single entry point for user-initiated changes to persisted application settings.

The sidebar theme control will compute the next theme and call `settings.setTheme(next)` instead of calling `theme.toggle()` directly. `setTheme` already applies the theme immediately and then persists it through `PUT /api/settings`. Consequently, later sidebar collapse or expand updates receive and reapply the same saved theme.

No backend endpoint or settings schema changes are needed.

## Error Handling

Preserve existing behavior: the visual theme changes immediately, and persistence errors propagate through the existing asynchronous request path. This fix does not introduce a separate retry or rollback policy.

## Testing

Add a frontend regression test that exercises the sidebar's theme action through the persisted settings path. The test will prove that:

1. switching from dark to light persists `theme: "light"`;
2. subsequently persisting `sidebarCollapsed` does not restore the dark theme;
3. the live theme remains light after the full settings response is applied.

Run the focused regression test, the complete frontend test suite, and the frontend type-check/build.

## Scope

Only the sidebar theme action and its regression coverage are in scope. Theme architecture, API response shapes, and unrelated sidebar behavior remain unchanged.
