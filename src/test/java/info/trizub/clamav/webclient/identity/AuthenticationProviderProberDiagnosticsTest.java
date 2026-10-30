package info.trizub.clamav.webclient.identity;

import org.junit.jupiter.api.Test;
import javax.naming.AuthenticationException;
import javax.naming.CommunicationException;
import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationProviderProberDiagnosticsTest {
    @Test void untrustedCertificateDoesNotExposeRawException() {
        var failure=new CommunicationException("directory hostname and private details");
        failure.setRootCause(new SSLHandshakeException("PKIX path building failed private data"));
        assertThat(AuthenticationProviderProber.ldapFailureReason(failure))
            .contains("not trusted").doesNotContain("private").doesNotContain("hostname and");
    }
    @Test void wrongBindPasswordHasSafeGuidance() {
        assertThat(AuthenticationProviderProber.ldapFailureReason(
                new AuthenticationException("invalid credentials including confidential directory content")))
            .contains("manager bind was rejected").doesNotContain("confidential");
    }
    @Test void unreachableServerHasSafeGuidance() {
        assertThat(AuthenticationProviderProber.ldapFailureReason(new ConnectException("connection refused")))
            .contains("could not be reached");
    }
    @Test void mismatchedCertificateNameHasSafeGuidance() {
        assertThat(AuthenticationProviderProber.ldapFailureReason(
                new SSLHandshakeException("No subject alternative names matching IP address 192.0.2.123")))
            .contains("hostname/IP mismatch").doesNotContain("192.0.2.123");
    }
}
