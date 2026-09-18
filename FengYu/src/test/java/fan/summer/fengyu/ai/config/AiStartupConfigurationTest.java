package fan.summer.fengyu.ai.config;

import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.database.repository.AppSettingRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiStartupConfigurationTest {

    @Test
    void defaultModelSettingsAreReadOnlyWhenRequested() {
        AppSettingRepository settings = mock(AppSettingRepository.class);
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenReturn(1L);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AppSettingRepository.class, () -> settings);
            context.registerBean(SecurityContext.class, () -> security);
            context.register(AiConfigService.class, AiConfigProperties.Config.class);
            context.refresh();

            verifyNoInteractions(settings);
            AiConfigProperties snapshot = context.getBean(AiConfigProperties.class);
            assertEquals("local", snapshot.mode());
            verify(settings).findByUserIdAndSettingKey(1L, "ai.mode");
            clearInvocations(settings);
            assertSame(snapshot, context.getBean(AiConfigProperties.class));
            verifyNoInteractions(settings);
        }
    }

    @Test
    void compatibilityCallbacksDoNotScanCatalogAtStartup() {
        AiToolRegistry registry = mock(AiToolRegistry.class);
        ToolCallback callback = mock(ToolCallback.class);
        when(registry.callbacks()).thenReturn(List.of(callback));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(AiToolDiscoveryConfig.class);
            // Replace the production registry factory with a controlled live catalog.
            context.registerBean("aiToolRegistry", AiToolRegistry.class, () -> registry);
            context.refresh();

            verifyNoInteractions(registry);
            assertArrayEquals(new ToolCallback[]{callback}, context.getBean(ToolCallback[].class));
            verify(registry).callbacks();
        }
    }
}
