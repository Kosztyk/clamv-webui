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

import java.util.*;
import java.net.URI;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WebUiController {

    @ModelAttribute
    public void addCommonModelAttributes(Model model, Authentication authentication) {
        // Never expose the credential-bearing webhook URL through shared models.
        // The admin settings page uses a write-only replacement field instead.
        try {
            Properties safeSettings = settings.snapshot();
            safeSettings.remove("app.webhook.url");
            model.addAttribute("settings", safeSettings);
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

    private final ClamdTopService clamdTop;
    private final SettingsService settings;
    private final EndpointService endpoints;
    private final ScanJobService jobs;
    private final ClamavClientProvider clientProvider;
    private final AuditService audit;
    private final AuditEventRepository auditRepo;
    private final AppUserRepository userRepo;
    private final UserService userService;
    private final WatchedDirectoryRepository watchRepo;

    public WebUiController(ClamdTopService clamdTop, SettingsService settings,
                           EndpointService endpoints,
                           ScanJobService jobs,
                           ClamavClientProvider clientProvider,
                           AuditService audit,
                           AuditEventRepository auditRepo,
                           AppUserRepository userRepo,
                           UserService userService,
                           WatchedDirectoryRepository watchRepo) {
        this.clamdTop = clamdTop;
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

    @GetMapping("/clamdtop")
    public String clamdtop(@RequestParam(name = "endpointId", required = false) Long endpointId,
                           Model model) {
        model.addAttribute("endpoints", endpoints.all());
        model.addAttribute("endpointId", endpointId != null ? endpointId :
                Optional.ofNullable(endpoints.defaultEndpointOrEnsure()).map(ClamdEndpoint::getId).orElse(null));
        return "pages/clamdtop";
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
            ClamdClient c = clientProvider.clientFor(ep);
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
        Map<Long,String> uploadLimits = new LinkedHashMap<>();
        for (ClamdEndpoint ep : endpoints.all()) {
            try { uploadLimits.put(ep.getId(),String.valueOf(jobs.uploadMaxBytes(ep))); }
            catch (IllegalArgumentException ex) { uploadLimits.put(ep.getId(),"Unavailable"); }
        }
        model.addAttribute("uploadLimits",uploadLimits);
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
            for (ScanJob job : created) audit.recordScan(auth, req, "SCAN_UPLOAD", "QUEUED", ep.getId(), job.getId());
            return "redirect:/jobs";
        } catch (Exception e) {
            audit.recordScan(auth, req, "SCAN_UPLOAD", "FAILED", endpointId, null);
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
            audit.recordScan(auth, req, "SCAN_PATH", "QUEUED", ep.getId(), job.getId());
            return "redirect:/scan";
        } catch (Exception e) {
            audit.recordScan(auth, req, "SCAN_PATH", "FAILED", endpointId, null);
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
        Properties safeSettings = settings.snapshot();
        safeSettings.remove("app.webhook.url");
        model.addAttribute("settings", safeSettings);
        model.addAttribute("webhookEnabled", settings.webhookEnabled());
        model.addAttribute("webhookConfigured", !settings.webhookUrl().isBlank());
        model.addAttribute("editableSettingsKeys", List.of(
            "app.allowedScanRoots", "app.upload.maxBytes", "app.concurrentScans",
            "app.storage.uploadDir", "app.storage.quarantineDir", "app.quarantine.enabled",
            "app.watch.enabled", "app.watch.pollSeconds"));
        model.addAttribute("settingsLabels", Map.of(
            "app.allowedScanRoots","Allowed server scan roots", "app.upload.maxBytes","Maximum upload size (bytes)",
            "app.concurrentScans","Concurrent scan workers", "app.storage.uploadDir","Upload storage directory",
            "app.storage.quarantineDir","Quarantine directory", "app.quarantine.enabled","Enable quarantine",
            "app.watch.enabled","Enable directory watching", "app.watch.pollSeconds","Watch polling interval (seconds)"));
        model.addAttribute("settingsHints", Map.of(
            "app.allowedScanRoots","Comma-separated paths in the Web UI container. Token path roots cannot widen these limits.",
            "app.upload.maxBytes","1 MiB = 1,048,576 bytes. Effective daemon limits may be lower.",
            "app.concurrentScans","Restart the Web UI after changing worker concurrency.",
            "app.storage.uploadDir","Keep this directory on the persistent data volume.",
            "app.storage.quarantineDir","Keep this directory on the persistent data volume.",
            "app.quarantine.enabled","Detected server files may be moved into quarantine when enabled.",
            "app.watch.enabled","This global switch controls the directories listed under Watch Directories.",
            "app.watch.pollSeconds","The watcher checks eligible directories at this interval."));
        return PAGE_SETTINGS;
    }

    @PostMapping("/admin/settings")
    public String adminSettingsSave(@RequestParam Map<String,String> params,
                                    Authentication auth,
                                    HttpServletRequest req,
                                    RedirectAttributes redirect) {
        // Allow only known keys; keep the existing secret on a blank replacement.
        Map<String,String> allowed = new HashMap<>();
        for (String key : List.of(
                "app.allowedScanRoots", "app.upload.maxBytes", "app.concurrentScans",
                "app.storage.uploadDir", "app.storage.quarantineDir", "app.quarantine.enabled",
                "app.watch.enabled", "app.watch.pollSeconds", "app.webhook.enabled")) {
            if (params.containsKey(key)) allowed.put(key, params.get(key));
        }

        boolean clearWebhook = "true".equals(params.get("app.webhook.clear"));
        String replacement = params.getOrDefault("app.webhook.url", "").trim();
        if (clearWebhook) {
            allowed.put("app.webhook.url", "");
            allowed.put("app.webhook.enabled", "false");
        } else if (!replacement.isBlank()) {
            // Preserve the legacy HTTP generic-webhook support, while requiring
            // HTTPS for Discord token-bearing webhook destinations.
            if (!isValidNotificationUrl(replacement)) {
                redirect.addFlashAttribute("errorMsg", "Invalid webhook URL. Use an absolute HTTP(S) URL; Discord requires HTTPS.");
                return "redirect:/admin/settings";
            }
            allowed.put("app.webhook.url", replacement);
        }
        String finalUrl = allowed.getOrDefault("app.webhook.url", settings.webhookUrl());
        if ("true".equals(allowed.get("app.webhook.enabled")) && finalUrl.isBlank()) {
            redirect.addFlashAttribute("errorMsg", "Enter a webhook URL before enabling notifications.");
            return "redirect:/admin/settings";
        }

        settings.updateFromMap(allowed);
        // Audit key names, never log or print the credential-bearing URL.
        audit.record(auth, req, "ADMIN_SETTINGS_UPDATE", "keys=" + allowed.keySet(), "SUCCESS", null);
        redirect.addFlashAttribute("successMsg", "Settings saved. Webhook URL is kept private.");
        return "redirect:/admin/settings";
    }

    private static boolean isValidNotificationUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || uri.getRawUserInfo() != null || uri.getFragment() != null) return false;
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return false;
            String domain = host.toLowerCase(Locale.ROOT);
            if ((domain.equals("discord.com") || domain.endsWith(".discord.com")
                    || domain.equals("discordapp.com") || domain.endsWith(".discordapp.com"))
                    && !scheme.equalsIgnoreCase("https")) return false;
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
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
    public String adminUsersReset(@PathVariable Long id,
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
    public String adminWatchCreate(@RequestParam String path,
                                   @RequestParam Long endpointId,
                                   Authentication auth,
                                   HttpServletRequest req) {
        var ep = endpoints.get(endpointId);
        var allowed = info.trizub.clamav.webclient.util.PathPolicy.requireAllowed(
                info.trizub.clamav.webclient.util.PathPolicy.normalize(path), settings.allowedRoots());
        if (!java.nio.file.Files.isDirectory(allowed)) throw new IllegalArgumentException("Watch path must be a directory");
        watchRepo.save(new info.trizub.clamav.webclient.model.WatchedDirectory(allowed.toString(), ep));
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
