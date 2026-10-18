package id.soalku.persistence.material;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BagianMateriRepository extends JpaRepository<BagianMateriEntity,Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select b from BagianMateriEntity b where b.id = :id")
    java.util.Optional<BagianMateriEntity> lockForGeneration(@org.springframework.data.repository.query.Param("id") Long id);
    List<BagianMateriEntity> findByMateriIdOrderByUrutan(Long materiId);
}
