package id.soalku.persistence;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionMigrationIT {
    @Test void sessionMigrationIsIdempotentReversibleAndProtectsSnapshots() throws Exception {
        String url=System.getenv("SOALKU_TEST_DATABASE_URL");
        assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-z0-9_]+_test"));
        String user=System.getenv().getOrDefault("SOALKU_TEST_DATABASE_USERNAME","soalku_test"),password=System.getenv().getOrDefault("SOALKU_TEST_DATABASE_PASSWORD","");
        String schema="soalku_it_session_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(url,user,password)) {
            execute(c,"CREATE SCHEMA "+schema);c.setSchema(schema);
            try {
                var previous=Flyway.configure().dataSource(url,user,password).defaultSchema(schema).locations("classpath:db/migration")
                        .configuration(Map.of("flyway.postgresql.transactional.lock","false")).target("3").load();previous.migrate();
                execute(c,"INSERT INTO pengguna(id,nama,email,password_hash,role) VALUES (1,'A','a@example.test','hash','SISWA')");
                execute(c,"INSERT INTO sesi_latihan(siswa_id,mode) VALUES (1,'ACAK')"); // Legacy sessions remain valid.
                var latest=Flyway.configure().dataSource(url,user,password).defaultSchema(schema).locations("classpath:db/migration")
                        .configuration(Map.of("flyway.postgresql.transactional.lock","false")).load();
                assertEquals(1,latest.migrate().migrationsExecuted);assertEquals(0,latest.migrate().migrationsExecuted);
                execute(c,"DELETE FROM flyway_schema_history WHERE version='4'");assertEquals(1,latest.migrate().migrationsExecuted);
                String rollback;
                try(var in=getClass().getResourceAsStream("/db/rollback/U4__practice_session.sql")) { assertNotNull(in);rollback=new String(in.readAllBytes(),StandardCharsets.UTF_8); }
                for(int n=0;n<2;n++) for(String sql:rollback.split("\\n(?=ALTER TABLE|DROP INDEX|DELETE FROM|RESET)")) execute(c,sql);
                assertEquals(1,latest.migrate().migrationsExecuted);
                String base="INSERT INTO sesi_latihan(siswa_id,mode,request_key,requested_count,soal_snapshot,berakhir_at) VALUES ";
                execute(c,base+"(1,'ACAK','00000000-0000-0000-0000-000000000001',2,'[{\"id\":1}]',NULL)");
                rejected(c,"23505",base+"(1,'ACAK','00000000-0000-0000-0000-000000000001',2,'[{\"id\":1}]',NULL)");
                rejected(c,"23514",base+"(1,'ACAK',gen_random_uuid(),0,'[{}]',NULL)");
                rejected(c,"23514",base+"(1,'ACAK',gen_random_uuid(),2,'[]',NULL)");
                rejected(c,"23514",base+"(1,'ACAK',gen_random_uuid(),2,'{}',NULL)");
                rejected(c,"23514",base+"(1,'BERWAKTU',gen_random_uuid(),2,'[{}]',NULL)");
                rejected(c,"23514",base+"(1,'ACAK',gen_random_uuid(),2,'[{}]',now()+interval '1 minute')");
                rejected(c,"23514",base+"(1,'BERWAKTU',gen_random_uuid(),2,'[{}]',now()-interval '1 minute')");
                rejected(c,"23514",base+"(1,'ACAK',NULL,2,'[{}]',NULL)");
                assertThrows(SQLException.class,()->execute(c,rollback.split("\\n(?=ALTER TABLE|DROP INDEX|DELETE FROM|RESET)")[0]));
                try(var st=c.createStatement();var rs=st.executeQuery("SELECT count(*) FROM sesi_latihan")) { assertTrue(rs.next());assertEquals(2,rs.getInt(1)); }
            } finally { execute(c,"ROLLBACK");execute(c,"DROP SCHEMA "+schema+" CASCADE"); }
        }
    }
    private static void execute(Connection c,String sql) throws SQLException { try(var st=c.createStatement()) { st.execute(sql); } }
    private static void rejected(Connection c,String state,String sql) { assertEquals(state,assertThrows(SQLException.class,()->execute(c,sql)).getSQLState()); }
}
