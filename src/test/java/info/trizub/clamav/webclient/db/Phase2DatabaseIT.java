package info.trizub.clamav.webclient.db;

import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

/** Integration profile only: requires a Docker-enabled host runner. */
class Phase2DatabaseIT {
    @BeforeAll static void dockerAvailable() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Database integration suite needs a Docker daemon");
    }
    @Test void postgresCleanAndLegacy() throws Exception {
        try(PostgreSQLContainer c=new PostgreSQLContainer(DockerImageName.parse("postgres:17"))) {
            c.start();run(c,"postgresql");
        }
    }
    @Test void mysqlCleanAndLegacy() throws Exception {
        try(MySQLContainer c=new MySQLContainer(DockerImageName.parse("mysql:8.4"))) {
            c.start();run(c,"mysql");
        }
    }
    @Test void mariadbCleanAndLegacy() throws Exception {
        try(MariaDBContainer c=new MariaDBContainer(DockerImageName.parse("mariadb:11.8"))) {
            c.start();run(c,"mariadb");
        }
    }
    private void run(JdbcDatabaseContainer<?> c,String vendor) throws Exception {
        String url=c.getJdbcUrl(),user=c.getUsername(),pass=c.getPassword();
        var draft=new java.util.LinkedHashMap<>(info.trizub.clamav.webclient.configuration.ConfigurationCatalog.defaults());
        draft.put("spring.datasource.url",url);draft.put("spring.datasource.username",user);draft.put("spring.datasource.password",pass);
        try(var probe=new info.trizub.clamav.webclient.configuration.ConfigurationConnectionTester()) {
            assertEquals(true,probe.test("database",draft).get("ok"),"Unsaved draft must connect before migrations");
            var wrong=new java.util.LinkedHashMap<>(draft);wrong.put("spring.datasource.password","synthetic-invalid-probe-password");
            var rejected=assertThrows(IllegalArgumentException.class,()->probe.test("database",wrong));
            assertTrue(rejected.getMessage().contains("authentication failed"));assertFalse(rejected.getMessage().contains(pass));
        }
        Phase2MigrationFixture.fresh(url,user,pass,vendor);
        Phase2MigrationFixture.legacy(url,user,pass,vendor);
        try(var probe=new info.trizub.clamav.webclient.configuration.ConfigurationConnectionTester()) {
            assertEquals(true,probe.test("database",draft).get("ok"),"Same draft must work after migration/startup");
        }
    }
}
