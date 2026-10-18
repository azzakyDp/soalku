package id.soalku.persistence.question;

import id.soalku.core.OpsiJawaban;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="opsi_jawaban")
public class OpsiJawabanEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="soal_id",nullable=false) private Long soalId;
    @Column(name="teks_opsi",nullable=false,columnDefinition="text") private String teksOpsi;
    @Column(nullable=false) private short urutan;
    @Column(name="is_benar",nullable=false) private boolean benar;
    @Column(name="created_at",nullable=false,insertable=false,updatable=false) private Instant createdAt;
    protected OpsiJawabanEntity() { }
    public OpsiJawabanEntity(Long soalId,OpsiJawaban option) {
        this.soalId=soalId;teksOpsi=option.teks();urutan=(short)option.urutan();benar=option.benar();
    }
}
