package id.soalku.core;

import java.util.List;

public abstract class Soal {
    private final String pertanyaan;
    private final Long gambarId;
    protected Soal(String pertanyaan, Long gambarId) {
        if (pertanyaan == null || pertanyaan.isBlank() || pertanyaan.strip().length() > 3000
                || (gambarId != null && gambarId < 1)) throw new IllegalArgumentException("Pertanyaan/gambar tidak valid.");
        this.pertanyaan = pertanyaan.strip();
        this.gambarId = gambarId;
    }
    public String getPertanyaan() { return pertanyaan; }
    public Long getGambarId() { return gambarId; }
    public StatusReview getStatusReview() { return StatusReview.MENUNGGU; }
    public abstract List<OpsiJawaban> getOpsi();
}
