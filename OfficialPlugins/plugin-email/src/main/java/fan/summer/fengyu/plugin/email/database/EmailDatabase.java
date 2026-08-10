package fan.summer.fengyu.plugin.email.database;

import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import fan.summer.fengyu.sdk.PluginMessages;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.sql.Connection;
import java.sql.SQLException;

/** Plugin-owned JDBC/MyBatis entry point. */
public final class EmailDatabase {
    private static final PluginMessages MSGS = PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, EmailDatabase.class);
    private final PluginDatabaseConfig config;
    private final UnpooledDataSource dataSource;
    private final SqlSessionFactory sessions;

    public EmailDatabase(PluginDatabaseConfig config) {
        this.config = config;
        try { Class.forName(config.driver()); }
        catch (ClassNotFoundException e) { throw new IllegalStateException(MSGS.format("em.err.databaseDriverUnavailable"), e); }
        dataSource = new UnpooledDataSource(config.driver(), config.url(), config.username(), config.password());
        new SchemaMigrator(config.type(), dataSource).migrate();
        Configuration mybatis = new Configuration(new Environment("email", new JdbcTransactionFactory(), dataSource));
        mybatis.setMapUnderscoreToCamelCase(true);
        sessions = new SqlSessionFactoryBuilder().build(mybatis);
    }

    public PluginDatabaseConfig config() { return config; }
    public SqlSession openSession() { return sessions.openSession(); }
    public Connection openConnection() throws SQLException { return dataSource.getConnection(); }
}
