# FengYu Documentation

![FengYu](https://img.shields.io/badge/FengYu-Desktop%20Toolbox-blue) ![Java](https://img.shields.io/badge/Java-21-orange) ![License](https://img.shields.io/badge/License-MIT-green)

**FengYu** is a modular desktop toolbox built with JavaFX 21, providing a clean, extensible platform for productivity tools.

## Quick Links

- [Getting Started](getting-started.md) — Installation and setup
- [Features](features.md) — Built-in tools and capabilities
- [Architecture](architecture.md) — Plugin system and design
- [Development Guide](development.md) — Build plugins and contribute
- [Changelog](changelog.md) — Version history

## What is FengYu?

A modular desktop toolbox that allows you to:

- Chat with local AI models (GGUF format)
- Process and split Excel files
- Send and manage emails
- Convert colors between formats
- Format and validate JSON
- Encode/decode Base64 and compute hashes
- Edit Markdown with live preview
- Extend with custom plugins

### Key Features

- **JavaFX 21 UI** — Glassmorphism dark theme with custom window chrome
- **Plugin Architecture** — Auto-discovers plugins via Java SPI with hot-reload
- **AI Chat** — Local LLM inference with GGUF model support
- **Cross-Platform** — Fat JAR bundles native libraries for Windows, macOS, and Linux
- **StepWizard** — Reusable multi-step wizard component for plugin UIs

## Requirements

- **JDK 21 or higher**
- **Maven 3.8+** (for building from source)

## Quick Start

```bash
# Download from GitHub Releases, then:
java -jar FengYu-3.1.0.jar
```

Or build from source:

```bash
mvn install -f FengYu-Api/pom.xml -DskipTests
mvn clean package -f FengYu/pom.xml -DskipTests
java -jar FengYu/target/FengYu-3.1.0.jar
```

## License

MIT License
