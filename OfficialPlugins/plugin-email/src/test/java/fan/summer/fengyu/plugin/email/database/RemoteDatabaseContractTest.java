package fan.summer.fengyu.plugin.email.database;

import fan.summer.fengyu.plugin.email.repository.AccountRepository;
import fan.summer.fengyu.plugin.email.repository.AddressBookRepository;
import fan.summer.fengyu.plugin.email.repository.ArchiveRepository;
import fan.summer.fengyu.plugin.email.repository.PendingSendRepository;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteDatabaseContractTest {
    private static final Set<String> EXPECTED_TABLES = Set.of("fengyu_pl_email_schema_history",
        "fengyu_pl_email_account", "fengyu_pl_email_contact", "fengyu_pl_email_tag",
        "fengyu_pl_email_contact_tag", "fengyu_pl_email_mass_config", "fengyu_pl_email_pending_send",
        "fengyu_pl_email_sent_log", "fengyu_pl_email_archive");
    @TempDir Path temp;

    @Test void h2RepositoryContractIsMandatory() throws Exception {
        exercise(new PluginDatabaseConfig("h2", "org.h2.Driver",
            "jdbc:h2:mem:email-contract-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "", temp.resolve("h2")));
    }

    @Test void sqliteRepositoryContractIsMandatory() throws Exception {
        exercise(new PluginDatabaseConfig("sqlite", "org.sqlite.JDBC",
            "jdbc:sqlite:" + temp.resolve("email-contract.db"), "", "", temp.resolve("sqlite")));
    }

    @TestFactory Stream<DynamicTest> configuredRemoteDatabasesHonorTheEmailSchemaContract() {
        return Stream.of(
            contract("mysql", "com.mysql.cj.jdbc.Driver", "FENGYU_TEST_MYSQL_URL"),
            contract("postgresql", "org.postgresql.Driver", "FENGYU_TEST_POSTGRESQL_URL"));
    }

    private DynamicTest contract(String type, String driver, String urlVariable) {
        return DynamicTest.dynamicTest(type + " database contract", () -> {
            String url = System.getenv(urlVariable);
            Assumptions.assumeTrue(url != null && !url.isBlank(), urlVariable + " is not configured");
            String prefix = urlVariable.substring(0, urlVariable.length() - 4);
            PluginDatabaseConfig config = new PluginDatabaseConfig(type, driver, url,
                System.getenv().getOrDefault(prefix + "USERNAME", ""),
                System.getenv().getOrDefault(prefix + "PASSWORD", ""), temp.resolve(type));

            exercise(config);
        });
    }

    private void exercise(PluginDatabaseConfig config) throws Exception {
            Class.forName(config.driver());
            Set<String> before;
            try (Connection connection = DriverManager.getConnection(config.url(), config.username(), config.password())) {
                before = schemaTables(connection);
            }
            EmailDatabase database = new EmailDatabase(config);
            new EmailDatabase(config); // migration is repeatable against an existing schema
            String unique = UUID.randomUUID().toString().replace("-", "");

            AccountRepository accounts = new AccountRepository(database);
            long accountId = accounts.saveAccount(new AccountRepository.AccountInput(null, "Contract",
                unique + "@example.com", "smtp.example.com", 587, "STARTTLS", "imap.example.com", 993,
                "SSL", true), "encrypted-contract-secret");
            assertTrue(accounts.findAccount(accountId).isPresent());

            AddressBookRepository addressBook = new AddressBookRepository(database);
            long contactId = addressBook.saveContact(new AddressBookRepository.ContactInput(null,
                "contact-" + unique + "@example.com", "Contract", null));
            long tagId = addressBook.saveTag(null, "contract-" + unique);
            addressBook.assignTags(Set.of(contactId), Set.of(tagId));
            assertEquals(Set.of("contact-" + unique + "@example.com"), addressBook.resolveRecipientEmails(Set.of(tagId)));

            PendingSendRepository pending = new PendingSendRepository(database);
            String confirmation = "contract-" + unique;
            pending.create(confirmation, accountId, "SINGLE", "{\"messages\":[]}", LocalDateTime.now().plusHours(1));
            assertTrue(pending.find(confirmation).isPresent());

            ArchiveRepository archive = new ArchiveRepository(database);
            long archiveId = archive.insert(new ArchiveRepository.ArchiveEntry(accountId, unique + "@example.com",
                "INBOX", unique, "Contract", "sender@example.com", "[]", Instant.now(), Instant.now(),
                false, "preview", temp.resolve(unique + ".eml").toString()));
            assertTrue(archive.detail(archiveId).isPresent());

            assertPrefixedTables(database, before);
    }

    private static void assertPrefixedTables(EmailDatabase database, Set<String> before) throws Exception {
        Set<String> tables;
        try (Connection connection = database.openConnection()) { tables = schemaTables(connection); }
        Set<String> created = new HashSet<>(tables); created.removeAll(before);
        assertTrue(created.stream().allMatch(name -> name.startsWith("fengyu_pl_email_")), created::toString);
        assertTrue(tables.containsAll(EXPECTED_TABLES), tables::toString);
    }

    private static Set<String> schemaTables(Connection connection) throws Exception {
        Set<String> tables = new HashSet<>();
        String catalog = connection.getCatalog();
        String schema = connection.getSchema();
        try (var result = connection.getMetaData().getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (result.next()) {
                tables.add(result.getString("TABLE_NAME").toLowerCase());
            }
        }
        return tables;
    }
}
