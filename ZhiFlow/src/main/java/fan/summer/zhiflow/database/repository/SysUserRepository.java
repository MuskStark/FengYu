package fan.summer.zhiflow.database.repository;

import fan.summer.zhiflow.database.entity.SysUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUserEntity, Long> {
    Optional<SysUserEntity> findByUsername(String username);
    boolean existsById(Long id);
}
