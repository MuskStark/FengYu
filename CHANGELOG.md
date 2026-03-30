# Changelog

All notable changes to SwissKit will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## 1.1.0

**Release Date:** 2026-03-30

---

### ✨ New Features

- **Plugin Hot-Deployment**: Deploy, reload, and uninstall plugins without restarting the application
- **HappyLearning Enhancement**: Add class hours tracking and status display

### 🔄 Changes

- **Project Restructuring**: Renamed `Happy-learning` → `SwissKitJ-Plugin-HappyLearning`, moved plugins to `OfficalPlugin/` directory
- **Module Organization**: Added `SwissKitJ-Api` as a shared API module

---

## 1.0.0

**Release Date:** 2026-03-29

---

### Introduction

SwissKit is a modular desktop productivity toolkit built with Java Swing, designed to simplify daily office tasks. It provides powerful Excel processing capabilities, email management with mass-sending features, and a flexible plugin system for extensibility.

**Key Characteristics:**
- Cross-platform: Windows, Linux, macOS (Apple Silicon)
- Plugin Architecture: Auto-discovers and loads plugins via Java SPI
- Modern UI: FlatLaf Look and Feel
- Database: H2 embedded database with MyBatis ORM
- Requirements: Java 11 or higher

---

### Features

#### Excel Kit
- **Complex Split Mode**: Split Excel files with complex configurations
- **Configuration Editor**: Visual editor for split parameters
- **Progress Tracking**: Real-time progress bar with animation

#### Email Kit
- **Mass Email Sending**: Send emails to recipients based on tags
- **Address Book**: Manage contacts with tag associations
- **Tag Management**: Organize recipients by tags
- **Sent Log**: Track email sending history with status

#### Plugin System
- **Auto-Discovery**: Automatically finds plugins in `META-INF/services`
- **Isolated Loading**: Plugins loaded in isolated ClassLoader
- **Easy Extension**: Implement `KitPage` interface to add new tools

#### Settings
- **Unified Configuration**: Centralized settings management
- **Plugin Management**: View and manage installed plugins

---

### Installation

#### System Requirements
- Java 11 or higher (JDK/JRE)
- Windows 10+, Linux (glibc 2.17+), or macOS 11+

#### Windows Installation
1. Download `SwissKit-windows.zip`
2. Extract to desired location
3. Run `SwissKit.exe`

#### Linux Installation
1. Download `SwissKit-linux.zip`
2. Extract: `unzip SwissKit-linux.zip`
3. Run: `./run.sh`

#### macOS Installation
1. Download `SwissKit-macos-apple-silicon.zip`
2. Extract: `unzip SwissKit-macos-apple-silicon.zip`
3. Run: `open SwissKit.app` or `./run.sh`

