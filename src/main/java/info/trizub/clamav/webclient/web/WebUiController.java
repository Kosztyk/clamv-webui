package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.Role;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.repo.AuditEventRepository;
import info.trizub.clamav.webclient.repo.AppUserRepository;
import info.trizub.clamav.webclient.repo.WatchedDirectoryRepository;
import info.trizub.clamav.webclient.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import xyz.capybara.clamav.ClamavClient;

import java.util.*;

@Controller
public class WebUiController {

    @ModelAttribute
    public void addCommonModelAttributes(Model model, Authentication authentication) {
        // Always provide non-null settings + dashboard flags so fragments cannot crash on nulls
        try {
            model.addAttribute("settings", settings.snapshot());
        } catch (Exception e) {
            model.addAttribute("settings", Collections.<String, String>emptyMap());
        }
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

    private final SettingsService settings;
    private final EndpointService endpoints;
    private final ScanJobService jobs;
    private final ClamavClientProvider clientProvider;
    private final AuditService audit;
    private final AuditEventRepository auditRepo;
    private final AppUserRepository userRepo;
    private final UserService userService;
    private final WatchedDirectoryRepository watchRepo;

    public WebUiController(SettingsService settings,
                           EndpointService endpoints,
                           ScanJobService jobs,
                           ClamavClientProvider clientProvider,
                           AuditService audit,
                           AuditEventRepository auditRepo,
                           AppUserRepository userRepo,
                           UserService userService,
                           WatchedDirectoryRepository watchRepo) {
        this.settings = settings;
        this.endpoints = endpoints;
        this.jobs = jobs;
        this.clientProvider = clientProvider;
        this.audit = audit;
        this.auditRepo = auditRepo;
        this.userRepo = userRepo;
        this.userService = userService;
        this.watchRepo = watchRepo;
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
    public String dashboard(Model model) {
        model.addAttribute("endpointsCount", endpoints.all().size());
        var latest = jobs.latest();
        model.addAttribute("jobs", latest);
        model.addAttribute("jobsCount", latest != null ? latest.size() : 0);
        var def = endpoints.defaultEndpointOrEnsure();
        model.addAttribute("defaultEndpointName", def != null ? def.getName() : "—");
        return PAGE_DASHBOARD;
    }

    @GetMapping({"/main"})
    public String main(@RequestParam(name="endpointId", required = false) Long endpointId,
                       Model model) {
        ClamdEndpoint ep = endpointId != null ? endpoints.get(endpointId) : endpoints.defaultEndpointOrEnsure();
        model.addAttribute("endpoint", ep);
        model.addAttribute("endpoints", endpoints.all());

        if (ep == null) {
            model.addAttribute("pingOk", false);
            model.addAttribute("error", "No endpoints configured. Ask an admin to create one (or restart to auto-create the default)." );
            return PAGE_MAIN;
        }

        try {
            ClamavClient c = clientProvider.clientFor(ep);
            c.ping();
            model.addAttribute("pingOk", true);
            model.addAttribute("version", c.version());
            model.addAttribute("stats", c.stats());
        } catch (Exception e) {
            model.addAttribute("pingOk", false);
            model.addAttribute("error", e.getMessage());
        }

        return PAGE_MAIN;
    }


    @GetMapping("/scan")
    public String scan(Model model,
                       @RequestParam(name="endpointId", required = false) Long endpointId) {
        model.addAttribute("endpoints", endpoints.all());
        model.addAttribute("endpointId", endpointId != null ? endpointId : Optional.ofNullable(endpoints.defaultEndpointOrEnsure()).map(ClamdEndpoint::getId).orElse(null));
        model.addAttribute("allowedRoots", settings.allowedRoots());
        model.addAttribute("uploadMaxBytes", settings.uploadMaxBytes());
        model.addAttribute("jobs", jobs.latest());
        return PAGE_SCAN;
    }

    @PostMapping("/scan/upload")
    public String scanUpload(@RequestParam("files") List<MultipartFile> files,
                             @RequestParam("endpointId") Long endpointId,
                             Authentication auth,
                             HttpServletRequest req,
                             Model model) {
        try {
            ClamdEndpoint ep = endpoints.get(endpointId);
            List<ScanJob> created = jobs.createUploadJobs(files, ep, auth.getName());
            audit.record(auth, req, "SCAN_UPLOAD", "files=" + created.size(), "SUCCESS", created.isEmpty() ? null : created.get(0).getId());
            return "redirect:/jobs";
        } catch (Exception e) {
            audit.record(auth, req, "SCAN_UPLOAD", e.getMessage(), "FAILED", null);
            model.addAttribute("errorMsg", e.getMessage());
            return scan(model, endpointId);
        }
    }

    @PostMapping("/scan/path")
    public String scanPath(@RequestParam("path") String path,
                           @RequestParam("endpointId") Long endpointId,
                           Authentication auth,
                           HttpServletRequest req,
                           Model model) {
        try {
            ClamdEndpoint ep = endpoints.get(endpointId);
            ScanJob job = jobs.createPathJob(path, ep, auth.getName());
            audit.record(auth, req, "SCAN_PATH", "path=" + path, "SUCCESS", job.getId());
            return "redirect:/scan";
        } catch (Exception e) {
            audit.record(auth, req, "SCAN_PATH", e.getMessage(), "FAILED", null);
            model.addAttribute("errorMsg", e.getMessage());
            return scan(model, endpointId);
        }
    }

    @GetMapping("/jobs")
    public String jobs(Model model) {
        model.addAttribute("jobs", jobs.latest());
        return PAGE_JOBS;
    }

    @GetMapping("/jobs/{id}")
    public String job(@PathVariable("id") String id, Model model) {
        var job = jobs.getOrNull(id);
        if (job == null) {
            model.addAttribute("errorMsg", "Job not found: " + id);
            // fall back to jobs list
            model.addAttribute("jobs", jobs.latest());
            return PAGE_JOBS;
        }
        model.addAttribute("job", job);
        return PAGE_JOB;
    }

    // --- Admin pages ---

    @GetMapping("/admin/settings")
    public String adminSettings(Model model) {
        model.addAttribute("settings", settings.snapshot());
        return PAGE_SETTINGS;
    }

    @PostMapping("/admin/settings")
    public String adminSettingsSave(@RequestParam Map<String,String> params,
                                    Authentication auth,
                                    HttpServletRequest req) {
        // Only accept known keys (others ignored)
        Map<String,String> allowed = new HashMap<>();
        for (String key : List.of(
                "app.allowedScanRoots",
                "app.upload.maxBytes",
                "app.concurrentScans",
                "app.storage.uploadDir",
                "app.storage.quarantineDir",
                "app.quarantine.enabled",
                "app.webhook.enabled",
                "app.webhook.url",
                "app.watch.enabled",
                "app.watch.pollSeconds"
        )) {
            if (params.containsKey(key)) allowed.put(key, params.get(key));
        }
        settings.updateFromMap(allowed);
        audit.record(auth, req, "ADMIN_SETTINGS_UPDATE", "keys=" + allowed.keySet(), "SUCCESS", null);
        return "redirect:/admin/settings";
    }

    @GetMapping("/admin/endpoints")
    public String adminEndpoints(Model model) {
        model.addAttribute("endpoints", endpoints.all());
        return PAGE_ENDPOINTS;
    }

    @PostMapping("/admin/endpoints/create")
    public String adminEndpointsCreate(@RequestParam String name,
                                       @RequestParam String host,
                                       @RequestParam int port,
                                       @RequestParam String platform,
                                       @RequestParam(defaultValue = "true") boolean enabled,
                                       Authentication auth,
                                       HttpServletRequest req) {
        endpoints.create(name, host, port, xyz.capybara.clamav.Platform.valueOf(platform), enabled);
        audit.record(auth, req, "ENDPOINT_CREATE", name + "@" + host + ":" + port, "SUCCESS", null);
        return "redirect:/admin/endpoints";
    }

    @PostMapping("/admin/endpoints/{id}/update")
    public String adminEndpointsUpdate(@PathVariable Long id,
                                       @RequestParam String name,
                                       @RequestParam String host,
                                       @RequestParam int port,
                                       @RequestParam String platform,
                                       @RequestParam(defaultValue = "true") boolean enabled,
                                       Authentication auth,
                                       HttpServletRequest req) {
        endpoints.update(id, name, host, port, xyz.capybara.clamav.Platform.valueOf(platform), enabled);
        audit.record(auth, req, "ENDPOINT_UPDATE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/endpoints";
    }

    @PostMapping("/admin/endpoints/{id}/delete")
    public String adminEndpointsDelete(@PathVariable Long id,
                                       Authentication auth,
                                       HttpServletRequest req) {
        endpoints.delete(id);
        audit.record(auth, req, "ENDPOINT_DELETE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/endpoints";
    }

    @GetMapping("/admin/users")
    public String adminUsers(Model model) {
        model.addAttribute("users", userRepo.findAll());
        return PAGE_USERS;
    }

    @PostMapping("/admin/users/create")
    public String adminUsersCreate(@RequestParam String username,
                                   @RequestParam String password,
                                   @RequestParam(defaultValue = "true") boolean enabled,
                                   @RequestParam(defaultValue = "VIEWER") String role,
                                   Authentication auth,
                                   HttpServletRequest req) {
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
        userService.createUser(username, password, roles, enabled);
        audit.record(auth, req, "USER_CREATE", username, "SUCCESS", null);
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/delete")
    public String adminUsersDelete(@PathVariable Long id,
                                   Authentication auth,
                                   HttpServletRequest req) {
        userService.deleteUser(id);
        audit.record(auth, req, "USER_DELETE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/reset")
    public String adminUsersReset(@PathVariable Long id,
                                  @RequestParam String newPassword,
                                  Authentication auth,
                                  HttpServletRequest req) {
        userService.resetPassword(id, newPassword);
        audit.record(auth, req, "USER_RESET_PASSWORD", "id=" + id, "SUCCESS", null);
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
    public String adminWatchCreate(@RequestParam String path,
                                   @RequestParam Long endpointId,
                                   Authentication auth,
                                   HttpServletRequest req) {
        var ep = endpoints.get(endpointId);
        watchRepo.save(new info.trizub.clamav.webclient.model.WatchedDirectory(path, ep));
        audit.record(auth, req, "WATCH_CREATE", path, "SUCCESS", null);
        return "redirect:/admin/watch";
    }

    @PostMapping("/admin/watch/{id}/toggle")
    public String adminWatchToggle(@PathVariable Long id,
                                   Authentication auth,
                                   HttpServletRequest req) {
        var wd = watchRepo.findById(id).orElseThrow();
        wd.setEnabled(!wd.isEnabled());
        watchRepo.save(wd);
        audit.record(auth, req, "WATCH_TOGGLE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/watch";
    }

    @PostMapping("/admin/watch/{id}/delete")
    public String adminWatchDelete(@PathVariable Long id,
                                   Authentication auth,
                                   HttpServletRequest req) {
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