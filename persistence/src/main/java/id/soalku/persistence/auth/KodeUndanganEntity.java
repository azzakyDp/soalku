package id.soalku.persistence.auth;

import java.time.Instant;
import jakarta.persistence.*;

@Entity
@Table(name = "kode_undangan")
public class KodeUndanganEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String kode;
    @Column(name = "dibuat_oleh", nullable = false)
    private Long dibuatOleh;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "expired_at", nullable = false)
    private Instant expiredAt;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected KodeUndanganEntity() { }

    public boolean isUsableAt(Instant now) {
        return "AKTIF".equals(status) && expiredAt.isAfter(now);
    }
    public Long getDibuatOleh() { return dibuatOleh; }
    public void markUsed() { status = "DIGUNAKAN"; }
}
