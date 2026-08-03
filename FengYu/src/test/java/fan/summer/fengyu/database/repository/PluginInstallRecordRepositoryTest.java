package fan.summer.fengyu.database.repository;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.store.PluginInstallRecordEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class PluginInstallRecordRepositoryTest {
    @Autowired private PluginInstallRecordRepository repo;

    @Test
    void findByUidAndUserId_returnsRecord() {
        PluginInstallRecordEntity e = new PluginInstallRecordEntity();
        e.setUid("anthropics-claude:CLAUDE:browser-use");
        e.setPluginName("browser-use");
        e.setSourceType("CLAUDE");
        e.setOrigin("anthropics-claude");
        e.setInstallPath("/tmp/x");
        e.setUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
        repo.save(e);

        Optional<PluginInstallRecordEntity> found =
            repo.findByUidAndUserId("anthropics-claude:CLAUDE:browser-use",
                SecurityConstants.LOCAL_VIRTUAL_USER_ID);
        assertTrue(found.isPresent());
        assertEquals("browser-use", found.get().getPluginName());
    }
}
