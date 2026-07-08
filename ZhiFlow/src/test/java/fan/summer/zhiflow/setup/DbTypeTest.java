package fan.summer.zhiflow.setup;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DbTypeTest {

    @Test
    void h2_isEmbedded_withCorrectDriverAndDialect() {
        DbType h2 = DbType.H2;
        assertTrue(h2.embedded);
        assertEquals("org.h2.Driver", h2.driver);
        assertEquals("org.hibernate.dialect.H2Dialect", h2.dialect);
        assertTrue(h2.urlTemplate.contains("{path}"));
    }

    @Test
    void sqlite_isEmbedded_usesCommunityDialect() {
        DbType sqlite = DbType.SQLITE;
        assertTrue(sqlite.embedded);
        assertEquals("org.hibernate.community.dialect.SQLiteDialect", sqlite.dialect);
    }

    @Test
    void mysql_isNotEmbedded_hasHostPortDbTemplate() {
        DbType mysql = DbType.MYSQL;
        assertFalse(mysql.embedded);
        assertTrue(mysql.urlTemplate.contains("{host}"));
        assertTrue(mysql.urlTemplate.contains("{port}"));
        assertTrue(mysql.urlTemplate.contains("{db}"));
    }

    @Test
    void postgresql_isNotEmbedded() {
        assertFalse(DbType.POSTGRESQL.embedded);
    }

    @Test
    void fromName_resolvesAllTypes_caseInsensitive() {
        assertEquals(DbType.H2, DbType.fromName("h2"));
        assertEquals(DbType.SQLITE, DbType.fromName("SQLite"));
        assertEquals(DbType.MYSQL, DbType.fromName("MYSQL"));
        assertEquals(DbType.POSTGRESQL, DbType.fromName("postgresql"));
    }

    @Test
    void fromName_unknown_throws() {
        assertThrows(IllegalArgumentException.class, () -> DbType.fromName("oracle"));
    }
}
