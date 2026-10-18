package id.soalku.persistence.practice;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SesiLatihanRepository extends JpaRepository<SesiLatihanEntity,Long> {
    Optional<SesiLatihanEntity> findBySiswaIdAndRequestKey(Long siswaId,UUID requestKey);
}
