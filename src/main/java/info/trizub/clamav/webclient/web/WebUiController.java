package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.config.WebhookNotificationProperties;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.Role;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.repo.AppUserRepository;
import info.trizub.clamav.webclient.repo.AuditEventRepository;
import info.trizub.clamav.webclient.repo.WatchedDirectoryRepository;
import info.trizub.clamav.webclient.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WebUiController {

  @ModelAttribute
  public void addCommonModelAttributes(Model model, Authentication authentication) {
    // Avoid duplicating the whole configuration Properties for every request.
    // The legacy settings page redirects to Configuration Center; other pages
    // consume only the explicit, non-sensitive model attributes they need.
    model.addAttribute(
        "canRefreshJobs",
        authentication != null
            && authentication.getAuthorities().stream()
                .anyMatch(
                    role ->
                        role.getAuthority().equals("ROLE_ADMIN")
                            || role.getAuthority().equals("ROLE_OPERATOR")));
    model.addAttribute("pingOk", Boolean.FALSE);
    model.addAttribute("versionOk", Boolean.FALSE);
    model.addAttribute("statsOk", Boolean.FALSE);
    model.addAttribute("reloadOk", Boolean.FALSE);

    if (authentication != null) {
      model.addAttribute("username", authentication.getName());
    }
  }

  private static final Logger log = LoggerFactory.getLogger(WebUiController.class);

  // Each tab is rendered as its own page template under templates/pages/.
  // This prevents duplicated/stacked sections across tabs.
  private static final String PAGE_DASHBOARD = "pages/dashboard";
  private static final String PAGE_MAIN = "pages/main";
  private static final String PAGE_SCAN = "pages/scan";
  private static final String PAGE_JOBS = "pages/jobs";
  private static final String PAGE_JOB = "pages/job";
  private static final String PAGE_SETTINGS = "pages/settings";
  private static final String PAGE_ENDPOINTS = "pages/endpoints";
  private static final String PAGE_USERS = "pages/users";
  private static final String PAGE_WATCH = "pages/watch";
  private static final String PAGE_AUDIT = "pages/audit";

  // NOTE: We no longer rely on an "action" model attribute for rendering.

  private final ClamdTopService clamdTop;
  private final SettingsService settings;
  private final EndpointService endpoints;
  private final ScanJobService jobs;
  private final ClamavClientProvider clientProvider;
  private final AuditService audit;
  private final AuditEventRepository auditRepo;
  private final AppUserRepository userRepo;
  private final UserService userService;
  private final info.trizub.clamav.webclient.repo.ExternalIdentityRepository externalIdentities;
  private final WatchedDirectoryRepository watchRepo;
  private final WebhookAddressPolicy webhookPolicy;
  private final WebhookNotificationProperties webhookProperties;

  public WebUiController(
      ClamdTopService clamdTop,
      SettingsService settings,
      EndpointService endpoints,
      ScanJobService jobs,
      ClamavClientProvider clientProvider,
      AuditService audit,
      AuditEventRepository auditRepo,
      AppUserRepository userRepo,
      UserService userService,
      info.trizub.clamav.webclient.repo.ExternalIdentityRepository externalIdentities,
      WatchedDirectoryRepository watchRepo,
      WebhookAddressPolicy webhookPolicy,
      WebhookNotificationProperties webhookProperties) {
    this.clamdTop = clamdTop;
    this.settings = settings;
    this.endpoints = endpoints;
    this.jobs = jobs;
    this.clientProvider = clientProvider;
    this.audit = audit;
    this.auditRepo = auditRepo;
    this.userRepo = userRepo;
    this.userService = userService;
    this.externalIdentities = externalIdentities;
    this.watchRepo = watchRepo;
    this.webhookPolicy = webhookPolicy;
    this.webhookProperties = webhookProperties;
  }

  @GetMapping("/login")
  public String login() {
    return "login";
  }

  @GetMapping(value = {"/"})
  public String root() {
    return "redirect:/dashboard";
  }

  @GetMapping("/dashboard")
  public String dashboard(Model model, Authentication auth) {
    model.addAttribute("endpointsCount", endpoints.all().size());
    // Dashboard is VIEWER accessible; never render another user's scan targets.
    var latest = jobs.latest();
    model.addAttribute("jobs", privileged(auth) ? latest : java.util.List.<ScanJob>of());
    model.addAttribute("jobsPage", 0);
    model.addAttribute("jobsCount", latest.size());
    var def = endpoints.defaultEndpointOrEnsure();
    model.addAttribute(
        "defaultEndpointName",
        def == null ? "—" : privileged(auth) ? def.getName() : "Endpoint " + def.getId());
    return PAGE_DASHBOARD;
  }

  private static boolean privileged(Authentication auth) {
    return auth != null
        && auth.getAuthorities().stream()
            .anyMatch(
                granted ->
                    granted.getAuthority().equals("ROLE_OPERATOR")
                        || granted.getAuthority().equals("ROLE_ADMIN"));
  }

  private List<?> monitoringEndpoints(Authentication auth) {
    if (privileged(auth)) return endpoints.all();
    return endpoints.all().stream()
        .map(ep -> Map.of("id", ep.getId(), "name", "Endpoint " + ep.getId()))
        .toList();
  }

  @GetMapping("/clamdtop")
  public String clamdtop(
      @RequestParam(name = "endpointId", required = false) Long endpointId,
      Model model,
      Authentication auth) {
    model.addAttribute("endpoints", monitoringEndpoints(auth));
    model.addAttribute(
        "endpointId",
        endpointId != null
            ? endpointId
            : Optional.ofNullable(endpoints.defaultEndpointOrEnsure())
                .map(ClamdEndpoint::getId)
                .orElse(null));
    return privileged(auth) ? "pages/clamdtop" : "pages/clamdtop-viewer";
  }

  @GetMapping({"/main"})
  public String main(
      @RequestParam(name = "endpointId", required = false) Long endpointId,
      Model model,
      Authentication auth) {
    ClamdEndpoint ep =
        endpointId != null ? endpoints.get(endpointId) : endpoints.defaultEndpointOrEnsure();
    model.addAttribute("endpoint", ep);
    model.addAttribute("endpoints", monitoringEndpoints(auth));

    if (ep == null) {
      model.addAttribute("pingOk", false);
      model.addAttribute(
          "error",
          "No endpoints configured. Ask an admin to create one (or restart to auto-create the default).");
      return PAGE_MAIN;
    }

    if (!privileged(auth)) {
      try {
        Map<String, Object> view = ClamdMetricsView.from(clamdTop.snapshot(ep.getId()), ep.getId());
        model.addAttribute("pingOk", Boolean.TRUE.equals(view.get("ok")));
        model.addAttribute("stats", ClamdMetricsView.summary(view));
        if (!Boolean.TRUE.equals(view.get("ok")))
          model.addAttribute("error", "ClamD health information is unavailable.");
      } catch (Exception e) {
        log.warn("ClamD health view failed for endpoint {}", ep.getId(), e);
        model.addAttribute("pingOk", false);
        model.addAttribute("error", "ClamD health information is unavailable.");
      }
      return PAGE_MAIN;
    }
    try {
      ClamdClient c = clientProvider.probeFor(ep);
      c.ping();
      model.addAttribute("pingOk", true);
      model.addAttribute("version", c.version());
      model.addAttribute("stats", c.stats());
    } catch (Exception e) {
      log.warn("ClamD health probe failed for endpoint {}", ep.getId(), e);
      model.addAttribute("pingOk", false);
      model.addAttribute("error", "ClamD health information is unavailable. Check server logs.");
    }

    return PAGE_MAIN;
  }

  @GetMapping("/scan")
  public String scan(
      Model model, @RequestParam(name = "endpointId", required = false) Long endpointId) {
    model.addAttribute("endpoints", endpoints.all());
    model.addAttribute(
        "endpointId",
        endpointId != null
            ? endpointId
            : Optional.ofNullable(endpoints.defaultEndpointOrEnsure())
                .map(ClamdEndpoint::getId)
                .orElse(null));
    model.addAttribute("allowedRoots", settings.allowedRoots());
    Map<Long, String> uploadLimits = new LinkedHashMap<>();
    for (ClamdEndpoint ep : endpoints.all()) {
      try {
        uploadLimits.put(ep.getId(), String.valueOf(jobs.uploadMaxBytes(ep)));
      } catch (IllegalArgumentException ex) {
        uploadLimits.put(ep.getId(), "Unavailable");
      }
    }
    model.addAttribute("uploadLimits", uploadLimits);
    model.addAttribute("jobs", jobs.latest());
    model.addAttribute("jobsPage", 0);
    return PAGE_SCAN;
  }

  @PostMapping("/scan/upload")
  public String scanUpload(
      @RequestParam("files") List<MultipartFile> files,
      @RequestParam("endpointId") Long endpointId,
      Authentication auth,
      HttpServletRequest req,
      Model model) {
    try {
      ClamdEndpoint ep = endpoints.get(endpointId);
      List<ScanJob> created = jobs.createUploadJobs(files, ep, auth.getName(), "BROWSER");
      for (ScanJob job : created)
        audit.recordScan(auth, req, "SCAN_UPLOAD", "QUEUED", ep.getId(), job.getId());
      return "redirect:/jobs";
    } catch (Exception e) {
      audit.recordScan(auth, req, "SCAN_UPLOAD", "FAILED", endpointId, null);
      model.addAttribute("errorMsg", e.getMessage());
      return scan(model, endpointId);
    }
  }

  @PostMapping("/scan/path")
  public String scanPath(
      @RequestParam("path") String path,
      @RequestParam("endpointId") Long endpointId,
      Authentication auth,
      HttpServletRequest req,
      Model model) {
    try {
      ClamdEndpoint ep = endpoints.get(endpointId);
      ScanJob job = jobs.createPathJob(path, ep, auth.getName(), "BROWSER");
      audit.recordScan(auth, req, "SCAN_PATH", "QUEUED", ep.getId(), job.getId());
      return "redirect:/scan";
    } catch (Exception e) {
      audit.recordScan(auth, req, "SCAN_PATH", "FAILED", endpointId, null);
      model.addAttribute("errorMsg", e.getMessage());
      return scan(model, endpointId);
    }
  }

  private void addJobsPage(Model model, int page, int size) {
    var result = jobs.page(page, size);
    model.addAttribute("jobs", result.getContent());
    model.addAttribute("jobPage", result);
    model.addAttribute("jobsPage", result.getNumber());
  }

  @GetMapping("/jobs")
  public String jobs(
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "25") int size,
      Model model,
      jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    addJobsPage(model, page, size);
    return PAGE_JOBS;
  }

  @GetMapping(value = "/jobs/table", produces = org.springframework.http.MediaType.TEXT_HTML_VALUE)
  public String jobsTable(
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "25") int size,
      Model model,
      jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    addJobsPage(model, page, size);
    return "fragments/tables :: jobsTable";
  }

  @GetMapping("/jobs/{id}")
  public String job(@PathVariable("id") String id, Model model) {
    var job = jobs.getOrNull(id);
    if (job == null) {
      model.addAttribute("errorMsg", "Job not found: " + id);
      addJobsPage(model, 0, 25);
      return PAGE_JOBS;
    }
    model.addAttribute("job", job);
    return PAGE_JOB;
  }

  // --- Admin pages ---

  // Old bookmarks stay useful; all application configuration writes require
  // Configuration Center's shared validation, review and verification.
  @GetMapping("/admin/settings")
  public String adminSettings() {
    return "redirect:/admin/configuration-center?category=scanning";
  }

  @PostMapping("/admin/settings")
  @ResponseBody
  public org.springframework.http.ResponseEntity<?> adminSettingsSave(HttpServletRequest request) {
    return ConfigurationNavigation.moved(request, "scanning");
  }

  @GetMapping("/admin/endpoints")
  public String adminEndpoints(Model model) {
    model.addAttribute("endpoints", endpoints.all());
    model.addAttribute("defaultEndpointId", endpoints.defaultEndpointOrEnsure().getId());
    return PAGE_ENDPOINTS;
  }

  @PostMapping("/admin/endpoints/create")
  public String adminEndpointsCreate(
      @RequestParam(required = false) String name,
      @RequestParam(required = false) String host,
      @RequestParam(required = false) String port,
      @RequestParam(required = false) String platform,
      @RequestParam(defaultValue = "true") boolean enabled,
      Authentication auth,
      HttpServletRequest req,
      RedirectAttributes redirect) {
    try {
      int number = EndpointInputValidator.port(port);
      var supported = EndpointInputValidator.platform(platform);
      EndpointInputValidator.validate(name, host, number, supported);
      var primary = endpoints.defaultEndpointOrEnsure();
      if ("default".equalsIgnoreCase(name) || primary.getName().equalsIgnoreCase(name))
        throw new IllegalArgumentException(
            "The default engine is configured in Configuration Center");
      endpoints.create(name, host, number, supported, enabled);
      audit.record(auth, req, "ENDPOINT_CREATE", name + "@" + host + ":" + number, "SUCCESS", null);
    } catch (IllegalArgumentException | org.springframework.dao.DataIntegrityViolationException e) {
      redirect.addFlashAttribute(
          "errorMsg",
          e instanceof IllegalArgumentException
              ? e.getMessage()
              : "Endpoint name is already in use");
    }
    return "redirect:/admin/endpoints";
  }

  @PostMapping("/admin/endpoints/{id}/update")
  public String adminEndpointsUpdate(
      @PathVariable Long id,
      @RequestParam(required = false) String name,
      @RequestParam(required = false) String host,
      @RequestParam(required = false) String port,
      @RequestParam(required = false) String platform,
      @RequestParam(defaultValue = "true") boolean enabled,
      Authentication auth,
      HttpServletRequest req,
      RedirectAttributes redirect) {
    if (endpoints.defaultEndpointOrEnsure().getId().equals(id))
      return "redirect:/admin/configuration-center?section=clamd";
    try {
      int number = EndpointInputValidator.port(port);
      var supported = EndpointInputValidator.platform(platform);
      EndpointInputValidator.validate(name, host, number, supported);
      if ("default".equalsIgnoreCase(name))
        throw new IllegalArgumentException(
            "The default engine name is reserved for Configuration Center");
      endpoints.update(id, name, host, number, supported, enabled);
      audit.record(auth, req, "ENDPOINT_UPDATE", "id=" + id, "SUCCESS", null);
    } catch (IllegalArgumentException | org.springframework.dao.DataIntegrityViolationException e) {
      redirect.addFlashAttribute(
          "errorMsg",
          e instanceof IllegalArgumentException
              ? e.getMessage()
              : "Endpoint name is already in use");
    }
    return "redirect:/admin/endpoints";
  }

  @PostMapping("/admin/endpoints/{id}/delete")
  public String adminEndpointsDelete(
      @PathVariable Long id, Authentication auth, HttpServletRequest req) {
    if (endpoints.defaultEndpointOrEnsure().getId().equals(id))
      return "redirect:/admin/configuration-center?section=clamd";
    endpoints.delete(id);
    audit.record(auth, req, "ENDPOINT_DELETE", "id=" + id, "SUCCESS", null);
    return "redirect:/admin/endpoints";
  }

  @GetMapping("/admin/users")
  public String adminUsers(Model model) {
    model.addAttribute("users", userRepo.findAll());
    model.addAttribute("externalIdentities", externalIdentities.findAll());
    return PAGE_USERS;
  }

  @PostMapping("/admin/users/create")
  public String adminUsersCreate(
      @RequestParam(required = false) String username,
      @RequestParam(required = false) String password,
      @RequestParam(defaultValue = "true") boolean enabled,
      @RequestParam(defaultValue = "VIEWER") String role,
      Authentication auth,
      HttpServletRequest req,
      RedirectAttributes redirect) {
    try {
      if (role == null
          || !Set.of("VIEWER", "OPERATOR", "ADMIN").contains(role.toUpperCase(Locale.ROOT)))
        throw new IllegalArgumentException("Select a valid user role");
      Set<String> roles = new HashSet<>();
      // role parameter represents minimum role
      if ("ADMIN".equalsIgnoreCase(role)) {
        roles.add(Role.ADMIN.asAuthority());
        roles.add(Role.OPERATOR.asAuthority());
        roles.add(Role.VIEWER.asAuthority());
      } else if ("OPERATOR".equalsIgnoreCase(role)) {
        roles.add(Role.OPERATOR.asAuthority());
        roles.add(Role.VIEWER.asAuthority());
      } else {
        roles.add(Role.VIEWER.asAuthority());
      }
      var created = userService.createUser(username, password, roles, enabled);
      audit.record(auth, req, "USER_CREATE", created.getUsername(), "SUCCESS", null);
    } catch (IllegalArgumentException | org.springframework.dao.DataIntegrityViolationException e) {
      redirect.addFlashAttribute(
          "errorMsg",
          e instanceof IllegalArgumentException ? e.getMessage() : "Username is already in use");
    }
    return "redirect:/admin/users";
  }

  @PostMapping("/admin/users/{id}/delete")
  public String adminUsersDelete(
      @PathVariable Long id,
      Authentication auth,
      HttpServletRequest req,
      org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
    try {
      userService.deleteUser(id);
      audit.record(auth, req, "USER_DELETE", "id=" + id, "SUCCESS", null);
    } catch (IllegalArgumentException e) {
      audit.record(auth, req, "USER_DELETE", "id=" + id, "FAILED", null);
      flash.addFlashAttribute("errorMsg", e.getMessage());
    }
    return "redirect:/admin/users";
  }

  @PostMapping("/admin/users/{id}/reset")
  public String adminUsersReset(
      @PathVariable Long id,
      @RequestParam String newPassword,
      Authentication auth,
      HttpServletRequest req,
      org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
    try {
      userService.resetPassword(id, newPassword);
      audit.record(auth, req, "USER_RESET_PASSWORD", "id=" + id, "SUCCESS", null);
    } catch (IllegalArgumentException e) {
      audit.record(auth, req, "USER_RESET_PASSWORD", "id=" + id, "FAILED", null);
      flash.addFlashAttribute("errorMsg", e.getMessage());
    }
    return "redirect:/admin/users";
  }

  @GetMapping("/admin/watch")
  public String adminWatch(Model model) {
    model.addAttribute("watchEnabled", settings.watchEnabled());
    model.addAttribute("watchDirs", watchRepo.findAll());
    model.addAttribute("endpoints", endpoints.all());
    model.addAttribute("allowedRoots", settings.allowedRoots());
    return PAGE_WATCH;
  }

  @PostMapping("/admin/watch/create")
  public String adminWatchCreate(
      @RequestParam String path,
      @RequestParam Long endpointId,
      Authentication auth,
      HttpServletRequest req) {
    var ep = endpoints.get(endpointId);
    var allowed =
        info.trizub.clamav.webclient.util.PathPolicy.requireAllowed(
            info.trizub.clamav.webclient.util.PathPolicy.normalize(path), settings.allowedRoots());
    if (!java.nio.file.Files.isDirectory(allowed))
      throw new IllegalArgumentException("Watch path must be a directory");
    watchRepo.save(new info.trizub.clamav.webclient.model.WatchedDirectory(allowed.toString(), ep));
    audit.record(auth, req, "WATCH_CREATE", path, "SUCCESS", null);
    return "redirect:/admin/watch";
  }

  @PostMapping("/admin/watch/{id}/toggle")
  public String adminWatchToggle(
      @PathVariable Long id, Authentication auth, HttpServletRequest req) {
    var wd = watchRepo.findById(id).orElseThrow();
    wd.setEnabled(!wd.isEnabled());
    watchRepo.save(wd);
    audit.record(auth, req, "WATCH_TOGGLE", "id=" + id, "SUCCESS", null);
    return "redirect:/admin/watch";
  }

  @PostMapping("/admin/watch/{id}/delete")
  public String adminWatchDelete(
      @PathVariable Long id, Authentication auth, HttpServletRequest req) {
    watchRepo.deleteById(id);
    audit.record(auth, req, "WATCH_DELETE", "id=" + id, "SUCCESS", null);
    return "redirect:/admin/watch";
  }

  @GetMapping("/admin/audit")
  public String adminAudit(Model model) {
    model.addAttribute("auditEvents", auditRepo.findTop200ByOrderByAtDesc());
    return PAGE_AUDIT;
  }
}
