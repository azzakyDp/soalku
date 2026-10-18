package id.soalku.persistence.practice;

import id.soalku.core.*;
import jakarta.persistence.EntityManagerFactory;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LatihanCandidateRepository {
    private final JdbcTemplate jdbc;
    private final String schema;
    public LatihanCandidateRepository(JdbcTemplate jdbc,EntityManagerFactory emf) {
        this.jdbc=jdbc;
        // Match Hibernate's actual schema, including per-test overrides. Never interpolate request input.
        String configured=String.valueOf(emf.getProperties().get("hibernate.default_schema"));
        if(!configured.matches("[a-zA-Z_][a-zA-Z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid Hibernate schema");
        schema="\""+configured+"\".";
    }
    /** Caller holds a transaction and closes this cursor; only 128 rows are fetched at a time. */
    public Stream<KandidatSoal> approvedForStudent(Long student,boolean focus) {
        String history=focus?"""
                LEFT JOIN (
                    SELECT old.bagian_materi_id, count(*) AS total, count(*) FILTER (WHERE NOT r.is_benar) AS salah
                    FROM %sriwayat_jawaban r JOIN %ssesi_latihan s ON s.id=r.sesi_id
                    JOIN %ssoal old ON old.id=r.soal_id WHERE s.siswa_id=? GROUP BY old.bagian_materi_id
                ) h ON h.bagian_materi_id=q.bagian_materi_id
                """.formatted(schema,schema,schema):"";
        String stats=focus?"COALESCE(h.total,0),COALESCE(h.salah,0)":"0::bigint,0::bigint";
        String sql="SELECT q.id,q.bagian_materi_id,"+stats+" FROM "+schema+"soal q "+history+" WHERE q.status_review='DISETUJUI'";
        return jdbc.queryForStream(connection->{
            var statement=connection.prepareStatement(sql);statement.setFetchSize(128);statement.setQueryTimeout(5);
            if(focus) statement.setLong(1,student);return statement;
        },(row,n)->new KandidatSoal(row.getLong(1),row.getLong(2),StatusReview.DISETUJUI,row.getLong(3),row.getLong(4)));
    }
}
