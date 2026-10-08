package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.database.repository.PluginInstallRecordRepository;
import fan.summer.fengyu.plugin.store.InstallerDispatcher;
import fan.summer.fengyu.plugin.store.StoreSourceRegistry;
import fan.summer.fengyu.plugin.store.UnifiedCatalogEntry;
import fan.summer.fengyu.plugin.store.UnifiedStoreService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * uid resolution of the install lifecycle endpoints: one catalog pull indexed by uid (a
 * map lookup), duplicate uids resolved deterministically to the first entry, unknown uids
 * rejected without touching the dispatcher.
 */
class PluginStoreControllerTest {

    private final StoreSourceRegistry sources = mock(StoreSourceRegistry.class);
    private final UnifiedStoreService store = mock(UnifiedStoreService.class);
    private final InstallerDispatcher dispatcher = mock(InstallerDispatcher.class);
    private final PluginInstallRecordRepository records = mock(PluginInstallRecordRepository.class);

    private PluginStoreController controller() {
        return new PluginStoreController(sources, store, dispatcher, records);
    }

    private static UnifiedCatalogEntry entry(String uid) {
        return new UnifiedCatalogEntry(uid, uid.split(":")[0],
                fan.summer.fengyu.plugin.store.StoreSourceType.FENGYU, uid, uid, "desc",
                null, null, List.of(), null, null,
                new UnifiedCatalogEntry.ZipUrlSource("https://e/" + uid + ".fyp"),
                List.of(), List.of(), null, false, null, false, false);
    }

    @Test
    void installResolvesTheEntryByUidFromOneCatalogPull() {
        UnifiedCatalogEntry wanted = entry("good:plugin");
        when(store.list(any(UnifiedStoreService.StoreFilter.class)))
                .thenReturn(List.of(entry("other:plugin"), wanted));

        controller().install("good:plugin");

        verify(dispatcher).install(wanted);
    }

    @Test
    void duplicateUidsResolveDeterministicallyToTheFirstEntry() {
        UnifiedCatalogEntry first = entry("dup:uid");
        UnifiedCatalogEntry second = entry("dup:uid");
        when(store.list(any(UnifiedStoreService.StoreFilter.class)))
                .thenReturn(List.of(first, second));

        controller().install("dup:uid");

        verify(dispatcher).install(first);
    }

    @Test
    void unknownUidIsRejectedBeforeTheDispatcherRuns() {
        when(store.list(any(UnifiedStoreService.StoreFilter.class)))
                .thenReturn(List.of(entry("known:plugin")));

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> controller().install("missing:plugin"));
        assertEquals("No catalog entry for uid: missing:plugin", rejected.getMessage());
        verify(dispatcher, never()).install(any());
    }
}
