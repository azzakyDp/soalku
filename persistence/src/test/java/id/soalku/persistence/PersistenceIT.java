package id.soalku.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL only; opt in with -Ppostgres-it against a local *_test database. */
class PersistenceIT {

    private static final Set<String> TABLES = Set.of("pengguna", "kode_undangan", "materi",
            "bagian_materi", "gambar_materi", "soal", "opsi_jawaban", "sesi_latihan", "riwayat_jawaban");

    @Test
    void generationMigrationCanBeReappliedRolledBackAndEnforcesNewInvariants() throws Exception {
        inSchema((connection, initial) -> {
            initial.migrate();
            var latest=Flyway.configure().dataSource(databaseUrl(),databaseUser(),databasePassword())
                    .defaultSchema(connection.getSchema()).locations("classpath:db/migration")
                    .configuration(java.util.Map.of("flyway.postgresql.transactional.lock","false")).target("3").load();
            assertEquals(2,latest.migrate().migrationsExecuted);
            assertEquals(0,latest.migrate().migrationsExecuted);
            // Test-only simulation of retry after a partial non-transactional migration.
            execute(connection,"DELETE FROM flyway_schema_history WHERE version='3'");
            assertEquals(1,latest.migrate().migrationsExecuted);
            String rollback;
            try(var stream=PersistenceIT.class.getResourceAsStream("/db/rollback/U3__generation_state.sql")) {
                assertNotNull(stream);rollback=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
            }
            for(int repeat=0;repeat<2;repeat++) {
                for(String command:rollback.split("\\n(?=ALTER TABLE|DROP INDEX|DELETE FROM|RESET)")) execute(connection,command);
                assertEquals(0,scalar(connection,"SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND column_name='generated_at'"));
            }
            assertEquals(1,latest.migrate().migrationsExecuted);
            execute(connection,"""
                    INSERT INTO pengguna(id,nama,email,password_hash,role) VALUES(1,'A','a@example.test','hash','SISWA');
                    INSERT INTO materi(id,judul,file_path,diunggah_oleh,jenjang) VALUES(1,'A','original.pdf',1,'SD');
                    INSERT INTO bagian_materi(id,materi_id,judul_bagian,urutan) VALUES(1,1,'A',1),(2,1,'B',2);
                    INSERT INTO gambar_materi(id,materi_id,bagian_materi_id,file_path) VALUES(1,1,1,'a.png'),(2,1,2,'b.png');
                    INSERT INTO soal(bagian_materi_id,pertanyaan,gambar_id,urutan_generasi) VALUES(1,'A?',1,1);
                    """);
            rejected(connection,"23505","INSERT INTO soal(bagian_materi_id,pertanyaan,urutan_generasi) VALUES(1,'Duplicate?',1)");
            rejected(connection,"23503","INSERT INTO soal(bagian_materi_id,pertanyaan,gambar_id,urutan_generasi) VALUES(1,'Foreign?',2,2)");
            rejected(connection,"23514","UPDATE bagian_materi SET generated_at=now() WHERE id=1");
            rejected(connection,"23514","UPDATE bagian_materi SET generation_token='00000000-0000-0000-0000-000000000001' WHERE id=1");
            assertThrows(SQLException.class,() -> {
                for(String command:rollback.split("\\n(?=ALTER TABLE|DROP INDEX|DELETE FROM|RESET)")) execute(connection,command);
            });
            assertEquals(1,scalar(connection,"SELECT count(*) FROM soal"));
        });
    }

    @Test
    void materialPathIndexSupportsRetryAndReversibleMigration() throws Exception {
        inSchema((connection, initial) -> {
            initial.migrate();
            var latest = Flyway.configure().dataSource(databaseUrl(), databaseUser(), databasePassword())
                    .defaultSchema(connection.getSchema()).locations("classpath:db/migration")
                    .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false")).target("2").load();
            assertEquals(1, latest.migrate().migrationsExecuted);
            assertEquals(0, latest.migrate().migrationsExecuted);
            execute(connection, """
                    INSERT INTO pengguna(nama,email,password_hash,role) VALUES ('A','a@example.test','hash','SISWA');
                    INSERT INTO materi(judul,file_path,diunggah_oleh,jenjang)
                    SELECT 'A','private/original.pdf',id,'SD' FROM pengguna;
                    """);
            rejected(connection, "23505", "INSERT INTO materi(judul,file_path,diunggah_oleh,jenjang) SELECT 'B','private/original.pdf',id,'SD' FROM pengguna");
            String rollback;
            try (var stream = PersistenceIT.class.getResourceAsStream("/db/rollback/U2__unique_material_file_path.sql")) {
                assertNotNull(stream);
                rollback = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                for (String command : rollback.split("\\n(?=DROP INDEX|DELETE FROM|RESET)")) execute(connection, command);
                assertEquals(1, scalar(connection,"SELECT count(*) FROM materi"));
            }
            assertEquals(1, latest.migrate().migrationsExecuted);
            assertEquals(0, latest.migrate().migrationsExecuted);
        });
    }

