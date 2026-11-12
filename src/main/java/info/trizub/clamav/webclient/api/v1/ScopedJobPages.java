package info.trizub.clamav.webclient.api.v1;

import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.*;
import info.trizub.clamav.webclient.security.TokenJobScope;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Bounded metadata batches prevent collation/legacy-path overmatches from leaking rows or page totals. */
final class ScopedJobPages {
    private static final int BATCH = 500;
    public record JobRow(String id, ScanJobType type, String target, String submittedBy, String submittedTransport, Instant submittedAt) {
        boolean allowed(TokenJobScope scope) { return scope.allows(type, target, submittedBy, submittedTransport); }
    }
    public record AuditRow(Long id, Instant at, String jobId) { }
    private static Expression<Instant> time(CriteriaBuilder cb, Root<?> root, String field) {
        return cb.<Instant>coalesce().value(root.get(field)).value(Instant.EPOCH);
    }
    private static Instant stamp(Instant value) { return value == null ? Instant.EPOCH : value; }

    static Page<ScanJob> jobs(ScanJobRepository repo, Specification<ScanJob> filters, Pageable page, TokenJobScope scope) {
        var selected = new ArrayList<String>();
        long total = 0;
        JobRow after = null;
        while (true) {
            JobRow cursor = after;
            Specification<ScanJob> batch = (root, query, cb) -> {
                var date = time(cb, root, "submittedAt");
                query.orderBy(cb.desc(date), cb.desc(root.get("id")));
                var allowed = cb.and(filters.toPredicate(root, query, cb), scope.candidates(root, cb));
                return cursor == null ? allowed : cb.and(allowed, cb.or(cb.lessThan(date, stamp(cursor.submittedAt())),
                        cb.and(cb.equal(date, stamp(cursor.submittedAt())), cb.lessThan(root.get("id"), cursor.id()))));
            };
            List<JobRow> rows = repo.findBy(batch, query -> query.as(JobRow.class).limit(BATCH).all());
            for (JobRow row : rows) if (row.allowed(scope)) {
                if (total >= page.getOffset() && selected.size() < page.getPageSize()) selected.add(row.id());
                total++;
            }
            if (rows.size() < BATCH) break;
            after = rows.get(rows.size() - 1);
        }
        return new PageImpl<>(ordered(repo.findAllById(selected), selected, ScanJob::getId).stream().filter(scope::allows).toList(), page, total);
    }

    static Page<AuditEvent> audit(AuditEventRepository audits, ScanJobRepository jobs,
                                 Specification<AuditEvent> filters, Pageable page, TokenJobScope scope) {
        var selected = new ArrayList<Long>();
        long total = 0;
        AuditRow after = null;
        while (true) {
            AuditRow cursor = after;
            Specification<AuditEvent> batch = (root, query, cb) -> {
                var date = time(cb, root, "at");
                query.orderBy(cb.desc(date), cb.desc(root.get("id")));
                var linked = query.subquery(String.class);
                var job = linked.from(ScanJob.class);
                linked.select(job.get("id")).where(cb.equal(job.get("id"), root.get("jobId")), scope.candidates(job, cb));
                var allowed = cb.and(filters.toPredicate(root, query, cb), cb.exists(linked));
                return cursor == null ? allowed : cb.and(allowed, cb.or(cb.lessThan(date, stamp(cursor.at())),
                        cb.and(cb.equal(date, stamp(cursor.at())), cb.lessThan(root.get("id"), cursor.id()))));
            };
            List<AuditRow> rows = audits.findBy(batch, query -> query.as(AuditRow.class).limit(BATCH).all());
            Set<String> ids = new HashSet<>();
            for (AuditRow row : rows) if (row.jobId() != null) ids.add(row.jobId());
            Set<String> visible = new HashSet<>();
            if (!ids.isEmpty()) {
                Specification<ScanJob> linked = (root, query, cb) -> cb.and(root.get("id").in(ids), scope.candidates(root, cb));
                jobs.findBy(linked, query -> query.as(JobRow.class).limit(BATCH).all()).stream()
                        .filter(row -> row.allowed(scope)).forEach(row -> visible.add(row.id()));
            }
            for (AuditRow row : rows) if (visible.contains(row.jobId())) {
                if (total >= page.getOffset() && selected.size() < page.getPageSize()) selected.add(row.id());
                total++;
            }
            if (rows.size() < BATCH) break;
            after = rows.get(rows.size() - 1);
        }
        return new PageImpl<>(ordered(audits.findAllById(selected), selected, AuditEvent::getId), page, total);
    }

    private static <K,T> List<T> ordered(List<T> values, List<K> ids, Function<T,K> key) {
        Map<K,T> indexed = new HashMap<>();
        for (T value : values) indexed.put(key.apply(value), value);
        return ids.stream().map(indexed::get).filter(Objects::nonNull).toList();
    }
}
