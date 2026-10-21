package fan.summer.fengyu.ai.connections;

import fan.summer.fengyu.database.entity.ai.ConnectionProfileEntity;
import fan.summer.fengyu.database.repository.ai.ConnectionProfileRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectionProfileServiceTest {

    private ConnectionProfileRepository repository;
    private ConnectionProfileService service;

    @BeforeEach
    void setUp() {
        repository = mock(ConnectionProfileRepository.class);
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenReturn(1L);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findByUserIdAndName(anyLong(), any()))
                .thenReturn(Optional.empty());
        service = new ConnectionProfileService(repository, security);
    }

    private static ConnectionProfileService.ConnectionProfileRequest request(
            String name, String type, Map<String, String> fields) {
        return new ConnectionProfileService.ConnectionProfileRequest(name, type, null, fields);
    }

    @Test
    void createNormalizesTypeAndStoresFields() {
        ConnectionProfileService.ConnectionProfile created =
                service.create(request("Company mailbox", "Email", Map.of("token", "abc123")));

        assertEquals("email", created.type(), "types normalize to the annotation vocabulary");
        assertEquals(Map.of("token", "abc123"), created.fields());
    }

    @Test
    void typeFilterBacksTheInspectorDropdown() {
        when(repository.findByUserIdAndTypeOrderByNameAsc(1L, "email")).thenReturn(java.util.List.of(
                entity("c1", "Company mailbox", "email", "{\"token\":\"abc\"}")));
        assertEquals(1, service.list("email").size());
        assertEquals(Map.of("token", "abc"), service.list("email").getFirst().fields());
    }

    @Test
    void duplicateNamesAreRejected() {
        when(repository.findByUserIdAndName(1L, "Company mailbox")).thenReturn(Optional.of(
                entity("c-existing", "Company mailbox", "email", "{}")));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(request("Company mailbox", "email", Map.of("token", "x"))));
        assertTrue(error.getMessage().contains("already exists"));
    }

    @Test
    void updateRenamingToAnotherProfileFailsButOwnNameIsFine() {
        when(repository.findByIdAndUserId("c-1", 1L)).thenReturn(Optional.of(
                entity("c-1", "Old name", "email", "{\"token\":\"t\"}")));
        when(repository.findByUserIdAndName(1L, "Taken")).thenReturn(Optional.of(
                entity("c-2", "Taken", "email", "{}")));
        assertThrows(IllegalArgumentException.class,
                () -> service.update("c-1", request("Taken", "email", Map.of("token", "t"))));

        when(repository.findByUserIdAndName(1L, "Old name")).thenReturn(Optional.of(
                entity("c-1", "Old name", "email", "{}")));
        ConnectionProfileService.ConnectionProfile updated =
                service.update("c-1", request("Old name", "email", Map.of("token", "t2")));
        assertEquals(Map.of("token", "t2"), updated.fields());
    }

    @Test
    void validationGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(request("  ", "email", Map.of("token", "x"))));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(request("Name", "", Map.of("token", "x"))));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(request("Name", "email", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(null));
        Map<String, String> oversized = Map.of("k", "x".repeat(4097));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(request("Name", "email", oversized)));
    }

    @Test
    void deleteReportsWhetherTheProfileExisted() {
        when(repository.findByIdAndUserId("c-gone", 1L)).thenReturn(Optional.empty());
        assertFalse(service.delete("c-gone"));
        when(repository.findByIdAndUserId("c-1", 1L)).thenReturn(Optional.of(
                entity("c-1", "Old name", "email", "{}")));
        assertTrue(service.delete("c-1"));
    }

    private static ConnectionProfileEntity entity(String id, String name, String type, String fieldsJson) {
        ConnectionProfileEntity entity = new ConnectionProfileEntity();
        entity.setId(id);
        entity.setUserId(1L);
        entity.setName(name);
        entity.setType(type);
        entity.setFieldsJson(fieldsJson);
        entity.setCreatedAt(java.time.Instant.now());
        entity.setUpdatedAt(java.time.Instant.now());
        return entity;
    }
}
