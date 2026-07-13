package fan.summer.fengyu.plugin.email.rpc;

import fan.summer.fengyu.plugin.email.model.Contact;
import fan.summer.fengyu.plugin.email.model.Tag;
import fan.summer.fengyu.plugin.email.service.AddressBookService;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class AddressBookRpc {
    private final AddressBookService addressBook;
    public AddressBookRpc(AddressBookService addressBook) { this.addressBook = addressBook; }

    public Contact saveContact(ContactRequest request) {
        long id = addressBook.saveContact(new AddressBookService.ContactInput(request.id(), request.email(), request.nickname()));
        return addressBook.findContact(id).orElseThrow();
    }
    public Optional<Contact> findContact(long id) { return addressBook.findContact(id); }
    public boolean deleteContact(long id) { return addressBook.deleteContact(id); }
    public List<Contact> search(SearchRequest request) { return addressBook.search(request.query(), request.tagIds(), request.offset(), request.limit()); }
    public Tag saveTag(TagRequest request) {
        long id = addressBook.saveTag(request.id(), request.name());
        return addressBook.listTags().stream().filter(tag -> tag.id() == id).findFirst().orElseThrow();
    }
    public List<Tag> listTags() { return addressBook.listTags(); }
    public boolean deleteTag(long id) { return addressBook.deleteTag(id); }
    public void assignTags(BulkTagRequest request) { addressBook.assignTags(request.contactIds(), request.tagIds()); }
    public Set<String> resolveRecipients(Set<Long> tagIds) { return addressBook.resolveRecipientEmails(tagIds); }

    public record ContactRequest(Long id, String email, String nickname) { }
    public record TagRequest(Long id, String name) { }
    public record SearchRequest(String query, Set<Long> tagIds, int offset, int limit) {
        public SearchRequest { tagIds = tagIds == null ? Set.of() : Set.copyOf(tagIds); }
    }
    public record BulkTagRequest(Set<Long> contactIds, Set<Long> tagIds) {
        public BulkTagRequest {
            contactIds = contactIds == null ? Set.of() : Set.copyOf(contactIds);
            tagIds = tagIds == null ? Set.of() : Set.copyOf(tagIds);
        }
    }
}
