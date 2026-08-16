package fan.summer.fengyu.ai.memory;

import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.database.entity.ai.AiMemoryEntity;
import fan.summer.fengyu.database.repository.ai.AiMemoryRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Cross-session memory: keyword × recency ranking, first-use injection text, and the
 * experimental default-off switch. The retrieval half (keyword overlap) is the portable
 * stand-in for an FTS index; recency follows the 7-day half-life curve.
 */
class AiMemoryServiceTest {

    private AiMemoryRepository repository;
    private AiMemoryService service;
    private MockedStatic<AiConfigServiceHeadless> config;

    @BeforeEach
    void setUp() {
        repository = mock(AiMemoryRepository.class);
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenReturn(1L);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new AiMemoryService(repository, security);
        config = mockStatic(AiConfigServiceHeadless.class);
        config.when(() -> AiConfigServiceHeadless.getSetting(anyString(), anyString()))
                .thenReturn("false");
    }

    @AfterEach
    void tearDown() {
        config.close();
    }

    private void enable() {
        config.when(() -> AiConfigServiceHeadless.getSetting(anyString(), anyString()))
                .thenReturn("true");
    }

    private AiMemoryEntity entry(String id, String content, int ageDays) {
        AiMemoryEntity entity = new AiMemoryEntity();
        entity.setId(id);
        entity.setUserId(1L);
        entity.setContent(content);
        entity.setTopicsJson("[]");
        entity.setSource("manual");
        entity.setCreatedAt(LocalDateTime.now().minusDays(ageDays));
        entity.setLastAccessedAt(entity.getCreatedAt());
        return entity;
    }

    @Test
    void remembersAndForgetsWhenEnabledOnly() {
        assertThrows(IllegalStateException.class, () -> service.remember("note", List.of()));
        enable();
        Map<String, Object> stored = service.remember("User prefers Chinese summaries", List.of("prefs"));
        assertEquals("User prefers Chinese summaries", stored.get("content"));
        assertEquals(List.of("prefs"), stored.get("topics"));

        when(repository.findByIdAndUserId("m-1", 1L))
                .thenReturn(Optional.of(entry("m-1", "note", 0)));
        assertTrue(service.forget("m-1"));
        assertFalse(service.forget("missing"));
    }

    @Test
    void searchRanksByKeywordOverlapAndRecency() {
        enable();
        AiMemoryEntity fresh = entry("fresh", "Split invoices by region column", 0);
        AiMemoryEntity old = entry("old", "Split invoices by region column every month", 30);
        AiMemoryEntity irrelevant = entry("other", "Browser automation screenshots", 0);
        when(repository.findByUserIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(irrelevant, old, fresh));

        List<Map<String, Object>> results = service.search("split invoices region", 5);
        assertEquals(2, results.size());
        assertEquals("fresh", results.get(0).get("id"), "fresher entry outranks the older one");
        assertTrue(results.stream().noneMatch(r -> "other".equals(r.get("id"))));
    }

    @Test
    void searchIsDisabledByDefaultAndIgnoresBlankQueries() {
        when(repository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        assertEquals(List.of(), service.search("anything", 5));
        enable();
        assertEquals(List.of(), service.search("   ", 5));
    }

    @Test
    void injectionTextCarriesTopMatchesOnlyWhenEnabled() {
        assertEquals("", service.injectionFor("split invoices", 3));
        enable();
        when(repository.findByUserIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(entry("m-1", "Invoices live in D:/finance", 0)));
        String injection = service.injectionFor("find my invoices", 3);
        assertTrue(injection.contains("Invoices live"));
        assertTrue(injection.contains("long-term memories"));
        assertEquals("", service.injectionFor("", 3));
    }
}
