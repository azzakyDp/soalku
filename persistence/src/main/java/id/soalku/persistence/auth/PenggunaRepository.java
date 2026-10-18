package id.soalku.persistence.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PenggunaRepository extends JpaRepository<PenggunaEntity, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PenggunaEntity p where p.id = :id")
    Optional<PenggunaEntity> lockForUpload(@Param("id") Long id);
    // Matches the lower(email) unique index in V1; caller supplies normalized email.
    @Query("select p from PenggunaEntity p where lower(p.email) = :email")
    Optional<PenggunaEntity> findByNormalizedEmail(@Param("email") String email);
}
