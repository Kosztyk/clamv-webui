package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;

import info.trizub.clamav.webclient.configuration.ConfigurationStore;
import info.trizub.clamav.webclient.util.AtomicPropertiesFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Issue42LegacyWebhookMigrationTest {
  @TempDir Path folder;

  private ConfigurationStore encrypted() {
    return new ConfigurationStore(
        folder.resolve("configuration-center.properties"),
        folder.resolve("configuration-center.key"));
  }

  @Test
  void movesExistingWebhookTokenToEncryptedStoreAndScrubsPlaintextFile() throws Exception {
    String token = "https://example.test/webhooks/private-test-token-123456";
    Properties legacy = new Properties();
    legacy.setProperty("app.webhook.url", token);
    legacy.setProperty("app.webhook.enabled", "true");
    legacy.setProperty("app.allowedScanRoots", "/scandir");
    var store = encrypted();

    SettingsService.migrateLegacyWebhookUrl(legacy, Optional.of(store));
    AtomicPropertiesFile.storeAtomic(folder.resolve("clamav-web-client.properties"), legacy);

    assertThat(legacy).doesNotContainKey("app.webhook.url");
    assertThat(legacy).containsEntry("app.webhook.enabled", "true");
    assertThat(Files.readString(folder.resolve("clamav-web-client.properties")))
        .doesNotContain(token, "app.webhook.url");
    assertThat(Files.readString(store.file())).contains("cc1\\:").doesNotContain(token);
    assertThat(encrypted().saved("app.webhook.url")).contains(token);
    assertThat(Files.readString(folder.resolve("configuration-audit.jsonl"))).doesNotContain(token);
  }

  @Test
  void encryptedValueWinsOverStalePlaintextAndExplicitClearsCannotBeUndone() {
    var store = encrypted();
    store.save(
        "0", Map.of("app.webhook.url", "https://example.test/new-secret"), Set.of(), false, "test");
    Properties legacy = new Properties();
    legacy.setProperty("app.webhook.url", "https://example.test/old-secret");
    SettingsService.migrateLegacyWebhookUrl(legacy, Optional.of(store));
    assertThat(encrypted().saved("app.webhook.url")).contains("https://example.test/new-secret");
    assertThat(legacy).doesNotContainKey("app.webhook.url");
    store.save(store.snapshot().revision(), Map.of(), Set.of("app.webhook.url"), false, "test");
    legacy.setProperty("app.webhook.url", "https://example.test/old-secret");
    SettingsService.migrateLegacyWebhookUrl(legacy, Optional.of(store));
    assertThat(encrypted().saved("app.webhook.url")).contains("");
    assertThat(legacy).doesNotContainKey("app.webhook.url");
  }

  @Test
  void missingEncryptedStoreFailsClosedAndDoesNotDiscardCredential() {
    Properties legacy = new Properties();
    legacy.setProperty("app.webhook.url", "https://example.test/legacy-secret");
    assertThatThrownBy(() -> SettingsService.migrateLegacyWebhookUrl(legacy, Optional.empty()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Encrypted Configuration Center");
    assertThat(legacy.getProperty("app.webhook.url"))
        .isEqualTo("https://example.test/legacy-secret");
  }

  @Test
  void emptyUrlIsNeverWrittenToLegacyFile() {
    Properties legacy = new Properties();
    legacy.setProperty("app.webhook.url", "");
    SettingsService.migrateLegacyWebhookUrl(legacy, Optional.empty());
    assertThat(legacy).doesNotContainKey("app.webhook.url");
  }
}
