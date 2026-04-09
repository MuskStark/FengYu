# SwissKit

![SwissKit](https://img.shields.io/badge/SwissKit-Desktop%20Toolbox-blue) ![Java](https://img.shields.io/badge/Java-11-orange) ![License](https://img.shields.io/badge/License-MIT-green) ![Maven](https://img.shields.io/badge/Maven-3.6+-red)

**SwissKit** is a *modular desktop toolbox* built with Java Swing. It provides a clean, extensible platform for various
utility tools including Excel file processing, email sending, email address book management, and plugin support. The
application uses a plugin-based architecture with automatic page discovery, making it easy to add new functionality.

If you want a lightweight, fast, and customizable desktop utility suite, this is it.

---

## Quick Start

**Requirements:**

- **JDK 11 or higher**
- **Maven 3.6 or higher**

### Installation

```bash
# Clone the repository
git clone https://github.com/MuskStark/SwissKitJ.git
cd SwissKitJ

# Install API module first (required)
mvn install -f SwissKitJ-Api/pom.xml -DskipTests

# Build the main project
mvn clean package

# Run the application (executable JAR)
java -jar target/SwissKit-1.2.1.jar
```

Or using Maven exec plugin:

```bash
mvn exec:java -Dexec.mainClass="fan.summer.Main"
```

### Development Mode

```bash
# Compile
mvn clean compile

# Run
mvn exec:java -Dexec.mainClass="fan.summer.Main"
```

---

## Features

- **📦 Modular Architecture** - Plugin-based design with SPI auto-discovery
- **🎨 Modern UI** - Built with Swing and FlatLaf theme framework
- **⚡ High Performance** - Uses Apache FESOD for efficient Excel processing
- **🔄 Async Processing** - Background tasks with SwingWorker for non-blocking UI
- **💾 Database Support** - H2 + MyBatis for persistent storage
- **📧 Email Management** - Address book with tags for contact organization
- **🔌 Plugin System** - Install external JAR plugins to extend functionality
- **🛠️ Easy Extension** - Add new tools by implementing the `KitPage` interface
- **📊 Custom UI Components** - Gradient progress bar, fixed-width combo box
- **🚀 Splash Screen** - Professional startup experience with loading indicator

### Current Tools

#### 📊 Excel Tool

- **File Analysis** - Read Excel file structure and extract headers
- **File Splitting** - Split Excel files by sheets or column values
- **Complex Split Mode** - Advanced splitting with custom configuration
- **Config View** - View and edit split configurations
- **Progress Tracking** - Real-time progress updates with percentage display
- **Multi-Sheet Support** - Handle multiple sheets in a single file
- **Streaming Data Processing** - Efficient memory usage with Apache FESOD

#### 📧 Email Tool

- **Email Composition** - Compose emails with subject and body
- **Recipient Management** - Add multiple recipients (To, Cc)
- **Mass Email Support** - Send emails to multiple contacts based on tags
- **Tag-based Recipients** - Load recipients from address book by tags
- **Attachment by Tag** - Attach files from tag-based folder selection
- **SMTP Integration** - Full SMTP support with TLS/SSL
- **Sent Log Viewer** - View history of sent emails with status tracking

#### ⚙️ Settings

- **Email Server Configuration** - SMTP settings with TLS/SSL support
- **Email Address Book** - Manage email contacts with nicknames; double-click to edit
- **Tag Management** - Create and manage tags for categorizing contacts
- **Plugin Installation** - Install JAR plugins via file upload

#### 🏠 Welcome Page

- Application overview and guidance
- Quick access to all tools

#### 🎓 HappyLearning Plugin

- **Auto Learning** - Automated online learning with configurable passkey
- **Config Upload** - Upload and manage learning configuration files
- **Progress Tracking** - Real-time progress bars for major and elective subjects
- **Start/Stop Control** - Manual control over learning sessions
- **Background Processing** - Non-blocking UI with SwingWorker

---

## Architecture

### Project Modules

SwissKit uses a multi-module Maven structure:

| Module                 | Description                                                             |
|------------------------|-------------------------------------------------------------------------|
| `SwissKitJ-Api`        | Shared API module containing interfaces, annotations, and UI components |
| `SwissKit` (main)      | Core application with Excel, Email, and Settings tools                  |
| `OfficalPlugin/SwissKitJ-Plugin-HappyLearning` | Auto-learning plugin with progress tracking     |

### Project Structure

```
SwissKitJ/                          # Project root
├── SwissKitJ-Api/                  # Shared API module
│   └── src/main/java/fan/summer/
│       ├── annoattion/
│       │   └── SwissKitPage.java
│       ├── api/
│       │   └── KitPage.java
│       └── plugin.swisskit.hpl.ui/components/
│           ├── GradientProgressBar.java
│           └── FixedWidthComboBox.java
├── SwissKit/                       # Main application
│   └── src/main/java/fan/summer/
│       ├── Main.java
│       ├── database/               # H2 + MyBatis
│       ├── kitpage/                # Excel, Email, Settings pages
│       ├── plugin/                 # PluginLoader, IsolatedPluginClassLoader
│       ├── scaner/                 # SwissKitPageScaner (SPI)
│       ├── plugin.swisskit.hpl.ui/ # UI components
│       └── utils/                  # ExcelUtil, EmailUtil, etc.
├── OfficalPlugin/
│   └── SwissKitJ-Plugin-HappyLearning/  # Built-in plugin
└── docs/                           # Documentation
```

### Plugin System

SwissKit uses an automatic discovery mechanism with annotations and SPI (Service Provider Interface) for tool pages:

1. **Interface**: Implement the `KitPage` interface (from `fan.summer.api.KitPage`)
2. **Annotation**: Add `@SwissKitPage` annotation (from `fan.summer.annoattion.SwissKitPage`)
3. **SPI Registration**: Add class name to `META-INF/services/fan.summer.api.KitPage`
4. **Auto-discovery**: Pages are automatically discovered at runtime using `SwissKitPageScaner`
5. **Sorting**: Pages are sorted by `order()` value in annotation

Example:

```java
package fan.summer.kitpage.mytool;

import fan.summer.api.KitPage;
import fan.summer.annoattion.SwissKitPage;

import javax.swing.*;

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

### Tech Stack

| Category                | Technology       | Version          |
|-------------------------|------------------|------------------|
| **Language**            | Java             | 11               |
| **Build Tool**          | Maven            | 3.6+             |
| **UI Framework**        | Swing + FlatLaf  | 3.5              |
| **Excel Processing**    | Apache FESOD     | 2.0.1-incubating |
| **Database**            | H2 + MyBatis     | 2.4.240 / 3.5.19 |
| **Logging**             | Log4j2 + SLF4J   | 2.25.3 / 2.0.16  |
| **JSON**                | FastJSON2        | 2.0.59           |
| **Code Simplification** | Lombok           | 1.18.42          |
| **Email**               | Simple Java Mail | 8.12.6           |

---

## Database

### Tables

| Table Name                | Purpose                                    |
|---------------------------|--------------------------------------------|
| `swiss_kit_setting_email` | Email SMTP configuration                   |
| `complex_split_config`    | Excel complex split configuration          |
| `email_address_book`      | Email contacts with nicknames and tags     |
| `email_tag`               | Tags for categorizing email contacts       |
| `email_mass_sent_config`  | Mass email sending configuration           |
| `email_sent_log`          | Email sending history with status tracking |

### Database Location

- **Path**: `.swisskit/swisskit.db` (relative to application runtime directory)
- **Type**: H2 (embedded, file-based, no external server required)

---

## Development

### Adding a New Tool

1. Create a new package under `fan.summer.kitpage` (e.g., `pdf/`, `image/`)
2. Create a class implementing `KitPage` (from `SwissKitJ-Api` module)
3. Add `@SwissKitPage` annotation for menu configuration
4. Register in SPI plugin.swisskit.hpl.service file (`META-INF/services/fan.summer.api.KitPage`)
5. The tool will be automatically discovered

### Creating a Plugin

1. Create a new Maven project with `SwissKitJ-Api` as dependency:
   ```xml
   <dependency>
       <groupId>fan.summer.api</groupId>
       <artifactId>SwissKitJ-Api</artifactId>
       <version>1.2.1</version>
   </dependency>
   ```
2. Implement `KitPage` interface
3. Add `@SwissKitPage` annotation
4. Register in `META-INF/services/fan.summer.api.KitPage`
5. Package as JAR and install via Settings page

### Code Style

- Use camelCase for method and variable names
- Use PascalCase for class names
- Add Javadoc comments for all public methods
- All UI text and comments should be in English
- Use SLF4J Logger for logging
- Use `SansSerif` font for UI components

### Building

```bash
# Install API module first (required)
mvn install -f SwissKitJ-Api/pom.xml -DskipTests

# Clean build with executable JAR
mvn clean package

# Skip tests
mvn clean package -DskipTests

# Run executable JAR
java -jar target/SwissKit-1.2.1.jar
```

---

## Roadmap

- [x] Excel file analysis functionality
- [x] Excel file split by sheet functionality
- [x] Excel file split by column functionality
- [x] Excel complex split mode with configuration
- [x] Email address book management
- [x] Email tag management
- [x] Plugin installation support
- [x] Email sending with SMTP support
- [x] Mass email sending with tag-based recipients
- [x] Email sent log viewing functionality
- [ ] Add PDF processing tool
- [ ] Add image processing tool
- [ ] Support theme switching
- [ ] Add unit tests
- [ ] Add multi-language support

---

## Contributing

Contributions are welcome! Please follow these guidelines:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m ':sparkles: Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

### Commit Message Format

Follow the conventional commits format with emojis:

- `✨` / `:sparkles:` - New feature
- `📝` / `:memo:` - Documentation
- `🐛` / `:bug:` - Bug fix
- `♻️` / `:recycle:` - Refactor code
- `⬆️` / `:arrow_up:` - Upgrade dependency

---

## License

This project is licensed under the MIT License - see the LICENSE file for details.

---

## Authors

- **Summer** - Core development
- **PhoebeJ** - Email & Excel functionality

---

## Documentation

For detailed technical documentation, see [AGENTS.md](AGENTS.md).

For user documentation, see the [docs](docs/) folder.

---

**Built with ❤️ using Java and Swing**