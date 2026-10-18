package id.soalku.ai;

import id.soalku.core.*;
import id.soalku.auth.JwtService;
import id.soalku.persistence.auth.*;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Mock the provider port; keep orchestration, authorization and PostgreSQL transactions real. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class MaterialGenerationMockIT {
    private static final String SCHEMA="soalku_it_mock_"+UUID.randomUUID().toString().replace("-","");
    private static final String JWT_KEY;
    static { byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);JWT_KEY=Base64.getEncoder().encodeToString(key); }
    @MockitoBean QuestionGeneratorService generator;
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PenggunaRepository users;
    @Autowired JwtService tokens;
    @Autowired GeminiProperties config;
    private Long materialId;
    private String token;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",MaterialGenerationMockIT::url);
        properties.add("spring.datasource.username",MaterialGenerationMockIT::user);
        properties.add("spring.datasource.password",MaterialGenerationMockIT::password);
        properties.add("spring.datasource.hikari.schema",() -> SCHEMA);
        properties.add("spring.flyway.default-schema",() -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema",() -> SCHEMA);
        properties.add("soalku.jwt.secret",() -> JWT_KEY);
        properties.add("soalku.gemini.questions-per-section",() -> "1");
    }
    private static String url() {
        String value=System.getenv("SOALKU_TEST_DATABASE_URL");
        if(value==null || !value.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-z0-9_]+_test"))
            throw new IllegalStateException("Only local *_test database allowed");
        return value;
    }
    private static String user() { return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_USERNAME","soalku_test"); }
    private static String password() { return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_PASSWORD",""); }
    @BeforeEach void source() {
        var student=users.saveAndFlush(new PenggunaEntity("Student","mock"+UUID.randomUUID()+"@example.test","test-hash",Role.SISWA));
        token=tokens.issue(student.getId(),Role.SISWA).accessToken();
        materialId=jdbc.queryForObject("INSERT INTO "+SCHEMA+".materi(judul,file_path,diunggah_oleh,jenjang) VALUES (?,?,?,'SMP') RETURNING id",
                Long.class,"Materi mock","fixture/"+UUID.randomUUID()+".docx",student.getId());
        jdbc.update("INSERT INTO "+SCHEMA+".bagian_materi(materi_id,judul_bagian,teks_asli,urutan) VALUES (?,?,?,1),(?,?,?,2)",
                materialId,"Akar","Akar menyerap air. Teks lengkap bagian pertama.",materialId,"Daun","Daun membantu membuat makanan. Teks lengkap bagian kedua.");
    }
    @AfterAll static void cleanup() throws Exception {
        try(var connection=DriverManager.getConnection(url(),user(),password());var statement=connection.createStatement()) {
            statement.execute("DROP SCHEMA "+SCHEMA+" CASCADE");
        }
    }
    private QuestionGeneratorService.GeneratedSection generated() {
        Soal question=new SoalPilihanGanda(Jenjang.SMP,"Apa fungsi akar?",List.of(new OpsiJawaban("Menyerap air",1,true),
                new OpsiJawaban("Terbang",2,false),new OpsiJawaban("Berenang",3,false),new OpsiJawaban("Berlari",4,false)),null);
        return new QuestionGeneratorService.GeneratedSection("Ringkasan fixture deterministik.",List.of(question));
    }
    private int generate() throws Exception {
        return http.perform(post("/api/materi/"+materialId+"/generate").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus();
    }
    private int questions() {
        return jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".soal s JOIN "+SCHEMA+".bagian_materi b ON b.id=s.bagian_materi_id WHERE b.materi_id=?",Integer.class,materialId);
    }
    @Test void mockPortPersistsResultsAndReplayMakesNoProviderCalls() throws Exception {
        assertFalse(config.configured(),"The test profile must override inherited live credentials");
        assertEquals("127.0.0.1",config.baseUrl().getHost());assertEquals(9,config.baseUrl().getPort());
        when(generator.generate(any())).thenReturn(generated());
        assertEquals(200,generate());assertEquals(2,questions());
        var captured=org.mockito.ArgumentCaptor.forClass(QuestionGeneratorService.Request.class);
        verify(generator,times(2)).generate(captured.capture());
        assertEquals("Akar menyerap air. Teks lengkap bagian pertama.",captured.getAllValues().getFirst().teks());
        assertEquals(4,captured.getAllValues().getFirst().jumlahOpsi());
        assertEquals(200,generate());assertEquals(2,questions());verifyNoMoreInteractions(generator);
    }
    @Test void mockTimeoutRetainsSourceAndSuccessfulPartAndRetryResumes() throws Exception {
        when(generator.generate(any())).thenReturn(generated()).thenThrow(new QuestionGenerationException(
                QuestionGenerationException.Reason.TIMEOUT,"Timeout fixture")).thenReturn(generated());
        assertEquals(504,generate());assertEquals(1,questions());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".bagian_materi WHERE materi_id=?",Integer.class,materialId));
        assertEquals(200,generate());assertEquals(2,questions());
        verify(generator,times(3)).generate(any());assertEquals(200,generate());verifyNoMoreInteractions(generator);
    }
}
