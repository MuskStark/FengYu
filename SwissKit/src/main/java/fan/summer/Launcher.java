package fan.summer;

/**
 * Fat JAR / classpath launch entry point.
 *
 * Must be separate from ToolboxApp (extends Application).
 * JavaFX module system requires: when using a class that extends Application
 * as the main class, javafx.graphics needs to be on the module-path,
 * not classpath. Using this non-Application intermediate class for launch
 * allows compatibility with classpath mode (lib/ directory).
 */
public class Launcher {
    public static void main(String[] args) {
        fan.summer.app.SwissKitJApp.main(args);
    }
}