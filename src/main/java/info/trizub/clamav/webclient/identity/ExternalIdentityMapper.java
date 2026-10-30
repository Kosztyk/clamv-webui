package info.trizub.clamav.webclient.identity;

import info.trizub.clamav.webclient.model.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Trust boundary shared by LDAP and OIDC: no implicit username/email matching, no default role. */
@Component
public class ExternalIdentityMapper {
    public enum Source { LDAP, OIDC }
    public record MappedIdentity(Source source, String issuer, String subject, String principalName,
                                 Set<GrantedAuthority> authorities) {}
    private final IdentityProperties properties;
    public ExternalIdentityMapper(IdentityProperties properties) { this.properties=properties; }

    public MappedIdentity map(Source source, String issuer, String subject, Collection<String> memberships) {
        if (source == null || blank(issuer) || blank(subject)) {
            throw new IllegalArgumentException("External identity requires source, issuer and immutable subject");
        }
        IdentityProperties.RoleGroups configured = source == Source.LDAP
                ? properties.getLdap().getGroups() : properties.getOidc().getGroups();
        Set<String> groups = new HashSet<>();
        if (memberships != null) for (String group : memberships) {
            if (!blank(group)) groups.add(group.trim().toLowerCase(Locale.ROOT));
        }
        Set<GrantedAuthority> roles = new LinkedHashSet<>();
        if (matches(groups, configured.getViewer())) roles.add(authority(Role.VIEWER));
        if (matches(groups, configured.getOperator())) {
            roles.add(authority(Role.VIEWER)); roles.add(authority(Role.OPERATOR));
        }
        if (matches(groups, configured.getAdmin())) {
            roles.add(authority(Role.VIEWER)); roles.add(authority(Role.OPERATOR)); roles.add(authority(Role.ADMIN));
        }
        // Canonical principal is source-qualified and derived from immutable IdP identifiers,
        // never from a potentially shared mutable username or email address.
        String canonical = source.name().toLowerCase(Locale.ROOT) + ":" + digest(issuer + "\u0000" + subject).substring(0, 48);
        return new MappedIdentity(source, issuer, subject, canonical, Collections.unmodifiableSet(roles));
    }
    private static GrantedAuthority authority(Role role) { return new SimpleGrantedAuthority(role.asAuthority()); }
    private static boolean matches(Set<String> memberships, String config) {
        if (config == null || config.isBlank()) return false;
        for (String configured : config.split(",")) {
            String group = configured.trim().toLowerCase(Locale.ROOT);
            if (!group.isEmpty() && memberships.contains(group)) return true;
        }
        return false;
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String digest(String s) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
