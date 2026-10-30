package info.trizub.clamav.webclient.identity;

import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.stereotype.Component;
import java.util.Map;
import javax.naming.NamingException;
import javax.naming.AuthenticationException;
import javax.naming.NameNotFoundException;
import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/** Validate trust/TLS and basic discovery/bind before enabling. Does not verify a user's password. */
@Component
public class AuthenticationProviderProber {
    public void test(String provider, Map<String,String> config, String secret) {
        Map<String,String> options=new java.util.HashMap<>(config);
        options.put("enabled","true");
        AuthenticationSettingsService.validate(provider,options,secret);
        try {
            if (provider.equals("ldap")) {
                var ldap=new IdentityProperties.Ldap();
                ldap.setEnabled(true);ldap.setUrl(options.get("url"));ldap.setBaseDn(options.get("baseDn"));
                ldap.setManagerDn(options.get("managerDn"));ldap.setUserSearchFilter(options.get("userSearchFilter"));
                ldap.setUserSearchBase(options.get("userSearchBase"));ldap.setGroupSearchBase(options.get("groupSearchBase"));
                ldap.setGroupSearchFilter(options.getOrDefault("groupSearchFilter", ""));
                ldap.setGroupMemberAttribute(options.get("groupMemberAttribute"));ldap.setGroupNameAttribute(options.get("groupNameAttribute"));
                ldap.setStartTls(Boolean.parseBoolean(options.get("startTls")));
                ldap.setPooled(Boolean.parseBoolean(options.get("pooled")));
                ldap.setReferrals(options.get("referrals"));
                ldap.setConnectTimeoutMillis(Integer.parseInt(options.get("connectTimeoutMillis")));
                ldap.setReadTimeoutMillis(Integer.parseInt(options.get("readTimeoutMillis")));
                ldap.setNestedGroups(Boolean.parseBoolean(options.get("nestedGroups")));
                ldap.setMaxNestedDepth(Integer.parseInt(options.get("maxNestedDepth")));
                var built=IdentityLdapConfiguration.createContext(ldap,secret);
                // Manager bind. JNDI TLS certificate validation uses the JVM truststore.
                var ctx=built.context().getReadOnlyContext();
                try { ctx.getAttributes(""); } finally { ctx.close(); }
            } else {
                // Discovery validates the issuer's configuration and HTTPS certificate.
                // Client-secret validity is verified only by the real authorization-code exchange.
                var registration=ClientRegistrations.fromIssuerLocation(options.get("issuer"))
                    .registrationId("clamav").clientId(options.get("clientId"))
                    .clientSecret(secret).scope(OidcScopes.parse(options.getOrDefault("scopes","openid,profile,email,groups"))).build();
                if (!registration.getProviderDetails().getIssuerUri().equals(options.get("issuer")))
                    throw new IllegalArgumentException("OIDC discovery issuer mismatch");
            }
        } catch(Exception e) {
            // No raw JNDI/IdP exception bodies are returned to a browser: they can contain
            // directory values or endpoint details. Only carefully selected diagnostics.
            throw new IllegalArgumentException(provider.equals("ldap")
                 ? ldapFailureReason(e)
                 : "OIDC discovery failed. Verify issuer URL, HTTPS certificate and server availability.", e);
        }
    }

    /** Package visible for tests. Never includes user-supplied secrets or raw LDAP error bodies. */
    static String ldapFailureReason(Throwable failure) {
        boolean handshake=false;
        boolean connection=false;
        boolean timeout=false;
        boolean authentication=false;
        boolean invalidBase=false;
        boolean badName=false;
        boolean untrusted=false;
        for (Throwable current=failure, next; current!=null; current=next) {
            // Include the JNDI root cause as well as the normal exception cause.
            next=current.getCause();
            if (next==null && current instanceof NamingException naming) next=naming.getRootCause();
            if (next==current) break;
            String message=current.getMessage();
            String lower=message==null?"":message.toLowerCase(Locale.ROOT);
            if (lower.contains("no subject alternative") || lower.contains("no name matching") ||
                lower.contains("doesn't match any") || lower.contains("does not match")) badName=true;
            if (lower.contains("pkix") || lower.contains("unable to find valid certification path") ||
                lower.contains("unable to get local issuer") || lower.contains("certificate_unknown") ||
                current.getClass().getSimpleName().contains("CertPath")) untrusted=true;
            if (current instanceof SSLHandshakeException) handshake=true;
            if (current instanceof AuthenticationException) authentication=true;
            if (current instanceof NameNotFoundException) invalidBase=true;
            if (current instanceof SocketTimeoutException) timeout=true;
            if (current instanceof ConnectException || current instanceof NoRouteToHostException ||
                current instanceof UnknownHostException) connection=true;
        }
        if (badName) return "LDAP TLS hostname/IP mismatch. The server certificate must include the LDAP URL hostname or IP in its Subject Alternative Name";
        if (untrusted) return "LDAP TLS certificate is not trusted by the ClamAV Java runtime. Import the LDAP test CA into the JVM truststore and restart ClamAV";
        if (handshake) return "LDAP TLS handshake failed. Verify the trusted LDAP CA and server certificate (hostname/IP and expiry)";
        if (authentication) return "LDAP manager bind was rejected. Check the Bind / manager DN and bind password";
        if (invalidBase) return "LDAP base DN or search base does not exist. Verify the directory tree";
        if (timeout) return "LDAP connection timed out. Check host, port, firewall, and timeout settings";
        if (connection) return "LDAP server could not be reached. Check the LDAPS host, port, and firewall";
        return "LDAP test failed. Verify bind DN, TLS certificate, network and search settings";
    }
}
