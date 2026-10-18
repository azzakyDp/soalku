package id.soalku.persistence.question;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OpsiJawabanRepository extends JpaRepository<OpsiJawabanEntity,Long> {
    Optional<OpsiJawabanEntity> findByIdAndSoalId(Long id,Long soalId);
    List<OpsiJawabanEntity> findBySoalIdOrderByUrutan(Long soalId);
    List<OpsiJawabanEntity> findBySoalIdInOrderBySoalIdAscUrutanAsc(Collection<Long> soalIds);
}
