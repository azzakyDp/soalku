package id.soalku.review;

import com.fasterxml.jackson.databind.*;
import id.soalku.auth.JwtService;
import id.soalku.core.Role;
import id.soalku.persistence.auth.*;
import java.sql.DriverManager;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real JWT/filter chain, review service, JPA, Flyway and PostgreSQL; no mocked review logic. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class QuestionReviewIT {
    private static final String SCHEMA="soalku_it_review_"+UUID.randomUUID().toString().replace("-","");
    private static final String JWT_KEY;
    static { byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);JWT_KEY=Base64.getEncoder().encodeToString(key); }
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PenggunaRepository users;
    @Autowired JwtService tokens;
    @Autowired ObjectMapper json;
    @Autowired jakarta.persistence.EntityManagerFactory emf;
    private Long mentorId,otherMentorId,studentId,questionId;
    private String mentor,otherMentor,student;
    private List<Long> optionIds;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",QuestionReviewIT::url);
        p.add("spring.datasource.username",QuestionReviewIT::user);
        p.add("spring.datasource.password",QuestionReviewIT::password);
        p.add("spring.datasource.hikari.schema",()->SCHEMA);
        p.add("spring.flyway.default-schema",()->SCHEMA);
        p.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
        p.add("soalku.jwt.secret",()->JWT_KEY);
        p.add("spring.jpa.properties.hibernate.generate_statistics",()->"true");
        p.add("logging.level.org.hibernate.stat",()->"OFF");
        p.add("logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener",()->"OFF");
    }
    private static String url() {
        String value=System.getenv("SOALKU_TEST_DATABASE_URL");
        if(value==null || !value.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-z0-9_]+_test"))
            throw new IllegalStateException("Only local *_test database allowed");
        return value;
    }
    private static String user() { return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_USERNAME","soalku_test"); }
    private static String password() { return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_PASSWORD",""); }
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE TABLE "+SCHEMA+".pengguna CASCADE");
        var m=users.saveAndFlush(new PenggunaEntity("Mentor","m"+UUID.randomUUID()+"@example.test","hash",Role.MENTOR));
        var m2=users.saveAndFlush(new PenggunaEntity("Mentor lain","m2"+UUID.randomUUID()+"@example.test","hash",Role.MENTOR));
        var s=users.saveAndFlush(new PenggunaEntity("Siswa","s"+UUID.randomUUID()+"@example.test","hash",Role.SISWA));
        mentorId=m.getId();otherMentorId=m2.getId();studentId=s.getId();
        mentor=tokens.issue(mentorId,Role.MENTOR).accessToken();otherMentor=tokens.issue(otherMentorId,Role.MENTOR).accessToken();
        student=tokens.issue(studentId,Role.SISWA).accessToken();questionId=question("SMP",4);
        optionIds=ids(questionId);
    }
    @AfterAll static void cleanup() throws Exception {
        try(var c=DriverManager.getConnection(url(),user(),password());var st=c.createStatement()) { st.execute("DROP SCHEMA "+SCHEMA+" CASCADE"); }
    }
    private Long question(String level,int count) {
        Long material=jdbc.queryForObject("INSERT INTO "+SCHEMA+".materi(judul,file_path,diunggah_oleh,jenjang) VALUES ('Biologi',?, ?,?) RETURNING id",Long.class,"fixture/"+UUID.randomUUID(),studentId,level);
        Long section=jdbc.queryForObject("INSERT INTO "+SCHEMA+".bagian_materi(materi_id,judul_bagian,urutan) VALUES (?,'Akar',1) RETURNING id",Long.class,material);
        Long id=jdbc.queryForObject("INSERT INTO "+SCHEMA+".soal(bagian_materi_id,pertanyaan) VALUES (?,'Fungsi akar?') RETURNING id",Long.class,section);
        for(int n=1;n<=count;n++) jdbc.update("INSERT INTO "+SCHEMA+".opsi_jawaban(soal_id,teks_opsi,urutan,is_benar) VALUES (?,?,?,?)",id,"Opsi "+n,n,n==1);
        return id;
    }
    private List<Long> ids(Long id) { return jdbc.queryForList("SELECT id FROM "+SCHEMA+".opsi_jawaban WHERE soal_id=? ORDER BY urutan",Long.class,id); }
    private Map<String,Object> row() { return jdbc.queryForMap("SELECT status_review,direview_oleh,direview_at,pertanyaan FROM "+SCHEMA+".soal WHERE id=?",questionId); }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private MvcResult decision(String action,String token) throws Exception {
        return http.perform(post("/api/mentor/soal/"+questionId+"/"+action).header("Authorization","Bearer "+token)).andReturn();
    }
    private String editBody(List<Long> ids,int correct) throws Exception {
        var opts=new ArrayList<Map<String,Object>>();
        for(int n=0;n<ids.size();n++) opts.add(Map.of("id",ids.get(n),"teks","Revisi "+(n+1),"benar",n==correct));
        return json.writeValueAsString(Map.of("pertanyaan","Pertanyaan baru?","opsi",opts));
    }
    private MvcResult edit(String payload) throws Exception {
        return http.perform(put("/api/mentor/soal/"+questionId).header("Authorization","Bearer "+mentor).contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn();
    }
    @Test void pendingQueueIncludesOptionsAndIsPaginatedAcrossStudentMaterials() throws Exception {
        Long approved=question("SD",3);jdbc.update("UPDATE "+SCHEMA+".soal SET status_review='DISETUJUI',direview_oleh=?,direview_at=now() WHERE id=?",mentorId,approved);
        Long second=question("SD",3);
        var first=http.perform(get("/api/mentor/soal?size=1").header("Authorization","Bearer "+mentor)).andReturn();
        assertEquals(200,first.getResponse().getStatus());var b=body(first);
        assertEquals(2,b.path("totalElements").asInt());assertEquals(2,b.path("totalPages").asInt());
        assertEquals(questionId,b.path("content").get(0).path("id").asLong());
        assertEquals(4,b.path("content").get(0).path("opsi").size());
        assertTrue(b.path("content").get(0).path("opsi").get(0).path("benar").asBoolean());
        var next=http.perform(get("/api/mentor/soal?page=1&size=1").header("Authorization","Bearer "+mentor)).andReturn();
        assertEquals(second,body(next).path("content").get(0).path("id").asLong());
    }
    @Test void queueUsesBoundedQueriesRatherThanOneOptionQueryPerQuestion() throws Exception {
        for(int n=0;n<10;n++) question("SD",3);
        var stats=emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();stats.clear();
        var result=http.perform(get("/api/mentor/soal?size=10").header("Authorization","Bearer "+mentor)).andReturn();
        assertEquals(200,result.getResponse().getStatus());assertEquals(10,body(result).path("content").size());
        assertTrue(stats.getPrepareStatementCount()<=4,"Expected user + page + count + bulk options, got "+stats.getPrepareStatementCount());
    }
    @Test void studentIsForbiddenOnEveryReviewActionAndCannotMutateData() throws Exception {
        assertEquals(403,http.perform(get("/api/mentor/soal").header("Authorization","Bearer "+student)).andReturn().getResponse().getStatus());
        assertEquals(403,http.perform(put("/api/mentor/soal/"+questionId).header("Authorization","Bearer "+student).contentType(MediaType.APPLICATION_JSON).content(editBody(optionIds,0))).andReturn().getResponse().getStatus());
        assertEquals(403,decision("approve",student).getResponse().getStatus());assertEquals(403,decision("reject",student).getResponse().getStatus());
        assertEquals("MENUNGGU",row().get("status_review"));assertEquals("Fungsi akar?",row().get("pertanyaan"));
    }
    @Test void unauthenticatedRequestsAreRejected() throws Exception {
        assertEquals(401,http.perform(get("/api/mentor/soal")).andReturn().getResponse().getStatus());
        assertEquals(401,http.perform(post("/api/mentor/soal/"+questionId+"/approve")).andReturn().getResponse().getStatus());
    }
    @Test void mentorCanEditTextAndChangeCorrectOptionWithoutReplacingIdsAndCanRetry() throws Exception {
        String payload=editBody(optionIds,2);assertEquals(200,edit(payload).getResponse().getStatus());
        assertEquals(200,edit(payload).getResponse().getStatus());assertEquals(optionIds,ids(questionId));
        assertEquals("Pertanyaan baru?",row().get("pertanyaan"));assertEquals("MENUNGGU",row().get("status_review"));
        assertNull(row().get("direview_at"));
        assertEquals(optionIds.get(2),jdbc.queryForObject("SELECT id FROM "+SCHEMA+".opsi_jawaban WHERE soal_id=? AND is_benar",Long.class,questionId));
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".opsi_jawaban WHERE soal_id=? AND teks_opsi LIKE 'Revisi %'",Integer.class,questionId));
    }
    @Test void approvalRecordsServerMentorAndTimeAndReplayPreservesAudit() throws Exception {
        var result=decision("approve",mentor);assertEquals(200,result.getResponse().getStatus());
        var saved=row();assertEquals("DISETUJUI",saved.get("status_review"));assertEquals(mentorId,saved.get("direview_oleh"));assertNotNull(saved.get("direview_at"));
        assertEquals(mentorId,body(result).path("direviewOleh").asLong());
        assertEquals(200,decision("approve",otherMentor).getResponse().getStatus());assertEquals(saved,row());
        assertEquals(0,body(http.perform(get("/api/mentor/soal").header("Authorization","Bearer "+mentor)).andReturn()).path("totalElements").asInt());
    }
    @Test void rejectionRecordsAuditAndIsIdempotent() throws Exception {
        assertEquals(200,decision("reject",mentor).getResponse().getStatus());var saved=row();
        assertEquals("DITOLAK",saved.get("status_review"));assertEquals(mentorId,saved.get("direview_oleh"));assertNotNull(saved.get("direview_at"));
        assertEquals(200,decision("reject",mentor).getResponse().getStatus());assertEquals(saved,row());
    }
    @Test void finalDecisionCannotBeReversedOrEdited() throws Exception {
        assertEquals(200,decision("approve",mentor).getResponse().getStatus());var saved=row();
        assertEquals(409,decision("reject",otherMentor).getResponse().getStatus());assertEquals(409,edit(editBody(optionIds,1)).getResponse().getStatus());assertEquals(saved,row());
    }
    @Test void invalidOptionSetsAreAtomicAndDoNotChangeQuestion() throws Exception {
        String valid=editBody(optionIds,0);
        for(String payload:List.of(valid.replace("\"benar\":true","\"benar\":false"),valid.replace("\"benar\":false","\"benar\":true"),editBody(optionIds.subList(0,3),0),editBody(List.of(optionIds.get(0),optionIds.get(0),optionIds.get(2),optionIds.get(3)),0),valid.replace("Pertanyaan baru?","   "))) {
            assertEquals(400,edit(payload).getResponse().getStatus());assertEquals("Fungsi akar?",row().get("pertanyaan"));assertEquals(optionIds,ids(questionId));
        }
    }
    @Test void sdRequiresExactlyThreeOptions() throws Exception {
        questionId=question("SD",3);optionIds=ids(questionId);
        assertEquals(200,edit(editBody(optionIds,1)).getResponse().getStatus());assertEquals(200,decision("approve",mentor).getResponse().getStatus());
    }
    @Test void optionsFromOtherQuestionsCannotBeAssigned() throws Exception {
        var foreign=ids(question("SMP",4));
        assertEquals(400,edit(editBody(foreign,0)).getResponse().getStatus());assertEquals(optionIds,ids(questionId));assertEquals("Fungsi akar?",row().get("pertanyaan"));
    }
    @Test void massAssignmentOfReviewMetadataIsRejected() throws Exception {
        String payload=editBody(optionIds,0).replaceFirst("\\{","{\"statusReview\":\"DISETUJUI\",\"direviewOleh\":"+otherMentorId+",");
        assertEquals(400,edit(payload).getResponse().getStatus());assertEquals("MENUNGGU",row().get("status_review"));assertNull(row().get("direview_oleh"));
    }
    @Test void malformedAndMissingOptionFieldsReturn400() throws Exception {
        for(String payload:List.of("{}","{\"pertanyaan\":\"x\",\"opsi\":[null,null,null]}",editBody(optionIds,0).replaceFirst("\"benar\":true,?",""),"{bad-json"))
            assertEquals(400,edit(payload).getResponse().getStatus());
        assertEquals("Fungsi akar?",row().get("pertanyaan"));
    }
    @Test void questionWithoutOneCorrectAnswerCannotBeApprovedButCanBeRejected() throws Exception {
        jdbc.update("UPDATE "+SCHEMA+".opsi_jawaban SET is_benar=false WHERE soal_id=?",questionId);
        assertEquals(422,decision("approve",mentor).getResponse().getStatus());assertEquals("MENUNGGU",row().get("status_review"));
        assertEquals(200,decision("reject",mentor).getResponse().getStatus());
    }
    @Test void duplicateOptionTextCannotBeEditedOrApproved() throws Exception {
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(editBody(optionIds,0));
        ((com.fasterxml.jackson.databind.node.ObjectNode)payload.path("opsi").get(1)).put("teks","  REVISI 1  ");
        assertEquals(400,edit(json.writeValueAsString(payload)).getResponse().getStatus());assertEquals("Fungsi akar?",row().get("pertanyaan"));
        jdbc.update("UPDATE "+SCHEMA+".opsi_jawaban SET teks_opsi='opsi 1' WHERE id=?",optionIds.get(1));
        assertEquals(422,decision("approve",mentor).getResponse().getStatus());assertEquals("MENUNGGU",row().get("status_review"));
        assertEquals(200,decision("reject",mentor).getResponse().getStatus());
    }
    @Test void databaseFailureRollsBackTextAndAnswerKeyWithoutLeakingSqlMessage() throws Exception {
        jdbc.execute("CREATE FUNCTION "+SCHEMA+".reject_edit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.teks_opsi LIKE 'Revisi%' THEN RAISE EXCEPTION 'private-question-diagnostic' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER review_failure BEFORE UPDATE ON "+SCHEMA+".opsi_jawaban FOR EACH ROW EXECUTE FUNCTION "+SCHEMA+".reject_edit()");
        try {
            var result=edit(editBody(optionIds,2));assertEquals(500,result.getResponse().getStatus());
            assertFalse(result.getResponse().getContentAsString().contains("private-question-diagnostic"));
            assertEquals("Fungsi akar?",row().get("pertanyaan"));
            assertEquals(optionIds.getFirst(),jdbc.queryForObject("SELECT id FROM "+SCHEMA+".opsi_jawaban WHERE soal_id=? AND is_benar",Long.class,questionId));
        } finally {
            jdbc.execute("DROP TRIGGER review_failure ON "+SCHEMA+".opsi_jawaban");jdbc.execute("DROP FUNCTION "+SCHEMA+".reject_edit()");
        }
    }
    @Test void heldRowLockHasBoundedWaitAndReturnsRetryableError() throws Exception {
        try(var c=DriverManager.getConnection(url(),user(),password())) {
            c.setAutoCommit(false);
            try(var st=c.prepareStatement("SELECT id FROM "+SCHEMA+".soal WHERE id=? FOR UPDATE")) {
                st.setLong(1,questionId);try(var ignored=st.executeQuery()) {
                    long start=System.nanoTime();var result=decision("approve",mentor);
                    assertEquals(503,result.getResponse().getStatus());assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(10));
                }
            } finally { c.rollback(); }
        }
        assertEquals("MENUNGGU",row().get("status_review"));assertEquals(200,decision("approve",mentor).getResponse().getStatus());
    }
    @Test void missingQuestionReturns404() throws Exception {
        questionId=Long.MAX_VALUE;
        assertEquals(404,decision("approve",mentor).getResponse().getStatus());assertEquals(404,decision("reject",mentor).getResponse().getStatus());assertEquals(404,edit(editBody(optionIds,0)).getResponse().getStatus());
    }
    @Test void paginationBoundsAndMalformedParametersReturn400() throws Exception {
        for(String query:List.of("page=-1","size=0","size=101","page=2147483647","page=abc"))
            assertEquals(400,http.perform(get("/api/mentor/soal?"+query).header("Authorization","Bearer "+mentor)).andReturn().getResponse().getStatus());
    }
    @Test void staleMentorTokenCannotReviewAfterRoleChanged() throws Exception {
        jdbc.update("UPDATE "+SCHEMA+".pengguna SET role='SISWA' WHERE id=?",mentorId);
        assertEquals(403,decision("approve",mentor).getResponse().getStatus());assertEquals("MENUNGGU",row().get("status_review"));
    }
    @Test void concurrentOpposingDecisionsHaveOneWinnerAndOneAudit() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return decision("approve",mentor).getResponse().getStatus();});
            var b=pool.submit(()->{start.await();return decision("reject",otherMentor).getResponse().getStatus();});start.countDown();
            assertEquals(List.of(200,409),java.util.stream.Stream.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)).sorted().toList());
            var saved=row();assertNotNull(saved.get("direview_at"));
            assertEquals("DISETUJUI".equals(saved.get("status_review"))?mentorId:otherMentorId,saved.get("direview_oleh"));
        }
    }
}
