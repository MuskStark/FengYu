package fan.summer.database;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

public class DatabaseInit {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseInit.class);

    private static final String DB_URL;

    static {
        String dbPath = Path.of(System.getProperty("user.dir"))
                .resolve(".swisskit")
                .resolve("swisskit")
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
            Path dbDir = Path.of(System.getProperty("user.dir")).resolve(".swisskit");
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
            String initSqlContent = new String(initSqlStream.readAllBytes());

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

            // 通过 Properties 将动态 URL 注入 mybatis-config.xml 的 ${db.url} 占位符
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
}