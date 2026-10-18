package id.soalku.core;

import java.util.List;

/** Framework-independent port; one request corresponds to one complete material section. */
public interface QuestionGeneratorService {
    GeneratedSection generate(Request request);

    record Image(Long id, byte[] png) {
        public Image {
            if (id == null || id < 1 || png == null || png.length == 0) throw new IllegalArgumentException("Invalid image");
            png = png.clone();
        }
        @Override public byte[] png() { return png.clone(); }
    }
    record Request(Jenjang jenjang, String judul, String teks, List<Image> gambar, int jumlahSoal) {
        public Request {
            if (jenjang == null || judul == null || teks == null || teks.isBlank() || teks.length() > 50_000
                    || jumlahSoal < 1 || jumlahSoal > 10) throw new IllegalArgumentException("Invalid generation input");
            gambar = List.copyOf(gambar);
            if (gambar.stream().map(Image::id).distinct().count() != gambar.size()
                    || gambar.size() > 200 || gambar.stream().mapToLong(image -> image.png.length).sum() > 10L*1024*1024)
                throw new IllegalArgumentException("Generation images exceed limits");
        }
        public int jumlahOpsi() { return jenjang == Jenjang.SD ? 3 : 4; }
    }
    record GeneratedSection(String ringkasan, List<Soal> soal) {
        public GeneratedSection {
            if (ringkasan == null || ringkasan.isBlank() || ringkasan.strip().length() > 3000
                    || soal == null || soal.isEmpty() || soal.size() > 10) throw new IllegalArgumentException("Invalid generated section");
            ringkasan = ringkasan.strip(); soal = List.copyOf(soal);
        }
    }
}
