package id.soalku.extraction;

import java.util.ArrayList;
import java.util.List;
import java.io.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Bounded output shared by both parsers; images are normalized to PNG. */
final class SectionCollector {
    private final List<ExtractedDocument.Section> sections = new ArrayList<>();
    private final List<byte[]> images = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private String title;
    private boolean explicitHeading;
    private int characters;
    private int imageCount;
    private long imageBytes;

    void heading(String value) {
        start(value,true);
    }
    void fallback(String value) { start(value,false); }
    private void start(String value,boolean explicit) {
        finish();
        title = value.strip();
        explicitHeading=explicit;
        if (title.length() > 255) title = title.substring(0,255);
        if (sections.size() >= 200) limit();
    }
    void ensure(String fallback) { if (title == null) fallback(fallback); }
    void text(String value) {
        if (value.isBlank()) return;
        ensure("Bagian awal");
        characters += value.length();
        if (characters > 1_000_000) limit();
        text.append(value.strip()).append('\n');
    }
    void image(byte[] input) throws IOException {
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new ExtractionFailure("UNSUPPORTED_IMAGE", "Format gambar tertanam tidak dapat dibaca.");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                checkPixels(reader.getWidth(0), reader.getHeight(0));
                image(reader.read(0));
            } finally { reader.dispose(); }
        }
    }
    void image(java.awt.image.BufferedImage image) throws IOException {
        checkPixels(image.getWidth(),image.getHeight());
        ensure("Bagian awal");
        if (++imageCount > 200) limit();
        var out = new ByteArrayOutputStream();
        if (!ImageIO.write(image,"png",out)) throw new IOException("PNG encoder unavailable");
        byte[] png = out.toByteArray();
        imageBytes += png.length;
        if (imageBytes > 25L * 1024 * 1024) limit();
        images.add(png);
    }
    static void checkPixels(int width, int height) {
        if (width < 1 || height < 1 || (long) width * height > 10_000_000) limit();
    }
    private static void limit() {
        throw new ExtractionFailure("EXTRACTION_LIMIT", "Dokumen melebihi batas ekstraksi (200 bagian/gambar, 1 juta karakter, atau resolusi gambar).");
    }
    private void finish() {
        if (title != null && (explicitHeading || !text.isEmpty() || !images.isEmpty()))
            sections.add(new ExtractedDocument.Section(title,text.toString().strip(),new ArrayList<>(images)));
        text.setLength(0); images.clear();
    }
    ExtractedDocument result(List<String> warnings) { finish(); return new ExtractedDocument(sections,warnings); }
}
