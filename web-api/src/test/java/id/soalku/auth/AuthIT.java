package id.soalku.auth;

import java.sql.DriverManager;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import({AuthIT.MentorProbe.class, AuthIT.MentorFilterProbe.class})
@ExtendWith(OutputCaptureExtension.class)
class AuthIT {
    private static final String SCHEMA = "soalku_it_auth_" + UUID.randomUUID().toString().replace("-", "");
    private static final byte[] SECRET = new byte[32];
    private static final String PASSWORD = "Test-passphrase-123";
    static { new SecureRandom().nextBytes(SECRET); }

    private final MockMvc http;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    @Autowired
    AuthIT(MockMvc http, ObjectMapper json, JdbcTemplate jdbc) {
        this.http = http;
        this.json = json;
        this.jdbc = jdbc;
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", AuthIT::databaseUrl);
        properties.add("spring.datasource.username", AuthIT::databaseUser);
        properties.add("spring.datasource.password", AuthIT::databasePassword);
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        properties.add("soalku.jwt.secret", () -> Base64.getEncoder().encodeToString(SECRET));
        properties.add("soalku.jwt.issuer", () -> "soalku");
        properties.add("soalku.jwt.ttl", () -> "15m");
    }

    @BeforeEach
    void clearAccounts() {
        MentorFilterProbe.calls.set(0);
        jdbc.update("DELETE FROM " + SCHEMA + ".kode_undangan");
        jdbc.update("DELETE FROM " + SCHEMA + ".pengguna");
    }

    @Test
    void invalidInvitationCannotCreateMentor() throws Exception {
        assertEquals(400, register("mentor@example.test", "MENTOR", "not-a-valid-code"));
        assertEquals(0, count("pengguna"));
    }

    @Test
    void expiredInvitationRemainsUnusedAndCannotCreateMentor() throws Exception {
        invite("expired", "AKTIF", Instant.now().minusSeconds(60));
        assertEquals(400, register("mentor@example.test", "MENTOR", "expired"));
        assertEquals(1, count("pengguna"));
        assertEquals("AKTIF", jdbc.queryForObject("SELECT status FROM " + SCHEMA + ".kode_undangan WHERE kode='expired'", String.class));
    }

    @Test
    void missingRevokedAndUsedInvitationsAreRejected() throws Exception {
        assertEquals(400, register("a@example.test", "MENTOR", ""));
        invite("revoked", "DICABUT", Instant.now().plusSeconds(3600));
        invite("used", "DIGUNAKAN", Instant.now().plusSeconds(3600));
        assertEquals(400, register("b@example.test", "MENTOR", "revoked"));
        assertEquals(400, register("c@example.test", "MENTOR", "used"));
        assertEquals(1, count("pengguna"));
    }

