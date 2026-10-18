package id.soalku.practice;

import id.soalku.core.ModeLatihan;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.math.BigDecimal;

public final class PracticeDtos {
    private PracticeDtos() { }
    public record StartRequest(@NotNull ModeLatihan mode,@NotNull @Min(1) @Max(50) Integer jumlahSoal) { }
    public record Option(Long id,int urutan,String teks) { }
    public record Question(Long id,Long bagianMateriId,String pertanyaan,Long gambarId,List<Option> opsi) { }
    public record Session(Long sesiId,ModeLatihan mode,Instant mulaiAt,Instant berakhirAt,List<Question> soal) { }
    public record SubmitRequest(@NotNull @Positive Long soalId,@NotNull @Positive Long opsiId) { }
    public record Answer(Long id,Long sesiId,Long soalId,Long opsiDipilih,boolean benar,Instant dijawabAt) { }
    public record SectionEvaluation(Long bagianMateriId,String judulBagian,long totalJawaban,long jumlahBenar,
            long jumlahSalah,BigDecimal persentaseBenar,boolean perluDiulang,String rekomendasi) { }
    public record Evaluation(Long sesiId,List<SectionEvaluation> content,int page,int size,long totalElements,int totalPages) { }
}
