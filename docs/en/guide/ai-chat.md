---
title: AI Chat
description: Use the multi-backend AI chat with streaming (SSE) replies, thinking cards, tool-call display, and saved conversations.
lang: en
---

# AI Chat

AI Chat is the conversational surface in Infinia. Pick a backend, send a prompt, and read the reply token-by-token as a server-sent events (SSE) stream. While the model is working it can stream its own thinking and call plugin tools inline; finished conversations are persisted so you can reopen them later.

## Backends

Chat runs against four backends, selected in [Configuration](/en/guide/configuration) under AI config:

| Mode | Backend | Notes |
| --- | --- | --- |
| `local` | Ollama | Talks to an **external `ollama serve`** process over its local HTTP API. The backend does not load a GGUF in-process. |
| `openai` | OpenAI | Standard OpenAI API. |
| `anthropic` | Anthropic | Anthropic Messages API. |
| `deepseek` | DeepSeek | DeepSeek is **OpenAI-compatible** — driven through the OpenAI-style adapter. |

The active mode is whatever `PUT /api/ai/config` last persisted; it is hot-swapped at runtime via `BackendReactivator.reactivate()` so a mode switch takes effect without a restart.

## Request flow

A chat turn is a two-step request: start the run, then open the SSE stream.

```text
POST /api/ai/chat
  Content-Type: application/json
  X-FengYu-Token: <token>
  { "messages": [ { "role": "user", "content": "Summarize this workbook" } ],
    "permissionMode": "ask-for-approval" }

  ◄── 200 { "streamId": "<uuid>", "activeFileRefs": [...] }

GET /api/ai/stream?streamId=<uuid>
  X-FengYu-Token: <token>
  Accept: text/event-stream

  ◄── SSE stream (see below)
```

The first frame on the stream is a `:connected` comment heartbeat — it confirms the stream is open before any events arrive.

### Files and directories

You can select a file/directory or type an existing absolute path directly in the latest user message. The host creates a separate opaque FileRef for every enabled backend plugin that declares `files.read`; picker-selected directories also receive `read-write` access for plugins that declare `files.write`. Typed paths remain read-only. The response's `activeFileRefs` keeps newly discovered path grants available to follow-up turns without exposing the absolute path to plugin iframes.

::: warning
The SSE endpoint is `GET /api/ai/stream?streamId=...`. There is **no** `?token=` query parameter. Authenticate the stream request with the `X-FengYu-Token` header, the same as every other endpoint.
:::

## SSE events

Every event is an SSE frame named after its type. See [SSE Events](/en/reference/sse-events) for the full taxonomy.

| Event | Data | Meaning |
| --- | --- | --- |
| `token` | `{text}` | A chunk of the assistant's reply. Concatenate to rebuild the message. |
| `thinking` | `{text}` | A chunk of the model's chain-of-thought. |
| `tool` | `call`: `{phase:"call", name, arguments}` | The model decided to call a tool (plugin or built-in). |
| `tool` | `result`: `{phase:"result", id, success, output}` | The tool returned. `success:false` carries an error in `output`. |
| `done` | `{text, tokens, tps}` | The turn is complete. `text` is the full reply; `tps` is tokens/sec. |
| `error` | `{message}` | The run failed. The stream ends after this frame. |

A representative stream:

```text
: connected

event: token
data: {"text":"Let me check "}

event: tool
data: {"phase":"call","name":"excel_analyze","arguments":"{\"filePath\":...}"}

event: tool
data: {"phase":"result","id":"...","success":true,"output":"..."}

event: token
data: {"text":"the workbook has 3 sheets."}

event: done
data: {"text":"Let me check the workbook has 3 sheets.","tokens":42,"tps":18.6}
```

### Rendering

- **Thinking** renders as collapsed cards — one card per thinking span, expandable on click so it stays out of the way unless you want it.
- **Tool calls** render as compact activity rows such as `Read FengYu Plugin Dev skill` and update in place as the call runs or completes.
- **Approvals** render inside the composer, directly above the text area. The transcript keeps the compact activity row instead of inserting a large approval card between messages.

### Permission modes

The composer offers three per-turn profiles:

| Mode | Behaviour |
| --- | --- |
| **Ask for approval** | Reads run directly; command execution, document/file changes, and external actions ask before they run. |
| **Approve for me** | Safe sandboxed commands and declared reads/writes run automatically; commands detected as risky and external/network actions still ask. |
| **Full access** | Runs without tool approval and executes commands without the native file/network sandbox. Sensitive inherited environment variables are still removed. |

Plugin tools declare their effect in the manifest, so the same gate covers both built-in and out-of-process plugin actions. A missing effect is handled conservatively as an external action.

## Conversations

Conversations are stored on the backend. All endpoints require the `X-FengYu-Token` header.

| Method + path | Body / query | Returns |
| --- | --- | --- |
| `GET /api/ai/conversations` | — | Conversation summaries, **newest first**. |
| `GET /api/ai/conversations/{id}` | — | A single conversation (title + messages). |
| `POST /api/ai/conversations` | `{title, messages}` | The created conversation with its `id`. |
| `PUT /api/ai/conversations/{id}` | `{title, messages}` | Replaces both title and messages wholesale. |
| `DELETE /api/ai/conversations/{id}` | — | Removes the conversation. |

`PUT` is a full replace — send the complete `messages` array you want stored, not a delta.

## Next steps

- [AI Agent](/en/guide/ai-agent) — the plan-and-execute agent built on the same backend.
- [Configuration](/en/guide/configuration) — set the active mode and API keys.
- [AI Tools](/en/plugins/ai-tools) — how plugin tools become callable from chat.
