package info.trizub.clamav.webclient.api.v1;

import info.trizub.clamav.webclient.api.ApiEndpointRules;
import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.*;
import info.trizub.clamav.webclient.security.ApiV1Access;
import info.trizub.clamav.webclient.security.TokenJobScope;
import info.trizub.clamav.webclient.service.*;
import info.trizub.clamav.webclient.service.ClamdClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ApiV1Controller {
  private final EndpointService endpoints;
  private final ScanJobService jobs;
  private final ScanJobRepository jobRepo;
  private final AuditEventRepository auditRepo;
  private final AuditService audit;
  private final ApiV1Access access;
  private final DataSource database;
  private final ClamavClientProvider clients;
  private final ClamdTopService clamdTop;
  private final int maxPages;
  private final int dbMaxAgeHours;
  private final ZoneId databaseTimezone;

  public ApiV1Controller(
      EndpointService endpoints,
      ScanJobService jobs,
      ScanJobRepository jobRepo,
      AuditEventRepository auditRepo,
      AuditService audit,
      ApiV1Access access,
      DataSource database,
      ClamavClientProvider clients,
      ClamdTopService clamdTop,
      @Value("${api.v1.max-page-size:100}") int maxPages,
      @Value("${api.v1.clamd.max-database-age-hours:168}") int dbMaxAgeHours,
      @Value("${api.v1.clamd.database-timezone:UTC}") String databaseTimezone) {
    this.endpoints = endpoints;
    this.jobs = jobs;
    this.jobRepo = jobRepo;
    this.auditRepo = auditRepo;
    this.audit = audit;
    this.access = access;
    this.database = database;
    this.clients = clients;
    this.clamdTop = clamdTop;
    this.maxPages = Math.max(1, Math.min(500, maxPages));
    this.dbMaxAgeHours = Math.max(1, dbMaxAgeHours);
    this.databaseTimezone = ZoneId.of(databaseTimezone);
  }

  private PageRequest paging(int page, int size, String field) {
    if (page < 0 || page > 1000000 || size < 1 || size > maxPages)
      throw new IllegalArgumentException("Invalid pagination");
    return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, field));
  }

  private ClamdEndpoint endpoint(Long id, Authentication auth) {
    ClamdEndpoint ep = ApiEndpointRules.resolve(endpoints, id);
    access.endpoint(auth, ep);
    return ep;
  }

  private boolean databaseReady() {
    try (var connection = database.getConnection()) {
      return connection.isValid(2);
    } catch (Exception e) {
      return false;
    }
  }

  @GetMapping("/auth")
  public Map<String, Object> authentication(Authentication auth) {
    return Map.of(
        "authenticated",
        true,
        "principal",
        auth.getName(),
        "authentication",
        access.principal(auth) == null ? "user" : "token");
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    // Basic CLI writes use a normal session CSRF token; browser protections remain in force.
    return Map.of(
        "token",
        token.getToken(),
        "headerName",
        token.getHeaderName(),
        "parameterName",
        token.getParameterName());
  }

  @GetMapping("/health/live")
  public Map<String, Object> live(Authentication auth) {
    access.require(auth, "health:read");
    return Map.of("status", "UP", "component", "web", "at", Instant.now().toString());
  }

  @GetMapping("/health/ready")
  public ResponseEntity<Map<String, Object>> ready(Authentication auth) {
    access.require(auth, "health:read");
    boolean ready = databaseReady();
    return ResponseEntity.status(ready ? 200 : 503)
        .body(
            Map.of(
                "status",
                ready ? "UP" : "DOWN",
                "component",
                "database",
                "at",
                Instant.now().toString()));
  }

  public record ClamdHealth(
      Long endpointId,
      String endpoint,
      String connectivity,
      String version,
      String signatureDatabaseVersion,
      String signatureDatabaseTime,
      Instant signatureDatabaseTimestamp,
      String signatureDatabaseFreshness,
      Integer signatureAgeHours,
      Instant checkedAt) {}

  private ClamdHealth inspect(ClamdEndpoint ep) {
    Instant checked = Instant.now();
    try {
      ClamdClient client = clients.probeFor(ep);
      client.ping();
      String raw = client.version();
      String[] version = (raw == null ? "" : raw).split("/", 3);
      String signatureTime = version.length > 2 ? version[2].trim() : null;
      Integer ageHours = null;
      Instant databaseTimestamp = null;
      String freshness = "UNKNOWN";
      if (signatureTime != null && !signatureTime.isBlank()) {
        try {
          DateTimeFormatter format =
              DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US);
          LocalDateTime stamp = LocalDateTime.parse(signatureTime, format);
          Instant asInstant = stamp.atZone(databaseTimezone).toInstant();
          databaseTimestamp = asInstant;
          long age = Duration.between(asInstant, checked).toHours();
          if (age >= 0 && age < 999999) {
            ageHours = (int) age;
            freshness = age > dbMaxAgeHours ? "STALE" : "FRESH";
          }
        } catch (Exception ignored) {
          /* Report UNKNOWN, never guess freshness. */
        }
      }
      return new ClamdHealth(
          ep.getId(),
          ep.getName(),
          "UP",
          version[0].trim(),
          version.length > 1 ? version[1].trim() : null,
          signatureTime,
          databaseTimestamp,
          freshness,
          ageHours,
          checked);
    } catch (Exception e) {
      return new ClamdHealth(
          ep.getId(), ep.getName(), "DOWN", null, null, null, null, "UNKNOWN", null, checked);
    }
  }

  @GetMapping("/health/clamd")
  public ResponseEntity<ClamdHealth> clamd(
      @RequestParam(required = false) Long endpointId, Authentication auth) {
    access.require(auth, "health:read");
    ClamdHealth h = inspect(endpoint(endpointId, auth));
    // A browser VIEWER may inspect health, but must not learn internal endpoint names.
    if (!canSeeEndpointName(auth)) {
      h =
          new ClamdHealth(
              h.endpointId(),
              "Endpoint " + h.endpointId(),
              h.connectivity(),
              h.version(),
              h.signatureDatabaseVersion(),
              h.signatureDatabaseTime(),
              h.signatureDatabaseTimestamp(),
              h.signatureDatabaseFreshness(),
              h.signatureAgeHours(),
              h.checkedAt());
    }
    return ResponseEntity.status(
            "DOWN".equals(h.connectivity()) || "STALE".equals(h.signatureDatabaseFreshness())
                ? 503
                : 200)
        .body(h);
  }

  @GetMapping("/metrics/clamd")
  public Map<String, Object> metrics(
      @RequestParam(required = false) Long endpointId, Authentication auth) {
    access.require(auth, "health:read");
    ClamdEndpoint ep = endpoint(endpointId, auth);
    Map<String, Object> snapshot = clamdTop.snapshot(ep.getId());
    // Same allowlisted projection for every viewer-facing ClamD metric route.
    return ClamdMetricsView.from(snapshot, ep.getId());
  }

  @GetMapping("/endpoints")
  public List<ApiV1Dtos.Endpoint> endpointList(Authentication auth) {
    access.require(auth, "endpoints:read");
    return endpoints.all().stream()
        .filter(
            e -> {
              try {
                access.endpoint(auth, e);
                return true;
              } catch (org.springframework.security.access.AccessDeniedException
                  | IllegalArgumentException ex) {
                return false;
              }
            })
        .map(
            ep ->
                canSeeEndpointName(auth)
                    ? ApiV1Dtos.Endpoint.from(ep)
                    : new ApiV1Dtos.Endpoint(ep.getId(), "Endpoint " + ep.getId(), ep.isEnabled()))
        .toList();
  }

  private boolean canSeeEndpointName(Authentication auth) {
    return auth != null
        && (access.principal(auth) != null
            || auth.getAuthorities().stream()
                .anyMatch(
                    a ->
                        a.getAuthority().equals("ROLE_OPERATOR")
                            || a.getAuthority().equals("ROLE_ADMIN")));
  }

  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  @GetMapping("/jobs")
  public ApiV1Dtos.Page<ApiV1Dtos.Job> jobList(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size,
      @RequestParam(required = false) Long endpointId,
      @RequestParam(required = false) ScanJobStatus status,
      @RequestParam(required = false) ScanVerdict verdict,
      Authentication auth) {
    access.require(auth, "jobs:read");
    var token = access.principal(auth);
    if (endpointId != null) endpoint(endpointId, auth);
    Set<Long> allowed = token == null ? Set.of() : token.endpointIds();
    Specification<ScanJob> spec =
        (root, query, cb) -> {
          List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
          if (endpointId != null)
            predicates.add(cb.equal(root.get("endpoint").get("id"), endpointId));
          if (status != null) predicates.add(cb.equal(root.get("status"), status));
          if (verdict != null) predicates.add(cb.equal(root.get("verdict"), verdict));
          if (!allowed.isEmpty()) predicates.add(root.get("endpoint").get("id").in(allowed));
          return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    var pageable =
        paging(page, size, "submittedAt")
            .withSort(Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("id")));
    var result =
        token != null && !token.pathRoots().isEmpty()
            ? ScopedJobPages.jobs(jobRepo, spec, pageable, new TokenJobScope(token))
            : jobRepo.findFilteredSummaries(
                endpointId,
                status,
                verdict,
                !allowed.isEmpty(),
                allowed.isEmpty() ? List.of(-1L) : allowed,
                pageable);
    List<String> visibleIds = result.getContent().stream().map(ScanJobSummary::id).toList();
    Map<String, String> recordedQuarantinePaths = new HashMap<>();
    if (!visibleIds.isEmpty()) {
      for (ScanJobListQuarantine meta : jobRepo.findApiListQuarantine(visibleIds)) {
        recordedQuarantinePaths.put(meta.id(), meta.quarantinePath());
      }
    }
    List<String> diagnosticIds =
        result.getContent().stream()
            .filter(
                row ->
                    row.verdict() == ScanVerdict.VIRUS_FOUND || row.verdict() == ScanVerdict.ERROR)
            .map(ScanJobSummary::id)
            .toList();
    Map<String, ScanJobListDiagnostics> diagnostics = new HashMap<>();
    if (!diagnosticIds.isEmpty()) {
      for (ScanJobListDiagnostics detail :
          jobRepo.findApiListDiagnostics(
              diagnosticIds, List.of(ScanVerdict.VIRUS_FOUND, ScanVerdict.ERROR))) {
        diagnostics.put(detail.id(), detail);
      }
    }
    return ApiV1Dtos.Page.from(
        result,
        row ->
            ApiV1Dtos.Job.fromSummary(
                row, recordedQuarantinePaths.get(row.id()), diagnostics.get(row.id())));
  }

  private ScanJob job(String id, Authentication auth) {
    ScanJob j = jobs.getOrNull(id);
    if (j == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
    access.job(auth, j);
    return j;
  }

  @GetMapping("/jobs/{id}")
  public ApiV1Dtos.Job getJob(@PathVariable String id, Authentication auth) {
    access.require(auth, "jobs:read");
    return ApiV1Dtos.Job.from(job(id, auth));
  }

  @GetMapping("/jobs/{id}/progress")
  public Map<String, Object> progress(@PathVariable String id, Authentication auth) {
    access.require(auth, "jobs:read");
    ScanJob j = job(id, auth);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("jobId", j.getId());
    result.put("status", j.getStatus().name());
    result.put("completed", j.getStatus() == ScanJobStatus.FINISHED);
    result.put("verdict", j.getVerdict() == null ? null : j.getVerdict().name());
    result.put(
        "updatedAt",
        j.getFinishedAt() != null
            ? j.getFinishedAt()
            : j.getStartedAt() != null ? j.getStartedAt() : j.getSubmittedAt());
    return result;
  }

  @PostMapping(value = "/scans/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiV1Dtos.Submitted> upload(
      @RequestParam("files") List<MultipartFile> files,
      @RequestParam(required = false) Long endpointId,
      Authentication auth,
      HttpServletRequest req) {
    access.require(auth, "scan:upload");
    ClamdEndpoint ep = endpoint(endpointId, auth);
    ApiEndpointRules.requireEnabled(ep);
    try {
      var created =
          jobs.createUploadJobs(
              files,
              ep,
              auth.getName(),
              access.principal(auth) != null ? "API_TOKEN" : "API_SESSION");
      if (created.isEmpty()) throw new IllegalArgumentException("Empty upload");
      for (ScanJob j : created)
        audit.recordScan(auth, req, "SCAN_UPLOAD", "QUEUED", ep.getId(), j.getId());
      return ResponseEntity.status(202)
          .body(
              new ApiV1Dtos.Submitted(
                  created.size(), created.stream().map(ScanJob::getId).toList()));
    } catch (RuntimeException e) {
      audit.recordScan(auth, req, "SCAN_UPLOAD", "FAILED", ep.getId(), null);
      throw e;
    }
  }

  public record PathRequest(@NotBlank String path, Long endpointId) {}

  @PostMapping(value = "/scans/paths", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, String>> path(
      @Valid @RequestBody PathRequest body, Authentication auth, HttpServletRequest req) {
    access.require(auth, "scan:path");
    ClamdEndpoint ep = endpoint(body.endpointId(), auth);
    ApiEndpointRules.requireEnabled(ep);
    try {
      java.nio.file.Path canonical = access.path(auth, body.path());
      ScanJob j =
          canonical != null
              ? jobs.createPathJob(canonical, ep, auth.getName(), "API_TOKEN")
              : jobs.createPathJob(body.path(), ep, auth.getName(), "API_SESSION");
      audit.recordScan(auth, req, "SCAN_PATH", "QUEUED", ep.getId(), j.getId());
      return ResponseEntity.status(202).body(Map.of("jobId", j.getId()));
    } catch (RuntimeException e) {
      audit.recordScan(auth, req, "SCAN_PATH", "FAILED", ep.getId(), null);
      throw e;
    }
  }

  @GetMapping("/audit")
  public ApiV1Dtos.Page<ApiV1Dtos.Audit> audit(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size,
      Authentication auth) {
    access.require(auth, "audit:read");
    var token = access.principal(auth);
    if (token != null && !token.pathRoots().isEmpty()) {
      Specification<AuditEvent> allowed =
          (root, query, cb) ->
              token.endpointIds().isEmpty()
                  ? cb.conjunction()
                  : root.get("endpointId").in(token.endpointIds());
      return ApiV1Dtos.Page.from(
          ScopedJobPages.audit(
              auditRepo, jobRepo, allowed, paging(page, size, "at"), new TokenJobScope(token)),
          ApiV1Dtos.Audit::from);
    }
    if (token != null && !token.endpointIds().isEmpty()) {
      Specification<AuditEvent> allowed =
          (root, query, cb) -> root.get("endpointId").in(token.endpointIds());
      return ApiV1Dtos.Page.from(
          auditRepo.findAll(allowed, paging(page, size, "at")), ApiV1Dtos.Audit::from);
    }
    return ApiV1Dtos.Page.from(auditRepo.findAll(paging(page, size, "at")), ApiV1Dtos.Audit::from);
  }

  @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
  public Resource openapi(Authentication auth) {
    access.require(auth, "endpoints:read");
    return new ClassPathResource("openapi/clamav-v1.yaml");
  }
}
