package fan.summer.zhiflow.database;

import fan.summer.zhiflow.database.entity.SysUserEntity;
import fan.summer.zhiflow.database.repository.SysUserRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Ensures the local virtual user (id=1, "ZFlow-Summer") exists after the Spring context
 * starts in APP mode. All unauthenticated (local offline) requests are attributed to this user.
 *
 * <p>The id is fixed at 1 via a native INSERT because Hibernate's IDENTITY generation ignores
 * explicit ids set on entities. We use {@link EntityManager#createNativeQuery} to insert the
 * row with an explicit id, then the virtual user is stable across restarts.
 */
@Component
public class VirtualUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VirtualUserInitializer.class);

    private final SysUserRepository sysUserRepo;
    private final EntityManager entityManager;

    public VirtualUserInitializer(SysUserRepository sysUserRepo, EntityManager entityManager) {
        this.sysUserRepo = sysUserRepo;
        this.entityManager = entityManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureVirtualUser();
    }

    /**
     * Public for testing. Idempotent — safe to call multiple times.
     */
    public void ensureVirtualUser() {
        if (sysUserRepo.existsById(SecurityConstants.LOCAL_VIRTUAL_USER_ID)) {
            return;
        }
        log.info("Creating local virtual user (id=1, username={})", SecurityConstants.LOCAL_VIRTUAL_USERNAME);
        // Native insert to force id=1 (IDENTITY strategy would otherwise assign its own id).
        entityManager.createNativeQuery(
                "INSERT INTO sys_user (id, username, password_hash, auth_provider, status, user_type) " +
                "VALUES (?, ?, NULL, ?, ?, ?)")
                .setParameter(1, SecurityConstants.LOCAL_VIRTUAL_USER_ID)
                .setParameter(2, SecurityConstants.LOCAL_VIRTUAL_USERNAME)
                .setParameter(3, "local")
                .setParameter(4, 1)
                .setParameter(5, 1)
                .executeUpdate();
        entityManager.clear();
        log.info("Local virtual user created");
    }
}
