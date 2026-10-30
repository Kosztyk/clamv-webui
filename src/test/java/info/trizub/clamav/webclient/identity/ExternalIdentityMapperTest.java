package info.trizub.clamav.webclient.identity;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class ExternalIdentityMapperTest {
    private final IdentityProperties config=new IdentityProperties();
    private final ExternalIdentityMapper mapper=new ExternalIdentityMapper(config);
    private Set<String> names(ExternalIdentityMapper.MappedIdentity id) {
        return id.authorities().stream().map(a->a.getAuthority()).collect(java.util.stream.Collectors.toSet());
    }
    @Test void noConfiguredGroupsMeansNoExternalAccess() {
        assertThat(names(mapper.map(ExternalIdentityMapper.Source.OIDC,"https://id.example/realm","123",List.of("admins")))).isEmpty();
        assertThat(names(mapper.map(ExternalIdentityMapper.Source.LDAP,"ldaps://directory.example/dc=example","cn=admin,dc=example",List.of("Domain Admins")))).isEmpty();
    }
    @Test void roleMatrixIsExactAndNoUnassignedAdmin() {
        config.getOidc().getGroups().setViewer("readers");
        config.getOidc().getGroups().setOperator("operators");
        config.getOidc().getGroups().setAdmin("security-admins");
        assertThat(names(mapper.map(ExternalIdentityMapper.Source.OIDC,"https://id.example","one",List.of("readers"))))
            .containsExactlyInAnyOrder("ROLE_VIEWER");
        assertThat(names(mapper.map(ExternalIdentityMapper.Source.OIDC,"https://id.example","two",List.of("operators"))))
            .containsExactlyInAnyOrder("ROLE_VIEWER","ROLE_OPERATOR");
        assertThat(names(mapper.map(ExternalIdentityMapper.Source.OIDC,"https://id.example","three",List.of("SECURITY-ADMINS"))))
            .containsExactlyInAnyOrder("ROLE_VIEWER","ROLE_OPERATOR","ROLE_ADMIN");
        assertThat(names(mapper.map(ExternalIdentityMapper.Source.OIDC,"https://id.example","four",List.of("superadmin")))).isEmpty();
    }
    @Test void identityNeverCollidesAcrossSourceIssuerOrSubject() {
        var ldap=mapper.map(ExternalIdentityMapper.Source.LDAP,"ldap.example","sam",List.of());
        var oidc=mapper.map(ExternalIdentityMapper.Source.OIDC,"ldap.example","sam",List.of());
        var issuer2=mapper.map(ExternalIdentityMapper.Source.OIDC,"other.example","sam",List.of());
        var subject2=mapper.map(ExternalIdentityMapper.Source.OIDC,"ldap.example","SAM",List.of());
        assertThat(Set.of(ldap.principalName(), oidc.principalName(), issuer2.principalName(), subject2.principalName())).hasSize(4);
        assertThat(oidc.principalName()).startsWith("oidc:").hasSizeLessThanOrEqualTo(64);
        assertThat(ldap.principalName()).startsWith("ldap:");
        assertThat(mapper.map(ExternalIdentityMapper.Source.OIDC,"ldap.example","sam",List.of()).principalName())
            .isEqualTo(oidc.principalName());
    }
    @Test void adminMappingMustBeConfiguredOnEachProvider() {
        config.getLdap().getGroups().setAdmin("ClamAV-Admins");
        assertThat(names(mapper.map(ExternalIdentityMapper.Source.LDAP,"ldap.example","sam",List.of("clamav-admins")))).contains("ROLE_ADMIN");
        assertThat(names(mapper.map(ExternalIdentityMapper.Source.OIDC,"oidc.example","sam",List.of("clamav-admins")))).isEmpty();
    }
    @Test void missingImmutableSubjectIsRejected() {
        assertThatThrownBy(() -> mapper.map(ExternalIdentityMapper.Source.OIDC,"issuer","",List.of("admins")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
