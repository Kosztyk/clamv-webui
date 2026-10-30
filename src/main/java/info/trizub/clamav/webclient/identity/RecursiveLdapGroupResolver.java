package info.trizub.clamav.webclient.identity;

import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.security.ldap.userdetails.LdapAuthoritiesPopulator;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import javax.naming.directory.SearchControls;
import java.util.*;

/** Configurable direct and bounded recursive group lookup; never assigns an application role itself. */
public class RecursiveLdapGroupResolver implements LdapAuthoritiesPopulator {
    private final LdapTemplate template;
    private final IdentityProperties.Ldap config;
    private final LdapGroupSearchFilter groupFilter;
    record Group(String name, String dn) {}
    public RecursiveLdapGroupResolver(LdapTemplate template, IdentityProperties.Ldap config) {
        this.template=template; this.config=config;
        verifyAttribute(config.getGroupNameAttribute());
        groupFilter = new LdapGroupSearchFilter(config);
    }
    private static void verifyAttribute(String attribute) {
        if (attribute == null || !attribute.matches("[a-zA-Z][a-zA-Z0-9-]{0,63}"))
            throw new IllegalArgumentException("Unsafe LDAP attribute name");
    }
    @Override public Collection<? extends GrantedAuthority> getGrantedAuthorities(DirContextOperations userData, String username) {
        String userDn = userData.getNameInNamespace();
        Set<String> found = new LinkedHashSet<>();
        Set<String> visitedDns = new HashSet<>();
        Set<String> queue = new LinkedHashSet<>(List.of(userDn));
        int iterations = config.isNestedGroups() ? Math.max(1, Math.min(config.getMaxNestedDepth(), 8)) : 1;
        for (int depth=0; depth<iterations && !queue.isEmpty(); depth++) {
            Set<String> next = new LinkedHashSet<>();
            for (String dn : queue) {
                String filter = groupFilter.render(dn, username);
                List<Group> parents = template.search(config.getGroupSearchBase(), filter, SearchControls.SUBTREE_SCOPE,
                    (ContextMapper<Group>) ctx -> {
                        DirContextOperations group = (DirContextOperations) ctx;
                        String groupName = group.getStringAttribute(config.getGroupNameAttribute());
                        String fullDn = group.getNameInNamespace();
                        return new Group(groupName, fullDn);
                    });
                for (Group group : parents) {
                    if (group.name() != null && !group.name().isBlank()) found.add(group.name());
                    if (visitedDns.size() >= 256) throw new IllegalStateException("LDAP group nesting limit exceeded");
                    if (visitedDns.add(group.dn().toLowerCase(Locale.ROOT))) next.add(group.dn());
                }
            }
            queue=next;
        }
        return found.stream().map(name -> (GrantedAuthority) new SimpleGrantedAuthority("LDAP_GROUP:"+name)).toList();
    }
}
