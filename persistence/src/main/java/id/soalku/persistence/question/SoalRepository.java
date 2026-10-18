package id.soalku.persistence.question;

import java.util.List;
import java.util.Optional;
import id.soalku.core.Jenjang;
import id.soalku.core.StatusReview;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SoalRepository extends JpaRepository<SoalEntity,Long> {
    List<SoalEntity> findByBagianMateriIdOrderByUrutanGenerasi(Long bagianId);
    Page<SoalEntity> findByStatusReview(StatusReview status,Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SoalEntity s where s.id = :id")
    Optional<SoalEntity> lockForReview(@Param("id") Long id);
    @Query("select m.jenjang from BagianMateriEntity b, MateriEntity m where b.materiId=m.id and b.id=:section")
    Optional<Jenjang> findJenjang(@Param("section") Long section);
}
