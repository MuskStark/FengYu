# Changelog

All notable changes to FengYu. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased]

### ✨ Added
- **App updates now come from the store — it replaces the FY-Proxy update proxy.** The Windows
  portable self-updater targets the store's compat mirror
  (`/api/v1/compat/fengyu/fengyu-releases/api/releases/latest`, a GitHub-releases-compatible
  object from an APP listing's stable release with the mandatory SHA-256 digest) instead of the
  FY-Proxy path, the manual download page becomes the store's web app (`/web`), and the backend
  portable-web check keeps rejecting the channel with store-era wording. The lite deb feed keeps
  the legacy FY-Proxy contract until the store ships an electron-updater feed.
- **The Settings upgrade channel is now the single runtime route to the production store.**
  Production deploys the Infinia Store separately from the app, so the store base URL now
  resolves per request through `StoreEndpointProvider`: the existing 升级渠道 setting
  (`updateApiBase`) overrides the `fengyu.store.api-base` bootstrap property without a restart,
  and plugin installs/updates, cloud-account sign-in (OAuth + user center), the store status
  endpoint, and app-update checks all communicate through that one channel. Every resolution
  re-runs the SSRF policy — a channel may not point into a private network unless
  `fengyu.store.allow-private-network` is set — and the Settings copy now documents the channel's
  double role.
- **The account page is now a user center mirroring the store platform's.** After signing in to
  the Infinia cloud account, `/account` aggregates identity with the Infinia Level badge and
  next-level hint, role badges,
  role-aware quick links (plugin store, online user center, publisher/admin consoles for
  privileged roles), profile renaming, a library summary (favorites / entitlements / install
  counts plus top favorites), organization membership, and account security — password change,
  active-session list with revoke, and registered devices with revoke. Signed out, the page keeps
  a local-account card that starts the browser OAuth flow and surfaces in-page errors, with a
  skeleton while loading. New backend endpoints proxy the live store data over the cloud
  account's access token: `GET /api/account/store-profile` (adds `beeLevel`/`createdAt`),
  `PUT /api/account/profile`, `PUT /api/account/password`, and `/api/account/{library,
  organizations, sessions, devices}` with per-item DELETE; all answer 401 when signed out, and a
  display-name rename syncs the local binding so `/api/account/me` follows.
- **Desktop browser and Computer Use controls cover more real interaction paths.** Browser
  automation can now move backward/forward or reload, activate hover-only controls, send bounded
  CDP wheel input to the page or a nested scroller, select verified native `<select>` options,
  and press keys against the active page without inventing a selector. History changes invalidate
  stale element refs. Computer Use can run an ordered, bounded `computer_key_sequence` in one
  approved call for keyboard-driven navigation.

### 🐛 Fixed
- **Cloud sign-in works against the store's confidential desktop client.** The store
  platform registers `fengyu-desktop` as confidential (`client_secret_post` on top of PKCE,
  because Spring Authorization Server 7 no longer authenticates public clients on the
  refresh-token grant), which made FengYu's secret-less token exchange fail with
  `invalid_client`. Token and revocation requests now send the desktop secret via
  `client_secret_post`. The built-in default (`dev-only-desktop-secret`) pairs with the
  store's own development default, so local sign-in works with zero configuration; a
  production store sets `STORE_DESKTOP_CLIENT_SECRET` and deployments against it mirror the
  value via `fengyu.store.client-secret` (`FENGYU_STORE_CLIENT_SECRET`). An `invalid_client`
  rejection now surfaces with a hint naming that setting.

## [4.0.0-rc.1] — 2026-09-01

### ✨ Added
- **Flow AI authoring is now a reviewable RC workflow.** The docked Flow chat can inspect the
  live canvas and installed tool contracts, diagnose invalid arguments, unavailable tools,
  references, dangling edges, cycles, and the last run error, and generate complete Flow graphs
  from an empty canvas or edit an existing one. AI edits are non-mutating proposals: the builder
  shows node/connection changes, rejects stale snapshots, and runs the normal canvas validation,
  optimistic revision check, and save path only after the user chooses **Apply and save**.
- **The store platform ships with a closed trust chain.** Every store and remote-skill download
  must carry an attested SHA-256 and a platform Ed25519 signature from a key in the new
  `trusted-store-keys.json` registry (bundled + user-provided, with revocation), verified over
  the exact bytes while streaming through a size budget. Store, catalog, and CDN URLs must be
  HTTPS — plain HTTP only on loopback — and hosts resolving into private/link-local networks are
  blocked (shared SSRF policy for the store client and the skill marketplace), with bounded JSON
  responses everywhere.
- **Cloud sign-in is now an OAuth 2.1 public client.** The desktop app ships no client secret:
  authorization uses PKCE, the browser redirect lands on a one-time ephemeral loopback port
  (RFC 8252), and the callback renders a branded, localized result page. The access token lives
  only in memory with serialized refresh (server-side rotation is persisted exactly once); the
  refresh token only in the OS credential store — macOS Keychain, Windows Credential Manager, or
  Linux Secret Service — and a Flyway V2 migration removes the legacy token columns from the
  database.
- **`/api/plugin-market` lives on as a documented compatibility layer.** Its local-package
  lifecycle endpoints forward 1:1 to the new `/api/plugin-packages` surface with deprecation
  headers; the catalog endpoints superseded by the unified store answer `410 Gone` naming their
  replacement. The REST reference now documents the plugin-packages, unified store, Infinia
  Store, skills, and account surfaces in both languages.

### 🐛 Fixed
- **Windows desktop builds no longer white-screen on startup.** The release CSP hash for the
  inline Vue import map was computed over the raw HTML bytes, so the Windows build runner's CRLF
  checkout produced a token Chromium refuses (it normalizes inline scripts to LF before
  hashing); the import map was blocked, `vue` could not resolve, and the window stayed blank.
  The hash is now computed over LF-normalized content, and the desktop packaging gate
  (`verify:frontend-dist`) re-checks every inline script's CSP token instead of only verifying
  relative asset paths.
- **Store installs run the complete dependency plan as one journaled transaction.** The resolver
  plan executes dependency-first with per-artifact verification; the ledger binds all
  coordinates in a single save, plugin rollback snapshots release only after every installer,
  health preflight, and the ledger commit succeeded, and a failure — or a crash — rolls applied
  items back in reverse order on next startup. Store installs/updates/uninstalls of plugins now
  go through the same runtime gate as local uploads (worker stop, health preflight,
  commit/rollback), via one shared lifecycle orchestrator.
- **Update checks follow SemVer precedence.** `4.0.0-beta.5` users now correctly see `rc.1` and
  then `4.0.0` (previously all prereleases of one version compared equal), numeric prerelease
  identifiers order correctly (`beta.10` > `beta.2`), and build metadata never marks an update.
- **Remote skills can no longer spoof official identity or override builtin guidance.** The
  official badge displays only when an entry's signing key verifies; a package that claims the
  official identity outside the verified marketplace/seeder path is rejected at install, and
  builtin skill ids cannot be shadowed by installed packages.
- **A damaged store ledger no longer blocks startup.** An unreadable `installs.json` is
  quarantined as a timestamped `.corrupt` file and the store continues with an empty ledger.
- **macOS desktop traffic lights align with the renderer-owned 48 px window bar.** The frameless
  window now keeps Electron's hidden-title-bar button proxy active, restores the native controls,
  and applies their custom position after the visibility update so the traffic lights, sidebar
  toggle, and route toolbar share one centerline.
- **The store sources panel's enable/disable switch now labels the action consistently with its
  tooltip.**

## [4.0.0-beta.5] — 2026-08-24

### ✨ Added
- **Flow authoring now treats upstream inputs as first-class data.** The variable picker separates
  every upstream node's effective **Input** from its worker **Output**, and the runner resolves
  `{{steps.N.input.path}}` without synthetic manifest outputs while filtering sensitive fields.
  The canvas also restores debounced local drafts, protects direct-route navigation, fits templates
  reliably at laptop widths, keeps editing rails mutually exclusive, and warns when disconnected
  roots will run in parallel. Restart-interrupted runs enter a reviewable recovery checkpoint with
  stable per-step invocation IDs; expired session file grants are explicitly non-resumable.
- **Java, Python, and Go plugin scaffolds are code-first by default.** A short
  `manifest.base.json` plus a language-owned contract generates the RPC methods, AI tools, final
  manifest, typed client, and method constants. `flowNodes` is now a UI-only delta over the RPC
  schema, and locale files use compact display-only node/port deltas; CLI checks and host install
  reject schema drift and stale localized keys.
- **Dynamic tool loading (pi's `setActiveTools` pattern).** When the visible tool catalog exceeds
  the new `ai.tool_loading_threshold` setting (default 25; `ai.tool_loading_mode` =
  auto/always/off), the chat loops stop resending every tool schema on every round: a small
  cheap core stays attached, the rest is advertised by name in a new system-prompt catalog
  ("Available tools (on-demand activation)" — untrusted-data framed, MCP server tagged) and
  activated on demand through a new built-in `search_tools` tool. Activation is additive-only
  and capped (40/conversation); the activation set re-seeds on follow-up turns from a
  machine-readable marker mirrored into chat history, so stateless replay needs no server-side
  session. Hallucinated calls to inactive tools now get an actionable "activate via
  search_tools" tool result instead of the turn-killing exception Spring AI's ToolCallingManager
  raises for unresolvable names; compaction overhead accounting follows the actually-attached
  set. The plan-and-execute agent applies the same gate as two-phase planning: above the
  threshold, a schema-less selection call picks `selectedTools` first and the plan is authored
  against only those schemas, with a bounded fallback to the classic full-schema call. Settings →
  AI exposes the mode and threshold; at or below the threshold behaviour is byte-for-byte
  unchanged.
- **MCP management rebuilt around per-server control** (patterns adopted from cherry-studio,
  Codex, and deepseek-harness). Dynamic MCP tools are now namespaced per server
  (`<server>__<tool>`), so permission rules can target one server and two servers can expose
  the same tool name without colliding — previously every dynamic server's tools shared a
  single prefix and silently shadowed each other. Each server gains per-tool enable/disable
  (bare name, wire name, or `prefix*` / `*` wildcards), configurable request and
  initialization timeouts (5–600 s, default 30), and a hardened STDIO environment:
  interpreter-injection keys (`NODE_OPTIONS`, `LD_PRELOAD`, `LD_LIBRARY_PATH`, `DYLD_*`, JVM
  variants) are stripped from saved and imported configs. The AI-facing tool catalog is now a
  cached snapshot — reading it never performs a live MCP round trip, so a dead or slow server
  can no longer stall chat startup or the flow tool list.
- **Plugin-declared MCP servers are wired up.** Claude/Codex/Grok plugins that declare
  `mcpServers` used to write config files that nothing read. They now surface in
  Settings → MCP as disabled servers tagged with their plugin origin, are testable on demand,
  and get adopted into the user-managed registry on first enable (adopted servers survive a
  plugin uninstall). The store refreshes the import after every install/update/uninstall.
- **Conversation compaction upgraded to the algorithm pi, grok-cli and deepseek-harness
  converged on.** Summaries follow a fixed structured template (Goal / Constraints /
  Progress / Key decisions / Next steps / Critical context), tool results are truncated to
  2 000 characters in summarizer input, a summarizer failure retries once with the more
  recent half before failing open, and when even the kept tail overflows the window, recent
  rounds are traded away down to a floor of two instead of shipping a history the provider
  will reject. Cuts remain at user-turn boundaries only — a tool call and its results are
  never separated.
- **Token accounting uses provider-reported usage.** Chat turns now report the sum of
  provider completion-token counts across all tool rounds — falling back to the old
  text-length estimate only when a stream carries no usage — plus a measured
  tokens-per-second figure.
- **Plugin platform hardening and Java/Python/Go Workers.** The host and Toolchain 2 now implement
  the full package-to-runtime trust chain:
  - Strict SemVer plus `engines.fengyu` compatibility, digest-pinned single-download installs,
    whole-package integrity records, conventional runtime artifacts, and protocol-v1 startup
    handshakes. Java remains compatible; new Python 3.12+ and Go 1.26+ SDKs/scaffolds speak the
    same cancellation, locale, structured-log, and JSON-RPC contracts.
  - Ed25519 catalog signatures with publisher namespace authorization, bundled/user trust roots,
    key/package revocation, and `fengyu sign` sidecars. Official status no longer bypasses remote
    verification.
  - Health-gated transactional updates retain a rollback snapshot, reject unconfirmed permission
    escalation, restore the previous package after failed spawn/handshake, and recover interrupted
    swaps on startup.
  - Runtime observability exposes STOPPED/STARTING/HEALTHY/DEGRADED/BACKOFF/FAILED/UPDATING/
    DISABLED states and structured fault categories. Rapid crashes use bounded exponential
    backoff; manifests can cap worker-tree memory and process count, enforced by POSIX monitoring
    or Windows Job Object limits.
- **Flow-builder interaction overhaul — typed, self-explaining nodes** (the deliverable of the
  flow-canvas interaction design). Nodes stop being opaque forms:
  - **Three-state source control per input** (manual / reference / expression). *Reference*
    opens a recursive **variable tree** — workflow inputs plus every upstream node's outputs
    (nested object fields, array `[0]` sample children) — filtered by the input's expected
    type, with copy-path buttons and drag-to-bind onto any input. Bound values render as
    readable chips ("node · field") and auto-create the canvas edge. *Expression* keeps the
    raw-template escape hatch, flagging unknown references inline before save.
  - **Descriptor v2.** `flowNodes` declarations gain a type system (`string` / `number` /
    `boolean` / `object` / `array` / `file` / `any`), nested output `properties`/`items`,
    `examples`, per-field `help`/`placeholder`/`required`, and node-level `help` — all
    optional, fully backward compatible (v1 declarations behave exactly as before). Output
    handles are colored by type and their tooltips show type + description + example.
    Schema updated in `toolchain/spec/manifest.schema.json` (+ synced CLI copy) with a new
    `flow-node.schema.json` validating the host's `builtin.json`; the excel/email official
    plugins and `json_format` now declare v2 metadata.
  - **Data visibility.** The node panel gains an upstream-data preview (declared fields →
    examples → **actual last-run values** resolved per field path) and an output viewer with
    the same degradation. Reference paths validate at save time (`unknownNodeReferences`) —
    the "no such output field" error moves from run time to authoring time. The reference
    grammar now supports **array indexes** (`{{node.n.result.files[0].name}}`) end to end,
    runner included.
  - **Pinned results.** A completed step's real output can be pinned; later runs serve the
    pinned value without executing that tool (`AgentStep.pinnedResult`, capped at 64 KB),
    so downstream debugging never re-runs an expensive or destructive upstream step.
  - **Start node.** New flows open with a Start node — the visual editor for run-time inputs
    (name/label/type/required/options/example) that replaces hand-written JSON Schema for
    ordinary users and renders the inputs as typed rows on the canvas. The JSON view remains
    in the settings drawer for power users.
  - **Canvas legibility.** Live run badges on node cards (running / done / failed), custom
    node titles (rename any node; every picker and chip uses the title), `N` opens the node
    palette focused, and a green coach note seeds brand-new flows. The palette lists
    explicitly declared nodes first and reveals **every** orchestrable tool behind a
    "show all tools" toggle — undeclared tools are no longer invisible to flow authors.
  - Node completion checks now union the tool schema's `required` list with descriptor-v2
    `required` flags, and node `lastRun` previews persist with the graph (excluded from the
    unsaved-changes guard so finishing a run never flags the flow dirty).
- **JSON Schema contracts are enforced at every trust boundary.** A new
  `JsonSchemaContractValidator` backs the generated manifest schemas end to end:
  `PluginProcessManager` validates worker input before dispatch and output after it,
  `AiToolRegistry` exposes the output schema through `AuditedToolCallback` and injects
  `sessionId` only when the schema declares it, and workflow compilation validates nested
  input contracts. Flow runs harden accordingly — pinned failure envelopes fail the step
  instead of masquerading as completed, template references are validated against declared
  output paths at plan time, and a skipped producer cascades through implicit template
  dependencies; AI callers receive the last actually-completed branch result. Email semantics
  tightened in the same pass: preparing a send is a WRITE (non-idempotent) effect,
  `confirm_send` success reflects the real delivery status (a GreenMail test proves
  attachments only go out after confirmation), and node labels clarify prepare vs execute.
- **Host file inputs are declared by structured schema metadata, not wording.** Plugin
  parameters now declare file semantics explicitly — `format: fengyu-file | fengyu-directory`
  plus `x-fengyu-file-access: read | read-write` — enforced end to end: the manifest spec and
  CLI validate the keywords with conditional rules (format requires string, access requires
  format, `fengyu-file` is read-only), the Java/Go/Python SDK field annotations gain
  `format`/`fileAccess` with contract-checked constraints, the host's file injector
  classifies from the metadata (description-wording heuristics stay as a compatibility
  fallback), and webhook triggers reject directory inputs. The flow UI applies descriptor
  presentation order and renders display overlays on top of the executable schema instead of
  duplicating it.

### ✨ Added (flow control & debugging, 2026-08-20)
- **AI generation node (`flow_llm`).** A host-built-in LLM node distilled from the
  surveyed builders (n8n LLM Chain, Dify LLM node, Flowise structured output): one
  non-interactive completion per step with a prompt assembled from upstream references,
  an optional system role, an optional temperature (0–2, defaulting to the global AI
  setting), and an optional **JSON Schema** for structured output. The raw reply always
  survives in the `text` output; a schema-shaped reply is parsed into `data` with one
  targeted repair retry that feeds the exact validation error back into the prompt
  (the community's measured fix for unreliable output parsers). Each call builds a fresh
  model client from the live config — parallel canvas steps and flows executed from chat
  via `run_current_flow` never contend with the active conversation's generation lock.
- **Excel executor outputs its write directory.** `excel_execute` now returns
  `outputDir` — the absolute path it actually wrote the split files to (including any
  host-injected default) — declared both in the RPC output schema and as a canvas output
  port ("输出目录"). The email batch-send node's attachment directory binds it directly
  (`{{node.write.result.outputDir}}`) instead of re-entering the path; the excel→email
  template is re-wired accordingly and the email node's field help points at the binding.
- **IF branch node (`flow_if`).** A host-built-in control node comparing two values
  (12 operators: contains / starts_with / numeric order / emptiness / numeric-aware
  equality; either operand may bind an upstream reference) and exposing **true / false**
  output ports — the only multi-port node on the canvas. An edge drawn from a branch port
  compiles into the new `AgentStep.runWhen` conditions; the engine skips steps whose branch
  did not fire (and cascades over steps whose dependencies were all skipped), emitting a new
  `step_skipped` SSE event rendered as a gray badge on the node, a muted chip in the
  execution panel, and a `SKIPPED` execution in run history. Branch edges carry a labeled
  chip naming their port and round-trip through `graph_json`.
- **Run this node.** Single-step debugging from the node inspector: the node executes alone
  via a one-step plan on `POST /api/agent/run`, with upstream references resolved from each
  ancestor's **pinned** or **last-run** value (no ancestors re-run) and workflow inputs from
  the current settings values. The result fills the same surfaces a full run does — badge,
  panel, history, last-run preview — closing the cold-start debugging gap (a deep node can
  now be validated before the whole chain ever passes).
- **Flow templates ×4 and recent flows.** The canvas landing now offers "Excel column
  split" (no email leg), the always-available "JSON tidy-up", a **branch demo** (format only
  when a marker is filled — visibly exercises the skip path with built-in tools only), plus
  the existing excel→email flow; below the templates, the three most recently edited flows
  reopen in one click now that the sidebar lands on a fresh canvas.
- **`fengyu check` flowNodes cross-validation.** Every declared input must name a parameter
  the referenced tool actually accepts, and impossible widget/type pairs (e.g. a `number`
  widget typed `string`) are rejected at check/build time. The rule immediately caught and
  fixed a real misalignment in the email plugin (`body` → `plainText`: manually authored
  email bodies were silently dropped). Select options now also accept `{value,label}` pairs
  for localized labels (schema widened, frontend renders both shapes).
- **Published snapshots and version history.** Publishing now creates an immutable revision
  snapshot. Later edits remain a draft while AI keeps invoking the last reviewed version;
  the settings drawer shows the active revision and full publication history, and any older
  snapshot can be restored into a new draft without changing what is live. Manual runs and
  the builder's bound `run_current_flow` continue to use the editable draft.
- **Idempotency-aware node retries.** Flow nodes can make 1–5 attempts with bounded exponential
  backoff, but only for read-only tools or write/external plugin tools that explicitly declare
  `idempotent: true`. Unsafe retry plans are rejected before any tool call, preventing duplicate
  writes; the canvas exposes retry controls only when the live tool descriptor is retry-safe.
  Live and persisted execution views now retain every failed attempt with its error and backoff.
- **Durable workflow schedules.** Scheduled published workflows now survive application restarts,
  retain inputs/permission/sandbox posture, keep fixed-rate boundaries without clock drift, and
  coalesce overdue occurrences into one recovery run with a visible `missedFires` count. A durable
  at-most-once claim prevents crash recovery from duplicating external side effects; weakened
  plugin isolation pauses the trigger, and workflow deletion cancels schedules transactionally.
- **Durable, owner-scoped background tasks.** Recent task status and capped output now survive
  application restarts and are visible only to their owner across REST and model tools. Work that
  was queued or running at shutdown is recovered as an explicit interrupted failure instead of
  disappearing, remaining stuck, or being replayed; off-request schedule fires explicitly retain
  the schedule owner's identity. The host now runs at most 16 task bodies concurrently and holds
  another 128 in a bounded queue instead of rejecting the first overload burst. A single owner may
  queue at most 32, preserving admission capacity for other owners even under racing submissions;
  batch and normal work are further capped at 16/24 per owner and 64/96 globally, preserving eight
  owner slots and 32 global slots for interactive webhook work. Model submissions are normal,
  schedule fires are batch, and webhook deliveries are interactive. Admitted tasks are FIFO within
  each owner-priority queue, scheduled 4:2:1 across interactive/normal/batch work to bound batch
  starvation, and round-robin across owners, while remaining work-conserving when only one class or
  owner is runnable. Rejected calls distinguish `owner`, `global`, `owner-priority`, and
  `global-priority` saturation and include the rejected `capacityPriority` when applicable. Queued
  state is visible and owner-cancellable, including a race-safe
  cancellation bridge installed after a kill request. REST, the run panel, and the read-only
  `task_capacity` model tool expose live running/queued pressure and priority mix, reservations,
  active-owner count, oldest queue wait, global/owner saturation and policy without leaking task
  details; task snapshots persist their priority, and the panel refreshes while open and warns on
  saturation or a 30-second wait. Capacity exhaustion now returns retryable HTTP
  429 with `Retry-After` (and structured model-tool retry advice) instead of 500. An unadmitted webhook
  releases its idempotency claim so the same event ID can retry safely. Task snapshots also record
  start time, queue wait, and run duration for overload diagnosis.
- **Per-priority queueing metrics.** The background-task scheduler now publishes its queue pressure
  through Micrometer with semantics calibrated against Kubernetes API Priority and Fairness and
  Temporal's schedule-to-start latency: `fengyu.bg.tasks.dispatched` and `fengyu.bg.tasks.rejected`
  counters per priority (rejections tagged with the limiting `owner`/`global`/`owner-priority`/
  `global-priority` scope), a `fengyu.bg.task.queue.wait` schedule-to-start histogram split by
  executed versus cancelled-while-queued outcome, and `fengyu.bg.queue.inqueue` plus
  `fengyu.bg.queue.oldest_wait_ms` gauges per priority. `task_capacity`, the capacity endpoint, and
  the run panel also report the oldest queue wait per priority class, so the 30-second delay alert
  names the workload class (interactive/normal/batch) that is actually aging instead of a global
  blur.
- **Loopback workflow webhooks.** Published workflows can now expose durable, owner-scoped webhook
  triggers from the run dialog. Each trigger has a stable loopback endpoint and a 256-bit secret
  shown only at creation or rotation; only its SHA-256 digest is retained. Optional event IDs are
  hashed and atomically claimed before task submission for at-most-once admission, request bodies
  overlay saved default inputs, and accepted deliveries run as ordinary durable background tasks.
  Ephemeral picker/shared-directory inputs are rejected, weakened plugin isolation pauses a
  sandboxed trigger, crash-interrupted claims are never replayed, and workflow deletion disables
  schedules and webhooks in the same transaction. Every accepted delivery — including calls that
  omit an event ID — now has a bounded, read-only lifecycle record that advances from queued to
  running and completed/failed/cancelled, records duration and the owning task, and becomes interrupted
  after a crash. The run panel exposes the latest records without retaining or returning request
  bodies, secrets, raw event IDs, or event-ID hashes; no unsafe blind-replay action is offered.
- **Docs.** New user-facing concept page `guide/flow-nodes.md` (EN + ZH): references and the
  variable tree, the three-tier output preview, branch/skip semantics, single-step
  debugging, and pins; `step_skipped` documented in the SSE events reference; the plugin
  manifest reference documents labeled select options and the new cross-check.

### 🐛 Fixed
- **Windows portable self-update no longer stalls when a plugin was open.** The update-quit path
  force-killed the backend JVM with a synchronous TerminateProcess while tree-kill's async
  `taskkill /T` was still spawning: the JVM died first, taskkill then found the root PID gone and
  exited without ever killing the plugin-worker grandchildren. Those workers run from the bundled
  `resources\jre`, so they kept its image files locked and the replace script's robocopy burned
  its bounded retries and relaunched a half-updated app — the intranet field failure where an
  update with a previously opened plugin never completed its file replacement. `forceKill` on
  Windows now kills the whole backend tree synchronously (`taskkill /F /T` by absolute path)
  before the direct-kill backstop, and the replace bat additionally sweeps any process still
  running from the app root (scoped by image path — never a blanket `java.exe` kill) before
  copying, then retries robocopy once when destination files were still locked.
- **Agent event decorators preserve control-flow telemetry.** Notification and persistence wrappers
  now forward `step_skipped`, while retry attempts are persisted and streamed as `step_retry`, so
  production runs no longer lose skip or retry visibility when sinks are composed.
- **Workflow publication and concurrent editing are revision-safe.** Saving an edited published
  flow no longer changes the active AI snapshot. Save, publish, and restore requests carry the
  editor's last-seen revision; stale windows receive a structured HTTP 409 instead of silently
  overwriting, publishing, or restoring over a newer definition.
- **Plugin notifications now always go through the unified host pipeline.** The notify bridge
  in `PluginView` used to require the manifest's `notifications` permission: an undeclared
  plugin's `notify` fell back to the iframe-internal snackbar queue, which the user effectively
  never sees (it scrolls away with the iframe). Every plugin's `notify` now creates a real
  unified host notification — in-app toast, native desktop notification when the window is
  hidden, and the persisted notification center — with no permission required; the SDK's
  iframe-local fallback remains only as the last resort when host delivery fails. The
  `notifications` permission token is still accepted in manifests (documents intent, keeps
  existing packages installing).
- **Plugin uninstall did nothing in the desktop shell — and the drawer stayed open.** The
  uninstall (and every other destructive) confirmation used the synchronous `window.confirm`,
  which sandboxed Electron renderers silently drop (electron#7472): the call returns `false`
  without showing anything, so "Uninstall" exited before sending the request and the detail
  drawer never closed. The shell now exposes a native `confirm` over IPC
  (`dialog:confirm` → `dialog.showMessageBox`, Cancel-focused for destructive prompts) and the
  SPA routes all seven `window.confirm` call sites (plugin/skill uninstall, workflow delete,
  MCP delete, flow discard) through an awaited `confirmAction()` helper that falls back to
  `window.confirm` in the browser. The plugin drawer also re-syncs after a failed uninstall:
  the backend deletes files before writing the response, so a mid-teardown error could leave
  a plugin-less drawer full of dead buttons — it now closes when the row is gone/uninstalled
  and stays open for retry only when the plugin is still installed.
- **Plugin installs no longer fail with an opaque "internal error".** Local-package install
  pre-reads the manifest via `/inspect(-native)`; when the running backend predates that
  endpoint (404/405 — e.g. an IDE session on stale classes), the UI now falls back to the
  file-name confirmation and uploads anyway instead of failing the whole install. On the
  backend, `IOException` on the install/uninstall paths (staging, extraction, atomic move)
  and any unmapped `RuntimeException` now answer with the real reason in the body and leave
  an ERROR log line (previously: whitelabel "Internal Server Error" with nothing in the log),
  and the unified-store `InstallerDispatcher` stops rewrapping validation verdicts
  (`IllegalArgumentException` → 400 with the actionable message) into generic 500s.
- **Connecting a third node no longer drops earlier links.** vue-flow re-validates the whole
  edge list through `isValidConnection` on every `v-model` reassignment; previously each
  already-stored edge failed the duplicate check against itself and was silently dropped,
  leaving only the newest connection (the canvas effectively supported two-node chains).
  `canConnect` now recognizes stored-edge echoes by id (also protecting undo/redo) and is
  extracted into `workflow.ts` with unit tests.
- **One output port per node.** Nodes with several declared outputs rendered one output dot
  per field; wiring is whole-node, so the canvas now renders a single output port whose
  tooltip summarizes the declared outputs (the IF node's true/false branch ports are the
  deliberate exception).
- **Code-review fixes across the unreleased feature set.** The last-resort
  `RuntimeException` advice no longer swallows framework status exceptions — deliberate
  controller 409/429 responses and SSE async timeouts keep their precise status codes
  instead of degrading to opaque 500s. The LLM node's 180s timeout is now a hard deadline:
  a hung model call is interrupted and abandoned rather than close()-joined past the
  deadline. On the flow canvas, node references with array indexes (`{{node.x.result.files[0]}}`)
  compile through the same shared grammar that validates them (they used to pass save-time
  checks and then reach the tool as literal text), the variable-tree search reaches nested
  fields (previously only top-level rows were searchable), editing the Start designer
  preserves annotations it does not model (`x-fengyu-auto/-analyze/-enum/-options-from`,
  `default`, `description`, nested `items`, the `fengyu-directory` format), and the
  expression-mode button switches the input without wiping its value. Also: the missing
  `common.copied` locale key (EN+ZH), fallback colors/icons for the `ai`/`control` node
  categories, `flow_if`'s defensive fallback no longer interpolates unescaped operator text.
- **CLI/desktop hardening from the same review.** `fengyu check`'s flowNodes cross-validation
  now also rejects declared inputs against parameter-less tools (the exact "silently ignored
  field" case, previously skipped when `inputSchema` had no `properties`); declared
  `package.resources` copy targets are containment-checked against the staging directory;
  and the desktop `dialog:confirm` IPC validates its invoke payload, answering `false`
  instead of rejecting on malformed input.

### ♻️ Changed
- **Notification entry moved into the account menu.** The sidebar's standalone bell button is
  gone; the notification center now opens from a "Notifications" item in the menu behind the
  username (with a live unread count chip), and the panel drops in exactly where the account
  menu was — including the rail-sidebar anchor. Discoverability of new notifications is kept
  by an unread beacon dot on the user avatar while any notification is unread. The panel
  itself is unchanged: same live SSE feed, mark-read, and navigation behavior.

### 🐛 Fixed
- **Update checks never ran in the current shell — and now repeat periodically.** The startup
  update probe lived in `StatusBar`, a component the current shell layout no longer renders,
  so no update was ever detected outside the About page's manual button. The probe (plus a
  new idempotent 6-hour re-check for long-running sessions) now lives in `AppShell`, the one
  component mounted on every route. When a newer release is found, the sidebar's About (ⓘ)
  entry carries a red beacon dot (with the available version as its tooltip) until the
  update is installed — mirroring the avatar's unread-notification dot.

### ✨ Added
- **Update plugins from a local package, with a version-aware confirmation.** Installing a
  locally uploaded `.fyp` over an already-installed plugin id now behaves as a first-class
  update flow instead of a silent replace. New pre-install inspection endpoints
  (`POST /api/plugin-market/inspect` and its desktop path twin `/inspect-native`) read the
  incoming package's manifest without installing and return whether the id is installed plus
  the version step (`upgrade` / `same` / `downgrade`, ordered by the same semver comparator
  behind the catalog's update badge). The marketplace UI routes every local `.fyp` pick
  through a confirmation dialog showing `installed → incoming` versions — warning on a
  downgrade or same-version reinstall — and the detail drawer of an installed plugin gains
  an "Update from local package" entry; the upload itself keeps the existing update gate
  (stop the running worker, atomic directory swap, enabled state preserved). This also
  gives non-catalog third-party plugins a working update path: catalog-based
  `/{id}/update` can only resolve plugins listed in a configured marketplace.
- **Host-side unified notifications.** One pipeline now reaches the user everywhere: a
  persisted notification center (`/api/notifications` — create/list/read/delete with a
  200-row retention window), a live ticket-authenticated SSE stream
  (`GET /api/notifications/stream`) that every connected shell subscribes to, in-app toasts
  while the app is visible, and native OS notifications through the Electron shell when it
  is not (clicking one focuses the window). Producers: the plugin `notify` host bridge —
  now gated on the declared `notifications` permission, which was previously advisory-only
  and always fell back to the iframe-internal center — and agent run termination
  (completed/failed; user cancels stay silent). The sidebar gains a bell with an unread
  badge and a notification-center panel. The official plugins whose UIs call `notify`
  (email, excel, offlinepython) now declare the `notifications` permission, so their
  notifications ride the real host surface instead of the iframe-local fallback.

### 🐛 Fixed
- **About page update row: the unsigned-install confirm showed alongside the "Update now"
  button and "Cancel" did nothing.** The inline confirm popover's `v-else` was chained to
  the download-progress bar's `v-if` instead of the `!confirming` button, so the popover
  rendered whenever a download wasn't in flight — overlapping the "Update now" button,
  ignoring Cancel (its visibility never depended on `confirming`), and after clicking
  "Update now" only "Continue/Cancel" remained. The popover now pairs with the
  `!confirming` button and the progress bar is an independent element, restoring the
  intended gate: "Update now" → warning + Continue/Cancel → download progress.
- **Plugin/skill detail drawer stayed open on a stale snapshot after marketplace operations.**
  The drawer held the row object captured when it was opened, so after an operation refreshed
  the list the drawer kept rendering the old version/enabled label — and after an uninstall it
  stayed open with dead action buttons (a catalog plugin's row even survives uninstalling).
  Uninstalling a plugin or skill from the drawer now closes it, and every other operation
  (update, toggle, local-package update) re-points the drawer at the refreshed row — the
  skill preview also reloads after a skill update.
- **Email Center contacts page: the New-contact form and the tag manager were unreachable.**
  In the real host iframe the page usually renders in the narrow single-column layout (the
  `.fy-plugin-page` container sits below the 1000px breakpoint once the plugin's own drawer is
  subtracted), which stacks those cards below the tall contact list — and wheel scrolling over
  the list did nothing because `.contact-list-scroll`'s `overscroll-behavior: contain` blocked
  scroll chaining to the page even with an empty list. The containment is removed (the wide
  two-column layout never scrolls the document, so it gains nothing from it); wheel/touch
  scrolling now chains and the below-fold cards are reachable again.

## [4.0.0-beta.4] — 2026-08-18

### ✨ Added
- **Unified option-source standard for flow-node inputs.** Three declarative candidate kinds
  replace the excel-only vocabulary: static `options`; CATALOG `source` (a plugin list method
  the host fetches and maps through value/label/labelSecondary, single- or multi-select); and
  CONTEXT `context` (datasets derived at edit time from another input's value — e.g. the
  workbook path → sheets/columns via the analyze RPC — consumed through `optionsFromContext`
  references, including row-field keying). The node inspector loads catalogs on focus with a
  manual fallback, renders context triggers beside their input, and feeds datalists; the run
  form's x-fengyu-enum annotations now share the same fetch path. Excel migrated to
  context/optionsFromContext (the analyze widget and workbook-sheets/workbook-columns
  annotations retire); Email declares catalog sources for 发件账号 and 收件/抄送分组.

### ♻️ Changed
- **Explicit flow-node declarations (canvas no longer derives node forms from AI-tool schemas).**
  Plugins declare nodes in `manifest.flowNodes[]` (tool binding, label, color, icon, typed
  `inputs` with widget configs — text/number/switch/select/textarea/json/analyze/rows — and
  named `outputs`); the host ships built-in declarations in `flow-nodes/builtin.json`
  (currently `json_format`). The tool descriptor API carries the declaration through, the
  canvas palette lists ONLY declared nodes, node cards render declared labels/icons/colors and
  NAMED OUTPUT PORTS (Flowise outputAnchors), and the node inspector renders the declared
  widgets (descriptor defaults apply on drop, e.g. `action=add`). Non-declared tools remain
  available to the model but never appear as canvas nodes. Excel (复杂拆分/执行拆分) and
  Email (批量发送/放行发送) ship the first declarations; legacy saved graphs keep the
  schema-derived fallback rendering.

### ♻️ Changed
- **1:1 Vue replica of Flowise's AgentFlow v2 canvas (React removed).** The flow builder's
  canvas now mirrors the dark AgentFlow canvas from the screenshot, rebuilt in pure Vue on
  vue-flow after a full read of Flowise's source: node-type colors from `tokens.ts` tint each
  card via MUI's `darken(color, 0.8)` formula (hover 0.7, border alpha .5/.8/selected), the
  40px radius-15 icon badge, NodeInputHandle's 5×20 color bar, the hover-revealed chevron
  output handle, AgentFlowEdge's gradient bezier strokes with hover delete buttons, the
  #1a1a1a dot grid, bottom-center Controls with snap/background toggles, and the dark MiniMap.
  The Delete key is now history-aware (vue-flow's built-in deleteKeyCode bypassed the undo
  stack). react/react-dom/reactflow and the React island are gone.
- **One-click workflow templates & ordinary-user run form.** The canvas ships a built-in
  template gallery (empty-canvas state and the workflow library); the first template,
  *Excel split → batch email*, pre-wires `excel_complex_config → excel_execute →
  email_send_batch → confirm_send`, pre-maps nested output references
  (`confirmation.confirmationId`), and ships a run form a non-technical user fills in:
  file inputs render upload pickers, shared output folders are minted automatically, and
  account/recipient-group inputs render live dropdowns fed by plugin list tools
  (`email_accounts_list`, `email_tags_list`) instead of numeric ids.
- **Multi-rule complex split configuration.** The template's *split rules* input is a
  dynamic row editor — one row per worksheet (`工作表` + `拆分列`), bound whole as
  `entries: {{inputs.rules}}` — and an omitted `headerIndex` now defaults to the first
  header row in the Excel plugin, so neither the canvas nor the model needs row numbers.
  Uploading a workbook analyzes it through the plugin (`excel_analyze`) and turns the
  sheet/column fields into datalist candidates, so users pick their real sheet and column
  names instead of typing them blind; the union fallback keeps fields free-text.
- **Run-scoped file grants for workflows.** `POST /api/agent/run` and
  `POST /api/workflows/{id}/run` accept a `files` array (pass-through picker grants, a
  native path, or `createSharedDirectory`). File-class inputs travel as `@file:<name>`
  placeholders that the host swaps for the current plugin's FileRef at dispatch
  (`RunFileContext` + `AiToolFileInjector.bindRunFilePlaceholders`), and a host-minted
  shared scratch directory is granted **live** to every eligible plugin — so an Excel
  split step's outputs are readable by a later Email step on every sandbox backend.
- **`confirm_send` as an approval-gated AI tool.** The email plugin now exposes
  `confirm_send` with effect `external`: chat keeps its confirmation card, while visual
  workflows mark the send step *requires approval*, so every permission mode except
  full-access pauses for a one-click human go-ahead before dispatching the batch.
- **Nested node-output mapping.** The inspector's output picker flattens one nesting
  level into dotted paths (`confirmation.confirmationId`), so a follow-up step can map a
  specific nested field instead of the whole result object.
- **e2e smoke: full template chain.** `scripts/e2e-smoke.sh` now boots a local SMTP sink
  and drives the whole scenario — uploaded workbook + shared dir + run file grants +
  approval-gated `confirm_send` — asserting four completed steps and a delivered message.
- **Permission rules & lifecycle hooks (grok-build-informed agent runtime hardening).**
  A user-configurable guard now sits between the permission modes and every AI tool call,
  evaluated as `PreToolUse hooks → deny rules → ask rules → allow rules → mode default`.
  Rules follow the `Command(git status)` / `Tool(excel_*)` / `Effect(read)` /
  `Mcp(server__*)` / `WebFetch(domain:…)` grammar with order-independent
  `deny > ask > allow` precedence, per-segment shell-chain checks (an allow must cover
  every segment of `a && b | c`; deny/ask match any), and a dangerous-command floor
  (`rm`, `sudo`, `git push`, …) that allow rules cannot bypass. Lifecycle hooks extend
  the same pipeline: command hooks (event JSON on stdin; exit 2 denies with stderr as
  the reason; a stdout `{"decision":"deny"}` gate document denies on any exit code) and
  HTTP callbacks for `pre_tool_use`, `post_tool_use`, `post_tool_use_failure`,
  `run_complete`, and `run_error`, failing open when a hook crashes or times out.
  Configured in Settings (runtime & security), shared by chat and agent runs; a denied
  call fails its step with the rule's reason so the model can replan around it.
- **Plugin-contributed hooks with an enable≠trust gate.** A `.fyp` package may ship
  `hooks/hooks.json` (grok-shaped or FengYu's flat list); installing or enabling the
  plugin never activates them — the user trusts the plugin explicitly via
  `POST /api/plugin-hooks/{id}/trust`, and untrusting takes effect on the next call.
  Trusted hooks run with the plugin install dir as working directory plus
  `FENGYU_PLUGIN_ROOT`/`FENGYU_PLUGIN_DATA` env and `plugin/<id>/<name>` namespacing.
- **Workflow schedules.** Published workflows run on schedules (`task_schedule` tools +
  `/api/agent/schedules` REST): 60-second minimum interval, 50-schedule cap, 7-day
  expiry, optional immediate first fire, delayed one-shots via `recurring:false`.
  Scheduled runs are ordinary background tasks (task_output/wait/kill apply); durable definitions
  are restored after restart with fixed-rate, overdue-coalescing, and at-most-once crash semantics.
- **Host-level background tasks.** Long workflows no longer block the synchronous tool
  slot: `task_submit_workflow` returns a `taskId` immediately, `task_output` polls or
  blocks, `task_wait` waits on up to 20 tasks (`any`/`all`), and `task_kill` stops a
  runaway task with cooperative cancellation first and SIGTERM → SIGKILL escalation for
  process-backed ones. The same durable, owner-scoped registry backs `GET /api/agent/tasks`
  for the UI and retains the 100 most recent finished snapshots across restarts.
- **Run-history search, fork, and rewind.** `GET /api/agent/runs?q=…` searches
  goal/summary/error text; `POST /runs/{id}/fork` re-runs a finished run's plan as a
  fresh peer; `POST /runs/{id}/rewind {keepSteps}` truncates the plan to its first N
  steps (inheriting only earlier completed executions) and resumes under plan review —
  dropped steps' side effects are documented as not rolled back. Runs also record the
  plugin-sandbox posture they were created under and refuse replay while the host runs
  unsandboxed, so isolation can never silently weaken.
- **Read-only batch capability.** `POST /api/agent/batch` accepts
  `capabilityMode: "read-only"`, restricting every child run to `read`-effect tools —
  parallel research/review tasks can be declared mutation-free up front.
- **Cross-session memory (experimental, off by default).** `memory_remember`,
  `memory_search`, `memory_list`, and `memory_forget` tools over a per-user store with
  keyword × 7-day-half-life recency ranking; relevant memories are injected into agent
  planning context when the Settings toggle is on.
- **Usage metrics with OTLP export.** Agent runs and per-tool steps are counted and
  timed through Micrometer (`fengyu.agent.runs` / `fengyu.agent.steps` /
  `fengyu.agent.run.duration`), readable at `/actuator/metrics`; setting
  `management.otlp.metrics.export.url` streams the same metrics to an OpenTelemetry
  collector for production observability (the registry stays dormant with no URL).
- **Marketplace checksum pinning.** With "require plugin checksums" enabled, installing
  a plugin demands its `.fyp.sha256` sidecar (fengyu CLI packager output) and rejects
  mismatches — supply-chain hardening for third-party packages.
- **Computer use (ChatGPT-desktop-style screen control).** Desktop builds now expose a
  `computer_*` AI tool family driven by `java.awt.Robot` inside the backend JVM, with the same
  behavior on Windows, macOS, and Linux: screen capture with Hi-DPI scale reporting (the PNG
  reaches vision models through the same media bridge as `browser_screenshot`), display/app
  enumeration, application launch and activation (PowerShell on Windows with `.exe`-stripped
  process-name and window-title fallback matching; `open`/osascript on macOS; `gtk-launch`/
  `wmctrl` on Linux), mouse move/click/double-click/drag/scroll, keyboard typing with
  clipboard-paste fallback for non-ASCII text, key combos, and a settle wait. Input-injecting
  calls are `external` effects that pass the per-turn approval gate; observing calls are `read`.
  A Settings card (**Runtime & security → Computer use**, `computerUseEnabled`, default on) shows
  the capability probe and can hide the whole family per registry snapshot. Windows needs no
  extra permissions (UAC/elevated windows stay OS-protected); macOS needs Screen Recording and
  Accessibility. Display-less hosts degrade to `"computer use unavailable"` envelopes. A dedicated
  cross-OS workflow (`.github/workflows/computer-use-ci.yml`) runs the family's unit tests on
  Windows/Linux/macOS and the real-display Robot integration test on a Windows runner.
- **Dynamic MCP server runtime.** MCP servers can now be added, edited, removed, tested, and called
  from Settings or the REST API. STDIO, SSE, and Streamable HTTP connections are persisted,
  initialized immediately, discovered with `tools/list`, and injected into the live AI tool catalog
  without restarting FengYu; credentials are kept in a protected sidecar file and are never returned.
- **Reusable workflows with one manual/AI execution path.** Visual DAGs can now be persisted with
  JSON Schema inputs, loaded and revised in the canvas, run manually with typed `{{inputs.*}}`
  bindings, and published as dynamically discovered Spring AI tools. Manual and model-triggered
  calls share the existing dependency-aware runner, tool registry, durable run history, SSE events,
  and audit trail; saved definitions reject nested workflow tools to prevent recursion. Definitions
  persist the canvas node layout, so a saved graph reopens exactly as arranged; the workflow
  library can save a copy of any definition as a starting point, and the run panel shows each
  step's actual output.
- **One-call complex Excel split configuration.** `excel_complex_config` accepts an `entries`
  array declaring the complete rule set in one call (one rule per sheet, `columnName` resolved to
  a column index against the analysis) plus an optional `filePath` that analyzes the workbook in
  the same call and leaves the session in `COMPLEX` mode — a FengyuFlow canvas needs just
  `excel_complex_config → excel_execute`. Re-running the same call is idempotent (entries replace,
  not append), duplicate rules for one sheet are rejected up front, and the shared AI session is
  now pinned and synchronized so concurrent workflow steps cannot corrupt it. `excel_execute`
  with a blank `outputDir` writes into the plugin's default output folder — injected by the host
  as a plain sandbox-writable path, deliberately not registered as a file grant because grant
  registration restarts stateful plugin workers and would destroy their in-memory sessions
  mid-flow. `scripts/e2e-smoke.sh` covers the single-node complex-split workflow end to end.

### ♻️ Changed
- **The canvas is now a literal port of Flowise's canvas (vue-flow removed).** The flow builder's
  stage runs Flowise's own React + React Flow stack as an island inside the Vue shell: ported
  `CanvasNode`/`ButtonEdge`/`StickyNote` components and the chatflow canvas wiring live in
  `src/flowise/`, mounted through `FlowiseCanvasHost.vue`. The Vue side stays the single source
  of truth — nodes/edges flow in as props and every renderer mutation (select/position/dimensions/
  remove, connects, note edits, edge deletes) travels back through controlled-mode change
  channels (`applyCanvasNodeChanges`/`applyCanvasEdgeChanges`). reactflow v11's controlled mode
  requires the parent to store measured dimensions back onto nodes — miss it and nodes stay
  invisible with edgeless rendering; that round-trip is now covered by unit tests. All
  `@vue-flow/*` dependencies are gone.
- **Flowise canvas replica.** The flow builder's canvas now mirrors Flowise's CanvasNode design
  (source-verified against the archived FlowiseAI/Flowise repo): 300px node cards with a circular
  white icon badge and per-category accent colors, grey "Inputs"/"Output" section bands, and
  Flowise's handle states (red on invalid connection, green on a valid target). Edges are Flowise
  ButtonEdge replicas — smooth bezier curves with a midpoint delete button — and structural edits
  (node/edge/note add, remove, moves) gained undo/redo (toolbar buttons, ⌘/Ctrl+Z, ⇧⌘Z / Ctrl+Y,
  50-step cap).
- **Fixed silently dropped canvas edges.** vue-flow re-validates programmatic edge assignments
  through `isValidConnection` AFTER the parent state already contains the new edge — the builder's
  duplicate check therefore rejected every auto-connected, restored, or linked edge. Validation now
  runs against the store-supplied pre-update edge list. (The same latent bug existed in the old
  AiAgent canvas.) The Flowise edge-delete button also needed an explicit `pointer-events: all` —
  vue-flow's edge-label layer disables pointer events for its whole subtree.
- **One tool-call mode for AI Chat and flows (Flowise chat-with-your-flow).** The flow
  builder ships a Flowise-style docked chat panel: sending a message binds the turn to the
  flow being edited (`POST /api/ai/chat` now accepts `workflowId`), and the backend exposes
  that flow — draft or published — to the model as `run_current_flow`, an ordinary tool call
  in the same chat tool-call loop that powers AI Chat (identical permission modes, approval
  gates, and SSE `tool` events; the panel auto-saves pending edits first). AI Chat reaches
  published flows through the same loop via `run_workflow_<id>`, making chat and the canvas
  peers over one runtime. Internals: request-scoped `BoundToolsContext` merges bound tools
  into each turn's registry snapshot (cloud + Ollama backends), and
  `WorkflowExecutionService.executeForAi(…, requirePublished)` admits drafts on the bound
  path only.
- **Flow builder rebuilt Flowise-style (flows refactor).** The visual canvas moved out of the
  AI Agent page into a dedicated **Flows** section (`/flows`): a library page listing saved
  flows as cards (open / duplicate / delete) plus templates, and a full-workspace builder with
  a categorized, collapsible node palette (search, drag-to-add), a right-hand node
  configuration panel, Flowise-style sticky notes (annotation-only nodes), per-category node
  accent colors, and the same run dialog / execution panel / settings drawer as before. The
  AI Agent page (`/agent`) is now the pure AI-planning view; `AiAgent.vue` shrank from ~3300
  lines to ~350 by extracting the shared SSE run engine (`useAgentRunStream`) and the canvas
  into `FlowBuilder.vue` + focused child components.
- **Workflows persist the authored canvas graph.** Definitions gained an optional `graph`
  field (`ai_workflow.graph_json`) storing the exact nodes/edges/sticky-notes as arranged —
  node ids are stable across save/reload, so `{{node.<id>.result}}` references survive.
  `plan` + `layout` remain the compiled execution contract; older definitions without a graph
  still reconstruct the canvas from the plan. The backend validates graph shape (nodes/edges
  lists, size caps) without interpreting node internals.
- **FengyuFlow fails fast instead of failing mid-run.** Saving a workflow now rejects
  `{{inputs.*}}` references that the input schema never declares (previously such a graph saved
  fine and failed at every run) and caps definitions at 64 steps. The manual-run dialog blocks the
  start until required workflow inputs are filled, host validation messages surface localized in
  the UI, and unsaved canvas work is protected by confirm dialogs on switch/new/delete plus a
  browser close guard. `scripts/e2e-smoke.sh` now probes workflow create/layout/manual-run/
  publish-as-AI-tool/delete end to end against a real backend.
- **Plugin tool failures now carry their reason.** Most official plugin methods report failure as
  `success:false` plus a localized `summary` with no `error` field; the host's result normalizer
  only read `error`, so agent runs and workflows showed a useless "Tool reported failure". The
  normalizer now falls back to `summary`, surfacing the actual localized diagnostic.
- **All JavaScript areas now install through Yarn 4 (corepack).** `frontend/`,
  `desktop/electron/`, `docs/`, and the four `@infinia/*` toolchain packages pin
  `yarn@4.18.0` via `packageManager` + `.yarnrc.yml` and install from committed
  `yarn.lock` files (`--immutable` in CI); the `package-lock.json` files are gone and npm
  lockfiles are deprecated for repository areas. Only `fengyu init` still scaffolds
  npm-based projects for third-party plugin authors, and the release workflows cache and
  install with yarn accordingly.

### ✨ Added
- **Three-control complex-split rows + in-node workbook analysis.** `excel_complex_config`
  entry rows in the flow builder now show only Sheet 名称 / 拆分列 / 整表拷贝 (headerIndex and
  columnIndex fold away as `x-fengyu-advanced`; the new per-entry `copyEntireSheet` boolean is
  the canvas-friendly spelling of the (-1, -1) whole-sheet convention, honored by the Excel
  worker). The node's File Path input gains an analyze button (plugin `analyze` RPC, isolated
  `canvas-<nodeId>` session) whose sheet/column results feed datalist pickers — the 拆分列
  candidates narrow to the chosen sheet.

### 🐛 Fixed
- **Settings page was a blank screen in production builds (dev worked).** Four i18n messages
  (`settings.mcp.placeholderEnvironment/placeholderHeaders/placeholderArgumentsObject` and
  `settings.guardHooksHint`) embed literal JSON examples, and their `{`/`}` was parsed by
  vue-i18n's message compiler as placeholder syntax — INVALID_TOKEN_IN_PLACEHOLDER. The dev
  build tolerates the compile error (warn + raw-message fallback), but the production build
  throws `SyntaxError: 2` from the vendored Vue bundle, killing the whole Settings view (the
  shell survives, the page body dies). The literal braces are now escaped with vue-i18n's
  `{'{'}`/`{'}'}` literal syntax in both locales.
- **A "fresh" directory silently adopted the config from `~/.fengyu` — the setup wizard never
  appeared.** `DataSourceConfigService` probed legacy locations (`<cwd>`, `~/.fengyu`) and
  migrated their `datasource.properties` into the runtime root on load, so once any run had
  saved a config under `~/.fengyu`, every new portable extraction (or any directory the
  desktop shell pins via `-Dfengyu.runtime.dir`) booted straight into APP mode and the main
  window. An explicitly pinned runtime root now stays isolated — fresh directory, fresh
  setup wizard; unpinned `java -jar` runs keep the legacy migration for upgrades.
- **Setup wizard's restart detection raced the dying SETUP backend (main shell dead on
  arrival after setup).** The wizard polled `/api/setup/status` for `initialized:true`, but
  the still-exiting SETUP backend answers exactly that for its ~1s grace period (the config
  was just persisted), so the SPA navigated to the main shell while every app API still 404'd
  — and once APP mode came up the endpoint 404s by design, so the poll could also never
  succeed and timed out. The wizard now treats that 404 as the "restarted into APP mode"
  signal (same contract as the SPA router guard) and ignores the ambiguous 200.
- **Desktop: launching on a configured machine (or right after finishing the setup wizard)
  killed the healthy backend with "setup status request failed: HTTP 404".** `/api/setup/**`
  is token-bypassed and therefore only mapped in SETUP mode, so an APP-mode backend answers
  404 there by design — the desktop shell's startup probe treated that as fatal (and the
  SETUP→APP restart after the wizard hit the same probe), while the SPA router guard already
  read 404 as "already configured". The desktop probe now does the same: 404 → APP mode,
  other statuses stay fatal.
- **Drag-connect on the canvas.** Flowise's NodeInputHandle color bar (the 5×20 vertical strip)
  broke vue-flow's drop hit-test — `elementFromPoint` landed on the bar's inner div instead of
  the handle, so no connection ever fired. The input handle is now a small node-colored dot
  with no inner content, which also removes the vertical line from every node.
- **Canvas follows the app theme.** The hardcoded #1a1a1a surface, white text, and dark
  controls/minimap are gone — nodes tint per Flowise's useNodeColors formulas on BOTH themes
  (dark: `darken(color, 0.8)`, light: `lighten(color, 0.9)`) and the surface, controls, minimap,
  and text use the Vuetify theme tokens, switching with the app's light/dark setting.
- **Empty-canvas "添加节点" button and template cards were unclickable.** The empty-state
  overlay rendered inside VueFlow's default slot, where the interactive pane stacks above
  slot content and swallowed every click. The overlay now lives outside the VueFlow root
  (pointer-events only on its buttons), so both the centered add-node button and the
  built-in template cards respond.
- **Canvas test runs now bind `{{inputs.*}}`.** An unsaved canvas run used to post the
  compiled plan with literal placeholders, so tools received `{{inputs.x}}` strings; the
  client-side compiler now binds run-form inputs with the same semantics as the backend
  (exact reference → typed value, embedded → rendered text) while saves keep placeholders
  for later re-binding. The Excel→Email template's required-but-empty `ccGroupTagIds`
  likewise no longer blocks the run: it maps to an optional input seeded with `[]`.
- **Text-only fallback for strict OpenAI-compatible gateways.** Some gateways only accept
  string `content` in chat messages and reject the array-form (multimodal) content that
  screenshot tools produce — e.g. Go-based relays answering
  `json: cannot unmarshal array into Go struct field ChatMessage.messages.content of type string`.
  The chat round now detects that 400, retries once without image attachments, and stays
  text-only for that endpoint from then on; screenshots remain in the conversation history
  and UI, and vision-capable endpoints are unaffected.

### 🔒 Security
- **Plugin SDK no longer bridges to a wildcard origin (BREAKING for plugin toolchain 2.0.0).**
  `@infinia/plugin-sdk` removed the historical `'*'` default for the postMessage bridge:
  the embedding shell must append `?shellOrigin=<its origin>` to the plugin URL (or the
  caller passes `allowedOrigin`), otherwise the client refuses every request — previously
  ANY website that iframed the loopback-served plugin page could silently receive every
  invoke response. Plugin UIs built with this SDK therefore refuse to bridge on hosts that
  do not pass `?shellOrigin=`, which is why the unreleased toolchain line moves to 2.0.0.
  The parameter pins the bridge for well-behaved hosts but is not an authenticity boundary
  (an embedder controlling the iframe URL can forge it); origin assurance belongs server-side.
- **Dev-server AUTH token handshake.** The `fengyu-plugin-devkit` loopback dev server
  requires every connection to lead with `AUTH <token>`; the token is random per start and
  shared with the dev Vite client through a user-only file under `~/.fengyu/` that is
  created `0600` owner-only from the first byte. Loopback binding alone had left the full
  worker RPC surface open to any local process.
- **Optional Ed25519 checksum signing for releases.** `fengyu-release.yml` signs
  `checksums.txt` into `checksums.txt.sig` (base64 Ed25519) whenever the
  `FENGYU_SIGNING_KEY` secret is configured; without the secret the release stays
  checksum-only, so unsigned builds keep working.

### ⬆️ Dependencies

- `vue-i18n` 10.0.8 → 11.4.8 (frontend + email plugin UI). v9/v10 are EOL upstream — the
  project now forbids EOL dependencies on every surface. v11's stricter message compiler
  surfaced one unescaped literal `@` in `settings.mcp.placeholderArguments`, now escaped
  with `{'@'}` in both locales.
- `vite` 6 → 7.3.6 (frontend, with `@vitejs/plugin-vue` 6 and a resolution unifying every
  vite resolve on 7.x). Vite 6 left the upstream support window once Node 20 reached EOL;
  7.x is the maintained line the plugin toolchain already uses.
- `vitest` 3 → 4.1 (frontend + desktop) and `pinia` 2.3 → 4.0.3 (frontend). Vitest 4's new
  spy implementation is not constructible from arrow-function implementations, so the two
  desktop suites that stub `new BrowserWindow(...)` were switched to function-expression
  implementations.
- `typescript` 5.7 → 5.9.3 and `playwright` 1.50 → 1.62.1 (desktop + `@playwright/test`),
  aligning the desktop toolchain with the repo's 5.9 TypeScript line and a current
  Playwright.
- Removed dead dependencies: `miglayout-swing` (a JavaFX-migration-era Swing layout
  manager with zero source references) and the never-referenced `com.microsoft.playwright`
  managed entry + property in the root POM.
- `fengyu-release.yml` now runs `yarn npm audit --environment production` for the frontend
  and desktop areas — any advisory, including EOL/maintenance deprecations without an
  exploitable CVE, fails the release (the recorded no-EOL-dependencies policy).

### ✨ Added
- **Constrained intranet application updates through FY-Proxy.** The desktop loads its persisted
  update channel before the first automatic probe, supports the same channel for manual checks,
  verifies FY-Proxy SHA-256 metadata for Windows portable ZIP updates, and uses a dedicated
  electron-updater feed for lite x64 deb packages. The proxy accepts and serves only those two
  package classes and rejects NSIS, AppImage, macOS, JRE, and portable Web/JAR assets.
- **Automatic conversation compaction for long AI chats.** The cloud and Ollama chat paths now
  estimate provider input from UTF-8 bytes and, at 60% of the configured context window, summarize
  the oldest complete rounds into a marked assistant context note while preserving system messages
  and the latest eight rounds verbatim. The full transcript remains unchanged for the UI and
  persistence, and a failed summary falls back safely to the original history. Settings exposes the
  context-window size (32,768 tokens by default; `0` disables compaction).
- **Stateful, multimodal browser sessions.** A Java-side `BrowserSession` now routes operations to
  the same isolated Electron context/tab, caches URL/title and snapshot refs per tab, and rejects
  unknown or stale refs before an action. Four tab-management tools plus `browser_batch` extend the
  browser surface to 21 tools; the batch path performs one snapshot plus click/type/press in a
  single serialized bridge request. `browser_screenshot` now carries the actual PNG into the next
  Spring AI model round as `Media(image/png)` while retaining its DOM snapshot and accessibility
  text fallback.
- **First-class read-only web retrieval.** New host-embedded `web_search` and `web_fetch` tools
  separate lightweight discovery/content reading from interactive browser work. Fetches are
  bounded, follow a limited redirect chain, reject local/private-network targets, and enter the
  shared approval policy as `read` operations.

### ♻️ Changed
- **Tool-loop context is now bounded.** Tool responses larger than 64 KiB retain their first 75%
  and final 25% with an explicit omitted-character marker before the next model round, preventing a
  single result from consuming the conversation window while the SSE activity still receives the
  complete result.
- **Chat and Plan-and-Execute share one approval policy.** Both paths now use the same decision for
  `read`, `write`, `command`, and `external` effects, eliminating future permission drift. MCP tools
  remain conservatively wrapped as `external`, isolated plugin tools keep their manifest effect,
  and the 21 built-in browser tools now declare per-operation effects: inspection is `read`, while
  navigation, page interaction, JavaScript evaluation, and window close are `external`.
- **Browser bridge I/O is asynchronous.** Java uses `HttpClient.sendAsync` with virtual-thread
  response processing and waits only at Spring AI's synchronous tool boundary. Electron preserves
  serialized input semantics while routing independent logical sessions, contexts, and tabs.

### 🐛 Fixed
- **Command failures no longer lose their diagnostic tail.** `execute_command` drains stdout and
  stderr concurrently and returns them separately (`stdout`/`stderr` plus per-stream truncation
  flags), while keeping the combined `output`/`truncated` fields for compatibility. Oversized streams
  preserve both the head and tail instead of discarding the end where compilers and shells usually
  report the actionable error.

## [4.0.0-beta.3] — 2026-08-13

### ✨ Added
- **One responsive UI system for every official plugin.** `@infinia/plugin-ui` now provides
  `FyPluginPage` for shared responsive content gutters and `FyProgress` for determinate or
  indeterminate long-running state, plus reusable surface/action/status/log/table CSS hooks.
  `FyPluginShell` owns the notification center and supports navigation-free single-workspace apps;
  notification composables share one client-bound fallback queue with consistent tones and timing.
- **Plugin manifest schema v2 — the single hand-written contract.** `manifest.json` is now
  `schemaVersion: 2` with `additionalProperties: false` throughout. RPC methods live once in an
  `rpc.methods` table whose `inputSchema`/`outputSchema` are JSON-Schema **objects** (not escaped
  strings); `aiTools` reference those methods by `method` with a mandatory `effect`, duplicating no
  schema. `backend` keeps only `callTimeoutSeconds` — the worker is implicitly
  `java -jar backend/worker.jar` over JSON-RPC 2.0 (`backend.command`/`backend.protocol` removed).
  The CLI validator enforces every rule, including a raw-text duplicate-`rpc.methods`-key scan
  (JSON.parse would otherwise silently merge duplicates).
- **Deterministic contract generator.** `fengyu init|dev|build` generate a typed TS RPC client
  (`ui-src/src/generated/fengyu-rpc.ts` — `createPluginRpc(client)` + per-method `Input`/`Output`
  types) and Java records + a centralized `PluginMethods` name class
  (`src/main/java/<id>/generated/`) straight from `rpc.methods`. Output is byte-for-byte stable
  (sorted), handles identifier escaping / reserved words / nullable / required, nests records for
  object/array fields, and rejects unsupported JSON-Schema constructs instead of coercing to
  `Object`. `fengyu check` detects generated-file drift without writing.
- **Typed Java Worker SDK + standard cancellation.** `JsonRpcWorker.method(NAME, Input, Output,
  handler)` registers typed handlers; `RpcContext` exposes `callId`/`pluginId`/`pluginRoot`/
  `locale`/`cancellation()`/`logger()`. The dispatch loop is split into a reader + handler pool so a
  `$/cancelRequest` notification cancels an in-flight call (returns `CANCELLED`, worker survives);
  EOF drains gracefully. Stable `RpcError.Code` set (`INVALID_ARGUMENT`/`PERMISSION_DENIED`/
  `NOT_FOUND`/`CONFLICT`/`CANCELLED`/`INTERNAL`) maps to JSON-RPC codes and an `error.data.code`
  label. The old `JsonRpcWorker.string/integer` Map-parsing helpers were removed.
- **Reserved `_fengyu` metadata envelope on the worker RPC frame.** The request locale now rides in
  a host-owned top-level `_fengyu` object (`_fengyu.locale`) instead of being injected into `params`,
  so a plugin method may freely declare its own `locale` input field without it being overwritten by
  the request locale. The Worker SDK reads `_fengyu.locale` (binding both `RpcContext.locale()` and
  `WorkerLocale`, so synchronous handlers now resolve messages in the request language — previously
  only `Jobs` propagated locale) and falls back to the legacy `params.locale` key for hosts that have
  not yet adopted the envelope. Any frame-root key beginning with `_fengyu` is reserved.
- **Shared plugin bridge protocol `3.0.0` and capability pre-check.** `@infinia/plugin-sdk/protocol`
  is the side-effect-free source for iframe/host message types, method constants, capabilities, and
  structured errors. `HostEnvironment` now carries `pluginId`/`pluginVersion`/`permissions`;
  `HostError.code` adds `TIMEOUT`/`CANCELLED`. The SDK validates the capability before each request,
  posts the cancel notification on both timeout and abort, and silently drops responses for unknown
  ids / wrong origin / wrong protocol version.
- **Host v2 install + dispatch.** Install accepts schema v2 only and validates `rpc.methods`
  (unknown method rejected before worker start; AI tool schemas read from the referenced method's
  object schema — no string re-parsing). The iframe/HTTP `callId` is passed through as the JSON-RPC
  `id`. Cancel sends `$/cancelRequest` first and only force-restarts on timeout. Worker errors map
  to typed exceptions: `PERMISSION_DENIED` → HTTP 403 (no longer a generic 500), `CANCELLED` → 499.
- **All four official plugins migrated to the typed model.** `markdown` (canary), `excel`,
  `offlinepython`, and `email` now ship schema-v2 manifests, generated TS/Java contracts, typed
  `method(...)` workers, and transport-cancellation tests. `offlinepython`'s domain
  `build.cancel`/`deploy.cancel` now reaps the whole Python/pip subprocess tree.
- **Plugin cards re-fetch their server-localized name/description on locale change.** Names and
  descriptions are resolved server-side per request locale (each manifest's i18n block), but the
  tools page, market "plugins" tab, and store tab fetched once and cached — so switching the UI
  language flipped the `$t()` strings but left the cards' server-supplied text in the old language
  until a manual reload. A reactive `localeRef` now drives re-fetches in the plugins/plugin stores
  and `PluginMarket.vue`; the axios interceptor already sends the current `Accept-Language` header
  on every request.

### ♻️ Changed
- **Markdown, Excel, Email Center, and Offline Python now use the same official UI foundation.**
  Email's private green palette and custom task rail were removed; all four plugins now use the
  shared responsive shell/page, notification path, progress treatment, theme tokens, and
  container-aware narrow layouts. The host sidebar automatically enters rail mode when space is
  constrained, and both CLI Vue templates emit the same composition for new plugins.
- **Convention-based Toolchain 2 CLI.** The public command surface is `fengyu init`, `dev`,
  `check`, and `build`. `fengyu.plugin.json` and arbitrary command arrays were removed; projects use
  standard npm scripts and Maven lifecycles, the Worker is discovered as the unique
  `target/*-worker.jar`, and packages are written under `dist/`. The unused direct-ESM
  `default.mount(el, ctx)` loader was removed; sandboxed iframe + shared protocol is the only UI
  runtime.
- **`offlinepython` RPC method names are lowerCamelCase.** Schema v2 forbids dots in method keys,
  so `config.get`→`configGet`, `requirements.save`→`requirementsSave`, `build.start`→`buildStart`,
  etc. The smoke scripts were updated; external callers of the old dotted names must switch.

### 🐛 Fixed
- **Official plugin layouts now update correctly in real host iframes.** Step changes remount their
  keyed content so restored Excel sessions cannot retain a previous step's layout class; host
  notification rejection reaches the shared local snackbar; and bundled official plugins are
  refreshed when same-version `.fyp` bytes change instead of leaving stale installed UI behind.
- **macOS no longer shows "compatibility mode" for process isolation.** The Settings "Process
  isolation" badge was a strict `sandboxed ? active : compatibility` binary, so macOS's
  `sandbox-exec` posture (correctly classified by the backend as `reduced` — a deny-sensitive
  boundary, not full isolation) collapsed into the no-isolation warning "兼容模式 · 必须明确审批",
  contradicting the card below it (which keys the compatibility toggle on `compatibilityMode`).
  The badge is now three-state — `sandboxed` (Linux bwrap) and `reduced` (macOS) both render green;
  the yellow "compatibility mode" chip is reserved for Windows Job Object / no-backend. A muted
  hint explains the macOS deny-sensitive model. Backend behavior was already correct; this is a
  frontend display fix.
- **offlinepython no longer crashes on omitted nullable fields, and its logging is no longer
  silently dropped.** `verify`'s `scope` and `deploy`'s `target.kind` are nullable enums in the
  manifest (defaulting to ALL / global); omitting them threw `NullPointerException` before the
  existing null-handling ran. The custom `OpbLogger` wrapper — wired with a null instance in
  production — was removed in favour of direct SLF4J, so the package-done milestone now emits and
  previously silent `catch` blocks log. (Excel's analyze/execute `catch` blocks also warn-log now;
  cancellation still propagates as `CANCELLED` first.)

## [4.0.0-beta.2] — 2026-08-11

### ✨ Added
- **Settings-driven update channel.** The Settings page can now point the app's update check at an
  intranet/offline FY-Proxy base URL instead of the default GitHub feed. The value is persisted in
  the `app_setting` store and honored on both update paths: the backend `UpdateCheckService` reads
  it per check (live without a JVM restart), and the Electron shell bootstraps
  `FENGYU_UPDATE_API_BASE` from the backend before the first update check and updates it live via an
  `update:set-api-base` IPC from the renderer. `PUT /api/settings` validates the value as an absolute
  HTTP(S) URL with no credentials, query, or fragment, mirroring the desktop `update-feed.ts`
  validation so both channels accept the same value; invalid values map to HTTP 400.
- **Full i18n for all official plugins (front + back end).** Plugin worker backends now render
  localized `summary`/`error` messages, and the Markdown and Excel plugin UIs are fully localized
  to match the Email and Offline Python Builder plugins. Locale flows per-request from the host
  `Accept-Language` header (and the AI chat turn) into the worker, with no change to the JSON-RPC
  envelope or the `PluginHandler` signature:
  - **SDK `1.3.0`** adds `WorkerLocale` (per-request locale ThreadLocal bound by `JsonRpcWorker.serve`
    from a `locale` params key), `PluginMessages` (a classpath `i18n/messages[_zh].properties` bundle
    resolver), and keyed `okKey`/`failKey`/`t` helpers on `PluginHandlerSupport`. Workers without
    bundles keep their prior English behaviour (default locale `en`).
  - **Host** injects the resolved locale on both call paths: `PluginRuntimeController.invoke` reads
    `Accept-Language`, and the AI path carries the locale through a new `AiToolLocaleContext`
    ThreadLocal (mirroring `AiPermissionContext`) bound for the chat turn.
  - **Plugin backends** ship `i18n/messages[_zh].properties` and key their user-facing strings:
    Markdown, Excel (worker + progress logs), Email (handlers + services), and Offline Python Builder
    (activating the previously-orphaned bundles). The Offline Python doctor check `id`/`value`
    protocol tokens stay locale-neutral (the frontend translates them, as before).
  - **Markdown + Excel frontends** gain a lightweight `i18n.ts` + `useFengYuEnvironment()` composable
    (mirroring the Offline Python pattern), keying every visible UI string.
  - **Unified production lifecycle.** All six plugin-tooling artifacts now agree on `1.3.0`.
    `@infinia/plugin-ui` provides `mountFengYuApp` and `createFengYuI18n`, so the four official
    plugin UIs share one ready/theme/locale/mount/pagehide-dispose path. The TS SDK deduplicates
    `ready()`, caches and merges environment updates, and exposes `currentEnvironment()`.
    Worker `Jobs` inherit the initiating request locale, support race-safe cancellation and
    reject starts after close; `JsonRpcWorker.onClose` closes registered resources in reverse
    order before exit, and the official async workers register their job registries for teardown.
    Toolchain and official-plugin lockfiles also pick up patched `fast-uri`, `nanoid`, `dompurify`,
    and `brace-expansion` releases; their npm audits now report zero known vulnerabilities.

## [4.0.0-beta.1] — 2026-08-09

### ✨ Added
- **Official Browser Agent.** The fifth bundled plugin drives a real Chromium through nine
  confirmation-aware AI tools for navigation, clicking, typing, DOM inspection, screenshots,
  waiting, JavaScript evaluation, and session shutdown. Chromium resolution supports a configured
  system browser, a plugin-managed download, and Playwright fallback.
- **Isolated plugin database lifecycle and localized manifests.** Database-capable workers now use
  user-authorized per-plugin credentials with recoverable provisioning/deprovisioning state, while
  manifest display strings and AI-tool descriptions support locale-family fallback. Plugin logs are
  persisted per plugin and exposed through ordered REST/SSE history.
- **Windows Job Object process isolation backend (`ProcessSandbox` `WINDOWS_JOB`).** Plugin workers
  and AI-authored commands on Windows now run inside a Win32 Job Object configured with
  `KILL_ON_JOB_CLOSE`, giving reliable process-tree termination: closing the job handle (or
  `TerminateJobObject`) kills the worker and any descendants (e.g. a `pip` subprocess) without
  relying on `ProcessHandle.descendants()`, which was unreliable on Windows. The Job Object is a
  process-layer isolation only — filesystem and network confinement remain a known gap on Windows
  (the explicit-approval gate still guards every effect there). `GET /api/security/process-isolation`
  reports `backend: "windows-job"`. JNA 5.19.1 was added for the Win32 binding.

### ♻️ Changed
- **Browser automation moved from `plugin-browser` (Playwright) to a host-embedded capability.**
  Browser automation is now built into the desktop application and exposed by the backend
  `BrowserTool`, not a `.fyp` plugin. It drives a real browser window through Electron's native
  `webContents` and the Chrome DevTools Protocol (CDP) over a loopback HTTP bridge — **no Playwright
  dependency and no separate Chromium download**. The nine AI tools (`browser_navigate`,
  `browser_click`, `browser_type`, `browser_get_text`, `browser_query`, `browser_screenshot`,
  `browser_wait_for`, `browser_eval_js`, `browser_close`) remain, each approval-gated. The capability
  is **desktop-only**: it requires the Electron shell and is unavailable in pure-web / headless mode.

### 🗑️ Removed
- **`plugin-browser` (`fan.summer.browser`) official plugin.** The Playwright-based browser plugin
  has been removed; its function is now provided by the host-embedded `BrowserTool` (see Changed
  above). `OfficialPlugins` now ships four plugins: `plugin-markdown`, `plugin-excel`,
  `plugin-email`, and `plugin-offlinepython`.

### 🐛 Fixed
- **Official plugins no longer reappear after the user uninstalls them.** Uninstall previously
  deleted both the package directory and the integrity record, leaving no trace; on the next restart
  the official-plugin seeder could not distinguish a user uninstall from a never-installed plugin and
  reinstalled the bundled archive. A persistent **uninstall tombstone** now marks uninstalled
  plugins; the seeder checks it before seeding and skips them. A later reinstall (local upload,
  online upgrade, or a bundled upgrade) clears the tombstone so the cycle is repeatable.
- **Local `.fyp` install with a matching `.sha256` sidecar may now claim official identity.** The
  native install path now verifies a sibling `<archive>.sha256` sidecar (GNU coreutils
  `sha256sum -c` format — the same credential the official seeder verifies), letting a user install
  a rebuilt official plugin locally at the same trust level. Without a sidecar (or with a mismatched
  one) the install stays untrusted, so the existing reservation still blocks official /
  namespace-squatting. (Asymmetric signature verification remains a tracked follow-up; a sidecar is
  a tamper/corruption check, not an independent authenticity anchor.)
- **Excel split no longer falls back to copying the whole sheet after a worker restart.** The host
  tears down and relaunches a plugin worker whenever its file-grant version changes — in the Excel
  wizard, picking the output folder (Output step) grants the output dir *after* `configure` (Mode
  step), so the worker serving `split` is a fresh process whose in-memory session store is empty.
  `split` now re-applies the full split config from its own arguments (fields absent are left
  untouched, preserving partial-update callers), making the session store a cache rather than a
  correctness dependency.
- **Web and desktop dependency advisories.** Updated DOMPurify, nanoid, js-yaml, PostCSS, fast-uri,
  and brace-expansion to patched compatible versions; both npm dependency audits now report zero
  known vulnerabilities.
- **Beta plugin runtime hardening.** Official package checksum sidecars now travel with `.fyp`
  artifacts through Web/desktop release assembly; plugin DB provisioning/deprovisioning keeps
  recoverable lifecycle state; JSON-RPC frames, async job logs, and plugin-log SSE replay are
  bounded and ordered; Windows command Job Object handles are reclaimed on every path. Plugin
  uninstall now asks whether to retain or permanently delete runtime data and the provisioned DB
  namespace, and reports deletion failures instead of silently leaving data behind.
- **Plugin worker processes no longer leak after the host exits.** A plugin worker is an
  independent JVM spawned by the host backend; previously the worker was often not reaped when the
  host exited (macOS/Windows have no equivalent of Linux's `bwrap --die-with-parent`, and Electron
  only signalled the backend's direct PID with no tree-kill), so leaked workers kept holding the
  exclusive file locks of embedded databases (H2/SQLite), leaving the database files undeletable.
  Fixed with four complementary layers of defense:
  - **Worker SDK watchdog** (`fengyu-plugin-sdk` 1.1.0 → 1.2.0): the production `run()` entry point
    gained dual watchdogs — stdin-EOF (primary) and a parent-liveness poll (auxiliary). On host
    shutdown/crash the worker auto-`System.exit`s, ensuring it exits and releases file locks even
    when it holds non-daemon thread pools (HikariCP, etc.).
  - **Explicit host shutdown hook**: `HeadlessLauncher` registers a JVM shutdown hook in APP mode
    that is independent of Spring and calls `PluginProcessManager.close()` directly, instead of
    relying solely on the timing of Spring's default hook.
  - **Grandchild-process fallback**: after destroying a worker, `PluginProcessManager.Worker.close()`
    recursively `destroyForcibly`s its descendant processes (e.g. offlinepython's `pip` subprocess)
    to avoid orphaning them.
  - **Electron tree-kill**: the desktop shell adds a `tree-kill` dependency so that on exit it sends
    SIGTERM→SIGKILL to the entire backend process tree (including worker grandchildren), as a
    fallback when the host crashes.
  - The exit traps in `scripts/e2e-smoke.sh` and `scripts/offlinepython-e2e-smoke.sh` now also run
    `pkill -P` to clean up worker subprocess trees.
- **Offline Python Builder deploy now uses the deployment machine's Python.** The deploy step
  previously re-ran Python detection with a null hint, discarding the interpreter resolved from the
  target — so on machines whose Python (conda/pyenv/venv) was not on `PATH`, version detection
  returned `null`, every C-extension wheel (numpy/pandas/…) was judged incompatible, and the deploy
  silently installed zero packages (or reported success with nothing actually installed).
  `DeployService` now resolves the version from the target's interpreter and fails loudly if it
  cannot, instead of masking the failure as an empty match. The Deploy panel also auto-detects this
  machine's interpreter on load and falls back to a manual path input when detection fails, so
  offline machines with non-`PATH` interpreters can still deploy.

## [4.0.0-alpha.8] — 2026-08-04

### 🐛 Fixed
- **Email plugin now works with the default embedded-H2 database.** The host holds an exclusive
  file lock on its own H2 file, so a sandboxed plugin worker could not attach to the same file —
  H2 rejected the `AUTO_SERVER` + sandbox combination and every email RPC failed at worker boot.
  Database-permission workers now get their own DB file under their plugin data directory for
  embedded databases (H2/SQLite), while remote databases (MySQL/PostgreSQL) still share the host
  URL (real servers handle concurrent connections). The useless `AUTO_SERVER=TRUE` option was also
  dropped from the host H2 URL template. The e2e smoke now exercises an email RPC end-to-end.

## [4.0.0-alpha.7] — 2026-08-04

### ✨ Added
- **Unified plugin store (Claude / Codex / FengYu).** A new `/api/plugin-store/*` REST surface lets
  you subscribe to third-party Claude Code and OpenAI Codex marketplaces alongside the FengYu
  catalog, browse a merged, filtered, source-badged grid, and install Claude/Codex plugins by
  cloning their git source (JGit) with pinned-sha verification. The frontend ships a unified
  "Stores" tab with a source manager, install/update/uninstall actions, declared-skills and MCP
  rendering, and an in-app detail drawer. JGit 7.7.0 was added for clone support.
- **Windows unsandboxed-plugins toggle.** On platforms without a native process sandbox (Windows,
  or any host where `ProcessSandbox.detect()` is `NONE`), a new Settings row — gated behind a
  confirmation dialog and defaulting fail-closed — lets a user opt into running plugin workers via
  the `unrestricted()` channel (`effectiveUnrestricted = fullAccess || unsandboxedPluginsEnabled`).
  AI command-approval and the sandbox fail-closed primitive are untouched; this only unlocks plugin
  workers.

### 🐛 Fixed
- **Store installer path-traversal (security).** A malicious third-party marketplace entry with a
  `name` containing path-traversal sequences (`../…`) could delete or overwrite arbitrary
  user-writable files, because the raw name flowed unchecked into `skills/<uid>` and
  `mcp-servers/<uid>.json` paths that are both deleted and written on install. Catalog adapters now
  slugify the name to a single safe path segment, and the installer asserts every uid-derived path
  stays inside the runtime root before any delete/write (defense in depth).
- **Store clone URL scheme validation (security).** Clone URLs from third-party marketplace JSON are
  now restricted to `https`/`http`/`file`; `ftp:`, `jar:`, and bare local paths are rejected before
  JGit sees them.
- **Store clone cleanup and timeouts.** A failed clone no longer leaves a `.clone-/agent-*` temp dir
  (with `.git`) behind, and the configured `fengyu.store.git-clone-timeout-seconds` is now actually
  applied to the clone.
- **Symlink defense in skill extraction (security).** A malicious repo containing a symlink whose
  target escapes the plugin root can no longer leak host-readable files into the runtime tree; the
  skill walker now skips symlinks and copies with `NOFOLLOW_LINKS`.
- **Codex install integrity.** Codex sources (which declare no pinned sha) now record the resolved
  HEAD commit sha in the install record, so every install carries an auditable content fingerprint
  instead of `null`.
- **Catalog fetch size cap.** Catalog responses are now bounded to 16 MiB, so a malicious or broken
  catalog URL cannot OOM the backend by streaming an unbounded body into memory.
- **Homepage XSS (security).** The store detail drawer's Homepage button now allows only
  `http(s):`/`mailto:` URLs, blocking `javascript:` URIs from third-party catalog fields.
- **Frontend store error surfacing.** Install/update/uninstall failures now surface to the user
  instead of being silently swallowed; `busy` is always reset. Malformed catalog array fields are
  coerced to `[]` so the template never throws.
- **Plugin enable/disable marker.** Toggling a Claude/Codex plugin's enable state now writes the
  `.disabled` marker the skill loader reads, so disabling actually stops the skill from loading.

### ♻️ Changed
- **Hardened the unsandboxed-plugins platform gate** to rely on `compatibilityMode`
  (`isNativeSandboxAvailable()`), per the design — the toggle appears on any platform lacking a
  native sandbox, not strictly Windows.

## [4.0.0-alpha.6] — 2026-08-02

### ✨ Added
- **AI chat now has Codex-style action approval profiles.** The composer offers Ask for approval,
  Approve for me, and Full access; command execution and plugin-declared read/write/external
  effects share one host approval gate. Approval cards stay inside the composer, while calls render
  as compact progress rows such as `Read FengYu Plugin Dev skill`. Plugin manifests can declare an
  optional `aiTools[].effect`, with undeclared third-party effects treated conservatively.

### 🐛 Fixed
- **AI chat now handles Excel file workflows and approval-heavy replies reliably.** Attached
  files/directories are granted to every compatible backend plugin, and existing absolute paths
  typed in a user message are converted into read-only plugin-scoped FileRefs. Selected directories
  retain writable access where declared and are injected into single write-directory plugin tool
  parameters; Excel analysis is preferred before split operations, and unresolved FileRef objects
  can no longer become map-shaped output folders. IME composition no longer drops a trailing English
  segment on click-to-send, and final answers render below command approval cards.

### ✨ Added
- **Live visual-workflow tool contracts.** Plugin AI tools may declare an optional serialized
  `outputSchema`; the official Excel, Email, and Offline Python tools now publish user-facing input
  metadata and result-envelope schemas for canvas configuration.

### ♻️ Changed
- **Agent tool discovery now follows the installed-plugin lifecycle.** New runs read a live tool
  registry, while canvas nodes survive tool disable/uninstall, block unsafe execution, and reconcile
  newly required inputs when the same tool is enabled again.
- **A directory the user names as an output target never becomes worker-writable.** Instead a
  plugin-owned staging directory is created per turn and handed to the worker as a writable sandbox
  root; the host copies its contents to the real target after the turn completes and deletes the
  staging tree. The real directory stays read-only, so the OS sandbox writable-roots remain stable
  (one staging grant per turn) and a worker can never overwrite files in a user-named folder.
  Plugin workers also now receive a per-plugin writable temp directory
  (`-Djava.io.tmpdir` + `TMPDIR`/`TMP`/`TEMP`), and `PLUGIN_DATA_DIR_ENV` is set for every plugin
  rather than only database-capable ones. macOS sandbox writable-roots are canonicalized via
  `toRealPath()` before the profile is built (resolves `/var` → `/private/var`).
- **The chat tool-loop cap is now configurable.** A new `ai.max_tool_rounds` setting (default 50,
  `0` = unlimited) replaces the previously hard-coded per-backend limits and bounds the number of
  tool-call rounds a turn may take, stopping a model that re-requests the same tool from wedging the
  virtual thread and locking the backend. It is editable in Settings → AI.
- **Bumped Apache POI to 5.5.1.**

### 🐛 Fixed
- **The permission-mode menu is now disabled while a generation is in flight**, so an approval
  profile can no longer be switched mid-turn.

## [4.0.0] — 2026-07-29

### ✨ Added
- **Built-in `fengyu-plugin-dev` skill.** A second built-in skill (alongside `fengyu-features`)
  that teaches the in-app assistant the 4.0.0 plugin model: `.fyp` packages (sandboxed iframe UI +
  out-of-process JSON-RPC worker), the `manifest.json` fields, the permission enum, `aiTools`, and
  the `fengyu` CLI build/install flow. It is authored for the app's runtime context, distinct from
  the repo's agent-workflow skill of the same id.

### ♻️ Changed
- **Removed the legacy `FengYu-Api` module.** Host-only AI contracts, tool categories, and theme
  state now live in the headless `FengYu` application module. The obsolete JavaFX preview assets
  and in-process plugin logging bridge were deleted, and Maven no longer manages JavaFX artifacts.
- **Unified the application version at 4.0.0.** Maven, the frontend, Electron shell, built-in
  skills, and official plugin packages now use the stable version.
- **Scoped the VitePress toolchain to `docs/`.** The documentation package manifest, lockfile,
  installed dependencies, local commands, and CI cache now live with the documentation sources
  instead of occupying the repository root.

## [4.0.0-alpha.5] — 2026-07-29

### ✨ Added
- **Agent runs are now durable.** Each plan-and-execute run is snapshotted to the database
  (`ai_agent_run`) with a sequenced lifecycle-event append log (`ai_agent_run_event`) covering
  `plan_ready`, `plan_approval_requested`, `step_start`/`step_complete`, `step_approval_requested`,
  `complete`, and `error`/`cancelled`. History is listable/detailable per user, a failed or cancelled
  run can be resumed from its last completed step, and on restart any non-terminal in-flight run
  (PLANNING / AWAITING_*_APPROVAL / EXECUTING) is reclassified as FAILED. Persistence failures are
  logged but never kill a healthy run.
- **Sensitive tool calls require explicit user approval.** A new `ApprovalRequiredTool` contract
  marks host tools that must never run without confirmation. In ordinary chat,
  `ChatToolApprovalGate` blocks each model response containing such calls on a confirmation card
  (5-minute expiry), and in the Plan-and-Execute Agent each step pauses for the same gate. Cancelling
  a generation or swapping the backend rejects all pending approvals.
- **`execute_command` tool with OS-level process sandboxing.** AI-authored shell commands run inside
  a native isolator when one is available — `bwrap` (bubblewrap) on Linux and `sandbox-exec`
  (Seatbelt) on macOS — with read-only system files, writes confined to the working directory, and
  the network isolated unless explicitly opted in. Inherited environment variables holding
  `TOKEN`/`SECRET`/`PASSWORD`/`API_KEY`/`CREDENTIAL`/`COOKIE`/`AUTHORIZATION` are stripped before
  launch, output is bounded (default 64 KiB, max 256 KiB) with truncation flagged, and a bounded
  timeout (default 30s, max 600s) forcibly terminates descendants. Where no isolator exists the tool
  falls back to direct execution and discloses `compatibilityMode` in the result; approval stays
  mandatory regardless. `GET /api/security/process-isolation` reports the active backend.
- **MCP (Model Context Protocol) client.** MCP servers configured via `spring.ai.mcp.client.*` are
  connected at startup and surface their tools to the Agent. `GET /api/mcp/status` reports the
  enabled flag, connection/tool counts, and per-connection detail (name, version, protocol version,
  initialized).
- **The host and Java plugin Workers now share one live log level.** The Settings page persists
  `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, or `OFF`, applies it to the host's Logback namespaces,
  and pushes it to running Workers without a restart. The Java Worker SDK replaces
  `slf4j-simple` with a structured stderr provider, preserving logger name, thread, level, message,
  and exception stack while keeping stdout reserved for JSON-RPC; legacy free-form stderr remains
  supported.

### 🔒 Security
- **Runtime secret files are owner-only on POSIX.** `SensitiveFilePermissions` applies `rwx------`
  to secret/key-material directories and `rw-------` to their files on macOS/Linux (no-op on
  Windows, where user-profile ACLs apply).

### ♻️ Changed
- **Default runtime state is self-contained under the launch directory.** Without an explicit
  `fengyu.runtime.dir`, the app now stores embedded databases in
  `<program-working-directory>/.fengyu/database/` and configuration, logs, plugins, skills, and
  other writable state under `<program-working-directory>/.fengyu/`.
- **Plugin Workers are sandboxed per manifest permissions.** `ProcessSandbox.plugin(...)` confines
  each Worker's writes to its plugin-owned roots (broadened when `files.write` is declared) and
  isolates the network according to the manifest, on supported isolators.

---

## [4.0.0-alpha.4] — 2026-07-28

### 🐛 Fixed
- **Plugin UI icons now render reliably in sandboxed third-party plugins.** The host CSP explicitly
  permits same-origin and bundled `data:` fonts for compatibility with existing `.fyp` packages,
  while `@infinia/plugin-ui` leaves its MDI stylesheet to the consuming Vite app so new builds emit
  ordinary hashed font assets instead of embedding multi-megabyte fonts in the library CSS. Official
  plugin UIs now declare `@mdi/font` directly so the Vite build can resolve the externalized
  `@import` under strict `npm ci`.
- **`window.fengyu` access is SSR-safe.** The desktop-bridge calls added to `settings.ts`,
  `main.ts`, and `router/index.ts` are now guarded with `typeof window !== 'undefined'`, so they no
  longer throw `ReferenceError` in vite SSR / the `node --test` suite.

### ♻️ Changed
- **Windows desktop releases ship an extract-and-run ZIP instead of a self-extracting portable
  `.exe`.** Both the lite and JRE variants keep the NSIS installer (`*-win-x64-setup.exe`) and now
  publish `*-win-x64-portable.zip` (extract once, then run `Infinia.exe`); the startup-time
  self-extraction of the old portable executable is gone. Artifact names were unified to
  `<product>-<version>-<platform>-<arch>[<form>].<ext>` across macOS / Windows / Linux
  (`Infinia-4.0.0-mac-arm64.dmg`, `Infinia-4.0.0-win-x64-setup.exe`, …), and the release workflow +
  its contract test were updated to match.
- **Desktop startup no longer flashes and no longer requires a live backend to pick a route.** The
  shell shows a splash window while it probes the backend, exposes the pre-probed setup state and the
  chosen theme to the renderer via `window.fengyu.setupMode()` / `initialTheme()` / `setTheme()`, and
  the router consumes that snapshot on first navigation before falling back to live checks.

### 🔧 Internal
- **Backend runtime directories are centralized.** Plugin, skill, plugin-data, and transient-file
  directories are now derived from one stable root via the new `RuntimePaths` and overridable through
  `fengyu.runtime.dir` (default `~/.fengyu`), replacing scattered `System.getProperty("user.dir")`
  paths. `CryptoUtil` derives the `.machineid` from the same root. This makes the packaged Electron
  shell and the portable Web distribution agree on where state lives.
- **Portable Web distribution keeps its state self-contained.** `run.sh` / `run.bat` now pass
  `-Dfengyu.runtime.dir=<dist>/data`, so the database, config, logs, and plugin data land next to the
  launcher inside the extracted folder rather than in the user's home directory — preserving the
  "unzip and run, move/delete leaves nothing behind" portability contract. `scripts/e2e-smoke.sh`
  pins the same property to its temp dir so its run is repeatable.

---

## [4.0.0-alpha.3] — 2026-07-25

### ♻️ Changed — Desktop shell: Tauri → Electron
- **The desktop shell was rewritten from Tauri 2.0 (Rust) to Electron 43 (TypeScript).** The backend
  lifecycle is **unchanged** — Electron spawns the backend JAR over loopback, waits for `/api/health`,
  and hands the token + api-base to the renderer via a `contextBridge` preload (`window.fengyu`).
  This replaces the old Tauri `window.__FENGYU_*` globals.
- **Two release variants per platform.** Each platform ships a **lite** build (user supplies Java 21+
  on `PATH`) and a **JRE** build that bundles a `jlink`-minimized JRE under `<resources>/jre/`:
  - **macOS** (arm64 + x64) — `Infinia-<ver>-mac.dmg` and `Infinia-<ver>-mac-jre.dmg`.
  - **Windows** (x64) — `Infinia-<ver>-win.exe` (NSIS installer) and a portable exe; plus the
    `-jre.exe` NSIS variant.
  - **Linux** (x64) — `Infinia-<ver>.AppImage` / `.deb` and the `-jre.AppImage`.
- **Auto-updater** (`electron-updater` against GitHub Releases), **system tray** (hide-to-tray on
  window close, backend stays alive until app quit), **single-instance lock**, and **file logging**
  (`electron-log` → `~/.fengyu/logs/desktop.log`).
- **Dev mode** defaults to connecting an IDE-started backend at `http://127.0.0.1:24056` (no spawn,
  no token, no supervisor); set `FENGYU_JAR=<jar>` (or `FENGYU_DEV_BACKEND=disabled`) to make the
  shell spawn its own backend with the full release lifecycle.
- **Security posture:** `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`. Navigation
  guards — `setWindowOpenHandler` delegates `http(s)` targets to the system browser and denies
  `window.open('file://...')`; `will-navigate` blocks cross-origin in-page navigation. See
  [docs/en/architecture/desktop.md](docs/en/architecture/desktop.md).

### ♻️ Toolchain directory consolidation
- Consolidated 7 plugin toolchain directories (2 Maven + 4 npm + schema) into `toolchain/`,
  flattening intermediate layers and unifying short semantic names
  (`sdk-java`/`devkit-java`/`sdk-ts`/`ui`/`dev`/`cli`/`spec`):
  - `FengYu-Plugin-Sdk`→`toolchain/sdk-java`
  - `FengYu-Plugin-DevKit`→`toolchain/devkit-java`
  - `plugin-sdk/typescript`→`toolchain/sdk-ts`
  - `plugin-ui/vue`→`toolchain/ui`
  - `plugin-dev`→`toolchain/dev`
  - `plugin-cli`→`toolchain/cli`
  - `plugin-spec`→`toolchain/spec`
- CI/release workflows and skills were renamed to `toolchain-*`
  (`plugin-tooling.yml`→`toolchain-ci.yml`, `plugin-tooling-release.yml`→`toolchain-release.yml`).
  The tag prefix `plugin-tooling-v*` is unchanged.
- **Maven artifactIds and npm package names are unchanged**: `fan.summer.fengyu.sdk:fengyu-plugin-sdk`,
  `fan.summer.fengyu.sdk:fengyu-plugin-devkit`, and `@infinia/plugin-sdk` / `@infinia/plugin-ui` /
  `@infinia/plugin-cli` / `@infinia/plugin-dev` are still published under their original names. The
  repo directories changed; the coordinates did not.

### ✨ Added
- **Skills** — a third extension surface (peer to plugins and AI tools) using Codex-style
  progressive disclosure. Enabled skills appear as a compact catalog in the system prompt, and
  the assistant loads a skill's full body on demand via the built-in `skill` tool — so large
  guidance documents never bloat the per-request token budget.
  - **Managed like plugins:** skills are packaged as **`.fys` archives** (zip: `manifest.json` +
    `SKILL.md`) and installed under `~/.fengyu/skills/<id>/` — a filesystem peer of
    `~/.fengyu/plugins/<id>/`. The full install/uninstall/enable/disable lifecycle mirrors the
    plugin system (atomic publish + backup rollback in `SkillPackageService`).
  - **Marketplace:** `fengyu.skills.catalog-url` points at a remote catalog JSON;
    `SkillMarketplaceService` merges remote entries with local install state (the lifecycle
    twin of `PluginMarketplaceService`). Install/update by id from the catalog.
  - **Enable state** is a `.disabled` filesystem marker (not a DB row), exactly like plugins,
    so it survives reinstall.
  - **OfficialSkillSeeder** (`ApplicationRunner`) idempotently seeds bundled `.fys` artifacts
    on boot, mirroring `OfficialPluginSeeder`.
  - Two discovery sources: **built-in** (`classpath:/skills/<id>/SKILL.md`, packaged in the
    JAR, cannot be uninstalled/disabled) and **installed** (`.fys` packages under
    `~/.fengyu/skills/`). Installed skills override built-ins on id clash.
  - New REST surface under `/api/skills`: list, detail, market, upload, upload-native, install,
    update, PATCH enabled, DELETE (409 for built-in skills). All require `X-FengYu-Token`.
  - Frontend: skill management is **integrated into the Plugins page** (`/plugins`) — a
    Codex-style `Plugins | Skills` tab pair at the top switches the view, with an installed
    fast-row and a card grid. A single Upload button accepts both `.fyp` and `.fys` archives
    and routes each by extension. No separate `/skills` route.
  - Built-in example skill `fengyu-features` (answers "what can FengYu do"), now with a
    `manifest.json` alongside its `SKILL.md`.
  - Skills are decoupled from plugins — they never touch `plugin-spec/` or a plugin manifest.
  - See [docs/en/skills/](docs/en/skills/) and [docs/zh/skills/](docs/zh/skills/).
- **IDE plugin debugging (plugin toolchain 1.1.0)** — `fengyu plugin dev` is replaced by an
  IDE-native flow so third-party authors debug UI and worker with real breakpoints, no JDWP
  remote attach.
  - **`@infinia/plugin-dev`** (new npm package, `plugin-dev/`) — a Vite plugin that turns the dev
    server into a FengYu host simulator: serves the iframe shell at `/__fengyu`, bridges
    `@infinia/plugin-sdk`'s `postMessage` calls, and forwards `rpc.invoke` to the dev worker over
    loopback TCP.
  - **`fengyu-plugin-devkit`** (new Maven artifact, `fan.summer.fengyu.sdk:fengyu-plugin-devkit`,
    `FengYu-Plugin-DevKit/`) — a loopback-only TCP JSON-RPC server (`PluginDevServer`) that drives
    the worker's `serve(RpcTransport)` loop. Scaffolded as `PluginDevMain` under
    `worker/src/test/java`; declared `<scope>test</scope>` so it never ships in the shaded JAR.
  - **`RpcTransport` abstraction** in the Java Worker SDK — `JsonRpcWorker.serve(RpcTransport)`
    shares the dispatch loop between production stdio (`StdioTransport`) and the devkit's loopback
    socket. `run()` / `run(InputStream, OutputStream)` are unchanged in behaviour.
  - The scaffolder now generates a shared `<Prefix>Worker.create()` handler factory, the production
    `<Prefix>WorkerMain`, and the IDE-debug `PluginDevMain`. UI-only scaffolds set `mockWorker: true`.
- **Real LLM planner + visual canvas workflow builder (AiAgent).** The empty
  `StubPlanGenerator` is replaced by `ChatBackendPlanGenerator`, which asks the active AI backend
  for a validated structured workflow while keeping tools disabled during planning (new
  `ChatBackend#chatWithoutTools` default — both `OllamaLocalBackend` and `SpringAiCloudBackend`
  honor the toggle). `AgentRunner` validates every workflow (model- or user-supplied) before any
  tool runs, resolves step-result references (`{{steps.N.result}}`, `{{last.result}}`), and accepts
  a caller-supplied workflow via `POST /api/agent/run` so the HTTP API can drive deterministic
  execution. The frontend gains a **Vue Flow** canvas (`AiAgent.vue`) with a tool palette,
  `WorkflowToolNode`, and `workflow.ts` that compiles the graph into the `AgentPlan` sent to the
  backend — a no-code peer to the AI plan path. EN/ZH strings updated. Also: the Electron main
  window starts hidden on a dark surface and is revealed only on first paint (or load failure),
  removing the white flash on cold start.

### 🐛 Fixed
- **IDE Worker failures no longer look successful.** When `workerEndpoint` is configured,
  `@infinia/plugin-dev` returns connection failures as RPC errors instead of silently substituting
  `devMock` data. All official plugin UIs now expose the documented `npm run dev` entry point.
- **Plugin-tooling release gates** now ship the canonical manifest schema, exempt the independently
  versioned Worker SDK from the application-parent check, and resolve the patched `fast-uri` 3.1.4.
- **First-launch (SETUP mode) no longer crashes.** `SkillController` was component-scanned into the
  DB-less SETUP context but depends on `SkillRegistry`/`SkillPackageService`/`SkillMarketplaceService`
  (in the `ai.skill` package, which SETUP mode does not scan), causing an
  `UnsatisfiedDependencyException` that aborted startup before the database wizard could run. It is
  now excluded from `SetupApplication`'s scan alongside the other APP-only controllers.
- **B1 — actuator `restart` endpoint removed from the default exposure.** `application.yml` now sets
  `management.endpoints.web.exposure.include: health` only. The `/actuator/restart` endpoint was
  reachable, and in the Web bundle's default no-token posture any loopback process could force a
  context restart (DoS). The SETUP→APP restart already goes through `System.exit(SETUP_DONE)` + the
  desktop supervisor, so no functionality is lost.
- **B2 — Web bundle generates a per-launch token by default.** `distribution/web/run.sh` and
  `run.bat` now generate a random `--token=` when the user passes none (previously auth was disabled
  by default). Explicit `--token=<t>` still overrides.
- **D1 — desktop navigation guards.** `setWindowOpenHandler` denies `window.open` and delegates
  `http(s)` targets to the system browser; `will-navigate` blocks cross-origin in-page navigation.
  Prevents a compromised page from `window.open('file://...')`.
- **D2 — auto-updater skips the JRE variant.** JRE-bundled builds detect `resourcesPath/jre` and skip
  the update check — the updater feed only references the lite variant, so auto-update would silently
  downgrade JRE users to the Java-dependent lite build. Full per-variant feeds are deferred.
- **D3 — supervisor `stop()` is now saved and called.** `main.ts` stores the
  `superviseSetupRestart` return value and calls it in `killBackend()` (defensive; prevents a future
  leak if a persistent listener is ever added).
- **D4 — APP-mode backend crash shows a dialog.** A lightweight exit listener in APP mode shows
  `dialog.showErrorBox` and quits on an unexpected backend crash (previously silent — the user saw
  only connection errors). The alpha does not auto-restart.
- **Desktop — right-edge dark strip on the window.** The shell allowed a document-level scrollbar
  whose transparent track exposed Electron's native window backing as a thin dark line on the
  right. `html/body/#app` now set `overflow: hidden` — the shell owns scrolling inside its panes
  (sidebar history, chat column) and no document scrollbar is ever created. The window's
  `backgroundColor` is also aligned to the dark theme (`#0d0d0d`) so the native backing never
  contrasts with the renderer.
- **Electron migration & tooling gates hardened.** New
  `desktop/electron/scripts/verify-frontend-dist.mjs` blocks a desktop build if
  `frontend-dist/` is missing or stale; `backend/spawn.ts`, `supervisor.ts`, `util/health.ts`
  and `main.ts` received additional lifecycle hardening with new unit tests
  (`health.test.ts`, `spawn.test.ts`, expanded `supervisor.test.ts`).
- **Plugin toolchain — `sdk-ts` lockfile desynced from 1.1.0** (root version stayed at 1.0.0);
  regenerated so the root and `packages[""]` agree. Also forced `brace-expansion=5.0.8` via
  `npm overrides` in `toolchain/ui` to clear 6 high-severity audit findings on
  `@vue/test-utils → js-beautify → … → brace-expansion@2.1.2` (no upstream fix exists on the
  2.x line).
- **Splash screen — shipped in the JRE build variant.** `electron-builder.jre.yml` had not been
  synced with the lite config when `resources/splash.html` was added to the desktop asar, so the
  self-contained JRE build (the flagship download) silently never showed a splash. The file list
  now matches `electron-builder.yml`, and both configs include `resources/splash.html`.
- **AI planner timeout no longer wedges the backend.** When a planning call exceeded its 180s
  budget (e.g. a hung Ollama process or a stalled provider connection),
  `ChatBackendPlanGenerator` gave up but the underlying stream kept blocking on `blockLast()` with
  no way to interrupt it. Both `OllamaLocalBackend` and `SpringAiCloudBackend` now hold the Reactor
  `Disposable` and await a `CountDownLatch` instead of `blockLast()`, so `cancelGeneration()` can
  `dispose()` the stream mid-flight and release the worker. The planner calls
  `cancelGeneration()` on any timeout/failure path, guaranteeing the `generating` flag is cleared
  and every subsequent `chat` / planning request no longer fails with
  *"Generation already in progress"*. Covered by a new regression test
  (`ChatBackendPlanGeneratorTest`).

### ♻️ Changed
- **CLI scope narrowed to `create` + `build`.** `fengyu plugin dev` moved to the IDE
  (`@infinia/plugin-dev` + `fengyu-plugin-devkit`); `fengyu plugin validate` is now a built-in step
  of `build` (the staging tree is always validated before packaging); `fengyu plugin install` is
  done through the host's plugin marketplace UI (`POST /api/plugin-market/upload`). `--port`,
  `--host`, `--token`, and `--ui-port` CLI flags were removed with their commands.
- **Plugin toolchain locked at six artifacts**, all released together as `plugin-tooling-vX.Y.Z`:
  the Worker SDK, the devkit, `@infinia/plugin-sdk`, `@infinia/plugin-ui`, `@infinia/plugin-cli`,
  and `@infinia/plugin-dev`. `plugin-cli/scripts/resolve-tooling-version.mjs` verifies all six.

### 🗑️ Removed
- `fengyu plugin dev`, `fengyu plugin validate`, and `fengyu plugin install` CLI subcommands and
  their source (`plugin-cli/src/dev.mjs`, `worker.mjs`, `install.mjs`). Development now happens in
  the IDE via `@infinia/plugin-dev`; the `FENGYU_DEBUG` JDWP remote-attach workaround is no longer
  needed (run `PluginDevMain` and set breakpoints directly).

---

## [4.0.0-alpha.1] — 2026-07-19

First public **alpha** of the 4.0 line. Infinia (FengYu) is re-architected from a JavaFX
desktop app into a **headless web + desktop application**: a loopback-only Spring Boot backend, a
Vue 3.5 + TypeScript SPA (identical for browser and desktop), and a Tauri 2.0 desktop shell that
sidecar-launches the backend. Built-in tools become isolated **`.fyp`** plugins — a sandboxed
iframe UI talking to an out-of-process JSON-RPC 2.0 worker. This alpha publishes unsigned
Windows/macOS/Linux Tauri packages and a portable, loopback-only Web distribution.

### ⚠️ Breaking Changes
- **JavaFX is gone.** All JavaFX code and dependencies are deleted — `FengYuApp`, the `ui/` shell,
  all built-in tool UI classes, the v1 `PluginRegistry`/`PluginLoader`, and every `org.openjfx:*`
  dependency. The running backend is headless (no window).
- **New entry point:** `fan.summer.fengyu.HeadlessLauncher` (was `fan.summer.Launcher`). It boots a
  loopback Spring Boot web server: `java -jar FengYu-4.0.0-alpha.1.jar --port=<n> --token=<t>`.
- **Plugin contract v2** (`FengYuPluginV2`): `descriptor()` + `invoke(action, args)` (JSON-in /
  JSON-out) + `aiTools()`. The old `createView()` → JavaFX `Node` contract is removed; UI is now a
  separately-served micro-frontend ESM bundle (`PluginDescriptor.uiEntry`).
- **`IconStyle` decoupled from JavaFX** — colours are RGB ints + `getColorHex()` (no
  `javafx.scene.paint.Color`).
- **Database layer migrated from MyBatis to Spring Data JPA + Hibernate 7** — see Removed.

### ✨ Added
- **Headless backend** (`fan.summer.fengyu.web.*`): `GET /api/health`, `GET /api/plugins`,
  `POST /api/plugins/{id}/invoke`, `GET /plugin-ui/{id}/**` (serves MF bundles), `GET/PUT
  /api/settings`, `POST /api/ai/chat` + `GET /api/ai/stream` (SSE: token / thinking / tool / done /
  error). Loopback-only bind + per-launch `X-FengYu-Token` auth (`?token=` for the SSE stream).
- **Alpha desktop + web release pipeline** — `v4.0.0-alpha.1` publishes unsigned Windows/macOS/Linux
  Tauri packages and a portable, loopback-only Web distribution. The Vue SPA is baked into the shaded
  backend JAR (`static/`) and served by a new `SpaForwardController`; a release-tag resolver
  (`scripts/resolve-release-version.mjs`) drives the version strings, and `scripts/package-web-release.sh`
  + `test-web-release.sh` assemble and smoke-test the archive. Code-signing, a bundled JRE, and the
  auto-updater remain deferred to a later release.
- **Multi-datasource setup wizard**: first launch guides users through database selection
  (H2 / SQLite / MySQL / PostgreSQL) with connection testing and automatic schema initialization.
  The backend boots in **SETUP mode** (minimal Spring context, no JPA) when
  `~/.fengyu/config/datasource.properties` is absent, and **APP mode** (full context) once it exists.
  The Tauri/desktop supervisor restarts the sidecar after the wizard completes.
- **JPA migration**: the database layer migrated from MyBatis to **Spring Data JPA + Hibernate 7**
  (`ddl-auto=update`). All 14 entities ported with `@Entity` annotations; 14 Spring Data repositories
  replace the MyBatis mappers.
- **User-system groundwork**: `sys_user` / `sys_session` tables, `user_id` row-level isolation on all
  user-scoped tables, and pluggable `AuthProvider` / `SecurityContext` interfaces with a Noop
  implementation (login UI deferred to a later phase). Local offline mode attributes all data to a
  single virtual user (id=1, "Summer"), created on APP-mode startup.
- **AES-GCM encryption** (`CryptoUtil`) for the datasource password field in
  `datasource.properties` — keys are machine-bound via a per-machine UUID.
- **Official plugin UI kit** `@infinia/plugin-ui` — a Vuetify 3 (Material Design 3) component library
  for generated plugins. Ships `FyPluginShell`, `FyPageHeader`, `FyToolbar`, SDK-backed
  `FyFilePicker` / `FyDirectoryPicker`, `FyStepWizard`, `FyTaskTable`, `FyNotificationCenter`, the
  `FyEmptyState` / `FyLoadingState` / `FyErrorState` / `FyPermissionNotice` state panels, and
  `FyConfirmDialog`, plus `createFengYuVuetify`, `bindFengYuEnvironment`, and `provideFengYuClient`
  so the scaffolded `main.ts` binds the host theme/locale automatically.
- **Email Center `.fyp`** (`fan.summer.email`) with a sandboxed five-tab Vue/Vuetify/TipTap UI, an
  isolated official-SDK Java Worker, and package permissions limited to database, email network, and
  authorized file read/write capabilities. Multi-account SMTP/IMAP configuration, AES-GCM credential
  storage, address-book/tag CRUD, confirmed single and batch sending, manual IMAP `.eml` collection,
  archive search/detail, and seven manifest-declared AI tools. Plugin-owned tables use the
  `FengTu_PL_Email_` namespace across H2, SQLite, MySQL, and PostgreSQL.
- **Excel Splitter `.fyp`** (`fan.summer.excel`) — BY_SHEET / BY_COLUMN / COMPLEX split modes with a
  stateful four-step wizard, six manifest-declared AI tools, and authorized file read/write.
- **Markdown Editor `.fyp`** (`fan.summer.markdown`) — first official v2 plugin: server-side
  commonmark render via `invoke("render", {markdown})` plus a Vue split-editor + live preview.
- **`frontend/`** — Vue 3.5.39 + TS shell: sidebar (collapsible, categories), theme (dark/light),
  settings, AI chat (SSE + markdown + collapsible thinking), ToolGrid, and a micro-frontend host
  that dynamically imports each plugin's `uiEntry`.
- **`desktop/`** — Tauri 2.0 shell: spawns the Java sidecar (`--port=24056`), reads `FENGYU_PORT`
  from stdout, polls `/api/health`, injects the backend URL + token into the webview, kills the
  sidecar on close.
- **Publishable plugin toolchain** — the Java Worker SDK (`fan.summer.fengyu.sdk:fengyu-plugin-sdk`,
  independently versioned, GitHub Packages) and the npm packages `@infinia/plugin-sdk`,
  `@infinia/plugin-ui`, `@infinia/plugin-cli`, plus a release workflow with a clean-consumer smoke job.
- **Default Vue + Java scaffold** — `fengyu plugin create` produces a complete plugin by default: a
  Vue/Vuetify UI (`ui-src/`) backed by a Java JSON-RPC worker (`worker/`), with the Maven Wrapper, a
  build declaration, tests, and a GitHub Packages `settings.xml`. `--ui-only` retains the lightweight
  UI-only template.
- **Real-worker dev simulator** — `fengyu plugin dev` builds the worker JAR (if missing), starts the
  real Java JSON-RPC worker, and forwards the UI's `rpc.invoke` calls over `POST /__rpc`. Java source
  edits trigger a debounced rebuild + worker restart.
- **Declared build lifecycle** — `fengyu.plugin.json` drives an ordered, atomic pipeline
  (prepare → install → test → build → validate staging → package) with the Maven Wrapper (no system
  Maven fallback). `--skip-tests` skips tests only, never type checking or packaging.
- **Shared manifest contract** — a canonical `plugin-spec/manifest.schema.json` + fixtures shared by
  the CLI and the host, including `database` and `network.email` permissions and AI-tool `method` /
  object-schema validation.
- **Offline Python Builder `.fyp`** (`fan.summer.offlinepython`) — doctor, dependency search, project
  init, async wheelhouse builds with streamed logs, and output verification.

### ♻️ Changed
- **Vuetify 3 (Material Design 3) adoption** — full visual-language switch for the web shell and
  plugin micro-frontends, from the legacy `--sk-*` IntelliJ-token system to MD3. Theme driven by
  Vuetify's global singleton from `useThemeStore`; plugins share the host's Vuetify instance via
  `PluginContext.vuetify`.
- **Stateful plugin workflows** — `@infinia/plugin-ui` provides a controlled, persistent-ready step
  wizard with explicit states, async validation, branching, invalidation, and snapshots; the official
  Excel plugin adopts it with reload re-analysis and configuration replay, worker-faithful mode
  validation, safe output reselection, and explicit completion/download.
- **Refined plugin-ui surface** — `@infinia/plugin-ui` gains a theme-driven polish layer so plugins
  using `FyPluginShell` share one calm, low-elevation design language (hairline borders, soft primary
  active chips, a brand marker, de-uppercased buttons, denser fields/tables). Every color resolves
  through a Vuetify theme variable; the email green palette is explicitly excluded.
- **Node.js 24.18.0 baseline** — documentation, `plugin-cli` engine metadata, and every GitHub Actions
  workflow now use the same exact Node.js version, protected by a repository contract test.
- **Official plugins built by the CLI** — Markdown, Excel, and Email are packaged by
  `fengyu plugin build` (a CI matrix); the legacy shell packager and centralized source manifests are
  removed.
- **Offline-first install** — `fengyu plugin install` validates the archive (limits, paths, manifest)
  before any network access; unsafe or invalid packages are rejected with zero fetch calls.
- **Strict SDK RPC contracts** — the worker surfaces canonical JSON-RPC errors (`-32700` parse,
  `-32600` invalid request, `-32601` unknown method, `-32000` handler failure); the TypeScript client
  removes abort listeners on every settled path.
- **HeadlessLauncher** now selects `SetupApplication` (SETUP) vs `AiApplication` (APP, with
  `fengyu.mode=app`) based on `datasource.properties` presence; the desktop host restarts the sidecar
  on `SETUP_DONE` (exit 0) to enter APP mode.
- `AiConfigService` / `AiConfigServiceHeadless` / `EmailUtil` converted from static utilities to
  Spring beans scoped by `SecurityContext.currentUserId()`. Setup-wizard endpoints (`/api/setup/*`)
  bypass token auth (`TokenAuthFilter`).
- Email batch sending creates one message per parsed attachment tag; all matching contacts share the
  To/CC fields, To takes precedence over CC, and failed-item retry was removed.

### 🗑️ Removed
- `DatabaseInit`, all MyBatis mapper interfaces (12), `mybatis-config.xml`, all mapper XML (12), and
  the MyBatis dependency.
- All JavaFX code and dependencies (see Breaking Changes above).

### 🐛 Fixed
- **Headless fat-jar boot**: aligned `logback-classic`/`logback-core` versions (a split pair crashed
  on `JaninoEventEvaluatorBase` at first logger init); added shade `AppendingTransformer` for
  `AutoConfiguration.imports` (Spring Boot 4 splits web/Tomcat autoconfig across module jars — without
  merging, embedded Tomcat silently never started); emit `-parameters` so Spring MVC resolves
  `@PathVariable`/`@RequestParam` names.
- `VirtualUserInitializer` native INSERT now runs inside `@Transactional` (was throwing
  `TransactionRequiredException`).
- Atomic `.fyp` packaging: a failure at any stage leaves no `.fyp`, no `.tmp-*`, and no staging dir.
- Offline Python Builder now opens a writable project workspace, passes complete `FileRef` objects
  through the host bridge, reports translated job states, stops failed polling, and performs real
  build/deploy cancellation instead of changing UI state only.
- Email archive timestamps on SQLite (including upgrade migration), literal wildcard search,
  account/folder path isolation, UTF-8 filename limits, and temporary-file cleanup.

---

## [3.2.0] — IDEA 2025 New UI Redesign

**v3.2.0** — 2026-06-30

This release re-skins the app from glassmorphism-dark to the JetBrains **IDEA 2025 New UI** look: a flat, token-based theme with switchable **dark / light** themes, a collapsible sidebar, and native OS window chrome. Theming is driven by JavaFX looked-up color tokens (`-sk-*`) declared per theme on the scene root, so a theme switch is just a root class swap — no stylesheet reload.

### ⚠️ Breaking Changes

- **`.glass-*` CSS utility classes renamed to `.sk-*`** (in `fengyu-common.css`). External plugins that call `getStyleClass().add("glass-...")` or reference `.glass-*` selectors must update. The full mapping:

  | old | new |
  |---|---|
  | `glass-dialog` | `sk-dialog` |
  | `glass-field` / `glass-field-label` | `sk-field` / `sk-field-label` |
  | `glass-tab-pane` | `sk-tab-pane` |
  | `glass-combo` | `sk-combo` |
  | `glass-table` | `sk-table` |
  | `glass-checkbox` | `sk-checkbox` |
  | `glass-btn-primary` / `glass-btn-secondary` | `sk-btn-primary` / `sk-btn-secondary` |
  | `glass-notif-*` | `sk-notif-*` |

  > The external plugin repo ([`MuskStark/FengYu-Plugin`](https://github.com/MuskStark/FengYu-Plugin)) is updated separately; flag this rename when migrating third-party plugins.

### 🎨 Theme (strict tokenization)

- **Token set expanded 14 → 19** — added `-sk-shadow`, `-sk-scrim`, `-sk-success-soft`, `-sk-warning-soft`, `-sk-danger-soft` (each under both `.theme-dark` and `.theme-light`). Custom themes/stylesheets that hardcoded the old 14 must add these 5 or popups/dialogs/cards will have undefined shadows and status soft-fills.
- **Fixed popups rendering as un-themed white** — `GlassNotification` (toast/notify/confirm) loaded the stylesheet but never stamped the theme class on its scene, so every `-sk-*` token was undefined and all popups fell back to JavaFX default white in both themes. Root-cause fix via `Themes.applyTo(scene)`.
- **Removed all hardcoded colors** from popups, dialogs, `StepWizard`, `ToggleSwitch`, status labels, and CSS drop-shadows. Everything now resolves through `-sk-*` tokens and adapts correctly to dark and light themes. Notably `StepWizard` idle dots and `ToggleSwitch` off-track were invisible on the light theme.

### ✨ New

- **Dark / light theme system** — `fan.summer.api.theme.ThemeService` (API module, no DB dependency) holds the active `Theme.DARK`/`Theme.LIGHT`, stamps a `theme-dark`/`theme-light` class on every registered scene root, and fires `onChange` listeners. Switchable from the sidebar footer (☀/☾) and the Settings page; persisted in the `theme` setting (`dark` default).
- **Looked-up color tokens** (`-sk-bg`, `-sk-bg-elevated`, `-sk-text`, `-sk-accent`, `-sk-border`, …) declared per theme in `fengyu-common.css`; swapping the root class re-resolves every token with no stylesheet reload.
- **Collapsible sidebar** — `«`/`»` toggle between the label view and a 48px icon-strip; collapse state persisted via the `sidebar.collapsed` setting.
- **Native window chrome** — `StageStyle.DECORATED` gives the real OS title bar + close/min/max (macOS traffic lights), replacing the custom transparent window.
- `MarkdownRenderer.render(md, Theme)` / `renderPlain(md, Theme)` overloads (theme-aware dark/light CSS palettes); no-arg forms delegate via `ThemeService.current()`.

### ♻️ Changed

- `fengyu-common.css` rewritten: token definitions under `.theme-dark`/`.theme-light`, every component flattened to IDEA New UI style (neutral-gray selection with a left accent bar, slim 4–8px scrollbars, flat fields/buttons/tables/tabs/dialogs/notifications), all `.glass-*` → `.sk-*`.
- `shell.css` rewritten token-based for the New UI shell (`.app-root`, `.sidebar` + `.collapsed`, capsule `.search-bar`, flat `.tool-card`, `.detail-panel`, `.statusbar`, `.store-*`).
- `Themes.applyTo(scene)` now delegates to `ThemeService.registerScene(scene)` (loads the common stylesheet + stamps the theme class); the shared stylesheet load is factored into `Themes.loadCommonStylesheet(scene)` to keep the delegation non-recursive.
- `FengYuApp` reads the persisted theme on startup and registers the main scene with `ThemeService`.
- `AiChatPlugin` derives its WebView background from the active theme and re-renders the conversation live on theme change.
- Inline `#5b8cf7` accent literals replaced with `#3574F0` / the dark palette across the sidebar and Markdown link CSS.

### 🔥 Removed

- `fan.summer.ui.titlebar.TitleBar` — replaced by native OS window chrome.
- `fan.summer.ui.util.WindowResizeHelper` — native `DECORATED` resize/drag/maximize replaces it; the macOS `isMaximized()`-on-`TRANSPARENT` bug is gone with it.

---

## [3.1.0] — LangChain4j ChatBackend + Plugin-Owned AI Tools

**v3.1.0** — 2026-06-25

This release rebuilds the AI subsystem on LangChain4j and unifies the two cloud providers (OpenAI + Anthropic) into a single `CloudChatBackend` class behind a new `ChatBackend` interface. Plugins can now self-declare their own AI tools. The local tool-calling model is Qwen3-4B (Hermes `<tool_call>` + streamed `<think>` reasoning), running in a hardened out-of-process worker.

### ⚠️ Breaking Changes

- **`AiService` interface removed** — replaced by `ChatBackend`. External plugins calling `AiServiceProvider.getService()` must change the return type from `AiService` to `ChatBackend`. See [`docs/migration-3.1.md`](migration-3.1.md) for the migration guide.
- **`OpenAiService` and `AnthropicService` concrete classes removed** — replaced by a single `CloudChatBackend` class with `openAi(...)` / `anthropic(...)` static factories. One unified class serves both providers.
- **`CloudAiConfigProvider` and standalone `StreamingResponseHandlerBridge` removed** — their logic moved into `CloudChatBackend` (config accessors are public methods on the class; the stream bridge is a private inner class).
- **`AiServiceImpl` renamed to `LocalChatBackend`** — pure rename, no behavior change.
- **`BuiltinAiToolRegistrar` removed** — plugins now self-register AI tools via `FengYuPlugin.aiTools()`; the central registrar and its startup call are gone.

### ✨ New

- **Plugins self-declare AI tools** via `FengYuPlugin.aiTools()` — the registry auto-registers/unregisters them on add/remove (including JAR hot-reload). No central registrar.
- `AiTool` interface declares per-mode visibility (`supportsLocal` / `supportsCloud`) and dual descriptions (`getDescription` / `getLocalDescription`); `AiServiceProvider.getTools()` filters by the active backend mode.
- `AiToolDescriptions` helper centralises cloud-rich / local-concise description templates.
- **Qwen3-4B local tool-calling** — Hermes `<tool_call>` parsing (`ToolCallParser`), `ThinkingStreamSegmenter` (THINK / CONTENT / tool-call stream splitting), `Qwen3Adapter` (Hermes system prompt + `/no_think` toggle), and a collapsible thinking card in the chat UI.
- New `ChatBackend` interface (`fan.summer.api.ai.ChatBackend`).
- New `CloudChatBackend` class with `openAi(...)` / `anthropic(...)` factories.
- `LocalChatBackend` (renamed from `AiServiceImpl`).
- `AiToolCall.of(id, name, arguments)` overload to preserve server-issued tool-call IDs when bridging from LangChain4j.
- Tests: `CloudChatBackendTest` (11) + adapter tests for `ChatMessageMapper` / `AiToolToToolSpecification`; `ThinkingStreamSegmenterTest` (11) + `LocalChatBackendMaxTokensTest` (3).
- Migration guide at [`docs/migration-3.1.md`](migration-3.1.md) (EN + ZH).

### ♻️ Changed

- All 16 builtin AI tools return standardized JSON `{success, summary, ...payload}`; tool descriptions follow a cloud-rich / local-concise dual template.
- `BuiltinToolRegistrar.register()` routes through `PluginRegistry.addPlugins` to auto-register plugin AI tools in one pass.
- **Unified `ChatBackend` interface** in `FengYu-Api` — non-sealed (Java forbids cross-module sealed permits). Two known implementors: `CloudChatBackend`, `LocalChatBackend`. UI consumers use `instanceof` checks; the interface itself is treated as opaque.
- **`CloudChatBackend` unifies OpenAI + Anthropic** in one class (~450 LOC). HTTP/SSE, tool-loop plumbing, and stream bridging are delegated to LangChain4j's streaming models; provider differences isolated to a `buildStreamingModel(...)` switch on an internal `Provider` enum.
- `SynchronousChatHelper` (browser planner) rewritten to use LC4j's synchronous `OpenAiChatModel` directly via `CloudChatBackend` config accessors.
- `AiServiceProvider` exposes `ChatBackend` everywhere (method names unchanged).
- Sampling parameters (temperature / topP / maxTokens) are honoured per-call — settings changes take effect on the next message without restarting the chat.
- Default `maxTokens` raised 512 → 2048 (the Qwen3 thinking-model floor), enforced once at the `chat()` entry so both the native and Java backends benefit.

### 🐛 Fixes

- **Qwen3 silent empty answer** — a thinking model truncated mid-`<think>` produced an empty answer because `stripThink` wiped the unclosed block. The `maxTokens` budget is now floored to `QWEN3_MIN_MAX_TOKENS` (2048) at the unified `chat()` entry, with a diagnostic warning when output survives only as a think block.
- **Qwen3 on the Java backend** leaked raw `<think>` tags into the answer — now routed through `ThinkingStreamSegmenter` (thinking → collapsible card) and stripped from the final answer/history, matching the native path.
- **`AiConfigService.getAiMaxTokens()` default** synced to 2048 (was a stale 512 that disagreed with the settings UI).
- **AI worker IPC** — the child process pins a dedicated `logback-worker.xml` (no `ConsoleAppender`) so worker logs no longer corrupt the line-delimited JSON pipe on stdout; stderr is drained on its own thread into the shared log.
- **AI worker native load** — the child JVM loads the llama.cpp library at startup (`NativeLoader.load()`) so `LlamaContext` construction no longer throws "Native library not loaded".
- **AI worker crash recovery** — `handleChildExit` waits for a real exit code instead of throwing `IllegalThreadStateException` on stdout EOF, so pending callbacks are released and auto-restart runs reliably.
- **Qwen3.5 hybrid-model warning** — filenames matching `qwen3.5` / `qwen35` now warn that the native worker is known to SIGABRT on multi-turn (use Qwen3-4B).
- **Cloud `testConnection()` null-message bug on macOS** — `ConnectException` with a `null` message now falls back to `e.getClass().getSimpleName() + ": " + e`.
- **Anthropic multi-round tool calling** — server-issued `tool_use_id` preserved through the `AiToolCall → LangChain4j → AiToolCall` round-trip (previously caused HTTP 400 on round 2).
- **Multi-turn conversation continuity** — the assistant's final reply is appended to `history` before the service returns.
- **OpenAI tool-round message ordering** — the assistant-with-tools message is appended before `ToolExecutor.executeAndFeed`.
- `pdf_merge.filePaths` parameter type fixed (`"array"` → `"string[]"`); enums declared for `base64.mode`, `hash_calculate.algorithm`, `color_convert.from/to`.
- `ToolExecutor` error output is always JSON `{success:false,error:...}`; `ExcelConfigureTool` success returns `success:true`.
- `testConnection()` `HttpClient` wrapped in try-with-resources; thread-safety hardening on the cloud stream handler.

### 🔥 Removed

- `BuiltinAiToolRegistrar` — superseded by plugin-owned `aiTools()`.
- FunctionGemma adapter and `OfflineNlNormalizer` — replaced by the Qwen3 path.

### ⬆️ Dependencies

- `dev.langchain4j:langchain4j-open-ai:1.2.0`
- `dev.langchain4j:langchain4j-anthropic:1.2.0`
- (1.0.1 was originally pinned but `langchain4j-anthropic` was never published at that version; bumped to the lowest GA where both modules co-exist)

### ⚠️ Known Behavior Changes

- `cancelGeneration()` on cloud backends is best-effort (LangChain4j 1.x does not expose mid-stream cancellation on streaming models); the in-progress flag is still cleared. Local mode is unaffected.
- Mid-stream SSE errors now surface via `callback.onError` on the JavaFX Application Thread.
- The local tool-calling model is Qwen3-4B; the native worker requests full GPU offload automatically on builds that ship a GPU backend.

### 📉 Net Code Change

- Deleted: `AiService` (117 LOC), `OpenAiService` (244 LOC), `AnthropicService` (283 LOC), `CloudAiConfigProvider` (22 LOC), `StreamingResponseHandlerBridge` (120 LOC), `StreamingResponseHandlerBridgeTest` (214 LOC), `BuiltinAiToolRegistrar`, FunctionGemma adapter + `OfflineNlNormalizer` ≈ **1000+ LOC removed**.
- Added: `ChatBackend` (86 LOC), `CloudChatBackend` (450 LOC), the Qwen3 toolchain (`ThinkingStreamSegmenter`, `Qwen3Adapter`, `ToolCallParser`), worker hardening, tests, migration guides ≈ **1100+ LOC added**.
- Net: roughly even on LOC, but cloud code is one unified class and local AI has a dedicated tool-calling model + isolated worker.

---

## [3.0.1] — FunctionGemma Offline Adaptation

**v3.0.1** — 2026-06-21

### ✨ New Features

- **FunctionGemma Multi-Round Tool Loop**: Host-driven `analyze → configure → execute` loop for the FunctionGemma-270m-it local model; tool-call tokens are suppressed during call rounds and only the final response is forwarded to the UI
- **Offline CN→EN Keyword Normalizer**: `OfflineNlNormalizer` rewrites Chinese tool-name keywords to English before local-model parsing, no network required (resource-backed `nl-normalizer.properties`)
- **Enum-Schema Tool Parameters**: `AiToolParam` gains an `enumValues` field; tool declarations now emit `enum:[...]` constraints to FunctionGemma, OpenAI, and Anthropic backends — materially improves small-model parameter reliability
- Enriched Excel AI tool descriptions and added enum constraints on `mode`/`action` parameters

### 🐛 Fixes

- Harden `FunctionGemmaAdapter` parser: 🪙 (U+1FA99) string delimiter correctly handles values containing commas, braces, and multiple tool calls in a single response
- Release `GGUFModel` mmap on unload via best-effort `unmap`
- Harden `GGUFReader` against malformed or truncated model files
- Serialise `PluginLoader` JAR load/unload on a single-thread scheduler
- Complete `LlamaRunner` generation cleanly when cancelled during prefill
- Drive `TokenBatcher` flushes off the FX thread
- Let the native AI worker exit gracefully before force-killing it
- Close target POI `Workbook` in `ExcelUtil` even when copy/write throws
- Low-priority stability cleanup (MDI font log, daemon UI threads)

---

## [3.0.0] — JavaFX Migration

**v3.0.0** — 2026-06-12

- Update app icons for v3.0.0 release
- Resolve static analysis warnings across codebase (Qodana)

**v3.0.0-rc.3** — 2026-06-10

- **Slash Commands**: Type `/` in AI chat to list available tools, get help on a specific tool, or invoke a tool directly without model inference — supports both direct execution and guided model parameter extraction
- **Plugin Resource Isolation**: Child-first `ClassLoader` for external plugins ensures plugin resources are resolved from the plugin JAR before the host; `PluginContext` provides TCCL switching on every plugin lifecycle call and event dispatch
- **Plugin Store Redesign**: Searchable, filterable card grid for the online plugin store with install state indicators and version comparison
- **AI Configuration Service**: Extracted `AiConfigService` centralizes AI configuration access, decoupling it from UI settings code
- **Email Archive**: New `email_archive` table, entity, and mapper for email archive storage
- Fix sidebar icons not displaying on Windows — switched from JavaFX `Font` icons to MDI webfont
- Fix email settings save always failing; now shows missing required field names
- Fix Excel complex split Phase 3 corrupting pre-existing output files — only merge into files created during the split operation
- Fix POI `NullPointerException` during cross-workbook cell style cloning when data format string is null
- Harden Excel Splitter progress callback with null guard
- Extract `StorePlugin` and `StorePluginLogic` from `OnlineStorePane` with unit tests
- Add GPLv3 license file to the repository
- Add JUnit 5 test dependency to `FengYu` module

---

**v3.0.0-rc.2** — 2026-06-05

- **Tool Favorites**: Bookmark tools with a star toggle on tool cards and the detail panel; favorites persist across restarts via H2 database and are filterable from the sidebar "Favorites" category
- **Lazy AI Backend**: Local AI backend (native/Java) initialization is deferred until the AI tool is first opened, improving startup performance; Java/Native inference engine toggle in AI settings
- **Plugin Uninstall**: Uninstall external plugins from the detail panel with confirmation dialog; closes ClassLoader, removes JAR file, and cleans up from registry
- **Install Toast Notifications**: Success toast notification when a plugin is installed from the online store or local JAR
- **Token Batching**: AI token output is batched at 50ms intervals to reduce FX thread flooding during high-speed generation
- **Crash Rate Limiting**: Native worker auto-restart respects a time window (3 crashes within 5 min) to prevent restart storms
- **Settings Cache**: App settings are cached in memory with debounced DB writes (300ms) to reduce database load during rapid UI interaction
- Fix native library loading on hardened Linux distros (UOS/Deepin/Kylin) where `SecurityException` is thrown for unsigned `.so` files
- Fix email batch sending mutating shared recipient lists across iterations
- Fix online store plugin catalog parsing — replaced hand-rolled string slicing with Gson-based `JsonHelper`
- Fix `WindowResizeHelper` double-attachment causing duplicate event filters
- Thread-safety hardening across `PluginLoader`, `PluginRegistry`, and `MainWindow` (`ConcurrentHashMap`, `volatile`, `synchronizedSet`)
- Stagger limit for tool card entry animations (max 30) to avoid creating hundreds of `PauseTransition` instances
- Fix plugin JAR deletion on Windows — retry with `System.gc()` hint, fall back to `deleteOnExit()` if file is still locked
- Fix `onUnload()` lifecycle callback not fired when unloading plugin JARs
- Fix cached plugin view not cleared when uninstalling an inactive plugin, preventing GC of plugin classes
- Fix English locale (`Locale.ENGLISH`) returning Chinese strings on Chinese-locale systems — `ResourceBundle` no longer falls back to JVM default locale
- Fix Windows no-JRE release zip redundantly including the fat JAR alongside the Launch4j exe (which already embeds it)

---

**v3.0.0-rc.1** — 2026-06-04

- **Browser Automation**: AI-callable `browser_automate` tool that automates web browsers via natural language; uses Playwright with the system's installed Chrome/Edge/Chromium (no separate browser download); observe-think-act loop with page DOM snapshots, CSS selector targeting, and a planner LLM
- **Resizable Window**: Edge and corner drag resize for the undecorated `StageStyle.TRANSPARENT` window via `WindowResizeHelper`; uses screen coordinates for macOS compatibility
- **Responsive Layout**: Dynamic `FlowPane` wrap length bound to viewport width; `windowPane` and `ContentArea` properly fill parent with `setMaxWidth/Height(Double.MAX_VALUE)`
- **Pure Java PDF-to-DOCX**: `PdfBoxToDocxConverter` using PDFBox for extraction and Apache POI for DOCX generation — no external Office installation required; three-tier page strategy (text → extracted images → full-page render fallback)
- **Native Backend Health Tracking**: `NativeLoader.FailureReason` enum for structured failure diagnostics; degraded-mode banner in AI chat when native acceleration is unavailable
- Fix macOS window resize not working due to unreliable `stage.isMaximized()` with `StageStyle.TRANSPARENT`
- Fix tool grid layout not responsive to window width changes
- Fix Playwright runtime attempting to download browser driver unnecessarily
- Fix AI browser planner recursively invoking `browser_automate` tool via tool injection loop

---

**v3.0.0-beta.2** — 2026-05-26
