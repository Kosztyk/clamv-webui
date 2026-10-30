package info.trizub.clamav.webclient.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;

import javax.naming.directory.SearchControls;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecursiveLdapGroupResolverTest {
    private IdentityProperties.Ldap config(String filter) {
        var config = new IdentityProperties.Ldap();
        config.setGroupSearchBase("ou=groups");
        config.setGroupSearchFilter(filter);
        return config;
    }

    @Test void compoundFilterEscapesDnAndUsernameAsValues() {
        var filter = new LdapGroupSearchFilter(config("(&(objectClass=group)(member={0})(uid={1}))"));
        assertThat(filter.render("uid=a*)(cn=*),dc=example", "sam\0\\x"))
            .isEqualTo("(&(objectClass=group)(member=uid=a\\2a\\29\\28cn=\\2a\\29,dc=example)(uid=sam\\00\\5cx))");
    }

    @Test void substitutedValuesAreNeverReinterpretedAsPlaceholders() {
        var filter = new LdapGroupSearchFilter(config("(member={0})"));
        assertThat(filter.render("uid={1},dc=example", "other-user"))
            .isEqualTo("(member=uid={1},dc=example)");
    }

    @Test void usernameOnlyFilterSupportsPosixGroups() {
        assertThat(new LdapGroupSearchFilter(config("(&(objectClass=posixGroup)(memberUid={1}))"))
            .render("uid=sam,dc=example", "sam*)(cn=*)"))
            .isEqualTo("(&(objectClass=posixGroup)(memberUid=sam\\2a\\29\\28cn=\\2a\\29))");
    }

    @Test void omittedCustomFilterPreservesLegacyMemberAttribute() {
        var config = config("");
        config.setGroupMemberAttribute("uniqueMember");
        assertThat(new LdapGroupSearchFilter(config).render("uid=sam,dc=example", "sam"))
            .isEqualTo("(uniqueMember=uid=sam,dc=example)");
    }

    @Test void adMatchingRuleAndNegationRemainAvailable() {
        var filter = "(&(member:1.2.840.113556.1.4.1941:={0})(!(cn=disabled)))";
        assertThat(new LdapGroupSearchFilter(config(filter)).render("uid=sam,dc=example", "sam"))
            .isEqualTo("(&(member:1.2.840.113556.1.4.1941:=uid=sam,dc=example)(!(cn=disabled)))");
    }

    @ParameterizedTest
    @ValueSource(strings = {"(member={2})", "(member={0}", "(member={0})(cn=admins)", "(|)",
        "(&(member={0})", "(member=*)", "(member={0}\\zz)", "(!(member={0})(cn=admins))"})
    void invalidTemplatesAreRejectedBeforeDirectoryQueries(String filter) {
        assertThatThrownBy(() -> new LdapGroupSearchFilter(config(filter)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void usernameOnlyFilterRequiresNestedLookupToBeDisabled() {
        var config = config("(memberUid={1})");
        config.setNestedGroups(true);
        assertThatThrownBy(() -> new LdapGroupSearchFilter(config))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Nested LDAP groups require {0}");
    }

    @Test void realResolverUsesCustomFilterForDirectAndNestedSearches() {
        var config = config("(&(objectClass=group)(member={0}))");
        config.setNestedGroups(true);
        var template = mock(LdapTemplate.class);
        var user = mock(DirContextOperations.class);
        when(user.getNameInNamespace()).thenReturn("uid=sam,dc=example");
        String direct = "(&(objectClass=group)(member=uid=sam,dc=example))";
        String nested = "(&(objectClass=group)(member=cn=readers,ou=groups,dc=example))";
        when(template.search(eq("ou=groups"), eq(direct), eq(SearchControls.SUBTREE_SCOPE),
            org.mockito.ArgumentMatchers.<ContextMapper<RecursiveLdapGroupResolver.Group>>any()))
            .thenReturn(List.of(new RecursiveLdapGroupResolver.Group("readers", "cn=readers,ou=groups,dc=example")));
        when(template.search(eq("ou=groups"), eq(nested), eq(SearchControls.SUBTREE_SCOPE),
            org.mockito.ArgumentMatchers.<ContextMapper<RecursiveLdapGroupResolver.Group>>any()))
            .thenReturn(List.of(new RecursiveLdapGroupResolver.Group("admins", "cn=admins,ou=groups,dc=example")));
        when(template.search(eq("ou=groups"), eq("(&(objectClass=group)(member=cn=admins,ou=groups,dc=example))"),
            eq(SearchControls.SUBTREE_SCOPE), org.mockito.ArgumentMatchers.<ContextMapper<RecursiveLdapGroupResolver.Group>>any()))
            .thenReturn(List.of());

        var authorities = new RecursiveLdapGroupResolver(template, config).getGrantedAuthorities(user, "sam");
        assertThat(authorities).extracting(a -> a.getAuthority())
            .containsExactlyInAnyOrder("LDAP_GROUP:readers", "LDAP_GROUP:admins");
        verify(template, times(3)).search(eq("ou=groups"), anyString(), eq(SearchControls.SUBTREE_SCOPE),
            org.mockito.ArgumentMatchers.<ContextMapper<RecursiveLdapGroupResolver.Group>>any());
    }
}
