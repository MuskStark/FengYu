package fan.summer.fengyu.database.repository;

import fan.summer.fengyu.database.entity.store.PluginInstallRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginInstallRecordRepository extends JpaRepository<PluginInstallRecordEntity, Integer> {
    Optional<PluginInstallRecordEntity> findByUidAndUserId(String uid, Long userId);
    List<PluginInstallRecordEntity> findAllByUserIdOrderByInstalledAtDesc(Long userId);
    void deleteByUidAndUserId(String uid, Long userId);
}
