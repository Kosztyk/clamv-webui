package info.trizub.clamav.webclient.identity;

import static org.assertj.core.api.Assertions.*;

import javax.naming.NamingException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ldap.core.support.DefaultTlsDirContextAuthenticationStrategy;
import org.springframework.test.util.ReflectionTestUtils;

/** StartTLS must use JNDI's default strict certificate/hostname checks on JDK 21. */
class Issue44StartTlsHostnameVerificationTest {
  @Test
  void actualJndiStartTlsRejectsTrustedCertificateWithWrongSan() throws Exception {
    StartTlsJndiMismatchProbe.verify();
  }

  @Test
  void strategyDoesNotOverrideJndiDefaultHostnameVerifier() {
    DefaultTlsDirContextAuthenticationStrategy strategy =
        IdentityLdapConfiguration.startTlsStrategy();
    // Explicitly setting a verifier that returns true would bypass validation.
    assertThat(ReflectionTestUtils.getField(strategy, "hostnameVerifier")).isNull();
  }

  @Test
  void subjectAlternativeNameMismatchIsReportedWithoutLeakingDirectoryNames() {
    var failure = new NamingException("StartTLS failure");
    failure.setRootCause(
        new SSLHandshakeException(
            "No subject alternative DNS name matching LDAP-PRIVATE.example found"));
    String diagnostic = AuthenticationProviderProber.ldapFailureReason(failure);
    assertThat(diagnostic).contains("hostname/IP mismatch");
    assertThat(diagnostic).doesNotContain("LDAP-PRIVATE.example");
  }

  /**
   * Optional wire-level integration test. Run with a local LDAP StartTLS fixture whose certificate
   * is trusted by the test JVM but has a deliberately wrong SAN. The supplied URL MUST be a
   * mismatch and MUST NOT be production LDAP.
   */
  @Test
  @EnabledIfEnvironmentVariable(named = "CLAMAV_TEST_STARTTLS_MISMATCH_URL", matches = "ldap://.*")
  void realStartTlsSanMismatchFailsClosed() throws Exception {
    var ldap = new IdentityProperties.Ldap();
    ldap.setUrl(System.getenv("CLAMAV_TEST_STARTTLS_MISMATCH_URL"));
    ldap.setBaseDn("dc=test");
    ldap.setManagerDn("cn=service,dc=test");
    ldap.setUserSearchFilter("(uid={0})");
    ldap.setStartTls(true);
    var constructed = IdentityLdapConfiguration.createContext(ldap, "test-secret");
    assertThatThrownBy(
            () -> {
              var context = constructed.context().getReadOnlyContext();
              try {
                context.getAttributes("");
              } finally {
                context.close();
              }
            })
        .isInstanceOf(NamingException.class)
        .satisfies(
            error ->
                assertThat(AuthenticationProviderProber.ldapFailureReason(error))
                    .containsAnyOf("hostname/IP mismatch", "TLS handshake failed"));
  }
}
