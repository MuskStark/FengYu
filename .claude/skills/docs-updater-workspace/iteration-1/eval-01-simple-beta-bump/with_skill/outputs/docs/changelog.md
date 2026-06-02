# Changelog

## 3.0.0-beta.2 (2026-05-29)

### New Features

- **AI Chat**: OpenAI and Anthropic API backend support with auto-load
- **AI Chat**: Markdown rendering in responses via WebView, renamed to SwissKitJClaw
- **AI Chat**: Built-in AI tools — Base64, Hash, JSON Format, Color Convert
- **AI Chat**: ToolExecutor and ToolSchemaBuilder for easy tool registration
- **AI Chat**: Excel integration — analyze, configure, execute, query, and cancel tools for AI-powered split operations
- **AI Chat**: excel_complex_config tool and reasoning_content handling
- **AI Chat**: FunctionGemma native tool calling protocol support with adapter, stop sequences, and AiServiceImpl integration
- **AI Chat**: Accept *.ggufz files in model file chooser
- **Email Archive**: Built-in email archive tool with IMAP support
- **Plugin System**: Background execution with view caching and ToolCard indicator
- **Hash Calculator**: Calculate hash digests (MD5, SHA-1, SHA-256) for text input

### Fixes

- Fix ToolCard background indicator not showing and preview i18n not working
- Fix plugin i18n bundles returning host translations due to ClassLoader parent delegation
- Fix missing hasRunningTasks on ExcelSplitterPlugin
- Fix stale AiServiceImpl init in MainWindow overwriting startup backend
- Fix WebView white background, use dark theme for AI message bubbles
- Fix auto-resize WebView height to match content
- Fix rounded corners on AI message bubbles, brighten text
- Fix array types handling in JSON Schema for tool parameters
- Fix proper JSON Schema for tool parameters and improved tool-calling prompt
- Fix Base64 encoding not handling UTF-8 characters

### Changes

- Consolidate AI tool infrastructure: Gson-based JSON, shared registry, remove JsonBuilder/JsonParser
- Expose shared SplitConfig for AI tool access

## 3.0.0-beta.1 (2026-05-20)

- Initial JavaFX migration release
