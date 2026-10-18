package id.soalku.practice;

import com.fasterxml.jackson.databind.*;
import id.soalku.auth.JwtService;
import id.soalku.core.Role;
import id.soalku.persistence.auth.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.http.MediaType;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class PracticeSessionIT {
    private static final String SCHEMA="soalku_it_practice_"+UUID.randomUUID().toString().replace("-","");
    private static final String JWT_KEY;
    static { byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);JWT_KEY=Base64.getEncoder().encodeToString(key); }
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PenggunaRepository users;
    @Autowired JwtService tokens;
    @Autowired ObjectMapper json;
    private Long studentId,otherStudentId,mentorId,weakSection,otherSection,pendingId;
    private String student,otherStudent,mentor;
    private List<Long> approved;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",PracticeSessionIT::url);p.add("spring.datasource.username",PracticeSessionIT::user);
        p.add("spring.datasource.password",PracticeSessionIT::password);p.add("spring.datasource.hikari.schema",()->SCHEMA);
        p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
        p.add("soalku.jwt.secret",()->JWT_KEY);p.add("soalku.latihan.seconds-per-question",()->"30");
    }
    private static String url() {
        String value=System.getenv("SOALKU_TEST_DATABASE_URL");
        if(value==null || !value.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-z0-9_]+_test")) throw new IllegalStateException("Only local *_test allowed");
        return value;
    }
    private static String user() { return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_USERNAME","soalku_test"); }
    private static String password() { return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_PASSWORD",""); }
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE TABLE "+SCHEMA+".pengguna CASCADE");
        var s=users.saveAndFlush(new PenggunaEntity("A","a"+UUID.randomUUID()+"@example.test","hash",Role.SISWA));
        var s2=users.saveAndFlush(new PenggunaEntity("B","b"+UUID.randomUUID()+"@example.test","hash",Role.SISWA));
        var m=users.saveAndFlush(new PenggunaEntity("Mentor","m"+UUID.randomUUID()+"@example.test","hash",Role.MENTOR));
        studentId=s.getId();otherStudentId=s2.getId();mentorId=m.getId();
        student=tokens.issue(studentId,Role.SISWA).accessToken();otherStudent=tokens.issue(otherStudentId,Role.SISWA).accessToken();mentor=tokens.issue(mentorId,Role.MENTOR).accessToken();
        weakSection=section(studentId);otherSection=section(otherStudentId);
        approved=List.of(question(weakSection,"DISETUJUI"),question(weakSection,"DISETUJUI"),question(otherSection,"DISETUJUI"),question(otherSection,"DISETUJUI"));
        pendingId=question(weakSection,"MENUNGGU");question(otherSection,"DITOLAK");
    }
    @AfterAll static void cleanup() throws Exception {
        try(var c=DriverManager.getConnection(url(),user(),password());var st=c.createStatement()) { st.execute("DROP SCHEMA "+SCHEMA+" CASCADE"); }
    }
    private Long section(Long owner) {
        var material=jdbc.queryForObject("INSERT INTO "+SCHEMA+".materi(judul,file_path,diunggah_oleh,jenjang) VALUES ('Biologi',?,?,'SD') RETURNING id",Long.class,"fixture/"+UUID.randomUUID(),owner);
        return jdbc.queryForObject("INSERT INTO "+SCHEMA+".bagian_materi(materi_id,judul_bagian,urutan) VALUES (?,'Topik',1) RETURNING id",Long.class,material);
    }
    private Long question(Long section,String status) {
        var id=jdbc.queryForObject("INSERT INTO "+SCHEMA+".soal(bagian_materi_id,pertanyaan,status_review,direview_oleh,direview_at) VALUES (?,'Fungsi akar?',?,?,?) RETURNING id",Long.class,section,status,"MENUNGGU".equals(status)?null:mentorId,"MENUNGGU".equals(status)?null:java.sql.Timestamp.from(Instant.now()));
        for(int n=1;n<=3;n++) jdbc.update("INSERT INTO "+SCHEMA+".opsi_jawaban(soal_id,teks_opsi,urutan,is_benar) VALUES (?,?,?,?)",id,"Opsi "+n,n,n==1);
        return id;
    }
    private void history(Long owner,Long question,boolean correct) {
        var session=jdbc.queryForObject("INSERT INTO "+SCHEMA+".sesi_latihan(siswa_id,mode) VALUES (?,'ACAK') RETURNING id",Long.class,owner);
        var option=jdbc.queryForObject("SELECT id FROM "+SCHEMA+".opsi_jawaban WHERE soal_id=? AND urutan=?",Long.class,question,correct?1:2);
        jdbc.update("INSERT INTO "+SCHEMA+".riwayat_jawaban(sesi_id,soal_id,opsi_dipilih,is_benar) VALUES (?,?,?,?)",session,question,option,correct);
    }
    private MvcResult start(String token,String key,String payload) throws Exception {
        var request=post("/api/latihan/sesi").contentType(MediaType.APPLICATION_JSON).content(payload);
        if(token!=null) request.header("Authorization","Bearer "+token);
        if(key!=null) request.header("Idempotency-Key",key);
        return http.perform(request).andReturn();
    }
    private String payload(String mode,int count) { return "{\"mode\":\""+mode+"\",\"jumlahSoal\":"+count+"}"; }
    private JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString()); }
    private int sessions() { return jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".sesi_latihan WHERE request_key IS NOT NULL",Integer.class); }
    @Test void allModesReturnOnlyApprovedQuestionsAcrossSectionsWithoutAnswerKeys() throws Exception {
        for(String mode:List.of("ACAK","BERWAKTU","FOKUS_KELEMAHAN")) {
            var result=start(student,UUID.randomUUID().toString(),payload(mode,50));assertEquals(201,result.getResponse().getStatus());
            var b=body(result);assertEquals(4,b.path("soal").size());var seen=new HashSet<Long>();
            for(var q:b.path("soal")) { seen.add(q.path("id").asLong());assertEquals(3,q.path("opsi").size()); }
            assertEquals(new HashSet<>(approved),seen);assertFalse(result.getResponse().getContentAsString().contains("benar"));
            assertFalse(result.getResponse().getContentAsString().contains("kunci"));
        }
        assertEquals(3,sessions());
    }
    @Test void focusUsesCurrentStudentsPercentageNotOtherStudentsOrRawWrongCounts() throws Exception {
        history(studentId,approved.get(0),false);history(studentId,approved.get(0),false);history(studentId,approved.get(0),true);
        history(studentId,approved.get(2),false); // 100% > 2/3 even with fewer wrong answers.
        history(otherStudentId,approved.get(0),false); // Other student's history must not affect A's ranking.
        var result=start(student,UUID.randomUUID().toString(),payload("FOKUS_KELEMAHAN",2));assertEquals(201,result.getResponse().getStatus());
        for(var q:body(result).path("soal")) assertEquals(otherSection,q.path("bagianMateriId").asLong());
        var b=start(otherStudent,UUID.randomUUID().toString(),payload("FOKUS_KELEMAHAN",1));assertEquals(201,b.getResponse().getStatus());
        assertEquals(weakSection,body(b).path("soal").get(0).path("bagianMateriId").asLong());
    }
    @Test void timedDeadlineIsPersistedAndBasedOnActualQuestionCount() throws Exception {
        var b=body(start(student,UUID.randomUUID().toString(),payload("BERWAKTU",50)));
        Instant began=Instant.parse(b.path("mulaiAt").asText()),end=Instant.parse(b.path("berakhirAt").asText());
        assertEquals(120,Duration.between(began,end).toSeconds());
        assertEquals(end,jdbc.queryForObject("SELECT berakhir_at FROM "+SCHEMA+".sesi_latihan WHERE id=?",java.sql.Timestamp.class,b.path("sesiId").asLong()).toInstant());
        var untimed=body(start(student,UUID.randomUUID().toString(),payload("ACAK",2)));assertTrue(untimed.path("berakhirAt").isNull());
    }
    @Test void replayKeepsSameSnapshotAndDeadlineDespiteBankAndHistoryChanges() throws Exception {
        String key=UUID.randomUUID().toString();var first=start(student,key,payload("FOKUS_KELEMAHAN",3));assertEquals(201,first.getResponse().getStatus());
        jdbc.update("UPDATE "+SCHEMA+".soal SET status_review='DISETUJUI',direview_oleh=?,direview_at=now() WHERE id=?",mentorId,pendingId);
        history(studentId,approved.get(2),false);
        assertEquals(body(first),body(start(student,key,payload("FOKUS_KELEMAHAN",3))));assertEquals(1,sessions());
    }
    @Test void keyCannotBeReusedWithDifferentModeOrCount() throws Exception {
        String key=UUID.randomUUID().toString();assertEquals(201,start(student,key,payload("ACAK",2)).getResponse().getStatus());
        assertEquals(409,start(student,key,payload("BERWAKTU",2)).getResponse().getStatus());assertEquals(409,start(student,key,payload("ACAK",3)).getResponse().getStatus());assertEquals(1,sessions());
    }
    @Test void sameKeyIsScopedToAuthenticatedStudent() throws Exception {
        String key=UUID.randomUUID().toString();var a=body(start(student,key,payload("ACAK",2)));var b=body(start(otherStudent,key,payload("ACAK",2)));
        assertNotEquals(a.path("sesiId"),b.path("sesiId"));
        assertEquals(studentId,jdbc.queryForObject("SELECT siswa_id FROM "+SCHEMA+".sesi_latihan WHERE id=?",Long.class,a.path("sesiId").asLong()));
        assertEquals(otherStudentId,jdbc.queryForObject("SELECT siswa_id FROM "+SCHEMA+".sesi_latihan WHERE id=?",Long.class,b.path("sesiId").asLong()));
    }
    @Test void unauthenticatedAndMentorRequestsAreRejectedWithoutSession() throws Exception {
        assertEquals(401,start(null,UUID.randomUUID().toString(),payload("ACAK",2)).getResponse().getStatus());
        assertEquals(403,start(mentor,UUID.randomUUID().toString(),payload("ACAK",2)).getResponse().getStatus());assertEquals(0,sessions());
    }
    @Test void staleStudentRoleCannotStartSession() throws Exception {
        jdbc.update("UPDATE "+SCHEMA+".pengguna SET role='MENTOR' WHERE id=?",studentId);
        assertEquals(403,start(student,UUID.randomUUID().toString(),payload("ACAK",2)).getResponse().getStatus());assertEquals(0,sessions());
    }
    @Test void invalidInputAndMassAssignmentAreRejected() throws Exception {
        for(String p:List.of(payload("UNKNOWN",2),payload("ACAK",0),payload("ACAK",51),"{}","{\"mode\":\"ACAK\",\"jumlahSoal\":2,\"siswaId\":"+otherStudentId+"}"))
            assertEquals(400,start(student,UUID.randomUUID().toString(),p).getResponse().getStatus());
        assertEquals(400,start(student,null,payload("ACAK",2)).getResponse().getStatus());assertEquals(400,start(student,"not-a-uuid",payload("ACAK",2)).getResponse().getStatus());assertEquals(0,sessions());
    }
    @Test void emptyApprovedBankDoesNotCreateSession() throws Exception {
        jdbc.update("UPDATE "+SCHEMA+".soal SET status_review='DITOLAK' WHERE status_review='DISETUJUI'");
        var result=start(student,UUID.randomUUID().toString(),payload("ACAK",2));assertEquals(409,result.getResponse().getStatus());assertEquals(0,sessions());
    }
    @Test void concurrentSameKeyCreatesOneSessionWithSameQuestions() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var latch=new CountDownLatch(1);String key=UUID.randomUUID().toString();
            var a=pool.submit(()->{latch.await();return start(student,key,payload("ACAK",2));});
            var b=pool.submit(()->{latch.await();return start(student,key,payload("ACAK",2));});latch.countDown();
            var ar=a.get(15,TimeUnit.SECONDS);var br=b.get(15,TimeUnit.SECONDS);
            assertEquals(201,ar.getResponse().getStatus());assertEquals(201,br.getResponse().getStatus());assertEquals(body(ar),body(br));assertEquals(1,sessions());
        }
    }
    @Test void expiredTimedSessionReplayDoesNotResetDeadline() throws Exception {
        String key=UUID.randomUUID().toString();var initial=body(start(student,key,payload("BERWAKTU",2)));
        jdbc.update("UPDATE "+SCHEMA+".sesi_latihan SET mulai_at=mulai_at-interval '1 hour',berakhir_at=berakhir_at-interval '1 hour' WHERE id=?",initial.path("sesiId").asLong());
        var replay=body(start(student,key,payload("BERWAKTU",2)));
        assertEquals(initial.path("soal"),replay.path("soal"));
        assertEquals(3600,Duration.between(Instant.parse(replay.path("berakhirAt").asText()),Instant.parse(initial.path("berakhirAt").asText())).toSeconds());
        assertTrue(Instant.parse(replay.path("berakhirAt").asText()).isBefore(Instant.now()));
        assertEquals(replay,body(start(student,key,payload("BERWAKTU",2))));assertEquals(1,sessions());
    }
    @Test void persistenceFailureRollsBackAndSameKeyCanBeRetried() throws Exception {
        jdbc.execute("CREATE FUNCTION "+SCHEMA+".reject_session() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private-session-diagnostic' USING ERRCODE='23514'; END $$");
        jdbc.execute("CREATE TRIGGER session_failure BEFORE INSERT ON "+SCHEMA+".sesi_latihan FOR EACH ROW EXECUTE FUNCTION "+SCHEMA+".reject_session()");
        String key=UUID.randomUUID().toString();
        try {
            var result=start(student,key,payload("ACAK",2));assertEquals(500,result.getResponse().getStatus());assertEquals(0,sessions());
            assertFalse(result.getResponse().getContentAsString().contains("private-session-diagnostic"));
        } finally { jdbc.execute("DROP TRIGGER session_failure ON "+SCHEMA+".sesi_latihan");jdbc.execute("DROP FUNCTION "+SCHEMA+".reject_session()"); }
        assertEquals(201,start(student,key,payload("ACAK",2)).getResponse().getStatus());assertEquals(1,sessions());
    }
    @Test void heldStudentLockHasBoundedWaitAndSafeRetry() throws Exception {
        String key=UUID.randomUUID().toString();
        try(var c=DriverManager.getConnection(url(),user(),password())) {
            c.setAutoCommit(false);
            try(var st=c.prepareStatement("SELECT id FROM "+SCHEMA+".pengguna WHERE id=? FOR UPDATE")) {
                st.setLong(1,studentId);try(var ignored=st.executeQuery()) {
                    long began=System.nanoTime();assertEquals(503,start(student,key,payload("ACAK",2)).getResponse().getStatus());
                    assertTrue(System.nanoTime()-began<TimeUnit.SECONDS.toNanos(10));assertEquals(0,sessions());
                }
            } finally { c.rollback(); }
        }
        assertEquals(201,start(student,key,payload("ACAK",2)).getResponse().getStatus());assertEquals(1,sessions());
    }
}
