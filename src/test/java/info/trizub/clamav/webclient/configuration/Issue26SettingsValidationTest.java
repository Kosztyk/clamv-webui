package info.trizub.clamav.webclient.configuration;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Issue26SettingsValidationTest {
  @TempDir Path dir;

  @Test
  void workersAreBoundedAndInvalidInputRejected() {
    valid("app.concurrentScans", "1");
    valid("app.concurrentScans", "64");
    invalid("app.concurrentScans", "0");
    invalid("app.concurrentScans", "65");
    invalid("app.concurrentScans", "-1");
    invalid("app.concurrentScans", "foo");
    invalid("app.concurrentScans", "9999999999999999999999999999");
  }

  @Test
  void watchPollingHasFiveSecondMinimum() {
    valid("app.watch.pollSeconds", "5");
    valid("app.watch.pollSeconds", "3600");
    invalid("app.watch.pollSeconds", "0");
    invalid("app.watch.pollSeconds", "4");
    invalid("app.watch.pollSeconds", "3601");
    invalid("app.watch.pollSeconds", "abc");
  }

  @Test
  void uploadMaxMustBePositiveAndBounded() {
    valid("app.upload.maxBytes", "1");
    valid("app.upload.maxBytes", "2147483648");
    invalid("app.upload.maxBytes", "0");
    invalid("app.upload.maxBytes", "-1");
    invalid("app.upload.maxBytes", "2147483649");
    invalid("app.upload.maxBytes", "xyz");
  }

  @Test
  void customDirectoriesMustBeAbsoluteNormalizedExistingWritableAndNotSymlinks() throws Exception {
    for (String key : new String[] {"app.storage.uploadDir", "app.storage.quarantineDir"}) {
      valid(key, ConfigurationCatalog.get(key).defaultValue()); // keep legacy default compatibility
      invalid(key, "");
      invalid(key, "relative/custom");
      invalid(key, dir.resolve("does-not-exist").toString());
      Path file = Files.createTempFile(dir, "not-a-directory", ".txt");
      invalid(key, file.toString());
      Path real = Files.createDirectory(dir.resolve(key.substring(key.lastIndexOf('.') + 1)));
      valid(key, real.toString());
      Path alias = dir.resolve(key.substring(key.lastIndexOf('.') + 1) + "-alias");
      Files.createSymbolicLink(alias, real);
      invalid(key, alias.toString());
    }
  }

  private static void valid(String key, String value) {
    assertThat(ConfigurationCatalog.validate(ConfigurationCatalog.get(key), value))
        .isEqualTo(value);
  }

  private static void invalid(String key, String value) {
    assertThatThrownBy(() -> ConfigurationCatalog.validate(ConfigurationCatalog.get(key), value))
        .as(key + "=" + value)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(ConfigurationCatalog.get(key).label());
  }
}
