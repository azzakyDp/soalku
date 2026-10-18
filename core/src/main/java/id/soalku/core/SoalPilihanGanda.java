package id.soalku.core;

import java.util.*;

public final class SoalPilihanGanda extends Soal {
    private final List<OpsiJawaban> opsi;
    public SoalPilihanGanda(Jenjang jenjang, String pertanyaan, List<OpsiJawaban> opsi, Long gambarId) {
        super(pertanyaan, gambarId);
        this.opsi = List.copyOf(opsi);
        if (jenjang == null || this.opsi.size() != (jenjang == Jenjang.SD ? 3 : 4)
                || this.opsi.stream().filter(OpsiJawaban::benar).count() != 1
                || this.opsi.stream().map(option -> option.teks().toLowerCase(Locale.ROOT)).distinct().count() != this.opsi.size())
            throw new IllegalArgumentException("Jumlah opsi/kunci jawaban tidak sesuai jenjang.");
        for (int index=0;index<this.opsi.size();index++)
            if (this.opsi.get(index).urutan()!=index+1) throw new IllegalArgumentException("Urutan opsi harus berurutan.");
    }
    @Override public List<OpsiJawaban> getOpsi() { return opsi; }
}
