package info.trizub.clamav.webclient.identity;

import info.trizub.clamav.webclient.repo.ExternalIdentityRepository;
import info.trizub.clamav.webclient.repo.AppUserRepository;
import info.trizub.clamav.webclient.service.ExternalIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;

@DataJpaTest(properties={
    "spring.datasource.url=jdbc:h2:mem:phase3-mapping;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@Import({ExternalIdentityService.class, ExternalIdentityMapper.class, IdentityProperties.class})
class Phase3IdentityPersistenceTest {
    @Autowired ExternalIdentityService service;
    @Autowired ExternalIdentityRepository external;
    @Autowired AppUserRepository local;
    @Autowired ExternalIdentityMapper mapper;
    @Autowired IdentityProperties properties;
    @Test void externalSubjectStoredSeparatelyAndNeverCreatesPassword() {
        properties.getOidc().getGroups().setViewer("readers");
        long localsBefore=local.count();
        var id=mapper.map(ExternalIdentityMapper.Source.OIDC,"https://auth.example/realm","immutable-id-456",List.of("readers"));
        service.recordLogin(id);
        var stored=external.findById(id.principalName()).orElseThrow();
        assertThat(stored.getSource()).isEqualTo("OIDC");
        assertThat(stored.getIssuer()).isEqualTo("https://auth.example/realm");
        assertThat(stored.getSubject()).isEqualTo("immutable-id-456");
        assertThat(local.count()).isEqualTo(localsBefore);
        service.recordLogin(id);
        assertThat(external.count()).isEqualTo(1);
    }
}
