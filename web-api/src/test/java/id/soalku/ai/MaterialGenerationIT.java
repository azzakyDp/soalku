package id.soalku.ai;

import id.soalku.auth.JwtService;
import id.soalku.core.Role;
import id.soalku.persistence.auth.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.util.Units;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class MaterialGenerationIT {
    private static final String SCHEMA="soalku_it_ai_"+UUID.randomUUID().toString().replace("-","");
    private static final Path ROOT=Path.of("target","generation-it-"+UUID.randomUUID()).toAbsolutePath();
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final byte[] JWT_KEY=new byte[32];
    private static final AtomicInteger CALLS=new AtomicInteger();
    private static final AtomicInteger FAIL_ON=new AtomicInteger();
    private static final AtomicLong DELAY=new AtomicLong();
    private static final AtomicBoolean FOREIGN_IMAGE=new AtomicBoolean();
    private static final AtomicBoolean BAD_JSON=new AtomicBoolean();
    private static final ExecutorService SERVER_THREADS=Executors.newFixedThreadPool(4);
    private static final HttpServer API=startServer();
    static { new java.security.SecureRandom().nextBytes(JWT_KEY); }
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PenggunaRepository users;
    @Autowired JwtService tokens;
    private Long owner;
    private String token;

    private static HttpServer startServer() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(SERVER_THREADS);
            server.createContext("/v1beta/models/gemini-3.8-flash:generateContent",exchange -> {
                int number=CALLS.incrementAndGet();var request=JSON.readTree(exchange.getRequestBody().readNBytes(16*1024*1024));
                int status=number==FAIL_ON.get() ? 429 : 200;
                var schema=request.path("generationConfig").path("responseFormat").path("text").path("schema");
                int count=schema.path("properties").path("soal").path("minItems").asInt();
                var questionSchema=schema.path("properties").path("soal").path("items").path("properties");
                int optionCount=questionSchema.path("opsi").path("minItems").asInt();
                var allowedImages=questionSchema.path("gambar_id").path("enum");
                Long image=allowedImages.size()>1 ? allowedImages.get(1).asLong() : null;
                if(FOREIGN_IMAGE.get()) image=Long.MAX_VALUE;
                var questions=new ArrayList<Map<String,Object>>();
                for(int i=0;i<count;i++) {
                    var question=new LinkedHashMap<String,Object>();question.put("pertanyaan","Pertanyaan hasil AI "+(i+1)+"?");
                    var options=new ArrayList<String>();for(int j=1;j<=optionCount;j++) options.add("Pilihan "+j);
                    question.put("opsi",options);question.put("jawaban_benar",1);question.put("gambar_id",image);questions.add(question);
                }
                String content=BAD_JSON.get() ? "{broken}" : JSON.writeValueAsString(Map.of("ringkasan","Ringkasan sederhana.","soal",questions));
                byte[] response=JSON.writeValueAsBytes(status==200 ? Map.of("candidates",List.of(Map.of("finishReason","STOP","content",Map.of("parts",List.of(Map.of("text",content)))))) : Map.of("error",Map.of("code",429)));
                long delay=DELAY.get();
                try {
                    if(delay>0) Thread.sleep(delay);
                    exchange.sendResponseHeaders(status,response.length);exchange.getResponseBody().write(response);
                } catch(InterruptedException failure) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });server.start();return server;
        } catch(IOException failure) { throw new IllegalStateException("Cannot start test Gemini server",failure); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",MaterialGenerationIT::url);
        properties.add("spring.datasource.username",MaterialGenerationIT::user);
        properties.add("spring.datasource.password",MaterialGenerationIT::password);
        properties.add("spring.datasource.hikari.schema",() -> SCHEMA);
        properties.add("spring.flyway.default-schema",() -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema",() -> SCHEMA);
        properties.add("soalku.jwt.secret",() -> Base64.getEncoder().encodeToString(JWT_KEY));
        properties.add("soalku.storage.root",ROOT::toString);
        properties.add("soalku.gemini.api-key",() -> "integration-test-key-only");
        properties.add("soalku.gemini.base-url",() -> "http://127.0.0.1:"+API.getAddress().getPort()+"/v1beta/");
        properties.add("soalku.gemini.timeout",() -> "1s");
        properties.add("soalku.gemini.questions-per-section",() -> "2");
    }
    private static String url() {
        String url=System.getenv("SOALKU_TEST_DATABASE_URL");
        if(url==null || !url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-z0-9_]+_test")) throw new IllegalStateException("Only local *_test DB allowed");
        return url;
    }
    private static String user() { return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_USERNAME","soalku_test"); }
    private static String password() { return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_PASSWORD",""); }
    @BeforeEach void before() {
        CALLS.set(0);FAIL_ON.set(0);DELAY.set(0);FOREIGN_IMAGE.set(false);BAD_JSON.set(false);
        var student=users.saveAndFlush(new PenggunaEntity("Siswa","ai"+UUID.randomUUID()+"@example.test","test-hash",Role.SISWA));
        owner=student.getId();token=tokens.issue(owner,Role.SISWA).accessToken();
    }
    @AfterAll static void after() throws Exception {
        API.stop(0);SERVER_THREADS.shutdownNow();
        try(var connection=DriverManager.getConnection(url(),user(),password());var statement=connection.createStatement()) { statement.execute("DROP SCHEMA "+SCHEMA+" CASCADE"); }
        // Only this test's generated target directory is deleted.
        if(Files.exists(ROOT)) try(var paths=Files.walk(ROOT)) { for(var path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    private Long upload(String level,boolean twoSections,boolean picture) throws Exception {
        var source=new ByteArrayOutputStream();
        try(var doc=new XWPFDocument()) {
            var heading=doc.createParagraph();heading.setStyle("Heading1");heading.createRun().setText("Akar");
            doc.createParagraph().createRun().setText("Akar menyerap air.");
            if(twoSections) {
                var next=doc.createParagraph();next.setStyle("Heading1");next.createRun().setText("Daun");
                doc.createParagraph().createRun().setText("Daun membuat makanan.");
            }
            if(picture) {
                var png=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB),"png",png);
                doc.createParagraph().createRun().addPicture(new ByteArrayInputStream(png.toByteArray()),Document.PICTURE_TYPE_PNG,"image.png",Units.toEMU(10),Units.toEMU(10));
            }
            doc.write(source);
        }
        var response=http.perform(multipart("/api/materi/upload").file(new MockMultipartFile("file","lesson.docx","application/octet-stream",source.toByteArray()))
                .param("judul","Materi "+UUID.randomUUID()).param("jenjang",level).header("Authorization","Bearer "+token)).andReturn().getResponse();
        assertEquals(201,response.getStatus(),response.getContentAsString());return JSON.readTree(response.getContentAsString()).path("id").asLong();
    }
    private org.springframework.mock.web.MockHttpServletResponse generate(Long id,String bearer) throws Exception {
        var request=post("/api/materi/"+id+"/generate");if(bearer!=null) request.header("Authorization","Bearer "+bearer);
        return http.perform(request).andReturn().getResponse();
    }
    private int count(Long id,String table) {
        String sql=switch(table) {
            case "soal" -> "SELECT count(*) FROM "+SCHEMA+".soal s JOIN "+SCHEMA+".bagian_materi b ON b.id=s.bagian_materi_id WHERE b.materi_id=?";
            case "opsi" -> "SELECT count(*) FROM "+SCHEMA+".opsi_jawaban o JOIN "+SCHEMA+".soal s ON s.id=o.soal_id JOIN "+SCHEMA+".bagian_materi b ON b.id=s.bagian_materi_id WHERE b.materi_id=?";
            default -> throw new IllegalArgumentException(table);
        };
        return jdbc.queryForObject(sql,Integer.class,id);
    }
    @Test void persistsSummaryQuestionsOptionsWaitingAndImageReferenceForBothLevels() throws Exception {
        for(String level:List.of("SD","SMP")) {
            Long id=upload(level,true,true);var result=generate(id,token);assertEquals(200,result.getStatus(),result.getContentAsString());
            assertTrue(JSON.readTree(result.getContentAsString()).path("selesai").asBoolean());
            assertEquals(4,count(id,"soal"));assertEquals(level.equals("SD") ? 12 : 16,count(id,"opsi"));
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".bagian_materi WHERE materi_id=? AND generated_at IS NOT NULL AND teks_ringkasan='Ringkasan sederhana.' AND generation_token IS NULL",Integer.class,id));
            assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".soal s JOIN "+SCHEMA+".bagian_materi b ON b.id=s.bagian_materi_id WHERE b.materi_id=? AND s.status_review='MENUNGGU' AND s.direview_oleh IS NULL AND s.direview_at IS NULL",Integer.class,id));
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".soal s JOIN "+SCHEMA+".bagian_materi b ON b.id=s.bagian_materi_id JOIN "+SCHEMA+".gambar_materi g ON g.id=s.gambar_id AND g.bagian_materi_id=b.id WHERE b.materi_id=?",Integer.class,id));
        }
    }
    @Test void failureKeepsSourceAndEarlierResultsAndRetryResumesWithoutDuplicates() throws Exception {
        Long id=upload("SD",true,false);FAIL_ON.set(2);
        var response=generate(id,token);assertEquals(503,response.getStatus());assertEquals(2,count(id,"soal"));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".bagian_materi WHERE materi_id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".materi WHERE id=?",Integer.class,id));
        FAIL_ON.set(0);int before=CALLS.get();var retry=generate(id,token);assertEquals(200,retry.getStatus());
        assertEquals(before+1,CALLS.get());assertEquals(4,count(id,"soal"));
        before=CALLS.get();var replay=generate(id,token);assertEquals(200,replay.getStatus());assertEquals(before,CALLS.get());
        assertEquals(4,count(id,"soal"));assertEquals(12,count(id,"opsi"));
    }
    @Test void ownershipRoleAndAuthenticationAreCheckedBeforeCallingGemini() throws Exception {
        Long id=upload("SD",false,false);
        assertEquals(401,generate(id,null).getStatus());
        var other=users.saveAndFlush(new PenggunaEntity("Other","other"+UUID.randomUUID()+"@example.test","hash",Role.SISWA));
        assertEquals(404,generate(id,tokens.issue(other.getId(),Role.SISWA).accessToken()).getStatus());
        var mentor=users.saveAndFlush(new PenggunaEntity("Mentor","mentor"+UUID.randomUUID()+"@example.test","hash",Role.MENTOR));
        assertEquals(403,generate(id,tokens.issue(mentor.getId(),Role.MENTOR).accessToken()).getStatus());assertEquals(0,CALLS.get());
    }
    @Test void malformedOutputAndForeignImagesDoNotPartiallyPersistAndRetryWorks() throws Exception {
        Long id=upload("SD",false,false);BAD_JSON.set(true);
        assertEquals(502,generate(id,token).getStatus());assertEquals(0,count(id,"soal"));
        BAD_JSON.set(false);FOREIGN_IMAGE.set(true);
        assertEquals(502,generate(id,token).getStatus());assertEquals(0,count(id,"soal"));
        FOREIGN_IMAGE.set(false);assertEquals(200,generate(id,token).getStatus());assertEquals(2,count(id,"soal"));
    }
    @Test void concurrentFirstGenerateMakesOneApiCallAndCreatesOneQuestionSet() throws Exception {
        Long id=upload("SD",false,false);DELAY.set(500);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var first=executor.submit(() -> generate(id,token));
            for(int i=0;i<300 && CALLS.get()==0;i++) Thread.sleep(10);
            assertEquals(1,CALLS.get());assertEquals(409,generate(id,token).getStatus());
            assertEquals(200,first.get(5,TimeUnit.SECONDS).getStatus());
        }
        assertEquals(1,CALLS.get());assertEquals(2,count(id,"soal"));
    }
    @Test void expiredLeaseCanBeRetriedAndStaleWriterCannotClearNewLease() throws Exception {
        Long id=upload("SD",false,false);DELAY.set(500);
        UUID replacement=UUID.randomUUID();
        try(var executor=Executors.newSingleThreadExecutor()) {
            var first=executor.submit(() -> generate(id,token));
            for(int i=0;i<300 && CALLS.get()==0;i++) Thread.sleep(10);
            assertEquals(1,CALLS.get());
            jdbc.update("UPDATE "+SCHEMA+".bagian_materi SET generation_token=?,generation_started_at=now() WHERE materi_id=?",replacement,id);
            assertEquals(409,first.get(5,TimeUnit.SECONDS).getStatus());assertEquals(0,count(id,"soal"));
        }
        assertEquals(replacement,jdbc.queryForObject("SELECT generation_token FROM "+SCHEMA+".bagian_materi WHERE materi_id=?",UUID.class,id));
        jdbc.update("UPDATE "+SCHEMA+".bagian_materi SET generation_started_at=now()-interval '3 minutes' WHERE materi_id=?",id);
        DELAY.set(0);assertEquals(200,generate(id,token).getStatus());assertEquals(2,count(id,"soal"));
    }
    @Test void timeoutPreservesMaterialAndReleasesLeaseForRetry() throws Exception {
        Long id=upload("SD",false,false);DELAY.set(1500);
        assertEquals(504,generate(id,token).getStatus());assertEquals(0,count(id,"soal"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".materi WHERE id=?",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".bagian_materi WHERE materi_id=? AND generation_token IS NOT NULL",Integer.class,id));
        DELAY.set(0);assertEquals(200,generate(id,token).getStatus());
    }
    @Test void databaseFailureRollsBackEntireSectionAndDoesNotLogMaterial(CapturedOutput output) throws Exception {
        Long id=upload("SD",false,false);
        jdbc.execute("ALTER TABLE "+SCHEMA+".opsi_jawaban ADD CONSTRAINT reject_generation_test CHECK (teks_opsi <> 'Pilihan 2') NOT VALID");
        try {
            assertEquals(500,generate(id,token).getStatus());assertEquals(0,count(id,"soal"));assertEquals(0,count(id,"opsi"));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".bagian_materi WHERE materi_id=? AND (generated_at IS NOT NULL OR generation_token IS NOT NULL)",Integer.class,id));
            assertFalse(output.getAll().contains("Akar menyerap air."));assertFalse(output.getAll().contains("integration-test-key-only"));
        } finally { jdbc.execute("ALTER TABLE "+SCHEMA+".opsi_jawaban DROP CONSTRAINT reject_generation_test"); }
        assertEquals(200,generate(id,token).getStatus());
    }
    @Test void heldDatabaseRowLockTimesOutAndCanBeRetried() throws Exception {
        Long id=upload("SD",false,false);
        try(var connection=DriverManager.getConnection(url(),user(),password())) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM "+SCHEMA+".bagian_materi WHERE materi_id=? FOR UPDATE")) {
                statement.setLong(1,id);statement.executeQuery().close();
                long start=System.nanoTime();var response=generate(id,token);
                assertEquals(503,response.getStatus(),response.getContentAsString());
                assertEquals("GENERATION_BUSY",JSON.readTree(response.getContentAsString()).path("code").asText());
                assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).compareTo(java.time.Duration.ofSeconds(5))<0);
                assertEquals(0,CALLS.get());connection.rollback();
            }
        }
        assertEquals(200,generate(id,token).getStatus());assertEquals(2,count(id,"soal"));
    }
}
