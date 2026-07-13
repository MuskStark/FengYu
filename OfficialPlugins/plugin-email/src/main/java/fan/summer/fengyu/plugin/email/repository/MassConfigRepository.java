package fan.summer.fengyu.plugin.email.repository;

import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.MassConfig;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.session.SqlSession;

import java.util.List;
import java.util.Optional;

public final class MassConfigRepository {
    private final EmailDatabase database;

    public MassConfigRepository(EmailDatabase database) {
        this.database = database;
        AccountRepository.register(database, Mapper.class);
    }

    public Optional<MassConfig> find(long id) {
        try (SqlSession session = database.openSession()) {
            return Optional.ofNullable(session.getMapper(Mapper.class).find(id));
        }
    }

    public List<MassConfig> list() {
        try (SqlSession session = database.openSession()) {
            return List.copyOf(session.getMapper(Mapper.class).list());
        }
    }

    public long save(Long id, String name, String mode, String configJson) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (mode == null || mode.isBlank()) throw new IllegalArgumentException("mode is required");
        if (configJson == null || configJson.isBlank()) throw new IllegalArgumentException("configJson is required");
        try (SqlSession session = database.openSession()) {
            Row row = new Row(id, name.trim(), mode.trim(), configJson);
            Mapper mapper = session.getMapper(Mapper.class);
            if (id == null) mapper.insert(row); else mapper.update(row);
            session.commit();
            return id == null ? row.id : id;
        }
    }

    public boolean delete(long id) {
        try (SqlSession session = database.openSession()) {
            boolean deleted = session.getMapper(Mapper.class).delete(id) > 0;
            session.commit();
            return deleted;
        }
    }

    private static final class Row {
        private Long id;
        private final String name;
        private final String mode;
        private final String configJson;
        private Row(Long id, String name, String mode, String configJson) {
            this.id = id; this.name = name; this.mode = mode; this.configJson = configJson;
        }
    }

    private interface Mapper {
        @Select("SELECT id,name,mode,config_json AS configJson,created_at AS createdAt FROM FengTu_PL_Email_Mass_Config WHERE id=#{id}") MassConfig find(long id);
        @Select("SELECT id,name,mode,config_json AS configJson,created_at AS createdAt FROM FengTu_PL_Email_Mass_Config ORDER BY id") List<MassConfig> list();
        @Insert("INSERT INTO FengTu_PL_Email_Mass_Config(name,mode,config_json) VALUES(#{name},#{mode},#{configJson})")
        @Options(useGeneratedKeys=true,keyProperty="id") int insert(Row row);
        @Update("UPDATE FengTu_PL_Email_Mass_Config SET name=#{name},mode=#{mode},config_json=#{configJson} WHERE id=#{id}") int update(Row row);
        @Delete("DELETE FROM FengTu_PL_Email_Mass_Config WHERE id=#{id}") int delete(long id);
    }
}
