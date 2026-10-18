package id.soalku.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Counts come from one grouped database query; unanswered sections are not assigned a score. */
public record EvaluasiBagian(long bagianMateriId,String judulBagian,long totalJawaban,long jumlahBenar) {
    public EvaluasiBagian {
        if(bagianMateriId<1 || judulBagian==null || judulBagian.isBlank() || totalJawaban<1 || jumlahBenar<0 || jumlahBenar>totalJawaban)
            throw new IllegalArgumentException("Invalid evaluation statistics");
    }
    public long jumlahSalah() { return totalJawaban-jumlahBenar; }
    public BigDecimal persentaseBenar() {
        return BigDecimal.valueOf(jumlahBenar).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalJawaban),2,RoundingMode.HALF_UP);
    }
    public boolean perluDiulang() { return jumlahSalah()>0; }
}
