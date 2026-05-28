# Code Reviewer Subagent

Specialized agent for reviewing code changes in this JavaFX/SwissKitJ codebase.

## Usage
Spawn via `Agent` tool with `subagent_type: code-reviewer`

## Review Focus Areas

### JavaFX Layout Bugs
This codebase has 5 documented JavaFX layout pitfalls (see CLAUDE.md section "JavaFX Layout Pitfalls"). Pay special attention to:
- `Control.maxWidth` defaults — use `setMaxWidth(Double.MAX_VALUE)` on ScrollPane/Button inside StackPane
- Never set `prefWidth = Double.MAX_VALUE` on any control
- No circular `maxWidthProperty` bindings
- CSS stylesheet rules override Java property setters
- Toggle both `setVisible` and `setManaged` when swapping StackPane children

### AI Tool Calling Refactors
Recent commits refactored AI services to use shared `Gson`/`JsonHelper`, `ToolExecutor`, and `ToolSchemaBuilder`. Ensure:
- JSON parsing uses `fan.summer.api.json.JsonHelper`, not old `JsonBuilder`/`JsonParser`
- Tool schemas are generated via `ToolSchemaBuilder`
- Tool execution goes through `ToolExecutor`

### Maven Multi-Module
- `SwissKitJ-Api` must be built/installed before `SwissKit` (dependency order)
- Each module has standalone POM — no parent dependency

### Glassmorphism Theming
- Three CSS layers: `swisskit-common.css` (shared), `shell.css` (app-shell), `builtin.css` (built-in tools)
- Plugin `createView()` results inherit all three stylesheets automatically