# Phase 1 AI Strangler — Execution Status

**Last updated:** 2026-07-07
**Branch:** `4.0.0-ZhiFlow`
**Plan:** [`2026-07-06-phase1-ai-strangler-spring-ai.md`](./2026-07-06-phase1-ai-strangler-spring-ai.md)
**State:** CUTOVER COMPLETE. Cloud backend reimplemented, app/settings rewired, legacy stack deleted. Full reactor build + 67 tests green. Only Task 14 (docs/CHANGELOG) + live smoke-test with a running Ollama/cloud key remain.

---

## Decisions taken during execution

1. **Plan was stale** (pre `SwissKit→ZhiFlow` rename). Mechanically retargeted paths/names; added a drift note + GA-API-corrections note to the plan. Build is a **parent/reactor POM** now (not standalone), and there is **no system Maven** — builds run via IDEA's bundled Maven (`/Users/phoebej/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn`) with `JAVA_HOME=azul-21.0.11`.
2. **Spring AI 2.0.0 GA API differs from the plan's cheat-sheet** (verified against resolved `~/.m2` jars):
   - OpenAI/Anthropic use vendor SDK clients (`com.openai.client.OpenAIClient` / `com.anthropic.client.AnthropicClient`), **not** `OpenAiApi`/`AnthropicApi`. Vendor `*-client-okhttp` artifacts are **not** on the classpath yet.
   - Builders use `.options(...)` not `.defaultOptions(...)`.
   - `AssistantMessage` + `ToolResponseMessage` have **no public multi-arg ctors** → use `.builder()`.
   - `ToolResponseMessage.ToolResponse` accessor is `responseData()` not `responseMessage()`.
3. **"Ollama-local first, cloud later"** (user decision): build the local path now, defer the cloud beans/backend.
4. **Task 8 spike:** Ollama not available on host → thinking surfacing dropped for Phase 1 (documented fallback).
5. **PAUSE before cutover** (user decision "记录执行状态，等待后续实现"): do not delete legacy code or rewire the app until the cloud backend is reimplemented, so cloud modes are not silently broken.

---

## Done (committed)

| Plan Task | Commit subject | Notes |
|---|---|---|
| Plan fix | `docs(plan): update … SwissKit->ZhiFlow rename` | paths/names retargeted |
| Task 1 | `chore(ai): swap LangChain4j deps for Spring Boot 4.1 + Spring AI 2.0 BOMs` | + `fix(ai): move Spring BOM imports into ZhiFlow module POM` |
| Task 2 | `feat(ai): add embedded Spring Boot context bootstrap` | `AiApplication`, `AiSpringContext` (+ `getBean(String,Class)` overload) |
| Task 3 | `feat(ai): add H2-backed AiConfigProperties snapshot + Ollama settings` | `AiConfigService` Ollama getters + `AiConfigProperties` |
| Plan fix | `docs(plan): correct Spring AI 2.0.0 GA API cheat-sheet` | |
| Task 5 | `feat(ai): add MessageMapper` | GA builder API |
| Task 6 | `feat(ai): add ToolSchemaJson` | |
| Task 7 | `feat(ai): add AiToolCallback` | keeps `ai.tools.AiToolDescriptions` import (relocated in Task 11) |
| Task 8 | `docs(plan): record Task 8 spike outcome` | fallback, no code |
| Task 9 | `feat(ai): add OllamaLocalBackend` | manual tool loop, `probeReachable` |
| Task 4 (partial) | `feat(ai): add ChatModel @Bean config (Ollama-only for Phase 1)` | **cloud beans deferred** |

All new files verified via IDEA per-file diagnostics (clean). **The module does not compile as a whole yet** — see below.

---

## Why the module is not green yet (expected)

Removing LC4j (Task 1) broke the legacy files that still import `dev.langchain4j`:
- `ai/service/CloudChatBackend.java` (broken; referenced by `ZhiFlowApp`, `ZhiFlowSettingUi`, `buildintool/browser/SynchronousChatHelper.java`)
- `ai/service/LocalChatBackend.java` (referenced by `ZhiFlowSettingUi`)
- `ai/adapter/AiToolToToolSpecification.java`, `ai/adapter/ChatMessageMapper.java` (broken adapters)
- `buildintool/browser/SynchronousChatHelper.java` (imports LC4j directly)

Maven compiles all of `src/main/java` before any test, so the per-task `mvn test -Dtest=X` verifications in the plan cannot run until the broken legacy code is removed. New-file correctness was therefore verified via IDEA diagnostics; the JUnit suites (`MessageMapperTest`, `ToolSchemaJsonTest`, `AiToolCallbackTest`, `OllamaLocalBackendConnectionTest`) will run at the first green point.

---

## Cutover completed (2026-07-07)

