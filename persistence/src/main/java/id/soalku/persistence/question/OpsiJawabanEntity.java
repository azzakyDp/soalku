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
    public Long getId() { return id; }
    public Long getSoalId() { return soalId; }
    public String getTeksOpsi() { return teksOpsi; }
    public short getUrutan() { return urutan; }
    public boolean isBenar() { return benar; }
    public void edit(String text,boolean correct) { teksOpsi=text;benar=correct; }
}