    @Test
    void studentCanRegisterLoginAndReadOnlyTheirOwnIdentity() throws Exception {
        assertEquals(201, register("Student@Example.test", "SISWA", null));
        String hash = jdbc.queryForObject("SELECT password_hash FROM " + SCHEMA + ".pengguna", String.class);
        assertNotEquals(PASSWORD, hash);
        assertTrue(new BCryptPasswordEncoder().matches(PASSWORD, hash));
        String token = login("student@example.test");
        SignedJWT signed = SignedJWT.parse(token);
        assertTrue(signed.verify(new MACVerifier(SECRET)));
        assertEquals("SISWA", signed.getJWTClaimsSet().getStringClaim("role"));
        assertEquals("soalku", signed.getJWTClaimsSet().getIssuer());
        assertNotNull(signed.getJWTClaimsSet().getExpirationTime());
        var response = http.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        JsonNode account = json.readTree(response.getContentAsString());
        assertEquals("student@example.test", account.path("email").asText());
        assertFalse(response.getContentAsString().contains("password"));
        assertEquals(403, http.perform(get("/__test/mentor").header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus());
    }

    @Test
    void mentorRegistrationConsumesCodeOnceAndJwtGrantsMentorAuthority() throws Exception {
        invite("single-use", "AKTIF", Instant.now().plusSeconds(3600));
        assertEquals(201, register("mentor@example.test", "MENTOR", "single-use"));
        assertEquals(400, register("other@example.test", "MENTOR", "single-use"));
        assertEquals("DIGUNAKAN", jdbc.queryForObject("SELECT status FROM " + SCHEMA + ".kode_undangan WHERE kode='single-use'", String.class));
        String token = login("mentor@example.test");
        assertEquals("MENTOR", SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("role"));
        assertEquals(200, http.perform(get("/__test/mentor").header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus());
    }

    @Test
    void duplicateEmailDoesNotConsumeAnInvitation() throws Exception {
        assertEquals(201, register("same@example.test", "SISWA", null));
        invite("retry", "AKTIF", Instant.now().plusSeconds(3600));
        assertEquals(409, register("SAME@example.test", "MENTOR", "retry"));
        assertEquals("AKTIF", jdbc.queryForObject("SELECT status FROM " + SCHEMA + ".kode_undangan WHERE kode='retry'", String.class));
    }

    @Test
    void concurrentRegistrationsCannotShareAnInvitation() throws Exception {
        invite("racing", "AKTIF", Instant.now().plusSeconds(3600));
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Integer> first = () -> { barrier.await(10, TimeUnit.SECONDS); return register("first@example.test", "MENTOR", "racing"); };
            Callable<Integer> second = () -> { barrier.await(10, TimeUnit.SECONDS); return register("second@example.test", "MENTOR", "racing"); };
            var a = workers.submit(first);
            var b = workers.submit(second);
            var statuses = java.util.List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(s -> s == 201).count());
            assertEquals(1, statuses.stream().filter(s -> s == 400).count());
            assertEquals(2, count("pengguna")); // seeded inviter plus exactly one registered Mentor
        }
    }

    @Test
    void wrongPasswordUnknownEmailAndMissingTokenReturn401() throws Exception {
        assertEquals(201, register("student@example.test", "SISWA", null));
        assertEquals(401, postJson("/api/auth/login", Map.of("email", "student@example.test", "password", "wrong-password")));
        assertEquals(401, postJson("/api/auth/login", Map.of("email", "unknown@example.test", "password", PASSWORD)));
        assertEquals(401, http.perform(get("/api/auth/me")).andReturn().getResponse().getStatus());
    }

    @Test
    void forgedExpiredAndInvalidRoleTokensAreRejected() throws Exception {
        assertEquals(401, http.perform(get("/api/auth/me").header("Authorization", "Bearer invalid.token.value")).andReturn().getResponse().getStatus());
        var original = SignedJWT.parse(signedToken("SISWA", Instant.now().plusSeconds(300)));
        var forged = new SignedJWT(original.getHeader(), original.getJWTClaimsSet());
        byte[] wrongKey = SECRET.clone();
        wrongKey[0] ^= 1;
        forged.sign(new MACSigner(wrongKey));
        assertEquals(401, http.perform(get("/api/auth/me").header("Authorization", "Bearer " + forged.serialize())).andReturn().getResponse().getStatus());
        assertEquals(401, http.perform(get("/api/auth/me").header("Authorization", "Bearer " + signedToken("SISWA", Instant.now().minusSeconds(120)))).andReturn().getResponse().getStatus());
        assertEquals(401, http.perform(get("/api/auth/me").header("Authorization", "Bearer " + signedToken("ADMIN", Instant.now().plusSeconds(300)))).andReturn().getResponse().getStatus());
        assertEquals(401, http.perform(get("/api/auth/me").header("Authorization", "Bearer " + signedToken(null, Instant.now().plusSeconds(300)))).andReturn().getResponse().getStatus());
    }

