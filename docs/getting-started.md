# Getting Started

## Requirements

- **JDK 21 or higher**
- **Maven 3.8 or higher** (for building from source)

FengYu bundles JavaFX for all platforms inside the fat JAR — no separate JavaFX SDK needed.

## Installation

### Option 1: Download Pre-built JAR

Download from the [GitHub Releases](https://github.com/MuskStark/FengYu/releases) page.

```bash
java -jar FengYu-3.1.0.jar
```

The fat JAR includes JavaFX native libraries for macOS, Windows, and Linux — no additional setup required.

### Option 2: Build from Source

```bash
git clone https://github.com/MuskStark/FengYu.git
cd FengYu

# Install API module first (required)
mvn install -f FengYu-Api/pom.xml -DskipTests

# Build the main app
mvn clean package -f FengYu/pom.xml -DskipTests

# Run
java -jar FengYu/target/FengYu-3.1.0.jar
```

**Build order matters**: `FengYu-Api` provides the shared plugin interface and reusable UI components. It must be installed into the local Maven repository before the main app can compile.

## Running

### Fat JAR

```bash
java -jar FengYu/target/FengYu-3.1.0.jar
```

### IDE (IntelliJ IDEA)

1. Open the project
2. Locate `Launcher.java` in `FengYu/src/main/java/fan/summer/`
3. Right-click → "Run 'Launcher.main()'"

The `Launcher` class is the fat-JAR manifest entry point.

## First Steps

1. **Main Window** — The app opens with a transparent window frame and custom title bar
2. **Sidebar** — Tool categories: All, Text, Image, Dev, Net, Other
3. **Tool Card** — Click a tool card to see its detail panel, then click **Launch**
4. **Plugin Store** — Browse and install plugins from the online catalog, or load local JARs

## Troubleshooting

### Application Won't Start

- Ensure JDK 21+ is installed
- Check `JAVA_HOME` environment variable
- Verify you're running the fat JAR (not a module JAR)

### UI Not Rendering

- Rebuild from clean: `mvn clean package -f FengYu/pom.xml -DskipTests`
- Ensure you're running the fat JAR which bundles JavaFX

### API Module Not Found

```bash
mvn install -f FengYu-Api/pom.xml -DskipTests
```

## Next Steps

- [Explore Features](features.md)
- [Architecture](architecture.md)
- [Development Guide](development.md)
