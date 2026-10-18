package id.soalku.persistence.material;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GambarMateriRepository extends JpaRepository<GambarMateriEntity,Long> {
    List<GambarMateriEntity> findByMateriIdOrderById(Long materiId);
}