    @Test
    void invalidRoleMassAssignmentAndOversizedBcryptPasswordReturn400() throws Exception {
        assertEquals(400, register("a@example.test", "ADMIN", null));
        assertEquals(400, postJson("/api/auth/register", Map.of("nama", "A", "email", "b@example.test", "password", PASSWORD, "role", "SISWA", "passwordHash", "injected")));
        assertEquals(400, postJson("/api/auth/register", Map.of("nama", "A", "email", "c@example.test", "password", "é".repeat(40), "role", "SISWA")));
        assertEquals(0, count("pengguna"));
    }

    @Test
    void healthChecksDatabaseWithoutRevealingDetails() throws Exception {
        var response = http.perform(get("/actuator/health")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("UP", json.readTree(response.getContentAsString()).path("status").asText());
        assertFalse(response.getContentAsString().contains("jdbc:"));
    }

    @Test
    void siswaJwtGets403BeforeUnannotatedMentorControllerRuns() throws Exception {
        assertEquals(201, register("student-guard@example.test", "SISWA", null));
        String token = login("student-guard@example.test");
        for (String path : mentorPaths()) {
            for (var request : java.util.List.of(get(path), post(path))) {
                var response = http.perform(request.header("Authorization", "Bearer " + token))
                        .andReturn().getResponse();
                assertEquals(403, response.getStatus(), "SISWA must be denied before controller execution");
                assertEquals("FORBIDDEN", json.readTree(response.getContentAsString()).path("code").asText());
                assertNotNull(response.getHeader("X-Request-ID"));
            }
        }
        assertEquals(0, MentorFilterProbe.calls.get(), "Denied requests must not execute business handlers");
    }

    @Test
    void mentorJwtCanReachUnannotatedMentorController() throws Exception {
        invite("guard-code", "AKTIF", Instant.now().plusSeconds(3600));
        assertEquals(201, register("mentor-guard@example.test", "MENTOR", "guard-code"));
        String token = login("mentor-guard@example.test");
        for (String path : mentorPaths()) {
            for (var request : java.util.List.of(get(path), post(path))) {
                assertEquals(200, http.perform(request.header("Authorization", "Bearer " + token))
                        .andReturn().getResponse().getStatus());
            }
        }
        assertEquals(6, MentorFilterProbe.calls.get());
    }

    @Test
    void missingOrExpiredJwtGets401BeforeMentorControllerRuns() throws Exception {
        for (String path : mentorPaths()) {
            assertEquals(401, http.perform(get(path)).andReturn().getResponse().getStatus());
            assertEquals(401, http.perform(post(path)).andReturn().getResponse().getStatus());
        }
        String expired = signedToken("MENTOR", Instant.now().minusSeconds(120));
        assertEquals(401, http.perform(get("/api/mentor").header("Authorization", "Bearer " + expired))
                .andReturn().getResponse().getStatus());
        assertEquals(0, MentorFilterProbe.calls.get());
    }

    private static java.util.List<String> mentorPaths() {
        return java.util.List.of("/api/mentor", "/api/mentor/review-guard-probe", "/api/mentor/review-guard-probe/nested");
    }

    @Test
    void successRejectionAndMalformedRequestsHaveCorrelatedCompletionLogs(CapturedOutput output) throws Exception {
        String previousMdc = org.slf4j.MDC.get("request_id");
        var success = http.perform(get("/actuator/health").header("X-Request-ID", "invalid request id"))
                .andReturn().getResponse();
        assertEquals(200, success.getStatus());
        String generated = success.getHeader("X-Request-ID");
        assertNotNull(generated);
        assertDoesNotThrow(() -> UUID.fromString(generated));
        var rejected = http.perform(get("/api/auth/me").header("X-Request-ID", "request-unauthorized"))
                .andReturn().getResponse();
        assertEquals(401, rejected.getStatus());
        var malformed = http.perform(post("/api/auth/register").header("X-Request-ID", "request-malformed")
                .contentType("application/json").content("{\"password\":\"do-not-log-this-password\","))
                .andReturn().getResponse();
        assertEquals(400, malformed.getStatus());
        assertEquals("request-unauthorized", rejected.getHeader("X-Request-ID"));
        assertEquals("request-malformed", malformed.getHeader("X-Request-ID"));
        assertEquals(previousMdc, org.slf4j.MDC.get("request_id"));
        var expected = Map.of(generated, 200, "request-unauthorized", 401, "request-malformed", 400);
        var actual = new java.util.HashMap<String, Integer>();
        for (String line : output.getOut().lines().filter(value -> value.startsWith("{")).toList()) {
            JsonNode event = json.readTree(line);
            if ("http.request.completed".equals(event.path("event").asText())) {
                String id = event.path("request_id").asText();
                if (expected.containsKey(id)) {
                    assertTrue(event.path("duration_ms").isNumber());
                    assertTrue(event.path("duration_ms").asLong() >= 0);
                    assertFalse(event.path("path").asText().isBlank());
                    actual.put(id, event.path("status").asInt());
                }
            }
        }
        assertEquals(expected, actual);
        assertFalse(output.getAll().contains("do-not-log-this-password"));
        assertFalse(output.getAll().contains("invalid request id"));
    }

    private int register(String email, String role, String code) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("nama", "Pengguna Test"); body.put("email", email); body.put("password", PASSWORD); body.put("role", role);
        if (code != null) body.put("kodeUndangan", code);
        return postJson("/api/auth/register", body);
    }

