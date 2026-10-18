package id.soalku.practice;

import id.soalku.core.ModeLatihan;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class PracticeDtos {
    private PracticeDtos() { }
    public record StartRequest(@NotNull ModeLatihan mode,@NotNull @Min(1) @Max(50) Integer jumlahSoal) { }
    public record Option(Long id,int urutan,String teks) { }
    public record Question(Long id,Long bagianMateriId,String pertanyaan,Long gambarId,List<Option> opsi) { }
    public record Session(Long sesiId,ModeLatihan mode,Instant mulaiAt,Instant berakhirAt,List<Question> soal) { }
}
