import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Dependency-free JDBC probe. Driver JARs are mounted from the verified Maven build.
 * Use only against disposable upgrade-test databases, never production data. */
public final class Issue57JdbcProbe {
    private Issue57JdbcProbe() { }
    private static final String FLYWAY_HISTORY = """
            SELECT "version", "success" FROM "flyway_schema_history"
            WHERE "version" IS NOT NULL ORDER BY "installed_rank"
            """;
    private static final LinkedHashMap<String,String> SNAPSHOT = new LinkedHashMap<>();
    static {
        SNAPSHOT.put("user", "SELECT username || ':' || password_hash || ':' || enabled FROM app_users WHERE id=5701");
        SNAPSHOT.put("user-roles", "SELECT role FROM app_user_roles WHERE user_id=5701 ORDER BY role");
        SNAPSHOT.put("token", "SELECT token_hash || ':' || prefix || ':' || scopes FROM api_credentials WHERE label='upgrade57-token'");
        SNAPSHOT.put("endpoint", "SELECT name || ':' || host || ':' || port || ':' || enabled FROM clamd_endpoints WHERE id=5702");
        SNAPSHOT.put("job", "SELECT id || ':' || type || ':' || status || ':' || verdict || ':' || target || ':' || submitted_by FROM scan_jobs WHERE id='upgrade57-job'");
        SNAPSHOT.put("job-owner", "SELECT endpoint_id FROM scan_jobs WHERE id='upgrade57-job'");
        SNAPSHOT.put("audit", "SELECT action || ':' || details FROM audit_events WHERE id=5703");
        SNAPSHOT.put("ldap", "SELECT settings_json || ':' || encrypted_secret || ':' || updated_by FROM auth_provider_settings WHERE provider='ldap'");
        SNAPSHOT.put("oidc", "SELECT settings_json || ':' || encrypted_secret || ':' || updated_by FROM auth_provider_settings WHERE provider='oidc'");
    }
    static String snapshot(Connection connection) throws Exception {
        var output = new StringBuilder();
        for (Map.Entry<String,String> entry : SNAPSHOT.entrySet()) {
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(entry.getValue())) {
                int count = 0;
                while (rs.next()) {
                    String value = rs.getString(1);
                    if (value == null) throw new IllegalStateException("Missing value for " + entry.getKey());
                    // Logs and CI artifacts must not contain password hashes or encrypted settings.
                    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
                    output.append(entry.getKey()).append('=').append(encoded).append('\n');
                    count++;
                }
                if (count == 0) throw new IllegalStateException("Missing historical data for " + entry.getKey());
            }
        }
        return output.toString();
    }
    static void verifyFlyway(Connection connection, int expectedVersion) throws Exception {
        boolean[] installed = new boolean[expectedVersion + 1];
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(
                FLYWAY_HISTORY)) {
            while (rs.next()) {
                String raw = rs.getString(1);
                if (!rs.getBoolean(2)) throw new IllegalStateException("Failed Flyway migration version " + raw);
                if (raw.matches("[0-9]+")) {
                    int number = Integer.parseInt(raw);
                    if (number > expectedVersion) throw new IllegalStateException("Unexpected future migration " + raw);
                    installed[number] = true;
                }
            }
        }
        for (int v = 1; v <= expectedVersion; v++)
            if (!installed[v]) throw new IllegalStateException("Pending or missing Flyway migration version " + v);
    }
    static int latestFlywayVersion(Connection connection) throws Exception {
        int latest = 0;
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(
                FLYWAY_HISTORY)) {
            while (rs.next()) {
                if (!rs.getBoolean(2)) throw new IllegalStateException("Failed Flyway migration in predecessor");
                String version = rs.getString(1);
                if (!version.matches("[0-9]+")) throw new IllegalStateException("Unrecognized Flyway version");
                latest = Math.max(latest, Integer.parseInt(version));
            }
        }
        if (latest < 4) throw new IllegalStateException("Historical fixture needs at least schema V4");
        verifyFlyway(connection, latest);
        return latest;
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 5 || args.length > 6)
            throw new IllegalArgumentException("Expected: mode jdbc-url username password version [seed-file]");
        String mode = args[0];
        try (Connection connection = DriverManager.getConnection(args[1], args[2], args[3])) {
            if (mode.equals("seed")) {
                if (args.length != 6) throw new IllegalArgumentException("Seed requires SQL fixture path");
                String sql = Files.readString(Path.of(args[5]), StandardCharsets.UTF_8);
                connection.setAutoCommit(false);
                try (Statement st = connection.createStatement()) {
                    for (String part : sql.split(";")) {
                        String text = part.replaceAll("(?m)^--[^\\n]*$", "").trim();
                        if (!text.isEmpty()) st.execute(text);
                    }
                    connection.commit();
                } catch (Exception problem) {
                    connection.rollback(); throw problem;
                }
                System.out.println("PASS: predecessor database seeded from versioned fixture");
            } else if (mode.equals("version")) {
                System.out.println(latestFlywayVersion(connection));
            } else if (mode.equals("snapshot")) {
                verifyFlyway(connection,Integer.parseInt(args[4]));
                System.out.print(snapshot(connection));
            } else {
                throw new IllegalArgumentException("Unsupported mode");
            }
        }
    }
}
