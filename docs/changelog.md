# Changelog

All notable changes to SwissKit will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 1.2.2

### ✨ Added

- Mouse Plugin: Add KeepMove plugin to prevent screen saver
- Mouse Plugin: Add button interlock for KeepMove
- SwissKit-Plugin-Mouse module added to build

### 🔧 Fixed

- Plugin loading: Handle errors gracefully to ensure app startup

---

## 1.2.1

### ✨ Added

- Excel: Support copying entire sheet to all split files when headerIndex=-1 and columnIndex=-1

### 🔧 Fixed

- HappyLearning: Change totaltime from Integer to Float
- HappyLearning: Update UI when course is found

---

## 1.2.0

### ✨ Added

- Email: Rich text editor with formatting toolbar (bold, italic, underline, font size, text color)
- HappyLearning: skipClass button with enable/disable control

### 🔧 Fixed

- Email: Improve rich text editor alignment and HTML extraction

---

## 1.0.0

### 🔧 Fixes

- EmailSentWorker: Use proper window ancestor for error dialog
- SetComplexSplitConfigWorker: Add null check for selectedItem
- StringUtil: Pre-compile email regex pattern as static constant

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