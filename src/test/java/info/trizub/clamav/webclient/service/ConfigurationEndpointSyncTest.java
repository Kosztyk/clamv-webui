package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.config.ClamdConfigurationObserver;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.repo.ClamdEndpointRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import xyz.capybara.clamav.Platform;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConfigurationEndpointSyncTest {
    @TempDir Path dir;
    private final ClamdEndpointRepository repository=mock(ClamdEndpointRepository.class);
    private final AtomicReference<ClamdEndpoint> stored=new AtomicReference<>();

    private EndpointService service(boolean enabled, MockEnvironment env) throws Exception {
        when(repository.findByName("default")).thenAnswer(i -> Optional.ofNullable(stored.get()));
        when(repository.count()).thenAnswer(i -> stored.get()==null ? 0L : 1L);
        when(repository.save(any())).thenAnswer(i -> { stored.set(i.getArgument(0));return stored.get(); });
        return new EndpointService(repository,mock(SettingsService.class),
            new ClamdConfigurationObserver(dir.resolve("clamd.conf").toString(),"",enabled),env);
    }

    @Test void seedsThenSynchronizesExistingDefaultWithoutChangingEnabledStateOrWritingUnchangedData() throws Exception {
        Files.writeString(dir.resolve("clamd.conf"),"TCPAddr 127.0.0.1\nTCPSocket 3310\n");
        var service=service(true,new MockEnvironment());service.ensureDefaultEndpoint();
        var first=stored.get();assertThat(first.getHost()).isEqualTo("127.0.0.1");
        first.setEnabled(false);clearInvocations(repository);
        service.pollConfiguration();verify(repository,never()).save(any());
        Files.writeString(dir.resolve("clamd.conf"),"TCPAddr 192.0.2.15\nTCPSocket 3311\n");
        service.pollConfiguration();
        assertThat(stored.get()).isSameAs(first);assertThat(first.getHost()).isEqualTo("192.0.2.15");
        assertThat(first.getPort()).isEqualTo(3311);assertThat(first.isEnabled()).isFalse();
    }

    @Test void updatesAnAlreadyPersistedDefaultAndSwitchesBetweenUnixAndTcp() throws Exception {
        var first=new ClamdEndpoint("default","old-server",3310,Platform.UNIX);stored.set(first);
        Files.writeString(dir.resolve("clamd.conf"),"LocalSocket /run/clamav/clamd.sock\n");
        var service=service(true,new MockEnvironment());service.pollConfiguration();
        assertThat(first.getHost()).isEqualTo("/run/clamav/clamd.sock");assertThat(first.getPort()).isZero();
        Files.writeString(dir.resolve("clamd.conf"),"TCPSocket 3312\n");service.pollConfiguration();
        assertThat(first.getHost()).isEqualTo("127.0.0.1");assertThat(first.getPort()).isEqualTo(3312);
        verify(repository,never()).findAll();
    }

    @ParameterizedTest @ValueSource(strings={"CLAMAV_HOST","CLAMAV_PORT"})
    void explicitConnectionEnvironmentOverridePreventsEndpointMutation(String name) throws Exception {
        var first=new ClamdEndpoint("default","operator-server",3315,Platform.UNIX);stored.set(first);
        Files.writeString(dir.resolve("clamd.conf"),"TCPSocket 3312\n");
        var service=service(true,new MockEnvironment().withProperty(name,name.equals("CLAMAV_PORT")?"3315":"operator-server"));
        service.pollConfiguration();assertThat(first.getHost()).isEqualTo("operator-server");
        verify(repository,never()).save(any());
    }

    @Test void inspectionOnlyAndInvalidInitialFileNeverMutateEndpoints() throws Exception {
        Files.writeString(dir.resolve("clamd.conf"),"TCPSocket 3312\n");
        service(false,new MockEnvironment()).pollConfiguration();verify(repository,never()).save(any());
        Files.writeString(dir.resolve("clamd.conf"),"TCPSocket invalid\n");
        service(true,new MockEnvironment()).pollConfiguration();verify(repository,never()).save(any());
    }
}
