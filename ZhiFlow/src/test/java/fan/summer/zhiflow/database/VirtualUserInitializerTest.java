package fan.summer.zhiflow.database;

import fan.summer.zhiflow.database.entity.SysUserEntity;
import fan.summer.zhiflow.database.repository.SysUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
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
