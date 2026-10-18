package id.soalku.persistence.question;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface OpsiJawabanRepository extends JpaRepository<OpsiJawabanEntity,Long> {
    List<OpsiJawabanEntity> findBySoalIdOrderByUrutan(Long soalId);
    List<OpsiJawabanEntity> findBySoalIdInOrderBySoalIdAscUrutanAsc(Collection<Long> soalIds);
}
