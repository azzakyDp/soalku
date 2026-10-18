package id.soalku.persistence.dashboard;

import jakarta.persistence.EntityManagerFactory;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardQueries {
    public record StudentProgress(Long siswaId,String nama,long totalMateri,long totalSesi,long sesiSelesai,
            long sesiKedaluwarsa,long totalJawaban,long jumlahBenar,Instant latihanTerakhirAt) { }
    public record SessionHistory(Long sesiId,String mode,Instant mulaiAt,Instant selesaiAt,Instant berakhirAt,
            Integer jumlahSoal,long totalJawaban,long jumlahBenar) { }
    private final JdbcTemplate jdbc;
    private final String schema;
    public DashboardQueries(JdbcTemplate jdbc,EntityManagerFactory emf) {
        this.jdbc=jdbc;
        // Use the same validated schema as JPA, including isolated integration-test schemas.
        String configured=String.valueOf(emf.getProperties().get("hibernate.default_schema"));
        if(!configured.matches("[a-zA-Z_][a-zA-Z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid Hibernate schema");
        schema="\""+configured+"\".";
    }
    public Optional<StudentProgress> student(Long id,Instant now) {
        return progress(id,1,0,now).stream().findFirst();
    }
    public List<StudentProgress> students(int size,long offset,Instant now) { return progress(null,size,offset,now); }
    public long countStudents() {
        return jdbc.queryForObject("SELECT count(*) FROM "+schema+"pengguna WHERE role=?",Long.class,"SISWA");
    }
    private List<StudentProgress> progress(Long student,int size,long offset,Instant now) {
        // Aggregate each one-to-many table independently: joining raw rows would multiply answer/session counts.
        // Only the bounded page of students participates in the aggregate joins.
        String sql="""
                WITH page_users AS (
                    SELECT id,nama FROM %spengguna WHERE role='SISWA' %s ORDER BY id LIMIT ? OFFSET ?
                ), material_stats AS (
                    SELECT m.diunggah_oleh,count(*) AS total_materi FROM %smateri m
                    JOIN page_users p ON p.id=m.diunggah_oleh GROUP BY m.diunggah_oleh
                ), session_stats AS (
                    SELECT s.siswa_id,count(*) AS total_sesi,
                        count(*) FILTER (WHERE s.selesai_at IS NOT NULL) AS sesi_selesai,
                        count(*) FILTER (WHERE s.selesai_at IS NULL AND s.berakhir_at<=?) AS sesi_kedaluwarsa,
                        max(s.mulai_at) AS latihan_terakhir
                    FROM %ssesi_latihan s JOIN page_users p ON p.id=s.siswa_id GROUP BY s.siswa_id
                ), answer_stats AS (
                    SELECT s.siswa_id,count(*) AS total_jawaban,count(*) FILTER (WHERE r.is_benar) AS jumlah_benar
                    FROM %sriwayat_jawaban r JOIN %ssesi_latihan s ON s.id=r.sesi_id
                    JOIN page_users p ON p.id=s.siswa_id GROUP BY s.siswa_id
                )
                SELECT p.id,p.nama,COALESCE(m.total_materi,0) AS total_materi,
                    COALESCE(s.total_sesi,0) AS total_sesi,COALESCE(s.sesi_selesai,0) AS sesi_selesai,
                    COALESCE(s.sesi_kedaluwarsa,0) AS sesi_kedaluwarsa,COALESCE(a.total_jawaban,0) AS total_jawaban,
                    COALESCE(a.jumlah_benar,0) AS jumlah_benar,s.latihan_terakhir
                FROM page_users p LEFT JOIN material_stats m ON m.diunggah_oleh=p.id
                LEFT JOIN session_stats s ON s.siswa_id=p.id LEFT JOIN answer_stats a ON a.siswa_id=p.id
                ORDER BY p.id
                """.formatted(schema,student==null?"":"AND id=?",schema,schema,schema,schema);
        var args=new ArrayList<Object>();if(student!=null) args.add(student);args.add(size);args.add(offset);args.add(Timestamp.from(now));
        return jdbc.query(sql,(rs,n)->new StudentProgress(rs.getLong("id"),rs.getString("nama"),rs.getLong("total_materi"),
                rs.getLong("total_sesi"),rs.getLong("sesi_selesai"),rs.getLong("sesi_kedaluwarsa"),rs.getLong("total_jawaban"),
                rs.getLong("jumlah_benar"),instant(rs,"latihan_terakhir")),args.toArray());
    }
    public List<SessionHistory> history(Long student,int size,long offset) {
        String sql="""
                SELECT s.id,s.mode,s.mulai_at,s.selesai_at,s.berakhir_at,
                    CASE WHEN s.soal_snapshot IS NULL THEN NULL ELSE jsonb_array_length(s.soal_snapshot) END AS jumlah_soal,
                    count(r.id) AS total_jawaban,count(r.id) FILTER (WHERE r.is_benar) AS jumlah_benar
                FROM %ssesi_latihan s LEFT JOIN %sriwayat_jawaban r ON r.sesi_id=s.id
                WHERE s.siswa_id=? GROUP BY s.id ORDER BY s.mulai_at DESC,s.id DESC LIMIT ? OFFSET ?
                """.formatted(schema,schema);
        return jdbc.query(sql,(rs,n)->new SessionHistory(rs.getLong("id"),rs.getString("mode"),instant(rs,"mulai_at"),
                instant(rs,"selesai_at"),instant(rs,"berakhir_at"),rs.getObject("jumlah_soal",Integer.class),
                rs.getLong("total_jawaban"),rs.getLong("jumlah_benar")),student,size,offset);
    }
    private static Instant instant(ResultSet rs,String column) throws SQLException {
        Timestamp value=rs.getTimestamp(column);return value==null?null:value.toInstant();
    }
}
