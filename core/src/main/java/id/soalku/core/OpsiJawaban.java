package id.soalku.core;

public record OpsiJawaban(String teks, int urutan, boolean benar) {
    public OpsiJawaban {
        if (teks == null || teks.isBlank() || teks.strip().length() > 1000 || urutan < 1 || urutan > 4)
            throw new IllegalArgumentException("Opsi wajib berisi teks dan urutan 1 sampai 4.");
        teks = teks.strip();
    }
}
