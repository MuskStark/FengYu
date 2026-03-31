# Getting Started

Welcome to SwissKit! This guide will help you install, configure, and run SwissKit on your system.

## Requirements

Before you begin, ensure you have the following installed:

- **JDK 11 or higher**
- **Maven 3.6 or higher** (required for building from source)

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

1. Download `SwissKit-1.1.0.jar`
2. Run the application:
   ```bash
   java -jar SwissKit-1.1.0.jar
   ```

### Option 2: Build from Source

If you want to build from source, follow these steps:

```bash
# Clone the repository
git clone https://github.com/MuskStark/SwissKitJ.git
cd SwissKitJ

# Install API module first (required)
mvn install -f SwissKitJ-Api/pom.xml -DskipTests

# Build the main project
mvn clean package

# Run the application
java -jar target/SwissKit-1.1.0.jar
```

## Configuration

### Application Settings

SwissKit comes with sensible defaults, but you can customize certain aspects:

#### Window Settings

Default window settings:
- **Minimum Size**: 800x500 pixels
- **Default Size**: 900x600 pixels
- **Theme**: FlatIntelliJLaf

#### Custom Icon

Place an icon file in `src/main/resources/`:
- `icon.png` (preferred)
- `icon.jpg`
- `app.png`

If not found, a log message will be displayed.

## Running the Application

### Using Maven Exec Plugin

```bash
mvn exec:java -Dexec.mainClass="fan.summer.Main"
```

### Using JAR File

```bash
java -jar target/SwissKit-1.1.0.jar
```

### Using IDE

If you're using IntelliJ IDEA:

1. Open the project
2. Locate `Main.java` in `src/main/java/fan/summer/`
3. Right-click and select "Run 'Main.main()'"

## First Steps

Once SwissKit is running:

1. **Splash Screen** - You'll see a loading screen while the application initializes
2. **Welcome Page** - After loading, you'll see the welcome page with an overview
3. **Select a Tool** - Choose a tool from the left sidebar:
   - **Excel Tool** - Process and split Excel files
   - **Email Tool** - Compose and send emails, view sent history
4. **Explore** - Click around to explore the available features

## Troubleshooting

### Application Won't Start

**Issue**: Application fails to start

**Solution**:
- Ensure JDK 11+ is installed
- Check `JAVA_HOME` environment variable
- Verify you're using the correct Java version

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
- Update FlatLaf dependencies
- Rebuild the project
- Try running with a fresh build

```bash
mvn clean package
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

- [Explore Features](features.md) - Learn about available tools
- [Read Architecture](architecture.md) - Understand how it works
- [Start Developing](development.md) - Contribute to the project

## Getting Help

If you encounter any issues:

1. Check the [Troubleshooting](#troubleshooting) section above
2. Search existing [GitHub Issues](https://github.com/MuskStark/SwissKitJ/issues)
3. Create a new issue with details about your problem

---

**Ready to explore?** Continue to [Features](features.md) to learn what SwissKit can do!
