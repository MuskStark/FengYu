# Features

SwissKitJ provides 8 built-in tools organized by category, plus support for external plugins.

## Categories

| Category | Purpose |
|----------|---------|
| ALL | Show all tools |
| TEXT | Text processing |
| IMAGE | Image tools |
| DEV | Developer utilities |
| NET | Network / communication |
| OTHER | Miscellaneous |

## Built-in Tools

### AI Chat (`DEV`)

Chat with AI models using multiple backends. Supports:

- **Local GGUF models** — Load and run models locally with JNI native acceleration
- **OpenAI-compatible APIs** — Connect to any OpenAI Chat Completions endpoint
- **Anthropic Claude APIs** — Connect to any Anthropic Messages API endpoint
- Streaming inference with real-time token generation
- Tool calling support with custom tool definitions
- Chat session management with history
- Auto-activates the configured backend on startup

### JSON Formatter (`DEV`)

Format, compress, and validate JSON data. Paste raw JSON and get pretty-printed output.

### Base64 (`DEV`)

Encode text to Base64 or decode Base64 strings back to plain text.

### Hash Calculator (`DEV`)

Compute MD5, SHA-1, SHA-256, and SHA-512 checksums for text input.

### Markdown Editor (`TEXT`)

Real-time Markdown editor with side-by-side preview.

### Color Converter (`IMAGE`)

Convert between HEX, RGB, and HSL color formats with a live color preview swatch.

### Email (`NET`)

Send emails with SMTP configuration:

- Single and mass email sending by recipient tags
- Rich text editor with formatting toolbar
- Attachment routing by tag-based folder
- Address book with tag management
- Sent mail history log

### Excel Splitter (`OTHER`)

Split Excel files using a multi-step wizard:

1. **Select File** — Choose the source `.xlsx` or `.xls` file
2. **Analysis** — Auto-detects all sheets and their headers
3. **Split Mode** — Split by Sheet, by Column, or Complex Split (multi-config)
4. **Output** — Choose output directory and start processing

## System Features

### Plugin Store

Browse and install plugins from the online catalog, or load a local plugin JAR. Installed plugins are hot-reloaded automatically.

### Settings

Configure SMTP email server settings, manage the email address book and tags, and view installed plugins.

## Plugin System

SwissKitJ discovers plugins in two ways:

- **Built-in tools** — registered directly by `BuiltinToolRegistrar` at startup
- **External plugins** — implement `SwissKitJPlugin`, declare in `META-INF/services/`, drop JAR into `plugins/` directory with hot-reload

See [Architecture](architecture.md) for how plugins are loaded, and [Development Guide](development.md) for building your own.
