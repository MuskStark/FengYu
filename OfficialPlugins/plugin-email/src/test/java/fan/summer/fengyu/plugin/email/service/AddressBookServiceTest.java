package fan.summer.fengyu.plugin.email.service;

import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.repository.MassConfigRepository;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressBookServiceTest {
    @TempDir Path temp;

    @Test void searchesContactsAssignsTagsInBulkAndResolvesDistinctRecipients() {
        AddressBookService service = new AddressBookService(database("address-book"));
        long engineering = service.saveTag(null, "Engineering");
        long release = service.saveTag(null, "Release");
        assertThrows(IllegalArgumentException.class, () -> service.saveTag(null, "Engineering"));

        long alice = service.saveContact(new AddressBookService.ContactInput(null,
            "alice@example.com", "Alice Chen", null));
        long bob = service.saveContact(new AddressBookService.ContactInput(null,
            "bob@example.com", "Bob Stone", null));
        long carol = service.saveContact(new AddressBookService.ContactInput(null,
            "carol@example.com", "Carol Jones", null));

        service.assignTags(Set.of(alice, bob), Set.of(engineering));
        service.assignTags(Set.of(alice, carol), Set.of(release));

        assertEquals(Set.of("alice@example.com", "bob@example.com", "carol@example.com"),
            service.resolveRecipientEmails(Set.of(engineering, release)));
        assertEquals(Set.of("alice@example.com"), service.resolveRecipientEmails(Set.of(engineering, release), true));
        assertEquals(1, service.search("stone", Set.of(), 0, 20).size());
        assertEquals("bob@example.com", service.search("stone", Set.of(), 0, 20).getFirst().email());
        assertEquals(2, service.search("", Set.of(engineering), 0, 20).size());
        assertEquals(1, service.search("alice", Set.of(engineering, release), 0, 20).size());
        assertEquals(Set.of(engineering, release), service.search("alice", Set.of(), 0, 20).getFirst().tagIds());
    }

    @Test void updatesAndDeletesContactsAndTags() {
        AddressBookService service = new AddressBookService(database("address-book-crud"));
        long tag = service.saveTag(null, "Friends");
        long contact = service.saveContact(new AddressBookService.ContactInput(null,
            "old@example.com", "Old", null));
        service.assignTags(Set.of(contact), Set.of(tag));

        service.saveContact(new AddressBookService.ContactInput(contact, "new@example.com", "New", null));
        assertEquals("new@example.com", service.findContact(contact).orElseThrow().email());
        assertTrue(service.deleteContact(contact));
        assertTrue(service.deleteTag(tag));
        assertTrue(service.listTags().isEmpty());
    }

    @Test void savesContactAndReplacesItsTags() {
        AddressBookService service = new AddressBookService(database("address-book-contact-tags"));
        long customer = service.saveTag(null, "Customer");
        long vip = service.saveTag(null, "VIP");

        long contact = service.saveContact(new AddressBookService.ContactInput(null,
            "tagged@example.com", "Tagged", null), Set.of(customer));
        assertEquals(Set.of(customer), service.findContact(contact).orElseThrow().tagIds());

        service.saveContact(new AddressBookService.ContactInput(contact,
            "tagged@example.com", "Tagged", null), Set.of(vip));
        assertEquals(Set.of(vip), service.findContact(contact).orElseThrow().tagIds());
    }

    @Test void storesReusableMassConfigurationsAndRejectsBlankMetadata() {
        MassConfigRepository configs = new MassConfigRepository(database("mass-config"));
        long id = configs.save(null, "Engineering release", "TAG", "{\"tagIds\":[1]}");
        assertEquals("TAG", configs.find(id).orElseThrow().mode());
        assertEquals(1, configs.list().size());
        configs.save(id, "Updated", "FILENAME_SUFFIX", "{}");
        assertEquals("Updated", configs.find(id).orElseThrow().name());
        assertThrows(IllegalArgumentException.class, () -> configs.save(null, " ", "TAG", "{}"));
        assertTrue(configs.delete(id));
    }

    private EmailDatabase database(String name) {
        return new EmailDatabase(new PluginDatabaseConfig("h2", "org.h2.Driver",
            "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "", temp));
    }
}
