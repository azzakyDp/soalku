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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.http.MediaType;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Mock only time; JWT, services, answer grading, aggregation and transactions use real components. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class AnswerEvaluationIT {
    private static final String SCHEMA="soalku_it_answer_"+UUID.randomUUID().toString().replace("-","");
    private static final Instant NOW=Instant.parse("2026-10-18T00:00:00Z");
    private static final String JWT_KEY;
    static { byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);JWT_KEY=Base64.getEncoder().encodeToString(key); }
    @MockitoBean Clock clock;
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PenggunaRepository users;
    @Autowired JwtService tokens;
    @Autowired ObjectMapper json;
    @Autowired jakarta.persistence.EntityManagerFactory emf;
    private Long studentId,otherStudentId,mentorId,weakSection,strongSection;
    private String student,otherStudent,mentor;
    private List<Long> approved;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",AnswerEvaluationIT::url);p.add("spring.datasource.username",AnswerEvaluationIT::user);
        p.add("spring.datasource.password",AnswerEvaluationIT::password);p.add("spring.datasource.hikari.schema",()->SCHEMA);
        p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
        p.add("soalku.jwt.secret",()->JWT_KEY);p.add("soalku.latihan.seconds-per-question",()->"30");
        p.add("spring.jpa.properties.hibernate.generate_statistics",()->"true");
        p.add("logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener",()->"OFF");
        p.add("logging.level.org.hibernate.stat",()->"OFF");
    }
    private static String url() {
        String value=System.getenv("SOALKU_TEST_DATABASE_URL");
        if(value==null || !value.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-z0-9_]+_test")) throw new IllegalStateException("Only local *_test allowed");return value;
    }
    private static String user() { return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_USERNAME","soalku_test"); }
    private static String password() { return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_PASSWORD",""); }
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        jdbc.execute("TRUNCATE TABLE "+SCHEMA+".pengguna CASCADE");
        var s=users.saveAndFlush(new PenggunaEntity("A","a"+UUID.randomUUID()+"@example.test","hash",Role.SISWA));
        var s2=users.saveAndFlush(new PenggunaEntity("B","b"+UUID.randomUUID()+"@example.test","hash",Role.SISWA));
        var m=users.saveAndFlush(new PenggunaEntity("Mentor","m"+UUID.randomUUID()+"@example.test","hash",Role.MENTOR));
        studentId=s.getId();otherStudentId=s2.getId();mentorId=m.getId();
        student=tokens.issue(studentId,Role.SISWA).accessToken();otherStudent=tokens.issue(otherStudentId,Role.SISWA).accessToken();mentor=tokens.issue(mentorId,Role.MENTOR).accessToken();
        weakSection=section("Akar");strongSection=section("Daun");
        approved=List.of(question(weakSection),question(weakSection),question(strongSection),question(strongSection));
    }
    @AfterAll static void cleanup() throws Exception {
        try(var c=DriverManager.getConnection(url(),user(),password());var st=c.createStatement()) { st.execute("DROP SCHEMA "+SCHEMA+" CASCADE"); }
    }
    private Long section(String title) {
        var material=jdbc.queryForObject("INSERT INTO "+SCHEMA+".materi(judul,file_path,diunggah_oleh,jenjang) VALUES ('Biologi',?,?,'SD') RETURNING id",Long.class,"fixture/"+UUID.randomUUID(),studentId);
        return jdbc.queryForObject("INSERT INTO "+SCHEMA+".bagian_materi(materi_id,judul_bagian,urutan) VALUES (?,?,1) RETURNING id",Long.class,material,title);
    }
    private Long question(Long section) {
        var id=jdbc.queryForObject("INSERT INTO "+SCHEMA+".soal(bagian_materi_id,pertanyaan,status_review,direview_oleh,direview_at) VALUES (?,'Fungsi akar?','DISETUJUI',?,?) RETURNING id",Long.class,section,mentorId,java.sql.Timestamp.from(NOW));
        for(int n=1;n<=3;n++) jdbc.update("INSERT INTO "+SCHEMA+".opsi_jawaban(soal_id,teks_opsi,urutan,is_benar) VALUES (?,?,?,?)",id,"Opsi "+n,n,n==1);
        return id;
    }
    private Long option(Long question,boolean correct) { return jdbc.queryForObject("SELECT id FROM "+SCHEMA+".opsi_jawaban WHERE soal_id=? AND urutan=?",Long.class,question,correct?1:2); }
    private JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString()); }
    private JsonNode session(int count,String mode) throws Exception {
        var result=http.perform(post("/api/latihan/sesi").header("Authorization","Bearer "+student).header("Idempotency-Key",UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\""+mode+"\",\"jumlahSoal\":"+count+"}")).andReturn();
        assertEquals(201,result.getResponse().getStatus());return body(result);
    }
    private MvcResult submit(Long session,Long question,Long option,String token) throws Exception {
        return submitRaw(session,"{\"soalId\":"+question+",\"opsiId\":"+option+"}",token);
    }
    private MvcResult submitRaw(Long session,String payload,String token) throws Exception {
        var req=post("/api/latihan/sesi/"+session+"/jawaban").contentType(MediaType.APPLICATION_JSON).content(payload);
        if(token!=null) req.header("Authorization","Bearer "+token);return http.perform(req).andReturn();
    }
    private MvcResult evaluation(String token,String query) throws Exception {
        var req=get("/api/latihan/evaluasi"+query);if(token!=null) req.header("Authorization","Bearer "+token);return http.perform(req).andReturn();
    }
    private int answers() { return jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".riwayat_jawaban",Integer.class); }
    private void history(Long owner,Long question,boolean correct) {
        var id=jdbc.queryForObject("INSERT INTO "+SCHEMA+".sesi_latihan(siswa_id,mode) VALUES (?,'ACAK') RETURNING id",Long.class,owner);
        jdbc.update("INSERT INTO "+SCHEMA+".riwayat_jawaban(sesi_id,soal_id,opsi_dipilih,is_benar) VALUES (?,?,?,?)",id,question,option(question,correct),correct);
    }
    @Test void submittedChoiceIsGradedByServerAndPersistedWithSessionAndQuestion() throws Exception {
        var s=session(4,"ACAK");Long id=s.path("sesiId").asLong();
        var correct=submit(id,approved.get(0),option(approved.get(0),true),student);assertEquals(200,correct.getResponse().getStatus());assertTrue(body(correct).path("benar").asBoolean());
        var wrong=submit(id,approved.get(1),option(approved.get(1),false),student);assertEquals(200,wrong.getResponse().getStatus());assertFalse(body(wrong).path("benar").asBoolean());
        var saved=jdbc.queryForMap("SELECT sesi_id,soal_id,opsi_dipilih,is_benar,dijawab_at FROM "+SCHEMA+".riwayat_jawaban WHERE soal_id=?",approved.get(0));
        assertEquals(id,saved.get("sesi_id"));assertEquals(approved.get(0),saved.get("soal_id"));assertEquals(true,saved.get("is_benar"));assertEquals(java.sql.Timestamp.from(NOW),saved.get("dijawab_at"));assertEquals(2,answers());
    }
    @Test void sameAnswerReplayIsStableButChangingAnAnswerConflicts() throws Exception {
        var s=session(1,"ACAK");Long id=s.path("sesiId").asLong(),q=s.path("soal").get(0).path("id").asLong();
        var first=submit(id,q,option(q,true),student);assertEquals(200,first.getResponse().getStatus());
        assertEquals(body(first),body(submit(id,q,option(q,true),student)));assertEquals(1,answers());
        assertEquals(409,submit(id,q,option(q,false),student).getResponse().getStatus());assertEquals(1,answers());
    }
    @Test void timedDeadlineRejectsNewAnswersExactlyAtBoundaryButAllowsAcceptedReplay() throws Exception {
        var s=session(2,"BERWAKTU");Long id=s.path("sesiId").asLong(),q=s.path("soal").get(0).path("id").asLong(),q2=s.path("soal").get(1).path("id").asLong();
        when(clock.instant()).thenReturn(NOW.plusSeconds(59));var first=submit(id,q,option(q,true),student);assertEquals(200,first.getResponse().getStatus());
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        var late=submit(id,q2,option(q2,true),student);assertEquals(409,late.getResponse().getStatus());assertEquals("SESSION_EXPIRED",body(late).path("code").asText());assertEquals(1,answers());
        assertEquals(body(first),body(submit(id,q,option(q,true),student)));assertEquals(1,answers());
    }
    @Test void anotherStudentsSessionAndUnknownSessionAreHiddenForSubmitAndEvaluation() throws Exception {
        var s=session(1,"ACAK");Long id=s.path("sesiId").asLong(),q=s.path("soal").get(0).path("id").asLong();
        assertEquals(404,submit(id,q,option(q,true),otherStudent).getResponse().getStatus());
        assertEquals(404,evaluation(otherStudent,"?sesiId="+id).getResponse().getStatus());
        assertEquals(404,submit(Long.MAX_VALUE,q,option(q,true),student).getResponse().getStatus());
        assertEquals(404,evaluation(student,"?sesiId="+Long.MAX_VALUE).getResponse().getStatus());assertEquals(0,answers());
    }
    @Test void questionOutsideSnapshotOrOptionFromAnotherQuestionCannotBeSubmitted() throws Exception {
        var s=session(1,"ACAK");Long id=s.path("sesiId").asLong(),q=s.path("soal").get(0).path("id").asLong();Long outside=approved.stream().filter(x->!x.equals(q)).findFirst().orElseThrow();
        assertEquals(400,submit(id,outside,option(outside,true),student).getResponse().getStatus());
        assertEquals(400,submit(id,q,option(outside,true),student).getResponse().getStatus());
        assertEquals(400,submit(id,q,Long.MAX_VALUE,student).getResponse().getStatus());assertEquals(0,answers());
    }
    @Test void unauthorizedRolesAndMalformedOrMassAssignedInputAreRejected() throws Exception {
        var s=session(1,"ACAK");Long id=s.path("sesiId").asLong(),q=s.path("soal").get(0).path("id").asLong();Long opt=option(q,true);
        assertEquals(401,submit(id,q,opt,null).getResponse().getStatus());assertEquals(403,submit(id,q,opt,mentor).getResponse().getStatus());
        assertEquals(401,evaluation(null,"").getResponse().getStatus());assertEquals(403,evaluation(mentor,"").getResponse().getStatus());
        for(String payload:List.of("{}","{\"soalId\":0,\"opsiId\":1}","{\"soalId\":"+q+",\"opsiId\":"+opt+",\"is_benar\":true}","{\"soalId\":"+q+",\"opsiId\":"+opt+",\"siswaId\":"+otherStudentId+"}"))
            assertEquals(400,submitRaw(id,payload,student).getResponse().getStatus());assertEquals(0,answers());
    }
    @Test void accountRoleChangeRevokesSubmissionAndEvaluation() throws Exception {
        var s=session(1,"ACAK");Long q=s.path("soal").get(0).path("id").asLong();
        jdbc.update("UPDATE "+SCHEMA+".pengguna SET role='MENTOR' WHERE id=?",studentId);
        assertEquals(403,submit(s.path("sesiId").asLong(),q,option(q,true),student).getResponse().getStatus());assertEquals(403,evaluation(student,"").getResponse().getStatus());assertEquals(0,answers());
    }
    @Test void lastAnswerCompletesSessionAtomicallyAndEarlierAnswersRemainReplayable() throws Exception {
        var s=session(2,"ACAK");Long id=s.path("sesiId").asLong(),a=s.path("soal").get(0).path("id").asLong(),b=s.path("soal").get(1).path("id").asLong();
        var first=submit(id,a,option(a,true),student);assertEquals(200,first.getResponse().getStatus());assertNull(jdbc.queryForMap("SELECT selesai_at FROM "+SCHEMA+".sesi_latihan WHERE id=?",id).get("selesai_at"));
        assertEquals(200,submit(id,b,option(b,false),student).getResponse().getStatus());
        assertEquals(java.sql.Timestamp.from(NOW),jdbc.queryForMap("SELECT selesai_at FROM "+SCHEMA+".sesi_latihan WHERE id=?",id).get("selesai_at"));
        assertEquals(body(first),body(submit(id,a,option(a,true),student)));assertEquals(2,answers());
    }
    @Test void completedSessionCannotAcceptAnUnansweredQuestion() throws Exception {
        var s=session(1,"ACAK");Long id=s.path("sesiId").asLong(),q=s.path("soal").get(0).path("id").asLong();
        jdbc.update("UPDATE "+SCHEMA+".sesi_latihan SET selesai_at=? WHERE id=?",java.sql.Timestamp.from(NOW),id);
        var result=submit(id,q,option(q,true),student);assertEquals(409,result.getResponse().getStatus());assertEquals("SESSION_COMPLETED",body(result).path("code").asText());assertEquals(0,answers());
    }
    @Test void evaluationAggregatesAttemptsBySectionNotAverageQuestionScoresAndScopesStudent() throws Exception {
        history(studentId,approved.get(0),true);for(int n=0;n<3;n++) history(studentId,approved.get(1),false);
        history(studentId,approved.get(2),true);history(studentId,approved.get(2),true);history(studentId,approved.get(3),false);
        for(int n=0;n<8;n++) history(otherStudentId,approved.get(0),true);
        var result=evaluation(student,"");assertEquals(200,result.getResponse().getStatus());var rows=body(result).path("content");assertEquals(2,rows.size());
        assertEquals(weakSection,rows.get(0).path("bagianMateriId").asLong());assertEquals("Akar",rows.get(0).path("judulBagian").asText());assertEquals(4,rows.get(0).path("totalJawaban").asLong());
        assertEquals(1,rows.get(0).path("jumlahBenar").asLong());assertEquals(3,rows.get(0).path("jumlahSalah").asLong());assertEquals(25.0,rows.get(0).path("persentaseBenar").asDouble());assertTrue(rows.get(0).path("perluDiulang").asBoolean());
        assertEquals(strongSection,rows.get(1).path("bagianMateriId").asLong());assertEquals(66.67,rows.get(1).path("persentaseBenar").asDouble());
        var ownOther=body(evaluation(otherStudent,"")).path("content");assertEquals(1,ownOther.size());assertEquals(100.0,ownOther.get(0).path("persentaseBenar").asDouble());assertFalse(ownOther.get(0).path("perluDiulang").asBoolean());
    }
    @Test void optionalSessionFilterExcludesOwnAnswersInOtherSessions() throws Exception {
        var s=session(4,"ACAK");Long id=s.path("sesiId").asLong();assertEquals(200,submit(id,approved.get(0),option(approved.get(0),false),student).getResponse().getStatus());
        history(studentId,approved.get(0),true);
        var filtered=evaluation(student,"?sesiId="+id);assertEquals(200,filtered.getResponse().getStatus());var rows=body(filtered).path("content");
        assertEquals(1,rows.size());assertEquals(1,rows.get(0).path("totalJawaban").asLong());assertEquals(0.0,rows.get(0).path("persentaseBenar").asDouble());
        assertEquals(50.0,body(evaluation(student,"")).path("content").get(0).path("persentaseBenar").asDouble());
    }
    @Test void emptyEvaluationHasNoInventedZeroScoresAndPaginationIsValidated() throws Exception {
        var result=evaluation(student,"");assertEquals(200,result.getResponse().getStatus());assertEquals(0,body(result).path("totalElements").asLong());assertTrue(body(result).path("content").isEmpty());
        for(String query:List.of("?page=-1","?size=0","?size=101","?page=2147483647","?page=abc","?sesiId=0")) assertEquals(400,evaluation(student,query).getResponse().getStatus());
    }
    @Test void aggregateQueryCountIsConstantWithGrowingNumberOfSections() throws Exception {
        var sections=new ArrayList<Long>();
        for(int n=0;n<5;n++) { Long section=section("Topik "+n);sections.add(section);history(studentId,question(section),false); }
        var stats=emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();stats.clear();
        var first=evaluation(student,"?size=2");assertEquals(200,first.getResponse().getStatus());assertEquals(5,body(first).path("totalElements").asLong());assertEquals(2,body(first).path("content").size());
        assertEquals(sections.get(0),body(first).path("content").get(0).path("bagianMateriId").asLong());assertEquals(sections.get(1),body(first).path("content").get(1).path("bagianMateriId").asLong());
        assertTrue(stats.getPrepareStatementCount()<=3,"Expected user + aggregate + aggregate count; got "+stats.getPrepareStatementCount());
        for(int n=5;n<20;n++) history(studentId,question(section("Topik "+n)),false);
        stats.clear();var larger=evaluation(student,"?size=2&page=1");assertEquals(200,larger.getResponse().getStatus());assertEquals(20,body(larger).path("totalElements").asLong());
        assertEquals(sections.get(2),body(larger).path("content").get(0).path("bagianMateriId").asLong());assertEquals(sections.get(3),body(larger).path("content").get(1).path("bagianMateriId").asLong());
        assertTrue(stats.getPrepareStatementCount()<=3,"Aggregate must not query once per section: "+stats.getPrepareStatementCount());
    }
    @Test void submittedHistoryFeedsWeaknessStrategy() throws Exception {
        var s=session(4,"ACAK");Long id=s.path("sesiId").asLong();
        assertEquals(200,submit(id,approved.get(0),option(approved.get(0),false),student).getResponse().getStatus());
        assertEquals(200,submit(id,approved.get(2),option(approved.get(2),true),student).getResponse().getStatus());
        var focus=session(2,"FOKUS_KELEMAHAN");for(var q:focus.path("soal")) assertEquals(weakSection,q.path("bagianMateriId").asLong());
    }
    @Test void concurrentSameAnswerCreatesOneReceiptAndOpposingAnswerCannotOverwrite() throws Exception {
        var s=session(1,"ACAK");Long id=s.path("sesiId").asLong(),q=s.path("soal").get(0).path("id").asLong(),opt=option(q,true);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();return submit(id,q,opt,student);});var b=pool.submit(()->{gate.await();return submit(id,q,opt,student);});gate.countDown();
            var ar=a.get(15,TimeUnit.SECONDS);var br=b.get(15,TimeUnit.SECONDS);assertEquals(200,ar.getResponse().getStatus());assertEquals(200,br.getResponse().getStatus());assertEquals(body(ar),body(br));assertEquals(1,answers());
        }
        assertEquals(409,submit(id,q,option(q,false),student).getResponse().getStatus());assertEquals(1,answers());
    }
    @Test void failureCompletingSessionRollsBackAnswerAndRetryCanSucceed() throws Exception {
        var s=session(1,"ACAK");Long id=s.path("sesiId").asLong(),q=s.path("soal").get(0).path("id").asLong();
        jdbc.execute("CREATE FUNCTION "+SCHEMA+".reject_finish() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.selesai_at IS NOT NULL THEN RAISE EXCEPTION 'private-answer-diagnostic' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER finish_failure BEFORE UPDATE ON "+SCHEMA+".sesi_latihan FOR EACH ROW EXECUTE FUNCTION "+SCHEMA+".reject_finish()");
        try {
            var result=submit(id,q,option(q,true),student);assertEquals(500,result.getResponse().getStatus());assertEquals(0,answers());assertFalse(result.getResponse().getContentAsString().contains("private-answer-diagnostic"));
            assertNull(jdbc.queryForMap("SELECT selesai_at FROM "+SCHEMA+".sesi_latihan WHERE id=?",id).get("selesai_at"));
        } finally { jdbc.execute("DROP TRIGGER finish_failure ON "+SCHEMA+".sesi_latihan");jdbc.execute("DROP FUNCTION "+SCHEMA+".reject_finish()"); }
        assertEquals(200,submit(id,q,option(q,true),student).getResponse().getStatus());assertEquals(1,answers());
    }
    @Test void heldSessionLockReturnsRetryableErrorAndDoesNotPersistAnswer() throws Exception {
        var s=session(1,"ACAK");Long id=s.path("sesiId").asLong(),q=s.path("soal").get(0).path("id").asLong();
        try(var c=DriverManager.getConnection(url(),user(),password())) {
            c.setAutoCommit(false);try(var st=c.prepareStatement("SELECT id FROM "+SCHEMA+".sesi_latihan WHERE id=? FOR UPDATE")) {
                st.setLong(1,id);try(var ignored=st.executeQuery()) { long began=System.nanoTime();assertEquals(503,submit(id,q,option(q,true),student).getResponse().getStatus());assertTrue(System.nanoTime()-began<TimeUnit.SECONDS.toNanos(10));assertEquals(0,answers()); }
            } finally { c.rollback(); }
        }
        assertEquals(200,submit(id,q,option(q,true),student).getResponse().getStatus());
    }
    @Test void simultaneousOpposingChoicesHaveOneWinnerAndCannotOverwriteGrade() throws Exception {
        var s=session(1,"ACAK");Long id=s.path("sesiId").asLong(),q=s.path("soal").get(0).path("id").asLong();
        Long yes=option(q,true),no=option(q,false);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();return submit(id,q,yes,student).getResponse().getStatus();});
            var b=pool.submit(()->{gate.await();return submit(id,q,no,student).getResponse().getStatus();});gate.countDown();
            assertEquals(List.of(200,409),java.util.stream.Stream.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)).sorted().toList());
        }
        assertEquals(1,answers());assertEquals(true,jdbc.queryForObject("SELECT r.is_benar=o.is_benar FROM "+SCHEMA+".riwayat_jawaban r JOIN "+SCHEMA+".opsi_jawaban o ON o.id=r.opsi_dipilih WHERE r.sesi_id=?",Boolean.class,id));
    }
    @Test void concurrentDifferentFinalQuestionsCompleteSessionOnceWithoutLosingAnswers() throws Exception {
        var s=session(2,"ACAK");Long id=s.path("sesiId").asLong(),a=s.path("soal").get(0).path("id").asLong(),b=s.path("soal").get(1).path("id").asLong();
        Long yes=option(a,true),no=option(b,false);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var first=pool.submit(()->{gate.await();return submit(id,a,yes,student).getResponse().getStatus();});
            var second=pool.submit(()->{gate.await();return submit(id,b,no,student).getResponse().getStatus();});gate.countDown();
            assertEquals(200,first.get(15,TimeUnit.SECONDS));assertEquals(200,second.get(15,TimeUnit.SECONDS));
        }
        assertEquals(2,answers());assertEquals(java.sql.Timestamp.from(NOW),jdbc.queryForMap("SELECT selesai_at FROM "+SCHEMA+".sesi_latihan WHERE id=?",id).get("selesai_at"));
    }
    @Test void legacySessionCannotAcceptNewAnswersButItsHistoryRemainsEvaluable() throws Exception {
        Long legacy=jdbc.queryForObject("INSERT INTO "+SCHEMA+".sesi_latihan(siswa_id,mode) VALUES (?,'ACAK') RETURNING id",Long.class,studentId);
        jdbc.update("INSERT INTO "+SCHEMA+".riwayat_jawaban(sesi_id,soal_id,opsi_dipilih,is_benar) VALUES (?,?,?,true)",legacy,approved.get(0),option(approved.get(0),true));
        var submit=submit(legacy,approved.get(1),option(approved.get(1),true),student);assertEquals(409,submit.getResponse().getStatus());assertEquals("SESSION_SNAPSHOT_REQUIRED",body(submit).path("code").asText());assertEquals(1,answers());
        var result=evaluation(student,"?sesiId="+legacy);assertEquals(200,result.getResponse().getStatus());assertEquals(100.0,body(result).path("content").get(0).path("persentaseBenar").asDouble());
    }
}
