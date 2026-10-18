package id.soalku.persistence.question;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SoalRepository extends JpaRepository<SoalEntity,Long> {
    List<SoalEntity> findByBagianMateriIdOrderByUrutanGenerasi(Long bagianId);
}
