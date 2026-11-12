package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.model.AppUser;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/** An immutable account snapshot. Credential erasure retains only a digest for revocation checks. */
public final class LocalUserDetails implements UserDetails, CredentialsContainer {
    private static final long serialVersionUID = 1L;
    private final Long accountId;
    private final String username;
    private final Set<String> roles;
    private final boolean enabled;
    private final byte[] credentialStamp;
    private String password;

    public LocalUserDetails(AppUser user) {
        accountId = Objects.requireNonNull(user.getId());
        username = user.getUsername();
        password = user.getPasswordHash();
        credentialStamp = stamp(password);
        roles = Set.copyOf(user.getRoles());
        enabled = user.isEnabled();
    }

    public Long accountId() { return accountId; }
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return password; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(SimpleGrantedAuthority::new).toList();
    }
    @Override public void eraseCredentials() { password = null; }

    public boolean matches(AppUser current) {
        return current != null && current.isEnabled() && accountId.equals(current.getId())
            && username.equals(current.getUsername()) && roles.equals(current.getRoles())
            && MessageDigest.isEqual(credentialStamp, stamp(current.getPasswordHash()));
    }

    private static byte[] stamp(String encodedPassword) {
        try { return MessageDigest.getInstance("SHA-256").digest(encodedPassword.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    // Registry keys must survive credential erasure and represent the immutable account, not its role snapshot.
    @Override public boolean equals(Object other) {
        return other instanceof LocalUserDetails user && accountId.equals(user.accountId);
    }
    @Override public int hashCode() { return accountId.hashCode(); }
}
