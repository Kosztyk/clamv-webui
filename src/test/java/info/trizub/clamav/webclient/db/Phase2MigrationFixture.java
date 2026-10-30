package info.trizub.clamav.webclient.db;

import java.sql.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.flywaydb.core.Flyway;

/** DB-agnostic fixture: initial/upgrade migration, data retention, and typed fields. */
final class Phase2MigrationFixture {
    private Phase2MigrationFixture() { }
    static Flyway flyway(String url, String user, String password, String vendor, boolean baseline) {
        return Flyway.configure()
            .dataSource(url,user,password)
            .locations("classpath:db/migration/"+vendor)
            .javaMigrations(new V2__reconcile_legacy_columns())
            .baselineVersion("1")
            .baselineOnMigrate(baseline)
            .cleanDisabled(false) // ONLY used on short-lived test databases
            .load();
    }
    static void fresh(String url,String user,String password,String vendor) throws Exception {
        Flyway f=flyway(url,user,password,vendor,false);
        f.migrate();
        try (Connection c=DriverManager.getConnection(url,user,password)) {
            verifyTables(c);
            seed(c);
            verifyRetained(c);
            verifyTokenRoundTrip(c);
        }
        f.migrate(); // second application restart: repeat is safe
        try (Connection c=DriverManager.getConnection(url,user,password)) { verifyRetained(c); }
    }
    static void legacy(String url,String user,String password,String vendor) throws Exception {
        Flyway f=flyway(url,user,password,vendor,false);
        f.clean(); // an isolated DB owned by this test, never an operator DB
        String resource="db/migration/"+vendor+"/V1__initial_schema.sql";
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            // ScriptUtils recognizes -- comments, including those containing semicolons.
            // Splitting V1 SQL on ';' submitted comment text as SQL in the legacy test.
            ScriptUtils.executeSqlScript(c, new ClassPathResource(resource));
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE scan_jobs DROP COLUMN submitted_transport");
                st.execute("ALTER TABLE audit_events DROP COLUMN transport");
                st.execute("ALTER TABLE audit_events DROP COLUMN endpoint_id");
                st.execute("DROP TABLE api_credentials");
            }
        }
        try (Connection c=DriverManager.getConnection(url,user,password)) { seed(c); }
        // Not automatic in production. Simulates operator choosing to baseline after backup.
        flyway(url,user,password,vendor,true).migrate();
        try (Connection c=DriverManager.getConnection(url,user,password)) {
            verifyTables(c);verifyRetained(c);
            verifyTokenRoundTrip(c);
            assertColumn(c,"scan_jobs","submitted_transport");
            assertColumn(c,"audit_events","transport");
            assertColumn(c,"audit_events","endpoint_id");
        }
        flyway(url,user,password,vendor,false).migrate();
        try (Connection c=DriverManager.getConnection(url,user,password)) { verifyRetained(c); }
    }
    static void verifyTables(Connection c) throws Exception {
        for (String t:List.of("app_users","app_user_roles","scan_jobs","audit_events",
                "watched_directories","processed_files","api_credentials","clamd_endpoints","external_identities","auth_provider_settings")) {
            try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM "+t)) {
                if (!rs.next()) throw new AssertionError("Missing table "+t);
            }
        }
    }
    static void assertColumn(Connection c,String table,String column) throws Exception {
        try (Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT "+column+" FROM "+table+" WHERE 1=0")) { }
    }
    static void seed(Connection c) throws Exception {
        try (Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO app_users(id,username,password_hash,enabled,created_at) VALUES (44,'migration-test','bcrypt-hash',TRUE,'2026-10-28 18:30:00.123456')");
            st.executeUpdate("INSERT INTO app_user_roles(user_id,role) VALUES (44,'ROLE_ADMIN')");
            st.executeUpdate("INSERT INTO clamd_endpoints(id,name,host,port,platform,enabled) " +
                    "VALUES (55,'test','localhost',3310,'UNIX',TRUE)");
            st.executeUpdate("INSERT INTO scan_jobs(id,type,status,target,endpoint_id,submitted_by,found_viruses_json,error_message,submitted_at) " +
                    "VALUES ('migratejob','UPLOAD','FINISHED','example.txt',55,'migration-test','EICAR','historical scan','2026-10-28 18:30:00.123456')");
            st.executeUpdate("INSERT INTO audit_events(id,username,action,details,job_id) " +
                    "VALUES (66,'migration-test','SCAN_RESULT','legacy audit','migratejob')");
            st.executeUpdate("INSERT INTO watched_directories(id,path,endpoint_id,enabled) " +
                    "VALUES (77,'/legacy/watch',55,TRUE)");
            st.executeUpdate("INSERT INTO processed_files(id,path,last_modified,size_bytes) " +
                    "VALUES (88,'/legacy/watch/file.txt',123,456)");
        }
    }
    static void verifyRetained(Connection c) throws Exception {
        Map<String,String> queries=Map.of(
            "app_users", "SELECT username FROM app_users WHERE id=44",
            "app_user_roles", "SELECT role FROM app_user_roles WHERE user_id=44",
            "scan_jobs", "SELECT error_message FROM scan_jobs WHERE id='migratejob'",
            "audit_events", "SELECT details FROM audit_events WHERE id=66",
            "watched_directories", "SELECT path FROM watched_directories WHERE id=77",
            "processed_files", "SELECT path FROM processed_files WHERE id=88"
        );
        Map<String,String> values=Map.of("app_users","migration-test","app_user_roles","ROLE_ADMIN",
            "scan_jobs","historical scan","audit_events","legacy audit",
            "watched_directories","/legacy/watch","processed_files","/legacy/watch/file.txt");
        try (Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT submitted_at FROM scan_jobs WHERE id='migratejob'")) {
            if (!rs.next() || rs.getTimestamp(1)==null) throw new AssertionError("Lost job timestamp");
        }
        for(var e:queries.entrySet()) {
            try(Statement st=c.createStatement();ResultSet rs=st.executeQuery(e.getValue())) {
                if(!rs.next() || !values.get(e.getKey()).equals(rs.getString(1)))
                    throw new AssertionError("Lost legacy row in " + e.getKey());
            }
        }
    }
    static void verifyTokenRoundTrip(Connection c) throws Exception {
        try (Statement st=c.createStatement()) {
            ResultSet rs=st.executeQuery("SELECT count(*) FROM api_credentials WHERE token_hash='" + "a".repeat(64) + "'");
            rs.next(); boolean exists=rs.getInt(1)>0;rs.close();
            if (!exists) {
                st.executeUpdate("INSERT INTO api_credentials(label,token_hash,prefix,scopes,created_at) VALUES (" +
                        "'migration-token','" + "a".repeat(64) + "','test','jobs:read','2026-10-28 18:30:00.123456')");
            }
            try(ResultSet check=st.executeQuery("SELECT scopes FROM api_credentials WHERE token_hash='" + "a".repeat(64) + "'")) {
                if (!check.next() || !"jobs:read".equals(check.getString(1)))
                    throw new AssertionError("API token data not persisted");
            }
        }
    }

}
