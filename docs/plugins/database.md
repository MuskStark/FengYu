# Database Layer

## 概述

插件数据库路径：`.fengyu/plugins/database/pl_<slug>`（相对于运行时工作目录）

技术栈：H2 嵌入式数据库 + MyBatis ORM

## DatabaseInit

```java
package {{base-package}}.database;

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
                .resolve(".fengyu")
                .resolve("plugins")
                .resolve("database")
                .resolve("pl_{{slug}}")
                .toAbsolutePath()
                .toString()
                .replace("\\", "/");
        DB_URL = "jdbc:h2:file:" + dbPath
                + ";AUTO_SERVER=TRUE"
                + ";INIT=CREATE SCHEMA IF NOT EXISTS PUBLIC\\;SET SCHEMA PUBLIC";
    }

    private static SqlSessionFactory sqlSessionFactory;

    public static void init() {
        try {
            Path dbDir = Path.of(System.getProperty("user.dir"))
                    .resolve(".fengyu").resolve("plugins").resolve("database");
            if (!Files.exists(dbDir)) {
                Files.createDirectories(dbDir);
            }
            createTables();
            initMyBatis();
        } catch (Exception e) {
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    private static void createTables() {
        try (InputStream sql = DatabaseInit.class.getClassLoader().getResourceAsStream("init.sql")) {
            if (sql == null) throw new RuntimeException("Cannot find init.sql");
            String content = new String(sql.readAllBytes());
            try (Connection conn = DriverManager.getConnection(DB_URL);
                 Statement stmt = conn.createStatement()) {
                stmt.execute(content);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tables", e);
        }
    }

    private static void initMyBatis() {
        try (InputStream config = DatabaseInit.class.getClassLoader()
                .getResourceAsStream("mybatis-config.xml")) {
            if (config == null) throw new RuntimeException("Cannot find mybatis-config.xml");
            Properties props = new Properties();
            props.setProperty("db.url", DB_URL);
            sqlSessionFactory = new SqlSessionFactoryBuilder().build(config, props);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize MyBatis", e);
        }
    }

    public static SqlSession getSqlSession() {
        if (sqlSessionFactory == null) {
            throw new IllegalStateException("Database not initialized. Call init() first.");
        }
        return sqlSessionFactory.openSession();
    }
}
```

---

## mybatis-config.xml

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE configuration PUBLIC "-//mybatis.org//DTD Config 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-config.dtd">
<configuration>
    <settings>
        <setting name="mapUnderscoreToCamelCase" value="true"/>
        <setting name="localCacheScope" value="STATEMENT"/>
        <setting name="cacheEnabled" value="false"/>
        <setting name="jdbcTypeForNull" value="NULL"/>
    </settings>
    <environments default="default">
        <environment id="default">
            <transactionManager type="JDBC"/>
            <dataSource type="UNPOOLED">
                <property name="driver" value="org.h2.Driver"/>
                <property name="url" value="${db.url}"/>
            </dataSource>
        </environment>
    </environments>
    <mappers>
        <!-- Add mapper XML references here -->
    </mappers>
</configuration>
```

---

## Mapper 接口与 XML

### Java 接口

```java
package {{base-package}}.database.mapper;

import {{base-package}}.database.entity.{{Name}}Entity;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface {{Name}}Mapper {
    void batchInsert(List<{{Name}}Entity> data);
    List<{{Name}}Entity> selectAllByDate(@Param("recordDate") String recordDate);
    void deleteByDate(@Param("recordDate") String date);
}
```

### XML Mapper

路径：`src/main/resources/mapper/{{Name}}Mapper.xml`

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="{{base-package}}.database.mapper.{{Name}}Mapper">

    <insert id="batchInsert" parameterType="list">
        INSERT INTO TABLE_NAME (id, field1, field2)
        VALUES
        <foreach collection="list" item="record" separator=",">
            (#{record.id}, #{record.field1}, #{record.field2})
        </foreach>
    </insert>

    <select id="selectAllByDate" resultType="{{base-package}}.database.entity.{{Name}}Entity">
        SELECT * FROM TABLE_NAME WHERE record_date = #{recordDate}
    </select>

    <delete id="deleteByDate">
        DELETE FROM TABLE_NAME WHERE record_date = #{recordDate}
    </delete>
</mapper>
```

**CRITICAL**：XML `namespace` 必须与 Java 接口的完全限定名完全一致，否则抛出 `BindingException`。

---

## init.sql 示例

```sql
CREATE TABLE IF NOT EXISTS EXAMPLE_ENTITY (
    id INT AUTO_INCREMENT PRIMARY KEY,
    field1 VARCHAR(255),
    field2 VARCHAR(255),
    record_date VARCHAR(20)
);
```