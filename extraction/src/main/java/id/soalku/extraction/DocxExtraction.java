package id.soalku.extraction;

import java.io.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.apache.poi.xwpf.usermodel.*;

final class DocxExtraction {
    static ExtractedDocument read(byte[] bytes) throws IOException {
        // Bound expanded ZIP content before POI allocates XML/package objects. No entry is written to disk.
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            int entries = 0; long expanded = 0; var buffer = new byte[8192];
            while (zip.getNextEntry() != null) {
                if (++entries > 2000) throw limit();
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expanded += read;
                    if (expanded > 50L * 1024 * 1024) throw limit();
                }
            }
        }
        var collector = new SectionCollector();
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var referenced = Collections.newSetFromMap(new IdentityHashMap<XWPFPictureData, Boolean>());
            visit(document.getBodyElements(),collector,referenced);
            for (var header : document.getHeaderList()) visit(header.getBodyElements(),collector,referenced);
            for (var footer : document.getFooterList()) visit(footer.getBodyElements(),collector,referenced);
            var warnings = new ArrayList<String>();
            for (var picture : document.getAllPictures()) {
                if (!referenced.contains(picture)) {
                    collector.heading("Gambar tanpa heading"); collector.image(picture.getData());
                    warnings.add("Gambar tanpa posisi paragraf ditempatkan pada bagian terpisah.");
                }
            }
            return collector.result(warnings);
        }
    }
    private static void visit(List<IBodyElement> elements, SectionCollector output,
                              Set<XWPFPictureData> referenced) throws IOException {
        for (var element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                String value = paragraph.getText();
                String style = paragraph.getStyle();
                boolean heading = style != null && style.matches("(?i)(heading|judul)[ _-]?[1-9]|title");
                var properties = paragraph.getCTP().getPPr();
                heading |= properties != null && properties.isSetOutlineLvl()
                        && properties.getOutlineLvl().getVal().intValue() < 9;
                if (heading && !value.isBlank()) output.heading(value);
                else output.text(value);
                for (var run : paragraph.getRuns()) for (var picture : run.getEmbeddedPictures()) {
                    var data = picture.getPictureData();
                    if (data == null) throw new ExtractionFailure("CORRUPT_DOCUMENT", "Referensi gambar DOCX tidak valid.");
                    referenced.add(data); output.image(data.getData());
                }
            } else if (element instanceof XWPFTable table) {
                for (var row : table.getRows()) for (var cell : row.getTableCells())
                    visit(cell.getBodyElements(),output,referenced);
            }
        }
    }
    private static ExtractionFailure limit() {
        return new ExtractionFailure("EXTRACTION_LIMIT", "Isi DOCX setelah dekompresi melebihi batas aman (50 MB/2000 entri).");
    }
}
