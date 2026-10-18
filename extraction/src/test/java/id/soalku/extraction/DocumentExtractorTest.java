package id.soalku.extraction;

import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.util.Units;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentExtractorTest {
    private final DocumentExtractor extractor = new DocumentExtractor();

    @Test void docxHeadingsTextTableAndImageAreAssociated() throws Exception {
        var result = extractor.extract("pelajaran.docx", docx());
        assertEquals(2, result.sections().size());
        assertEquals("Bab Pertama", result.sections().getFirst().title());
        assertTrue(result.sections().getFirst().text().contains("Isi pertama"));
        assertTrue(result.sections().get(1).text().contains("Isi tabel"));
        assertEquals(0, result.sections().getFirst().images().size());
        assertEquals(1, result.sections().get(1).images().size());
        assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(result.sections().get(1).images().getFirst())));
    }

    @Test void pdfHeadingsAndImageAreAssociated() throws Exception {
        var result = extractor.extract("pelajaran.pdf", pdf(true));
        assertEquals(2, result.sections().size());
        assertEquals("Bab Pertama", result.sections().getFirst().title());
        assertTrue(result.sections().getFirst().text().contains("Isi pertama"));
        assertEquals(1, result.sections().get(1).images().size());
    }

    @Test void plainPdfFallsBackToPages() throws Exception {
        var result = extractor.extract("plain.pdf", pdf(false));
        assertEquals(1, result.sections().size());
        assertEquals("Halaman 1", result.sections().getFirst().title());
        assertTrue(result.sections().getFirst().text().contains("Isi pertama"));
    }
    @Test void headingOnlyIsStillExtractableButBlankDocumentIsRejected() throws Exception {
        try(var document=new XWPFDocument();var output=new ByteArrayOutputStream()) {
            var heading=document.createParagraph();heading.setStyle("Heading1");heading.createRun().setText("Judul saja");
            document.write(output);
            var result=extractor.extract("heading.docx",output.toByteArray());
            assertEquals("Judul saja",result.sections().getFirst().title());
            assertTrue(result.sections().getFirst().text().isEmpty());
        }
        try(var document=new XWPFDocument();var output=new ByteArrayOutputStream()) {
            document.write(output);failure("EMPTY_DOCUMENT","empty.docx",output.toByteArray());
        }
    }

    @Test void rejectsUnsupportedCorruptEmptyAndOversizedDocuments() {
        failure("UNSUPPORTED_FORMAT", "x.exe", new byte[]{1});
        failure("CORRUPT_DOCUMENT", "x.pdf", "%PDF-1.7 broken".getBytes());
        failure("CORRUPT_DOCUMENT", "x.docx", new byte[]{80,75,3,4,0});
        failure("EMPTY_FILE", "x.pdf", new byte[0]);
        failure("FILE_TOO_LARGE", "x.pdf", new byte[DocumentExtractor.MAX_BYTES + 1]);
    }

    private void failure(String code, String name, byte[] bytes) {
        assertEquals(code, assertThrows(ExtractionFailure.class, () -> extractor.extract(name, bytes)).code());
    }

    @Test void encryptedPdfAndExpandedZipBombAreRejected() throws Exception {
        try (var document=new PDDocument();var output=new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner","password",
                    new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            document.save(output);failure("ENCRYPTED_DOCUMENT","secret.pdf",output.toByteArray());
        }
        var output=new ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(output)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            byte[] chunk=new byte[1024*1024];for(int i=0;i<51;i++) zip.write(chunk);
        }
        failure("EXTRACTION_LIMIT","bomb.docx",output.toByteArray());
    }

    @Test void compressedPdfTextBudgetIsEnforcedBeforeCollectingPage() throws Exception {
        try(var document=new PDDocument();var output=new ByteArrayOutputStream()) {
            var page=new PDPage();document.addPage(page);
            try(var stream=new PDPageContentStream(document,page)) {
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                // Highly compressible source, over one million decoded glyphs on a single page.
                String chunk="a".repeat(1000);
                for(int i=0;i<1001;i++) stream.showText(chunk);
                stream.endText();
            }
            document.save(output);assertTrue(output.size()<DocumentExtractor.MAX_BYTES);
            failure("EXTRACTION_LIMIT","dense.pdf",output.toByteArray());
        }
    }

    public static byte[] docx() throws Exception {
        try (var doc = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            var first = doc.createParagraph(); first.setStyle("Heading1"); first.createRun().setText("Bab Pertama");
            doc.createParagraph().createRun().setText("Isi pertama");
            var second = doc.createParagraph(); second.setStyle("Heading2"); second.createRun().setText("Bab Kedua");
            doc.createTable(1,1).getRow(0).getCell(0).setText("Isi tabel");
            var image = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB), "png", image);
            doc.createParagraph().createRun().addPicture(new java.io.ByteArrayInputStream(image.toByteArray()),
                    Document.PICTURE_TYPE_PNG, "image.png", Units.toEMU(20), Units.toEMU(20));
            doc.write(output); return output.toByteArray();
        }
    }

    public static byte[] pdf(boolean headings) throws Exception {
        System.setProperty("pdfbox.fontcache", "target/pdfbox-cache");
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage(); doc.addPage(page);
            try (var stream = new PDPageContentStream(doc,page)) {
                if (headings) line(stream,"Bab Pertama",18,740);
                line(stream,"Isi pertama",12,710);
                if (headings) line(stream,"Bab Kedua",18,660);
                line(stream,"Isi kedua",12,630);
                stream.drawImage(LosslessFactory.createFromImage(doc,new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB)),50,570,20,20);
            }
            doc.save(output); return output.toByteArray();
        }
    }
    private static void line(PDPageContentStream stream,String text,int size,int y) throws Exception {
        stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),size);
        stream.newLineAtOffset(50,y); stream.showText(text); stream.endText();
    }
}
