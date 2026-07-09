package fan.summer.zhiflow.database;

import fan.summer.zhiflow.ai.spring.AiApplication;
import fan.summer.zhiflow.database.entity.SysUserEntity;
import fan.summer.zhiflow.database.repository.SysUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the virtual user (id=1, ZFlow-Summer) is created on APP-mode context start.
 *
 * <p>Boots the full {@link AiApplication} context with the {@code test} profile, which provides an
 * in-memory H2 datasource via Spring's standard {@code DataSourceAutoConfiguration} (the profile does
 * NOT set {@code zhiflow.mode=app}, so the conditional APP-mode beans like {@code DataSourceAutoConfig}
 * stay inactive and the context loads without a real {@code datasource.properties}). The explicit
 * {@code classes} is required because this test lives in a subpackage of {@code database/} where the
 * upward {@code @SpringBootConfiguration} search cannot find {@code AiApplication} (which is in
 * {@code ai.spring}).
 */
@SpringBootTest(classes = AiApplication.class)
@ActiveProfiles("test")
@Transactional
class VirtualUserInitializerTest {

    @Autowired
    private SysUserRepository sysUserRepo;

    @Autowired
    private VirtualUserInitializer initializer;

    @Test
    void ensureVirtualUser_createsIdOneWithCorrectAttributes() {
        initializer.ensureVirtualUser();

        SysUserEntity u = sysUserRepo.findById(SecurityConstants.LOCAL_VIRTUAL_USER_ID)
                .orElseThrow(() -> new AssertionError("virtual user id=1 not found"));
        assertEquals(SecurityConstants.LOCAL_VIRTUAL_USERNAME, u.getUsername());
        assertEquals(1, u.getStatus());
        assertEquals(1, u.getUserType());
        assertEquals("local", u.getAuthProvider());
        assertNull(u.getPasswordHash());
    }

    @Test
    void ensureVirtualUser_idempotent_doesNotDuplicate() {
        initializer.ensureVirtualUser();
        initializer.ensureVirtualUser();   // second call should be a no-op

        long count = sysUserRepo.findAll().stream()
                .filter(u -> u.getId() == SecurityConstants.LOCAL_VIRTUAL_USER_ID)
                .count();
        assertEquals(1, count);
    }
}
