package info.trizub.clamav.webclient.api;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.api.v1.ApiV1Dtos;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.service.EndpointService;
import info.trizub.clamav.webclient.service.ScanJobService;
import info.trizub.clamav.webclient.service.ClamdTopService;
import info.trizub.clamav.webclient.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final AuditService audit;
    private final ClamdTopService clamdTop;
    private final EndpointService endpoints;
    private final ScanJobService jobs;

    public ApiController(EndpointService endpoints, ScanJobService jobs, ClamdTopService clamdTop, AuditService audit) {
        this.audit = audit;
        this.clamdTop = clamdTop;
        this.endpoints = endpoints;
        this.jobs = jobs;
    }

    @GetMapping("/clamdtop")
    public Map<String, Object> clamdtop(@RequestParam(name = "endpointId", required = false) Long endpointId) {
        return clamdTop.snapshot(endpointId);
    }

    @GetMapping("/endpoints")
    public List<Map<String, Object>> endpointList() {
        return endpoints.all().stream().map(ep -> Map.<String,Object>of(
            "id", ep.getId(), "name", ep.getName(), "enabled", ep.isEnabled()
        )).toList();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "endpoints", endpoints.all().stream().map(ClamdEndpoint::getName).toList()
        );
    }

    @GetMapping("/jobs")
    public List<ApiV1Dtos.Job> listJobs() {
        return jobs.latest().stream().map(ApiV1Dtos.Job::from).toList();
    }

    @GetMapping("/jobs/{id}")
    public ApiV1Dtos.Job getJob(@PathVariable String id) {
        ScanJob job = jobs.getOrNull(id);
        if (job == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "job not found"
            );
        }
        return ApiV1Dtos.Job.from(job);
    }

    @PostMapping(value = "/scan/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> scanUpload(@RequestParam("files") List<MultipartFile> files,
                                         @RequestParam("endpointId") Long endpointId,
                                         Authentication auth, HttpServletRequest httpRequest) {
        try {
            var ep = endpoints.get(endpointId);
            var created = jobs.createUploadJobs(files, ep, auth.getName(), "API_BASIC_OR_SESSION");
            for (ScanJob j : created) audit.recordScan(auth, httpRequest, "SCAN_UPLOAD", "QUEUED", ep.getId(), j.getId());
            return Map.of("created", created.size(), "jobIds", created.stream().map(ScanJob::getId).toList());
        } catch (RuntimeException e) {
            audit.record(auth, httpRequest, "SCAN_UPLOAD", e.getClass().getSimpleName(), "FAILED", null);
            throw e;
        }
    }

    public static class PathScanRequest {
        @NotBlank public String path;
        public Long endpointId;
    }

    @PostMapping(value = "/scan/path", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> scanPath(@RequestBody PathScanRequest req,
                                       Authentication auth, HttpServletRequest httpRequest) {
        try {
            var ep = req.endpointId != null ? endpoints.get(req.endpointId) : endpoints.defaultEndpoint();
            var job = jobs.createPathJob(req.path, ep, auth.getName(), "API_BASIC_OR_SESSION");
            audit.recordScan(auth, httpRequest, "SCAN_PATH", "QUEUED", ep.getId(), job.getId());
            return Map.of("jobId", job.getId());
        } catch (RuntimeException e) {
            audit.record(auth, httpRequest, "SCAN_PATH", e.getClass().getSimpleName(), "FAILED", null);
            throw e;
        }
    }
}
