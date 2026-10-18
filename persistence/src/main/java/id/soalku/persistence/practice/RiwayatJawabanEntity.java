package id.soalku.persistence.practice;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="riwayat_jawaban")
public class RiwayatJawabanEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="sesi_id",nullable=false) private Long sesiId;
    @Column(name="soal_id",nullable=false) private Long soalId;
    @Column(name="opsi_dipilih",nullable=false) private Long opsiDipilih;
    @Column(name="is_benar",nullable=false) private boolean benar;
    @Column(name="dijawab_at",nullable=false) private Instant dijawabAt;
    @Column(name="created_at",nullable=false,insertable=false,updatable=false) private Instant createdAt;
    protected RiwayatJawabanEntity() { }
    public RiwayatJawabanEntity(Long session,Long question,Long option,boolean correct,Instant at) {
        sesiId=session;soalId=question;opsiDipilih=option;benar=correct;dijawabAt=at;
    }
    public Long getId() { return id; }
    public Long getSesiId() { return sesiId; }
    public Long getSoalId() { return soalId; }
    public Long getOpsiDipilih() { return opsiDipilih; }
    public boolean isBenar() { return benar; }
    public Instant getDijawabAt() { return dijawabAt; }
}
