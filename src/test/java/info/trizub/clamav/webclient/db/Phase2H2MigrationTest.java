package info.trizub.clamav.webclient.db;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;

class Phase2H2MigrationTest {
    private String url(){return "jdbc:h2:mem:phase2_"+UUID.randomUUID().toString().replace("-", "")+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";}
    @Test void freshH2WithoutDbSettings() throws Exception {
        Phase2MigrationFixture.fresh(url(),"sa","","h2");
    }
    @Test void operatorBaselineH2PreservesAllRows() throws Exception {
        Phase2MigrationFixture.legacy(url(),"sa","","h2");
    }
    @Test void legacySchemaNeverAutoBaselined() throws Exception {
        String jdbc=url();
        try(Connection c=DriverManager.getConnection(jdbc,"sa","");Statement st=c.createStatement()) {
            st.execute("CREATE TABLE legacy_marker (id INTEGER PRIMARY KEY)");
        }
        assertThrows(Exception.class,()->Phase2MigrationFixture.flyway(jdbc,"sa","","h2",false).migrate());
    }
}
