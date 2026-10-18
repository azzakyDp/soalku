package id.soalku.extraction;

public final class DocumentExtractor {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public ExtractedDocument extract(String filename, byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new ExtractionFailure("FILE_TOO_LARGE", "Ukuran file maksimal 10 MB.");
        if (bytes.length == 0) throw new ExtractionFailure("EMPTY_FILE", "File tidak boleh kosong.");
        String name = filename == null ? "" : filename.toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".pdf") && !name.endsWith(".docx"))
            throw new ExtractionFailure("UNSUPPORTED_FORMAT", "Format tidak didukung. Gunakan .pdf atau .docx.");
        try {
            var result = name.endsWith(".pdf") ? PdfExtraction.read(bytes) : DocxExtraction.read(bytes);
            if (result.sections().isEmpty()) throw new ExtractionFailure("EMPTY_DOCUMENT", "Dokumen tidak memiliki teks atau gambar yang dapat diekstrak.");
            return result;
        } catch (ExtractionFailure failure) { throw failure; }
        catch (java.io.IOException | RuntimeException failure) {
            throw new ExtractionFailure("CORRUPT_DOCUMENT", "File rusak atau isinya tidak sesuai format PDF/DOCX.", failure);
        }
    }
}
