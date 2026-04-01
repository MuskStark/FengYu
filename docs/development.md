# Development Guide

This guide covers everything you need to know to contribute to SwissKit, from setting up your development environment to
implementing new features.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Setting Up Development Environment](#setting-up-development-environment)
- [Project Structure](#project-structure)
- [Code Standards](#code-standards)
- [Adding New Tools](#adding-new-tools)
- [Testing](#testing)
- [Building](#building)
- [Common Tasks](#common-tasks)

## Prerequisites

Before you start developing, ensure you have:

- **JDK 11 or higher**
- **Maven 3.6 or higher**
- **IntelliJ IDEA** (recommended) or your preferred IDE
- **Git**

### Verify Installation

```bash
# Check Java version
java -version

# Check Maven version
mvn -version

# Check Git version
git --version
```

## Setting Up Development Environment

### 1. Clone the Repository

```bash
git clone https://github.com/MuskStark/SwissKitJ.git
cd SwissKit
```

### 2. Import into IDE

**IntelliJ IDEA**:

1. Open IntelliJ IDEA
2. Select "Open" and choose the project directory
3. Wait for Maven to download dependencies
4. The project will be automatically recognized as a Maven project

### 3. Build the Project

```bash
mvn clean compile
```

### 4. Run the Application

```bash
mvn exec:java -Dexec.mainClass="fan.summer.Main"
```

Or run `Main.java` directly from your IDE.

## Project Structure

```
SwissKit/
├── src/main/java/fan/summer/
│   ├── Main.java                    # Entry point
│   ├── annoattion/                  # Annotations
│   │   └── SwissKitPage.java       # Page annotation
│   ├── kitpage/                     # Tool modules
│   │   ├── KitPage.java            # Interface
│   │   ├── KitPageScanner.java     # Auto-discovery scanner
│   │   ├── WelcomePage.java        # Welcome page
│   │   ├── excel/                  # Excel tool
│   │   │   ├── listener/           # Event listeners
│   │   │   └── plugin.swisskit.hpl.worker/             # Background workers
│   │   └── email/                  # Email tool
│   ├── plugin.swisskit.hpl.ui/                         # Custom UI components
│   │   └── components/
│   └── utils/                      # Utilities
├── src/main/resources/              # Resources (icons, etc.)
├── pom.xml                          # Maven configuration
└── docs/                            # Documentation
```

## Code Standards

### Naming Conventions

**Packages**:

- Tool pages: `fan.summer.kitpage.{toolName}`
- Listeners: `listener/{function}Listener.java`
- Workers: `plugin.swisskit.hpl.worker/{function}Worker.java`
- Callbacks: `plugin.swisskit.hpl.worker/{function}Callback.java`

**Classes**:

- Pages: `{ToolName}KitPage` or `{ToolName}Page`
- Listeners: `{Function}Listener`
- Workers: `{Function}Worker`
- Callbacks: `{Function}Callback`

**Methods and Variables**:

- Use camelCase
- Be descriptive and concise

```java
// Good
public void analyzeExcelFile(Path filePath) { }
private int currentProgress;

// Bad
public void analyzeExcel() { }
private int cp;
```

### Documentation

- Add Javadoc for all public methods
- Use clear, concise descriptions
- Include parameters and return values

```java
/**
 * Analyzes an Excel file and extracts header information.
 *
 * @param filePath the path to the Excel file to analyze
 * @return a map of sheet names to their headers
 * @throws IOException if the file cannot be read
 */
public Map<String, Map<Integer, String>> analyzeExcel(Path filePath) throws IOException {
    // Implementation
}
```

### Language

- All code comments must be in **English**
- All UI text must be in **English**
- Variable and method names in English

### UI Standards

- Use `SansSerif` font for UI components
- Use color constants from `UIUtils`
- Recommend layouts: `BorderLayout`, `GridBagLayout`, `BoxLayout`

```java
JLabel titleLabel = new JLabel("My Tool");
titleLabel.setFont(new Font("SansSerif", Font.BOLD, 18));
panel.add(titleLabel, BorderLayout.NORTH);
```

## Adding New Tools

### Step-by-Step Guide

**1. Create Package Structure**

```bash
mkdir -p src/main/java/fan/summer/kitpage/mytool
```

**2. Implement KitPage Interface with @SwissKitPage Annotation**

```java
package fan.summer.kitpage.mytool;

import fan.summer.api.KitPage;
import fan.summer.annoattion.SwissKitPage;

import javax.swing.*;
import java.awt.*;

@SwissKitPage(
        menuName = "🔧 My Tool",
        menuTooltip = "Open My Tool",
        visible = true,
        order = 10
)
public class MyToolPage implements KitPage {
    private JPanel panel;

    public MyToolPage() {
        initComponents();
    }

    private void initComponents() {
        panel = new JPanel(new BorderLayout());

        // Add your UI components here
        JLabel titleLabel = new JLabel("My Tool");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 18));

        panel.add(titleLabel, BorderLayout.NORTH);
    }

    @Override
    public JPanel getPanel() {
        return panel;
    }
}
```

**Annotation Properties**:

| Property      | Type    | Default | Description                   |
|---------------|---------|---------|-------------------------------|
| `menuName`    | String  | ""      | Display name in sidebar       |
| `menuTooltip` | String  | ""      | Tooltip on hover              |
| `visible`     | boolean | true    | Whether to show in menu       |
| `order`       | int     | 0       | Display order (lower = first) |

**3. Build and Test**

```bash
mvn clean compile
mvn exec:java -Dexec.mainClass="fan.summer.Main"
```

Your new tool will appear automatically in the sidebar!

### Adding Background Processing

For time-consuming operations, use SwingWorker:

```java
public class MyToolPage implements KitPage {
    private JProgressBar progressBar;
    private JButton processButton;

    public MyToolPage() {
        initComponents();
    }

    private void initComponents() {
        panel = new JPanel(new BorderLayout());

        // Create components
        progressBar = new GradientProgressBar();
        processButton = new JButton("Process");

        // Add action listener
        processButton.addActionListener(e -> {
            new MyWorker(progressBar, processButton, panel).execute();
        });

        // Layout
        panel.add(progressBar, BorderLayout.CENTER);
        panel.add(processButton, BorderLayout.SOUTH);
    }
}

class MyWorker extends SwingWorker<Void, Integer> {
    private final JProgressBar progressBar;
    private final JButton button;
    private final Component parent;

    public MyWorker(JProgressBar progressBar, JButton button, Component parent) {
        this.progressBar = progressBar;
        this.button = button;
        this.parent = parent;
    }

    @Override
    protected Void doInBackground() throws Exception {
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(0);
            progressBar.setStringPainted(true);
            button.setEnabled(false);
        });

        // Do work here
        for (int i = 0; i <= 100; i++) {
            Thread.sleep(50);
            publish(i);
        }

        return null;
    }

    @Override
    protected void process(List<Integer> chunks) {
        if (!chunks.isEmpty()) {
            int latest = chunks.get(chunks.size() - 1);
            progressBar.setValue(latest);
            progressBar.setString("Processing... " + latest + "%");
        }
    }

    @Override
    protected void done() {
        try {
            get();
            progressBar.setValue(100);
            progressBar.setString("Completed!");
            button.setEnabled(true);
        } catch (Exception e) {
            progressBar.setString("Failed: " + e.getMessage());
            JOptionPane.showMessageDialog(parent, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            button.setEnabled(true);
        }
    }
}
```

## Testing

### Running Tests

```bash
mvn test
```

### Writing Tests

```java
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExcelKitPageTest {

    @Test
    void testGetTitle() {
        ExcelKitPage page = new ExcelKitPage();
        assertEquals("Excel", page.getTitle());
    }
}
```

## Building

### Clean Build

```bash
mvn clean package
```

### Skip Tests

```bash
mvn clean package -DskipTests
```

### Create Executable JAR

```bash
mvn clean package
# Output: target/SwissKit-1.2.0.jar
```

### Run Executable JAR

```bash
java -jar target/SwissKit-1.2.0.jar
```

## Common Tasks

### Adding a Dependency

Edit `pom.xml`:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>library-name</artifactId>
    <version>1.0.0</version>
</dependency>
```

Then run:

```bash
mvn dependency:resolve
```

### Adding an Icon

Place icon in `src/main/resources/`:

- `icon.png` (preferred)
- `icon.jpg`
- `app.png`

### Debugging

Enable verbose logging:

```bash
java -Dlog4j.configurationFile=path/to/log4j2.xml -jar target/SwissKit-1.1.0.jar
```

Or add logging to your code:

```java
private static final Logger logger = LoggerFactory.getLogger(MyClass.class);

logger.info("Processing started");
logger.error("Error occurred", exception);
```

## Best Practices

### Thread Safety

- Always update UI components in EDT thread
- Use `SwingUtilities.invokeLater()` for UI updates from background threads

```java
// Wrong - from background thread
progressBar.setValue(50);

// Correct
SwingUtilities.invokeLater(() -> progressBar.setValue(50));
```

### Error Handling

- Always handle exceptions gracefully
- Provide user-friendly error messages
- Log errors for debugging

```java
try {
    // Operation
} catch (Exception e) {
    logger.error("Operation failed", e);
    JOptionPane.showMessageDialog(parent,
            "Operation failed: " + e.getMessage(),
            "Error", JOptionPane.ERROR_MESSAGE);
}
```

### Resource Management

- Use try-with-resources for file operations
- Close resources properly

```java
try (Workbook workbook = WorkbookFactory.create(file)) {
    // Use workbook
} // Automatically closed
```

## Contributing

See the [Contributing Guide](contributing.md) for guidelines on contributing to SwissKit.

---

**Ready to contribute?** Check out [Contributing](contributing.md) for submission guidelines!