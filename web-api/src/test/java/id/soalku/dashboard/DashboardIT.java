package id.soalku.dashboard;

import com.fasterxml.jackson.databind.*;
import id.soalku.auth.JwtService;
import id.soalku.core.Role;
import id.soalku.persistence.auth.*;
import id.soalku.practice.PracticeDtos;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Import(CountingSql.Config.class)
class DashboardIT {
    private static final String SCHEMA="soalku_it_dashboard_"+UUID.randomUUID().toString().replace("-","");
    private static final Instant NOW=Instant.parse("2026-10-18T00:00:00Z");
    private static final String JWT_KEY;
    static { byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);JWT_KEY=Base64.getEncoder().encodeToString(key); }
    @MockitoBean Clock clock;
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PenggunaRepository users;
    @Autowired JwtService tokens;
    @Autowired ObjectMapper json;
    private Long aId,bId,emptyId,mentorId,otherMentorId,qa,qb,qc,done,active,expired;
    private String a,b,empty,mentor,otherMentor;
    private List<Long> pending;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DashboardIT::url);p.add("spring.datasource.username",DashboardIT::user);
        p.add("spring.datasource.password",DashboardIT::password);p.add("spring.datasource.hikari.schema",()->SCHEMA);
        p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
        p.add("soalku.jwt.secret",()->JWT_KEY);
    }
    private static String url() {
        String value=System.getenv("SOALKU_TEST_DATABASE_URL");
        if(value==null || !value.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-z0-9_]+_test")) throw new IllegalStateException("Only local *_test allowed");return value;
    }
    private static String user() { return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_USERNAME","soalku_test"); }
    private static String password() { return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_PASSWORD",""); }
    private Long person(String name,Role role) {
        return users.saveAndFlush(new PenggunaEntity(name,UUID.randomUUID()+"@private.example.test","private-password-hash",role)).getId();
    }
    @BeforeEach void setup() throws Exception {
        when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        jdbc.execute("TRUNCATE TABLE "+SCHEMA+".pengguna CASCADE");
        aId=person("Siswa A",Role.SISWA);bId=person("Siswa B",Role.SISWA);emptyId=person("Belum latihan",Role.SISWA);
        mentorId=person("Mentor A",Role.MENTOR);otherMentorId=person("Mentor B",Role.MENTOR);
        a=tokens.issue(aId,Role.SISWA).accessToken();b=tokens.issue(bId,Role.SISWA).accessToken();empty=tokens.issue(emptyId,Role.SISWA).accessToken();
        mentor=tokens.issue(mentorId,Role.MENTOR).accessToken();otherMentor=tokens.issue(otherMentorId,Role.MENTOR).accessToken();
        Long aSection=section(aId),aSection2=section(aId),bSection=section(bId);
        qa=question(aSection,"DISETUJUI",NOW.minusSeconds(100));qb=question(aSection2,"DISETUJUI",NOW.minusSeconds(90));qc=question(bSection,"DISETUJUI",NOW.minusSeconds(80));
        pending=List.of(question(aSection,"MENUNGGU",NOW.minusSeconds(70)),question(bSection,"MENUNGGU",NOW.minusSeconds(70)),question(aSection2,"MENUNGGU",NOW.minusSeconds(60)));
        question(bSection,"DITOLAK",NOW.minusSeconds(50));
        done=session(aId,"ACAK",NOW.minusSeconds(60),NOW.minusSeconds(10),null,List.of(qa,qb));
        active=session(aId,"ACAK",NOW.minusSeconds(40),null,null,List.of(qa,qb));
        expired=session(aId,"BERWAKTU",NOW.minusSeconds(120),null,NOW.minusSeconds(60),List.of(qa,qb));
        answer(done,qa,true);answer(done,qb,false);answer(active,qa,true);answer(expired,qb,false);
        Long bDone=session(bId,"ACAK",NOW.minusSeconds(30),NOW.minusSeconds(15),null,List.of(qa,qc));answer(bDone,qa,true);answer(bDone,qc,true);
        session(bId,"ACAK",NOW.minusSeconds(20),null,null,List.of(qa,qc));
    }
    @AfterAll static void cleanup() throws Exception {
        try(var c=DriverManager.getConnection(url(),user(),password());var st=c.createStatement()) { st.execute("DROP SCHEMA "+SCHEMA+" CASCADE"); }
    }
    @AfterEach void resetSqlProbe() { CountingSql.reset(); }
    private Long section(Long owner) {
        var material=jdbc.queryForObject("INSERT INTO "+SCHEMA+".materi(judul,file_path,diunggah_oleh,jenjang) VALUES ('Biologi',?,?,'SD') RETURNING id",Long.class,"fixture/"+UUID.randomUUID(),owner);
        return jdbc.queryForObject("INSERT INTO "+SCHEMA+".bagian_materi(materi_id,judul_bagian,urutan) VALUES (?,'Bagian',1) RETURNING id",Long.class,material);
    }
    private Long question(Long section,String status,Instant created) {
        var id=jdbc.queryForObject("INSERT INTO "+SCHEMA+".soal(bagian_materi_id,pertanyaan,status_review,direview_oleh,direview_at,created_at) VALUES (?,'Pertanyaan antrean?',?,?,?,?) RETURNING id",Long.class,section,status,"MENUNGGU".equals(status)?null:mentorId,"MENUNGGU".equals(status)?null:java.sql.Timestamp.from(created),java.sql.Timestamp.from(created));
        for(int n=1;n<=3;n++) jdbc.update("INSERT INTO "+SCHEMA+".opsi_jawaban(soal_id,teks_opsi,urutan,is_benar) VALUES (?,?,?,?)",id,"Opsi "+n,n,n==1);
        return id;
    }
    private Long session(Long owner,String mode,Instant start,Instant finished,Instant end,List<Long> qs) throws Exception {
        var content=new ArrayList<PracticeDtos.Question>();
        for(Long q:qs) {
            Long section=jdbc.queryForObject("SELECT bagian_materi_id FROM "+SCHEMA+".soal WHERE id=?",Long.class,q);
            var opts=jdbc.query("SELECT id,urutan,teks_opsi FROM "+SCHEMA+".opsi_jawaban WHERE soal_id=? ORDER BY urutan",(rs,n)->new PracticeDtos.Option(rs.getLong(1),rs.getInt(2),rs.getString(3)),q);
            content.add(new PracticeDtos.Question(q,section,"Pertanyaan antrean?",null,opts));
        }
        return jdbc.queryForObject("INSERT INTO "+SCHEMA+".sesi_latihan(siswa_id,mode,mulai_at,selesai_at,berakhir_at,request_key,requested_count,soal_snapshot) VALUES (?,?,?,?,?,?,?,?::jsonb) RETURNING id",Long.class,
                owner,mode,java.sql.Timestamp.from(start),finished==null?null:java.sql.Timestamp.from(finished),end==null?null:java.sql.Timestamp.from(end),UUID.randomUUID(),qs.size(),json.writeValueAsString(content));
    }
    private void answer(Long session,Long q,boolean correct) {
        Long opt=jdbc.queryForObject("SELECT id FROM "+SCHEMA+".opsi_jawaban WHERE soal_id=? AND urutan=?",Long.class,q,correct?1:2);
        jdbc.update("INSERT INTO "+SCHEMA+".riwayat_jawaban(sesi_id,soal_id,opsi_dipilih,is_benar) VALUES (?,?,?,?)",session,q,opt,correct);
    }
    private MvcResult request(String path,String token) throws Exception {
        var req=get(path);if(token!=null) req.header("Authorization","Bearer "+token);return http.perform(req).andReturn();
    }
    private JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString()); }
    private JsonNode own(String token,String query) throws Exception {
        var result=request("/api/dashboard/siswa"+query,token);assertEquals(200,result.getResponse().getStatus());return body(result);
    }
    private JsonNode mentor(String query) throws Exception {
        var result=request("/api/mentor/dashboard"+query,mentor);assertEquals(200,result.getResponse().getStatus());return body(result);
    }
    @Test void studentProgressUsesOwnCountsWithoutJoinMultiplication() throws Exception {
        var p=own(a,"").path("progres");assertEquals(aId,p.path("siswaId").asLong());assertEquals(2,p.path("totalMateri").asLong());
        assertEquals(3,p.path("totalSesi").asLong());assertEquals(1,p.path("sesiSelesai").asLong());assertEquals(1,p.path("sesiBerjalan").asLong());assertEquals(1,p.path("sesiKedaluwarsa").asLong());
        assertEquals(4,p.path("totalJawaban").asLong());assertEquals(2,p.path("jumlahBenar").asLong());assertEquals(50.0,p.path("persentaseBenar").asDouble());assertEquals(33.33,p.path("persentaseSesiSelesai").asDouble());
        var other=own(b,"").path("progres");assertEquals(1,other.path("totalMateri").asLong());assertEquals(2,other.path("totalSesi").asLong());assertEquals(2,other.path("totalJawaban").asLong());assertEquals(100.0,other.path("persentaseBenar").asDouble());
    }
    @Test void ownHistoryIsPaginatedNewestFirstWithSessionScoresAndStatus() throws Exception {
        var first=own(a,"?page=0&size=1").path("riwayat");assertEquals(3,first.path("totalElements").asLong());assertEquals(3,first.path("totalPages").asInt());
        var latest=first.path("content").get(0);assertEquals(active,latest.path("sesiId").asLong());assertEquals("BERJALAN",latest.path("status").asText());assertEquals(2,latest.path("jumlahSoal").asInt());assertEquals(1,latest.path("totalJawaban").asLong());assertEquals(50.0,latest.path("persentasePenyelesaian").asDouble());
        var second=own(a,"?page=1&size=1").path("riwayat").path("content").get(0);assertEquals(done,second.path("sesiId").asLong());assertEquals("SELESAI",second.path("status").asText());assertEquals(50.0,second.path("persentaseBenar").asDouble());
        var last=own(a,"?page=2&size=1").path("riwayat").path("content").get(0);assertEquals(expired,last.path("sesiId").asLong());assertEquals("KEDALUWARSA",last.path("status").asText());
    }
    @Test void ownDashboardCannotBeRedirectedToAnotherStudentAndHasNoReviewKeys() throws Exception {
        var result=own(a,"?siswaId="+bId);assertEquals(aId,result.path("progres").path("siswaId").asLong());assertEquals(3,result.path("riwayat").path("totalElements").asLong());
        assertFalse(result.has("antreanReview"));assertFalse(result.toString().contains("Siswa B"));assertFalse(result.toString().contains("Pertanyaan antrean?"));assertFalse(result.toString().contains("private-password-hash"));
    }
    @Test void mentorHasPendingQueueAndGlobalStudentProgressAsAuthorizedByPrd2() throws Exception {
        var result=mentor("");assertEquals(3,result.path("totalMenungguReview").asLong());assertEquals(3,result.path("totalSiswa").asLong());
        assertEquals(3,result.path("antreanReview").path("content").size());assertEquals(3,result.path("siswa").path("content").size());
        var rows=result.path("siswa").path("content");assertEquals(aId,rows.get(0).path("siswaId").asLong());assertEquals(4,rows.get(0).path("totalJawaban").asLong());assertEquals(bId,rows.get(1).path("siswaId").asLong());
        assertEquals(100.0,rows.get(1).path("persentaseBenar").asDouble());assertEquals(emptyId,rows.get(2).path("siswaId").asLong());assertEquals(0,rows.get(2).path("totalSesi").asLong());
        for(var q:result.path("antreanReview").path("content")) assertEquals("MENUNGGU",q.path("statusReview").asText());
        var second=request("/api/mentor/dashboard",otherMentor);assertEquals(200,second.getResponse().getStatus());assertEquals(result,body(second));
    }
    @Test void mentorListsHaveIndependentPaginationAndStableTieOrdering() throws Exception {
        var result=mentor("?reviewPage=1&reviewSize=1&siswaPage=2&siswaSize=1");
        var queue=result.path("antreanReview");assertEquals(1,queue.path("content").size());assertEquals(pending.get(1),queue.path("content").get(0).path("id").asLong());assertEquals(3,queue.path("totalElements").asLong());
        var students=result.path("siswa");assertEquals(1,students.path("content").size());assertEquals(emptyId,students.path("content").get(0).path("siswaId").asLong());assertEquals(3,students.path("totalPages").asInt());
        assertEquals(pending.get(0),mentor("?reviewPage=0&reviewSize=1").path("antreanReview").path("content").get(0).path("id").asLong());
    }
    @Test void roleFiltersRejectMissingTokenAndWrongRoleBeforeReturningDashboard() throws Exception {
        assertEquals(401,request("/api/dashboard/siswa",null).getResponse().getStatus());assertEquals(401,request("/api/mentor/dashboard",null).getResponse().getStatus());
        assertEquals(403,request("/api/mentor/dashboard",a).getResponse().getStatus());assertEquals(403,request("/api/dashboard/siswa",mentor).getResponse().getStatus());
    }
    @Test void staleJwtRolesAreRejectedUsingCurrentDatabaseRole() throws Exception {
        jdbc.update("UPDATE "+SCHEMA+".pengguna SET role='MENTOR' WHERE id=?",aId);assertEquals(403,request("/api/dashboard/siswa",a).getResponse().getStatus());
        jdbc.update("UPDATE "+SCHEMA+".pengguna SET role='SISWA' WHERE id=?",mentorId);assertEquals(403,request("/api/mentor/dashboard",mentor).getResponse().getStatus());
    }
    @Test void paginationBoundsAreExplicitForEveryList() throws Exception {
        for(String query:List.of("?page=-1","?size=0","?size=101","?page=2147483647","?page=abc")) assertEquals(400,request("/api/dashboard/siswa"+query,a).getResponse().getStatus());
        for(String query:List.of("?reviewPage=-1","?reviewSize=0","?reviewSize=101","?siswaPage=-1","?siswaSize=0","?siswaSize=101","?siswaPage=2147483647","?reviewSize=abc")) assertEquals(400,request("/api/mentor/dashboard"+query,mentor).getResponse().getStatus());
    }
    @Test void emptyProgressDoesNotInventAccuracyOrCompletionPercentage() throws Exception {
        var result=own(empty,"");var p=result.path("progres");assertEquals(0,p.path("totalMateri").asLong());assertEquals(0,p.path("totalJawaban").asLong());
        assertTrue(p.path("persentaseBenar").isNull());assertTrue(p.path("persentaseSesiSelesai").isNull());assertTrue(result.path("riwayat").path("content").isEmpty());
        assertEquals(0,result.path("riwayat").path("totalElements").asLong());
    }
    @Test void mentorResponseHasNoEmailPasswordTokenOrOriginalMaterialPaths() throws Exception {
        String response=mentor("").toString();
        for(String secret:List.of("private.example.test","private-password-hash","passwordHash","password_hash","fixture/","accessToken")) assertFalse(response.contains(secret));
    }
    @Test void legacySessionsHaveUnknownTargetCountButTheirAnswersAreCounted() throws Exception {
        Long id=jdbc.queryForObject("INSERT INTO "+SCHEMA+".sesi_latihan(siswa_id,mode,mulai_at) VALUES (?,'ACAK',?) RETURNING id",Long.class,aId,java.sql.Timestamp.from(NOW.minusSeconds(3600)));answer(id,qa,true);
        var row=own(a,"?page=3&size=1").path("riwayat").path("content").get(0);assertEquals(id,row.path("sesiId").asLong());
        assertTrue(row.path("jumlahSoal").isNull());assertTrue(row.path("persentasePenyelesaian").isNull());assertEquals(1,row.path("totalJawaban").asLong());assertEquals(100.0,row.path("persentaseBenar").asDouble());
    }
    @Test void expiryAtBoundaryIsConsistentBetweenSummaryAndHistory() throws Exception {
        jdbc.update("UPDATE "+SCHEMA+".sesi_latihan SET mode='BERWAKTU',berakhir_at=? WHERE id=?",java.sql.Timestamp.from(NOW),active);
        var result=own(a,"");assertEquals(2,result.path("progres").path("sesiKedaluwarsa").asLong());assertEquals(0,result.path("progres").path("sesiBerjalan").asLong());
        assertEquals("KEDALUWARSA",result.path("riwayat").path("content").get(0).path("status").asText());
    }
    @Test void mentorReadQueryCountIsConstantAsStudentListGrows() throws Exception {
        for(int n=0;n<2;n++) person("Tambahan "+n,Role.SISWA);
        CountingSql.start();List<String> first;
        try { assertEquals(5,mentor("?reviewSize=1&siswaSize=2").path("totalSiswa").asLong()); } finally { first=CountingSql.stop(); }
        for(int n=2;n<17;n++) person("Tambahan "+n,Role.SISWA);
        CountingSql.start();List<String> larger;
        try { assertEquals(20,mentor("?reviewSize=1&siswaSize=2").path("totalSiswa").asLong()); } finally { larger=CountingSql.stop(); }
        assertEquals(1,first.stream().filter(sql->sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("with page_users")).count());
        assertEquals(first.size(),larger.size());assertTrue(larger.size()<=7,"Unexpected read queries: "+larger.size());
    }
    @Test void studentReadQueryCountDoesNotGrowWithSessionHistory() throws Exception {
        CountingSql.start();List<String> first;
        try { assertEquals(3,own(a,"?size=1").path("riwayat").path("totalElements").asLong()); } finally { first=CountingSql.stop(); }
        for(int n=0;n<20;n++) jdbc.update("INSERT INTO "+SCHEMA+".sesi_latihan(siswa_id,mode) VALUES (?,'ACAK')",aId);
        CountingSql.start();List<String> larger;
        try { assertEquals(23,own(a,"?size=1").path("riwayat").path("totalElements").asLong()); } finally { larger=CountingSql.stop(); }
        assertEquals(first.size(),larger.size());assertTrue(larger.size()<=3,"Unexpected read queries: "+larger.size());
    }
    @Test void emptyPagesPreserveTotalsAndEmptyReviewQueueDoesNotHideStudents() throws Exception {
        var own=own(a,"?page=99&size=1").path("riwayat");assertTrue(own.path("content").isEmpty());assertEquals(3,own.path("totalElements").asLong());
        var out=mentor("?reviewPage=99&reviewSize=1&siswaPage=99&siswaSize=1");
        assertTrue(out.path("antreanReview").path("content").isEmpty());assertTrue(out.path("siswa").path("content").isEmpty());assertEquals(3,out.path("totalSiswa").asLong());assertEquals(3,out.path("totalMenungguReview").asLong());
        jdbc.update("UPDATE "+SCHEMA+".soal SET status_review='DITOLAK',direview_oleh=?,direview_at=? WHERE status_review='MENUNGGU'",mentorId,java.sql.Timestamp.from(NOW));
        var emptyQueue=mentor("");assertEquals(0,emptyQueue.path("totalMenungguReview").asLong());assertTrue(emptyQueue.path("antreanReview").path("content").isEmpty());assertEquals(3,emptyQueue.path("siswa").path("content").size());
    }
    @Test void historyWithTiedStartTimeUsesDescendingIdAcrossPages() throws Exception {
        jdbc.update("UPDATE "+SCHEMA+".sesi_latihan SET mulai_at=? WHERE id=?",java.sql.Timestamp.from(NOW.minusSeconds(60)),active);
        assertEquals(active,own(a,"?size=1&page=0").path("riwayat").path("content").get(0).path("sesiId").asLong());
        assertEquals(done,own(a,"?size=1&page=1").path("riwayat").path("content").get(0).path("sesiId").asLong());
    }
    @Test void blockedReadTimesOutWithoutLeakingSqlAndCanBeRetried() throws Exception {
        try(var c=DriverManager.getConnection(url(),user(),password())) {
            c.setAutoCommit(false);
            try(var st=c.createStatement()) {
                st.execute("LOCK TABLE "+SCHEMA+".sesi_latihan IN ACCESS EXCLUSIVE MODE");
                long began=System.nanoTime();var result=request("/api/dashboard/siswa",a);
                assertEquals(503,result.getResponse().getStatus());assertEquals("DASHBOARD_BUSY",body(result).path("code").asText());
                assertTrue(System.nanoTime()-began<java.util.concurrent.TimeUnit.SECONDS.toNanos(10));assertFalse(result.getResponse().getContentAsString().contains(SCHEMA));
            } finally { c.rollback(); }
        }
        assertEquals(3,own(a,"").path("progres").path("totalSesi").asLong());
    }
    @Test void concurrentCommittedSessionDoesNotMakeSummaryAndHistoryDisagree() throws Exception {
        CountingSql.beforeNextHistory(()->{
            try(var c=DriverManager.getConnection(url(),user(),password());var st=c.prepareStatement("INSERT INTO "+SCHEMA+".sesi_latihan(siswa_id,mode) VALUES (?,'ACAK')")) {
                st.setLong(1,aId);assertEquals(1,st.executeUpdate()); // Separate autocommit transaction between the dashboard reads.
            } catch(java.sql.SQLException failure) { throw new IllegalStateException("Concurrent fixture insert failed",failure); }
        });
        var result=own(a,"");assertEquals(3,result.path("progres").path("totalSesi").asLong());
        assertEquals(3,result.path("riwayat").path("content").size());assertEquals(3,result.path("riwayat").path("totalElements").asLong());
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".sesi_latihan WHERE siswa_id=?",Long.class,aId));
    }
}
