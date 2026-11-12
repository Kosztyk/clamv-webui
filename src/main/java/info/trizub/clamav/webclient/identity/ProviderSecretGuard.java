package info.trizub.clamav.webclient.identity;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A write-only saved credential may only be reused with its existing destination and account. */
public final class ProviderSecretGuard {
    private ProviderSecretGuard() { }

    public static Set<String> destinationFields(String provider) {
        return switch (provider) {
            case "ldap" -> Set.of("url", "startTls", "baseDn", "managerDn", "referrals");
            case "oidc" -> Set.of("issuer", "clientId");
            default -> throw new IllegalArgumentException("Unsupported provider");
        };
    }

    public static void requireReplacement(String provider, Map<String, String> before, Map<String, String> after,
                                          boolean secretPresent, boolean explicitlySupplied, boolean explicitlyCleared) {
        if (!secretPresent || explicitlySupplied || explicitlyCleared) return;
        if (destinationFields(provider).stream().anyMatch(key -> !Objects.equals(value(before, key), value(after, key)))) {
            String label = provider.equals("ldap") ? "LDAP destination or bind identity" : "OIDC issuer or client identity";
            String credential = provider.equals("ldap") ? "bind password" : "client secret";
            throw new IllegalArgumentException(label + " changed. Re-enter the " + credential + " before testing or saving; a stored secret cannot be reused for a changed destination.");
        }
    }

    private static String value(Map<String, String> fields, String key) {
        String value = fields.getOrDefault(key, key.equals("referrals") ? "ignore" : "").trim();
        return key.equals("startTls") ? Boolean.toString(Boolean.parseBoolean(value)) : value;
    }
}
