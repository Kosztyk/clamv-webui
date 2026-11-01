package info.trizub.clamav.webclient.configuration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class ConfigurationDatabaseProbeTest {
    static final String SECRET="synthetic-database-probe-password";
    Path directory;
    @BeforeEach void directory() throws Exception {Files.createDirectories(Path.of("data"));directory=Files.createTempDirectory(Path.of("data"),"db-probe-");}
    @AfterEach void cleanup() throws Exception {try(var paths=Files.walk(directory)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    Map<String,String> values(String url) {
        var values=new LinkedHashMap<>(ConfigurationCatalog.defaults());values.put("spring.datasource.url",url);
        values.put("spring.datasource.username","probe_user");values.put("spring.datasource.password",SECRET);return values;
    }
    String h2() {return "jdbc:h2:file:"+directory.resolve("existing").toAbsolutePath()+";MODE=PostgreSQL;DB_CLOSE_DELAY=0";}
    @Test void existingH2ActuallyChecksCredentialsAndSelectWithoutMigratingTheSchema() throws Exception {
        String url=h2();
        try(var connection=DriverManager.getConnection(url,"probe_user",SECRET);var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE probe_marker (id INTEGER PRIMARY KEY)");statement.execute("INSERT INTO probe_marker VALUES (7)");
        }
        try(var tester=new ConfigurationConnectionTester()) {
            assertThat(tester.test("database",values(url))).containsEntry("ok",true).containsEntry("status","succeeded");
            var wrong=values(url);wrong.put("spring.datasource.password","synthetic-wrong-password");
            assertThatThrownBy(()->tester.test("database",wrong)).hasMessageContaining("authentication failed")
                .hasMessageNotContaining(SECRET).hasMessageNotContaining("synthetic-wrong-password").hasMessageNotContaining(directory.toString());
        }
        try(var connection=DriverManager.getConnection(url,"probe_user",SECRET);var statement=connection.createStatement();var row=statement.executeQuery("SELECT id FROM probe_marker")) {
            assertThat(row.next()).isTrue();assertThat(row.getInt(1)).isEqualTo(7);
            try(var tables=connection.getMetaData().getTables(null,"PUBLIC",null,new String[]{"TABLE"})) {
                var names=new ArrayList<String>();while(tables.next())names.add(tables.getString("TABLE_NAME"));assertThat(names).containsExactly("PROBE_MARKER");
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"FALSE","TRUE"})
    void ifExistsOptionIsReplacedInsteadOfDuplicatedAndNeverAllowsTestToCreateADatabase(String option) throws Exception {
        String url=h2();try(var connection=DriverManager.getConnection(url,"probe_user",SECRET)) {assertThat(connection.isValid(1)).isTrue();}
        try(var tester=new ConfigurationConnectionTester()) {
            assertThat(tester.test("database",values(url+";IFEXISTS="+option))).containsEntry("ok",true);
            String missing="jdbc:h2:file:"+directory.resolve("missing").toAbsolutePath()+";IFEXISTS="+option;
            assertThat(tester.test("database",values(missing))).containsEntry("ok",false).containsEntry("status","not_initialized");
            assertThat(directory.resolve("missing.mv.db")).doesNotExist();assertThat(directory.resolve("missing.trace.db")).doesNotExist();
        }
    }
    @Test void uninitializedMemoryDatabaseIsAlsoDeferredRatherThanCreatedByTheTest() throws Exception {
        try(var tester=new ConfigurationConnectionTester()) {
            assertThat(tester.test("database",values("jdbc:h2:mem:missing_"+UUID.randomUUID().toString().replace("-",""))))
                .containsEntry("ok",false).containsEntry("status","not_initialized");
        }
    }
    @ParameterizedTest @CsvSource({"jdbc:postgresql://database.example.test/probe,org.postgresql.Driver,10", "jdbc:mysql://database.example.test/probe,com.mysql.cj.jdbc.Driver,10000", "jdbc:mariadb://database.example.test/probe,org.mariadb.jdbc.Driver,10000", "jdbc:h2:mem:probe,org.h2.Driver,10000"})
    void everySupportedFamilyUsesItsPackagedDriverAndCorrectTimeoutUnits(String url,String driver,String timeout) throws Exception {
        var values=values(url);assertThat(ConfigurationCatalog.jdbcDriver(url)).isEqualTo(driver);
        assertThat(Class.forName(driver).getDeclaredConstructor().newInstance()).isInstanceOf(Driver.class);
        var options=ConfigurationConnectionTester.databaseOptions(url,values);
        assertThat(options).containsEntry("connectTimeout",timeout).containsEntry("socketTimeout",timeout);
        values.put("spring.datasource.hikari.connection-timeout","120000");assertThat(ConfigurationConnectionTester.databaseConnectMillis(values)).isEqualTo(30000);
        values.put("spring.datasource.hikari.connection-timeout","1500");assertThat(ConfigurationConnectionTester.databaseOptions(url,values).getProperty("connectTimeout"))
            .isEqualTo(url.startsWith("jdbc:postgresql:")?"2":"1500");
    }
    @Test void postgresqlAuthenticationSlowerThanTheOldThreeSecondTimeoutCanBeVerified() throws Exception {
        try(var database=new PgFixture("slow");var tester=new ConfigurationConnectionTester()) {
            String url="jdbc:postgresql://127.0.0.1:"+database.port()+"/probe?sslmode=disable&preferQueryMode=simple";
            long before=System.nanoTime();assertThat(tester.test("database",values(url))).containsEntry("ok",true);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)).isGreaterThanOrEqualTo(4000).isLessThan(12000);
            assertThat(database.queries).contains("SELECT 1");assertThat(database.failure.get()).isNull();
        }
    }
    @Test void normalStartupPoolAlsoAuthenticatesToTheSameFourSecondPostgresqlFixture() throws Exception {
        try(var database=new PgFixture("slow");var pool=new com.zaxxer.hikari.HikariDataSource()) {
            pool.setJdbcUrl("jdbc:postgresql://127.0.0.1:"+database.port()+"/probe?sslmode=disable&preferQueryMode=simple");
            pool.setDriverClassName("org.postgresql.Driver");pool.setUsername("probe_user");pool.setPassword(SECRET);
            pool.setConnectionTimeout(10000);pool.setMaximumPoolSize(1);pool.setMinimumIdle(1);
            try(var connection=pool.getConnection();var query=connection.createStatement();var row=query.executeQuery("SELECT 1")) {
                assertThat(row.next()).isTrue();assertThat(row.getInt(1)).isEqualTo(1);
            }
            assertThat(database.failure.get()).isNull();
        }
    }
    static Stream<Arguments> failures() {
        return Stream.of(
            Arguments.of(new SQLException(SECRET,"28P01"),"authentication failed"),
            Arguments.of(new SQLException(SECRET,"28000"),"authentication failed"),
            Arguments.of(new SQLException(SECRET,"3D000"),"does not exist"),
            Arguments.of(new SQLException(SECRET,"42000",1049),"does not exist"),
            Arguments.of(new SQLException(SECRET,"42000",1044),"lacks permission"),
            Arguments.of(new SQLException(SECRET,"42000",1142),"lacks permission"),
            Arguments.of(new SQLException(SECRET,"42501"),"lacks permission"),
            Arguments.of(new SQLException(SECRET,"57014"),"cancelled"),
            Arguments.of(new SQLException(SECRET,"90020"),"H2 database could not be opened"),
            Arguments.of(new SQLException(SECRET,"08001",new javax.net.ssl.SSLHandshakeException(SECRET)),"TLS verification failed"),
            Arguments.of(new SQLException(SECRET,"08001",new UnknownHostException(SECRET)),"could not be resolved"),
            Arguments.of(new SQLException(SECRET,"08001",new ConnectException(SECRET)),"could not be reached"),
            Arguments.of(new SQLTimeoutException(SECRET),"timed out"),
            Arguments.of(new ClassNotFoundException(SECRET),"driver is unavailable"),
            Arguments.of(new SQLException(SECRET,"XXXXX"),"server logs"));
    }
    @ParameterizedTest @MethodSource("failures")
    void failuresExplainTheDatabaseCauseWithoutIncludingDriverMessagesOrSecrets(Throwable failure,String expected) {
        assertThat(ConfigurationConnectionTester.databaseFailure(failure)).contains(expected,"No settings were saved").doesNotContain(SECRET);
    }
    @ParameterizedTest @ValueSource(strings={"success","28P01","3D000"})
    void actualPostgresqlDriverAuthenticatesAndValidatesAnUnsavedDraftAgainstALoopbackWireFixture(String mode) throws Exception {
        try(var database=new PgFixture(mode);var tester=new ConfigurationConnectionTester()) {
            String url="jdbc:postgresql://127.0.0.1:"+database.port()+"/probe?sslmode=disable&preferQueryMode=simple";
            if(mode.equals("success")) {
                assertThat(tester.test("database",values(url))).containsEntry("ok",true).containsEntry("status","succeeded");
                assertThat(database.queries).contains("SELECT 1").noneMatch(q->q.matches("(?is).*(CREATE|ALTER|INSERT|UPDATE|DELETE).*"));
            } else {
                assertThatThrownBy(()->tester.test("database",values(url)))
                    .hasMessageContaining(mode.equals("28P01")?"authentication failed":"does not exist")
                    .hasMessageNotContaining(SECRET).hasMessageNotContaining(url).hasMessageNotContaining("probe_user");
            }
            assertThat(database.failure.get()).isNull();
        }
    }
    /** Authentication/query protocol fixture only; it is not a PostgreSQL engine. */
    static final class PgFixture implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
        final List<String> queries=new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        final Thread peer;
        PgFixture(String mode) throws Exception {peer=new Thread(()->serve(mode),"postgres-probe-fixture");peer.setDaemon(true);peer.start();}
        int port(){return server.getLocalPort();}
        void serve(String mode) {
            try(var socket=server.accept()) {
                socket.setSoTimeout(5000);var input=new DataInputStream(socket.getInputStream());var output=new DataOutputStream(socket.getOutputStream());
                input.readNBytes(input.readInt()-4);
                if(mode.equals("slow")) Thread.sleep(4000);
                if(mode.equals("3D000")) {error(output,mode);return;}
                send(output,'R',integer(3));assertThat(input.readUnsignedByte()).isEqualTo((int)'p');
                String password=new String(input.readNBytes(input.readInt()-4),StandardCharsets.UTF_8).replace("\0","");assertThat(password).isEqualTo(SECRET);
                if(mode.equals("28P01")) {error(output,mode);return;}
                send(output,'R',integer(0));send(output,'S',text("server_version","16.0"));send(output,'S',text("client_encoding","UTF8"));
                send(output,'S',text("DateStyle","ISO, MDY"));send(output,'S',text("integer_datetimes","on"));send(output,'S',text("standard_conforming_strings","on"));
                send(output,'K',new byte[]{0,0,0,1,0,0,0,2});send(output,'Z',new byte[]{'I'});
                while(true) {
                    int type=input.read();if(type<0)return;byte[] body=input.readNBytes(input.readInt()-4);if(type=='X')return;
                    if(type!='Q')throw new IOException("Unexpected query protocol");
                    String query=new String(body,StandardCharsets.UTF_8).replace("\0","").trim();queries.add(query);
                    boolean show=query.toUpperCase(Locale.ROOT).startsWith("SHOW");
                    if(query.toUpperCase(Locale.ROOT).startsWith("SELECT") || show) {
                        var bytes=new ByteArrayOutputStream();var row=new DataOutputStream(bytes);row.writeShort(1);row.write(text("?column?"));row.writeInt(0);row.writeShort(0);row.writeInt(show?25:23);row.writeShort(show?-1:4);row.writeInt(-1);row.writeShort(0);send(output,'T',bytes.toByteArray());
                        byte[] value=(show?"read committed":"1").getBytes(StandardCharsets.UTF_8);
                        bytes.reset();row.writeShort(1);row.writeInt(value.length);row.write(value);send(output,'D',bytes.toByteArray());send(output,'C',text(show?"SHOW":"SELECT 1"));
                    } else send(output,'C',text("SET"));
                    send(output,'Z',new byte[]{'I'});
                }
            } catch(Throwable error) {failure.set(error);}
        }
        static byte[] integer(int value) throws IOException {var bytes=new ByteArrayOutputStream();new DataOutputStream(bytes).writeInt(value);return bytes.toByteArray();}
        static byte[] text(String... values) {return (String.join("\0",values)+"\0").getBytes(StandardCharsets.UTF_8);}
        static void send(DataOutputStream output,int type,byte[] data) throws IOException {output.writeByte(type);output.writeInt(data.length+4);output.write(data);output.flush();}
        static void error(DataOutputStream output,String state) throws IOException {send(output,'E',text("SFATAL","VFATAL","C"+state,"M"+SECRET,""));}
        public void close() throws Exception {server.close();peer.join(1000);}
    }
}
