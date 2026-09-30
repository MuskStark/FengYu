package fan.summer.fengyu.account;

import fan.summer.fengyu.FengYuApplication;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-schema coverage for the assigned-id singleton used by first-time cloud sign-in. */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class CloudAccountBindingRepositoryTest {

    @Autowired
    private CloudAccountBindingRepository bindings;

    @Autowired
    private EntityManager entityManager;

    @Test
    void assignedSingletonIdCanBeInsertedUpdatedAndRecreatedAfterSignOut() {
        CloudAccountBindingEntity first = binding("store-user-1", "First");
        assertTrue(first.isNew());

        bindings.saveAndFlush(first);
        assertFalse(first.isNew());
        entityManager.clear();
        assertEquals("First", bindings.findById(CloudAccountBindingEntity.SINGLETON_ID)
                .orElseThrow().getDisplayName());

        CloudAccountBindingEntity existing = bindings.findById(
                CloudAccountBindingEntity.SINGLETON_ID).orElseThrow();
        assertFalse(existing.isNew());
        existing.setDisplayName("Updated");
        bindings.saveAndFlush(existing);
        entityManager.clear();
        assertEquals("Updated", bindings.findById(CloudAccountBindingEntity.SINGLETON_ID)
                .orElseThrow().getDisplayName());

        bindings.deleteById(CloudAccountBindingEntity.SINGLETON_ID);
        bindings.flush();
        entityManager.clear();

        CloudAccountBindingEntity replacement = binding("store-user-2", "Replacement");
        bindings.saveAndFlush(replacement);
        entityManager.clear();
        assertEquals("store-user-2", bindings.findById(CloudAccountBindingEntity.SINGLETON_ID)
                .orElseThrow().getStoreUserId());
    }

    private static CloudAccountBindingEntity binding(String storeUserId, String displayName) {
        CloudAccountBindingEntity binding = new CloudAccountBindingEntity();
        binding.setId(CloudAccountBindingEntity.SINGLETON_ID);
        binding.setStoreUserId(storeUserId);
        binding.setDisplayName(displayName);
        return binding;
    }
}
