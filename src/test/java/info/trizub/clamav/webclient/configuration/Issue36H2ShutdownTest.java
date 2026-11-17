package info.trizub.clamav.webclient.configuration;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #36: file-backed H2 must close cleanly, including upgrades of the old default. */
class Issue36H2ShutdownTest {
  private static final String LEGACY =
      "jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
  private static final String SAFE =
      "jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=0";

  @TempDir Path folder;

  @Test
  void packagedPropertyCatalogAndBrowserPresetUseIdenticalSafeFileUrl() throws Exception {
    Properties application = new Properties();
    try (InputStream stream = getClass().getResourceAsStream("/application.properties")) {
      assertThat(stream).isNotNull();
      application.load(stream);
    }
    assertThat(application.getProperty("spring.datasource.url")).isEqualTo(SAFE);
    assertThat(ConfigurationCatalog.get("spring.datasource.url").defaultValue()).isEqualTo(SAFE);
    try (InputStream stream =
        getClass().getResourceAsStream("/static/js/configuration-center.js")) {
      assertThat(stream).isNotNull();
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
          .contains("h2: '" + SAFE + "'")
          .doesNotContain("h2: '" + LEGACY + "'");
    }
    assertThat(SAFE).contains("DB_CLOSE_DELAY=0").doesNotContain("DB_CLOSE_ON_EXIT=FALSE");
  }

  @Test
  void legacySavedShippedDefaultIsUpgradedInMemoryWithoutSilentFileMutation() throws Exception {
    Path file = folder.resolve("configuration-center.properties");
    Properties stored = new Properties();
    stored.setProperty("_format", "1");
    stored.setProperty("_revision", "3");
    stored.setProperty("_setupComplete", "true");
    stored.setProperty("spring.datasource.url", LEGACY);
    try (var output = Files.newOutputStream(file)) {
      stored.store(output, "legacy configuration");
    }
    byte[] before = Files.readAllBytes(file);
    var store = new ConfigurationStore(file, folder.resolve("configuration-center.key"));
    assertThat(store.saved("spring.datasource.url")).contains(SAFE);
    assertThat(store.active("spring.datasource.url")).contains(SAFE);
    assertThat(Files.readAllBytes(file)).isEqualTo(before);
    // A later user-initiated save persists the correction without rewriting
    // unrelated settings or using a database migration.
    store.save("3", Map.of(), Set.of(), false, "test");
    Properties saved = new Properties();
    try (var input = Files.newInputStream(file)) {
      saved.load(input);
    }
    assertThat(saved.getProperty("spring.datasource.url")).isEqualTo(SAFE);
  }

  @Test
  void fileUrlsWithDisabledClosingAreRejectedButMemoryTestsMayRetainTheirDatabase() {
    assertThatCode(() -> ConfigurationCatalog.checkJdbc(SAFE)).doesNotThrowAnyException();
    assertThatCode(() -> ConfigurationCatalog.checkJdbc("jdbc:h2:mem:unit_test;DB_CLOSE_DELAY=-1"))
        .doesNotThrowAnyException();
    for (String url :
        new String[] {
          LEGACY,
          "jdbc:h2:file:./data/clamav-web-client;DB_CLOSE_DELAY=-1",
          "jdbc:h2:file:./data/clamav-web-client;DB_CLOSE_ON_EXIT=FALSE"
        })
      assertThatThrownBy(() -> ConfigurationCatalog.checkJdbc(url))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("H2");
  }

  @Test
  void hikariPoolCloseReleasesTheFileLockAndRestartKeepsExistingRows() throws Exception {
    // This is the actual connection-pool lifecycle used by the application:
    // after Spring shuts down Hikari, no H2 file lock may remain.
    Path database = folder.resolve("hikari-pool-db");
    Path lock = folder.resolve("hikari-pool-db.lock.db");
    String url = "jdbc:h2:file:" + database + ";MODE=PostgreSQL;DB_CLOSE_DELAY=0";
    try (HikariDataSource pool = hikari(url)) {
      try (Connection connection = pool.getConnection();
          var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE pool_shutdown_marker (marker INT PRIMARY KEY)");
        statement.execute("INSERT INTO pool_shutdown_marker VALUES (36)");
      }
      assertThat(canLock(database))
          .as("A running H2 file database holds an MVStore lock")
          .isFalse();
    }
    assertThat(lock).as("Closing the pool must release the H2 file lock").doesNotExist();
    assertThat(canLock(database)).isTrue();

    // A new connection pool must be able to read, not recreate, the DB.
    try (HikariDataSource restarted = hikari(url);
        Connection connection = restarted.getConnection();
        var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT marker FROM pool_shutdown_marker")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getInt(1)).isEqualTo(36);
      assertThat(result.next()).isFalse();
    }
    assertThat(lock).as("A second clean pool close must remove the lock").doesNotExist();
    assertThat(canLock(database)).isTrue();
  }

  private static boolean canLock(Path database) throws Exception {
    try (var channel = FileChannel.open(Path.of(database + ".mv.db"), StandardOpenOption.WRITE);
        var lock = channel.tryLock()) {
      return lock != null;
    } catch (OverlappingFileLockException inUse) {
      return false;
    }
  }

  private static HikariDataSource hikari(String url) {
    HikariDataSource pool = new HikariDataSource();
    pool.setJdbcUrl(url);
    pool.setUsername("sa");
    pool.setPassword("");
    pool.setMaximumPoolSize(2);
    pool.setMinimumIdle(1);
    return pool;
  }

  @Test
  void safeFileUrlResetsAnAlreadyOpenLegacyDatabaseWithoutForcedShutdown() throws Exception {
    Path database = folder.resolve("existing-database");
    String base = "jdbc:h2:file:" + database + ";MODE=PostgreSQL";
    Path lock = folder.resolve("existing-database.lock.db");

    // Reproduce the old shipped settings. Closing Hikari's connections was
    // insufficient because the database retained its persistent -1 delay.
    try (HikariDataSource oldPool = hikari(base + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")) {
      try (Connection connection = oldPool.getConnection();
          var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE issue36_marker (id INT PRIMARY KEY)");
        statement.execute("INSERT INTO issue36_marker VALUES (36)");
      }
    }
    assertThat(canLock(database)).as("Legacy -1 setting deliberately keeps MVStore open").isFalse();

    // Do NOT issue SHUTDOWN between the two pools: verify that the explicit
    // DB_CLOSE_DELAY=0 is enough to override the legacy persistent value.
    try (HikariDataSource correctedPool = hikari(base + ";DB_CLOSE_DELAY=0")) {
      try (Connection connection = correctedPool.getConnection();
          var statement = connection.createStatement();
          var result = statement.executeQuery("SELECT id FROM issue36_marker")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getInt(1)).isEqualTo(36);
        assertThat(result.next()).isFalse();
      }
    }
    assertThat(lock)
        .as("New URL must reset -1 and close the database on pool shutdown")
        .doesNotExist();
    assertThat(canLock(database)).isTrue();

    // The next opening must preserve the pre-upgrade data and close again.
    try (HikariDataSource restartedPool = hikari(base + ";DB_CLOSE_DELAY=0")) {
      try (Connection connection = restartedPool.getConnection();
          var statement = connection.createStatement();
          var result = statement.executeQuery("SELECT COUNT(*) FROM issue36_marker")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getInt(1)).isEqualTo(1);
      }
    }
    assertThat(lock).as("Subsequent restart must also leave no lock").doesNotExist();
    assertThat(canLock(database)).isTrue();
  }
}
