package info.trizub.clamav.webclient.api;

import info.trizub.clamav.webclient.api.v1.ApiV1Dtos;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.service.AuditService;
import info.trizub.clamav.webclient.service.ClamdMetricsView;
import info.trizub.clamav.webclient.service.ClamdTopService;
import info.trizub.clamav.webclient.service.EndpointService;
import info.trizub.clamav.webclient.service.ScanJobService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Compatibility routes retained for existing clients; new integrations must use /api/v1. Planned
 * retirement: 2027-12-31 (subject to a major-version release).
 */
@Deprecated(since = "2.1.0-beta2", forRemoval = true)
@RestController
@RequestMapping("/api")
public class ApiController {

  private final AuditService audit;
  private final ClamdTopService clamdTop;
  private final EndpointService endpoints;
  private final ScanJobService jobs;

  public ApiController(
      EndpointService endpoints,
      ScanJobService jobs,
      ClamdTopService clamdTop,
      AuditService audit) {
    this.audit = audit;
    this.clamdTop = clamdTop;
    this.endpoints = endpoints;
    this.jobs = jobs;
  }

  @GetMapping("/clamdtop")
  public Map<String, Object> clamdtop(
      @RequestParam(name = "endpointId", required = false) Long endpointId, Authentication auth) {
    // Endpoint lookup failures are API errors, not ClamD connectivity failures.
    ClamdEndpoint selected = ApiEndpointRules.resolve(endpoints, endpointId);
    Map<String, Object> snapshot;
    try {
      snapshot = clamdTop.snapshot(selected.getId());
    } catch (RuntimeException failure) {
      // In particular, never reflect endpoint lookup/connection exception messages.
      return Map.of("ok", false, "error", "ClamD health information is unavailable.");
    }
    if (isOperatorOrAdmin(auth)) return snapshot; // privileged diagnostics remain available
    return ClamdMetricsView.from(snapshot, selected.getId());
  }

  private static boolean isOperatorOrAdmin(Authentication auth) {
    return auth != null
        && auth.getAuthorities().stream()
            .anyMatch(
                granted ->
                    granted.getAuthority().equals("ROLE_OPERATOR")
                        || granted.getAuthority().equals("ROLE_ADMIN"));
  }

  @GetMapping("/endpoints")
  public List<Map<String, Object>> endpointList(Authentication auth) {
    return endpoints.all().stream()
        .map(
            ep ->
                Map.<String, Object>of(
                    "id",
                    ep.getId(),
                    "name",
                    isOperatorOrAdmin(auth) ? ep.getName() : "Endpoint " + ep.getId(),
                    "enabled",
                    ep.isEnabled()))
        .toList();
  }

  @GetMapping("/health")
  public Map<String, Object> health(Authentication auth) {
    return Map.of(
        "status",
        "ok",
        "endpoints",
        endpoints.all().stream()
            .map(ep -> isOperatorOrAdmin(auth) ? ep.getName() : "Endpoint " + ep.getId())
            .toList());
  }

  @GetMapping("/jobs")
  public List<ApiV1Dtos.Job> listJobs() {
    // List response preserves the legacy DTO shape without reading TEXT
    // diagnostics. Fetch /api/jobs/{id} for signatures and error details.
    return jobs.latestForLegacyApi().stream().map(ApiV1Dtos.Job::fromSummary).toList();
  }

  @GetMapping("/jobs/{id}")
  public ApiV1Dtos.Job getJob(@PathVariable String id) {
    ScanJob job = jobs.getOrNull(id);
    if (job == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
    return ApiV1Dtos.Job.from(job);
  }

  @PostMapping(value = "/scan/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> scanUpload(
      @RequestParam("files") List<MultipartFile> files,
      @RequestParam("endpointId") Long endpointId,
      Authentication auth,
      HttpServletRequest httpRequest) {
    Long selectedEndpointId = null;
    try {
      var ep = ApiEndpointRules.requireEnabled(ApiEndpointRules.resolve(endpoints, endpointId));
      selectedEndpointId = ep.getId();
      var created = jobs.createUploadJobs(files, ep, auth.getName(), "API_BASIC_OR_SESSION");
      if (created.isEmpty()) throw new IllegalArgumentException("Empty upload");
      for (ScanJob j : created)
        audit.recordScan(auth, httpRequest, "SCAN_UPLOAD", "QUEUED", ep.getId(), j.getId());
      return Map.of(
          "created", created.size(), "jobIds", created.stream().map(ScanJob::getId).toList());
    } catch (RuntimeException e) {
      // Use a fixed outcome, never an exception message or implementation class.
      audit.recordScan(auth, httpRequest, "SCAN_UPLOAD", "FAILED", selectedEndpointId, null);
      throw e;
    }
  }

  public static class PathScanRequest {
    @NotBlank public String path;
    public Long endpointId;
  }

  @PostMapping(value = "/scan/path", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> scanPath(
      @Valid @RequestBody PathScanRequest req,
      Authentication auth,
      HttpServletRequest httpRequest) {
    Long selectedEndpointId = null;
    try {
      var ep = ApiEndpointRules.requireEnabled(ApiEndpointRules.resolve(endpoints, req.endpointId));
      selectedEndpointId = ep.getId();
      var job = jobs.createPathJob(req.path, ep, auth.getName(), "API_BASIC_OR_SESSION");
      audit.recordScan(auth, httpRequest, "SCAN_PATH", "QUEUED", ep.getId(), job.getId());
      return Map.of("jobId", job.getId());
    } catch (RuntimeException e) {
      audit.recordScan(auth, httpRequest, "SCAN_PATH", "FAILED", selectedEndpointId, null);
      throw e;
    }
  }
}
