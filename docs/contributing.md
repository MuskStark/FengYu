# Contributing

Thank you for your interest in contributing to SwissKit! This document provides guidelines and instructions for contributing to the project.

## Prerequisites

- JDK 21 or higher
- Maven 3.6 or higher
- Git
- IntelliJ IDEA (recommended)

## Setup

```bash
# Fork the repository, then clone your fork
git clone https://github.com/YOUR_USERNAME/SwissKitJ.git
cd SwissKitJ

# Add upstream remote
git remote add upstream https://github.com/MuskStark/SwissKitJ.git

# Install API module first (required)
mvn install -f SwissKitJ-Api/pom.xml -DskipTests

# Build the project
mvn clean compile -DskipTests

# Run the application
java -jar SwissKit/target/SwissKitJ-3.0.0-alpha.1.jar
```

## Development Workflow

### Branch naming

- `feature/` — New features
- `bugfix/` — Bug fixes
- `docs/` — Documentation updates
- `refactor/` — Code refactoring

### Commit message format

Use conventional commits with emojis:

| Prefix | Emoji | Purpose |
|--------|-------|---------|
| `✨ feat:` | `:sparkles:` | New feature |
| `🐛 fix:` | `:bug:` | Bug fix |
| `♻️ refactor:` | `:recycle:` | Refactoring |
| `📝 docs:` | `:memo:` | Documentation |
| `⬆️ deps:` | `:arrow_up:` | Dependency upgrade |

### Creating a PR

1. Fork the repository and create a feature branch
2. Make your changes, following the coding standards below
3. Build and test: `mvn clean package -DskipTests`
4. Commit with conventional commit format
5. Push to your fork and open a pull request against `main`

## Coding Standards

### Language

- All code comments in English
- All UI text in English
- Variable and method names in English

### Thread Safety

JavaFX has a single UI thread. All UI updates must happen on it:

```java
// Correct — update UI from background thread
Platform.runLater(() -> progressBar.setProgress(0.5));

// Also correct — update on JavaFX Application Thread directly
label.setText("Done");
```

### Error Handling

```java
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;

try {
    // Operation
} catch (Exception e) {
    log.error("Operation failed", e);
    Alert alert = new Alert(AlertType.ERROR, "Operation failed: " + e.getMessage());
    alert.showAndWait();
}
```

## Reporting Issues

Include:
- OS and version
- Java version (`java -version`)
- SwissKit version
- Steps to reproduce
- Expected vs actual behavior
- Screenshots if applicable

## Getting Help

1. Check the documentation in `docs/`
2. Search existing [GitHub Issues](https://github.com/MuskStark/SwissKitJ/issues)
3. Create a new issue with details

---

**Thank you for contributing to SwissKit!**
