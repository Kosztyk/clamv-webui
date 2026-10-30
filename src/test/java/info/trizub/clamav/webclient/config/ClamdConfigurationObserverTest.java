package info.trizub.clamav.webclient.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ClamdConfigurationObserverTest {
    @TempDir Path dir;
    @Test void preservesRepeatedDirectivesAndQuotesButRedactsPasswords() throws Exception {
        Files.writeString(dir.resolve("inc.conf"), "LogFile \"/some path/my.log\"\n");
        Files.writeString(dir.resolve("clamd.conf"), "# ignored\nExcludePath /tmp\nExcludePath \"/var/special path\" # comment\nProxyPassword p4ss\nInclude inc.conf\n");
        var view=ClamdConfigurationObserver.parse(dir.resolve("clamd.conf").toString());
        assertEquals("LOADED",view.status());
        assertEquals(2,view.directives().get("ExcludePath").size());
        assertEquals("/var/special path",view.directives().get("ExcludePath").get(1));
        assertEquals("/some path/my.log",view.directives().get("LogFile").get(0));
        assertEquals("[REDACTED]",view.directives().get("ProxyPassword").get(0));
        assertFalse(view.toString().contains("p4ss"));
    }
    @Test void missingMountIsDisabledAndNoGuessing() {
        assertEquals("NOT_MOUNTED",ClamdConfigurationObserver.parse("").status());
    }
    @Test void includeMayNotEscapeConfiguredRoot() throws Exception {
        Path privateFile=Files.createTempFile("outside-observer", ".conf");
        try {
            Files.writeString(privateFile,"SecretToken leak\n");
            Files.writeString(dir.resolve("clamd.conf"),"Include " + privateFile + "\n");
            var view=ClamdConfigurationObserver.parse(dir.resolve("clamd.conf").toString());
            assertTrue(view.notices().stream().anyMatch(v->v.contains("outside")));
            assertFalse(view.directives().containsKey("SecretToken"));
        } finally {Files.deleteIfExists(privateFile);}
    }
    @Test void discoveryRequiresOptInAndUnambiguousTcpAddress() throws Exception {
        Files.writeString(dir.resolve("clamd.conf"),"TCPAddr 192.168.68.112\nTCPSocket 3310\n");
        assertTrue(new ClamdConfigurationObserver(dir.resolve("clamd.conf").toString(),"",false).tcpCandidate().isEmpty());
        var candidate=new ClamdConfigurationObserver(dir.resolve("clamd.conf").toString(),"",true).tcpCandidate().orElseThrow();
        assertEquals("192.168.68.112",candidate.host()); assertEquals(3310,candidate.port());
        Files.writeString(dir.resolve("clamd.conf"),"LocalSocket /run/clamd.sock\n");
        assertTrue(new ClamdConfigurationObserver(dir.resolve("clamd.conf").toString(),"",true).tcpCandidate().isEmpty());
    }

    @Test void refreshReadsChangedIncludesAndFreshclamEvenWhenFileTimestampDoesNotChange() throws Exception {
        Path include=dir.resolve("limits.conf");
        Files.writeString(include,"StreamMaxLength 2M\nTCPSocket 3310\n");
        Files.writeString(dir.resolve("clamd.conf"),"Include limits.conf\nExcludePath \\.log$\n");
        Path freshclam=dir.resolve("freshclam.conf");
        Files.writeString(freshclam,"Checks 12\nHTTPProxyPassword private-secret\n");
        var observer=new ClamdConfigurationObserver(dir.resolve("clamd.conf").toString(),freshclam.toString(),true);
        var timestamp=Files.getLastModifiedTime(include);
        Files.writeString(include,"StreamMaxLength 4M\nTCPSocket 3311\n");
        Files.setLastModifiedTime(include,timestamp);
        Files.writeString(freshclam,"Checks 24\nHTTPProxyPassword private-secret\n");
        observer.refresh();
        assertEquals(4L*1024*1024,observer.effective().streamMaxLength());
        assertEquals(3311,observer.endpointCandidate().orElseThrow().port());
        assertEquals("\\.log$",observer.effective().excludePaths().get(0));
        assertEquals("24",observer.freshclam().directives().get("Checks").get(0));
        assertFalse(observer.freshclam().toString().contains("private-secret"));
    }

    @ParameterizedTest
    @CsvSource({"0.0.0.0,127.0.0.1","::,::1","localhost,localhost","127.0.0.1,127.0.0.1","[::1],::1"})
    void discoversColocatedAndWildcardTcpAddresses(String input,String expected) throws Exception {
        Files.writeString(dir.resolve("clamd.conf"),"TCPAddr "+input+"\nTCPSocket 3310\n");
        var observer=new ClamdConfigurationObserver(dir.resolve("clamd.conf").toString(),"",true);
        assertEquals(expected,observer.tcpCandidate().orElseThrow().host());
    }

    @Test void discoversUnixSocketBeforeTcpWithoutRequiringDaemonToStartFirst() throws Exception {
        Files.writeString(dir.resolve("clamd.conf"),"LocalSocket \"/run/clamav/clamd socket\"\nTCPSocket 3310\n");
        var observer=new ClamdConfigurationObserver(dir.resolve("clamd.conf").toString(),"",true);
        var endpoint=observer.endpointCandidate().orElseThrow();
        assertTrue(endpoint.unixSocket());assertEquals("/run/clamav/clamd socket",endpoint.host());
        assertEquals(0,endpoint.port());
    }

    @ParameterizedTest
    @ValueSource(strings={"StreamMaxLength -1M","StreamMaxLength 99999999999999999G","ExcludePath [",
        "TCPSocket 70000","Include missing.conf","StreamMaxLength 1M\nStreamMaxLength 2M",
        "LocalSocket relative.sock","ExcludePath \"unterminated"})
    void invalidUpdatesKeepTheEntireLastValidConfiguration(String invalid) throws Exception {
        Path file=dir.resolve("clamd.conf");
        Files.writeString(file,"TCPSocket 3310\nStreamMaxLength 2M\nExcludePath \\.log$\n");
        var observer=new ClamdConfigurationObserver(file.toString(),"",true);
        var valid=observer.effective();
        Files.writeString(file,invalid+"\n");observer.refresh();
        assertSame(valid,observer.effective());
        assertTrue(observer.usingLastKnownConfiguration());assertFalse(observer.policyProblem().isBlank());
    }

    @Test void missingFileRetainsPolicyAndRecoversAfterAtomicReplacement() throws Exception {
        Path file=dir.resolve("clamd.conf");Files.writeString(file,"TCPSocket 3310\nStreamMaxLength 1K\n");
        var observer=new ClamdConfigurationObserver(file.toString(),"",true);
        Files.delete(file);observer.refresh();
        assertEquals("NOT_FOUND",observer.clamd().status());
        assertEquals(1024,observer.effective().streamMaxLength());
        Files.writeString(dir.resolve("new.conf"),"TCPSocket 3312\nStreamMaxLength 2K\n");
        Files.move(dir.resolve("new.conf"),file,StandardCopyOption.ATOMIC_MOVE);
        observer.refresh();
        assertEquals(3312,observer.endpointCandidate().orElseThrow().port());
        assertFalse(observer.usingLastKnownConfiguration());assertTrue(observer.policyProblem().isBlank());
    }

    @Test void inspectionOnlyModeNeverExposesEffectivePolicyOrEndpointCandidate() throws Exception {
        Path file=dir.resolve("clamd.conf");Files.writeString(file,"TCPSocket 3310\nStreamMaxLength 1M\n");
        var observer=new ClamdConfigurationObserver(file.toString(),"",false);
        assertEquals("LOADED",observer.clamd().status());assertNull(observer.effective());
        assertTrue(observer.endpointCandidate().isEmpty());
    }
}
