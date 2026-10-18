package id.soalku.persistence.question;

import id.soalku.core.Soal;
import id.soalku.core.StatusReview;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="soal")
public class SoalEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="bagian_materi_id",nullable=false) private Long bagianMateriId;
    @Column(nullable=false,length=30) private String tipe="PILIHAN_GANDA";
    @Column(nullable=false,columnDefinition="text") private String pertanyaan;
    @Column(name="gambar_id") private Long gambarId;
    @Enumerated(EnumType.STRING) @Column(name="status_review",nullable=false,length=20) private StatusReview statusReview=StatusReview.MENUNGGU;
    @Column(name="direview_oleh") private Long direviewOleh;
    @Column(name="direview_at") private Instant direviewAt;
    @Column(name="urutan_generasi") private Integer urutanGenerasi;
    @Column(name="created_at",nullable=false,insertable=false,updatable=false) private Instant createdAt;
    protected SoalEntity() { }
    public SoalEntity(Long section,Soal question,int order) {
        bagianMateriId=section; pertanyaan=question.getPertanyaan();gambarId=question.getGambarId();urutanGenerasi=order;
    }
    public Long getId() { return id; }
    public Long getBagianMateriId() { return bagianMateriId; }
}