**Note:** Java 11+ is required. Download from [Adoptium](https://adoptium.net/).

---

### For Developers

#### Build from Source

```bash
# Install API module to local Maven repo (required)
mvn install -f SwissKitJ-Api/pom.xml -DskipTests

# Compile
mvn clean compile

# Package executable JAR
mvn clean package -DskipTests

# Run (development)
mvn exec:java -Dexec.mainClass="fan.summer.Main"
```

#### Project Structure
```
SwissKitJ/
├── SwissKitJ-Api/                         # Shared API module (interfaces, annotations)
├── SwissKit/                              # Main application
├── OfficalPlugin/
│   ├── SwissKitJ-Plugin-HappyLearning/   # Auto-learning plugin
│   └── SwissKitJ-Plugin-Qcc/              # CSV to Excel conversion plugin
└── docs/                                  # Documentation
```

#### Plugin Development
1. Create class implementing `KitPage` interface
2. Annotate with `@SwissKitPage`
3. Register in `META-INF/services/fan.summer.api.KitPage`
4. Tool appears automatically in sidebar

#### API Dependency
```xml
<dependency>
    <groupId>fan.summer.api</groupId>
    <artifactId>SwissKitJ-Api</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

### Changelog

The following is the complete version history.

---

## 1.0.0-RC.1

### 🔧 Fixes

- ExcelSplitWorker: Fix EDT violation by using GradientProgressBar type instead of JProgressBar for proper animation
- EmailSentWorker: Division by zero guard when totalEmails is zero
- EmailSentWorker: NPE guard when tagCollect returns null
- SetComplexSplitConfigWorker: EDT violation fix - read Swing component values in constructor
- MassSentConfigView: NPE guard for config.getToTag()
- EmailKitPage: NPE guard for config.getToTag()/getCcTag() in equals comparison
- QueryAllEmailInfoWorker: NPE guard when JSON.parseArray returns null
- ConfigEditorView: Add null-safe objToString() helper for table values
- PluginLoader: Check mkdirs() return value
- GradientProgressBar: Stop animation timer in removeNotify() to prevent resource leaks
- ShowConfigViewWorker: Re-throw exceptions instead of silent catch

### 🔄 Changes

- Maven: Disable resource filtering to prevent binary icon files from being corrupted

---

## 1.0.0-Beta.4

### 🚀 Enhancements

- Settings: Add plugin management UI with installed plugins table and uninstall functionality
- HappyLearning: Display current lesson ID and name (brief) during learning sessions
- HappyLearning: Add progress bar text display showing "current/max h" format

### 🔧 Fixes

- EDT violations: SetComplexSplitConfigWorker and ClearComplexSplitConfigWorker now properly wrap UI updates in SwingUtilities.invokeLater()
- Resource leaks: EmailSentWorker SqlSession usage restructured with try-with-resources
- Resource leaks: ExcelUtil Workbook leaks fixed on exception paths
- NullPointerExceptions: Fixed in EmailSentWorker (progressBar, emailTags), EmailKitPage (tagList), EmailTagsView (parseLong), Main.java (getCause())
- Silent failures: QueryAllEmailInfoWorker now returns null instead of empty list on error for proper error handling
- Cancellation support: EmailSentWorker mass-sending loop now checks isCancelled()

---

## 1.0.0-Beta.3

### 🚀 Enhancements

- Email Address Book: Add double-click editing functionality for contacts
- Email Address Book: Reset button now clears only tag information

---

## 1.0.0-Beta.2

### 🔧 Fixes

- PluginLoader: IsolatedPluginClassLoader now properly delegates JDK classes (java.*, javax.*, sun.*, com.sun.*) to main
  app ClassLoader
- PluginLoader: Universal classloading fallback ensures plugin DTOs and third-party library classes are accessible from
  isolated plugin JARs

### 📝 Documentation

- Add IsolatedPluginClassLoader classloading strategy to architecture.md
- Fix JAR filename typos: Bata.1.jar → Beta.2.jar in documentation
- Update table alignment and spacing in README.md and docs/

---

## 1.0.0-Beta.1

### 🚀 Enhancements

- Add error dialog display when email sending fails

### 🔧 Fixes

- Center email sent log table layout in viewing dialog

---

## 1.0.0-Alpha5

### 🚀 Enhancements

- Add SwissKitJ-Api module for shared components and plugin development
- Add email sent log viewing functionality with status tracking

### 🔄 Changes

- Move KitPage interface to SwissKitJ-Api module
- Move SwissKitPage annotation to SwissKitJ-Api module
- Move UI components (GradientProgressBar, FixedWidthComboBox) to SwissKitJ-Api module

---

## 1.0-Alpha4

### 🚀 Enhancements

- Add progress bar support for email sending
- Display tag names instead of IDs in configuration view
- Improve progress bar real-time updates

---

## 1.0-Alpha3

### 🔄 Changes

- Refactor tag association mechanism to use tag ID instead of tag name

---

## 1.0-Alpha2

### 🚀 Enhancements

- Add email sending functionality with SMTP support
- Add mass email sending feature with tag-based recipients
- Add attachment support by tag-based folder selection

---

## 1.0-Alpha

### 🚀 Enhancements

- Add Excel complex split mode with custom configuration
- Add email address book management feature
- Add email tag management feature
- Add plugin loading system
- Add custom UI components (GradientProgressBar, FixedWidthComboBox)
