package id.soalku.persistence.material;

import id.soalku.core.Jenjang;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "materi")
public class MateriEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 255) private String judul;
    @Column(name = "file_path", nullable = false, columnDefinition = "text") private String filePath;
    @Column(name = "diunggah_oleh", nullable = false) private Long diunggahOleh;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 3) private Jenjang jenjang;
    @Column(name = "created_at", insertable = false, updatable = false, nullable = false) private Instant createdAt;
    protected MateriEntity() { }
    public MateriEntity(String judul,String filePath,Long owner,Jenjang jenjang) {
        this.judul=judul; this.filePath=filePath; this.diunggahOleh=owner; this.jenjang=jenjang;
    }
    public Long getId() { return id; }
    public String getJudul() { return judul; }
    public String getFilePath() { return filePath; }
    public Long getDiunggahOleh() { return diunggahOleh; }
    public Jenjang getJenjang() { return jenjang; }
}
