package info.trizub.clamav.webclient.api;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.service.EndpointService;
import info.trizub.clamav.webclient.service.ScanJobService;
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

    private final EndpointService endpoints;
    private final ScanJobService jobs;

    public ApiController(EndpointService endpoints, ScanJobService jobs) {
        this.endpoints = endpoints;
        this.jobs = jobs;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "endpoints", endpoints.all().stream().map(ClamdEndpoint::getName).toList()
        );
    }

    @GetMapping("/jobs")
    public List<ScanJob> listJobs() {
        return jobs.latest();
    }

    @GetMapping("/jobs/{id}")
    public ScanJob getJob(@PathVariable String id) {
        ScanJob job = jobs.getOrNull(id);
        if (job == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "job not found"
            );
        }
        return job;
    }

    @PostMapping(value = "/scan/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> scanUpload(@RequestParam("files") List<MultipartFile> files,
                                         @RequestParam("endpointId") Long endpointId,
                                         Authentication auth) {
        var ep = endpoints.get(endpointId);
        var created = jobs.createUploadJobs(files, ep, auth.getName());
        return Map.of(
                "created", created.size(),
                "jobIds", created.stream().map(ScanJob::getId).toList()
        );
    }

    public static class PathScanRequest {
        @NotBlank public String path;
        public Long endpointId;
    }

    @PostMapping(value = "/scan/path", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> scanPath(@RequestBody PathScanRequest req,
                                       Authentication auth) {
        var ep = req.endpointId != null ? endpoints.get(req.endpointId) : endpoints.defaultEndpoint();
        var job = jobs.createPathJob(req.path, ep, auth.getName());
        return Map.of("jobId", job.getId());
    }
}
