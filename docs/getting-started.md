# Getting Started

Welcome to SwissKit! This guide will help you install, configure, and run SwissKit on your system.

## Requirements

Before you begin, ensure you have the following installed:

- **JDK 21 or higher**
- **Maven 3.6 or higher** (required for building from source)

SwissKit bundles JavaFX for all platforms inside the fat JAR -- no separate JavaFX SDK is needed.

### Verify Installation

```bash
# Check Java version
java -version

# Check Maven version
mvn -version
```

## Installation

### Option 1: Download Pre-built JAR

The easiest way to get started is to download the pre-built JAR file from the [GitHub Releases](https://github.com/MuskStark/SwissKitJ/releases) page.

1. Download `SwissKitJ-3.0.0-alpha.1.jar`
2. Run the application:
   ```bash
   java -jar SwissKitJ-3.0.0-alpha.1.jar
   ```

The fat JAR includes all dependencies including JavaFX libraries for every supported platform (macOS, Windows, Linux), so no additional setup is required.

### Option 2: Build from Source

If you want to build from source, follow these steps:

```bash
# Clone the repository
git clone https://github.com/MuskStark/SwissKitJ.git
cd SwissKitJ

# Install API module first (required)
mvn install -f SwissKitJ-Api/pom.xml -DskipTests

# Build the main project
mvn clean package -DskipTests

# Run the application
java -jar SwissKit/target/SwissKitJ-3.0.0-alpha.1.jar
```

**Build order matters**: The `SwissKitJ-Api` module provides the shared plugin interface and reusable UI components. It must be installed into the local Maven repository before the main app module can compile.

## Configuration

### Application Settings

SwissKit comes with sensible defaults, but you can customize certain aspects:

#### Window Settings

Default window settings:
- **Minimum Size**: 800x500 pixels
- **Default Size**: 900x600 pixels
- **Theme**: Glassmorphism dark theme (three-layer CSS architecture)
- **Window Style**: `StageStyle.TRANSPARENT` with a custom title bar

The glassmorphism theme is implemented as three CSS layers:

| File | Module | Scope |
|------|--------|-------|
| `css/swisskit-common.css` | `SwissKitJ-Api` | Shared variables, scrollbars, utility classes |
| `css/shell.css` | `SwissKit` | App-shell: titlebar, sidebar, cards, panels |
| `css/builtin.css` | `SwissKit` | Built-in tool styling |

#### Custom Icon

Place an icon file in `src/main/resources/`:
- `icon.png` (preferred)
- `icon.jpg`
- `app.png`

If not found, a log message will be displayed.

## Running the Application

### Using the Fat JAR

```bash
java -jar SwissKit/target/SwissKitJ-3.0.0-alpha.1.jar
```

### Using Maven Exec Plugin

```bash
mvn exec:java -Dexec.mainClass="fan.summer.Launcher"
```

### Using IDE

If you are using IntelliJ IDEA:

1. Open the project
2. Locate `Launcher.java` in `SwissKit/src/main/java/fan/summer/`
3. Right-click and select "Run 'Launcher.main()'"

The `Launcher` class is the fat-JAR manifest entry point and the canonical way to start the application.

## First Steps

Once SwissKit is running:

1. **Glassmorphism Window** -- The application opens with a transparent window frame and custom title bar
2. **Main Window** -- After launch, you will see the sidebar with tool categories and the content area
3. **Select a Tool** -- Choose a tool from the left sidebar, organized by category:
   - **All** -- View all available tools
   - **Text** -- Text processing tools
   - **Image** -- Image manipulation tools
   - **Dev** -- Developer utilities
   - **Net** -- Network tools
   - **Other** -- Miscellaneous tools
4. **Launch a Tool** -- Click on a tool card to see its detail panel, then click **Launch** to open it
5. **Explore** -- Click around to explore the available features

## Troubleshooting

### Application Won't Start

**Issue**: Application fails to start

**Solution**:
- Ensure JDK 21 or higher is installed
- Check `JAVA_HOME` environment variable
- Verify you are using the correct Java version

```bash
# Check Java version
java -version
```

### Excel Files Not Reading

**Issue**: Excel files are not being read properly

**Solution**:
- Ensure file format is supported (.xlsx, .xls)
- Check file permissions
- Verify the file is not corrupted

### UI Not Rendering Correctly

**Issue**: User interface appears broken or misaligned

**Solution**:
- Ensure you are running the fat JAR (which bundles JavaFX) rather than a plain module JAR
- Rebuild the project from a clean state
- Verify the CSS files are present in the JAR

```bash
mvn clean package -DskipTests
```

### API Module Not Found

**Issue**: Build fails with "SwissKitJ-Api not found"

**Solution**:
- Install the API module first:
  ```bash
  mvn install -f SwissKitJ-Api/pom.xml -DskipTests
  ```

## Next Steps

Now that you have SwissKit running, you can:

- [Explore Features](features.md) -- Learn about available tools
- [Read Architecture](architecture.md) -- Understand how it works
- [Start Developing](development.md) -- Contribute to the project

## Getting Help

If you encounter any issues:

1. Check the [Troubleshooting](#troubleshooting) section above
2. Search existing [GitHub Issues](https://github.com/MuskStark/SwissKitJ/issues)
3. Create a new issue with details about your problem

---

**Ready to explore?** Continue to [Features](features.md) to learn what SwissKit can do!
