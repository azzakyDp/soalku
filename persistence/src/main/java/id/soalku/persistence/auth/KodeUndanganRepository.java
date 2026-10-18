package id.soalku.persistence.auth;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KodeUndanganRepository extends JpaRepository<KodeUndanganEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from KodeUndanganEntity k where k.kode = :kode")
    Optional<KodeUndanganEntity> findForRegistration(@Param("kode") String kode);
}
