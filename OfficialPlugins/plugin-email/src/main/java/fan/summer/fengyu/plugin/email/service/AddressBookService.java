package fan.summer.fengyu.plugin.email.service;

import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.model.Contact;
import fan.summer.fengyu.plugin.email.model.Tag;
import fan.summer.fengyu.plugin.email.repository.AddressBookRepository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class AddressBookService {
    private final AddressBookRepository addressBook;

    public AddressBookService(EmailDatabase database) { this(new AddressBookRepository(database)); }
    public AddressBookService(AddressBookRepository addressBook) { this.addressBook = addressBook; }

    public long saveContact(ContactInput input) {
        return saveContact(input, null);
    }
    public long saveContact(ContactInput input, Set<Long> tagIds) {
        if (input == null || blank(input.email())) throw new IllegalArgumentException("email is required");
        String email = input.email().trim().toLowerCase(Locale.ROOT);
        if (!email.contains("@")) throw new IllegalArgumentException("Invalid email address");
        return addressBook.saveContact(new AddressBookRepository.ContactInput(input.id(), email,
            trimToNull(input.nickname())), tagIds == null ? null : Set.copyOf(tagIds));
    }
    public Optional<Contact> findContact(long id) { return addressBook.findContact(id); }
    public boolean deleteContact(long id) { return addressBook.deleteContact(id); }
    public List<Contact> search(String query, Set<Long> tagIds, int offset, int limit) {
        return addressBook.searchContacts(query, tagIds, offset, limit);
    }
    public long saveTag(Long id, String name) {
        if (blank(name)) throw new IllegalArgumentException("tag name is required");
        return addressBook.saveTag(id, name.trim());
    }
    public List<Tag> listTags() { return addressBook.listTags(); }
    public boolean deleteTag(long id) { return addressBook.deleteTag(id); }
    public void assignTags(Set<Long> contactIds, Set<Long> tagIds) { addressBook.assignTags(contactIds, tagIds); }
    public Set<String> resolveRecipientEmails(Set<Long> tagIds) { return addressBook.resolveRecipientEmails(tagIds); }
    public Set<String> resolveRecipientEmails(Set<Long> tagIds, boolean requireAllTags) {
        return addressBook.resolveRecipientEmails(tagIds, requireAllTags);
    }

    private static String trimToNull(String value) { return blank(value) ? null : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    public record ContactInput(Long id, String email, String nickname) { }
}
