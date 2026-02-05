package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.repo.AppUserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class DbUserDetailsService implements UserDetailsService {

    private final AppUserRepository repo;

    public DbUserDetailsService(AppUserRepository repo) {
        this.repo = repo;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var u = repo.findByUsername(username).orElseThrow(() -> new UsernameNotFoundException("Not found: " + username));
        var auth = u.getRoles().stream().map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        return org.springframework.security.core.userdetails.User
                .withUsername(u.getUsername())
                .password(u.getPasswordHash())
                .authorities(auth)
                .disabled(!u.isEnabled())
                .build();
    }
}
