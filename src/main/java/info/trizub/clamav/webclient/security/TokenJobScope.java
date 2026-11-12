package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.model.*;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import java.nio.file.*;
import java.util.*;

/** A request's pinned root grants. SQL narrows candidates; Java makes the final case-sensitive decision. */
public final class TokenJobScope {
    private final ApiTokenPrincipal token;
    private final List<Path> roots;

    public TokenJobScope(ApiTokenPrincipal token) {
        this.token = token;
        var canonical = new ArrayList<Path>();
        for (String value : token.pathRoots()) {
            try {
                Path stored = Path.of(value);
                // Do not turn a changed/legacy alias into a new grant. Reissue such tokens instead.
                if (stored.isAbsolute() && stored.normalize().equals(stored) && Files.isDirectory(stored)
                        && stored.toRealPath().equals(stored)) canonical.add(stored);
            } catch (java.io.IOException | RuntimeException ignored) { /* An unavailable root never expands access. */ }
        }
        roots = List.copyOf(canonical);
    }
    public boolean restricted() { return !token.pathRoots().isEmpty(); }
    public boolean allowsPath(Path canonical) { return roots.stream().anyMatch(canonical::startsWith); }
    public boolean allows(ScanJob job) {
        if (job == null || !token.endpointIds().isEmpty() && (job.getEndpoint() == null
                || !token.endpointIds().contains(job.getEndpoint().getId()))) return false;
        return allows(job.getType(), job.getTarget(), job.getSubmittedBy(), job.getSubmittedTransport());
    }
    public boolean allows(ScanJobType type, String target, String actor, String transport) {
        if (!restricted()) return true;
        if (type == ScanJobType.UPLOAD)
            return token.getName().equals(actor) && "API_TOKEN".equals(transport);
        if (type != ScanJobType.PATH && type != ScanJobType.WATCH || target == null) return false;
        try {
            Path stored = Path.of(target);
            // Targets are the canonical paths recorded at submission, even after quarantine/deletion.
            return stored.isAbsolute() && stored.normalize().equals(stored) && stored.toString().equals(target)
                    && allowsPath(stored);
        } catch (InvalidPathException ex) { return false; }
    }

    /** Candidate filter only: DB collations can make LIKE/equality broader than filesystem path equality. */
    public Predicate candidates(From<?, ScanJob> job, CriteriaBuilder cb) {
        var predicates = new ArrayList<Predicate>();
        if (!token.endpointIds().isEmpty()) predicates.add(job.get("endpoint").get("id").in(token.endpointIds()));
        if (restricted()) {
            var paths = new ArrayList<Predicate>();
            for (Path root : roots) {
                String value = root.toString(), separator = root.getFileSystem().getSeparator();
                String prefix = value.endsWith(separator) ? value : value + separator;
                paths.add(cb.or(cb.equal(job.get("target"), value), cb.like(job.get("target"), escape(prefix) + "%", '!')));
            }
            Predicate pathJobs = cb.and(job.get("type").in(ScanJobType.PATH, ScanJobType.WATCH), cb.or(paths.toArray(Predicate[]::new)));
            Predicate ownUploads = cb.and(cb.equal(job.get("type"), ScanJobType.UPLOAD),
                    cb.equal(job.get("submittedBy"), token.getName()), cb.equal(job.get("submittedTransport"), "API_TOKEN"));
            predicates.add(cb.or(pathJobs, ownUploads));
        }
        return cb.and(predicates.toArray(Predicate[]::new));
    }
    private static String escape(String text) { return text.replace("!", "!!").replace("%", "!%").replace("_", "!_"); }
}
