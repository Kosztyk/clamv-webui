package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import org.junit.jupiter.api.Test;
import xyz.capybara.clamav.Platform;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class NativeClamdtopConnectionTest {
    @Test void socketPathsRemainOneLiteralArgumentIncludingShellMetacharacters() throws Exception {
        String path="/run/clam av/socket'$(never_execute);name";
        String spec=NativeClamdtopWebSocket.connectionSpec(new ClamdEndpoint("default",path,0,Platform.UNIX));
        var process=new ProcessBuilder("/bin/sh","-c","printf '%s' "+NativeClamdtopWebSocket.shellQuote(spec)).start();
        assertThat(process.waitFor(3,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
        assertThat(new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(path);
    }
    @Test void tcpAndIpv6KeepTheirPortAndInvalidHostsAreRejected() {
        assertThat(NativeClamdtopWebSocket.connectionSpec(new ClamdEndpoint("default","localhost",3310,Platform.UNIX))).isEqualTo("localhost:3310");
        assertThat(NativeClamdtopWebSocket.connectionSpec(new ClamdEndpoint("default","::1",3310,Platform.UNIX))).isEqualTo("[::1]:3310");
        assertThatThrownBy(() -> NativeClamdtopWebSocket.connectionSpec(new ClamdEndpoint("default","bad;host",3310,Platform.UNIX)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
