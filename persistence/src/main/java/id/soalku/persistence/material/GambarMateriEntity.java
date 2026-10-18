package id.soalku.persistence.material;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "gambar_materi")
public class GambarMateriEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "materi_id",nullable = false) private Long materiId;
    @Column(name = "bagian_materi_id") private Long bagianMateriId;
    @Column(name = "file_path",nullable = false,columnDefinition = "text") private String filePath;
    @Column(name = "created_at",insertable = false,updatable = false,nullable = false) private Instant createdAt;
    protected GambarMateriEntity() { }
    public GambarMateriEntity(Long materiId,Long bagianId,String path) {
        this.materiId=materiId; this.bagianMateriId=bagianId; this.filePath=path;
    }
    public Long getId() { return id; }
    public Long getBagianMateriId() { return bagianMateriId; }
    public String getFilePath() { return filePath; }
}
