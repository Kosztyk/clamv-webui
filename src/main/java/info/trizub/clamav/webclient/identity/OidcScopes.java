package info.trizub.clamav.webclient.identity;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Shared validation for environment scopes, admin saves and authorization requests. */
final class OidcScopes {
    private OidcScopes() { }

    static List<String> parse(String value) {
        return normalize(value == null ? List.of() : List.of(value));
    }

    static List<String> normalize(Collection<String> values) {
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        int length = 0;
        if (values != null) for (String value : values) {
            if (value == null) throw invalid();
            length += value.length();
            if (length > 1024 || !value.matches("[\\x20-\\x7E]*")) throw invalid();
            // Accept both Spring's comma-separated value and a space-delimited scope list.
            for (String scope : value.split("[ ,]+")) {
                if (scope.isEmpty()) continue;
                // OAuth scope-token: printable ASCII except double quote and backslash.
                if (!scope.matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+")) throw invalid();
                scopes.add(scope);
            }
        }
        if (!scopes.contains("openid"))
            throw new IllegalArgumentException("OIDC scopes must include openid");
        return List.copyOf(scopes);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("OIDC scopes must be valid scope names separated by commas or spaces (maximum 1024 characters)");
    }
}
