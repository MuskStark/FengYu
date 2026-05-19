# Features

SwissKit provides a variety of built-in tools organized by category. You can also extend functionality with external plugins.

## Categories

The sidebar organizes tools into six categories:

| Category | Icon | Purpose |
|----------|------|---------|
| ALL | — | Show all tools |
| TEXT | `T` | Text processing |
| IMAGE | `I` | Image tools |
| DEV | `<>` | Developer utilities |
| NET | `@` | Network / communication |
| OTHER | `···` | Everything else |

## Built-in Tools

### Developer Tools (`DEV`)

#### JSON Formatter
Format, compress, and validate JSON data. Paste raw JSON and get pretty-printed output instantly.

#### Base64
Encode text to Base64 or decode Base64 strings back to plain text.

#### Hash Calculator
Compute MD5, SHA-1, SHA-256, and SHA-512 checksums for any text input.

---

### Text Tools (`TEXT`)

#### Markdown Editor
Real-time Markdown editor with side-by-side preview. Write in Markdown on the left, see rendered HTML on the right.

---

### Image Tools (`IMAGE`)

#### Color Converter
Convert between HEX, RGB, and HSL color formats with a live color preview swatch.

---

### Network Tools (`NET`)

#### Email
Send emails with SMTP configuration. Supports:
- Single and mass email sending by recipient tags
- Attachment routing by tag-based folder
- Address book with tag management
- Sent mail history log

---

### Other Tools (`OTHER`)

#### Excel Splitter
Split Excel files using a 4-step wizard:

1. **Select File** — Choose the source `.xlsx` or `.xls` file
2. **Analysis** — Auto-detects all sheets and their headers
3. **Split Mode** — Choose from three modes:
   - **Split by Sheet** — One output file per sheet
   - **Split by Column** — Group rows by unique column values
   - **Complex Split** — Multi-config split from database settings
4. **Output** — Choose output directory and start processing

Progress is shown in real-time with percentage updates.

---

## System Features

### Plugin Store
Browse and install plugins from the online catalog, or load a local plugin JAR. Installed plugins are hot-reloaded automatically.

### Settings
Configure SMTP email server settings, manage the email address book and tags, and view installed plugins.

---

## Plugin System

SwissKit discovers plugins in two ways:

- **Built-in tools** are registered directly by `BuiltinToolRegistrar` at startup.
- **External plugins** implement `SwissKitJPlugin` and declare it in `META-INF/services/`. Drop the JAR into the `plugins/` directory and it is loaded automatically with hot-reload support.

See the [Architecture](architecture.md) page for how plugins are loaded, and [Development](development.md) for building your own.

---

**Ready to build one?** Head to the [Development Guide](development.md).
