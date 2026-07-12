package fan.summer.fengyu.database.repository.email;

import fan.summer.fengyu.database.entity.email.EmailArchiveEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Date;
import java.util.List;
import java.util.Optional;

public interface EmailArchiveRepository extends JpaRepository<EmailArchiveEntity, Integer> {

    Optional<EmailArchiveEntity> findByUserIdAndAccountEmailAndFolderAndMessageUid(
            Long userId, String accountEmail, String folder, String messageUid);

    /**
     * Flexible search across archived emails for a user. All filter params are optional
     * (null = no filter). Ordered by send_date desc, limited to 100 results.
     */
    @Query("SELECT e FROM EmailArchiveEntity e WHERE e.userId = :userId " +
           "AND (:accountEmail IS NULL OR e.accountEmail = :accountEmail) " +
           "AND (:fromAddress IS NULL OR e.fromAddress LIKE CONCAT('%', :fromAddress, '%')) " +
           "AND (:subject IS NULL OR e.subject LIKE CONCAT('%', :subject, '%')) " +
           "AND (:startDate IS NULL OR e.sendDate >= :startDate) " +
           "AND (:endDate IS NULL OR e.sendDate <= :endDate) " +
           "ORDER BY e.sendDate DESC LIMIT 100")
    List<EmailArchiveEntity> searchByQuery(@Param("userId") Long userId,
                                           @Param("accountEmail") String accountEmail,
                                           @Param("fromAddress") String fromAddress,
                                           @Param("subject") String subject,
                                           @Param("startDate") Date startDate,
                                           @Param("endDate") Date endDate);
}
