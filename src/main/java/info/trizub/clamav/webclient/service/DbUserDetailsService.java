package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.repo.AppUserRepository;
import info.trizub.clamav.webclient.security.LocalUserDetails;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
public class DbUserDetailsService implements UserDetailsService {

    private final AppUserRepository repo;

    public DbUserDetailsService(AppUserRepository repo) {
        this.repo = repo;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.startsWith("ldap:") || username.startsWith("oidc:"))
            throw new UsernameNotFoundException("Reserved identity namespace");
        var u = repo.findByUsername(username).orElseThrow(() -> new UsernameNotFoundException("Not found: " + username));
        return new LocalUserDetails(u);
    }
}
