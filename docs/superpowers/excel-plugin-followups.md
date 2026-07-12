# Excel Plugin Migration — Deferred Follow-ups

These are non-blocking findings from the final whole-branch review of the Excel-plugin
migration (branch `excel-plugin-migration`, merged into `4.0.0-FengYu` at `d7fbe49`).
The final review verdict was **READY TO MERGE** — none of these block the merge. They are
recorded here for unified handling on the 4.0.0 branch.

Spec: `docs/superpowers/specs/2026-07-12-excel-plugin-migration-design.md`
Plan: `docs/superpowers/plans/2026-07-12-excel-plugin-migration.md`

## Important

### I1 — `ExcelSessionStore` grows unbounded (in-memory leak)
`OfficialPlugins/plugin-excel/.../ExcelSessionStore.java` — every web/UI `invoke` calls
`get(session)` which `computeIfAbsent`s a `SplitConfig` (holds the full `analysisResult`
header map + paths), keyed by session UUID. The only eviction is `remove()`, called *only*
by `ExcelCancelTool` on the fixed `"ai"` key. The web `DELETE /api/plugins/{id}/files`
removes only the filesystem workspace, never the in-memory config; the wizard's `fileIo.ts`
never calls DELETE; `WorkspaceSweepJob` sweeps files only. So each analyzed web/desktop
session leaves a `SplitConfig` resident for the process lifetime.
- **Impact:** bounded per-entry (headers + paths, not row data) but unbounded over process life.
- **Fix:** have `PluginFileController.delete` also drop the in-memory session (e.g. a
  `cleanup` invoke action wiring to `sessions.remove`), or give `ExcelSessionStore` a
  TTL/size cap hooked to the sweep job.

## Minor — data correctness

### M2 — UI BY_COLUMN keeps a stale/`-1` index on no-match; produces `..._INVALID.xlsx` reported as success
`ExcelPlugin.configure` (UI path) resolves `splitColumn` text→index; on no match it leaves
`splitColumnIndex` unchanged (default `-1` or a stale value from a prior configure). Then
`ExcelSplitter.splitByColumn` groups every row under `normalizeOrInvalid(null)` → one
`..._INVALID.xlsx` file, reported as success. The AI path (`ExcelConfigureTool`) errors on
the same condition.
- **Trigger:** pick sheet A + column "Region", switch to sheet B (no "Region"), don't reselect
  the column, split → one garbage INVALID file reported as success.
- **Fix:** in `ExcelPlugin.configure`, throw `IllegalArgumentException` when `splitColumn`
  doesn't resolve in `splitSheet` (mirror the AI path); and/or clear `splitColumn` in the Vue
  `splitSheet` watcher. Best done together with the shared-helper extraction below (#7).

## Minor

### M1 — `ExcelExecuteTool` `mode == null` guard is dead code
`SplitConfig.mode` defaults to `BY_SHEET`, so the guard never fires; an agent that calls
`excel_analyze` then jumps straight to `excel_execute` silently produces a by-sheet split
instead of the "call excel_configure first" error the guard promises. Track a "configured"
flag explicitly, or make `mode` nullable with the default applied only on the UI path.

### M3 — `deriveOutDir` fallback returns a file path as a directory
`fileIo.ts` — if the `/in/` marker is absent it returns the input unchanged (a file, not a
dir). Currently unreachable (web upload paths always contain `/in/`; desktop doesn't use it).
Add a guard/throw for safety against future refactors.

## Deferred (from per-task reviews)

1. **COMPLEX ignores `filePrefix`** — the COMPLEX split path names files `<stem>_<value>.xlsx`,
   bypassing the `filePrefix` that BY_SHEET/BY_COLUMN apply (pre-existing v3.2.0 behavior,
   preserved verbatim). The wizard shows the prefix field for all three modes, so a COMPLEX
   user's prefix is silently ignored. Fix: route COMPLEX names through `outputFileName()`, or
   hide the prefix field when `mode==='COMPLEX'`. Note in `docs/plugins/excel.md`.
2. **`configure` missing `sheetName` → literal `"null"` string** — `String.valueOf(map.get("sheetName"))`
   yields `"null"` rather than an error (only reachable via a malformed direct `invoke`;
   ExcelSplitter then skips the null sheet). Use `getOrDefault`/explicit null-check for symmetry.
3. **`unknownActionThrows` test** conflates the missing-session guard with the switch default
   (both throw IAE) — doesn't isolate the two. Test-quality only.
4. **Descriptor test asserts 3/9 fields** — add cheap assertions for uiEntry/category/icon/etc.
5. **`excel_complex_config` add uses primitive `int`** — an LLM omitting headerIndex/columnIndex
   gets silent `0/0` (looks like a valid split-by-column-0) instead of an error. Brief-mandated
   signature; mitigate with a firmer `@Tool` description or a `columnIndex<=0 && !copyAll → err`
   guard.
6. **`ExcelExecuteTool` swallows `createDirectories` exception** — muddier downstream error
   message; let it propagate through the existing catch.
7. **BY_COLUMN header→index resolution duplicated** in both `ExcelConfigureTool` (AI) and
   `ExcelPlugin.configure` (UI) — behaviorally identical today, but a drift risk. Extract a
   shared `resolveColumnIndex(analysisResult, sheet, column)` helper; fixes M2 at the same time.
8. **AI-tool test coverage gaps** — `ExcelComplexConfigTool` add/list/clear and
   `ExcelConfigureTool` unknown-sheet/column/COMPLEX-no-entries paths are untested; add
   opportunistically.

## Cross-cutting (informational, from final review — no action required)

- **AI-tool session seam:** `analyze`/`cancel` use the fixed `"ai"` session key, but
  `configure`/`execute`/`query`/`complex_config` operate on `sessions.active()` — a single
  global pointer the UI path also moves. Low-probability under single-user/loopback (you're
  either in the wizard or the chat). If belt-and-suspenders is wanted, have the AI tools
  resolve the `"ai"` session explicitly instead of via `active()`.
- **jackson-databind pin (2.21.4, provided)** in `plugin-excel/pom.xml` — compile-only (root
  pom doesn't manage jackson); keep in sync if the app's transitive jackson version changes.
