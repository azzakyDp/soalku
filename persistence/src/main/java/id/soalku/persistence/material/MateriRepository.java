package id.soalku.persistence.material;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MateriRepository extends JpaRepository<MateriEntity,Long> {
    Optional<MateriEntity> findByFilePath(String path);
}
