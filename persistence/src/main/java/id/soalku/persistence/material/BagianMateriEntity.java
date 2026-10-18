package id.soalku.persistence.material;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bagian_materi")
public class BagianMateriEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "materi_id",nullable = false) private Long materiId;
    @Column(name = "judul_bagian",nullable = false,length = 255) private String judulBagian;
    @Column(name = "teks_asli",nullable = false,columnDefinition = "text") private String teksAsli;
    @Column(name = "teks_ringkasan",columnDefinition = "text") private String teksRingkasan;
    @Column(nullable = false) private int urutan;
    @Column(name = "generated_at") private Instant generatedAt;
    @Column(name = "generation_token") private UUID generationToken;
    @Column(name = "generation_started_at") private Instant generationStartedAt;
    @Column(name = "created_at",insertable = false,updatable = false,nullable = false) private Instant createdAt;
    protected BagianMateriEntity() { }
    public BagianMateriEntity(Long materiId,String judul,String teks,int urutan) {
        this.materiId=materiId; this.judulBagian=judul; this.teksAsli=teks; this.urutan=urutan;
    }
    public Long getId() { return id; }
    public String getJudulBagian() { return judulBagian; }
    public String getTeksAsli() { return teksAsli; }
    public int getUrutan() { return urutan; }
    public Long getMateriId() { return materiId; }
    public String getTeksRingkasan() { return teksRingkasan; }
    public Instant getGeneratedAt() { return generatedAt; }
    public UUID getGenerationToken() { return generationToken; }
    public Instant getGenerationStartedAt() { return generationStartedAt; }
    public void claimGeneration(UUID token,Instant started) { generationToken=token; generationStartedAt=started; }
    public void releaseGeneration() { generationToken=null; generationStartedAt=null; }
    public void completeGeneration(String summary,Instant completed) {
        teksRingkasan=summary; generatedAt=completed; releaseGeneration();
    }
}
