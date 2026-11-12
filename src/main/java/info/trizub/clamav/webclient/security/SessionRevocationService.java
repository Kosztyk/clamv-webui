package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.identity.ExternalLdapPrincipal;
import info.trizub.clamav.webclient.identity.ExternalOidcUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.function.Predicate;

@Service
public class SessionRevocationService {
    private final SessionRegistry registry;
    private final ApplicationEventPublisher events;
    public SessionRevocationService(SessionRegistry registry, ApplicationEventPublisher events) {
        this.registry = registry; this.events = events;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void accountChanged(LocalAccountChanged change) { revokeLocal(change.accountId()); }

    public int revokeLocal(Long accountId) {
        return expire(principal -> principal instanceof LocalUserDetails user && accountId.equals(user.accountId()));
    }

    public int revokeExternal(String name) {
        if (name == null || !name.matches("(?:ldap|oidc):[0-9a-f]{48}"))
            throw new IllegalArgumentException("Select a recorded external identity");
        return expire(principal -> principal instanceof ExternalLdapPrincipal ldap && name.equals(ldap.getName())
            || principal instanceof ExternalOidcUser oidc && name.equals(oidc.getName()));
    }

    private int expire(Predicate<Object> matches) {
        int expired = 0;
        for (Object principal : registry.getAllPrincipals()) {
            if (!matches.test(principal)) continue;
            for (var session : registry.getAllSessions(principal, false)) {
                session.expireNow();
                events.publishEvent(new SessionRevoked(session.getSessionId()));
                expired++;
            }
        }
        return expired;
    }
}