    @Test
    void migrateIsRepeatableAndCreatesAllTablesWithTimestamps() throws Exception {
        inSchema((connection, flyway) -> {
            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            Set<String> actual = new TreeSet<>();
            try (var rows = connection.createStatement().executeQuery("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = current_schema() AND table_name <> 'flyway_schema_history'
                    """)) {
                while (rows.next()) actual.add(rows.getString(1));
            }
            assertEquals(TABLES, actual);
            assertEquals(9, scalar(connection, """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = current_schema() AND column_name = 'created_at'
                        AND is_nullable = 'NO'
                    """));
            assertEquals(0, scalar(connection, """
                    SELECT count(*) FROM pg_constraint c
                    WHERE c.contype = 'f' AND c.connamespace = current_schema()::regnamespace
                    AND NOT EXISTS (
                        SELECT 1 FROM pg_index i
                        WHERE i.indrelid = c.conrelid AND i.indisvalid AND i.indpred IS NULL
                        AND (SELECT array_agg(k.attnum ORDER BY k.position)
                             FROM unnest(i.indkey) WITH ORDINALITY AS k(attnum, position)
                             WHERE k.position <= cardinality(c.conkey)) = c.conkey
                    )
                    """), "Every FK must have an index with matching leading columns");
        });
    }

    @Test
    void constraintsRejectInvalidRolesDuplicatesAndCrossQuestionAnswers() throws Exception {
        inSchema((connection, flyway) -> {
            flyway.migrate();
            execute(connection, """
                    INSERT INTO pengguna(id,nama,email,password_hash,role) VALUES
                        (1,'Mentor','mentor@example.test','test-hash','MENTOR'),
                        (2,'Siswa','siswa@example.test','test-hash','SISWA');
                    INSERT INTO materi(id,judul,file_path,diunggah_oleh,jenjang)
                        VALUES (1,'Materi','test.pdf',2,'SD');
                    INSERT INTO bagian_materi(id,materi_id,judul_bagian,urutan) VALUES (1,1,'Bagian',1);
                    INSERT INTO soal(id,bagian_materi_id,pertanyaan) VALUES (1,1,'A?'),(2,1,'B?');
                    INSERT INTO opsi_jawaban(id,soal_id,teks_opsi,urutan,is_benar)
                        VALUES (1,1,'A',1,true),(2,2,'B',1,true);
                    INSERT INTO sesi_latihan(id,siswa_id,mode) VALUES (1,2,'ACAK');
                    INSERT INTO riwayat_jawaban(sesi_id,soal_id,opsi_dipilih,is_benar) VALUES (1,1,1,true);
                    SELECT setval(pg_get_serial_sequence('pengguna','id'),2);
                    SELECT setval(pg_get_serial_sequence('materi','id'),1);
                    SELECT setval(pg_get_serial_sequence('opsi_jawaban','id'),2);
                    """);
            rejected(connection, "23514", "INSERT INTO pengguna(nama,email,password_hash,role) VALUES ('X','x@example.test','hash','ADMIN')");
            rejected(connection, "23502", "INSERT INTO pengguna(nama,email,password_hash,role) VALUES (NULL,'x@example.test','hash','SISWA')");
            rejected(connection, "23505", "INSERT INTO pengguna(nama,email,password_hash,role) VALUES ('X','MENTOR@example.test','hash','SISWA')");
            rejected(connection, "23503", "INSERT INTO materi(judul,file_path,diunggah_oleh,jenjang) VALUES ('X','x.pdf',999,'SD')");
            rejected(connection, "23514", "INSERT INTO materi(judul,file_path,diunggah_oleh,jenjang) VALUES ('X','x.pdf',2,'SMA')");
            rejected(connection, "23514", "UPDATE soal SET status_review='DISETUJUI' WHERE id=1");
            rejected(connection, "23505", "INSERT INTO opsi_jawaban(soal_id,teks_opsi,urutan,is_benar) VALUES (1,'Another correct',2,true)");
            rejected(connection, "23503", "INSERT INTO riwayat_jawaban(sesi_id,soal_id,opsi_dipilih,is_benar) VALUES (1,2,1,false)");
            rejected(connection, "23505", "INSERT INTO riwayat_jawaban(sesi_id,soal_id,opsi_dipilih,is_benar) VALUES (1,1,1,true)");
        });
    }

    @Test
    void rollbackEmptySchemaIsRepeatableAndAllowsMigrateAgain() throws Exception {
        inSchema((connection, flyway) -> {
            flyway.migrate();
            execute(connection, rollbackScript());
            execute(connection, rollbackScript());
            assertEquals(0, scalar(connection, """
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema=current_schema() AND table_name <> 'flyway_schema_history'
                    """));
            assertEquals(1, flyway.migrate().migrationsExecuted);
        });
    }

    @Test
    void rollbackRefusesExistingDataWithoutLosingIt() throws Exception {
        inSchema((connection, flyway) -> {
            flyway.migrate();
            execute(connection, "INSERT INTO pengguna(nama,email,password_hash,role) VALUES ('A','a@example.test','hash','SISWA')");
            assertThrows(SQLException.class, () -> execute(connection, rollbackScript()));
            execute(connection, "ROLLBACK");
            assertEquals(1, scalar(connection, "SELECT count(*) FROM pengguna"));
            assertEquals(0, flyway.migrate().migrationsExecuted);
        });
    }

    @Test
    void rollbackRefusesLaterMigrationHistory() throws Exception {
        inSchema((connection, flyway) -> {
            flyway.migrate();
            execute(connection, """
                    INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,
                        installed_by,execution_time,success)
                    SELECT max(installed_rank)+1,'2','later change','SQL','V2__later.sql',current_user,0,true
                    FROM flyway_schema_history
                    """);
            assertThrows(SQLException.class, () -> execute(connection, rollbackScript()));
            execute(connection, "ROLLBACK");
            assertEquals(1, scalar(connection, "SELECT count(*) FROM flyway_schema_history WHERE version='2'"));
            assertEquals(1, scalar(connection, """
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema=current_schema() AND table_name='pengguna'
                    """));
        });
    }

    @Test
    void failedMigrationRollsBackDdlAndCanBeRetriedWithoutPartialTables() throws Exception {
        inSchema((connection, flyway) -> {
            flyway.migrate();
            var failingFlyway = Flyway.configure()
                    .dataSource(databaseUrl(), databaseUser(), databasePassword())
                    .defaultSchema(connection.getSchema())
                    .locations("classpath:db/migration", "classpath:db/test-failure").target("1.5").load();
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThrows(org.flywaydb.core.api.FlywayException.class, failingFlyway::migrate);
                assertEquals(0, scalar(connection, """
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema=current_schema() AND table_name='partial_migration'
                        """));
                assertEquals(0, scalar(connection, "SELECT count(*) FROM flyway_schema_history WHERE version='1.5'"));
            }
            assertEquals(0, flyway.migrate().migrationsExecuted);
        });
    }

    @Test
    void springBootLoadsPersistenceSettingsAndConnectsHibernateToPostgres() throws Exception {
        inSchema((connection, flyway) -> {
            String schema = connection.getSchema();
            var app = new SpringApplication(TestApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            try (var context = app.run(
                    "--spring.config.import=classpath:soalku-persistence.properties",
                    "--spring.datasource.url=" + databaseUrl(),
                    "--spring.datasource.username=" + databaseUser(),
                    "--spring.datasource.password=" + databasePassword(),
                    "--spring.flyway.default-schema=" + schema,
                    "--spring.jpa.properties.hibernate.default_schema=" + schema)) {
                assertEquals("validate", context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
                assertEquals("false", context.getEnvironment().getProperty("spring.jpa.open-in-view"));
                var emf = context.getBean(EntityManagerFactory.class);
                try (var em = emf.createEntityManager()) {
                    assertEquals(1, ((Number) em.createNativeQuery("SELECT 1").getSingleResult()).intValue());
                    assertEquals(0, ((Number) em.createNativeQuery("SELECT count(*) FROM " + schema + ".pengguna").getSingleResult()).intValue());
                }
            }
        });
    }

    private static void inSchema(DatabaseCheck check) throws Exception {
        String schema = "soalku_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(databaseUrl(), databaseUser(), databasePassword())) {
            assertTrue(databaseUrl().matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-z0-9_]+_test"),
                    "Integration tests require a local database with a name ending in _test");
            execute(connection, "CREATE SCHEMA " + schema);
            connection.setSchema(schema);
            var flyway = Flyway.configure().dataSource(databaseUrl(), databaseUser(), databasePassword())
                    .defaultSchema(schema).locations("classpath:db/migration").target("1").cleanDisabled(true).load();
            try {
                check.run(connection, flyway);
            } finally {
                execute(connection, "ROLLBACK");
                execute(connection, "DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static void rejected(Connection connection, String state, String sql) {
        assertEquals(state, assertThrows(SQLException.class, () -> execute(connection, sql)).getSQLState());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String rollbackScript() {
        try (var stream = PersistenceIT.class.getResourceAsStream("/db/rollback/U1__initial_schema.sql")) {
            assertNotNull(stream, "Rollback resource must exist");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String databaseUrl() {
        String url = System.getenv("SOALKU_TEST_DATABASE_URL");
        assertNotNull(url, "Set SOALKU_TEST_DATABASE_URL for -Ppostgres-it");
        assertTrue(url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-z0-9_]+_test"),
                "Only a local *_test database is allowed");
        return url;
    }

    private static String databaseUser() {
        return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_USERNAME", "soalku_test");
    }

    private static String databasePassword() {
        return System.getenv().getOrDefault("SOALKU_TEST_DATABASE_PASSWORD", "");
    }

    @FunctionalInterface
    private interface DatabaseCheck {
        void run(Connection connection, Flyway flyway) throws Exception;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication { }
}
