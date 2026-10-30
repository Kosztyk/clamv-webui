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
        Phase2MigrationFixture.fresh(url,user,pass,vendor);
        Phase2MigrationFixture.legacy(url,user,pass,vendor);
    }
}
