package id.soalku.extraction;

import java.io.IOException;
import java.awt.geom.Point2D;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.*;

final class PdfExtraction {
    private record Line(float y, String text, float size) { }
    private record Picture(float y, PDImage image) { }

    static ExtractedDocument read(byte[] bytes) throws IOException {
        if (bytes.length < 5 || !new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
            throw new ExtractionFailure("CORRUPT_DOCUMENT", "Isi file bukan dokumen PDF.");
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) throw new ExtractionFailure("ENCRYPTED_DOCUMENT", "PDF terenkripsi tidak didukung. Unggah PDF tanpa password.");
            if (document.getNumberOfPages() > 200) throw new ExtractionFailure("EXTRACTION_LIMIT", "PDF maksimal 200 halaman.");
            checkStreams(document);
            var output = new SectionCollector();
            boolean anyText = false;
            boolean foundHeading = false;
            int[] characterBudget = {0};
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                var lines = lines(document,pageIndex + 1,characterBudget);
                var sizes = lines.stream().filter(line -> !line.text.isBlank()).map(Line::size).sorted().toList();
                float body = sizes.isEmpty() ? 12 : sizes.get((sizes.size()-1)/2);
                boolean pageHeadings = lines.stream().anyMatch(line -> isHeading(line,body));
                if (!foundHeading && !pageHeadings) output.fallback("Halaman " + (pageIndex+1));
                var page = document.getPage(pageIndex);
                var graphics = new Images(page); graphics.processPage(page);
                int imageIndex = 0;
                graphics.pictures.sort(Comparator.comparingDouble(Picture::y));
                for (var line : lines) {
                    while (imageIndex < graphics.pictures.size() && graphics.pictures.get(imageIndex).y < line.y)
                        addImage(output,graphics.pictures.get(imageIndex++));
                    if (isHeading(line,body)) { output.heading(line.text); foundHeading = true; }
                    else output.text(line.text);
                    anyText |= !line.text.isBlank();
                }
                while (imageIndex < graphics.pictures.size()) addImage(output,graphics.pictures.get(imageIndex++));
            }
            return output.result(anyText ? List.of() : List.of("PDF tidak memiliki teks. Gambar disimpan; OCR belum tersedia."));
        } catch (InvalidPasswordException failure) {
            throw new ExtractionFailure("ENCRYPTED_DOCUMENT", "PDF dilindungi password. Unggah PDF tanpa password.",failure);
        }
    }
    private static boolean isHeading(Line line, float body) {
        return !line.text.isBlank() && line.text.length() <= 200
                && (line.size >= body * 1.2f || line.text.matches("(?i)bab\\s+[ivx0-9]+(?:\\s.*)?"));
    }
    /** Decode non-image streams through a bounded sink before PDFBox caches page/form content. */
    private static void checkStreams(PDDocument document) throws IOException {
        var cos = document.getDocument();
        if (cos.getXrefTable().size()>10000) throw pdfLimit();
        long total=0;
        for (var key : new ArrayList<>(cos.getXrefTable().keySet())) {
            if (!(cos.getObjectFromPool(key).getObject() instanceof org.apache.pdfbox.cos.COSStream stream)) continue;
            if (COSName.IMAGE.equals(stream.getCOSName(COSName.SUBTYPE))) {
                SectionCollector.checkPixels(stream.getInt(COSName.WIDTH),stream.getInt(COSName.HEIGHT));
                continue;
            }
            var filters=new ArrayList<COSName>();
            var filter=stream.getFilters();
            if (filter instanceof COSName name) filters.add(name);
            else if (filter instanceof org.apache.pdfbox.cos.COSArray array) {
                if (array.size()>4) throw pdfLimit();
                for (int i=0;i<array.size();i++) {
                    if (!(array.getObject(i) instanceof COSName name)) throw new IOException("Invalid PDF filter");
                    filters.add(name);
                }
            }
            byte[] content;
            try (var input=stream.createRawInputStream()) { content=input.readNBytes(DocumentExtractor.MAX_BYTES+1); }
            if (content.length>DocumentExtractor.MAX_BYTES) throw pdfLimit();
            for (int index=0;index<filters.size();index++) {
                var output=new java.io.ByteArrayOutputStream() {
                    private void check(int additional) { if ((long)count+additional>16L*1024*1024) throw pdfLimit(); }
                    @Override public synchronized void write(int value) { check(1);super.write(value); }
                    @Override public synchronized void write(byte[] values,int offset,int length) { check(length);super.write(values,offset,length); }
                };
                try (var input=new java.io.ByteArrayInputStream(content)) {
                    org.apache.pdfbox.filter.FilterFactory.INSTANCE.getFilter(filters.get(index)).decode(input,output,stream,index);
                }
                content=output.toByteArray();
            }
            total+=content.length;
            if (total>50L*1024*1024) throw pdfLimit();
        }
    }
    private static ExtractionFailure pdfLimit() {
        return new ExtractionFailure("EXTRACTION_LIMIT","Struktur atau isi PDF setelah dekompresi melebihi batas aman.");
    }
    private static void addImage(SectionCollector output, Picture picture) throws IOException {
        SectionCollector.checkPixels(picture.image.getWidth(),picture.image.getHeight());
        output.image(picture.image.getImage());
    }
    private static List<Line> lines(PDDocument document,int page,int[] characterBudget) throws IOException {
        var result = new ArrayList<Line>();
        var stripper = new PDFTextStripper() {
            final StringBuilder current = new StringBuilder();
            float y, size;
            @Override protected void processTextPosition(TextPosition position) {
                characterBudget[0] += position.getUnicode().length();
                if (characterBudget[0]>1_000_000)
                    throw new ExtractionFailure("EXTRACTION_LIMIT", "PDF melebihi batas 1 juta karakter.");
                super.processTextPosition(position);
            }
            private void flush() {
                if (!current.isEmpty()) result.add(new Line(y,current.toString().strip(),size));
                current.setLength(0); size = 0;
            }
            @Override protected void writeString(String text,List<TextPosition> positions) {
                if (!positions.isEmpty()) {
                    float nextY = positions.getFirst().getYDirAdj();
                    if (!current.isEmpty() && Math.abs(nextY-y)>2) flush();
                    y = nextY;
                    for (var position : positions) size = Math.max(size,position.getFontSizeInPt());
                }
                current.append(text);
            }
            @Override protected void writeWordSeparator() { current.append(' '); }
            @Override protected void writeLineSeparator() { flush(); }
            @Override protected void endPage(PDPage ignored) { flush(); }
        };
        stripper.setSortByPosition(true); stripper.setStartPage(page); stripper.setEndPage(page);
        stripper.getText(document);
        result.sort(Comparator.comparingDouble(Line::y));
        return result;
    }
    /** Traverses image drawing operations, including images inside form XObjects. */
    private static final class Images extends PDFGraphicsStreamEngine {
        final List<Picture> pictures = new ArrayList<>();
        Point2D current = new Point2D.Float();
        Images(PDPage page) { super(page); }
        @Override public void drawImage(PDImage image) {
            if (pictures.size() >= 200) throw new ExtractionFailure("EXTRACTION_LIMIT", "Terlalu banyak gambar dalam PDF.");
            var matrix = getGraphicsState().getCurrentTransformationMatrix();
            float top = 0;
            for (int x=0;x<=1;x++) for (int y=0;y<=1;y++)
                top = Math.max(top,(float) matrix.transformPoint(x,y).getY());
            pictures.add(new Picture(getPage().getCropBox().getUpperRightY()-top,image));
        }
        @Override public void appendRectangle(Point2D p0,Point2D p1,Point2D p2,Point2D p3) { current=p0; }
        @Override public void clip(int windingRule) { }
        @Override public void moveTo(float x,float y) { current=new Point2D.Float(x,y); }
        @Override public void lineTo(float x,float y) { current=new Point2D.Float(x,y); }
        @Override public void curveTo(float x1,float y1,float x2,float y2,float x3,float y3) { current=new Point2D.Float(x3,y3); }
        @Override public Point2D getCurrentPoint() { return current; }
        @Override public void closePath() { }
        @Override public void endPath() { }
        @Override public void strokePath() { }
        @Override public void fillPath(int windingRule) { }
        @Override public void fillAndStrokePath(int windingRule) { }
        @Override public void shadingFill(COSName name) { }
    }
}
