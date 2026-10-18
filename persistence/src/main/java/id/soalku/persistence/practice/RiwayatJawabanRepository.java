package id.soalku.persistence.practice;

import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RiwayatJawabanRepository extends JpaRepository<RiwayatJawabanEntity,Long> {
    Optional<RiwayatJawabanEntity> findBySesiIdAndSoalId(Long sesiId,Long soalId);
    long countBySesiId(Long sesiId);

    // Aggregation, ordering and pagination happen in SQL, not by loading answers or looping over sections.
    // Order by the unrounded ratio; the domain rounds only the displayed percentage.
    @Query(value="""
            select b.id as bagianMateriId,b.judulBagian as judulBagian,
                   count(r.id) as totalJawaban,sum(case when r.benar=true then 1 else 0 end) as jumlahBenar
            from RiwayatJawabanEntity r
            join SesiLatihanEntity s on s.id=r.sesiId
            join SoalEntity q on q.id=r.soalId
            join BagianMateriEntity b on b.id=q.bagianMateriId
            where s.siswaId=:student and (:sessionId is null or s.id=:sessionId)
            group by b.id,b.judulBagian
            order by (1.0 * sum(case when r.benar=true then 1 else 0 end) / count(r.id)) asc,b.id asc
            """,countQuery="""
            select count(distinct q.bagianMateriId) from RiwayatJawabanEntity r
            join SesiLatihanEntity s on s.id=r.sesiId
            join SoalEntity q on q.id=r.soalId
            where s.siswaId=:student and (:sessionId is null or s.id=:sessionId)
            """)
    Page<EvaluasiBagianProjection> aggregateBySection(@Param("student") Long student,
            @Param("sessionId") Long sessionId,Pageable page);
}
