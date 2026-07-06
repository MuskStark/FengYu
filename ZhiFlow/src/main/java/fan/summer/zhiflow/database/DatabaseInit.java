package fan.summer.zhiflow.database;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

/**
 * Database initialization and MyBatis configuration for the ZhiFlow application.
 * <p>
 * This class manages the H2 database connection lifecycle, including:
 * <ul>
 *   <li>Creating the database file and schema directory under {@code .zhiflow/}</li>
 *   <li>Executing {@code init.sql} to create/verify all tables</li>
 *   <li>Initializing MyBatis SqlSessionFactory with dynamic database URL injection</li>
 * </ul>
 * <p>
 * Must be called once at application startup via {@link #init()}. After initialization,
 * use {@link #getSqlSession()} to obtain database sessions for MyBatis operations.
 *
 * @since 3.0.0
 */
public class DatabaseInit {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseInit.class);

    private static final String DB_URL;

    static {
        String dbPath = Path.of(System.getProperty("user.dir"))
                .resolve(".zhiflow")
                .resolve("zhiflow")
                .toAbsolutePath()
                .toString()
                .replace("\\", "/");
        DB_URL = "jdbc:h2:file:" + dbPath
                + ";AUTO_SERVER=TRUE"
                + ";INIT=CREATE SCHEMA IF NOT EXISTS PUBLIC\\;SET SCHEMA PUBLIC";
    }

    private static SqlSessionFactory sqlSessionFactory;

    /**
     * Initializes the database connection and creates necessary tables.
     * This method should be called once at application startup.
     */
    public static void init() {
        try {
            // Ensure database directory exists
            Path dbDir = Path.of(System.getProperty("user.dir")).resolve(".zhiflow");
            if (!Files.exists(dbDir)) {
                Files.createDirectories(dbDir);
                logger.info("Created database directory: {}", dbDir.toAbsolutePath());
            }

            createTables();
            initMyBatis();

            logger.info("Database initialization completed, url={}", DB_URL);
        } catch (Exception e) {
            logger.error("Failed to initialize database", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    /**
     * Creates database tables by executing init.sql script.
     * The script is loaded from classpath resources.
     */
    private static void createTables() {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("H2 driver not found", e);
        }

        // Read init.sql from classpath as stream (works inside JAR)
        try (InputStream initSqlStream = DatabaseInit.class.getClassLoader()
                .getResourceAsStream("init.sql")) {

            if (initSqlStream == null) {
                throw new RuntimeException("Cannot find init.sql in classpath");
            }

            // Read the SQL content
            String initSqlContent = new String(initSqlStream.readAllBytes(), StandardCharsets.UTF_8);

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 Statement stmt = conn.createStatement()) {

                // Execute SQL statements directly
                stmt.execute(initSqlContent);
                logger.info("Database tables verified/created successfully from init.sql");

            }
        } catch (Exception e) {
            logger.error("Failed to create database tables", e);
            throw new RuntimeException("Failed to create database tables", e);
        }
    }

    /**
     * Initializes MyBatis SqlSessionFactory with database configuration.
     * Loads mybatis-config.xml from classpath and injects dynamic database URL.
     */
    private static void initMyBatis() {
        try (InputStream configStream = DatabaseInit.class.getClassLoader()
                .getResourceAsStream("mybatis-config.xml")) {

            if (configStream == null) {
                throw new RuntimeException("Cannot find mybatis-config.xml");
            }

            // Inject dynamic URL into mybatis-config.xml ${db.url} placeholder via Properties
            Properties props = new Properties();
            props.setProperty("db.url", DB_URL);

            sqlSessionFactory = new SqlSessionFactoryBuilder()
                    .build(configStream, props);

            logger.info("MyBatis SqlSessionFactory initialized");

        } catch (Exception e) {
            logger.error("Failed to initialize MyBatis", e);
            throw new RuntimeException("Failed to initialize MyBatis", e);
        }
    }

    /**
     * Returns a new SqlSession for database operations.
     * Caller is responsible for closing the session after use.
     *
     * @return a new SqlSession instance
     * @throws IllegalStateException if database is not initialized
     */
    public static SqlSession getSqlSession() {
        if (sqlSessionFactory == null) {
            throw new IllegalStateException("Database not initialized. Call init() first.");
        }
        return sqlSessionFactory.openSession();
    }

    /**
     * Returns the SqlSessionFactory instance for advanced MyBatis usage.
     *
     * @return the SqlSessionFactory instance
     * @throws IllegalStateException if database is not initialized
     */
    public static SqlSessionFactory getSqlSessionFactory() {
        if (sqlSessionFactory == null) {
            throw new IllegalStateException("Database not initialized. Call init() first.");
        }
        return sqlSessionFactory;
    }

    /**
     * Checks if the database has been initialized.
     *
     * @return true if database is initialized, false otherwise
     */
    public static boolean isInitialized() {
        return sqlSessionFactory != null;
    }

    // ── Convenience session helpers ──────────────────────────────

    /**
     * Executes an action with a new {@link SqlSession}, committing and closing automatically.
     *
     * <p>The session is always closed in a {@code finally} block. If the action completes
     * without throwing, {@code session.commit()} is called before closing.</p>
     *
     * @param action the action to execute; the session is passed as the argument
     */
    public static void withSession(java.util.function.Consumer<SqlSession> action) {
        try (SqlSession session = getSqlSession()) {
            action.accept(session);
            session.commit();
        }
    }

    /**
     * Executes a function with a new {@link SqlSession}, committing and closing automatically.
     *
     * @param action the function to execute; the session is passed as the argument
     * @param <T>    the return type
     * @return the result of the function
     */
    public static <T> T withSession(java.util.function.Function<SqlSession, T> action) {
        try (SqlSession session = getSqlSession()) {
            T result = action.apply(session);
            session.commit();
            return result;
        }
    }

    /**
     * Executes an action with a MyBatis mapper, committing and closing automatically.
     *
     * <p>This is a convenience wrapper that obtains a session, retrieves the mapper,
     * executes the action, commits, and closes the session.</p>
     *
     * @param mapperClass the MyBatis mapper interface class
     * @param action      the action to execute; the mapper is passed as the argument
     * @param <M>         the mapper type
     */
    public static <M> void withMapper(Class<M> mapperClass, java.util.function.Consumer<M> action) {
        try (SqlSession session = getSqlSession()) {
            M mapper = session.getMapper(mapperClass);
            action.accept(mapper);
            session.commit();
        }
    }

    /**
     * Executes a function with a MyBatis mapper, committing and closing automatically.
     *
     * @param mapperClass the MyBatis mapper interface class
     * @param action      the function to execute; the mapper is passed as the argument
     * @param <M>         the mapper type
     * @param <T>         the return type
     * @return the result of the function
     */
    public static <M, T> T withMapper(Class<M> mapperClass, java.util.function.Function<M, T> action) {
        try (SqlSession session = getSqlSession()) {
            M mapper = session.getMapper(mapperClass);
            T result = action.apply(mapper);
            session.commit();
            return result;
        }
    }
}
