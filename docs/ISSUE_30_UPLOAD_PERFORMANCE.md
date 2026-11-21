# Issue #30 — Multipart upload performance and early 413 admission

## Scope and behavior

Earlier beta2 already hashed file contents **during** the destination copy (one read and one write for the application copy), so the original issue's separate *third hash read* no longer applied to non-servlet uploads. The new optimization targets the remaining duplicate disk write from Tomcat's temporary spool into private upload storage.

**Servlet multipart fast path:** when Spring exposes the standard servlet-backed multipart implementation, `ScanJobService` calls the **`File` overload** of `MultipartFile.transferTo`. Tomcat can rename its on-disk part into the destination when source and destination share a filesystem. The application then reads the final file once to calculate SHA-256. This is typically one spool write plus one hash read, rather than spool write + copied write + read. A servlet container may still copy instead of renaming; this is an optimization, not a fixed I/O guarantee on all filesystems. `MultipartFile.transferTo(Path)` is deliberately **not** used because its default implementation copies.

**Other MultipartFile implementations:** retain the previously verified single-pass digest-during-copy behavior, with a private destination created before writing. Both code paths call `DaemonScanPolicy.requireUpload` before materialization and again using measured bytes, keep the `0700` directory and `0600` file mode, avoid database transactions around I/O, and preserve batch rollback plus upload retention. The new `Batch.reserve` allocates a collision-resistant name while tracking the file for cleanup without creating it before a possible atomic rename.

**Early 413:** a Spring Security filter runs **before CSRF form-parameter lookup and before DispatcherServlet multipart parsing**. This ordering is necessary because retrieving `_csrf` from a multipart form can trigger Tomcat spooling. The endpoint-specific size check is limited to already-authenticated session operators or administrators; unauthenticated requests and machine-token/Basic requests still get the global 413 preflight, and all requests retain full per-file enforcement. It rejects requests whose declared `Content-Length` is larger than the configured `spring.servlet.multipart.max-request-size`. The first-party browser upload form additionally places `uploadPreflightEndpointId` and selected `uploadFileCount` in the query string at form submit, letting the filter calculate a conservative multipart envelope limit from `DaemonScanPolicy.uploadMaxBytes` for the selected endpoint. A 64 KiB allowance per file and one extra 64 KiB avoids most false rejections due to form fields and boundaries. The per-file policy is always enforced again after parsing. Old API clients without these hints remain supported but can only receive the earlier global early check; they still receive per-file rejection after multipart parsing. For programmatic uploads, add `?uploadPreflightEndpointId=7&uploadFileCount=1` when that information is known *before* sending the request body. These fields are optimization hints, not trusted limits or authorization tokens. The endpoint id in the multipart form remains validated by the controller. Malformed or missing hints are ignored, not treated as authorization.

**Limitations:** HTTP requests without `Content-Length` (chunked uploads) cannot use this header-only early check and remain subject to Tomcat's multipart request limits and the application's per-file validation. The body length includes multipart overhead, so an exact file-size estimate from it is not possible. Reverse proxies may have independent body-size limits. No guarantee is claimed that multi-file uploads with deceptive metadata are stopped before parsing; file-size enforcement remains on the server. The declared `Content-Length` is used only for preflight rejection; per-file limits never depend on it.

## Docker deployment

The Docker image supplies `CLAMAV_CONTAINER_MULTIPART_DEFAULT=/app/data/tmp/multipart` as a fallback. Its owner-only entrypoint creates that path with mode `0700` as UID 10001; Configuration Center can override the corresponding Spring property after a restart without a Docker-environment lock. See [the Configuration Center instructions](ISSUE_30_MULTIPART_CONFIGURATION_CENTER.md). The existing `./data:/app/data` bind-mount preserves the temporary and final upload files on the same filesystem without making the rest of `data/` available to clamd. This does not weaken `0600` uploads or introduce an exposed ClamAV port. The temp spool remains private, outside the ClamAV container's `data/uploads` read-only mount.

Example application service:

```yaml
services:
  clamav-web-client:
    build: .
    user: "10001:10001"
    environment:
      # Optional deployment override; omit this line to manage the spool via Configuration Center.
      SPRING_SERVLET_MULTIPART_LOCATION: "/app/data/tmp/multipart"
    volumes:
      - ./data:/app/data
      - ./conf:/app/conf
```

Ensure the host `./data` bind mount is writable by numeric UID 10001 (for example, the owner on the host is 10001). The image's startup script needs write permission and fails rather than relaxing a world-writable directory. For plain `java -jar` execution without Docker, no multipart location is forced; you can set `SPRING_SERVLET_MULTIPART_LOCATION` to an absolute path on the same volume as `app.storage.uploadDir` and create it with owner-only permissions.

To confirm whether the move is happening, benchmark representative 1–2 GiB uploads on your actual storage using `iostat`, mount information, and a matching servlet container. File moves can be optimized only on compatible volumes; performance results must be validated for that deployment.

## Verification

1. Compile and run `UploadRequestLimitsTest`, `UploadAdmissionFilterTest`, and `UploadBatchPersistenceTest`.
2. Run `mvn -B verify` and run the container with `docker compose up -d --build`.
3. Send an oversized **authenticated** single-file upload with query hints and `Content-Length`; verify HTTP 413 without filesystem spool creation or job insertion.
4. Test the same upload with no hints and chunked transfer to confirm the existing multipart and per-file limits still protect the request.
5. Upload ordinary and EICAR samples, inspect successful SHA-256, job submission, and deletion of clean/infected uploads after scanning.
6. Confirm the source and spool directories are owner-only, no unintended symlinks or public clamd 3310 port, and all prior issues #13–#29 still pass.

**Validation caveat:** The source archive is prepared for testing, but full Maven/HTTP/Tomcat tests and disk-I/O benchmarks were not run in this offline environment.


**Cumulative audit:** `POST /rest-monitor/upload` now receives early global `Content-Length` rejection with JSON HTTP 413. The file ceiling defaults to `2047MB` and request ceiling to `2048MB`, reserving 1MiB for multipart framing; Configuration Center rejects newly saved combinations leaving less than 64KiB. Chunked uploads remain subject to Tomcat/post-parse limits. See [audit remediation](CUMULATIVE_AUDIT_REMEDIATION_13-35.md).
