package id.soalku.persistence.practice;

import id.soalku.core.ModeLatihan;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="sesi_latihan")
public class SesiLatihanEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="siswa_id",nullable=false) private Long siswaId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private ModeLatihan mode;
    @Column(name="mulai_at",nullable=false) private Instant mulaiAt;
    @Column(name="selesai_at") private Instant selesaiAt;
    @Column(name="berakhir_at") private Instant berakhirAt;
    @Column(name="request_key") private UUID requestKey;
    @Column(name="requested_count") private Short requestedCount;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="soal_snapshot",columnDefinition="jsonb") private String soalSnapshot;
    @Column(name="created_at",nullable=false,insertable=false,updatable=false) private Instant createdAt;
    protected SesiLatihanEntity() { }
    public SesiLatihanEntity(Long owner,ModeLatihan mode,UUID key,int requested,Instant start,Instant end,String snapshot) {
        siswaId=owner;this.mode=mode;requestKey=key;requestedCount=(short)requested;mulaiAt=start;berakhirAt=end;soalSnapshot=snapshot;
    }
    public Long getId() { return id; }
    public ModeLatihan getMode() { return mode; }
    public int getRequestedCount() { return requestedCount; }
    public Instant getMulaiAt() { return mulaiAt; }
    public Instant getBerakhirAt() { return berakhirAt; }
    public String getSoalSnapshot() { return soalSnapshot; }
    public Instant getSelesaiAt() { return selesaiAt; }
    public void complete(Instant at) {
        if(at==null || at.isBefore(mulaiAt)) throw new IllegalArgumentException("Invalid completion timestamp");
        if(selesaiAt==null) selesaiAt=at;
    }
}
