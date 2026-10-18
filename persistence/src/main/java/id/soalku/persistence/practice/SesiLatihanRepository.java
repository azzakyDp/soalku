package id.soalku.persistence.practice;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface SesiLatihanRepository extends JpaRepository<SesiLatihanEntity,Long> {
    Optional<SesiLatihanEntity> findBySiswaIdAndRequestKey(Long siswaId,UUID requestKey);
    Optional<SesiLatihanEntity> findByIdAndSiswaId(Long id,Long siswaId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SesiLatihanEntity s where s.id=:id and s.siswaId=:student")
    Optional<SesiLatihanEntity> lockOwned(@Param("id") Long id,@Param("student") Long student);
}
