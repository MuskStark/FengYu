package fan.summer.fengyu.plugin.email.repository;

import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.EmailAccount;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.session.SqlSession;

import java.util.List;
import java.util.Optional;

public final class AccountRepository {
    private final EmailDatabase database;

    public AccountRepository(EmailDatabase database) {
        this.database = database;
        register(database, Mapper.class);
    }

    public Optional<EmailAccount> findAccount(long id) {
        try (SqlSession session = database.openSession()) {
            return Optional.ofNullable(session.getMapper(Mapper.class).find(id));
        }
    }

    public List<EmailAccount> listAccounts() {
        try (SqlSession session = database.openSession()) {
            return List.copyOf(session.getMapper(Mapper.class).list());
        }
    }

    public long saveAccount(AccountInput input, String encryptedPassword) {
        try (SqlSession session = database.openSession()) {
            Mapper mapper = session.getMapper(Mapper.class);
            boolean makeDefault = input.defaultAccount() || mapper.countAccounts() == 0;
            if (makeDefault) mapper.clearDefault();
            AccountRow row = new AccountRow(input, encryptedPassword, makeDefault);
            if (input.id() == null) mapper.insert(row); else mapper.update(row);
            if (mapper.countDefaults() == 0) mapper.makeFirstDefault();
            session.commit();
            return input.id() == null ? row.id : input.id();
        }
    }

    public boolean deleteAccount(long id) {
        try (SqlSession session = database.openSession()) {
            Mapper mapper = session.getMapper(Mapper.class);
            EmailAccount existing = mapper.find(id);
            if (existing == null) return false;
            boolean deletedDefault = existing.defaultAccount();
            mapper.delete(id);
            if (deletedDefault) mapper.makeFirstDefault();
            session.commit();
            return true;
        }
    }

    public boolean setDefault(long id) {
        try (SqlSession session = database.openSession()) {
            Mapper mapper = session.getMapper(Mapper.class);
            if (mapper.find(id) == null) return false;
            mapper.clearDefault();
            mapper.setDefault(id);
            session.commit();
            return true;
        }
    }

    public record AccountInput(Long id, String displayName, String email, String smtpHost, int smtpPort,
            String smtpSecurity, String imapHost, Integer imapPort, String imapSecurity, boolean defaultAccount) {
    }

    private static final class AccountRow {
        private Long id;
        private final String displayName;
        private final String email;
        private final String encryptedPassword;
        private final String smtpHost;
        private final int smtpPort;
        private final String smtpSecurity;
        private final String imapHost;
        private final Integer imapPort;
        private final String imapSecurity;
        private final boolean defaultAccount;

        private AccountRow(AccountInput input, String encryptedPassword, boolean defaultAccount) {
            id = input.id(); displayName = input.displayName(); email = input.email();
            this.encryptedPassword = encryptedPassword; smtpHost = input.smtpHost(); smtpPort = input.smtpPort();
            smtpSecurity = input.smtpSecurity(); imapHost = input.imapHost(); imapPort = input.imapPort();
            imapSecurity = input.imapSecurity(); this.defaultAccount = defaultAccount;
        }
    }

    private interface Mapper {
        String COLUMNS = "id, display_name AS displayName, email, encrypted_password AS encryptedPassword, "
            + "smtp_host AS smtpHost, smtp_port AS smtpPort, smtp_security AS smtpSecurity, "
            + "imap_host AS imapHost, imap_port AS imapPort, imap_security AS imapSecurity, "
            + "is_default AS defaultAccount, created_at AS createdAt";

        @Select("SELECT " + COLUMNS + " FROM FengTu_PL_Email_Account WHERE id = #{id}")
        EmailAccount find(@Param("id") long id);

        @Select("SELECT " + COLUMNS + " FROM FengTu_PL_Email_Account ORDER BY is_default DESC, id")
        List<EmailAccount> list();

        @Select("SELECT COUNT(*) FROM FengTu_PL_Email_Account") int countAccounts();
        @Select("SELECT COUNT(*) FROM FengTu_PL_Email_Account WHERE is_default = TRUE") int countDefaults();
        @Update("UPDATE FengTu_PL_Email_Account SET is_default = FALSE WHERE is_default = TRUE") int clearDefault();
        @Update("UPDATE FengTu_PL_Email_Account SET is_default = TRUE WHERE id = #{id}") int setDefault(long id);
        @Update("UPDATE FengTu_PL_Email_Account SET is_default = TRUE WHERE id = (SELECT MIN(id) FROM FengTu_PL_Email_Account)") int makeFirstDefault();

        @Insert("INSERT INTO FengTu_PL_Email_Account(display_name,email,encrypted_password,smtp_host,smtp_port,smtp_security,imap_host,imap_port,imap_security,is_default,created_at) "
            + "VALUES(#{displayName},#{email},#{encryptedPassword},#{smtpHost},#{smtpPort},#{smtpSecurity},#{imapHost},#{imapPort},#{imapSecurity},#{defaultAccount},CURRENT_TIMESTAMP)")
        @Options(useGeneratedKeys = true, keyProperty = "id") int insert(AccountRow row);

        @Update("UPDATE FengTu_PL_Email_Account SET display_name=#{displayName},email=#{email},encrypted_password=#{encryptedPassword},"
            + "smtp_host=#{smtpHost},smtp_port=#{smtpPort},smtp_security=#{smtpSecurity},imap_host=#{imapHost},imap_port=#{imapPort},"
            + "imap_security=#{imapSecurity},is_default=#{defaultAccount} WHERE id=#{id}")
        int update(AccountRow row);

        @Delete("DELETE FROM FengTu_PL_Email_Account WHERE id = #{id}") int delete(long id);
    }

    static void register(EmailDatabase database, Class<?> mapper) {
        try (SqlSession session = database.openSession()) {
            var configuration = session.getConfiguration();
            synchronized (configuration) {
                if (!configuration.hasMapper(mapper)) configuration.addMapper(mapper);
            }
        }
    }
}
