package info.trizub.clamav.webclient.configuration;

import java.util.List;

/** One allow-listed setting; shared by startup loading, validation and both UIs. */
public record ConfigurationSetting(String key, String label, String category, String section,
        String type, String defaultValue, List<String> environment, String apply,
        boolean secret, boolean sensitive, boolean advanced, String hint,
        List<String> choices, long minimum, long maximum, String legacy) {
    public ConfigurationSetting {
        environment = environment == null ? List.of() : List.copyOf(environment);
        choices = choices == null ? List.of() : List.copyOf(choices);
        defaultValue = defaultValue == null ? "" : defaultValue;
        hint = hint == null ? "" : hint;
        legacy = legacy == null ? "" : legacy;
    }
    public boolean live() { return "live".equals(apply); }
    public boolean policy() { return key.startsWith("app.") && !key.startsWith("app.db."); }
}
