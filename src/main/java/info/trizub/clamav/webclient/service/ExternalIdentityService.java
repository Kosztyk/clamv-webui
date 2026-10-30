package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.identity.ExternalIdentityMapper;
import info.trizub.clamav.webclient.model.ExternalIdentity;
import info.trizub.clamav.webclient.repo.ExternalIdentityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records the exact immutable issuer/subject behind each opaque audit principal. */
@Service
public class ExternalIdentityService {
    private final ExternalIdentityRepository identities;
    public ExternalIdentityService(ExternalIdentityRepository identities) { this.identities=identities; }
    @Transactional
    public void recordLogin(ExternalIdentityMapper.MappedIdentity mapped) {
        if (mapped == null || mapped.authorities().isEmpty())
            throw new IllegalArgumentException("Unmapped external identity cannot be recorded as a login");
        ExternalIdentity stored = identities.findById(mapped.principalName()).orElse(null);
        if (stored == null) {
            stored = new ExternalIdentity(mapped.principalName(), mapped.source().name(), mapped.issuer(), mapped.subject());
        } else if (!stored.getSource().equals(mapped.source().name()) ||
                !stored.getIssuer().equals(mapped.issuer()) || !stored.getSubject().equals(mapped.subject())) {
            // Even a highly unlikely digest collision must never link two accounts.
            throw new IllegalStateException("External identity collision detected");
        }
        stored.seenNow();
        identities.save(stored);
    }
}