    private int postJson(String path, Object body) throws Exception {
        return http.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body))).andReturn().getResponse().getStatus();
    }

    private String login(String email) throws Exception {
        var response = http.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("email", email, "password", PASSWORD)))).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        JsonNode body = json.readTree(response.getContentAsString());
        assertEquals("Bearer", body.path("tokenType").asText());
        assertTrue(body.path("expiresIn").asLong() > 0);
        return body.path("accessToken").asText();
    }

    private void invite(String code, String status, Instant expires) {
        jdbc.update("INSERT INTO " + SCHEMA + ".pengguna(nama,email,password_hash,role) VALUES ('Seed Mentor','seed@example.test','test-only-hash','MENTOR') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO " + SCHEMA + ".kode_undangan(kode,dibuat_oleh,status,created_at,expired_at) SELECT ?,id,?,?,? FROM " + SCHEMA + ".pengguna WHERE email='seed@example.test'",
                code, status, java.sql.Timestamp.from(Instant.now().minusSeconds(86400)), java.sql.Timestamp.from(expires));
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + "." + table, Long.class); }

    private String signedToken(String role, Instant expiry) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("1").issuer("soalku").audience("soalku-api")
                .issueTime(Date.from(expiry.minusSeconds(300))).expirationTime(Date.from(expiry)).claim("role", role).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET));
        return jwt.serialize();
    }

    private static String databaseUrl() {
        String value = System.getenv("SOALKU_TEST_DATABASE_URL");
        if (value == null || !value.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-z0-9_]+_test"))
            throw new IllegalStateException("Set SOALKU_TEST_DATABASE_URL to a local *_test database");
        return value;
    }
    private static String databaseUser() { return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_USERNAME", "soalku_test"); }
    private static String databasePassword() { return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_PASSWORD", ""); }

    @AfterAll
    static void removeTestSchema() throws Exception {
        try (var connection = DriverManager.getConnection(databaseUrl(), databaseUser(), databasePassword());
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @RestController
    static class MentorProbe {
        @GetMapping("/__test/mentor")
        @PreAuthorize("hasRole('MENTOR')")
        String mentorOnly() { return "mentor-authorized"; }
    }

    // Intentionally no @PreAuthorize: these fixtures test the request filter itself.
    @RestController
    static class MentorFilterProbe {
        private static final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        @GetMapping({"/api/mentor", "/api/mentor/review-guard-probe", "/api/mentor/review-guard-probe/nested"})
        String getMentorResource() { calls.incrementAndGet(); return "mentor-filter-authorized"; }

        @PostMapping({"/api/mentor", "/api/mentor/review-guard-probe", "/api/mentor/review-guard-probe/nested"})
        String changeMentorResource() { calls.incrementAndGet(); return "mentor-filter-authorized"; }
    }
}
