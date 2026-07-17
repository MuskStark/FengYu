# Offline Python Builder UI Repair Design

## Problem

The Offline Python Builder loads in the FengYu plugin iframe, but its content is covered by a Vuetify navigation-drawer scrim. The scrim receives pointer events, so actions such as **Open Project** cannot be clicked. The navigation rail also has no icons, leaving its labels clipped to fragments such as `C…` and `B…`.

Runtime inspection confirmed that the drawer simultaneously receives the `rail`, `temporary`, and `mobile` states. `FyPluginShell` decides that viewports at least 720 px wide are desktop, while Vuetify still treats the roughly 1014 px plugin iframe as mobile at its default breakpoint. This contradictory state creates a temporary drawer and full-content scrim even though the shell intends a permanent rail.

## Goals

- Restore pointer interaction throughout the plugin iframe.
- Keep the canonical FengYu `FyPluginShell` and Vuetify Material Design 3 component system.
- Use the official compact icon rail on desktop and a temporary labeled drawer only on narrow screens.
- Move project selection into the Build & Verify workflow.
- Make Build & Verify the default view.
- Preserve the selected project as shared state for Build & Verify and Config.
- Add regression coverage for the shared shell and Offline Python Builder composition.

## Non-goals

- Redesigning the plugin backend or JSON-RPC methods.
- Changing file permissions or the SDK file-reference contract.
- Replacing `FyPluginShell` with plugin-specific navigation.
- Refactoring unrelated official plugins.

## Chosen Approach

Fix the breakpoint conflict in the shared `FyPluginShell`, then make small Offline Python Builder composition changes.

Alternatives considered:

1. Add plugin-local CSS to hide the scrim. This would mask the symptom, leave the contradictory drawer state intact, and allow the shared bug to affect other plugins.
2. Replace `FyPluginShell` with tabs or a custom sidebar. This would restore interaction but move the plugin away from the official component composition and duplicate responsive behavior.
3. Align the shared drawer breakpoint and retain the official icon rail. This addresses the root cause and is the selected approach.

## Shared Shell Design

`FyPluginShell` will pass its `railBreakpoint` to Vuetify as the navigation drawer's mobile breakpoint. At and above that width, the drawer is a permanent rail without a scrim. Below it, the shell uses a temporary drawer controlled by the app-bar menu button.

The existing selection and auto-close behavior remains unchanged. A regression test will set a desktop-sized viewport and assert that the drawer is not temporary/mobile and that no active scrim intercepts the content.

## Offline Python Builder Navigation

The plugin will keep four navigation destinations and add official MDI icons:

- Build & Verify: build/tool icon
- Config: settings/tune icon
- Deploy: package/deploy icon
- Doctor: medical/diagnostic icon

Build & Verify becomes the initial active destination. The rail presents icons at desktop widths, while the temporary narrow-screen drawer exposes the translated labels.

## Project Selection Flow

The page-level **Open Project** action will be removed from `App.vue`. `BuildVerifyPanel` will own the visible `FyDirectoryPicker` and emit the selected `FileRef` upward. `App.vue` will retain the selected project as shared state and pass it back into BuildVerifyPanel and ConfigPanel.

The Build & Verify page will behave as follows:

1. No project selected: show an official empty state containing the directory picker and explanatory text.
2. Project selected: show the project name, a change-project action, build/verify/package actions, status, and logs.
3. Project changed: clear UI job/log state that belongs to the previous project before allowing a new build.

Config continues to consume the shared project. Without a selected project, it shows an official empty state explaining that a project must first be opened from Build & Verify.

Deploy and Doctor remain independent of the project selection.

## Official UI Compliance

- Continue using `@infinia/plugin-ui` and Vuetify components.
- Use the shared FengYu Vuetify theme and environment binding.
- Replace the incorrect `FyPageHeader` `subtitle` usage with its supported `description` API where a header remains necessary.
- Replace hard-coded log-surface RGB colors with semantic Vuetify theme styling.
- Use official MDI names through Vuetify rather than glyph literals.
- Route new and existing visible strings through the plugin translation table in English and Simplified Chinese.

## Error and Lifecycle Handling

- Directory-picker cancellation leaves the current project unchanged and shows no error toast.
- Picker permission and host errors remain handled by `FyDirectoryPicker` official states.
- Build, verify, and package RPC failures surface their returned summary through the official notification path.
- Build polling is cleared when a job completes, is cancelled, or the panel unmounts.
- Async handlers use `try/finally` so loading states do not remain stuck after rejected SDK calls.

## Testing

### Shared component tests

- Desktop width produces a permanent rail without an active scrim.
- Narrow width produces the temporary drawer and menu action.
- Navigation items emit selection and disabled items remain inert.

### Offline Python Builder tests

- Build & Verify is active initially.
- All rail items provide MDI icons.
- The page-level project picker is absent.
- Build & Verify contains the project picker and emits selection to shared state.
- Config receives the selected shared project.
- Source compliance continues to prohibit direct `postMessage`, direct `/api/` calls, embedded icon glyphs, and non-SDK bridges.

### Verification

- Run plugin-ui unit tests.
- Run Offline Python Builder UI tests and type checking.
- Build plugin-ui and the Offline Python Builder UI bundle.
- Load the plugin in the running FengYu frontend and verify visually at desktop and narrow widths.
- Confirm that the scrim no longer intercepts Build & Verify controls and that selecting/changing a project invokes the SDK picker.
