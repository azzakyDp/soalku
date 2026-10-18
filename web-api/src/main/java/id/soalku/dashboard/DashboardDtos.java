package id.soalku.dashboard;

import id.soalku.review.ReviewDtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class DashboardDtos {
    private DashboardDtos() { }
    public record Page<T>(List<T> content,int page,int size,long totalElements,int totalPages) { }
    public record Progress(Long siswaId,String nama,long totalMateri,long totalSesi,long sesiSelesai,long sesiBerjalan,
            long sesiKedaluwarsa,long totalJawaban,long jumlahBenar,long jumlahSalah,BigDecimal persentaseBenar,
            BigDecimal persentaseSesiSelesai,Instant latihanTerakhirAt) { }
    public record History(Long sesiId,String mode,String status,Instant mulaiAt,Instant selesaiAt,Instant berakhirAt,
            Integer jumlahSoal,long totalJawaban,long jumlahBenar,BigDecimal persentaseBenar,BigDecimal persentasePenyelesaian) { }
    public record Student(Progress progres,Page<History> riwayat) { }
    public record Mentor(long totalMenungguReview,long totalSiswa,ReviewDtos.Queue antreanReview,Page<Progress> siswa) { }
}