| Section / Task | Commit subject | Notes |
|---|---|---|
| A. Cloud follow-up | `feat(ai): add cloud ChatModel beans + SpringAiCloudBackend (Spring AI 2.0 GA)` | See "How the cloud client is built" below |
| Task 11 | `refactor(ai): relocate ToolExecutor/SlashCommandHandler/AiToolDescriptions out of tools/` | ToolExecutor+SlashCommandHandler → `ai/`, AiToolDescriptions → `ai/adapter/`. SynchronousChatHelper rewritten onto the `openAiChatModel` bean. |
| Task 12 | `feat(ai): rewire app + settings UI to Spring AI cloud / Ollama local backends` | AiSpringContext bootstrapped after DB init / closed on stop; cloud + local call sites swapped |
| Task 13 | `refactor(ai): delete legacy LC4j cloud + GGUF/JNI/worker local stack` | old backends, LC4j adapters, tools parsers (kept Builtin\*Tool), inference/model/tensor/nativejni, cpp, native lib, stale tests. **logback pinned to 1.5.34** (BOM alignment — split classic/core → NoClassDefFound JaninoEventEvaluatorBase). |

**How the cloud client is built (no vendor okhttp artifact needed):** Spring AI 2.0.0 GA ships `OpenAiSetup.setupSyncClient(...)` and `AnthropicSetup.setupSyncClient(...)`, which build the vendor `OpenAIClient`/`AnthropicClient` over Spring AI's own `SpringAi*HttpClient` (okhttp3 is already a compile-scope transitive dep of `spring-ai-openai`). So the plan's Section A step 1 (add `com.openai:openai-java-client-okhttp` / `com.anthropic:anthropic-java-client-okhttp`) turned out **unnecessary** — `ChatModelConfig` uses the setup helpers instead. All builder signatures verified via `javap` against the resolved 2.0.0 jars + the official 2.0.0 docs.

**Verifications done at the green point:** full reactor build SUCCESS via IDEA JPS; `mvn test -f ZhiFlow` = 67 tests, 0 failures (incl. the 5 new AI suites + the 2 relocated-helper suites). Zero `dev.langchain4j` / deleted-package references remain in source (only javadoc `{@code}` mentions).

## Still owed
- **Task 14** — CHANGELOG + README (Ollama runtime) + migration doc. Also fix CLAUDE.md "standalone POMs" → reactor.
- **Live smoke test** — needs a running app + (local) `ollama serve` + `ollama pull qwen3:4b`, or a real cloud API key. Streaming/tool-loop paths are unit-tested with stub ChatModels but not exercised end-to-end.

## (historical) Original resume plan

### A. Cloud follow-up (unblocks everything else)
1. Add vendor client deps to `ZhiFlow/pom.xml`: `com.openai:openai-java-client-okhttp` and `com.anthropic:anthropic-java-client-okhttp` (versions via the spring-ai BOM if managed, else pin to the `-core` versions already resolved: openai `4.39.1`, anthropic `2.40.1`).
2. Verify the client builder API (`OpenAIOkHttpClient.builder().baseUrl(...).apiKey(...).build()` / `AnthropicOkHttpClient.builder()...`) by `javap`/sources before writing code.
3. Re-add `openAiChatModel` / `anthropicChatModel` beans to `ChatModelConfig` using `.openAiClient(...)` / `.anthropicClient(...)` + `.options(...)`.
4. Write `SpringAiCloudBackend` (plan Task 10) — the tool-loop shape is fine; only the bean construction + `testConnection()` need the corrected API. Its `ScriptedChatModel` test only implements `ChatModel` (verify the minimal method set against GA: `call(Prompt)` + inherited default `stream(Prompt)`).

### B. Then the plan's remaining tasks (in order), as one green sweep
- **Task 11** — relocate `ToolExecutor` → `ai/`, `AiToolDescriptions` → `ai/adapter/`, `SlashCommandHandler` → `ai/`; update importers (`AiChatPlugin`, `SynchronousChatHelper`, and the `AiToolCallback` import of `ai.tools.AiToolDescriptions`).
- **Task 12** — rewire `ZhiFlowApp` (bootstrap/close `AiSpringContext`; `initializeAiBackend` → new backends) + `ZhiFlowSettingUi` mode-switch call sites. `SynchronousChatHelper` also constructs `CloudChatBackend` — must be rewired here too (plan under-specifies this; it's a 3rd cloud call site).
- **Task 13** — delete legacy: old backends, LC4j adapters, `tools/*` parsers (keep relocated ones), `inference/model/tensor/nativejni`, `src/main/cpp`, `src/main/resources/native`, stale tests. Then full reactor build + `test` must be green; confirm zero `dev.langchain4j` / deleted-package refs.
- **Task 14** — CHANGELOG + README (Ollama runtime) + migration doc.

### C. Verifications owed at the green point
- Run the 4 new JUnit suites (they compile now, just can't run until the module compiles).
- Manual smoke test (plan Task 12 steps 5–6) needs a running app + (for local) `ollama serve` + `ollama pull qwen3:4b`.

---

## Known gaps / cautions for the resumer
- `SynchronousChatHelper` is a **third cloud-backend call site** not enumerated in the plan's Task 12 — grep `CloudChatBackend` before assuming two.
- `ZhiFlowSettingUi.ensureLocalBackend()` lazy path should build `new OllamaLocalBackend()` + `loadModel(null)`.
- CLAUDE.md still says "standalone POMs" — that's stale; it's a reactor now. Consider fixing CLAUDE.md in Task 14.
