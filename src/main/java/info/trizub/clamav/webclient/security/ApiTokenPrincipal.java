package info.trizub.clamav.webclient.security;

import java.security.Principal;
import java.util.Set;

public record ApiTokenPrincipal(Long id, String label, Set<String> scopes,
                                Set<Long> endpointIds, Set<String> pathRoots) implements Principal {
    @Override public String getName() { return "token:" + id; }
}
