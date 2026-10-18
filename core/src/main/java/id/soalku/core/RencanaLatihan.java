package id.soalku.core;

import java.time.Instant;
import java.util.List;

public record RencanaLatihan(List<Long> soalIds,Instant mulaiAt,Instant berakhirAt) {
    public RencanaLatihan { soalIds=List.copyOf(soalIds); }
    public boolean sudahBerakhir(Instant sekarang) { return berakhirAt!=null && !sekarang.isBefore(berakhirAt); }
}
